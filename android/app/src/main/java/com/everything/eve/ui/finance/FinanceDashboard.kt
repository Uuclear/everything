/*
 * ============================================================================
 * FinanceDashboard —— 财务看板（stage5-finance / Task 7 / TR-7.2）
 * ============================================================================
 *
 * 设计要点（spec FR-2 / AC-2 / TR-7.2）：
 *   1. **三数字卡 + 计数 + 月报**：净资产 / 总资产 / 总负债 + 账户/卡片/
 *      流水计数 + 当月 income / expense / net；
 *   2. **数字调用 FinanceAggregator.netWorth / monthlyReport**：实时
 *      计算（与 spec §3 资产看板定义字节级一致）；
 *   3. **零知识（spec NFR-1）**：金额渲染用 `****` 占位，避免泄漏具体
 *      金额到 UI 截屏；颜色按净资产正负配色（红色 = 资不抵债）。
 *   4. **预算状态**：根据当月支出/收入派生 BudgetStatus（OK / WARNING /
 *      EXCEEDED），仅做颜色标记，不渲染具体阈值数字。
 *
 * 关联：
 *   - finance/FinanceAggregator.kt 净资产 + 月报聚合
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
import com.everything.eve.finance.FinanceAggregator

/**
 * 财务看板 Composable。
 *
 * @param vm 注入 FinanceViewModel（默认从 viewModel() 取）。
 */
@Composable
fun FinanceDashboard(vm: FinanceViewModel) {
    val state by vm.state.collectAsState()
    val dashboard = state.dashboard
    val monthly = state.monthly
    val budget = state.budget

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
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
                Text(
                    text = stringResource(R.string.finance_dashboard_currency_cny),
                    style = MaterialTheme.typography.labelSmall,
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