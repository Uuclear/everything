/*
 * ============================================================================
 * SubscriptionEditorScreen —— 订阅编辑器（stage5-finance-v2 / Task 4 / TR-2.1）
 * ============================================================================
 *
 * 设计要点：
 *   1. **本地 EditorBuffer 状态**：编辑器内部用 `mutableStateOf` 持有
 *      [SubscriptionEditorBuffer]；保存时调 [FinanceViewModel.upsertSubscription]；
 *      删除走 [FinanceViewModel.deleteSubscription]。
 *   2. **校验**：[FinanceRecords.validateSubscription] 失败时直接弹错文案
 *      （testTag = `subscription_editor_error`）。
 *   3. **零知识（spec NFR-1）**：保存按钮 / 删除按钮均不渲染 amount 明文到
 *      日志/snackbar；错文案仅 reason。
 *
 * 关联：
 *   - tasks.md TR-2.1
 *   - ui/finance/FinanceViewModel.upsertSubscription / deleteSubscription
 *   - finance/FinanceRecords.validateSubscription
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

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import com.everything.eve.R
import com.everything.eve.finance.FINANCE_V2_SCHEMA_VERSION
import com.everything.eve.finance.SubscriptionRecord
import com.everything.eve.finance.ValidationResult
import kotlinx.coroutines.launch

/**
 * 订阅编辑器 Composable（spec FR-3 / TR-2.1）。
 *
 * @param id 已存在 subscription id；新建传 null。
 * @param vm FinanceViewModel（提供 upsertSubscription / deleteSubscription）。
 * @param onClose 保存 / 删除 / 取消后回调（外层 NavHost 清栈）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubscriptionEditorScreen(
    id: String?,
    vm: FinanceViewModel,
    onClose: () -> Unit,
) {
    // 加载当前 state —— 编辑器从 state 中 find 初始 record
    val state by vm.state.collectAsState()
    val existing: SubscriptionRecord? = remember(id, state.subscriptions) {
        id?.let { eid -> state.subscriptions.firstOrNull { it.id == eid } }
    }

    // 初始化本地 buffer —— 复用 SubscriptionEditorBuffer（同款 copy / remember 模式）
    var buffer by remember(id) {
        mutableStateOf(
            existing?.toEditorBuffer() ?: SubscriptionEditorBuffer(id = vm.newId())
        )
    }

    // 校验错误（null = 通过）
    var errorText by remember { mutableStateOf<String?>(null) }

    // Snackbar
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    // TODO(B2-strings): 替换为 R.string.finance_v2_subscription_editor_title_*
                    Text(if (id == null) "新建订阅" else "编辑订阅")
                },
                navigationIcon = {
                    TextButton(
                        onClick = onClose,
                        modifier = Modifier.semantics { testTag = "subscription_editor_close_btn" },
                    ) {
                        // TODO(B2-strings): 替换为 R.string.finance_editor_close_btn
                        Text("关闭")
                    }
                },
            )
        },
        snackbarHost = {
            SnackbarHost(snackbarHostState) { data ->
                Snackbar(snackbarData = data)
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SubscriptionEditorFields(
                buffer = buffer,
                onChange = { buffer = it },
            )

            // 校验错误显示
            errorText?.let { err ->
                Text(
                    text = err,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.semantics { testTag = "subscription_editor_error" },
                )
            }

            // 保存 + 删除按钮行
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = {
                        // 构造 record 并 upsert
                        val now = System.currentTimeMillis()
                        val record = SubscriptionRecord(
                            id = buffer.id,
                            schemaVersion = FINANCE_V2_SCHEMA_VERSION,
                            name = buffer.name.trim(),
                            provider = buffer.provider.trim(),
                            amountMinor = buffer.amountMinor.ifBlank { "0" },
                            currency = buffer.currency.ifBlank { "CNY" },
                            billingCycle = buffer.billingCycle,
                            customDays = if (buffer.billingCycle == "custom_days") buffer.customDays else null,
                            startTs = buffer.startTs,
                            nextRenewalTs = buffer.nextRenewalTs,
                            reminders = buffer.reminders,
                            active = buffer.active,
                            category = buffer.category.ifBlank { "other" },
                            createdAt = existing?.createdAt ?: now,
                            updatedAt = now,
                        )
                        val result = vm.upsertSubscription(record)
                        if (result.isSuccess) {
                            onClose()
                        } else {
                            val reason = (result.exceptionOrNull()?.message)
                                ?: "subscription_invalid"
                            errorText = reason
                            scope.launch {
                                snackbarHostState.showSnackbar("保存失败：$reason")
                            }
                        }
                    },
                    modifier = Modifier
                        .weight(1f)
                        .semantics { testTag = "subscription_editor_save_btn" },
                ) {
                    // TODO(B2-strings): 替换为 R.string.finance_editor_save_btn
                    Text("保存")
                }

                if (id != null) {
                    OutlinedButton(
                        onClick = {
                            val result = vm.deleteSubscription(id)
                            if (result.isSuccess) {
                                onClose()
                            } else {
                                val reason = (result.exceptionOrNull()?.message) ?: "delete_failed"
                                scope.launch {
                                    snackbarHostState.showSnackbar("删除失败：$reason")
                                }
                            }
                        },
                        modifier = Modifier
                            .weight(1f)
                            .semantics { testTag = "subscription_editor_delete_btn" },
                    ) {
                        // TODO(B2-strings): 替换为 R.string.finance_editor_delete_btn
                        Text("删除")
                    }
                }
            }
        }
    }
}

/**
 * 订阅编辑器字段集（name / provider / amount / cycle / customDays / start / renewal / active）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SubscriptionEditorFields(
    buffer: SubscriptionEditorBuffer,
    onChange: (SubscriptionEditorBuffer) -> Unit,
) {
    // 名称
    OutlinedTextField(
        value = buffer.name,
        onValueChange = { onChange(buffer.copy(name = it)) },
        // TODO(B2-strings): 替换为 R.string.finance_v2_subscription_field_name
        label = { Text("订阅名称") },
        singleLine = true,
        modifier = Modifier
            .fillMaxWidth()
            .semantics { testTag = "subscription_editor_name" },
    )

    // provider
    OutlinedTextField(
        value = buffer.provider,
        onValueChange = { onChange(buffer.copy(provider = it)) },
        // TODO(B2-strings): 替换为 R.string.finance_v2_subscription_field_provider
        label = { Text("服务商") },
        singleLine = true,
        modifier = Modifier
            .fillMaxWidth()
            .semantics { testTag = "subscription_editor_provider" },
    )

    // amount —— 小数 OutlinedTextField + toString 校验（容错：非数字字符丢弃）
    OutlinedTextField(
        value = buffer.amountMinor,
        onValueChange = { raw ->
            val sanitized = raw.filter { it.isDigit() || it == '.' }
            // 限制仅 1 个小数点
            val firstDot = sanitized.indexOf('.')
            val normalized = if (firstDot >= 0) {
                sanitized.substring(0, firstDot + 1) +
                    sanitized.substring(firstDot + 1).replace(".", "")
            } else sanitized
            onChange(buffer.copy(amountMinor = normalized))
        },
        // TODO(B2-strings): 替换为 R.string.finance_v2_subscription_field_amount
        label = { Text("金额") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = Modifier
            .fillMaxWidth()
            .semantics { testTag = "subscription_editor_amount" },
    )

    // billingCycle —— ExposedDropdownMenuBox 4 选
    // TODO(B2-strings): 替换为 R.string.finance_v2_subscription_field_cycle
    Text("结算周期", style = MaterialTheme.typography.labelMedium)
    SubscriptionCycleDropdown(
        selected = buffer.billingCycle,
        onSelect = { onChange(buffer.copy(billingCycle = it)) },
    )

    // customDays —— 仅 billingCycle=custom_days 时启用
    OutlinedTextField(
        value = buffer.customDays?.toString().orEmpty(),
        onValueChange = { v ->
            val days = v.toLongOrNull()
            onChange(buffer.copy(customDays = days?.takeIf { it > 0 }))
        },
        // TODO(B2-strings): 替换为 R.string.finance_v2_subscription_field_custom_days
        label = { Text("自定义天数") },
        singleLine = true,
        enabled = buffer.billingCycle == "custom_days",
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier
            .fillMaxWidth()
            .semantics { testTag = "subscription_editor_custom_days" },
    )

    // startTs / nextRenewalTs —— 简化为整数毫秒框（避免引入日期 picker）
    OutlinedTextField(
        value = buffer.startTs.toString(),
        onValueChange = { v ->
            val ts = v.toLongOrNull()
            if (ts != null) onChange(buffer.copy(startTs = ts))
        },
        // TODO(B2-strings): 替换为 R.string.finance_v2_subscription_field_start_ts
        label = { Text("起始时间戳（毫秒）") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier
            .fillMaxWidth()
            .semantics { testTag = "subscription_editor_start_ts" },
    )

    OutlinedTextField(
        value = buffer.nextRenewalTs.toString(),
        onValueChange = { v ->
            val ts = v.toLongOrNull()
            if (ts != null) onChange(buffer.copy(nextRenewalTs = ts))
        },
        // TODO(B2-strings): 替换为 R.string.finance_v2_subscription_field_next_renewal_ts
        label = { Text("下次续费时间戳（毫秒）") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier
            .fillMaxWidth()
            .semantics { testTag = "subscription_editor_next_renewal_ts" },
    )

    // active Switch
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // TODO(B2-strings): 替换为 R.string.finance_v2_subscription_field_active
        Text("启用", modifier = Modifier.weight(1f))
        Switch(
            checked = buffer.active,
            onCheckedChange = { onChange(buffer.copy(active = it)) },
            modifier = Modifier.semantics { testTag = "subscription_editor_active_switch" },
        )
    }
}

/**
 * 订阅周期下拉菜单（4 选：monthly / quarterly / yearly / custom_days）。
 *
 * 实现说明：采用 Box + DropdownMenu 直接锚定模式；不依赖 ExposedDropdownMenu
 * 的扩展函数签名（不同 Material3 版本间存在差异），保证编译稳定。
 */
