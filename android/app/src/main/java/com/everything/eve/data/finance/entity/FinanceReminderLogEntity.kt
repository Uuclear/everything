package com.everything.eve.data.finance.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 财务提醒日志表（阶段 5 / Task 4 / TR-4.4）。
 *
 * 与 4b [com.everything.eve.data.event.EventReminderLogEntity] 同模式——
 * 仅记 id / 关联条目 / 触发时刻 / 投递状态，**严禁**写 title/amount/last4
 * 等任何明文（spec NFR-1 / 零知识红线）。
 *
 * 表 `finance_reminder_log` 服务 4b ReminderScheduler 财务复用扩展（Task 6）：
 *  - 当 SCHEDULE_EXACT_ALARM / USE_EXACT_ALARM / POST_NOTIFICATIONS 被拒
 *    时降级写入本表（spec FR-6 / FU-7 沿用）；
 *  - 设置页/通知中心按 `fire_at DESC` 取最近 N 条展示；
 *  - 老日志按时间窗口清理（如 90 天前），保持表小。
 *
 * v1 启用前两类枚举（`card_statement_due` / `card_payment_due`）；
 * v2 启用后三类（`subscription_renewal` / `policy_expiry` / `loan_due`）。
 * 写入前由 ReminderScheduler/ReminderReceiver 校验枚举合法性。
 */
@Entity(tableName = "finance_reminder_log")
data class FinanceReminderLogEntity(
    /** 自增主键（仅本地行标识，不入密文、不上行）。 */
    @PrimaryKey(autoGenerate = true) val id: Long = 0,

    /**
     * 关联条目 id（UUID；对应 `finance_card.id` 或 `finance_account.id`）；
     * 由 `refKind` 决定指向哪张表的 id。
     */
    val ref_id: String,

    /**
     * 提醒种类：
     *  - `card_statement_due`：信用卡账单日（v1 启用）；
     *  - `card_payment_due`：信用卡还款日（v1 启用）；
     *  - `subscription_renewal`：订阅扣费提醒（v2 占位）；
     *  - `policy_expiry`：保单即将到期（v2 占位）；
     *  - `loan_due`：借款即将到期（v2 占位）。
     */
    val ref_kind: String,

    /** 计划触发时刻（实例本地 Unix 毫秒；不存任何其他条目明文）。 */
    val fire_at: Long,

    /**
     * 投递状态：0 表示尚未投递（闹钟已调度但未触发/未送达）；
     * 1 表示已成功触发并送达通知中心。
     */
    val delivered: Boolean,
)