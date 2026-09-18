// ============================================================================
// 汇率包导入 / 默认币种设置屏（stage5-finance-v2 / B5 / FR-V2-C.2、FR-V2-C.3）
// ============================================================================
//
// 路径：android/app/src/main/java/com/everything/eve/ui/settings/RatesImportScreen.kt
//
// 职责（全屏屏，由 FinanceScreen 的 settingsMode 承载，返回态与编辑器一致）：
//   1) 默认折算币种：5 个预设 FilterChip（CNY/USD/EUR/JPY/HKD）+ 手填
//      TextField（3 位大写字母粗校验，调 vm.setDefaultCurrency）；
//   2) 当前汇率包状态卡：生效日期（java.time 本地格式）+ 货币对数量，无包提示"未导入"；
//   3) SAF ACTION_OPEN_DOCUMENT 选 JSON 文件（application/json + * / * 兜底）→
//      contentResolver 读文本 → vm.importRateTable；成功 Snackbar + 明细预览；
//   4) 零知识注释：汇率是公开数据，仍走端侧加密通道。
//
// 设计纪律：
//   - 新文案全部走 strings.xml（Snackbar 也取 stringResource，不硬编码中文）；
//   - 无需运行时存储权限（SAF 系统选择器授权，读完即还，不持久化 Uri 授权）；
//   - 全程不打印汇率包明文到日志。
//
// 关联：
//   - android/.../ui/finance/FinanceViewModel.kt（rateTableState / defaultCurrencyState /
//     importRateTable / setDefaultCurrency）
//   - android/.../ui/finance/FinanceRoutes.kt（RATES_IMPORT 路由常量）
//   - android/.../ui/finance/AttachmentUploader.kt（SAF OpenDocument 范本）
// ============================================================================

package com.everything.eve.ui.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.TextButton
import androidx.compose.ui.unit.dp
import com.everything.eve.R
import com.everything.eve.ui.finance.FinanceSettings
import com.everything.eve.ui.finance.FinanceViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.TreeMap

