/*
 * ============================================================================
 * LoanEditorScreen —— 应收借款编辑器（stage5-finance-v2 / Task 4 / TR-2.3）
 * ============================================================================
 *
 * 设计要点：
 *   1. **本地 EditorBuffer 状态**：编辑器内部用 `mutableStateOf` 持有
 *      [LoanEditorBuffer]；保存时调 [FinanceViewModel.upsertLoan]；
 *      删除走 [FinanceViewModel.deleteLoan]。
 *   2. **校验**：[FinanceRecords.validateLoan] 失败时直接弹错文案
 *      （testTag = `loan_editor_error`）。
 *   3. **零知识（spec NFR-1）**：保存 / 删除 / 错误文案不渲染 principal
 *      / paid / interestRate 明文。
 *
 * 关联：
 *   - tasks.md TR-2.3
 *   - ui/finance/FinanceViewModel.upsertLoan / deleteLoan
 *   - finance/FinanceRecords.validateLoan
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import com.everything.eve.R
import com.everything.eve.finance.FINANCE_V2_SCHEMA_VERSION
import com.everything.eve.finance.LoanRecord
import kotlinx.coroutines.launch

/**
 * 应收借款编辑器 Composable（spec FR-3 / TR-2.3）。
 *
 * @param id 已存在 loan id；新建传 null。
 * @param vm FinanceViewModel（提供 upsertLoan / deleteLoan）。
 * @param onClose 保存 / 删除 / 取消后回调。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoanEditorScreen(
    id: String?,
    vm: FinanceViewModel,
    onClose: () -> Unit,
) {
    val state by vm.state.collectAsState()
    val existing: LoanRecord? = remember(id, state.loans) {
        id?.let { eid -> state.loans.firstOrNull { it.id == eid } }
    }

    var buffer by remember(id) {
        mutableStateOf(
            existing?.toEditorBuffer() ?: LoanEditorBuffer(id = vm.newId())
        )
    }

    var errorText by remember { mutableStateOf<String?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    // TODO(B2-strings): R.string.finance_v2_loan_editor_title_*
                    Text(if (id == null) "新建借款" else "编辑借款")
                },
                navigationIcon = {
                    TextButton(
                        onClick = onClose,
                        modifier = Modifier.semantics { testTag = "loan_editor_close_btn" },
                    ) {
                        // TODO(B2-strings): R.string.finance_editor_close_btn
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
            LoanEditorFields(
                buffer = buffer,
                onChange = { buffer = it },
            )

            errorText?.let { err ->
                Text(
                    text = err,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.semantics { testTag = "loan_editor_error" },
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = {
                        val now = System.currentTimeMillis()
                        val record = LoanRecord(
                            id = buffer.id,
                            schemaVersion = FINANCE_V2_SCHEMA_VERSION,
                            counterparty = buffer.counterparty.trim(),
                            principalMinor = buffer.principalMinor.ifBlank { "0" },
                            currency = buffer.currency.ifBlank { "CNY" },
                            direction = buffer.direction,
                            issueTs = buffer.issueTs,
                            dueTs = buffer.dueTs,
                            interestRateApyBps = buffer.interestRateApyBps,
                            status = buffer.status,
                            paidMinor = buffer.paidMinor.ifBlank { "0" },
                            reminders = buffer.reminders,
                            linkedAccountId = buffer.linkedAccountId?.takeIf { it.isNotBlank() },
                            includeInNetAssets = buffer.includeInNetAssets,
                            createdAt = existing?.createdAt ?: now,
                            updatedAt = now,
                        )
                        val result = vm.upsertLoan(record)
                        if (result.isSuccess) {
                            onClose()
                        } else {
                            val reason = (result.exceptionOrNull()?.message) ?: "loan_invalid"
                            errorText = reason
                            scope.launch {
                                snackbarHostState.showSnackbar("保存失败：$reason")
                            }
                        }
                    },
                    modifier = Modifier
                        .weight(1f)
                        .semantics { testTag = "loan_editor_save_btn" },
                ) {
                    // TODO(B2-strings): R.string.finance_editor_save_btn
                    Text("保存")
                }

                if (id != null) {
                    OutlinedButton(
                        onClick = {
                            val result = vm.deleteLoan(id)
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
                            .semantics { testTag = "loan_editor_delete_btn" },
                    ) {
                        // TODO(B2-strings): R.string.finance_editor_delete_btn
                        Text("删除")
                    }
                }
            }
        }
    }
}

/**
 * 借款编辑器字段集。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LoanEditorFields(
    buffer: LoanEditorBuffer,
    onChange: (LoanEditorBuffer) -> Unit,
) {
    // counterparty
    OutlinedTextField(
        value = buffer.counterparty,
        onValueChange = { onChange(buffer.copy(counterparty = it)) },
        // TODO(B2-strings): R.string.finance_v2_loan_field_counterparty
        label = { Text("对手方") },
        singleLine = true,
        modifier = Modifier
            .fillMaxWidth()
            .semantics { testTag = "loan_editor_counterparty" },
    )

    // principalMinor
    OutlinedTextField(
        value = buffer.principalMinor,
        onValueChange = { raw ->
            val sanitized = raw.filter { it.isDigit() || it == '.' }
            val firstDot = sanitized.indexOf('.')
            val normalized = if (firstDot >= 0) {
                sanitized.substring(0, firstDot + 1) +
                    sanitized.substring(firstDot + 1).replace(".", "")
            } else sanitized
            onChange(buffer.copy(principalMinor = normalized))
        },
        // TODO(B2-strings): R.string.finance_v2_loan_field_principal
        label = { Text("本金") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = Modifier
            .fillMaxWidth()
            .semantics { testTag = "loan_editor_principal" },
    )

    // direction —— 2 选：lent / borrowed
    // TODO(B2-strings): R.string.finance_v2_loan_field_direction
    Text("方向", style = MaterialTheme.typography.labelMedium)
    LoanDirectionDropdown(
        selected = buffer.direction,
        onSelect = { onChange(buffer.copy(direction = it)) },
    )

    // issueTs / dueTs
    OutlinedTextField(
        value = buffer.issueTs.toString(),
        onValueChange = { v ->
            val ts = v.toLongOrNull()
            if (ts != null) onChange(buffer.copy(issueTs = ts))
        },
        // TODO(B2-strings): R.string.finance_v2_loan_field_issue_ts
        label = { Text("起始时间戳（毫秒）") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier
            .fillMaxWidth()
            .semantics { testTag = "loan_editor_issue_ts" },
    )

    OutlinedTextField(
        value = buffer.dueTs.toString(),
        onValueChange = { v ->
            val ts = v.toLongOrNull()
            if (ts != null) onChange(buffer.copy(dueTs = ts))
        },
        // TODO(B2-strings): R.string.finance_v2_loan_field_due_ts
        label = { Text("到期时间戳（毫秒）") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier
            .fillMaxWidth()
            .semantics { testTag = "loan_editor_due_ts" },
    )

    // interestRateApyBps —— 整数
    OutlinedTextField(
        value = buffer.interestRateApyBps.toString(),
        onValueChange = { v ->
            val bps = v.toLongOrNull()
            if (bps != null && bps >= 0) onChange(buffer.copy(interestRateApyBps = bps))
        },
        // TODO(B2-strings): R.string.finance_v2_loan_field_interest_bps
        label = { Text("年化利率 bps（整数）") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier
            .fillMaxWidth()
            .semantics { testTag = "loan_editor_interest_rate" },
    )

    // status —— 4 选：active / partially_paid / paid / overdue
    // TODO(B2-strings): R.string.finance_v2_loan_field_status
    Text("状态", style = MaterialTheme.typography.labelMedium)
    LoanStatusDropdown(
        selected = buffer.status,
        onSelect = { onChange(buffer.copy(status = it)) },
    )

    // paidMinor
    OutlinedTextField(
        value = buffer.paidMinor,
        onValueChange = { raw ->
            val sanitized = raw.filter { it.isDigit() || it == '.' }
            val firstDot = sanitized.indexOf('.')
            val normalized = if (firstDot >= 0) {
                sanitized.substring(0, firstDot + 1) +
                    sanitized.substring(firstDot + 1).replace(".", "")
            } else sanitized
            onChange(buffer.copy(paidMinor = normalized))
        },
        // TODO(B2-strings): R.string.finance_v2_loan_field_paid
        label = { Text("已还本金") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = Modifier
            .fillMaxWidth()
            .semantics { testTag = "loan_editor_paid" },
    )
}

/**
 * 借款方向下拉菜单（2 选：lent / borrowed）。
 *
 * 实现说明：采用 Box + DropdownMenu 直接锚定模式；不依赖 ExposedDropdownMenu
 * 的扩展函数签名（不同 Material3 版本间存在差异），保证编译稳定。
 */
