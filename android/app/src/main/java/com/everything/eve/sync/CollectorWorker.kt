package com.everything.eve.sync

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.everything.eve.ServiceLocator
import com.everything.eve.collector.CollectorSettings
import com.everything.eve.collector.core.CollectorKind
import com.everything.eve.reminder.ReminderScheduler
import java.time.Duration

/**
 * 阶段 3 统一后台任务：先采集（仅用户启用的类别），再同步推送。
 *
 * doWork 顺序（spec FR-9 / AC-6）：
 *  1) 对每个"已启用"类别调 CollectorEngine.runKind——权限缺失 / MK 未解锁 /
 *     未配对由引擎记 skipReason 后跳过（MK 缺失时绝不触碰数据源与密封逻辑）；
 *  2) 无条件 repo.sync()：推送的均是已加密 dirty 记录，不依赖 MK；
 *  3) 阶段 4a Task 7 兜底：MK 非空先 packPending() 封块，再无条件 tryUpload()
 *     上行——复用本周期窗口不新增唤醒，失败块留队下轮重试；
 *  4) 单类采集异常不阻断其他类与同步；同步失败按可恢复错误指数退避重试。
 */
class CollectorWorker(appContext: Context, params: WorkerParameters) :
    CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        for (kind in CollectorKind.entries) {
            if (!CollectorSettings.isEnabled(ctx, kind)) continue
            try {
                ServiceLocator.collector.runKind(kind)
            } catch (e: Exception) {
                // 引擎内部已把可预期异常降级为 skipReason；此处兜底防御，
                // 保证单类异常不影响其余类别与同步推送。
            }
        }
        return try {
            ServiceLocator.repo.sync()
            // ---- 阶段 4a Task 7 兜底：封块 + 上行（复用 15 分钟周期窗口）----
            // MK 非空才封块（封块需要 MK 加密明文）；上行不依赖 MK（outbox
            // 只有密文）故无条件调用。异常吞掉不阻断 Result.success()——
            // 对齐"单类采集异常不阻断"的既有模式，失败块留队下轮窗口重试。
            try {
                if (ServiceLocator.auth.masterKey != null) {
                    ServiceLocator.locationPackager.packPending()
                }
                ServiceLocator.locationUploader.tryUpload()
            } catch (e: Exception) {
                // 封块/上行异常不阻断 Worker 成功语义（留队重试兜底）
            }
            // ---- 阶段 4b / TR-10.2 挂载点：事件模块拉取 + 闹钟链重建 ----
            // 严格 try-catch 包裹：不破坏 4a 既有同步链路（place/locations 等同步照常）；
            // 事件模块解密失败 / Room 异常 / ReminderScheduler 异常均不抛出此 Worker。
            // 顺序：先拉取事件密文 → 解密 → upsert 到 event 表 → 再 rebuildChain
            // 让闹钟链头对准下一触发点。
            try {
                if (ServiceLocator.auth.masterKey != null) {
                    // 增量游标：从 records 表取最大 updatedAt 作 since；
                    // 4a RecordsRepository 已有此 DAO 方法（无需新暴露）。
                    val sinceMs = ServiceLocator.db.recordDao().maxUpdatedAt()
                    ServiceLocator.eventsRepo.pullAndDecrypt(sinceMs)
                    ReminderScheduler.rebuildChain(ctx)
                }
            } catch (t: Throwable) {
                // 事件模块同步失败 / 闹钟重建失败不影响 Worker 整体成功（与
                // locationPackager.exception 兜底同模式；spec NFR-4 单闹钟
                // 链式约束对单次失败容忍度极高，留队下轮再试）。
                Log.w("SyncWorker", "events sync failed", t)
            }
            // ---- 阶段 5 / TR-11.2 挂载点：财务模块拉取 + 闹钟链重建 ----
            // 严格 try-catch 包裹：不破坏 4a/4b 既有同步链路；财务模块解密
            // 失败 / Room 异常 / ReminderScheduler 异常均不抛出此 Worker。
            // 顺序：先拉取 finance 模块远端 records → 解密 → upsert 到
            // account/card/tx 表 → 再 rebuildChain 让账单日/还款日闹钟对齐。
            // 复用 records 表 maxUpdatedAt 作 since 增量游标（同一 records
            // 表记录所有模块，避免新增同步游标字段）。
            try {
                if (ServiceLocator.auth.masterKey != null) {
                    val sinceMs = ServiceLocator.db.recordDao().maxUpdatedAt()
                    // 拉 records → 过滤 module="finance" → 走带参 pullAndDecrypt
                    val financeRecords = ServiceLocator.repo.listRecordsAfter(sinceMs)
                    ServiceLocator.financeRepo.pullAndDecrypt(
                        financeRecords.filter { it.module == com.everything.eve.data.finance.FinanceModule.MODULE }
                    )
                    // ---- 阶段 5 v2 / TR-3.2 挂载点：附件块 pull + push ----
                    // 复用同一份 financeRecords 列表过滤 type="attachment"，与 finance
                    // 主体 pullAndDecrypt 同款过滤；AttachmentRepository 内部按 module + type
                    // 双键兜底过滤。pushChanges 把本地 dirty 附件元数据推 records 表 dirty 行，
                    // 等待 RecordsRepository.sync() 周期推送。
                    ServiceLocator.attachmentRepo.pullAndDecrypt(
                        financeRecords.filter {
                            it.module == com.everything.eve.data.finance.FinanceModule.MODULE &&
                                it.type == com.everything.eve.data.finance.FinanceModule.TYPE_ATTACHMENT
                        }
                    )
                    ServiceLocator.attachmentRepo.pushChanges()
                    // ---- 阶段 5 v2 / B5 挂载点：离线汇率包 pull + push 对账 ----
                    // 复用同一份 financeRecords 过滤 type="rate"；RateTableRepository
                    // 内部按 module + type 双键兜底过滤。pull 解密下行包并按货币对
                    // 入库（dirty=0）；pushChanges 兜底重建 records 包并把本地行翻
                    // 干净，records 行的服务端推送仍由 RecordsRepository.sync 承接。
                    ServiceLocator.rateTableRepository.pullAndDecrypt(
                        financeRecords.filter {
                            it.module == com.everything.eve.data.finance.FinanceModule.MODULE &&
                                it.type == com.everything.eve.data.finance.FinanceModule.TYPE_RATE
                        }
                    )
                    ServiceLocator.rateTableRepository.pushChanges()
                    ReminderScheduler.rebuildChain(ctx)
                }
            } catch (t: Throwable) {
                // 财务模块同步失败 / 闹钟重建失败不影响 Worker 整体成功
                Log.w("SyncWorker", "finance sync failed", t)
            }
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }
}

