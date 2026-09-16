package com.everything.eve.collector.location

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.everything.eve.api.EveApi
import com.everything.eve.api.LocationBatchRequest
import com.everything.eve.api.LocationBatchResult
import com.everything.eve.collector.CollectorSettings
import com.everything.eve.collector.location.db.LocationOutboxEntity
import com.everything.eve.crypto.CryptoEnvelope
import com.everything.eve.data.EveDatabase
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import retrofit2.HttpException
import java.io.IOException
import java.lang.reflect.Proxy

/**
 * 轨迹上行编排 instrumented 测试（阶段 4a Task 7，运行需设备/模拟器，
 * 无设备环境仅编译参与；真机冒烟并入 FU-7）。
 *
 * 覆盖 tasks.md TR-7.1：
 *  - >50 块按 ≤50 块/批切片，全部成功后删队；
 *  - 网络失败：该批留队 attempts+1 并中止本轮（后续批次 attempts 不动）；
 *  - 4xx 校验拒绝：该批留队 attempts+1，继续下一批（坏批不阻塞好批）；
 *  - 5xx 按网络类处理（留队中止）；
 *  - attempts≥8 坏块记 give_up 跳过：不删不丢，attempts 不再增长；
 *  - 空队列零动作。
 *
 * EveApi 用动态代理伪造（接口方法多，仅拦截 uploadLocationBlocks）。
 * 全部断言只针对计数/枚举/幂等 id/批量大小，断言消息体不含坐标明文（NFR-1）。
 */
