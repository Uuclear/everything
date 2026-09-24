// ============================================================================
// 投资行情包导入 / 同步 URL 设置屏（stage5-finance-v2 / Task 8 / FR-V2-D.2、FR-V2-D.3）
// ============================================================================
//
// 路径：android/app/src/main/java/com/everything/eve/ui/settings/QuotesImportScreen.kt
//
// 职责（全屏屏，由 FinanceScreen 的 quotesMode 承载，返回态与编辑器一致）：
//   1) 默认目标币种：与 B5 RatesImportScreen 同款 5 预设 FilterChip + 手填
//      TextField（3 位大写字母粗校验，调 vm.setDefaultCurrency）；
//   2) 行情同步 URL：手填输入框 + 立即同步按钮（调 vm.pullQuoteNow）；
//   3) 当前行情包状态卡：生效 ts（本地格式）+ 报价数量，无包提示"未导入"；
//   4) SAF ACTION_OPEN_DOCUMENT 选 JSON 文件（application/json + */* 兜底）→
//      contentResolver 读文本 → vm.importQuoteTable；成功 Snackbar + 明细预览；
//   5) 零知识注释：行情报价是公开数据，仍走端侧加密通道。
//
// 设计纪律：
//   - 与 RatesImportScreen 同款镜像：文案全部走 strings.xml + testTag；
//   - 无运行时存储权限（SAF 系统选择器授权）；
//   - 全程不打印行情包明文到日志。
//
// 关联：
//   - android/.../ui/finance/FinanceViewModel.kt（quoteTableState / defaultCurrencyState /
//     quoteSyncUrlState / importQuoteTable / setDefaultCurrency / pullQuoteNow）
//   - android/.../ui/finance/FinanceRoutes.kt（QUOTES_IMPORT 路由常量）
//   - android/.../ui/settings/RatesImportScreen.kt（B5 同款模板镜像）
// ============================================================================

