package com.everything.eve.collector

import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.everything.eve.auth.AuthManager
import com.everything.eve.collector.core.CanonicalJson
import com.everything.eve.collector.core.CollectorCursor
import com.everything.eve.collector.core.CollectorIds
import com.everything.eve.collector.core.CollectorKind
import com.everything.eve.collector.source.CallLogSource
import com.everything.eve.collector.source.ContactsSource
import com.everything.eve.collector.source.PermissionGate
import com.everything.eve.collector.source.SmsSource
import com.everything.eve.collector.source.SystemSource
import com.everything.eve.crypto.CryptoEnvelope
import com.everything.eve.data.CollectorStateDao
import com.everything.eve.data.CollectorStateEntity
import com.everything.eve.data.RecordDao
import com.everything.eve.data.RecordEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 单类单轮采集的跳过原因（持久化到 collector_state，UI/诊断可见；禁止携带业务数据）。 */
enum class SkipReason(val wireName: String) {
    /** 主密钥未解锁：跳过扫描但不应阻断同步推送（Worker 语义见 AC-6）。 */
    MK_UNAVAILABLE("mk_unavailable"),

    /** 系统权限未授予或运行中被回收。 */
    PERMISSION_DENIED("permission_denied"),

    /** 设备未配对（无 deviceId），不应进入采集流程。 */
    NOT_PAIRED("not_paired"),

    /** 系统数据源查询抛错（OEM 列缺失/provider 异常等）。 */
    SOURCE_ERROR("source_error"),
}

/**
 * 单类单轮采集结果：只含计数与枚举（零知识，UI 可安全展示）。
 * stoppedAtLimit=true 表示到达单轮预算，下轮从游标处续采。
 */
data class CollectResult(
    val kind: CollectorKind,
    val scanned: Int = 0,
    val created: Int = 0,
    val updated: Int = 0,
    val unchanged: Int = 0,
    val stoppedAtLimit: Boolean = false,
    val skippedReason: SkipReason? = null,
)

/**
 * 采集编排引擎（阶段 3 核心）：游标 → 增量读取 → 变化检测 → 密封 → 入库。
 *
 * 单轮流程（runKind）：
 *  1) 防御性前置校验：MK 未解锁 / 权限缺失 / 未配对 → 记 skipReason 后返回；
 *  2) 从 collector_state 读复合游标，按预算向 Source 增量查询；
 *  3) 对每行：确定性 recordId 查本地记录——
 *     不存在：version=1 密封新建；存在：openRecord 解密后 CanonicalJson 结构比对，
 *     相同仅推进游标，不同 version+1 重封（解密失败按"已变化"处理，宁可多封不漏变）；
 *  4) 批量 upsert（dirty=1，等待 RecordsRepository.sync 推送）；
 *  5) 持久化新游标与运行计数。
 *
 * 零知识约束：本类不输出任何日志；异常一律翻译为 SkipReason 枚举，
 * 绝不把 DTO 字段拼进异常文案。
 *
 * 可测试性：sources / masterKeyProvider / deviceIdProvider 均可注入，
 * 生产默认取 AuthManager 与真实三源（见 ServiceLocator）。
 */
