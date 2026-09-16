/*
 * ============================================================================
 * PolicyEditorScreen —— 保单编辑器（stage5-finance-v2 / Task 4 / TR-2.2）
 * ============================================================================
 *
 * 设计要点：
 *   1. **本地 EditorBuffer 状态**：编辑器内部用 `mutableStateOf` 持有
 *      [PolicyEditorBuffer]；保存时调 [FinanceViewModel.upsertPolicy]；
 *      删除走 [FinanceViewModel.deletePolicy]。
 *   2. **校验**：[FinanceRecords.validatePolicy] 失败时直接弹错文案
 *      （testTag = `policy_editor_error`）。
 *   3. **零知识（spec NFR-1）**：policyNumber 仅在编辑器本地内存态驻留；
 *      **不打印日志**（不在 save / delete / log 中输出明文）；列表展示
 *      `****` + 末 4。
 *
 * 关联：
 *   - tasks.md TR-2.2
 *   - ui/finance/FinanceViewModel.upsertPolicy / deletePolicy
 *   - finance/FinanceRecords.validatePolicy
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
import com.everything.eve.finance.FINANCE_V2_SCHEMA_VERSION
import com.everything.eve.finance.PolicyRecord
import kotlinx.coroutines.launch

/**
 * 保单编辑器 Composable（spec FR-3 / TR-2.2）。
 *
 * @param id 已存在 policy id；新建传 null。
 * @param vm FinanceViewModel（提供 upsertPolicy / deletePolicy）。
 * @param onClose 保存 / 删除 / 取消后回调。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PolicyEditorScreen(
    id: String?,
    vm: FinanceViewModel,
    onClose: () -> Unit,
) {
    val state by vm.state.collectAsState()
    val existing: PolicyRecord? = remember(id, state.policies) {
        id?.let { eid -> state.policies.firstOrNull { it.id == eid } }
    }

    var buffer by remember(id) {
        mutableStateOf(
            existing?.toEditorBuffer() ?: PolicyEditorBuffer(id = vm.newId())
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
        // 应用启动后 ServiceLocator.attachmentRepo 必已初始化；
        // 这里安全地注入 vm —— 重复 bind 幂等。
        runCatching { vm.bindAttachmentRepository(ServiceLocator.attachmentRepo) }
    }
    val currentRecordId: String? = id

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    // TODO(B2-strings): R.string.finance_v2_policy_editor_title_*
                    Text(if (id == null) "新建保单" else "编辑保单")
                },
                navigationIcon = {
                    TextButton(
                        onClick = onClose,
                        modifier = Modifier.semantics { testTag = "policy_editor_close_btn" },
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
            PolicyEditorFields(
                buffer = buffer,
                onChange = { buffer = it },
            )

            errorText?.let { err ->
                Text(
                    text = err,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.semantics { testTag = "policy_editor_error" },
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
                    modifier = Modifier.semantics { testTag = "policy_editor_attachment_section" },
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
                        // 注意：policyNumber 仅在内存 buffer 中；保存前不输出日志。
                        val record = PolicyRecord(
                            id = buffer.id,
                            schemaVersion = FINANCE_V2_SCHEMA_VERSION,
                            name = buffer.name.trim(),
                            policyNumber = buffer.policyNumber,
                            policyNumberEncrypted = false,
                            provider = buffer.provider.trim(),
                            premiumMinor = buffer.premiumMinor.ifBlank { "0" },
                            currency = buffer.currency.ifBlank { "CNY" },
                            billingCycle = buffer.billingCycle,
                            startTs = buffer.startTs,
                            expiryTs = buffer.expiryTs,
                            reminders = buffer.reminders,
                            coverageMinor = buffer.coverageMinor.ifBlank { "0" },
                            active = buffer.active,
                            linkedAccountId = buffer.linkedAccountId?.takeIf { it.isNotBlank() },
                            attachments = emptyList(),
                            createdAt = existing?.createdAt ?: now,
                            updatedAt = now,
                        )
                        val result = vm.upsertPolicy(record)
                        if (result.isSuccess) {
                            onClose()
                        } else {
                            val reason = (result.exceptionOrNull()?.message) ?: "policy_invalid"
                            errorText = reason
                            scope.launch {
                                snackbarHostState.showSnackbar("保存失败：$reason")
                            }
                        }
                    },
                    modifier = Modifier
                        .weight(1f)
                        .semantics { testTag = "policy_editor_save_btn" },
                ) {
                    // TODO(B2-strings): R.string.finance_editor_save_btn
                    Text("保存")
                }

                if (id != null) {
                    OutlinedButton(
                        onClick = {
                            val result = vm.deletePolicy(id)
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
                            .semantics { testTag = "policy_editor_delete_btn" },
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
 * 保单编辑器字段集。
 *
 * 注意：policyNumber 是敏感字段 —— 本地内存态；保存到 Room 前**不打印日志**；
 * 不在 Logcat / snackbar / 错误文案中输出 policyNumber 明文。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PolicyEditorFields(
    buffer: PolicyEditorBuffer,
    onChange: (PolicyEditorBuffer) -> Unit,
) {
    // 名称
    OutlinedTextField(
        value = buffer.name,
        onValueChange = { onChange(buffer.copy(name = it)) },
        // TODO(B2-strings): R.string.finance_v2_policy_field_name
        label = { Text("保单名称") },
        singleLine = true,
        modifier = Modifier
            .fillMaxWidth()
            .semantics { testTag = "policy_editor_name" },
    )

    // 保单号 —— 敏感字段，本地内存态，不打日志
    OutlinedTextField(
        value = buffer.policyNumber,
        onValueChange = { onChange(buffer.copy(policyNumber = it)) },
        // TODO(B2-strings): R.string.finance_v2_policy_field_number
        label = { Text("保单号") },
        singleLine = true,
        modifier = Modifier
            .fillMaxWidth()
            .semantics { testTag = "policy_editor_number" },
    )

    // provider
    OutlinedTextField(
        value = buffer.provider,
        onValueChange = { onChange(buffer.copy(provider = it)) },
        // TODO(B2-strings): R.string.finance_v2_policy_field_provider
        label = { Text("保险公司") },
        singleLine = true,
        modifier = Modifier
            .fillMaxWidth()
            .semantics { testTag = "policy_editor_provider" },
    )

    // premiumMinor
    OutlinedTextField(
        value = buffer.premiumMinor,
        onValueChange = { raw ->
            val sanitized = raw.filter { it.isDigit() || it == '.' }
            val firstDot = sanitized.indexOf('.')
            val normalized = if (firstDot >= 0) {
                sanitized.substring(0, firstDot + 1) +
                    sanitized.substring(firstDot + 1).replace(".", "")
            } else sanitized
            onChange(buffer.copy(premiumMinor = normalized))
        },
        // TODO(B2-strings): R.string.finance_v2_policy_field_premium
        label = { Text("保费") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = Modifier
            .fillMaxWidth()
            .semantics { testTag = "policy_editor_premium" },
    )

    // billingCycle —— 4 选：monthly / quarterly / yearly / single
    // TODO(B2-strings): R.string.finance_v2_policy_field_cycle
    Text("缴费周期", style = MaterialTheme.typography.labelMedium)
    PolicyCycleDropdown(
        selected = buffer.billingCycle,
        onSelect = { onChange(buffer.copy(billingCycle = it)) },
    )

    // startTs / expiryTs
    OutlinedTextField(
        value = buffer.startTs.toString(),
        onValueChange = { v ->
            val ts = v.toLongOrNull()
            if (ts != null) onChange(buffer.copy(startTs = ts))
        },
        // TODO(B2-strings): R.string.finance_v2_policy_field_start_ts
        label = { Text("起始时间戳（毫秒）") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier
            .fillMaxWidth()
            .semantics { testTag = "policy_editor_start_ts" },
    )

    OutlinedTextField(
        value = buffer.expiryTs.toString(),
        onValueChange = { v ->
            val ts = v.toLongOrNull()
            if (ts != null) onChange(buffer.copy(expiryTs = ts))
        },
        // TODO(B2-strings): R.string.finance_v2_policy_field_expiry_ts
        label = { Text("到期时间戳（毫秒）") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier
            .fillMaxWidth()
            .semantics { testTag = "policy_editor_expiry_ts" },
    )

    // coverageMinor
    OutlinedTextField(
        value = buffer.coverageMinor,
        onValueChange = { raw ->
            val sanitized = raw.filter { it.isDigit() || it == '.' }
            val firstDot = sanitized.indexOf('.')
            val normalized = if (firstDot >= 0) {
                sanitized.substring(0, firstDot + 1) +
                    sanitized.substring(firstDot + 1).replace(".", "")
            } else sanitized
            onChange(buffer.copy(coverageMinor = normalized))
        },
        // TODO(B2-strings): R.string.finance_v2_policy_field_coverage
        label = { Text("保额") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = Modifier
            .fillMaxWidth()
            .semantics { testTag = "policy_editor_coverage" },
    )

    // active Switch
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // TODO(B2-strings): R.string.finance_v2_policy_field_active
        Text("启用", modifier = Modifier.weight(1f))
        Switch(
            checked = buffer.active,
            onCheckedChange = { onChange(buffer.copy(active = it)) },
            modifier = Modifier.semantics { testTag = "policy_editor_active_switch" },
        )
    }
}

/**
 * 保单缴费周期下拉菜单（4 选：monthly / quarterly / yearly / single）。
 *
 * 实现说明：采用 Box + DropdownMenu 直接锚定模式；不依赖 ExposedDropdownMenu
 * 的扩展函数签名（不同 Material3 版本间存在差异），保证编译稳定。
 */
@Composable
private fun PolicyCycleDropdown(
    selected: String,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val options = listOf(
        "monthly" to "月付",
        "quarterly" to "季付",
        "yearly" to "年付",
        "single" to "一次性",
    )
    val displayLabel = options.firstOrNull { it.first == selected }?.second ?: selected

    Box(modifier = Modifier.semantics { testTag = "policy_editor_cycle_dropdown" }) {
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
                    modifier = Modifier.semantics { testTag = "policy_editor_cycle_option_$value" },
                )
            }
        }
    }
}

/**
 * 把 PolicyRecord 转 PolicyEditorBuffer（编辑器加载已存在条目时使用）。
 */
private fun PolicyRecord.toEditorBuffer(): PolicyEditorBuffer =
    PolicyEditorBuffer(
        id = id,
        name = name,
        policyNumber = policyNumber,
        provider = provider,
        premiumMinor = premiumMinor,
        currency = currency,
        billingCycle = billingCycle,
        startTs = startTs,
        expiryTs = expiryTs,
        reminders = reminders,
        coverageMinor = coverageMinor,
        active = active,
        linkedAccountId = linkedAccountId,
    )