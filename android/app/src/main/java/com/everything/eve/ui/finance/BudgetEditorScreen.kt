/*
 * ============================================================================
 * BudgetEditorScreen —— B6 预算编辑器
 * ============================================================================
 *
 * 设计要点：
 *   1. 从 state.budgets 按 budgetId 找既有预算初始化；新建则给安全默认值
 *      （scope=monthly、category=all、currency=CNY、起止 now / now+30 天、
 *      预警 80 / 拦截 100、启用）；
 *   2. 保存前先过 FinanceRecords.validateBudget（纯函数）：Invalid 直接
 *      Toast 展示 reason（reason 是校验码级别短语，不含用户敏感数据），
 *      不调 VM；Ok 才 vm.upsertBudget 并 onClose；
 *   3. 金额输入强制"数字 + 最多一个小数点 + 最多两位小数"；币种取 3 位大写；
 *   4. 零知识铁律：本屏不向任何日志 / Toast 回显金额，校验失败仅给原因短语。
 *
 * 编码纪律：严禁 ASCII 双连字符；中文 KDoc/注释。
 * ============================================================================
 */

package com.everything.eve.ui.finance

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.everything.eve.R
import com.everything.eve.finance.BudgetRecord
import com.everything.eve.finance.FinanceRecords
import java.util.UUID

/** 一天的毫秒数（新建预算默认有效期 30 天）。 */
private const val DAY_MS_BUDGET = 86_400_000L

