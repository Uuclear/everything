package com.everything.eve.collector.location

import android.content.Context
import com.everything.eve.ServiceLocator
import com.everything.eve.api.EveApi
import com.everything.eve.api.LocationBatchRequest
import com.everything.eve.api.LocationBlockDto
import com.everything.eve.collector.CollectorSettings
import com.everything.eve.collector.location.db.LocationDao
import com.everything.eve.collector.location.db.LocationOutboxEntity
import com.everything.eve.crypto.CryptoEnvelope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import retrofit2.HttpException
import java.io.IOException

/**
 * 轨迹块上行的失败/跳过类别（阶段 4a Task 7）。
 *
 * 零知识红线（spec NFR-1）：对外只暴露枚举 wireName，
 * 任何异常一律翻译为枚举，绝不把坐标/密文/块 id 拼进文案或异常消息。
 */
enum class UploadSkipReason(val wireName: String) {
    /** 网络异常（断网/超时）或 5xx：留队待下轮（退避由 WorkManager 周期窗口天然提供）。 */
    NETWORK_ERROR("network_error"),

    /** 服务端 4xx 校验拒绝：留队记原因；attempts 递增，达上限转 give_up，不无限重试同一坏块。 */
    REJECTED_4XX("rejected_4xx"),

    /** attempts 达 [LocationUploader.MAX_ATTEMPTS] 的坏块：跳过不删不丢，待真机/排查。 */
    GIVE_UP("give_up"),
}

/**
 * 单轮上行结果：只含计数与枚举（NFR-1，UI/诊断可安全展示）。
 *
 * @property uploaded 本轮成功上行并删队的块数
 * @property deferred 本轮失败留队（attempts+1）的块数
 * @property giveUp   attempts 达上限被跳过的坏块数（不删不丢）
 * @property lastError 本轮最近一次失败类别；null 表示全成功或队列空
 */
data class UploadOutcome(
    val uploaded: Int = 0,
    val deferred: Int = 0,
    val giveUp: Int = 0,
    val lastError: UploadSkipReason? = null,
)

/**
 * 位置轨迹上行编排（阶段 4a Task 7）：密文 outbox → 服务端月表。
 *
 * 单轮流程（[tryUpload]）：
 *  1) outbox `pending()` 全取（按入队时间升序，先入队先传）；
 *  2) attempts ≥ [MAX_ATTEMPTS] 的坏块直接跳过记 give_up——不删不丢，
 *     阻断"同一坏块无限重试"（tasks.md Task 7 权威语义）；
 *  3) 余下按 ≤[MAX_BATCH_SIZE] 块/批切片顺次上行：
 *     - 单批成功 → `delete(该批 blockIds)` 出队，并刷新"上次上传时间"；
 *     - 4xx 校验拒绝 → 该批 `attempts+1` 记 [UploadSkipReason.REJECTED_4XX] 留队，
 *       继续下一批（服务端逐块校验遇错整批 400，坏批不阻塞后续好批）；
 *     - 网络异常 / 5xx → 该批 `attempts+1` 记 [UploadSkipReason.NETWORK_ERROR] 留队
 *       并中止本轮：网络故障时后续批次几乎必然同败，继续打只会把好块的
 *       attempts 误推向上限；中止后由 WorkManager 15 分钟周期窗口提供天然退避。
 *
 * 幂等性：块 id 全链路确定性派生（"{deviceId}:{startTs}:{endTs}"），
 * 服务端 INSERT OR IGNORE 同 id 幂等；上行成功后本地才删队，
 * "传到一半崩溃"最坏结果是服务端已有、本地重传一轮计 skipped，绝不丢块。
 *
 * 零知识约束：本类不输出任何日志；结果模型只含计数/枚举；
 * outbox 行只有密文与计数，cipher ByteArray → base64 是唯一的转码点。
 *
 * 可测试性：apiProvider / nowProvider 可注入（instrumented 用例以 FakeApi 驱动
 * 成功/网络失败/4xx 分支）。
 */
