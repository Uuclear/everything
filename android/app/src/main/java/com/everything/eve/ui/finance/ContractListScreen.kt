/*
 * ============================================================================
 * ContractListScreen —— 合同/发票列表（stage5-finance-v2 / Task 4 / TR-2.4）
 * ============================================================================
 *
 * 设计要点：
 *   1. **展示合同条目**：title + counterparty + kind chip + status chip + 距到期天数；
 *   2. **零知识（spec NFR-1）**：金额位置统一 `****` 占位；不显示具体日期数字；
 *   3. **新建/编辑入口**：FAB → 编辑器新建模式；行 click → 编辑器编辑模式。
 *
 * 关联：
 *   - tasks.md TR-2.4
 *   - ui/finance/FinanceViewModel.state.contracts
 *   - finance/ContractRecord
 *
 * 编码纪律：
 *   - 严禁 `--`（双连字符）；用全角破折号 `——` 或 `==========` 替代。
 *   - 不引入新依赖；复用项目已有 Material3 / Compose 组件。
 *
 * TODO(B2-strings): 等待 SA-4 strings.xml 替换为 R.string.xxx；本文件内
 *   中文临时值由后续 strings.xml 集中维护。
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
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import com.everything.eve.finance.ContractRecord
import kotlin.math.ceil

/**
 * 合同/发票列表 Composable（TR-2.4）。
 *
 * @param vm 共享的 FinanceViewModel。
 * @param onAdd 新建合同触发。
 * @param onEdit 编辑已存在合同。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContractListScreen(
    vm: FinanceViewModel,
    onAdd: () -> Unit,
    onEdit: (entityId: String) -> Unit,
) {
    val state by vm.state.collectAsState()
    val list = state.contracts

    Scaffold(
        topBar = {
            TopAppBar(title = { Text(stringResource(R.string.finance_tab_accounts) /* TODO(B2-strings): R.string.finance_v2_tab_contracts */) })
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = onAdd,
                modifier = Modifier.semantics { testTag = "contract_list_fab_add" },
            ) { Icon(Icons.Default.Add, contentDescription = null) }
        },
    ) { padding ->
        if (list.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    // TODO(B2-strings): R.string.finance_v2_empty_contract
                    text = "暂未合同/发票，点击右下角按钮新建",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.semantics { testTag = "contract_list_empty" },
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 12.dp)
                    .semantics { testTag = "contract_list" },
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(items = list, key = { it.id }) { con ->
                    ContractRow(con = con, onClick = { onEdit(con.id) })
                }
            }
        }
    }
}

/**
 * 单行合同卡片：标题 + 对手方 + 类型 chip + 状态 chip + 距结束天数 + 金额 mask。
 */
@Composable
private fun ContractRow(con: ContractRecord, onClick: () -> Unit) {
    val days = daysUntil(con.endTs)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .semantics { testTag = "contract_row_${con.id}" },
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
                Text(con.title.ifBlank { "(未命名)" }, style = MaterialTheme.typography.titleSmall)
                Text(con.counterparty, style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    ContractKindChip(con.kind)
                    ContractStatusChip(con.status)
                    if (con.autoRenew) {
                        // TODO(B2-strings): R.string.finance_v2_auto_renew_chip
                        AssistChip(
                            onClick = {},
                            label = { Text("自动续约") },
                            colors = AssistChipDefaults.assistChipColors(
                                containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                            ),
                            modifier = Modifier.semantics { testTag = "contract_row_auto_renew_chip" },
                        )
                    }
                    AssistChip(
                        onClick = {},
                        label = {
                            // TODO(B2-strings): R.string.finance_v2_days_until_format
                            Text("剩 $days 天")
                        },
                        colors = AssistChipDefaults.assistChipColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        ),
                        modifier = Modifier.semantics { testTag = "contract_row_days_chip" },
                    )
                }
            }
            // 金额 mask —— 零知识
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            ) {
                Text(
                    text = stringResource(R.string.finance_dashboard_amount_mask),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.semantics { testTag = "contract_row_amount_mask" },
                )
            }
        }
    }
}

/**
 * 合同类型 chip（rental / service / purchase / loan / other）。
 */
@Composable
private fun ContractKindChip(kind: String) {
    // TODO(B2-strings): R.string.finance_v2_contract_kind_*
    val label = when (kind) {
        "rental" -> "租赁"
        "service" -> "服务"
        "purchase" -> "采购"
        "loan" -> "借款合同"
        else -> "其他"
    }
    AssistChip(
        onClick = {},
        label = { Text(label) },
        colors = AssistChipDefaults.assistChipColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
        ),
    )
}

/**
 * 合同状态 chip（active / expired / terminated / renewed）。
 */
@Composable
private fun ContractStatusChip(status: String) {
    // TODO(B2-strings): R.string.finance_v2_contract_status_*
    val label = when (status) {
        "active" -> "生效中"
        "expired" -> "已到期"
        "terminated" -> "已终止"
        "renewed" -> "已续约"
        else -> status
    }
    AssistChip(
        onClick = {},
        label = { Text(label) },
        colors = AssistChipDefaults.assistChipColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
        ),
    )
}

/**
 * 计算 ts 距现在的天数（向上取整；负数表示已过期）。
 */
private fun daysUntil(ts: Long): Int {
    val now = System.currentTimeMillis()
    return ceil((ts - now).toDouble() / 86_400_000.0).toInt()
}