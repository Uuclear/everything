/*
 * ============================================================================
 * FinanceTxList —— 流水列表（stage5-finance / Task 7 / TR-7.3）
 * ============================================================================
 *
 * 设计要点：
 *   1. **按时间排序**：默认按 occurredAt 倒序，最新在前；
 *   2. **过滤**：类型 chip (income / expense / transfer) + 搜索（类别/备注）；
 *   3. **行内 chip**：类型 chip + 金额 mask（零知识）+ 主账户关联名 lookup；
 *   4. **新建入口**：FAB → 编辑器；行 click → 编辑器进入编辑模式。
 *
 * 关联：
 *   - tasks.md TR-7.3
 *   - ui/finance/FinanceViewModel.filteredTxs
 * ============================================================================
 */

package com.everything.eve.ui.finance

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.unit.dp
import com.everything.eve.R
import com.everything.eve.data.finance.entity.FinanceTxEntity

/**
 * 财务流水列表 Composable（TR-7.3）。
 *
 * @param vm 共享的 FinanceViewModel。
 * @param onAdd 新建流水（编辑器新建模式）。
 * @param onEdit 编辑已存在流水。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FinanceTxList(
    vm: FinanceViewModel,
    onAdd: () -> Unit,
    onEdit: (entityId: String) -> Unit,
) {
    val state by vm.state.collectAsState()
    val list = vm.filteredTxs(state)
    // 关联主账户字典（id → name），行内展示账户名 lookup，避免渲染 accountId 明文。
    val accountNameById = state.accounts.associate { it.id to it.name }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text(stringResource(R.string.finance_tab_txs)) })
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = onAdd,
                modifier = Modifier.semantics { testTag = "tx_list_fab_add" },
            ) { Icon(Icons.Default.Add, contentDescription = null) }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = state.search,
                onValueChange = { vm.setSearch(it) },
                label = { Text(stringResource(R.string.finance_list_search_hint)) },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { testTag = "tx_list_search" },
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                TxKindChip("all", stringResource(R.string.finance_list_filter_all), state.filterKind) { vm.setFilter(it) }
                TxKindChip("income", stringResource(R.string.finance_tx_chip_kind_income), state.filterKind) { vm.setFilter(it) }
                TxKindChip("expense", stringResource(R.string.finance_tx_chip_kind_expense), state.filterKind) { vm.setFilter(it) }
                TxKindChip("transfer", stringResource(R.string.finance_tx_chip_kind_transfer), state.filterKind) { vm.setFilter(it) }
            }

            if (list.isEmpty()) {
                Text(
                    text = stringResource(R.string.finance_list_empty),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.semantics { testTag = "tx_list_empty" },
                )
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .semantics { testTag = "tx_list" },
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(items = list, key = { it.id }) { tx ->
                        TxRow(
                            tx = tx,
                            accountName = accountNameById[tx.accountId] ?: "",
                            onClick = { onEdit(tx.id) },
                        )
                    }
                }
            }
        }
    }
}

/**
 * 单行流水：类别/分类 + 类型 chip + 关联账户 lookup + 金额 mask。
 *
 * 零知识：金额位置统一 `****`；不渲染具体日期数字（仅显示"今天"模糊态）。
 */
@Composable
private fun TxRow(tx: FinanceTxEntity, accountName: String, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .semantics { testTag = "tx_row_${tx.id}" },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        onClick = onClick,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = tx.category.ifBlank { "(未分类)" },
                    style = MaterialTheme.typography.titleSmall,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TxKindChipStatic(tx.kind)
                    if (accountName.isNotBlank()) {
                        AssistChip(
                            onClick = {},
                            label = { Text(accountName) },
                            colors = AssistChipDefaults.assistChipColors(
                                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                            ),
                            modifier = Modifier.semantics { testTag = "tx_row_account_chip" },
                        )
                    }
                }
            }
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            ) {
                Text(
                    text = stringResource(R.string.finance_dashboard_amount_mask),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.semantics { testTag = "tx_row_amount_mask" },
                )
            }
        }
    }
}

@Composable
private fun TxKindChipStatic(kind: String) {
    val label = when (kind) {
        "income" -> R.string.finance_tx_chip_kind_income
        "transfer" -> R.string.finance_tx_chip_kind_transfer
        else -> R.string.finance_tx_chip_kind_expense
    }
    AssistChip(
        onClick = {},
        label = { Text(stringResource(label)) },
        colors = AssistChipDefaults.assistChipColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
        ),
    )
}

@Composable
private fun TxKindChip(
    kind: String,
    label: String,
    selected: String,
    onSelect: (String) -> Unit,
) {
    FilterChip(
        selected = selected == kind,
        onClick = { onSelect(kind) },
        label = { Text(label) },
        modifier = Modifier.semantics { testTag = "tx_kind_chip_$kind" },
    )
}