@RunWith(AndroidJUnit4::class)
class LocationUploaderAndroidTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var db: EveDatabase
    private lateinit var fakeApi: FakeApi
    private lateinit var uploader: LocationUploader

    /** 测试基准时刻（UTC 毫秒）。 */
    private val t0 = 1_800_000_000_000L

    /** 可注入的测试时钟：上行成功时间戳断言依赖它。 */
    private var now = t0

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, EveDatabase::class.java).build()
        fakeApi = FakeApi()
        now = t0
        uploader = LocationUploader(
            appContext = context,
            locationDao = db.locationDao(),
            apiProvider = { fakeApi.proxy },
            nowProvider = { now },
        )
    }

    @After
    fun tearDown() {
        // 清理成功路径写入的"上次上传时间"，不污染应用 SharedPreferences
        CollectorSettings.setLocationLastUploadAt(context, 0L)
        db.close()
    }

    // ---- 测试道具 ----

    /** 造一个 outbox 行：幂等块 id 按真实派生格式，cipher 为固定测试字节（内容无关）。 */
    private fun outboxRow(seq: Int, attempts: Int = 0): LocationOutboxEntity {
        val start = t0 + seq * 2_000L
        return LocationOutboxEntity(
            blockId = "dev-test:$start:${start + 1_000}",
            startTs = start,
            endTs = start + 1_000,
            pointCount = 3,
            cipher = byteArrayOf(0x01, 0x02, 0x03),
            createdAt = t0 + seq, // 严格升序：pending() 顺序确定，批次切片可断言
            attempts = attempts,
        )
    }

    /** 批量入队 [count] 个块（seq 从 0 递增）。 */
    private suspend fun enqueue(count: Int, attempts: Int = 0) {
        repeat(count) { db.locationDao().upsertIgnore(outboxRow(it, attempts)) }
    }

    /** 构造一个携带指定 HTTP 状态码的 HttpException（4xx/5xx 分支驱动）。 */
    private fun httpError(code: Int): HttpException = HttpException(
        retrofit2.Response.error<LocationBatchResult>(
            code, "{}".toResponseBody("application/json".toMediaType()),
        ),
    )

    // ---- TR-7.1 用例 ----

    @Test
    fun moreThanBatchSize_splitsInto50Chunks_andDeletesAllOnSuccess() = runBlocking {
        // 120 块 → 应切 50/50/20 三批
        enqueue(120)

        val outcome = uploader.tryUpload()

        assertEquals(120, outcome.uploaded)
        assertEquals(0, outcome.deferred)
        assertEquals(0, outcome.giveUp)
        assertNull(outcome.lastError)

        // 批次切片：恰好 3 批，大小 50/50/20，每批 ≤ MAX_BATCH_SIZE
        assertEquals(listOf(50, 50, 20), fakeApi.batches.map { it.blocks.size })
        assertTrue(fakeApi.batches.all { it.blocks.size <= LocationUploader.MAX_BATCH_SIZE })

        // DTO 逐字段对齐（首块抽查）：五字段 + cipher base64 转码点
        val dto = fakeApi.batches[0].blocks[0]
        val row = outboxRow(0)
        assertEquals(row.blockId, dto.id)
        assertEquals(row.startTs, dto.startTs)
        assertEquals(row.endTs, dto.endTs)
        assertEquals(row.pointCount, dto.pointCount)
        assertEquals(CryptoEnvelope.b64(row.cipher), dto.cipher)

        // 成功才删队：outbox 清空，且记录了上次上传时间
        assertEquals(0, db.locationDao().count())
        assertEquals(now, CollectorSettings.locationLastUploadAt(context))
    }

    @Test
    fun networkError_batchStaysWithAttemptsIncremented_andAbortsRound() = runBlocking {
        // 60 块 = 2 批；第一批网络失败应中止本轮：第二批 attempts 不动
        enqueue(60)
        fakeApi.responder = { throw IOException("simulated transport failure") }

        val outcome = uploader.tryUpload()

        assertEquals(0, outcome.uploaded)
        assertEquals(50, outcome.deferred) // 仅第一批计失败留队
        assertEquals(0, outcome.giveUp)
        assertEquals(UploadSkipReason.NETWORK_ERROR, outcome.lastError)

        // 全部留队不丢：第一批 attempts=1，未尝试的第二批 attempts=0
        val pending = db.locationDao().pending()
        assertEquals(60, pending.size)
        assertEquals(50, pending.count { it.attempts == 1 })
        assertEquals(10, pending.count { it.attempts == 0 })
        // 失败路径不写上传时间
        assertEquals(0L, CollectorSettings.locationLastUploadAt(context))
    }

    @Test
    fun serverError5xx_treatedAsNetworkError() = runBlocking {
        enqueue(1)
        fakeApi.responder = { throw httpError(500) }

        val outcome = uploader.tryUpload()

        assertEquals(0, outcome.uploaded)
        assertEquals(1, outcome.deferred)
        assertEquals(UploadSkipReason.NETWORK_ERROR, outcome.lastError)
        assertEquals(1, db.locationDao().pending()[0].attempts)
    }

    @Test
    fun rejected4xx_batchStaysWithAttemptsIncremented_andContinuesNextBatch() = runBlocking {
        // 60 块 = 2 批；第一批 400 拒绝，第二批成功——坏批不阻塞好批
        enqueue(60)
        var calls = 0
        fakeApi.responder = {
            calls++
            if (calls == 1) throw httpError(400)
            LocationBatchResult(applied = it.blocks.size, skipped = 0)
        }

        val outcome = uploader.tryUpload()

        assertEquals(10, outcome.uploaded) // 第二批成功删队
        assertEquals(50, outcome.deferred) // 第一批留队
        assertEquals(0, outcome.giveUp)
        assertEquals(UploadSkipReason.REJECTED_4XX, outcome.lastError)
        assertEquals(2, fakeApi.batches.size) // 两批都被尝试

        // 只剩第一批 50 块留队，attempts 全为 1；好批已删
        val pending = db.locationDao().pending()
        assertEquals(50, pending.size)
        assertTrue(pending.all { it.attempts == 1 })
        // 有成功批次 → 上次上传时间已刷新
        assertEquals(now, CollectorSettings.locationLastUploadAt(context))
    }

    @Test
    fun giveUp_badBlockSkippedNotDeleted_andAttemptsUntouched() = runBlocking {
        // 1 个 attempts=8 坏块 + 1 个正常块：坏块跳过不删不丢，正常块照常上行
        db.locationDao().upsertIgnore(outboxRow(0, attempts = LocationUploader.MAX_ATTEMPTS))
        db.locationDao().upsertIgnore(outboxRow(1))

        val outcome = uploader.tryUpload()

        assertEquals(1, outcome.uploaded)
        assertEquals(0, outcome.deferred)
        assertEquals(1, outcome.giveUp)
        assertEquals(UploadSkipReason.GIVE_UP, outcome.lastError)
        // 坏块不参与上行：只发了 1 批且只含好块
        assertEquals(listOf(listOf(outboxRow(1).blockId)), fakeApi.batches.map { b -> b.blocks.map { it.id } })

        // 坏块原样留队：attempts 不再增长（阻断无限重试），块不丢
        val pending = db.locationDao().pending()
        assertEquals(1, pending.size)
        assertEquals(outboxRow(0).blockId, pending[0].blockId)
        assertEquals(LocationUploader.MAX_ATTEMPTS, pending[0].attempts)
    }

    @Test
    fun emptyQueue_noopOutcome() = runBlocking {
        val outcome = uploader.tryUpload()

        assertEquals(0, outcome.uploaded)
        assertEquals(0, outcome.deferred)
        assertEquals(0, outcome.giveUp)
        assertNull(outcome.lastError)
        assertTrue(fakeApi.batches.isEmpty()) // 零网络动作
    }

    // ---- FakeApi ----

    /**
     * 可编程 EveApi 假实现：动态代理仅拦截 uploadLocationBlocks
     * （suspend 函数经代理调用时，直接返回结果对象即走"未挂起"快路径，
     * 抛异常则如实传播给调用方）；其余端点调用即失败，防误用。
     */
    private class FakeApi {
        /** 已收到的批次快照（用于断言批量大小/批次数/块 id 幂等性）。 */
        val batches = mutableListOf<LocationBatchRequest>()

        /** 每批的响应行为；默认全部成功（applied=块数）。 */
        var responder: (LocationBatchRequest) -> LocationBatchResult =
            { LocationBatchResult(applied = it.blocks.size, skipped = 0) }

        val proxy: EveApi = Proxy.newProxyInstance(
            EveApi::class.java.classLoader,
            arrayOf(EveApi::class.java),
        ) { _, method, args ->
            if (method.name == "uploadLocationBlocks") {
                val req = args[0] as LocationBatchRequest
                batches.add(req)
                responder(req)
            } else {
                throw UnsupportedOperationException("FakeApi 未实现端点：${method.name}")
            }
        } as EveApi
    }
}
