/*
 * 阶段 4b — 闹钟触发接收器（tasks.md Task 5 / TR-5.3）。
 *
 * 职责（spec FR-5 / AC-5）：
 *  1) 接收 PendingIntent 广播（请求码 REMINDER_REQUEST_CODE）；
 *  2) 从 extras 拉取 event_id + occurrence_ts；
 *  3) 调 ServiceLocator.eventsRepo.getById 拉事件明文（仅 title；不入 log）；
 *  4) 渲染通知：仅 title + "N 分钟后开始"类抽象文案，**不渲染 start_ts 数值与 note 原文**；
 *  5) 重新计算全局 nextTrigger → 调 scheduleNext 注册下一闹钟（链式）。
 *
 * 通知权限降级（spec FR-6）：
 *  - API 33+ 用户拒绝 POST_NOTIFICATIONS → 不弹横幅；仅写
 *    event_reminder_log kind=notification_denied；
 *  - channelId = "events"；首次启动时一次性创建。
 *
 * 零知识红线（spec NFR-1 / FR-5）：
 *  - 通知文案仅渲染 title + 距开始分钟数（不渲染 start_ts 数值 / note 原文）；
 *  - log 仅存 event_id + occurrence_ts + kind；不写 title 原文；
 *  - 异常捕获后静默降级，不崩溃（避免国产 ROM 杀进程时弹窗）。
 */

package com.everything.eve.reminder

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.everything.eve.R
import com.everything.eve.ServiceLocator
import kotlinx.coroutines.runBlocking
import org.json.JSONArray

/**
 * 闹钟触发接收器（全局单闹钟 requestCode = REMINDER_REQUEST_CODE）。
 *
 * Manifest 注册（TR-5.5）：
 *  - `android:exported="false"`（仅应用内 PendingIntent 触发，不接收外部广播）；
 *  - 4a 既有的 BootReceiver（exported=true，仅接收 BOOT_COMPLETED 系统广播）
 *    不受影响。
 */
class ReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_FIRE) return
        val eventId = intent.getStringExtra(EXTRA_EVENT_ID).orEmpty()
        val occurrenceTs = intent.getLongExtra(EXTRA_OCCURRENCE_TS, 0L)
        if (eventId.isEmpty()) return

        val appCtx = context.applicationContext

        // 确保通知 channel 已创建（API 26+；minSdk=26 兜底）。幂等：重复
        // createNotificationChannel 系统会自动忽略同名 channel。
        ensureChannel(appCtx)

        // 拉取事件明文（仅渲染通知；不进 log / 不进 SharedPreferences）。
        val title = try {
            runBlocking {
                ServiceLocator.eventsRepo.getById(eventId)?.title
            }
        } catch (e: Exception) {
            null
        } ?: DEFAULT_NOTIFICATION_TITLE

        // 通知文案：title + 距开始分钟数（不渲染 occurrence_ts / note 原文）。
        val leadMinutes = computeLeadMinutes(occurrenceTs, eventId)
        val contentText = if (leadMinutes == 0L) {
            // reminders[0]=0 触发 → 抽象文案"即将开始"（不渲染任何时刻数字）
            appCtx.getString(R.string.reminder_now_text)
        } else if (leadMinutes > 0) {
            appCtx.getString(R.string.reminder_lead_text, leadMinutes.toInt())
        } else {
            // 数据缺失时仍走"即将开始"文案（spec FR-5 文案纪律：不渲染任何时刻数字）
            appCtx.getString(R.string.reminder_now_text)
        }

        // 通知权限检查（API 33+）：被拒时不弹横幅，仅写 log（spec FR-6）。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                appCtx, Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
            if (granted) {
                postNotification(appCtx, eventId, title, contentText)
            } else {
                logNotificationDenied(appCtx, eventId, occurrenceTs)
            }
        } else {
            // API <33：默认有通知权限（用户在系统设置关闭的边缘场景由 Android 框架静默吞掉）
            try {
                postNotification(appCtx, eventId, title, contentText)
            } catch (e: SecurityException) {
                logNotificationDenied(appCtx, eventId, occurrenceTs)
            }
        }

        // 链式：重算全局 nextTrigger → scheduleNext 注册下一闹钟（spec FR-5）。
        try {
            runBlocking {
                ReminderScheduler.rebuildChain(appCtx)
            }
        } catch (e: Exception) {
            // 链式调度失败不阻塞通知展示（best-effort）。
        }
    }

    /**
     * 创建通知 channel "events"（API 26+；幂等）。
     *
     * Importance = HIGH：触发时刻到点即弹横幅（spec FR-5 通知语义）。
     * description 由 strings.xml 提供，集中文案维护。
     */
    private fun ensureChannel(ctx: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            REMINDER_CHANNEL_ID,
            ctx.getString(R.string.reminder_channel_name),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = ctx.getString(R.string.reminder_channel_description)
            enableLights(false)
            enableVibration(false)
        }
        nm.createNotificationChannel(channel)
    }

    /**
     * 发送通知。
     *
     * 通知 id 用 eventId 的 hashCode（同一事件的多次提醒覆盖同一通知槽；不同
     * 事件并行展示——避免通知栏被同一事件堆满）。
     */
    private fun postNotification(
        ctx: Context,
        eventId: String,
        title: String,
        contentText: String,
    ) {
        val notification = NotificationCompat.Builder(ctx, REMINDER_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(contentText)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(ctx).notify(eventId.hashCode(), notification)
    }

    /**
     * 写一条 notification_denied 日志（spec FR-6）。
     */
    private fun logNotificationDenied(ctx: Context, eventId: String, occurrenceTs: Long) {
        try {
            runBlocking {
                ServiceLocator.db.eventReminderLogDao().insertRaw(
                    eventId = eventId,
                    occurrenceTs = occurrenceTs,
                    kind = "notification_denied",
                    createdTs = System.currentTimeMillis(),
                )
            }
        } catch (e: Exception) {
            // 日志写入失败静默吞掉，不阻塞通知路径。
        }
    }

    /**
     * 渲染文案用"距开始分钟数"计算（抽象；不暴露原始毫秒）。
     *
     * 实现：再读一次 event + reminders_json（闹钟触发本身是低频事件；
     * 每事件一次 IO 性能可接受）。reminders=[0] 时返回 0 → "即将开始"。
     *
     * @return 距开始分钟数（reminders[0]=0 时为 0）；数据缺失时返回 -1 → 走"即将开始"文案。
     */
    private fun computeLeadMinutes(occurrenceTs: Long, eventId: String): Long {
        return try {
            val entity = runBlocking { ServiceLocator.eventsRepo.getById(eventId) }
                ?: return -1L
            val reminders = try {
                val arr = JSONArray(entity.reminders_json)
                IntArray(arr.length()) { arr.getInt(it) }
            } catch (e: Exception) {
                return -1L
            }
            if (reminders.isEmpty()) return -1L
            // reminders[0] = 实际触发的"提前分钟数"（因为我们注册的闹钟 ts =
            // start_ts - r*60_000，r 即对应 lead minutes）。
            // 由于 reminders 数组本身可含多个值，这里用"已注册闹钟 ts 与 start_ts
            // 偏差"反推 leadMinutes：偏差 = (start_ts - occurrenceTs) / 60_000。
            val diffMs = entity.start_ts - occurrenceTs
            if (diffMs <= 0) 0L else diffMs / 60_000L
        } catch (e: Exception) {
            -1L
        }
    }

    companion object {
        /** ReminderScheduler 同步常量（与 buildPendingIntent 一致）。 */
        const val ACTION_FIRE = "com.everything.eve.reminder.FIRE"
        // EXTRA_EVENT_ID / EXTRA_OCCURRENCE_TS 由 ReminderScheduler 同名常量提供；
        // 此处复用 ReminderScheduler.EXTRA_EVENT_ID / EXTRA_OCCURRENCE_TS。
        private const val DEFAULT_NOTIFICATION_TITLE = "日程提醒"
    }
}
