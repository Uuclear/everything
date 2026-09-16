/*
 * ============================================================================
 * FinanceEditor —— 财务编辑器（stage5-finance / Task 7 / TR-7.1 + TR-7.5）
 * ============================================================================
 *
 * 设计要点：
 *   1. **三种 Composable 分发**：[FinanceEditor] 入口根据 [kind] 选择调用
 *      [AccountEditor] / [CardEditor] / [TxEditor]，共享 [EditorFormShell] 外壳
 *      （save / cancel / delete 三按钮 + Scaffold）。
 *   2. **本地 EditorBuffer 状态**：每个 Editor 内部用 `mutableStateOf` 持有
 *      编辑器缓冲；保存时调 [FinanceViewModel.saveBuffer]；删除走
 *      [FinanceViewModel.deleteEntity]。
 *   3. **Luhn 实时校验（卡片专用）**：PAN 输入实时 strip + Luhn 校验，
 *      通过后即填 last4；完整 PAN 仅驻留本地 state，保存后即丢弃。
 *   4. **零知识（spec NFR-1）**：保存按钮 / 删除按钮均不渲染 name / last4 /
 *      amount 等明文到日志/snackbar；错文案仅 code。
 *
 * 关联：
 *   - tasks.md TR-7.1 / TR-7.5
 *   - ui/finance/FinanceViewModel.bufferFor + saveBuffer + deleteEntity
 * ============================================================================
 */

package com.everything.eve.ui.finance

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import com.everything.eve.R
import com.everything.eve.finance.Luhn

