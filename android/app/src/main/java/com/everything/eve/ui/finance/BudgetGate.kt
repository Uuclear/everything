package com.everything.eve.ui.finance

import com.everything.eve.finance.BudgetCheckResult
import com.everything.eve.finance.BudgetLevel

/**
 * B6 预算硬约束 · 零知识 UI 闸门判定。
 *
 * 纯 JVM 纯函数 / 纯文案层：只消费第一批纯函数 [BudgetEnforcer] 的判定结果
 * [BudgetCheckResult]，决定 UI 该"放行、提示、拦截确认"中的哪一种，并产出
 * 可直接展示的中文文案。
 *
 * 零知识铁律（安全红线）：
 *  - 文案只允许出现"已用百分比 + 分类名"；
 *  - 禁止出现金额（元/分/币种数字）、日期、卡号、对手方账户等任何敏感信息；
 *  - 分类为 "all" 时降级为"全部支出"，不回显原始分类串。
 *
 * 文案刻意硬编码而非走 strings.xml：纯函数 JVM 单测需对稳定字符串断言，
 * 且字符串不含任何用户数据。
 */
object BudgetGate {

    /**
     * 是否需要弹"超支确认"对话框（拦截态）。
     *
     * 仅 [BudgetLevel.BLOCK] 为 true：此时保存动作必须中止，等用户显式选择
     * "仍保存"（携带 overspendAcknowledged=true 二次提交）或"返回修改"。
     */
    fun needsConfirmDialog(result: BudgetCheckResult): Boolean =
        result.level == BudgetLevel.BLOCK

    /**
     * 是否需要在保存成功后轻提示（预警态）。
     *
     * 仅 [BudgetLevel.WARNING] 为 true：WARNING 不拦截保存，仅 Toast 提醒；
     * OK / BLOCK 均不 Toast（BLOCK 走模态对话框而非轻提示）。
     */
    fun needsWarningToast(result: BudgetCheckResult): Boolean =
        result.level == BudgetLevel.WARNING

    /**
     * 预警 Toast 文案（零知识：只含百分比与分类名）。
     *
     * 分类为 "all"（预算覆盖全部分类）时显示"全部支出"；
     * 其他分类直接展示业务分类名（纯标签，如"餐饮"），不泄露金额与日期。
     */
    fun warningText(result: BudgetCheckResult): String {
        val label = categoryLabel(result.category)
        return "本月${label}已用 ${result.usedPct}%，接近预算上限"
    }

    /**
     * 拦截确认对话框正文（零知识：只含百分比）。
     *
     * 不给分类名也成立：用户此刻正在编辑本笔流水，上下文已足够；
     * 正文刻意不出现任何金额 / 日期信息。
     */
    fun confirmText(result: BudgetCheckResult): String =
        "预计已用 ${result.usedPct}%，超过预算阈值，是否仍保存？"

    /**
     * 零知识分类标签："all" 映射为"全部支出"，其余原样返回（分类是用户自填
     * 的短标签，不含金额 / 账号等敏感字段；FinanceRecords 已限长 1..20 字）。
     */
    private fun categoryLabel(category: String): String =
        if (category == "all") "全部支出" else category
}