class LocationUploader(
    private val appContext: Context,
    private val locationDao: LocationDao,
    private val apiProvider: () -> EveApi = { ServiceLocator.api },
    private val nowProvider: () -> Long = { System.currentTimeMillis() },
) {

    companion object {
        /** 单批最多块数（与服务端 maxLocationBlocksPerBatch=50 契约一致）。 */
        const val MAX_BATCH_SIZE = 50

        /** 坏块判定阈值：attempts 达 8 记 give_up 跳过（不删不丢，tasks.md Task 7）。 */
        const val MAX_ATTEMPTS = 8
    }

    /**
     * 执行一轮上行：把 outbox 中可传的密文块全部推送服务端并删队。
     *
     * 不依赖 MK（outbox 只存密文，上行无需解密），因此 Worker 兜底路径无条件调用。
     * 协程取消原样上抛（不算失败）；其余任何异常翻译为枚举留队。
     */
    suspend fun tryUpload(): UploadOutcome = withContext(Dispatchers.IO) {
        val pending = locationDao.pending()
        if (pending.isEmpty()) return@withContext UploadOutcome()

        // ---- 坏块隔离：attempts 达上限的块跳过记 give_up（不删不丢）----
        val eligible = pending.filter { it.attempts < MAX_ATTEMPTS }
        val giveUp = pending.size - eligible.size

        var uploaded = 0
        var deferred = 0
        var lastError: UploadSkipReason? = null

        for (batch in eligible.chunked(MAX_BATCH_SIZE)) {
            when (val failure = uploadBatch(batch)) {
                null -> {
                    // 单批成功 → 该批出队（成功才删，是"不丢块"的幂等锚点）
                    locationDao.delete(batch.map { it.blockId })
                    uploaded += batch.size
                    CollectorSettings.setLocationLastUploadAt(appContext, nowProvider())
                }

                UploadSkipReason.REJECTED_4XX -> {
                    // 4xx：该批留队 attempts+1，继续下一批（坏批不阻塞好批）
                    locationDao.incrementAttempts(batch.map { it.blockId })
                    deferred += batch.size
                    lastError = UploadSkipReason.REJECTED_4XX
                }

                UploadSkipReason.NETWORK_ERROR -> {
                    // 网络/5xx：该批留队 attempts+1 并中止本轮——后续批次几乎必然
                    // 同败，继续只会把好块 attempts 误推向 give_up 上限；
                    // 剩余未尝试批次的 attempts 不动，等下个周期窗口重试
                    locationDao.incrementAttempts(batch.map { it.blockId })
                    deferred += batch.size
                    lastError = UploadSkipReason.NETWORK_ERROR
                    break
                }

                else -> break // GIVE_UP 不会由 uploadBatch 返回，防御性兜底
            }
        }

        // 本轮没有网络/4xx 失败但有坏块被跳过：把 give_up 作为结果类别透出（UI 可提示"待排查"）
        if (lastError == null && giveUp > 0) lastError = UploadSkipReason.GIVE_UP

        UploadOutcome(
            uploaded = uploaded,
            deferred = deferred,
            giveUp = giveUp,
            lastError = lastError,
        )
    }

    /**
     * 上行单批：成功返回 null；失败翻译为 [UploadSkipReason] 枚举（不抛）。
     * 组包是唯一的转码点：cipher ByteArray → base64 字符串（服务端逐字段契约）。
     */
    private suspend fun uploadBatch(batch: List<LocationOutboxEntity>): UploadSkipReason? {
        val req = LocationBatchRequest(
            blocks = batch.map {
                LocationBlockDto(
                    id = it.blockId,
                    startTs = it.startTs,
                    endTs = it.endTs,
                    pointCount = it.pointCount,
                    cipher = CryptoEnvelope.b64(it.cipher),
                )
            },
        )
        return try {
            apiProvider().uploadLocationBlocks(req)
            null
        } catch (ce: CancellationException) {
            // 协程取消不是失败：如实上抛，不翻译为枚举（该批 attempts 不动）
            throw ce
        } catch (he: HttpException) {
            // 4xx 校验拒绝与 5xx 服务器故障分流：前者留队记原因继续下一批，
            // 后者按网络类留队中止本轮
            if (he.code() in 400..499) UploadSkipReason.REJECTED_4XX
            else UploadSkipReason.NETWORK_ERROR
        } catch (io: IOException) {
            // 断网 / 超时 / TLS 失败等传输层异常
            UploadSkipReason.NETWORK_ERROR
        } catch (e: Exception) {
            // 其余异常（序列化/未知）：按可重试网络类留队，等下轮窗口；
            // 异常消息绝不上抛到结果模型（可能携带实现细节，NFR-1 保守翻译）
            UploadSkipReason.NETWORK_ERROR
        }
    }
}