/**
 * 汇率包导入 / 默认币种设置全屏。
 *
 * @param vm 财务 ViewModel（汇率表与默认币种状态源 + 导入 / 设置动作）。
 * @param onClose 点击返回回调（FinanceScreen 清 settingsMode）。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun RatesImportScreen(
    vm: FinanceViewModel,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    val table by vm.rateTableState.collectAsState()
    val defaultCurrency by vm.defaultCurrencyState.collectAsState()

    // 手填币种代码的编辑态（仅 UI 本地态；合法后才进 VM）。
    var customCurrency by remember { mutableStateOf("") }

    // 文案预取（launcher 回调内不能直接调 stringResource）。
    val msgSuccess = stringResource(R.string.finance_rates_import_success)
    val msgFailed = stringResource(R.string.finance_rates_import_failed)
    val msgReadFailed = stringResource(R.string.finance_rates_read_failed)
    val msgCurrencyInvalid = stringResource(R.string.finance_rates_currency_invalid)

    // SAF 文件选择器：JSON 优先，* / * 兜底兼容文件管理器把 json 识别为 octet-stream。
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val json = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        input.readBytes().toString(Charsets.UTF_8)
                    }
                }.getOrNull()
            }
            if (json.isNullOrBlank()) {
                snackbar.showSnackbar(msgReadFailed)
                return@launch
            }
            val result = vm.importRateTable(json)
            snackbar.showSnackbar(if (result.isSuccess) msgSuccess else msgFailed)
        }
    }

    // 汇率明细按货币对 key 排序（TreeMap 天然字典序）。
    // 注意：remember 必须在 @Composable 函数体调用，不能放进 LazyColumn 的
    // LazyListScope 构建块（该块不是组合上下文，调 remember 会编译失败）。
    val sortedRates = remember(table) {
        table?.let { TreeMap(it.rates) } ?: TreeMap()
    }

    Scaffold(
        modifier = Modifier.semantics { testTag = "rates_import_screen" },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.finance_rates_title)) },
                navigationIcon = {
                    TextButton(
                        onClick = onClose,
                        modifier = Modifier.semantics { testTag = "rates_import_back" },
                    ) {
                        Text(stringResource(R.string.finance_rates_back))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        // 整屏单列表：状态卡 / 选择按钮 / 明细都作为 LazyColumn 条目，避免内嵌滚动冲突。
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // ========== 1. 默认折算币种 ==========
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.finance_rates_section_default_currency),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        // 5 预设：点击即设置；当前值高亮。
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FinanceSettings.SUPPORTED_CURRENCIES.forEach { code ->
                                FilterChip(
                                    selected = defaultCurrency == code,
                                    onClick = { vm.setDefaultCurrency(code) },
                                    label = { Text(code) },
                                    modifier = Modifier.semantics {
                                        testTag = "rates_currency_chip_$code"
                                    },
                                )
                            }
                        }
                        // 手填：3 位大写字母；Done 或点击按钮提交，非法弹 Snackbar 不落库。
                        OutlinedTextField(
                            value = customCurrency,
                            onValueChange = { raw ->
                                // 输入期即归一化为大写并截断到 3 位，减少非法态。
                                customCurrency = raw.uppercase().filter { it in 'A'..'Z' }.take(3)
                            },
                            label = {
                                Text(stringResource(R.string.finance_rates_custom_currency_label))
                            },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(
                                capitalization = KeyboardCapitalization.Characters,
                                imeAction = ImeAction.Done,
                            ),
                            keyboardActions = KeyboardActions(
                                onDone = {
                                    val r = vm.setDefaultCurrency(customCurrency)
                                    if (r.isSuccess) customCurrency = ""
                                    else scope.launch { snackbar.showSnackbar(msgCurrencyInvalid) }
                                },
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .semantics { testTag = "rates_currency_custom_input" },
                        )
                        Button(
                            onClick = {
                                val r = vm.setDefaultCurrency(customCurrency)
                                if (r.isSuccess) customCurrency = ""
                                else scope.launch { snackbar.showSnackbar(msgCurrencyInvalid) }
                            },
                            modifier = Modifier.semantics { testTag = "rates_currency_custom_apply" },
                        ) {
                            Text(stringResource(R.string.finance_rates_section_default_currency))
                        }
                    }
                }
            }

            // ========== 2. 当前汇率包状态 ==========
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { testTag = "rates_package_status" },
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.finance_rates_package_section),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        if (table == null) {
                            Text(
                                text = stringResource(R.string.finance_rates_not_imported),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.semantics { testTag = "rates_package_none" },
                            )
                        } else {
                            val dateText = remember(table!!.effectiveTs) {
                                DateTimeFormatter.ofPattern("yyyy-MM-dd")
                                    .withZone(ZoneId.systemDefault())
                                    .format(Instant.ofEpochMilli(table!!.effectiveTs))
                            }
                            Text(
                                text = stringResource(
                                    R.string.finance_rates_effective_date,
                                    dateText,
                                ),
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.semantics { testTag = "rates_package_date" },
                            )
                            Text(
                                text = stringResource(
                                    R.string.finance_rates_pair_count,
                                    table!!.rates.size,
                                ),
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.semantics { testTag = "rates_package_pair_count" },
                            )
                        }
                    }
                }
            }

            // ========== 3. 选择汇率包 JSON ==========
            item {
                Button(
                    onClick = { launcher.launch(arrayOf("application/json", "*/*")) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { testTag = "rates_select_file_btn" },
                ) {
                    Text(stringResource(R.string.finance_rates_select_file))
                }
            }

            // ========== 4. 汇率明细预览 ==========
            if (sortedRates.isNotEmpty()) {
                item {
                    Text(
                        text = stringResource(R.string.finance_rates_preview_title),
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
                items(sortedRates.entries.toList()) { entry ->
                    Column {
                        Text(
                            text = stringResource(
                                R.string.finance_rates_pair_row_format,
                                entry.key,
                                formatRate(entry.value),
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                                .semantics { testTag = "rates_preview_row_${entry.key}" },
                        )
                        HorizontalDivider()
                    }
                }
            }

            // ========== 5. 零知识注释 ==========
            item {
                Text(
                    text = stringResource(R.string.finance_rates_zero_knowledge_note),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }
        }
    }
}

/**
 * 汇率数字展示格式化：Double.toString 最短表示，整数补掉 ".0"
 * （如 7.0 → "7"，7.25 → "7.25"，0.048 → "0.048"）。
 */
private fun formatRate(rate: Double): String {
    val s = rate.toString()
    return if (s.endsWith(".0")) s.dropLast(2) else s
}
