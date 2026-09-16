package com.everything.eve.collector.location

import android.content.Context
import com.everything.eve.auth.AuthManager
import com.everything.eve.collector.location.core.BlockPacker
import com.everything.eve.collector.location.core.LocationBlockJson
import com.everything.eve.collector.location.core.LocationParams
import com.everything.eve.collector.location.core.TrackPoint
import com.everything.eve.collector.location.db.LocationDao
import com.everything.eve.collector.location.db.LocationOutboxEntity
import com.everything.eve.collector.location.db.LocationPointEntity
import com.everything.eve.crypto.CryptoEnvelope
import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.Moshi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 封块编排的跳过/失败原因（阶段 4a）。
 *
 * 零知识红线（spec FR-4 / NFR-1）：对外只暴露枚举 wireName，
 * 任何异常一律翻译为枚举，绝不把坐标等业务数据拼进文案。
 */
enum class PackSkipReason(val wireName: String) {
    /** 主密钥未解锁：本轮不封块（防御分支，调用方已按同条件拦截）。 */
    MK_UNAVAILABLE("mk_unavailable"),

    /** 读库 / 序列化 / 密封 / 写 outbox 任一环节抛错：本轮中止，明文留待下轮重试。 */
    PACK_ERROR("pack_error"),
}

/**
 * 单轮封块结果：只含计数与枚举（NFR-1，UI/诊断可安全展示）。
 *
 * @property sealedBlocks 本轮新密封并成功入队 outbox 的块数
 *            （同块 id 幂等命中——upsertIgnore 行数=0——不重复计数）。
 * @property expiredDropped 本轮按 24h 过期红线清除的明文点数（FR-4）。
 * @property skippedReason 非 null 表示本轮未正常完成（原因见枚举）。
 */
data class PackResult(
    val sealedBlocks: Int = 0,
    val expiredDropped: Int = 0,
    val skippedReason: PackSkipReason? = null,
)

/**
 * 位置轨迹封块密封编排（阶段 4a Task 5）：明文缓冲 → 密文 outbox。
 *
 * 单轮流程（[packPending]）：
 *  1) 防御性前置校验：MK 未解锁 → 立即返回 mk_unavailable（不动任何数据）；
 *  2) 24h 过期红线：`deleteExpired(now - POINT_EXPIRY_MS)` 清除滞留明文；
 *  3) `oldestFirst()` 全量取出剩余缓冲点 → [BlockPacker.pack] 分块；
 *  4) 逐块：moshi 序列化（UTF-8）→ [CryptoEnvelope.sealLocationBlock]
 *     （XChaCha20-Poly1305，AAD 绑块 id）→ outbox `upsertIgnore`
 *     （INSERT OR IGNORE：行数=0 说明同 id 已在队，即同参数重复封块的
 *     幂等命中，不重复计数）；
 *  5) 全部块密封成功后：`deleteUpToTs(块覆盖的最大 ts)` 批量删除明文
 *     （封块输入即全量 oldestFirst，该 ts 之前的点必然已全部入块，
 *     上界删除天然安全，tasks.md Task 5 Notes）。
 *
 * 零知识约束：本类不输出任何日志；异常一律翻译为 [PackSkipReason] 枚举；
 * 坐标明文只经"Room 行 → 内存 DTO → 序列化字节 → 密封"流转，
 * 绝不进入日志 / 异常消息 / SharedPreferences。
 *
 * 可测试性：masterKeyProvider / deviceIdProvider / nowProvider 均可注入
 * （nowProvider 供 AC-4 的 24h 过期场景做时间参数注入）；
 * 生产默认取 AuthManager 与系统时钟（见 ServiceLocator）。
 */
class LocationPackager(
    private val appContext: Context,
    private val auth: AuthManager,
    private val locationDao: LocationDao,
    moshi: Moshi,
    private val masterKeyProvider: () -> ByteArray? = { auth.masterKey },
    private val deviceIdProvider: () -> String = { auth.deviceId },
    private val nowProvider: () -> Long = { System.currentTimeMillis() },
) {

    /** 块明文 JSON 适配器（字段名为三端契约，见 TrackPoint.kt 头注）。 */
    private val blockAdapter: JsonAdapter<LocationBlockJson> =
        moshi.adapter(LocationBlockJson::class.java)

    /**
     * 执行一轮封块：把明文缓冲中已可结算的点全部密封入队 outbox 并删除明文。
     *
     * 幂等性：同设备同一批点重复触发本函数必然派生同块 id，
     * outbox INSERT OR IGNORE 去重，重复调用不产生重复块（AC-3）。
     */
    suspend fun packPending(): PackResult = withContext(Dispatchers.IO) {
        // ---- 防御性前置校验：MK 缺失立即返回（调用方已按同条件拦截，这里兜底）----
        val mk = masterKeyProvider()
            ?: return@withContext PackResult(skippedReason = PackSkipReason.MK_UNAVAILABLE)
        try {
            packWithKey(mk)
        } catch (ce: CancellationException) {
            // 协程取消不是失败：如实上抛，不翻译为枚举
            throw ce
        } catch (e: Exception) {
            // 任何异常只记枚举原因；明文未删（deleteUpToTs 是最后一步），
            // 下轮重封同 id 幂等命中，不产生重复块
            PackResult(skippedReason = PackSkipReason.PACK_ERROR)
        }
    }

    /** 密封 + 入队 + 删明文主流程（进入本函数时 MK 已确认非空）。 */
    private suspend fun packWithKey(mk: ByteArray): PackResult {
        val now = nowProvider()

        // ---- 1) 24h 过期红线：超时未加密的明文点强制清除（FR-4）----
        val expiredDropped = locationDao.deleteExpired(now - LocationParams.POINT_EXPIRY_MS)

        // ---- 2) 全量取出剩余缓冲点并分块 ----
        val rows = locationDao.oldestFirst()
        if (rows.isEmpty()) {
            return PackResult(sealedBlocks = 0, expiredDropped = expiredDropped)
        }
        val blocks = BlockPacker.pack(deviceIdProvider(), rows.map { it.toTrackPoint() })

        // ---- 3) 逐块：序列化 → 密封 → 幂等入队 ----
        var sealedBlocks = 0
        for (block in blocks) {
            val blockId = BlockPacker.blockId(block.deviceId, block.startTs, block.endTs)
            val plainBytes = blockAdapter.toJson(block).toByteArray(Charsets.UTF_8)
            val sealed = CryptoEnvelope.sealLocationBlock(mk, plainBytes, blockId)
            val inserted = locationDao.upsertIgnore(
                LocationOutboxEntity(
                    blockId = blockId,
                    startTs = block.startTs,
                    endTs = block.endTs,
                    pointCount = block.points.size,
                    cipher = sealed,
                    createdAt = now,
                ),
            )
            // 行数=0：同块 id 已在队（幂等命中），不重复计数
            if (inserted == 1) sealedBlocks++
        }

        // ---- 4) 密封全部成功后按"块覆盖的最大 ts"上界批量删明文（TR-5.1 Notes）----
        locationDao.deleteUpToTs(blocks.last().endTs)
        return PackResult(sealedBlocks = sealedBlocks, expiredDropped = expiredDropped)
    }

    /** Room 明文行 → 封块输入 DTO（丢弃本地管理列 id / created_at）。 */
    private fun LocationPointEntity.toTrackPoint(): TrackPoint = TrackPoint(
        ts = ts,
        lat = lat,
        lon = lon,
        acc = acc,
        speed = speed,
        bearing = bearing,
        altitude = altitude,
        provider = provider,
    )
}
