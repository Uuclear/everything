package com.everything.eve.sync

import android.content.Context
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
import java.time.Duration

/** 增量同步：先推本地脏记录，再按 updated_at 游标拉取。 */
class SyncWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result =
        try {
            ServiceLocator.repo.sync()
            Result.success()
        } catch (e: Exception) {
            // 网络抖动等可恢复错误：指数退避重试
            Result.retry()
        }
}

object SyncScheduler {
    private const val PERIODIC = "eve.sync.periodic"
    private const val ONESHOT = "eve.sync.now"

    private val networkConstraint = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    /** 应用启动后调用：每 15 分钟兜底同步（阶段 3/4 的采集器共用此通道）。 */
    fun schedulePeriodic(context: Context) {
        val req = PeriodicWorkRequestBuilder<SyncWorker>(Duration.ofMinutes(15))
            .setConstraints(networkConstraint)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, Duration.ofSeconds(30))
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            PERIODIC,
            ExistingPeriodicWorkPolicy.KEEP,
            req,
        )
    }

    /** 本地有新数据时立即触发（与周期任务互斥合并）。 */
    fun requestImmediate(context: Context) {
        val req = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(networkConstraint)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(ONESHOT, ExistingWorkPolicy.REPLACE, req)
    }
}
