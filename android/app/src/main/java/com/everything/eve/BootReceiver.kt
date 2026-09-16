package com.everything.eve

import android.app.BackgroundServiceStartNotAllowedException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.everything.eve.collector.CollectorSettings
import com.everything.eve.collector.location.LocationTrackingService
import com.everything.eve.reminder.ReminderScheduler
import kotlinx.coroutines.runBlocking

/**
 * 开机自愈接收器（BOOT_COMPLETED）。
 *
 * 当前职责（阶段 4a Task 6）：轨迹采集自愈分支——设备重启后若轨迹开关开，
 * 尝试拉起 [LocationTrackingService]；服务的四分支前置检查（开关/MK/FINE/
 * 后台定位）由服务自身 onStartCommand 复核，MK 未解锁时服务会记枚举原因
 * 后自行退出，等待 MainActivity 的解锁恢复挂钩再次拉起（FR-8）。
 *
 * 阶段 4b Task 5 / TR-5.4 扩展点：在轨迹自愈分支**之前**追加
 * [ReminderScheduler.rebuildChain] —— 设备刚开机/重启后立即重建闹钟链头，
 * 避免等待 SyncWorker 周期（最长 15 分钟）才能恢复闹钟。
 *
 *   - 位置选择：rebuildChain 与轨迹采集是相互独立的两个模块（spec FR-5 /
 *     4a FR-8 各管各的）；把 rebuildChain 放在轨迹 early-return 之前，
 *     保证轨迹开关关时仍执行（用户可能只开日程不开轨迹）；
 *   - try-catch 包裹：与 4a 既有"两路异常只吞不抛"约定一致；rebuildChain
 *     内部已对 ServiceLocator / Room 异常做静默降级，外层 catch 仅兜底
 *     防御；
 *   - 不阻塞开机广播：rebuildChain 同步执行 O(事件数)（spec NFR-4 单用户
 *     量级 < 100ms）；
 *   - 4a 既有轨迹自愈分支**未修改**：仅在其前插入 rebuildChain；轨迹开
 *     关逻辑（CollectorSettings.isLocationTrackingEnabled early return）
 *     与 LocationTrackingService.startForegroundService 调用均保留。
 *
 * 降级约定（TR-6.3）：两路异常只吞不抛——开机广播中崩溃会导致系统弹窗，
 * 必须静默降级为"等待下次解锁/启动"。
 *
 * 注：阶段 3 的周期采集任务由 WorkManager（SyncScheduler，KEEP 策略）注册，
 * 系统重启后 WorkManager 自动恢复，无需本接收器处理。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        // ---- 阶段 4b Task 5 / TR-5.4 扩展点（先于轨迹分支执行）----
        // 设备开机后立即重建全局闹钟链头（spec FR-5 / AC-7）。rebuildChain
        // 与轨迹采集相互独立，故放在轨迹分支 early-return 之前，保证无论
        // 轨迹开关与否都执行。try-catch 包裹不崩溃。
        try {
            runBlocking {
                ReminderScheduler.rebuildChain(context.applicationContext)
            }
        } catch (e: Exception) {
            // rebuildChain 内部已对 ServiceLocator / Room 异常做静默降级；
            // 此处 catch 仅兜底防御（任何未捕获异常均不抛出，避免开机弹窗）。
        }

        // ---- 轨迹自愈分支：开关关则无需动作 ----
        if (!CollectorSettings.isLocationTrackingEnabled(context)) return

        try {
            ContextCompat.startForegroundService(
                context,
                LocationTrackingService.startIntent(context),
            )
        } catch (e: BackgroundServiceStartNotAllowedException) {
            // API31+ 后台启动前台服务限制：降级为等待下次解锁/启动，不崩溃
        } catch (e: IllegalStateException) {
            // 其余系统启动限制（低版本同语义的 IllegalStateException）：同上降级
        }
    }
}
