/*
 * ============================================================================
 * ContractEditorScreen —— 合同/发票编辑器（stage5-finance-v2 / Task 4 / TR-2.4）
 * ============================================================================
 *
 * 设计要点：
 *   1. **本地 EditorBuffer 状态**：编辑器内部用 `mutableStateOf` 持有
 *      [ContractEditorBuffer]；保存时调 [FinanceViewModel.upsertContract]；
 *      删除走 [FinanceViewModel.deleteContract]。
 *   2. **校验**：[FinanceRecords.validateContract] 失败时直接弹错文案
 *      （testTag = `contract_editor_error`）。
 *   3. **零知识（spec NFR-1）**：保存 / 删除 / 错误文案不渲染 amount 明文。
 *
 * 关联：
 *   - tasks.md TR-2.4
 *   - ui/finance/FinanceViewModel.upsertContract / deleteContract
 *   - finance/FinanceRecords.validateContract
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

import android.content.Intent
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
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import com.everything.eve.R
import com.everything.eve.ServiceLocator
import com.everything.eve.finance.ContractRecord
import com.everything.eve.finance.FINANCE_V2_SCHEMA_VERSION
import kotlinx.coroutines.launch

/**
 * 合同/发票编辑器 Composable（spec FR-3 / TR-2.4）。
 *
 * @param id 已存在 contract id；新建传 null。
 * @param vm FinanceViewModel（提供 upsertContract / deleteContract）。
 * @param onClose 保存 / 删除 / 取消后回调。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContractEditorScreen(
    id: String?,
    vm: FinanceViewModel,
    onClose: () -> Unit,
) {
    val state by vm.state.collectAsState()
    val existing: ContractRecord? = remember(id, state.contracts) {
        id?.let { eid -> state.contracts.firstOrNull { it.id == eid } }
    }

    var buffer by remember(id) {
        mutableStateOf(
            existing?.toEditorBuffer() ?: ContractEditorBuffer(id = vm.newId())
        )
    }

    var errorText by remember { mutableStateOf<String?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // =============================================================================
    // SA-3 / TR-3.4：附件仓库绑定（编辑器持有 vm 引用；
    // 编辑模式下 currentRecordId != null → 显示附件节）
    // =============================================================================
    val context = LocalContext.current
    LaunchedEffect(vm) {
        runCatching { vm.bindAttachmentRepository(ServiceLocator.attachmentRepo) }
    }
    val currentRecordId: String? = id

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    // TODO(B2-strings): R.string.finance_v2_contract_editor_title_*
                    Text(if (id == null) "新建合同" else "编辑合同")
                },
                navigationIcon = {
                    TextButton(
                        onClick = onClose,
                        modifier = Modifier.semantics { testTag = "contract_editor_close_btn" },
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
            ContractEditorFields(
                buffer = buffer,
                onChange = { buffer = it },
            )

            errorText?.let { err ->
                Text(
                    text = err,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.semantics { testTag = "contract_editor_error" },
                )
            }

            // =============================================================================
            // SA-3 / TR-3.4：附件节 —— 仅编辑模式显示（新建模式下 currentRecordId
            // 是新生成的 vm.newId()，未入库，挂附件无意义；按 spec 隐藏）
            // =============================================================================
            if (currentRecordId != null) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                Text(
                    text = stringResource(R.string.finance_attachment_section_title),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.semantics { testTag = "contract_editor_attachment_section" },
                )
                AttachmentUploader(
                    recordId = currentRecordId,
                    vm = vm,
                )
                AttachmentList(
                    recordId = currentRecordId,
                    vm = vm,
                    onPreview = { attachmentId, mime ->
                        val uri = vm.openAttachment(context, attachmentId, mime)
                        if (uri != null) {
                            val intent = Intent(Intent.ACTION_VIEW).apply {
                                setDataAndType(uri, mime)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            runCatching { context.startActivity(intent) }
                        }
                    },
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
                        // 注意：noticeDeadlineTs 必须 = endTs - noticePeriodDays * DAY_MS
                        val noticeDeadlineTs = buffer.endTs - buffer.noticePeriodDays * 86_400_000L
                        val record = ContractRecord(
                            id = buffer.id,
                            schemaVersion = FINANCE_V2_SCHEMA_VERSION,
                            title = buffer.title.trim(),
                            counterparty = buffer.counterparty.trim(),
                            kind = buffer.kind,
                            amountMinor = buffer.amountMinor.ifBlank { "0" },
                            currency = buffer.currency.ifBlank { "CNY" },
                            signedTs = buffer.signedTs,
                            startTs = buffer.startTs,
                            endTs = buffer.endTs,
                            autoRenew = buffer.autoRenew,
                            noticePeriodDays = buffer.noticePeriodDays,
                            noticeDeadlineTs = noticeDeadlineTs,
                            status = buffer.status,
                            linkedAccountId = buffer.linkedAccountId?.takeIf { it.isNotBlank() },
                            attachments = emptyList(),
                            createdAt = existing?.createdAt ?: now,
                            updatedAt = now,
                        )
                        val result = vm.upsertContract(record)
                        if (result.isSuccess) {
                            onClose()
                        } else {
                            val reason = (result.exceptionOrNull()?.message) ?: "contract_invalid"
                            errorText = reason
                            scope.launch {
                                snackbarHostState.showSnackbar("保存失败：$reason")
                            }
                        }
                    },
                    modifier = Modifier
                        .weight(1f)
                        .semantics { testTag = "contract_editor_save_btn" },
                ) {
                    // TODO(B2-strings): R.string.finance_editor_save_btn
                    Text("保存")
                }

                if (id != null) {
                    OutlinedButton(
                        onClick = {
                            val result = vm.deleteContract(id)
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
                            .semantics { testTag = "contract_editor_delete_btn" },
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
 * 合同编辑器字段集。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ContractEditorFields(
    buffer: ContractEditorBuffer,
    onChange: (ContractEditorBuffer) -> Unit,
) {
    // title
    OutlinedTextField(
        value = buffer.title,
        onValueChange = { onChange(buffer.copy(title = it)) },
        // TODO(B2-strings): R.string.finance_v2_contract_field_title
        label = { Text("合同标题") },
        singleLine = true,
        modifier = Modifier
            .fillMaxWidth()
            .semantics { testTag = "contract_editor_title" },
    )

    // counterparty
    OutlinedTextField(
        value = buffer.counterparty,
        onValueChange = { onChange(buffer.copy(counterparty = it)) },
        // TODO(B2-strings): R.string.finance_v2_contract_field_counterparty
        label = { Text("对手方") },
        singleLine = true,
        modifier = Modifier
            .fillMaxWidth()
            .semantics { testTag = "contract_editor_counterparty" },
    )

    // kind —— 5 选：rental / service / purchase / loan / other
    // TODO(B2-strings): R.string.finance_v2_contract_field_kind
    Text("合同类型", style = MaterialTheme.typography.labelMedium)
    ContractKindDropdown(
        selected = buffer.kind,
        onSelect = { onChange(buffer.copy(kind = it)) },
    )

    // amountMinor
    OutlinedTextField(
        value = buffer.amountMinor,
        onValueChange = { raw ->
            val sanitized = raw.filter { it.isDigit() || it == '.' }
            val firstDot = sanitized.indexOf('.')
            val normalized = if (firstDot >= 0) {
                sanitized.substring(0, firstDot + 1) +
                    sanitized.substring(firstDot + 1).replace(".", "")
            } else sanitized
            onChange(buffer.copy(amountMinor = normalized))
        },
        // TODO(B2-strings): R.string.finance_v2_contract_field_amount
        label = { Text("金额") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = Modifier
            .fillMaxWidth()
            .semantics { testTag = "contract_editor_amount" },
    )

    // startTs / endTs
    OutlinedTextField(
        value = buffer.startTs.toString(),
        onValueChange = { v ->
            val ts = v.toLongOrNull()
            if (ts != null) onChange(buffer.copy(startTs = ts))
        },
        // TODO(B2-strings): R.string.finance_v2_contract_field_start_ts
        label = { Text("起始时间戳（毫秒）") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier
            .fillMaxWidth()
            .semantics { testTag = "contract_editor_start_ts" },
    )

    OutlinedTextField(
        value = buffer.endTs.toString(),
        onValueChange = { v ->
            val ts = v.toLongOrNull()
            if (ts != null) onChange(buffer.copy(endTs = ts))
        },
        // TODO(B2-strings): R.string.finance_v2_contract_field_end_ts
        label = { Text("结束时间戳（毫秒）") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier
            .fillMaxWidth()
            .semantics { testTag = "contract_editor_end_ts" },
    )

    // noticePeriodDays
    OutlinedTextField(
        value = buffer.noticePeriodDays.toString(),
        onValueChange = { v ->
            val days = v.toLongOrNull()
            if (days != null && days >= 0) onChange(buffer.copy(noticePeriodDays = days))
        },
        // TODO(B2-strings): R.string.finance_v2_contract_field_notice_days
        label = { Text("提前通知天数") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier
            .fillMaxWidth()
            .semantics { testTag = "contract_editor_notice_days" },
    )

    // autoRenew Switch
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // TODO(B2-strings): R.string.finance_v2_contract_field_auto_renew
        Text("自动续约", modifier = Modifier.weight(1f))
        Switch(
            checked = buffer.autoRenew,
            onCheckedChange = { onChange(buffer.copy(autoRenew = it)) },
            modifier = Modifier.semantics { testTag = "contract_editor_auto_renew_switch" },
        )
    }

    // status —— 4 选：active / expired / terminated / renewed
    // TODO(B2-strings): R.string.finance_v2_contract_field_status
    Text("状态", style = MaterialTheme.typography.labelMedium)
    ContractStatusDropdown(
        selected = buffer.status,
        onSelect = { onChange(buffer.copy(status = it)) },
    )
}

/**
 * 合同类型下拉菜单（5 选：rental / service / purchase / loan / other）。
 *
 * 实现说明：采用 Box + DropdownMenu 直接锚定模式；不依赖 ExposedDropdownMenu
 * 的扩展函数签名（不同 Material3 版本间存在差异），保证编译稳定。
 */
