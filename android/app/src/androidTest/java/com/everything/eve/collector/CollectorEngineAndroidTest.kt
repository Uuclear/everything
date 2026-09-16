package com.everything.eve.collector

import android.content.ContentResolver
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.everything.eve.auth.AuthManager
import com.everything.eve.collector.core.CanonicalJson
import com.everything.eve.collector.core.CollectorCursor
import com.everything.eve.collector.core.CollectorKind
import com.everything.eve.collector.core.CollectorSource
import com.everything.eve.collector.core.SmsData
import com.everything.eve.collector.source.PermissionGate
import com.everything.eve.collector.source.RawEntry
import com.everything.eve.collector.source.SystemSource
import com.everything.eve.crypto.CryptoEnvelope
import com.everything.eve.data.EveDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 采集引擎 instrumented 测试（运行需设备/模拟器，并入 FU-7）。
 *
 * 覆盖 TR-4.1 编排分支：新建 / 未变 / 变化 / MK 缺失 / 权限缺失 / 游标续传。
 * 数据源用内存 FakeSource（不触碰真实系统数据），权限经 shell 显式授予。
 */
@RunWith(AndroidJUnit4::class)
class CollectorEngineAndroidTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var db: EveDatabase
    private lateinit var auth: AuthManager
    private lateinit var smsSource: FakeSource
    private lateinit var engine: CollectorEngine

    /** 固定测试 MK（仅内存，测试结束即弃）。 */
    private val mk = ByteArray(32) { 0x11 }

    /** 内存假数据源：行内容完全由测试控制；记录最近一次收到的游标供断言。 */
    private class FakeSource(
        override val kind: CollectorKind,
    ) : SystemSource<SmsData> {
        var rows: List<RawEntry<SmsData>> = emptyList()
        var queriedWith: CollectorCursor? = null

        override fun query(
            resolver: ContentResolver,
            cursor: CollectorCursor,
            limit: Int,
        ): List<RawEntry<SmsData>> {
            queriedWith = cursor
            return rows.take(limit)
        }
    }

    @Before
    fun setUp() {
        // 经 shell 显式授予三个读权限，让 PermissionGate 放行（只测编排，不测授权流）。
        val ui = InstrumentationRegistry.getInstrumentation().uiAutomation
        for (p in PermissionGate.ALL_PERMISSIONS) {
            ui.executeShellCommand("pm grant ${context.packageName} $p").close()
        }
        db = Room.inMemoryDatabaseBuilder(context, EveDatabase::class.java).build()
        auth = AuthManager.create(context)
        smsSource = FakeSource(CollectorKind.SMS)
        engine = CollectorEngine(
            appContext = context,
            auth = auth,
            recordDao = db.recordDao(),
            stateDao = db.collectorStateDao(),
            sources = mapOf(CollectorKind.SMS to smsSource),
            masterKeyProvider = { mk },
            deviceIdProvider = { "dev-test" },
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    /** 构造一条短信假行（address 固定，测试只关心结构与版本语义）。 */
    private fun smsRow(id: Long, ts: Long, body: String) = RawEntry(
        systemId = id,
        timestamp = ts,
        data = SmsData(
            address = "10086",
            body = body,
            date = ts,
            type = "inbox",
            read = false,
            threadId = null,
            source = CollectorSource(systemId = id, lastUpdated = ts),
        ),
    )

    @Test
    fun firstRun_createsSealedRecordsAndPersistsCursor() = runBlocking {
        smsSource.rows = listOf(smsRow(1, 1000, "alpha"), smsRow(2, 2000, "beta"))

        val result = engine.runKind(CollectorKind.SMS)

        assertNull(result.skippedReason)
        assertEquals(2, result.scanned)
        assertEquals(2, result.created)
        assertEquals(0, result.updated)
        assertEquals(0, result.unchanged)

        // 记录确定性 id、module/type、version=1、dirty 待推送
        val rec = db.recordDao().getById("dev-test:sms:1")
        assertNotNull(rec)
        rec!!
        assertEquals("sms", rec.module)
        assertEquals("sms", rec.type)
        assertEquals(1L, rec.version)
        assertTrue(rec.dirty)

        // 信封可用 MK 逆解，且明文与源 DTO 结构一致（TR-4.2：AAD=记录自身 module）
        val plain = CryptoEnvelope.openRecord(
            mk, CryptoEnvelope.unb64(rec.ciphertext), rec.id, rec.module, rec.version,
        )
        assertTrue(
            CanonicalJson.sameContent(
                String(plain, Charsets.UTF_8),
                String(CanonicalJson.toJsonBytesOf(smsSource.rows[0].data), Charsets.UTF_8),
            ),
        )

        // 游标推进到最后一行 (2000, 2)，状态表可回读
        val state = db.collectorStateDao().get("sms")
        assertNotNull(state)
        state!!
        assertEquals(2000L, state.lastTimestamp)
        assertEquals(2L, state.lastSystemId)
        assertNull(state.lastSkipReason)
    }

    @Test
    fun secondRun_unchangedKeepsVersionAndPassesCursor() = runBlocking {
        smsSource.rows = listOf(smsRow(1, 1000, "alpha"), smsRow(2, 2000, "beta"))
        engine.runKind(CollectorKind.SMS)

        // 第二轮完全相同的系统行：不应产生任何新版本
        val again = engine.runKind(CollectorKind.SMS)
        assertEquals(2, again.unchanged)
        assertEquals(0, again.created)
        assertEquals(0, again.updated)
        // 第二轮查询收到的是上轮持久化的游标
        assertEquals(CollectorCursor(2000L, 2L), smsSource.queriedWith)
        // version 保持 1
        assertEquals(1L, db.recordDao().getById("dev-test:sms:1")!!.version)
    }

    @Test
    fun changedRow_bumpsVersionAndReseals() = runBlocking {
        smsSource.rows = listOf(smsRow(1, 1000, "alpha"))
        engine.runKind(CollectorKind.SMS)

        // 系统行内容变化（同 _id，时间戳前进）
        smsSource.rows = listOf(smsRow(1, 3000, "alpha-edited"))
        val result = engine.runKind(CollectorKind.SMS)

        assertEquals(1, result.updated)
        val rec = db.recordDao().getById("dev-test:sms:1")!!
        assertEquals(2L, rec.version)
        assertTrue(rec.dirty)
        // 新密文按 version=2 的 AAD 可解，内容为更新后 DTO
        val plain = CryptoEnvelope.openRecord(
            mk, CryptoEnvelope.unb64(rec.ciphertext), rec.id, rec.module, rec.version,
        )
        assertTrue(String(plain, Charsets.UTF_8).contains("alpha-edited"))
    }

    @Test
    fun missingMasterKey_skipsWithoutQueryingSource() = runBlocking {
        val locked = CollectorEngine(
            appContext = context,
            auth = auth,
            recordDao = db.recordDao(),
            stateDao = db.collectorStateDao(),
            sources = mapOf(CollectorKind.SMS to smsSource),
            masterKeyProvider = { null }, // 模拟未解锁
            deviceIdProvider = { "dev-test" },
        )
        smsSource.rows = listOf(smsRow(1, 1000, "alpha"))

        val result = locked.runKind(CollectorKind.SMS)

        assertEquals(SkipReason.MK_UNAVAILABLE, result.skippedReason)
        // MK 缺失绝不触碰数据源（TR-5.2 引擎侧防御）
        assertNull(smsSource.queriedWith)
        // skipReason 落库，游标保持 0
        val state = db.collectorStateDao().get("sms")!!
        assertEquals("mk_unavailable", state.lastSkipReason)
        assertEquals(0L, state.lastTimestamp)
    }

    @Test
    fun revokedPermission_skipsWithReason() = runBlocking {
        val ui = InstrumentationRegistry.getInstrumentation().uiAutomation
        ui.executeShellCommand(
            "pm revoke ${context.packageName} android.permission.READ_SMS",
        ).close()
        try {
            smsSource.rows = listOf(smsRow(1, 1000, "alpha"))
            val result = engine.runKind(CollectorKind.SMS)
            assertEquals(SkipReason.PERMISSION_DENIED, result.skippedReason)
            assertNull(smsSource.queriedWith)
        } finally {
            // 复原授权，避免影响同进程其他用例
            ui.executeShellCommand(
                "pm grant ${context.packageName} android.permission.READ_SMS",
            ).close()
        }
    }

    @Test
    fun unmappedKind_reportsSourceError() = runBlocking {
        // engine 只注册了 SMS；采集 CONTACT 走 SOURCE_ERROR 分支（不查系统）。
        val result = engine.runKind(CollectorKind.CONTACT)
        assertEquals(SkipReason.SOURCE_ERROR, result.skippedReason)
    }
}
