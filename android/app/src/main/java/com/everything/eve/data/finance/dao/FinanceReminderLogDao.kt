package com.everything.eve.data.finance.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.everything.eve.data.finance.entity.FinanceReminderLogEntity

/**
 * 财务提醒降级日志 DAO（阶段 5 / Task 4 / TR-4.4）。
 *
 * 表 `finance_reminder_log` 与 4b [com.everything.eve.data.event.EventReminderLogDao]
 * 同模式——
 *  - 自增 `id` INTEGER PRIMARY KEY AUTOINCREMENT；
 *  - `ref_id` / `ref_kind` / `fire_at` / `delivered` 四列可空约束：`ref_id`/
 *    `ref_kind`/`fire_at` 全 NOT NULL，`delivered` 缺省 0；
 *  - 严禁写 title/amount/last4 等明文（spec NFR-1 / 零知识红线）。
 *
 * 用途：
 *  - `insertRaw`：ReminderScheduler.scheduleNext / ReminderReceiver.onReceive 在
 *    权限降级分支写一条（spec FR-6 / Task 6 财务复用扩展）；
 *  - `recent`：设置页/通知中心按 `fire_at DESC` 取最近 N 条展示；
 *  - `purgeBefore`：老日志按时间窗口清理（如 90 天前），保持表小。
 */
@Dao
interface FinanceReminderLogDao {

    /**
     * 写入一条降级日志。
     *
     * @return 新行自增 id（写入失败时返回 -1L）。
     */
    @Insert
    suspend fun insert(entity: FinanceReminderLogEntity): Long

    /**
     * 受控便捷写入：仅写 ref_id / ref_kind / fire_at / delivered 四列，
     * 与 [insert] 行为等价（仅便捷 SQL）。
     *
     * 设计理由（仿 4b EventReminderLogDao.insertRaw）：
     *  - ReminderScheduler.scheduleNext 在 SCHEDULE_EXACT_ALARM/USE_EXACT_ALARM
     *    被拒时降级为 `setAndAllowWhileIdle` 并写一条 log（spec FR-6）；
     *  - 频率低、不破坏 T4 既有的 `insert / recent / purgeBefore` 公开接口；
     *  - 该便捷方法仅由 ReminderScheduler 单点调用，写入前已校验 ref_kind
     *    ∈ {card_statement_due, card_payment_due, subscription_renewal,
     *    policy_expiry, loan_due}（v1 仅启用前两类）。
     *
     * @return 新行自增 id（写入失败时返回 -1L）。
     */
    @Query(
        """
        INSERT INTO finance_reminder_log (ref_id, ref_kind, fire_at, delivered)
        VALUES (:refId, :refKind, :fireAt, :delivered)
        """,
    )
    suspend fun insertRaw(
        refId: String,
        refKind: String,
        fireAt: Long,
        delivered: Boolean,
    ): Long

    /**
     * 最近 N 条降级日志（按 `fire_at DESC`）。
     *
     * @param limit 上限条数（设置页/通知中心调用方传入，如 50）。
     */
    @Query(
        "SELECT * FROM finance_reminder_log ORDER BY fire_at DESC LIMIT :limit",
    )
    suspend fun recent(limit: Int): List<FinanceReminderLogEntity>

    /**
     * 清理 `fire_at < beforeTs` 的老日志（设置页手动清理 / 周期清理任务用）。
     *
     * @return 删除行数。
     */
    @Query("DELETE FROM finance_reminder_log WHERE fire_at < :beforeTs")
    suspend fun purgeBefore(beforeTs: Long): Int
}