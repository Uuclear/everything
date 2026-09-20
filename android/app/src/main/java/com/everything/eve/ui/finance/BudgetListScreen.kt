/*
 * ============================================================================
 * BudgetListScreen —— B6 预算管理列表
 * ============================================================================
 *
 * 设计要点：
 *   1. 列出 state.budgets 全量预算；每条展示"周期 · 分类 · 预警/拦截阈值 ·
 *      启停状态"，行点击进入编辑器；
 *   2. 顶部"新建预算"；每行支持"停用/启用"切换与删除（删除二次确认）；
 *   3. 零知识铁律：列表行只允许出现"百分比 + 分类名"，严禁渲染预算金额、
 *      起止日期、卡号、对手方。
 *
 * 编码纪律：严禁 ASCII 双连字符；中文 KDoc/注释。
 * ============================================================================
 */

package com.everything.eve.ui.finance

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.unit.dp
import com.everything.eve.R
import com.everything.eve.finance.BudgetRecord

/**
 * 预算管理列表屏。
 *
 * @param vm 共享的 FinanceViewModel。
 * @param onClose 关闭列表（由 FinanceScreen 清 budgetMode）。
 * @param onEdit 点击新建（参数 null）或编辑某条（参数为 budgetId）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BudgetListScreen(
    vm: FinanceViewModel,
    onClose: () -> Unit,
    onEdit: (budgetId: String?) -> Unit,
) {
    val state by vm.state.collectAsState()
    val budgets = state.budgets
    // 待删除确认的预算 id（null 表示未挂确认框）。
    var pendingDeleteId by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.finance_budget_title)) },
                navigationIcon = {
                    TextButton(
                        onClick = onClose,
                        modifier = Modifier.semantics { testTag = "budget_list_close_btn" },
                    ) { Text(stringResource(R.string.finance_editor_cancel_btn)) }
                },
                actions = {
                    TextButton(
                        onClick = { onEdit(null) },
                        modifier = Modifier.semantics { testTag = "budget_add_btn" },
                    ) { Text(stringResource(R.string.finance_budget_add)) }
                },
            )
        },
    ) { padding ->
        if (budgets.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(R.string.finance_budget_empty),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.semantics { testTag = "budget_list_empty" },
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 12.dp)
                    .semantics { testTag = "budget_list" },
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(items = budgets, key = { it.id }) { budget ->
                    BudgetRow(
                        budget = budget,
                        onClick = { onEdit(budget.id) },
                        onToggleActive = {
                            vm.upsertBudget(budget.copy(active = !budget.active))
                        },
                        onRequestDelete = { pendingDeleteId = budget.id },
                    )
                }
            }
        }
    }

    // 删除二次确认：仅展示分类标签，不展示金额 / 日期。
    pendingDeleteId?.let { deleteId ->
        AlertDialog(
            onDismissRequest = { pendingDeleteId = null },
            title = { Text(stringResource(R.string.finance_budget_delete_confirm_title)) },
            text = { Text(stringResource(R.string.finance_budget_delete_confirm_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.deleteBudget(deleteId)
                        pendingDeleteId = null
                    },
                    modifier = Modifier.semantics { testTag = "budget_delete_confirm_btn" },
                ) { Text(stringResource(R.string.finance_budget_delete)) }
            },
            dismissButton = {
                TextButton(
                    onClick = { pendingDeleteId = null },
                    modifier = Modifier.semantics { testTag = "budget_delete_cancel_btn" },
                ) { Text(stringResource(R.string.finance_editor_cancel_btn)) }
            },
            modifier = Modifier.semantics { testTag = "budget_delete_dialog" },
        )
    }
}

/**
 * 单条预算卡片。
 *
 * 零知识文案："月度预算 · 餐饮 · 预警80% 拦截100% · 启用"，
 * 只有周期标签、分类标签与百分比，不含任何金额或日期。
 */
@Composable
private fun BudgetRow(
    budget: BudgetRecord,
    onClick: () -> Unit,
    onToggleActive: () -> Unit,
    onRequestDelete: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .semantics { testTag = "budget_row_${budget.id}" },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        onClick = onClick,
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // 主文案行：周期 · 分类 · 阈值 · 启停（零知识：仅百分比 + 分类名）。
            val scopeLabel = budgetScopeLabel(budget.scope)
            val categoryLabel = if (budget.category == "all") {
                stringResource(R.string.finance_budget_category_all)
            } else {
                budget.category
            }
            val activeLabel = if (budget.active) {
                stringResource(R.string.finance_budget_active)
            } else {
                stringResource(R.string.finance_budget_inactive)
            }
            Text(
                text = "$scopeLabel · $categoryLabel · " +
                    "${stringResource(R.string.finance_budget_warning_short)}${budget.warningThresholdPct}% " +
                    "${stringResource(R.string.finance_budget_block_short)}${budget.blockThresholdPct}% · $activeLabel",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.semantics { testTag = "budget_row_summary_${budget.id}" },
            )

            // 行内操作：启停切换 + 删除（删除弹二次确认）。
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = onToggleActive,
                    modifier = Modifier.semantics { testTag = "budget_row_toggle_${budget.id}" },
                ) {
                    Text(
                        if (budget.active) {
                            stringResource(R.string.finance_budget_disable)
                        } else {
                            stringResource(R.string.finance_budget_enable)
                        },
                    )
                }
                OutlinedButton(
                    onClick = onRequestDelete,
                    modifier = Modifier.semantics { testTag = "budget_row_delete_${budget.id}" },
                ) { Text(stringResource(R.string.finance_budget_delete)) }
            }
        }
    }
}

/**
 * 预算周期的中文标签（零知识：周期是静态枚举标签，不携带任何敏感信息）。
 */
@Composable
private fun budgetScopeLabel(scope: String): String = when (scope) {
    "monthly" -> stringResource(R.string.finance_budget_scope_monthly)
    "weekly" -> stringResource(R.string.finance_budget_scope_weekly)
    "yearly" -> stringResource(R.string.finance_budget_scope_yearly)
    "custom" -> stringResource(R.string.finance_budget_scope_custom)
    else -> scope
}
