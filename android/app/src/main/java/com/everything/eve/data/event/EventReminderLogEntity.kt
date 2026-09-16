package com.everything.eve.data.event

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 事件提醒降级日志表（阶段 4b，v5 迁移新增）。
 *
 * 三种写入时机（spec FR-6）：
 *  1) `alarm_killed`：SCHEDULE_EXACT_ALARM 被拒（API 31+ 用户拒绝 / 系统回收），
 *     调度降级为 `setAndAllowWhileIdle`；UI 一次性 banner"可能被系统限制"。
 *  2) `notification_denied`：POST_NOTIFICATIONS 被拒（API 33+），通知不弹横幅，
 *     仅通知中心落一条；UI 同 banner。
 *  3) `exact_denied`：低版本系统关闭精确闹钟（API ≤30 用户手动关），检测
 *     `AlarmManager.canScheduleExactAlarms()=false` 后降级写入。
 *
 * 零知识红线（spec NFR-1 / FR-4）：
 *  - 仅存 `event_id`（UUID）+ `occurrence_ts`（实例本地毫秒，不存事件原文）；
 *  - `kind` 仅枚举字符串；严禁写 title/location/note/rrule 等明文。
 *  - 不进日志 / 通知文案 / SharedPreferences。
 */
@Entity(tableName = "event_reminder_log")
data class EventReminderLogEntity(
    /** 自增主键（仅本地行标识，不入密文、不上行）。 */
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** 事件 id（UUID；对应 `event.id`，便于 UI 跳转事件详情）。 */
    val event_id: String,
    /** 触发时刻（实例本地 Unix 毫秒；不存任何其他事件明文）。 */
    val occurrence_ts: Long,
    /**
     * 降级枚举：`alarm_killed` | `notification_denied` | `exact_denied`。
     * 严格三选一；写入前由 ReminderScheduler/ReminderReceiver 校验。
     */
    val kind: String,
    /** 写入时刻（本地毫秒；用于按时间窗口清理 + 倒序展示）。 */
    val created_ts: Long,
)