class CollectorEngine(
    private val appContext: Context,
    private val auth: AuthManager,
    private val recordDao: RecordDao,
    private val stateDao: CollectorStateDao,
    private val sources: Map<CollectorKind, SystemSource<*>> = mapOf(
        CollectorKind.CONTACT to ContactsSource(),
        CollectorKind.SMS to SmsSource(),
        CollectorKind.CALLLOG to CallLogSource(),
    ),
    private val masterKeyProvider: () -> ByteArray? = { auth.masterKey },
    private val deviceIdProvider: () -> String = { auth.deviceId },
) {

    /** 单类单轮采集；所有分支都把终态写入 collector_state。 */
    suspend fun runKind(kind: CollectorKind): CollectResult = withContext(Dispatchers.IO) {
        // ---- 防御性前置校验（Worker 已按同条件过滤，这里兜底）----
        val mk = masterKeyProvider()
            ?: return@withContext persistSkip(kind, SkipReason.MK_UNAVAILABLE)
        val granted = ContextCompat.checkSelfPermission(
            appContext, PermissionGate.requiredPermission(kind),
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) return@withContext persistSkip(kind, SkipReason.PERMISSION_DENIED)
        val deviceId = deviceIdProvider()
        if (deviceId.isEmpty()) return@withContext persistSkip(kind, SkipReason.NOT_PAIRED)
        val source = sources[kind]
            ?: return@withContext persistSkip(kind, SkipReason.SOURCE_ERROR)

        // ---- 读游标 → 增量查询（异常翻译为枚举，不带业务数据）----
        val prev = stateDao.get(kind.shortName)
        var cursor = prev?.let { CollectorCursor(it.lastTimestamp, it.lastSystemId) }
            ?: CollectorCursor.INITIAL
        val entries = try {
            source.query(
                appContext.contentResolver, cursor, CollectorCursor.MAX_PER_KIND_PER_RUN,
            )
        } catch (se: SecurityException) {
            // 权限在运行中被系统回收
            return@withContext persistSkip(kind, SkipReason.PERMISSION_DENIED)
        } catch (e: Exception) {
            return@withContext persistSkip(kind, SkipReason.SOURCE_ERROR)
        }

        // ---- 逐行：变化检测 → 密封 → 待入库 ----
        val now = System.currentTimeMillis()
        var created = 0
        var updated = 0
        var unchanged = 0
        val upserts = ArrayList<RecordEntity>(entries.size)

        for (entry in entries) {
            val data = entry.data ?: continue
            val recordId = CollectorIds.recordId(deviceId, kind, entry.systemId)
            val newJson = CanonicalJson.toJsonBytesOf(data)
            val existing = recordDao.getById(recordId)

            if (existing == null) {
                val sealed = CryptoEnvelope.sealRecord(mk, newJson, recordId, kind.module, 1L)
                upserts += RecordEntity(
                    id = recordId,
                    module = kind.module,
                    type = kind.type,
                    ciphertext = CryptoEnvelope.b64(sealed),
                    version = 1,
                    // createdAt/updatedAt 仅为本地占位，推送后由服务端时间权威覆盖（FU-1）
                    createdAt = now,
                    updatedAt = now,
                    deleted = false,
                    dirty = true,
                )
                created++
            } else if (isContentSame(mk, existing, recordId, newJson)) {
                unchanged++
            } else {
                val newVersion = existing.version + 1
                val sealed = CryptoEnvelope.sealRecord(mk, newJson, recordId, kind.module, newVersion)
                upserts += existing.copy(
                    module = kind.module,
                    type = kind.type,
                    ciphertext = CryptoEnvelope.b64(sealed),
                    version = newVersion,
                    updatedAt = now,
                    dirty = true,
                )
                updated++
            }
            // 游标随已处理行单调推进（advance 内含回退防御）
            cursor = CollectorCursor.advance(cursor, entry.timestamp, entry.systemId)
        }
        if (upserts.isNotEmpty()) recordDao.upsertAll(upserts)

        // ---- 终态：新游标 + 计数落库 ----
        stateDao.upsert(
            CollectorStateEntity(
                kind = kind.shortName,
                lastTimestamp = cursor.timestamp,
                lastSystemId = cursor.systemId,
                lastRunAt = now,
                lastScannedCount = entries.size,
                lastSkipReason = null,
            ),
        )
        CollectResult(
            kind = kind,
            scanned = entries.size,
            created = created,
            updated = updated,
            unchanged = unchanged,
            stoppedAtLimit = CollectorCursor.reachedLimit(entries.size),
        )
    }

    /**
     * 与本地密文做结构相等比对：解密失败（密钥轮换/数据损坏）按"已变化"处理，
     * 让上游用当前 MK 重封修复（宁可多封，不可漏变）。
     */
    private fun isContentSame(
        mk: ByteArray,
        existing: RecordEntity,
        recordId: String,
        newJson: ByteArray,
    ): Boolean {
        val oldPlain = try {
            CryptoEnvelope.openRecord(
                mk,
                CryptoEnvelope.unb64(existing.ciphertext),
                recordId,
                existing.module,
                existing.version,
            )
        } catch (e: Exception) {
            return false
        }
        return CanonicalJson.sameContent(
            String(oldPlain, Charsets.UTF_8),
            String(newJson, Charsets.UTF_8),
        )
    }

    /** 跳过路径：保留既有游标，只更新运行时间与原因（游标不动，下轮重试同段数据）。 */
    private suspend fun persistSkip(kind: CollectorKind, reason: SkipReason): CollectResult {
        val prev = stateDao.get(kind.shortName)
        stateDao.upsert(
            CollectorStateEntity(
                kind = kind.shortName,
                lastTimestamp = prev?.lastTimestamp ?: 0L,
                lastSystemId = prev?.lastSystemId ?: 0L,
                lastRunAt = System.currentTimeMillis(),
                lastScannedCount = 0,
                lastSkipReason = reason.wireName,
            ),
        )
        return CollectResult(kind = kind, skippedReason = reason)
    }
}
