package com.everything.eve.collector.location

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.everything.eve.auth.AuthManager
import com.everything.eve.collector.location.core.LocationBlockJson
import com.everything.eve.collector.location.core.LocationParams
import com.everything.eve.collector.location.core.TrackPoint
import com.everything.eve.collector.location.db.LocationPointEntity
import com.everything.eve.crypto.CryptoEnvelope
import com.everything.eve.data.EveDatabase
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 封块密封编排 instrumented 测试（阶段 4a Task 5，运行需设备/模拟器，
 * 无设备环境仅编译参与；真机冒烟并入 FU-7）。
 *
 * 覆盖 tasks.md TR-5.1 / TR-5.2：
 *  - 密封后 openLocationBlock 可逆（AAD = "eve:v1:location-block:" + blockId）；
 *  - outbox 落行且只有密文与计数；
 *  - 密封成功后明文行即删（上界批量删）；
 *  - 重复 pack 幂等（同 id INSERT OR IGNORE 命中，不重复计数）；
 *  - 24h 过期点清除（时间参数注入 nowProvider，AC-4）；
 *  - MK 缺失防御：立即 mk_unavailable，不触碰任何数据。
 *
 * 全部用例只断言计数/枚举/结构，断言失败的消息体也不含坐标明文。
 */
@RunWith(AndroidJUnit4::class)
class LocationPackagerAndroidTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var db: EveDatabase
    private lateinit var auth: AuthManager
    private lateinit var packager: LocationPackager

    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    private val blockAdapter by lazy { moshi.adapter(LocationBlockJson::class.java) }

    /** 固定测试 MK（仅内存，测试结束即弃）。 */
    private val mk = ByteArray(32) { 0x22 }

    /** 测试基准时刻（UTC 毫秒）。 */
    private val t0 = 1_800_000_000_000L

    /** 可注入的测试时钟：24h 过期场景靠修改它构造（AC-4 时间参数注入）。 */
    private var now = t0

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, EveDatabase::class.java).build()
        auth = AuthManager.create(context)
        now = t0
        packager = newPackager()
    }

    @After
    fun tearDown() {
        db.close()
    }

    /** 按测试需要构造 packager；mkOverride=null 模拟资料库未解锁。 */
    private fun newPackager(mkOverride: ByteArray? = mk) = LocationPackager(
        appContext = context,
        auth = auth,
        locationDao = db.locationDao(),
        moshi = moshi,
        masterKeyProvider = { mkOverride },
        deviceIdProvider = { "dev-test" },
        nowProvider = { now },
    )

    /** 造一个明文缓冲行（坐标为固定测试值，acc 恒合法）。 */
    private fun point(ts: Long) = LocationPointEntity(
        ts = ts, lat = 39.9, lon = 116.4, acc = 10f, createdAt = ts,
    )

    /** 解出 outbox 行密文并还原为块明文 DTO（可逆性断言的公共步骤）。 */
    private fun openBlock(cipher: ByteArray, blockId: String): LocationBlockJson {
        val plain = CryptoEnvelope.openLocationBlock(mk, cipher, blockId)
        return blockAdapter.fromJson(String(plain, Charsets.UTF_8))!!
    }

    @Test
    fun sealPack_roundtripOutboxRowAndPlaintextDeleted() = runBlocking {
        // 三个点同处 1 小时窗口内 → 单块
        db.locationDao().insert(point(t0 - 2_000))
        db.locationDao().insert(point(t0 - 1_000))
        db.locationDao().insert(point(t0))

        val result = packager.packPending()

        assertNull(result.skippedReason)
        assertEquals(0, result.expiredDropped)
        assertEquals(1, result.sealedBlocks)

        // outbox 落行：块 id 确定性派生、计数正确、密文非空
        val pending = db.locationDao().pending()
        assertEquals(1, pending.size)
        val entry = pending[0]
        assertEquals("dev-test:${t0 - 2_000}:$t0", entry.blockId)
        assertEquals(t0 - 2_000, entry.startTs)
        assertEquals(t0, entry.endTs)
        assertEquals(3, entry.pointCount)
        assertTrue(entry.cipher.isNotEmpty())

        // 密文可逆：AAD 绑块 id，解出的 JSON 结构与原始点流逐字段一致
        val block = openBlock(entry.cipher, entry.blockId)
        assertEquals("dev-test", block.deviceId)
        assertEquals(t0 - 2_000, block.startTs)
        assertEquals(t0, block.endTs)
        assertEquals(
            listOf(
                TrackPoint(ts = t0 - 2_000, lat = 39.9, lon = 116.4, acc = 10f),
                TrackPoint(ts = t0 - 1_000, lat = 39.9, lon = 116.4, acc = 10f),
                TrackPoint(ts = t0, lat = 39.9, lon = 116.4, acc = 10f),
            ),
            block.points,
        )

        // AAD 域分离：换块 id（哪怕同密钥同密文）必须认证失败
        assertThrows(Exception::class.java) {
            CryptoEnvelope.openLocationBlock(mk, entry.cipher, "dev-test:tampered")
        }

        // TR-5.2：密封成功后明文行即删
        assertTrue(db.locationDao().oldestFirst().isEmpty())
    }

    @Test
    fun repeatPack_samePoints_idempotentNoDuplicate() = runBlocking {
        db.locationDao().insert(point(t0 - 1_000))
        db.locationDao().insert(point(t0))
        val first = packager.packPending()
        assertEquals(1, first.sealedBlocks)

        // 缓冲已空：立刻重跑零产出
        val second = packager.packPending()
        assertEquals(0, second.sealedBlocks)
        assertNull(second.skippedReason)
        assertEquals(1, db.locationDao().count())

        // 模拟同一批点被重复投递（如同一位置回调重放）：
        // 同参数重封必得同块 id，outbox INSERT OR IGNORE 幂等命中
        db.locationDao().insert(point(t0 - 1_000))
        db.locationDao().insert(point(t0))
        val third = packager.packPending()
        assertEquals(0, third.sealedBlocks) // 幂等命中不重复计数
        assertNull(third.skippedReason)
        assertEquals(1, db.locationDao().count())
        assertEquals(
            "dev-test:${t0 - 1_000}:$t0",
            db.locationDao().pending()[0].blockId,
        )
        // 重投的明文同样即封即删
        assertTrue(db.locationDao().oldestFirst().isEmpty())
    }

    @Test
    fun expiredPoints_droppedBeforePacking() = runBlocking {
        // 过期点：距 now 超过 24h；新鲜点：1 秒前
        val expiredTs = now - LocationParams.POINT_EXPIRY_MS - 1
        val freshTs = now - 1_000
        db.locationDao().insert(point(expiredTs))
        db.locationDao().insert(point(freshTs))

        val result = packager.packPending()

        assertNull(result.skippedReason)
        assertEquals(1, result.expiredDropped) // 24h 红线清除
        assertEquals(1, result.sealedBlocks)

        // outbox 中只有新鲜点组成的单点块，过期点绝不入块
        val entry = db.locationDao().pending()[0]
        assertEquals("dev-test:$freshTs:$freshTs", entry.blockId)
        assertEquals(1, entry.pointCount)
        val block = openBlock(entry.cipher, entry.blockId)
        assertEquals(1, block.points.size)
        assertEquals(freshTs, block.points[0].ts)

        // 过期点与已封点全部清除
        assertTrue(db.locationDao().oldestFirst().isEmpty())
    }

    @Test
    fun multiBlock_hourSpanSplit_allSealedAndCleared() = runBlocking {
        // 两点跨度恰好 1 小时 → 开新块（BlockPacker 边界语义）
        val second = t0 + LocationParams.MAX_BLOCK_SPAN_MS
        now = second + 1_000
        db.locationDao().insert(point(t0))
        db.locationDao().insert(point(second))

        val result = packager.packPending()

        assertNull(result.skippedReason)
        assertEquals(2, result.sealedBlocks)
        // created_at 相同则顺序未定义，按集合断言两个幂等块 id
        assertEquals(
            setOf("dev-test:$t0:$t0", "dev-test:$second:$second"),
            db.locationDao().pending().map { it.blockId }.toSet(),
        )
        // 两块均可逆
        for (entry in db.locationDao().pending()) {
            assertEquals(1, openBlock(entry.cipher, entry.blockId).points.size)
        }
        // deleteUpToTs 上界=末块 endTs，明文全清
        assertTrue(db.locationDao().oldestFirst().isEmpty())
    }

    @Test
    fun mkUnavailable_skipsAndKeepsEverything() = runBlocking {
        val locked = newPackager(mkOverride = null)
        // 含一个已过 24h 的点：MK 检查在过期清理之前，缓冲必须原样保留
        db.locationDao().insert(point(now - LocationParams.POINT_EXPIRY_MS - 1))
        db.locationDao().insert(point(now - 1_000))

        val result = locked.packPending()

        assertEquals(PackSkipReason.MK_UNAVAILABLE, result.skippedReason)
        assertEquals(0, result.sealedBlocks)
        assertEquals(0, result.expiredDropped)
        // MK 缺失不动任何数据：明文保留、outbox 为空
        assertEquals(2, db.locationDao().oldestFirst().size)
        assertEquals(0, db.locationDao().count())
    }
}