/**
 * 编辑器入口 Composable（spec FR-1 / FR-3 / FR-4）。
 *
 * @param kind 编辑器类型（ACCOUNT / CARD / TX）。
 * @param entityId 已存在 entity id（新建传 null）。
 * @param vm FinanceViewModel（提供 saveBuffer / deleteEntity / bufferFor）。
 * @param onDone 保存 / 删除结束后回调（外层 NavHost 清栈）。
 * @param onCancel 用户取消（外层 NavHost 清栈）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FinanceEditor(
    kind: FinanceEditorKind,
    entityId: String?,
    vm: FinanceViewModel,
    onDone: () -> Unit,
    onCancel: () -> Unit,
) {
    // 初始化 EditorBuffer
    var buffer by remember(entityId) {
        mutableStateOf(
            entityId?.let { vm.bufferFor(kind, it) }
                ?: FinanceEditorBuffer(id = vm.newId(), kind = kind)
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when (kind) {
                            FinanceEditorKind.ACCOUNT -> if (entityId == null) stringResource(R.string.finance_account_editor_title_new)
                                else stringResource(R.string.finance_account_editor_title_edit)
                            FinanceEditorKind.CARD -> if (entityId == null) stringResource(R.string.finance_card_editor_title_new)
                                else stringResource(R.string.finance_card_editor_title_edit)
                            FinanceEditorKind.TX -> if (entityId == null) stringResource(R.string.finance_tx_editor_title_new)
                                else stringResource(R.string.finance_tx_editor_title_edit)
                        }
                    )
                },
                navigationIcon = {
                    TextButton(
                        onClick = onCancel,
                        modifier = Modifier.semantics { testTag = "editor_cancel_btn" },
                    ) { Text(stringResource(R.string.finance_editor_cancel_btn)) }
                },
            )
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
            when (kind) {
                FinanceEditorKind.ACCOUNT -> AccountFields(
                    buffer = buffer,
                    onChange = { buffer = it },
                )
                FinanceEditorKind.CARD -> CardFields(
                    buffer = buffer,
                    onChange = { buffer = it },
                )
                FinanceEditorKind.TX -> TxFields(
                    buffer = buffer,
                    accounts = vm.state.collectAsState().value.accounts,
                    onChange = { buffer = it },
                )
            }

            EditorFormShell(
                isNew = entityId == null,
                onSave = {
                    vm.saveBuffer(buffer)
                    onDone()
                },
                onDelete = {
                    entityId?.let { vm.deleteEntity(kind, it) }
                    onDone()
                },
            )
        }
    }
}

// =============================================================================
// 编辑器底部操作行（save + delete）
// =============================================================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditorFormShell(
    isNew: Boolean,
    onSave: () -> Unit,
    onDelete: () -> Unit,
) {
    var showDeleteConfirm by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Button(
            onClick = onSave,
            modifier = Modifier
                .weight(1f)
                .semantics { testTag = "editor_save_btn" },
        ) { Text(stringResource(R.string.finance_editor_save_btn)) }

        if (!isNew) {
            OutlinedButton(
                onClick = { showDeleteConfirm = true },
                modifier = Modifier
                    .weight(1f)
                    .semantics { testTag = "editor_delete_btn" },
            ) { Text(stringResource(R.string.finance_editor_delete_btn)) }
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text(stringResource(R.string.finance_editor_delete_confirm_title)) },
            text = { Text(stringResource(R.string.finance_editor_delete_confirm_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteConfirm = false
                        onDelete()
                    },
                    modifier = Modifier.semantics { testTag = "editor_delete_confirm_btn" },
                ) { Text(stringResource(R.string.finance_editor_delete_btn)) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text(stringResource(R.string.finance_editor_cancel_btn))
                }
            },
        )
    }
}

// =============================================================================
// AccountEditor（账户字段）
// =============================================================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AccountFields(
    buffer: FinanceEditorBuffer,
    onChange: (FinanceEditorBuffer) -> Unit,
) {
    OutlinedTextField(
        value = buffer.name,
        onValueChange = { onChange(buffer.copy(name = it)) },
        label = { Text(stringResource(R.string.finance_account_field_name)) },
        singleLine = true,
        modifier = Modifier
            .fillMaxWidth()
            .semantics { testTag = "account_editor_name" },
    )

    // 类型 chip
    Text(
        text = stringResource(R.string.finance_account_field_kind),
        style = MaterialTheme.typography.labelMedium,
        modifier = Modifier.semantics { testTag = "account_editor_kind_label" },
    )
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        AccountKindRadioChip("cash", stringResource(R.string.finance_account_kind_cash), buffer.itemKind) { onChange(buffer.copy(itemKind = it)) }
        AccountKindRadioChip("deposit", stringResource(R.string.finance_account_kind_deposit), buffer.itemKind) { onChange(buffer.copy(itemKind = it)) }
        AccountKindRadioChip("stock", stringResource(R.string.finance_account_kind_stock), buffer.itemKind) { onChange(buffer.copy(itemKind = it)) }
        AccountKindRadioChip("wallet", stringResource(R.string.finance_account_kind_wallet), buffer.itemKind) { onChange(buffer.copy(itemKind = it)) }
        AccountKindRadioChip("other", stringResource(R.string.finance_account_kind_other), buffer.itemKind) { onChange(buffer.copy(itemKind = it)) }
    }

    OutlinedTextField(
        value = buffer.currency,
        onValueChange = { onChange(buffer.copy(currency = it.take(3))) },
        label = { Text(stringResource(R.string.finance_account_field_currency)) },
        singleLine = true,
        modifier = Modifier
            .fillMaxWidth()
            .semantics { testTag = "account_editor_currency" },
    )

    OutlinedTextField(
        value = buffer.balance,
        onValueChange = { onChange(buffer.copy(balance = it)) },
        label = { Text(stringResource(R.string.finance_account_field_balance)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = Modifier
            .fillMaxWidth()
            .semantics { testTag = "account_editor_balance" },
    )

    OutlinedTextField(
        value = buffer.note,
        onValueChange = { onChange(buffer.copy(note = it)) },
        label = { Text(stringResource(R.string.finance_account_field_note)) },
        singleLine = false,
        modifier = Modifier
            .fillMaxWidth()
            .semantics { testTag = "account_editor_note" },
    )

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(R.string.finance_account_field_archived), modifier = Modifier.weight(1f))
        Switch(
            checked = buffer.archived,
            onCheckedChange = { onChange(buffer.copy(archived = it)) },
            modifier = Modifier.semantics { testTag = "account_editor_archived_switch" },
        )
    }
}

@Composable
private fun AccountKindRadioChip(
    kind: String,
    label: String,
    selected: String,
    onSelect: (String) -> Unit,
) {
    FilterChip(
        selected = selected == kind,
        onClick = { onSelect(kind) },
        label = { Text(label) },
        modifier = Modifier.semantics { testTag = "account_editor_kind_chip_$kind" },
    )
}

// =============================================================================
// CardEditor（卡片字段 + Luhn 实时校验）
// =============================================================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CardFields(
    buffer: FinanceEditorBuffer,
    onChange: (FinanceEditorBuffer) -> Unit,
) {
    OutlinedTextField(
        value = buffer.name,
        onValueChange = { onChange(buffer.copy(name = it)) },
        label = { Text(stringResource(R.string.finance_card_field_name)) },
        singleLine = true,
        modifier = Modifier
            .fillMaxWidth()
            .semantics { testTag = "card_editor_name" },
    )

    // 类型 chip
    Text(stringResource(R.string.finance_card_field_kind), style = MaterialTheme.typography.labelMedium)
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        CardKindRadioChip("credit", stringResource(R.string.finance_card_kind_credit), buffer.itemKind) { onChange(buffer.copy(itemKind = it)) }
        CardKindRadioChip("debit", stringResource(R.string.finance_card_kind_debit), buffer.itemKind) { onChange(buffer.copy(itemKind = it)) }
    }

    OutlinedTextField(
        value = buffer.issuer,
        onValueChange = { onChange(buffer.copy(issuer = it)) },
        label = { Text(stringResource(R.string.finance_card_field_issuer)) },
        singleLine = true,
        modifier = Modifier
            .fillMaxWidth()
            .semantics { testTag = "card_editor_issuer" },
    )

    // ========== PAN 输入 + 实时 Luhn 校验 ==========
    // PAN 完整值仅驻留本地 state（pan 字段），保存到 Room 时仅 last4 入库。
    OutlinedTextField(
        value = buffer.pan,
        onValueChange = { pan ->
            val newBuffer = buffer.copy(pan = pan)
            val last4 = if (pan.length >= 13 && Luhn.luhnValidate(pan)) {
                Luhn.extractLast4(pan)
            } else null
            onChange(newBuffer.copy(last4 = last4 ?: buffer.last4))
        },
        label = { Text(stringResource(R.string.finance_card_field_pan)) },
        placeholder = { Text(stringResource(R.string.finance_card_field_pan_hint)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier
            .fillMaxWidth()
            .semantics { testTag = "card_editor_pan" },
    )

    if (buffer.last4.isNotBlank()) {
        Text(
            text = stringResource(R.string.finance_card_last4_stored_format, buffer.last4),
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.semantics { testTag = "card_editor_last4_chip" },
        )
    }

    OutlinedTextField(
        value = buffer.creditLimit,
        onValueChange = { onChange(buffer.copy(creditLimit = it)) },
        label = { Text(stringResource(R.string.finance_card_field_credit_limit)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = Modifier
            .fillMaxWidth()
            .semantics { testTag = "card_editor_credit_limit" },
    )

    OutlinedTextField(
        value = buffer.usedLimit,
        onValueChange = { onChange(buffer.copy(usedLimit = it)) },
        label = { Text(stringResource(R.string.finance_card_field_used_limit)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = Modifier
            .fillMaxWidth()
            .semantics { testTag = "card_editor_used_limit" },
    )

    // 账单日 / 还款日（仅 credit 必填；用纯文本框简化，避免 picker 引入第三方依赖）
    OutlinedTextField(
        value = buffer.billingDay?.toString().orEmpty(),
        onValueChange = { v ->
            val day = v.toIntOrNull()
            onChange(buffer.copy(billingDay = day?.takeIf { it in 1..31 }))
        },
        label = { Text(stringResource(R.string.finance_card_field_billing_day)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier
            .fillMaxWidth()
            .semantics { testTag = "card_editor_billing_day" },
    )

    OutlinedTextField(
        value = buffer.dueDay?.toString().orEmpty(),
        onValueChange = { v ->
            val day = v.toIntOrNull()
            onChange(buffer.copy(dueDay = day?.takeIf { it in 0..60 }))
        },
        label = { Text(stringResource(R.string.finance_card_field_due_day)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier
            .fillMaxWidth()
            .semantics { testTag = "card_editor_due_day" },
    )

    // 卡组织 chip
    Text(stringResource(R.string.finance_card_field_brand), style = MaterialTheme.typography.labelMedium)
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        CardBrandRadioChip("visa", stringResource(R.string.finance_card_brand_visa), buffer.brand) { onChange(buffer.copy(brand = it)) }
        CardBrandRadioChip("mastercard", stringResource(R.string.finance_card_brand_mastercard), buffer.brand) { onChange(buffer.copy(brand = it)) }
        CardBrandRadioChip("unionpay", stringResource(R.string.finance_card_brand_unionpay), buffer.brand) { onChange(buffer.copy(brand = it)) }
        CardBrandRadioChip("amex", stringResource(R.string.finance_card_brand_amex), buffer.brand) { onChange(buffer.copy(brand = it)) }
        CardBrandRadioChip("other", stringResource(R.string.finance_card_brand_other), buffer.brand) { onChange(buffer.copy(brand = it)) }
    }

    OutlinedTextField(
        value = buffer.holder,
        onValueChange = { onChange(buffer.copy(holder = it)) },
        label = { Text(stringResource(R.string.finance_card_field_holder)) },
        singleLine = true,
        modifier = Modifier
            .fillMaxWidth()
            .semantics { testTag = "card_editor_holder" },
    )

    OutlinedTextField(
        value = buffer.note,
        onValueChange = { onChange(buffer.copy(note = it)) },
        label = { Text(stringResource(R.string.finance_card_field_note)) },
        singleLine = false,
        modifier = Modifier
            .fillMaxWidth()
            .semantics { testTag = "card_editor_note" },
    )

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(R.string.finance_card_field_archived), modifier = Modifier.weight(1f))
        Switch(
            checked = buffer.archived,
            onCheckedChange = { onChange(buffer.copy(archived = it)) },
            modifier = Modifier.semantics { testTag = "card_editor_archived_switch" },
        )
    }
}

@Composable
private fun CardKindRadioChip(
    kind: String,
    label: String,
    selected: String,
    onSelect: (String) -> Unit,
) {
    FilterChip(
        selected = selected == kind,
        onClick = { onSelect(kind) },
        label = { Text(label) },
        modifier = Modifier.semantics { testTag = "card_editor_kind_chip_$kind" },
    )
}

@Composable
private fun CardBrandRadioChip(
    brand: String,
    label: String,
    selected: String,
    onSelect: (String) -> Unit,
) {
    FilterChip(
        selected = selected == brand,
        onClick = { onSelect(brand) },
        label = { Text(label) },
        modifier = Modifier.semantics { testTag = "card_editor_brand_chip_$brand" },
    )
}

// =============================================================================
// TxEditor（流水字段）
// =============================================================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TxFields(
    buffer: FinanceEditorBuffer,
    accounts: List<com.everything.eve.data.finance.entity.FinanceAccountEntity>,
    onChange: (FinanceEditorBuffer) -> Unit,
) {
    // 类型 chip
    Text(stringResource(R.string.finance_tx_field_kind), style = MaterialTheme.typography.labelMedium)
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        TxKindRadioChip("income", stringResource(R.string.finance_tx_chip_kind_income), buffer.txKind) { onChange(buffer.copy(txKind = it)) }
        TxKindRadioChip("expense", stringResource(R.string.finance_tx_chip_kind_expense), buffer.txKind) { onChange(buffer.copy(txKind = it)) }
        TxKindRadioChip("transfer", stringResource(R.string.finance_tx_chip_kind_transfer), buffer.txKind) { onChange(buffer.copy(txKind = it)) }
    }

    // 账户下拉（简化：用 chip 列表展示账户，用户从 chips 选 accountId）
    Text(stringResource(R.string.finance_tx_field_account), style = MaterialTheme.typography.labelMedium)
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        accounts.forEach { acc ->
            val selected = acc.id == buffer.accountId
            FilterChip(
                selected = selected,
                onClick = { onChange(buffer.copy(accountId = acc.id)) },
                label = { Text(acc.name.ifBlank { "(未命名)" }) },
                modifier = Modifier.semantics { testTag = "tx_editor_account_chip_${acc.id}" },
            )
        }
    }

    if (buffer.txKind == "transfer") {
        Text(stringResource(R.string.finance_tx_field_transfer_to), style = MaterialTheme.typography.labelMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            accounts.filter { it.id != buffer.accountId }.forEach { acc ->
                val selected = acc.id == buffer.transferToAccountId
                FilterChip(
                    selected = selected,
                    onClick = { onChange(buffer.copy(transferToAccountId = acc.id)) },
                    label = { Text(acc.name.ifBlank { "(未命名)" }) },
                    modifier = Modifier.semantics { testTag = "tx_editor_transfer_chip_${acc.id}" },
                )
            }
        }
    }

    OutlinedTextField(
        value = buffer.balance,
        onValueChange = { onChange(buffer.copy(balance = it)) },
        label = { Text(stringResource(R.string.finance_tx_field_amount)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = Modifier
            .fillMaxWidth()
            .semantics { testTag = "tx_editor_amount" },
    )

    OutlinedTextField(
        value = buffer.category,
        onValueChange = { onChange(buffer.copy(category = it)) },
        label = { Text(stringResource(R.string.finance_tx_field_category)) },
        singleLine = true,
        modifier = Modifier
            .fillMaxWidth()
            .semantics { testTag = "tx_editor_category" },
    )

    OutlinedTextField(
        value = buffer.note,
        onValueChange = { onChange(buffer.copy(note = it)) },
        label = { Text(stringResource(R.string.finance_tx_field_note)) },
        singleLine = false,
        modifier = Modifier
            .fillMaxWidth()
            .semantics { testTag = "tx_editor_note" },
    )
}

@Composable
private fun TxKindRadioChip(
    kind: String,
    label: String,
    selected: String,
    onSelect: (String) -> Unit,
) {
    FilterChip(
        selected = selected == kind,
        onClick = { onSelect(kind) },
        label = { Text(label) },
        modifier = Modifier.semantics { testTag = "tx_editor_kind_chip_$kind" },
    )
}