/**
 * 预算编辑器 Composable。
 *
 * @param budgetId 已存在预算 id；新建传 null。
 * @param vm FinanceViewModel（提供 upsertBudget / deleteBudget / state.budgets）。
 * @param onClose 保存 / 删除 / 取消后回调（由 FinanceScreen 退回预算列表）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BudgetEditorScreen(
    budgetId: String?,
    vm: FinanceViewModel,
    onClose: () -> Unit,
) {
    val state by vm.state.collectAsState()
    val existing = remember(budgetId, state.budgets) {
        budgetId?.let { id -> state.budgets.firstOrNull { it.id == id } }
    }

    // ========== 本地编辑态（新建给安全默认值） ==========
    val initNow = remember { System.currentTimeMillis() }
    var scope by remember(budgetId) { mutableStateOf(existing?.scope ?: "monthly") }
    // allCategories=true 时 category 强制 "all"；false 时用自由文本。
    var allCategories by remember(budgetId) {
        mutableStateOf(existing?.category?.let { it == "all" } ?: true)
    }
    var categoryText by remember(budgetId) {
        mutableStateOf(existing?.category?.takeIf { it != "all" } ?: "")
    }
    var amount by remember(budgetId) { mutableStateOf(existing?.amountMinor ?: "") }
    var currency by remember(budgetId) { mutableStateOf(existing?.currency ?: "CNY") }
    var startTs by remember(budgetId) {
        mutableLongStateOf(existing?.startTs ?: initNow)
    }
    var endTs by remember(budgetId) {
        mutableLongStateOf(existing?.endTs ?: (initNow + 30L * DAY_MS_BUDGET))
    }
    var warningPct by remember(budgetId) {
        mutableStateOf((existing?.warningThresholdPct ?: 80).toString())
    }
    var blockPct by remember(budgetId) {
        mutableStateOf((existing?.blockThresholdPct ?: 100).toString())
    }
    var active by remember(budgetId) { mutableStateOf(existing?.active ?: true) }

    val context = LocalContext.current

    /**
     * 组装 BudgetRecord 并尝试保存：
     * 先跑纯函数校验，Invalid → Toast(reason) 且不动 VM；Ok → upsert + 退出。
     */
    val trySave: () -> Unit = {
        val now = System.currentTimeMillis()
        val record = BudgetRecord(
            id = existing?.id ?: UUID.randomUUID().toString(),
            scope = scope,
            category = if (allCategories) "all" else categoryText.trim(),
            amountMinor = amount.trim(),
            currency = currency.uppercase(),
            startTs = startTs,
            endTs = endTs,
            warningThresholdPct = warningPct.toIntOrNull() ?: 0,
            blockThresholdPct = blockPct.toIntOrNull() ?: 0,
            active = active,
            createdAt = existing?.createdAt ?: now,
            updatedAt = now,
        )
        when (val validation = FinanceRecords.validateBudget(record)) {
            is com.everything.eve.finance.ValidationResult.Invalid -> {
                // reason 为校验码级别短语，不含金额 / 日期等敏感数据。
                Toast.makeText(context, validation.reason, Toast.LENGTH_SHORT).show()
            }
            com.everything.eve.finance.ValidationResult.Ok -> {
                vm.upsertBudget(record)
                onClose()
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (budgetId == null) {
                            stringResource(R.string.finance_budget_editor_title_new)
                        } else {
                            stringResource(R.string.finance_budget_editor_title_edit)
                        },
                    )
                },
                navigationIcon = {
                    TextButton(
                        onClick = onClose,
                        modifier = Modifier.semantics { testTag = "budget_editor_close_btn" },
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
            // 周期下拉：monthly / weekly / yearly / custom。
            Text(
                stringResource(R.string.finance_budget_field_scope),
                style = MaterialTheme.typography.labelMedium,
            )
            BudgetScopeDropdown(
                selected = scope,
                onSelect = { scope = it },
            )

            // 覆盖全部分类开关；关闭后出现自由分类输入框。
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.finance_budget_field_category_all),
                    modifier = Modifier.weight(1f),
                )
                Switch(
                    checked = allCategories,
                    onCheckedChange = { allCategories = it },
                    modifier = Modifier.semantics { testTag = "budget_editor_category_all_switch" },
                )
            }
            if (!allCategories) {
                OutlinedTextField(
                    value = categoryText,
                    onValueChange = { categoryText = it },
                    label = { Text(stringResource(R.string.finance_budget_field_category)) },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { testTag = "budget_editor_category" },
                )
            }

            // 金额（元）：仅数字 + 最多一个小数点 + 最多两位小数。
            OutlinedTextField(
                value = amount,
                onValueChange = { raw ->
                    val filtered = raw.filter { it.isDigit() || it == '.' }
                    val firstDot = filtered.indexOf('.')
                    val oneDot = if (firstDot >= 0) {
                        filtered.substring(0, firstDot + 1) +
                            filtered.substring(firstDot + 1).replace(".", "")
                    } else {
                        filtered
                    }
                    // 最多两位小数。
                    val limited = if (firstDot >= 0 && oneDot.length > firstDot + 3) {
                        oneDot.substring(0, firstDot + 3)
                    } else {
                        oneDot
                    }
                    amount = limited
                },
                label = { Text(stringResource(R.string.finance_budget_field_amount)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { testTag = "budget_editor_amount" },
            )

            // 币种：取 3 位并大写。
            OutlinedTextField(
                value = currency,
                onValueChange = { currency = it.take(3).uppercase() },
                label = { Text(stringResource(R.string.finance_budget_field_currency)) },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { testTag = "budget_editor_currency" },
            )

            // 起止：毫秒时间戳数字框（与订阅编辑器同款简化方案，不引日期 picker）。
            OutlinedTextField(
                value = startTs.toString(),
                onValueChange = { v -> v.toLongOrNull()?.let { startTs = it } },
                label = { Text(stringResource(R.string.finance_budget_field_start)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { testTag = "budget_editor_start" },
            )
            OutlinedTextField(
                value = endTs.toString(),
                onValueChange = { v -> v.toLongOrNull()?.let { endTs = it } },
                label = { Text(stringResource(R.string.finance_budget_field_end)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { testTag = "budget_editor_end" },
            )

            // 预警 / 拦截阈值（百分比整数；纯函数兜底 1 ≤ warning ≤ block ≤ 10000）。
            OutlinedTextField(
                value = warningPct,
                onValueChange = { warningPct = it.filter(Char::isDigit) },
                label = { Text(stringResource(R.string.finance_budget_field_warning)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { testTag = "budget_editor_warning" },
            )
            OutlinedTextField(
                value = blockPct,
                onValueChange = { blockPct = it.filter(Char::isDigit) },
                label = { Text(stringResource(R.string.finance_budget_field_block)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { testTag = "budget_editor_block" },
            )

            // 启用开关。
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.finance_budget_field_active),
                    modifier = Modifier.weight(1f),
                )
                Switch(
                    checked = active,
                    onCheckedChange = { active = it },
                    modifier = Modifier.semantics { testTag = "budget_editor_active" },
                )
            }

            // 保存 + 删除（仅编辑态）。
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = trySave,
                    modifier = Modifier
                        .weight(1f)
                        .semantics { testTag = "budget_editor_save_btn" },
                ) { Text(stringResource(R.string.finance_budget_save)) }

                if (budgetId != null) {
                    OutlinedButton(
                        onClick = {
                            vm.deleteBudget(budgetId)
                            onClose()
                        },
                        modifier = Modifier
                            .weight(1f)
                            .semantics { testTag = "budget_editor_delete_btn" },
                    ) { Text(stringResource(R.string.finance_budget_delete)) }
                }
            }
        }
    }
}

/**
 * 预算周期下拉（monthly 月度 / weekly 周度 / yearly 年度 / custom 自定义）。
 *
 * 用 Box + DropdownMenu 锚定，避免不同 Material3 版本的
 * ExposedDropdownMenuBox 签名差异（与订阅编辑器同款做法）。
 */
@Composable
private fun BudgetScopeDropdown(
    selected: String,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val options = listOf(
        "monthly" to stringResource(R.string.finance_budget_scope_monthly),
        "weekly" to stringResource(R.string.finance_budget_scope_weekly),
        "yearly" to stringResource(R.string.finance_budget_scope_yearly),
        "custom" to stringResource(R.string.finance_budget_scope_custom),
    )
    val displayLabel = options.firstOrNull { it.first == selected }?.second ?: selected

    Box(modifier = Modifier.semantics { testTag = "budget_editor_scope" }) {
        OutlinedTextField(
            value = displayLabel,
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(R.string.finance_budget_field_scope)) },
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
                    modifier = Modifier.semantics { testTag = "budget_editor_scope_option_$value" },
                )
            }
        }
    }
}