/**
 * 后台调度器（WorkManager）。
 *
 * 阶段 3 起唯一周期任务名为 `eve.collector-sync`（15 分钟、CONNECTED、指数退避、
 * KEEP 幂等）；升级安装时先取消阶段 2 旧任务 `eve.sync.periodic`，避免双周期并存。
 */
object SyncScheduler {
    private const val PERIODIC = "eve.collector-sync"
    private const val ONESHOT = "eve.collector-sync.now"
    private const val LEGACY_PERIODIC = "eve.sync.periodic" // 阶段 2 旧任务名

    private val networkConstraint = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    /** 应用启动后调用：每 15 分钟"采集 + 同步"兜底轮询；总开关关闭时只清不建。 */
    fun schedulePeriodic(context: Context) {
        val wm = WorkManager.getInstance(context)
        // 清理阶段 2 遗留周期任务（幂等：不存在时为空操作）
        wm.cancelUniqueWork(LEGACY_PERIODIC)
        if (!CollectorSettings.isMasterEnabled(context)) {
            // 用户关闭了周期采集总开关：确保旧周期任务也被移除
            wm.cancelUniqueWork(PERIODIC)
            return
        }
        val req = PeriodicWorkRequestBuilder<CollectorWorker>(Duration.ofMinutes(15))
            .setConstraints(networkConstraint)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, Duration.ofSeconds(30))
            .build()
        wm.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, req)
    }

    /** 关闭周期采集总开关时调用：取消唯一周期任务（一次性任务不受影响）。 */
    fun cancelPeriodic(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(PERIODIC)
    }

    /** 本地有新数据或用户手动刷新时立即触发（与周期任务互斥合并）。 */
    fun requestCollectNow(context: Context) {
        val req = OneTimeWorkRequestBuilder<CollectorWorker>()
            .setConstraints(networkConstraint)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(ONESHOT, ExistingWorkPolicy.REPLACE, req)
    }

    /** 一次性任务唯一名（UI 观测进行中/成功/失败用）。 */
    const val ONESHOT_NAME = ONESHOT
}
