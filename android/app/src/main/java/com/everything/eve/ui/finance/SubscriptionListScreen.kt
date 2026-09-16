/*
 * ============================================================================
 * SubscriptionListScreen —— 订阅列表（stage5-finance-v2 / Task 4 / TR-2.1）
 * ============================================================================
 *
 * 设计要点：
 *   1. **展示订阅条目**：name + provider + 周期 chip + 距下次续费天数；
 *   2. **零知识（spec NFR-1）**：金额位置统一 `****` 占位；
 *   3. **新建/编辑入口**：FAB → 编辑器新建模式；行 click → 编辑器编辑模式。
 *
 * 关联：
 *   - tasks.md TR-2.1
 *   - ui/finance/FinanceViewModel.state.subscriptions
 *   - finance/SubscriptionRecord
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
import com.everything.eve.finance.SubscriptionRecord
import kotlin.math.ceil

/**
 * 订阅列表 Composable（TR-2.1）。
 *
 * @param vm 共享的 FinanceViewModel。
 * @param onAdd 新建订阅触发（编辑器新建模式）。
 * @param onEdit 编辑已存在订阅（编辑器加载该订阅）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubscriptionListScreen(
    vm: FinanceViewModel,
    onAdd: () -> Unit,
    onEdit: (entityId: String) -> Unit,
) {
    // 订阅 state 订阅 —— 与 v1 列表同款模式
    val state by vm.state.collectAsState()
    val list = state.subscriptions

    Scaffold(
        topBar = {
            TopAppBar(title = { Text(stringResource(R.string.finance_tab_accounts) /* 临时复用：SA-4 strings.xml 替换为 R.string.finance_v2_tab_subscriptions */) })
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = onAdd,
                modifier = Modifier.semantics { testTag = "subscription_list_fab_add" },
            ) { Icon(Icons.Default.Add, contentDescription = null) }
        },
    ) { padding ->
        if (list.isEmpty()) {
            // 空态：spec FR-1 提示用户新建
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    // TODO(B2-strings): 替换为 R.string.finance_v2_empty_subscription
                    text = "暂无订阅，点击右下角按钮新建",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.semantics { testTag = "subscription_list_empty" },
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 12.dp)
                    .semantics { testTag = "subscription_list" },
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(items = list, key = { it.id }) { sub ->
                    SubscriptionRow(sub = sub, onClick = { onEdit(sub.id) })
                }
            }
        }
    }
}

/**
 * 单行订阅卡片：名称 + provider + 周期 chip + 距续费天数 + 金额 mask。
 *
 * 行 click 触发编辑器进入编辑模式。
 */
@Composable
private fun SubscriptionRow(sub: SubscriptionRecord, onClick: () -> Unit) {
    val days = daysUntil(sub.nextRenewalTs)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .semantics { testTag = "subscription_row_${sub.id}" },
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
            // 主标识 + 周期 chip
            Column(modifier = Modifier.weight(1f)) {
                Text(sub.name.ifBlank { "(未命名)" }, style = MaterialTheme.typography.titleSmall)
                Text(sub.provider, style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    SubscriptionCycleChip(sub.billingCycle)
                    // 距续费天数 chip —— 零知识不显示具体日期
                    AssistChip(
                        onClick = {},
                        label = {
                            // TODO(B2-strings): 替换为 R.string.finance_v2_days_until_format
                            Text("剩 $days 天")
                        },
                        colors = AssistChipDefaults.assistChipColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        ),
                        modifier = Modifier.semantics { testTag = "subscription_row_days_chip" },
                    )
                    if (!sub.active) {
                        AssistChip(
                            onClick = {},
                            // TODO(B2-strings): 替换为 R.string.finance_v2_inactive_chip
                            label = { Text("已停用") },
                            colors = AssistChipDefaults.assistChipColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            ),
                            modifier = Modifier.semantics { testTag = "subscription_row_inactive_chip" },
                        )
                    }
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
                    modifier = Modifier.semantics { testTag = "subscription_row_amount_mask" },
                )
            }
        }
    }
}

/**
 * 订阅周期 chip（静态展示，复用 AssistChip 而非可点 FilterChip，避免 click 嵌套）。
 */
@Composable
private fun SubscriptionCycleChip(cycle: String) {
    // TODO(B2-strings): 替换为 R.string.finance_v2_billing_cycle_*
    val label = when (cycle) {
        "monthly" -> "月付"
        "quarterly" -> "季付"
        "yearly" -> "年付"
        "custom_days" -> "自定义"
        else -> cycle
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