@Composable
private fun ContractKindDropdown(
    selected: String,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val options = listOf(
        "rental" to "租赁",
        "service" to "服务",
        "purchase" to "采购",
        "loan" to "借款合同",
        "other" to "其他",
    )
    val displayLabel = options.firstOrNull { it.first == selected }?.second ?: selected

    Box(modifier = Modifier.semantics { testTag = "contract_editor_kind_dropdown" }) {
        OutlinedTextField(
            value = displayLabel,
            onValueChange = {},
            readOnly = true,
            label = { Text("类型") },
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
                    modifier = Modifier.semantics { testTag = "contract_editor_kind_option_$value" },
                )
            }
        }
    }
}

/**
 * 合同状态下拉菜单（4 选：active / expired / terminated / renewed）。
 */
@Composable
private fun ContractStatusDropdown(
    selected: String,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val options = listOf(
        "active" to "生效中",
        "expired" to "已到期",
        "terminated" to "已终止",
        "renewed" to "已续约",
    )
    val displayLabel = options.firstOrNull { it.first == selected }?.second ?: selected

    Box(modifier = Modifier.semantics { testTag = "contract_editor_status_dropdown" }) {
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
                    modifier = Modifier.semantics { testTag = "contract_editor_status_option_$value" },
                )
            }
        }
    }
}

/**
 * 把 ContractRecord 转 ContractEditorBuffer（编辑器加载已存在条目时使用）。
 */
private fun ContractRecord.toEditorBuffer(): ContractEditorBuffer =
    ContractEditorBuffer(
        id = id,
        title = title,
        counterparty = counterparty,
        kind = kind,
        amountMinor = amountMinor,
        currency = currency,
        signedTs = signedTs,
        startTs = startTs,
        endTs = endTs,
        autoRenew = autoRenew,
        noticePeriodDays = noticePeriodDays,
        noticeDeadlineTs = noticeDeadlineTs,
        status = status,
        linkedAccountId = linkedAccountId,
    )