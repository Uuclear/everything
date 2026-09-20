/*
 * ============================================================================
 * FinanceDashboard —— 财务看板（stage5-finance / Task 7 / TR-7.2 + stage5-finance-v2 / TR-2.5）
 * ============================================================================
 *
 * 设计要点（spec FR-2 / AC-2 / TR-7.2 / TR-2.5）：
 *   1. **三数字卡 + 计数 + 月报**：净资产 / 总资产 / 总负债 + 账户/卡片/
 *      流水计数 + 当月 income / expense / net；
 *   2. **数字调用 FinanceAggregator.netWorth / monthlyReport**：实时
 *      计算（与 spec §3 资产看板定义字节级一致）；
 *   3. **零知识（spec NFR-1）**：金额渲染用 `****` 占位，避免泄漏具体
 *      金额到 UI 截屏；颜色按净资产正负配色（红色 = 资不抵债）。
 *   4. **预算状态**：根据当月支出/收入派生 BudgetStatus（OK / WARNING /
 *      EXCEEDED），仅做颜色标记，不渲染具体阈值数字。
 *   5. **v2 卡片扩展（TR-2.5）**：在 v1 Dashboard 末尾追加 4 张 v2 子类型
 *      提示卡（即将续费订阅 / 即将到期保单 / 待还借款 / 即将结束合同）。
 *      每张卡显示 count + 相对天数（"最近 X 天内"），不渲染具体金额或日期
 *      数字；与 v1 看板共用 zero-knowledge 占位（finance_dashboard_amount_mask）。
 *
 * 关联：
 *   - finance/FinanceAggregator.kt 净资产 + 月报聚合
 *   - finance/FinanceRecords.kt v2 子类型字段定义
 *   - 4b CalendarScreen.kt 同款 stateIn + collectAsState 模式
 * ============================================================================
 */

package com.everything.eve.ui.finance

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.unit.dp
import com.everything.eve.R
import com.everything.eve.finance.ContractRecord
import com.everything.eve.finance.FinanceAggregator
import com.everything.eve.finance.LoanRecord
import com.everything.eve.finance.PolicyRecord
import com.everything.eve.finance.SubscriptionRecord

/** 1 天的毫秒数（用于 v2 卡片 30 / 90 天过滤）。 */
private const val DAY_MS = 86_400_000L

/**
 * 财务看板 Composable。
 *
 * @param vm 注入 FinanceViewModel（默认从 viewModel() 取）。
 * @param onOpenSettings B5 回调：点击"默认币种 / 汇率包导入"入口时全屏打开
 *   RatesImportScreen（由 FinanceScreen 切 settingsMode）。
 * @param onOpenBudgets B6 回调：点击"预算管理"入口时全屏打开 BudgetListScreen
 *   （由 FinanceScreen 切 budgetMode）。
 */