package com.everything.eve.ui.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
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
 * 投资行情包导入 / 同步 URL 设置全屏。
 *
 * @param vm 财务 ViewModel（行情表 / 默认币种 / 同步 URL 状态源 + 导入 / 同步动作）。
 * @param onClose 点击返回回调（FinanceScreen 清 quotesMode）。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun QuotesImportScreen(
    vm: FinanceViewModel,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    val table by vm.quoteTableState.collectAsState()
    val defaultCurrency by vm.defaultCurrencyState.collectAsState()
    val quoteSyncUrl by vm.quoteSyncUrlState.collectAsState()

    // 手填币种代码 / 同步 URL 的编辑态（仅 UI 本地态；合法后才进 VM）。
    var customCurrency by remember { mutableStateOf("") }
    var customUrl by remember(quoteSyncUrl) { mutableStateOf(quoteSyncUrl) }

    val msgSuccess = stringResource(R.string.finance_quotes_import_success)
    val msgFailed = stringResource(R.string.finance_quotes_import_failed)
    val msgReadFailed = stringResource(R.string.finance_quotes_read_failed)
    val msgCurrencyInvalid = stringResource(R.string.finance_rates_currency_invalid)
    val msgUrlInvalid = stringResource(R.string.finance_quotes_url_invalid)
    val msgUrlSyncOk = stringResource(R.string.finance_quotes_url_sync_ok)
    val msgUrlSyncFailed = stringResource(R.string.finance_quotes_url_sync_failed)

    // SAF 文件选择器：JSON 优先，*/* 兜底兼容文件管理器把 json 识别为 octet-stream。
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
            val result = vm.importQuoteTable(json)
            snackbar.showSnackbar(if (result.isSuccess) msgSuccess else msgFailed)
        }
    }

    // 行情明细按 symbol 字典序排序（TreeMap 天然字典序）。
    val sortedQuotes = remember(table) {
        table?.let { TreeMap(it.quotes) } ?: TreeMap()
    }

    Scaffold(
        modifier = Modifier.semantics { testTag = "quotes_import_screen" },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.finance_quotes_title)) },
                navigationIcon = {
                    TextButton(
                        onClick = onClose,
                        modifier = Modifier.semantics { testTag = "quotes_import_back" },
                    ) {
                        Text(stringResource(R.string.finance_quotes_back))
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
            // ========== 1. 默认折算币种（与 RatesImportScreen 同款镜像） ==========
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
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FinanceSettings.SUPPORTED_CURRENCIES.forEach { code ->
                                FilterChip(
                                    selected = defaultCurrency == code,
                                    onClick = { vm.setDefaultCurrency(code) },
                                    label = { Text(code) },
                                    modifier = Modifier.semantics {
                                        testTag = "quotes_currency_chip_$code"
                                    },
                                )
                            }
                        }
                        OutlinedTextField(
                            value = customCurrency,
                            onValueChange = { raw ->
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
                                .semantics { testTag = "quotes_currency_custom_input" },
                        )
                        Button(
                            onClick = {
                                val r = vm.setDefaultCurrency(customCurrency)
                                if (r.isSuccess) customCurrency = ""
                                else scope.launch { snackbar.showSnackbar(msgCurrencyInvalid) }
                            },
                            modifier = Modifier.semantics {
                                testTag = "quotes_currency_custom_apply"
                            },
                        ) {
                            Text(stringResource(R.string.finance_rates_section_default_currency))
                        }
                    }
                }
            }

            // ========== 2. 行情同步 URL ==========
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.finance_quotes_section_url),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        OutlinedTextField(
                            value = customUrl,
                            onValueChange = { raw ->
                                customUrl = raw.trim()
                            },
                            label = {
                                Text(stringResource(R.string.finance_quotes_url_label))
                            },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(
                                imeAction = ImeAction.Done,
                            ),
                            keyboardActions = KeyboardActions(
                                onDone = {
                                    val r = vm.setQuoteSyncUrl(customUrl)
                                    if (r.isFailure) {
                                        scope.launch { snackbar.showSnackbar(msgUrlInvalid) }
                                    }
                                },
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .semantics { testTag = "quotes_sync_url_input" },
                        )
                        Button(
                            onClick = {
                                val r = vm.pullQuoteNow(customUrl)
                                scope.launch {
                                    snackbar.showSnackbar(if (r.isSuccess) msgUrlSyncOk else msgUrlSyncFailed)
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .semantics { testTag = "quotes_sync_now_btn" },
                        ) {
                            Text(stringResource(R.string.finance_quotes_url_sync_now))
                        }
                    }
                }
            }

            // ========== 3. 当前行情包状态 ==========
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { testTag = "quotes_package_status" },
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.finance_quotes_package_section),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        if (table == null) {
                            Text(
                                text = stringResource(R.string.finance_quotes_not_imported),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.semantics { testTag = "quotes_package_none" },
                            )
                        } else {
                            val dateText = remember(table!!.ts) {
                                DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
                                    .withZone(ZoneId.systemDefault())
                                    .format(Instant.ofEpochMilli(table!!.ts))
                            }
                            Text(
                                text = stringResource(
                                    R.string.finance_quotes_effective_ts,
                                    dateText,
                                ),
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.semantics { testTag = "quotes_package_ts" },
                            )
                            Text(
                                text = stringResource(
                                    R.string.finance_quotes_count,
                                    table!!.quotes.size,
                                ),
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.semantics { testTag = "quotes_package_count" },
                            )
                        }
                    }
                }
            }

            // ========== 4. 选择行情包 JSON ==========
            item {
                Button(
                    onClick = { launcher.launch(arrayOf("application/json", "*/*")) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { testTag = "quotes_select_file_btn" },
                ) {
                    Text(stringResource(R.string.finance_rates_select_file))
                }
            }

            // ========== 5. 行情明细预览 ==========
            if (sortedQuotes.isNotEmpty()) {
                item {
                    Text(
                        text = stringResource(R.string.finance_quotes_preview_title),
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
                items(sortedQuotes.entries.toList()) { entry ->
                    Column {
                        Text(
                            text = stringResource(
                                R.string.finance_quotes_row_format,
                                entry.key,
                                formatPriceMinor(entry.value.priceMinor),
                                entry.value.currency,
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                                .semantics { testTag = "quotes_preview_row_${entry.key}" },
                        )
                        HorizontalDivider()
                    }
                }
            }

            // ========== 6. 零知识注释 ==========
            item {
                Text(
                    text = stringResource(R.string.finance_quotes_zero_knowledge_note),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }
        }
    }
}

/**
 * 价格 minor（分）格式化：分转元（BigDecimal.movePointLeft(2) 去尾零）。
 *
 * 例：3500 → "35"，3550 → "35.5"，10005 → "100.05"。
 */
private fun formatPriceMinor(minor: Long): String {
    val negative = minor < 0
    val absVal = kotlin.math.abs(minor)
    val whole = absVal / 100
    val frac = absVal % 100
    val core = if (frac == 0L) whole.toString() else "$whole.${frac.toString().padStart(2, '0')}"
    return if (negative) "-$core" else core
}
