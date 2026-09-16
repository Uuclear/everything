package com.everything.eve.data.event

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

/**
 * 提醒降级日志 DAO（阶段 4b Task 4 / TR-4.2）。
 *
 * 表 `event_reminder_log` 仅三列可空约束：`event_id`/`kind`/`created_ts` 全 NOT NULL，
 * 自增 `id` INTEGER PRIMARY KEY AUTOINCREMENT。
 *
 * 用途：
 *  - `insert`：ReminderScheduler.scheduleNext / ReminderReceiver.onReceive 在
 *    权限降级分支写一条（spec FR-6）；
 *  - `recent`：设置页/通知中心按 `created_ts DESC` 取最近 N 条展示；
 *  - `purgeBefore`：老日志按时间窗口清理（如 90 天前），保持表小。
 */
@Dao
interface EventReminderLogDao {

    /**
     * 写入一条降级日志。
     *
     * @return 新行自增 id（写入失败时返回 -1L）。
     */
    @Insert
    suspend fun insert(entity: EventReminderLogEntity): Long

    /**
     * 受控扩展（Task 5 / TR-5.2 引入）：写入 `kind=alarm_killed` 一行。
     *
     * 设计理由：
     *  - ReminderScheduler.scheduleNext 在 SCHEDULE_EXACT_ALARM/USE_EXACT_ALARM
     *    被拒时降级为 `setAndAllowWhileIdle` 并写一条 log（spec FR-6）；
     *  - 频率低、不破坏 T4 既有的 `insert / recent / purgeBefore` 公开接口；
     *  - 该便捷方法仅由 ReminderScheduler 单点调用，写入前已校验 kind
     *    ∈ {alarm_killed, notification_denied, exact_denied}（ReminderScheduler
     *    内部硬编码）。
     *
     * @return 新行自增 id（写入失败时返回 -1L）。
     */
    @Query(
        """
        INSERT INTO event_reminder_log (event_id, occurrence_ts, kind, created_ts)
        VALUES (:eventId, :occurrenceTs, :kind, :createdTs)
        """,
    )
    suspend fun insertRaw(eventId: String, occurrenceTs: Long, kind: String, createdTs: Long): Long

    /**
     * 最近 N 条降级日志（按 `created_ts DESC`）。
     *
     * @param limit 上限条数（设置页/通知中心调用方传入，如 50）。
     */
    @Query(
        "SELECT * FROM event_reminder_log ORDER BY created_ts DESC LIMIT :limit",
    )
    suspend fun recent(limit: Int): List<EventReminderLogEntity>

    /**
     * 清理 `created_ts < beforeTs` 的老日志（设置页手动清理 / 周期清理任务用）。
     *
     * @return 删除行数。
     */
    @Query("DELETE FROM event_reminder_log WHERE created_ts < :beforeTs")
    suspend fun purgeBefore(beforeTs: Long): Int
}