@Composable
private fun SubscriptionCycleDropdown(
    selected: String,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val options = listOf(
        "monthly" to "月付",
        "quarterly" to "季付",
        "yearly" to "年付",
        "custom_days" to "自定义",
    )
    val displayLabel = options.firstOrNull { it.first == selected }?.second ?: selected

    Box(modifier = Modifier.semantics { testTag = "subscription_editor_cycle_dropdown" }) {
        OutlinedTextField(
            value = displayLabel,
            onValueChange = {},
            readOnly = true,
            label = { Text("周期") },
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = true },
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            options.forEach { (value, label) ->
                DropdownMenuItem(
                    text = { Text(label) },
                    onClick = {
                        onSelect(value)
                        expanded = false
                    },
                    modifier = Modifier.semantics { testTag = "subscription_editor_cycle_option_$value" },
                )
            }
        }
    }
}

/**
 * 把 SubscriptionRecord 转 SubscriptionEditorBuffer（编辑器加载已存在条目时使用）。
 */
private fun SubscriptionRecord.toEditorBuffer(): SubscriptionEditorBuffer =
    SubscriptionEditorBuffer(
        id = id,
        name = name,
        provider = provider,
        amountMinor = amountMinor,
        currency = currency,
        billingCycle = billingCycle,
        customDays = customDays,
        startTs = startTs,
        nextRenewalTs = nextRenewalTs,
        reminders = reminders,
        active = active,
        category = category,
    )