@Composable
fun FinanceDashboard(
    vm: FinanceViewModel,
    onOpenSettings: () -> Unit = {},
    onOpenBudgets: () -> Unit = {},
) {
    val state by vm.state.collectAsState()
    val dashboard = state.dashboard
    val monthly = state.monthly
    val budget = state.budget
    // B5 入口按钮标签随默认币种实时刷新。
    val defaultCurrency by vm.defaultCurrencyState.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // ========== B5 入口行：默认币种 / 汇率包导入（点击进全屏设置） ==========
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .semantics { testTag = "dashboard_rate_entry_row" },
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            TextButton(
                onClick = onOpenSettings,
                modifier = Modifier.semantics { testTag = "dashboard_default_currency_entry" },
            ) {
                Text(
                    stringResource(
                        R.string.finance_dashboard_default_currency_entry,
                        defaultCurrency,
                    ) + " ▾",
                )
            }
            TextButton(
                onClick = onOpenSettings,
                modifier = Modifier.semantics { testTag = "dashboard_rate_import_entry" },
            ) {
                Text(stringResource(R.string.finance_dashboard_rate_entry))
            }
            // B6 预算管理入口（零知识：入口标签不含任何金额信息）。
            TextButton(
                onClick = onOpenBudgets,
                modifier = Modifier.semantics { testTag = "dashboard_budget_entry" },
            ) {
                Text(stringResource(R.string.finance_budget_dashboard_entry))
            }
        }

        // 净资产大字 + 计数
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .semantics { testTag = "dashboard_summary" },
            colors = CardDefaults.cardColors(
                containerColor = if (budget == FinanceAggregator.BudgetStatus.EXCEEDED) {
                    MaterialTheme.colorScheme.errorContainer
                } else MaterialTheme.colorScheme.surface,
            ),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = stringResource(R.string.finance_dashboard_net_worth_label),
                    style = MaterialTheme.typography.labelMedium,
                )
                Text(
                    text = stringResource(R.string.finance_dashboard_amount_mask),
                    style = MaterialTheme.typography.headlineLarge,
                    modifier = Modifier.semantics { testTag = "dashboard_net_worth" },
                )
                // B5：数字卡后缀显示折算目标币（dashboard.targetCurrency 随聚合入参更新）。
                Text(
                    text = stringResource(
                        R.string.finance_dashboard_target_currency_format,
                        dashboard.targetCurrency,
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.semantics { testTag = "dashboard_target_currency" },
                )
            }
        }

        // 总资产 / 总负债
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            DashboardStatCard(
                label = stringResource(R.string.finance_dashboard_total_assets_label),
                tag = "dashboard_total_assets",
                modifier = Modifier.weight(1f),
            )
            DashboardStatCard(
                label = stringResource(R.string.finance_dashboard_total_liability_label),
                tag = "dashboard_total_liability",
                modifier = Modifier.weight(1f),
                tint = MaterialTheme.colorScheme.error,
            )
        }

        // 计数
        Text(
            text = stringResource(
                R.string.finance_dashboard_count_format,
                dashboard.accountCount,
                dashboard.cardCount,
                dashboard.txCount,
            ),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.semantics { testTag = "dashboard_counts" },
        )

        // 月报
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = "${monthly.yearMonth} 月报",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.semantics { testTag = "dashboard_monthly_title" },
                )
                MonthlyRow(stringResource(R.string.finance_tx_kind_income), tag = "dashboard_monthly_income")
                MonthlyRow(stringResource(R.string.finance_tx_kind_expense), tag = "dashboard_monthly_expense")
                MonthlyRow(stringResource(R.string.finance_tx_kind_transfer) + " txCount", tag = "dashboard_monthly_txcount")
                Text(
                    text = "预算：${budget.name}",
                    style = MaterialTheme.typography.labelSmall,
                    color = when (budget) {
                        FinanceAggregator.BudgetStatus.OK -> Color.Unspecified
                        FinanceAggregator.BudgetStatus.WARNING -> MaterialTheme.colorScheme.tertiary
                        FinanceAggregator.BudgetStatus.EXCEEDED -> MaterialTheme.colorScheme.error
                    },
                    modifier = Modifier.semantics { testTag = "dashboard_budget" },
                )
            }
        }

        // =============================================================================
        // v2 子类型 Dashboard 卡片（stage5-finance-v2 / TR-2.5）
        // =============================================================================
        // 4 张 v2 提示卡：订阅续费 / 保单到期 / 待还借款 / 合同结束。
        // 零知识：仅展示 count + 相对天数（"最近 X 天内"），金额用 **** 占位。
        // =============================================================================

        val nowMs = System.currentTimeMillis()

        // v2 卡片 1：即将续费订阅（30 天内）—— 过滤 nextRenewalTs 在 [now, now+30d] 的订阅。
        V2DashboardCard(
            title = stringResource(R.string.finance_v2_dashboard_renewal_30d),
            tag = "dashboard_v2_subscription",
            count = state.subscriptions.count { sub ->
                sub.nextRenewalTs in nowMs..(nowMs + 30L * DAY_MS)
            },
            earliestMs = state.subscriptions
                .filter { it.nextRenewalTs in nowMs..(nowMs + 30L * DAY_MS) }
                .minOfOrNull { it.nextRenewalTs },
        )

        // v2 卡片 2：即将到期保单（90 天内）—— 过滤 expiryTs 在 [now, now+90d] 的保单。
        V2DashboardCard(
            title = stringResource(R.string.finance_v2_dashboard_expiry_90d),
            tag = "dashboard_v2_policy",
            count = state.policies.count { pol ->
                pol.expiryTs in nowMs..(nowMs + 90L * DAY_MS)
            },
            earliestMs = state.policies
                .filter { it.expiryTs in nowMs..(nowMs + 90L * DAY_MS) }
                .minOfOrNull { it.expiryTs },
        )

        // v2 卡片 3：待还借款（未结清）—— 过滤 status ∈ {active, partially_paid, overdue}。
        V2DashboardCard(
            title = stringResource(R.string.finance_v2_dashboard_loan_pending),
            tag = "dashboard_v2_loan",
            count = state.loans.count { loan ->
                loan.status in setOf("active", "partially_paid", "overdue")
            },
            earliestMs = state.loans
                .filter { it.status in setOf("active", "partially_paid", "overdue") }
                .minOfOrNull { it.dueTs },
        )

        // v2 卡片 4：即将结束合同（90 天内）—— 过滤 endTs 在 [now, now+90d] 的合同。
        V2DashboardCard(
            title = stringResource(R.string.finance_v2_dashboard_contract_end_90d),
            tag = "dashboard_v2_contract",
            count = state.contracts.count { contract ->
                contract.endTs in nowMs..(nowMs + 90L * DAY_MS)
            },
            earliestMs = state.contracts
                .filter { it.endTs in nowMs..(nowMs + 90L * DAY_MS) }
                .minOfOrNull { it.endTs },
        )

        // 空态
        if (dashboard.accountCount == 0 && dashboard.cardCount == 0 && dashboard.txCount == 0) {
            Text(
                text = stringResource(R.string.finance_dashboard_empty),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.semantics { testTag = "dashboard_empty" },
            )
        }
    }
}

