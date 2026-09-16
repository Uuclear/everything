/*
 * ============================================================================
 * FinanceAccountList —— 账户列表（stage5-finance / Task 7 / TR-7.3）
 * ============================================================================
 *
 * 设计要点：
 *   1. **展示账户项**：name + 类型 chip + 余额 mask（****）+ 归档标记；
 *   2. **搜索 + 排序 + 类型筛选** 行内联动（来自 FinanceViewModel.filteredAccounts）；
 *   3. **新建/编辑入口**：右上 + 行内 click 触发编辑器 navigation；
 *   4. **零知识（spec NFR-1）**：金额统一 `****` 占位（仅在编辑器输入瞬时驻留）。
 *
 * 关联：
 *   - tasks.md TR-7.3
 *   - ui/finance/FinanceViewModel.filteredAccounts
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
import com.everything.eve.data.finance.entity.FinanceAccountEntity

/**
 * 财务账户列表 Composable（TR-7.3）。
 *
 * @param vm 共享的 FinanceViewModel。
 * @param onAdd 新建账户触发：编辑器拉起新建模式（entityId = null）。
 * @param onEdit 编辑已存在账户：entityId = acc.id。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FinanceAccountList(
    vm: FinanceViewModel,
    onAdd: () -> Unit,
    onEdit: (entityId: String) -> Unit,
) {
    val state by vm.state.collectAsState()
    val list = vm.filteredAccounts(state)

    Scaffold(
        topBar = {
            TopAppBar(title = { Text(stringResource(R.string.finance_tab_accounts)) })
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = onAdd,
                modifier = Modifier.semantics { testTag = "account_list_fab_add" },
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
            // 搜索框 + 类型筛选 chips
            OutlinedTextField(
                value = state.search,
                onValueChange = { vm.setSearch(it) },
                label = { Text(stringResource(R.string.finance_list_search_hint)) },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { testTag = "account_list_search" },
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                AccountKindChip("all", stringResource(R.string.finance_list_filter_all), state.filterKind) { vm.setFilter(it) }
                AccountKindChip("cash", stringResource(R.string.finance_account_kind_cash), state.filterKind) { vm.setFilter(it) }
                AccountKindChip("deposit", stringResource(R.string.finance_account_kind_deposit), state.filterKind) { vm.setFilter(it) }
                AccountKindChip("stock", stringResource(R.string.finance_account_kind_stock), state.filterKind) { vm.setFilter(it) }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                SortChip(FinanceSortKey.UPDATED_DESC, R.string.finance_list_sort_updated_desc, state.sortKey) { vm.setSort(it) }
                SortChip(FinanceSortKey.BALANCE_ASC, R.string.finance_list_sort_balance_asc, state.sortKey) { vm.setSort(it) }
                SortChip(FinanceSortKey.BALANCE_DESC, R.string.finance_list_sort_balance_desc, state.sortKey) { vm.setSort(it) }
            }

            if (list.isEmpty()) {
                Text(
                    text = stringResource(R.string.finance_list_empty),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.semantics { testTag = "account_list_empty" },
                )
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .semantics { testTag = "account_list" },
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(items = list, key = { it.id }) { acc ->
                        AccountRow(acc = acc, onClick = { onEdit(acc.id) })
                    }
                }
            }
        }
    }
}

/**
 * 单行账户卡片：名称 + 类型 chip + 余额 mask + 归档 chip。
 * 行 click 触发编辑器进入编辑模式。
 */
@Composable
private fun AccountRow(acc: FinanceAccountEntity, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .semantics { testTag = "account_row_${acc.id}" },
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
                Text(acc.name.ifBlank { "(未命名)" }, style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    AccountKindChipStatic(acc.kind)
                    if (acc.archived) {
                        AssistChip(
                            onClick = {},
                            label = { Text(stringResource(R.string.finance_list_archived_chip)) },
                            colors = AssistChipDefaults.assistChipColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            ),
                            modifier = Modifier.semantics { testTag = "account_row_archived_chip" },
                        )
                    }
                }
            }
            // 余额 mask —— 零知识
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            ) {
                Text(
                    text = stringResource(R.string.finance_dashboard_amount_mask),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.semantics { testTag = "account_row_balance_mask" },
                )
            }
        }
    }
}

/**
 * 静态类型 chip（不复用 onClick 槽位 —— 列表行 click 触发编辑）。
 */
@Composable
private fun AccountKindChipStatic(kind: String) {
    val label = when (kind) {
        "cash" -> R.string.finance_account_kind_cash
        "deposit" -> R.string.finance_account_kind_deposit
        "stock" -> R.string.finance_account_kind_stock
        "wallet" -> R.string.finance_account_kind_wallet
        else -> R.string.finance_account_kind_other
    }
    AssistChip(
        onClick = {},
        label = { Text(stringResource(label)) },
        colors = AssistChipDefaults.assistChipColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
        ),
    )
}

/**
 * 可点类型 chip（搜索 filter 用）。与 [AccountKindChipStatic] 拆分避免 click 嵌套。
 */
@Composable
private fun AccountKindChip(
    kind: String,
    label: String,
    selected: String,
    onSelect: (String) -> Unit,
) {
    FilterChip(
        selected = selected == kind,
        onClick = { onSelect(kind) },
        label = { Text(label) },
        modifier = Modifier.semantics { testTag = "account_filter_chip_$kind" },
    )
}

/**
 * 排序 chip（共用，Card/Tx 列表复用）。
 */
@Composable
fun SortChip(
    key: FinanceSortKey,
    labelRes: Int,
    selected: FinanceSortKey,
    onSelect: (FinanceSortKey) -> Unit,
) {
    FilterChip(
        selected = selected == key,
        onClick = { onSelect(key) },
        label = { Text(stringResource(labelRes)) },
        modifier = Modifier.semantics { testTag = "sort_chip_$key" },
    )
}
