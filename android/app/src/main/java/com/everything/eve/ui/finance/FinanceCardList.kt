/*
 * ============================================================================
 * FinanceCardList —— 卡片列表（stage5-finance / Task 7 / TR-7.3）
 * ============================================================================
 *
 * 设计要点：
 *   1. **卡片项**：name + issuer + 卡组织 chip + usedLimit mask + 归档标记；
 *   2. **零知识（spec NFR-1）**：不渲染完整 PAN，不渲染 usedLimit 数字；
 *      last4 是已通过 Luhn 的末四位 —— 编辑器失焦后即丢弃完整 PAN。
 *   3. **品牌 / 类型筛选**：借记卡 / 信用卡 + visa / mastercard 等品牌联合筛选。
 *
 * 关联：
 *   - tasks.md TR-7.3
 *   - ui/finance/FinanceViewModel.filteredCards
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
import com.everything.eve.data.finance.entity.FinanceCardEntity

/**
 * 财务卡片列表 Composable（TR-7.3）。
 *
 * @param vm 共享的 FinanceViewModel。
 * @param onAdd 新建卡片触发（编辑器新建模式）。
 * @param onEdit 编辑已存在卡片。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FinanceCardList(
    vm: FinanceViewModel,
    onAdd: () -> Unit,
    onEdit: (entityId: String) -> Unit,
) {
    val state by vm.state.collectAsState()
    val list = vm.filteredCards(state)

    Scaffold(
        topBar = {
            TopAppBar(title = { Text(stringResource(R.string.finance_tab_cards)) })
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = onAdd,
                modifier = Modifier.semantics { testTag = "card_list_fab_add" },
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
                    .semantics { testTag = "card_list_search" },
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                CardKindChip("all", stringResource(R.string.finance_list_filter_all), state.filterKind) { vm.setFilter(it) }
                CardKindChip("credit", stringResource(R.string.finance_card_kind_credit), state.filterKind) { vm.setFilter(it) }
                CardKindChip("debit", stringResource(R.string.finance_card_kind_debit), state.filterKind) { vm.setFilter(it) }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                CardBrandChip("all", stringResource(R.string.finance_list_filter_all), state.filterKind) { vm.setFilter(it) }
                CardBrandChip("visa", stringResource(R.string.finance_card_brand_visa), state.filterKind) { vm.setFilter(it) }
                CardBrandChip("mastercard", stringResource(R.string.finance_card_brand_mastercard), state.filterKind) { vm.setFilter(it) }
                CardBrandChip("unionpay", stringResource(R.string.finance_card_brand_unionpay), state.filterKind) { vm.setFilter(it) }
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
                    modifier = Modifier.semantics { testTag = "card_list_empty" },
                )
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .semantics { testTag = "card_list" },
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(items = list, key = { it.id }) { card ->
                        CardRow(card = card, onClick = { onEdit(card.id) })
                    }
                }
            }
        }
    }
}

/**
 * 单行卡片项：卡片名 + 发卡行 + 卡组织 chip + 末四位 mask + 归档 chip。
 */
@Composable
private fun CardRow(card: FinanceCardEntity, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .semantics { testTag = "card_row_${card.id}" },
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
                Text(card.name.ifBlank { "(未命名)" }, style = MaterialTheme.typography.titleSmall)
                Text(card.issuer, style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    CardKindChipStatic(card.kind)
                    CardBrandChipStatic(card.brand)
                    // last4 渲染为 **** 占位 —— 已存末四位不展示具体数字（NFR-1）
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    ) {
                        Text(
                            text = "**** " + stringResource(R.string.finance_dashboard_amount_mask),
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.semantics { testTag = "card_row_last4_mask" },
                        )
                    }
                    if (card.archived) {
                        AssistChip(
                            onClick = {},
                            label = { Text(stringResource(R.string.finance_list_archived_chip)) },
                            colors = AssistChipDefaults.assistChipColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            ),
                            modifier = Modifier.semantics { testTag = "card_row_archived_chip" },
                        )
                    }
                }
            }
            // 已用额度 mask —— 零知识（spec NFR-1）
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            ) {
                Text(
                    text = stringResource(R.string.finance_dashboard_amount_mask),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.semantics { testTag = "card_row_used_mask" },
                )
            }
        }
    }
}

@Composable
private fun CardKindChipStatic(kind: String) {
    val label = if (kind == "credit") R.string.finance_card_kind_credit else R.string.finance_card_kind_debit
    AssistChip(
        onClick = {},
        label = { Text(stringResource(label)) },
        colors = AssistChipDefaults.assistChipColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
        ),
    )
}

@Composable
private fun CardKindChip(
    kind: String,
    label: String,
    selected: String,
    onSelect: (String) -> Unit,
) {
    FilterChip(
        selected = selected == kind,
        onClick = { onSelect(kind) },
        label = { Text(label) },
        modifier = Modifier.semantics { testTag = "card_kind_chip_$kind" },
    )
}

@Composable
private fun CardBrandChipStatic(brand: String?) {
    val label = when (brand) {
        "visa" -> R.string.finance_card_brand_visa
        "mastercard" -> R.string.finance_card_brand_mastercard
        "unionpay" -> R.string.finance_card_brand_unionpay
        "amex" -> R.string.finance_card_brand_amex
        "jcb" -> R.string.finance_card_brand_jcb
        "discover" -> R.string.finance_card_brand_discover
        else -> R.string.finance_card_brand_other
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
private fun CardBrandChip(
    brand: String,
    label: String,
    selected: String,
    onSelect: (String) -> Unit,
) {
    FilterChip(
        selected = selected == brand,
        onClick = { onSelect(brand) },
        label = { Text(label) },
        modifier = Modifier.semantics { testTag = "card_brand_chip_$brand" },
    )
}