/**
 * 单数字卡（标签 + **** 占位）。零知识：UI 不展示具体金额数字。
 */
@Composable
private fun DashboardStatCard(
    label: String,
    tag: String,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.onSurface,
) {
    Card(modifier = modifier) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium)
            Text(
                text = stringResource(R.string.finance_dashboard_amount_mask),
                style = MaterialTheme.typography.headlineSmall,
                color = tint,
                modifier = Modifier.semantics { testTag = tag },
            )
        }
    }
}

/**
 * 月报单行（标签 + **** 占位）。
 */
@Composable
private fun MonthlyRow(label: String, tag: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall)
        Text(
            text = stringResource(R.string.finance_dashboard_amount_mask),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(horizontal = 6.dp, vertical = 2.dp)
                .semantics { testTag = tag },
        )
    }
}

// =============================================================================
// v2 子类型 Dashboard 卡片 Composable（stage5-finance-v2 / TR-2.5）
// =============================================================================
// 通用 v2 提示卡：标题 + count + 相对天数 hint。
// 零知识：金额统一用 **** 占位（finance_dashboard_amount_mask），不渲染
// 具体日期数字，仅渲染"最近 X 天内"（基于 earliestMs 与 nowMs 计算）。
// =============================================================================

/**
 * v2 Dashboard 卡片 Composable。
 *
 * 渲染：标题（stringResource） + count（**）+ 相对天数 hint（**）。
 *
 * @param title 卡片标题（已 stringResource 化的文本）。
 * @param tag testTag 后缀（"dashboard_v2_<type>"）。
 * @param count 命中过滤条件的条目数。
 * @param earliestMs 命中条目中最早的触发时刻（ms）；null 表示无命中。
 */
@Composable
private fun V2DashboardCard(
    title: String,
    tag: String,
    count: Int,
    earliestMs: Long?,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .semantics { testTag = tag },
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                text = "$count",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.semantics { testTag = "${tag}_count" },
            )
            // 相对天数 hint：earliestMs 非空时显示"最近 X 天内"，否则显示"已结束 / 暂无"。
            val hintText = if (earliestMs != null) {
                val days = ((earliestMs - System.currentTimeMillis()) / DAY_MS).toInt().coerceAtLeast(0)
                "$days 天内"
            } else {
                stringResource(R.string.finance_v2_hint_ended)
            }
            Text(
                text = hintText,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.semantics { testTag = "${tag}_hint" },
            )
        }
    }
}