@Composable
private fun LoanDirectionDropdown(
    selected: String,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val options = listOf(
        "lent" to "借出",
        "borrowed" to "借入",
    )
    val displayLabel = options.firstOrNull { it.first == selected }?.second ?: selected

    Box(modifier = Modifier.semantics { testTag = "loan_editor_direction_dropdown" }) {
        OutlinedTextField(
            value = displayLabel,
            onValueChange = {},
            readOnly = true,
            label = { Text("方向") },
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
                    modifier = Modifier.semantics { testTag = "loan_editor_direction_option_$value" },
                )
            }
        }
    }
}

/**
 * 借款状态下拉菜单（4 选：active / partially_paid / paid / overdue）。
 */
@Composable
private fun LoanStatusDropdown(
    selected: String,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val options = listOf(
        "active" to "进行中",
        "partially_paid" to "部分已还",
        "paid" to "已结清",
        "overdue" to "已逾期",
    )
    val displayLabel = options.firstOrNull { it.first == selected }?.second ?: selected

    Box(modifier = Modifier.semantics { testTag = "loan_editor_status_dropdown" }) {
        OutlinedTextField(
            value = displayLabel,
            onValueChange = {},
            readOnly = true,
            label = { Text("状态") },
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
                    modifier = Modifier.semantics { testTag = "loan_editor_status_option_$value" },
                )
            }
        }
    }
}

/**
 * 把 LoanRecord 转 LoanEditorBuffer（编辑器加载已存在条目时使用）。
 */
private fun LoanRecord.toEditorBuffer(): LoanEditorBuffer =
    LoanEditorBuffer(
        id = id,
        counterparty = counterparty,
        principalMinor = principalMinor,
        currency = currency,
        direction = direction,
        issueTs = issueTs,
        dueTs = dueTs,
        interestRateApyBps = interestRateApyBps,
        status = status,
        paidMinor = paidMinor,
        reminders = reminders,
        linkedAccountId = linkedAccountId,
        includeInNetAssets = includeInNetAssets,
    )