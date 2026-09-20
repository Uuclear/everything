/*
 * ============================================================================
 * FinanceScreen —— 财务模块主容器（stage5-finance / Task 7 / TR-7.4）
 * ============================================================================
 *
 * 设计要点：
 *   1. **顶部 Tab + Dashboard / 列表**：tab = dashboard / accounts / cards /
 *      txs；当前 tab 由本地 remember state 持有（不在 ViewModel —— 一旦 ViewModel
 *      因旋转重建，UI 重新选默认 dashboard 即可，避免不必要的 state 耦合）。
 *   2. **编辑器入口**：每个列表行 click → editorScreen(kind, entityId)；FAB 加 →
 *      editorScreen(kind, null)；本 T7 任务编译器连通即可，正式 main navigation
 *      接入由 T10 负责。
 *   3. **snackbar**：监听 vm.eventFlow；SaveSucceeded / DeleteSucceeded → 轻提示；
 *      Error(code) → 走 finance_strings.xml 错误文案。
 *   4. **零知识（spec NFR-1）**：snackbar 文案不含 name / amount / last4 等明文。
 *
 * 关联：
 *   - tasks.md TR-7.4
 *   - ui/finance/FinanceViewModel.eventFlow
 * ============================================================================
 */

package com.everything.eve.ui.finance

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.everything.eve.R
import com.everything.eve.ui.settings.RatesImportScreen
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

// =============================================================================
// B6 预算管理路由哨兵值（budgetMode: String?；null=未进入预算模式）
// =============================================================================
/** 预算列表态。 */
const val BUDGET_LIST = "LIST"

/** 预算新建态（编辑态直接用预算 id 作为 mode 值）。 */
const val BUDGET_NEW = "NEW"

/**
 * 财务模块主屏幕（NavHost 容器）。
 *
 * 内部状态：
 *   - `tab` 当前 tab key（dashboard / accounts / cards / txs）
 *   - `editingKind` 编辑器类型（null=关闭）
 *   - `editingId` 编辑器实体 id（null=新建）
 *
 * @param vm FinanceViewModel；测试场景可注入 fake vm。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FinanceScreen(
    vm: FinanceViewModel = viewModel(),
) {
    // 顶部 tab key（用 FinanceRoutes 常量，与导航接入点保持一致）
    var tab by remember { mutableStateOf(FinanceRoutes.TAB_DASHBOARD) }

    // 编辑器路由态（null = 不显示编辑器）
    var editingKind by remember { mutableStateOf<FinanceEditorKind?>(null) }
    var editingId by remember { mutableStateOf<String?>(null) }

    // B5 设置屏路由态（false = 不显示；全屏承载 RatesImportScreen，返回态仿编辑器）
    var settingsMode by remember { mutableStateOf(false) }

    // B6 预算管理路由态：null=不显示；BUDGET_LIST=列表；BUDGET_NEW=新建；
    // 其他字符串=按预算 id 编辑（见文件顶部哨兵常量）。
    var budgetMode by remember { mutableStateOf<String?>(null) }

    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // 一次性事件订阅（spec NFR-3）—— Save/Delete/Error 走 Snackbar（不渲染敏感字段）
    LaunchedEffect(Unit) {
        vm.eventFlow.collectLatest { ev ->
            when (ev) {
                is FinanceUiEvent.SaveSucceeded -> {
                    scope.launch { snackbar.showSnackbar("已保存（id 摘要）") }
                }
                is FinanceUiEvent.DeleteSucceeded -> {
                    scope.launch { snackbar.showSnackbar("已删除（id 摘要）") }
                }
                is FinanceUiEvent.Error -> {
                    scope.launch { snackbar.showSnackbar("操作失败：" + ev.code) }
                }
            }
        }
    }

    // 编辑器模式（独立屏路由）
    if (editingKind != null) {
        FinanceEditor(
            kind = editingKind!!,
            entityId = editingId,
            vm = vm,
            onDone = {
                editingKind = null
                editingId = null
            },
            onCancel = {
                editingKind = null
                editingId = null
            },
        )
        return
    }

    // B5 设置模式：汇率包导入 / 默认币种（全屏承载，交互骨架与编辑器一致）
    if (settingsMode) {
        RatesImportScreen(
            vm = vm,
            onClose = { settingsMode = false },
        )
        return
    }

    // B6 预算管理模式：列表 / 新建 / 按 id 编辑（三个全屏分支，返回态各自回退）。
    when (budgetMode) {
        BUDGET_LIST -> {
            BudgetListScreen(
                vm = vm,
                onClose = { budgetMode = null },
                onEdit = { id -> budgetMode = id ?: BUDGET_NEW },
            )
            return
        }
        BUDGET_NEW -> {
            BudgetEditorScreen(
                budgetId = null,
                vm = vm,
                onClose = { budgetMode = BUDGET_LIST },
            )
            return
        }
        null -> Unit
        else -> {
            // 其余取值视为预算 id（从列表行点击进入编辑）。
            BudgetEditorScreen(
                budgetId = budgetMode,
                vm = vm,
                onClose = { budgetMode = BUDGET_LIST },
            )
            return
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text(stringResource(R.string.finance_nav_title)) })
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            // Tab 行（dashboard / accounts / cards / txs 四选一）
            TabRow(
                selectedTabIndex = tabIndexOf(tab),
                modifier = Modifier.semantics { testTag = "finance_tab_row" },
            ) {
                Tab(
                    selected = tab == FinanceRoutes.TAB_DASHBOARD,
                    onClick = { tab = FinanceRoutes.TAB_DASHBOARD },
                    text = { Text(stringResource(R.string.finance_tab_dashboard)) },
                    modifier = Modifier.semantics { testTag = "tab_dashboard" },
                )
                Tab(
                    selected = tab == FinanceRoutes.TAB_ACCOUNTS,
                    onClick = { tab = FinanceRoutes.TAB_ACCOUNTS },
                    text = { Text(stringResource(R.string.finance_tab_accounts)) },
                    modifier = Modifier.semantics { testTag = "tab_accounts" },
                )
                Tab(
                    selected = tab == FinanceRoutes.TAB_CARDS,
                    onClick = { tab = FinanceRoutes.TAB_CARDS },
                    text = { Text(stringResource(R.string.finance_tab_cards)) },
                    modifier = Modifier.semantics { testTag = "tab_cards" },
                )
                Tab(
                    selected = tab == FinanceRoutes.TAB_TXS,
                    onClick = { tab = FinanceRoutes.TAB_TXS },
                    text = { Text(stringResource(R.string.finance_tab_txs)) },
                    modifier = Modifier.semantics { testTag = "tab_txs" },
                )
            }

            // 当前 tab 内容
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 0.dp),
            ) {
                when (tab) {
                    FinanceRoutes.TAB_DASHBOARD -> FinanceDashboard(
                        vm = vm,
                        onOpenSettings = { settingsMode = true },
                        onOpenBudgets = { budgetMode = BUDGET_LIST },
                    )
                    FinanceRoutes.TAB_ACCOUNTS -> FinanceAccountList(
                        vm = vm,
                        onAdd = {
                            editingKind = FinanceEditorKind.ACCOUNT
                            editingId = null
                        },
                        onEdit = { id ->
                            editingKind = FinanceEditorKind.ACCOUNT
                            editingId = id
                        },
                    )
                    FinanceRoutes.TAB_CARDS -> FinanceCardList(
                        vm = vm,
                        onAdd = {
                            editingKind = FinanceEditorKind.CARD
                            editingId = null
                        },
                        onEdit = { id ->
                            editingKind = FinanceEditorKind.CARD
                            editingId = id
                        },
                    )
                    FinanceRoutes.TAB_TXS -> FinanceTxList(
                        vm = vm,
                        onAdd = {
                            editingKind = FinanceEditorKind.TX
                            editingId = null
                        },
                        onEdit = { id ->
                            editingKind = FinanceEditorKind.TX
                            editingId = id
                        },
                    )
                    else -> FinanceDashboard(vm = vm)
                }
            }
        }
    }
}

/**
 * 把 tab key 转 TabRow 索引（dashboard=0 / accounts=1 / cards=2 / txs=3）。
 *
 * 兜底：未知值 → dashboard。
 */
private fun tabIndexOf(tab: String): Int = when (tab) {
    FinanceRoutes.TAB_DASHBOARD -> 0
    FinanceRoutes.TAB_ACCOUNTS -> 1
    FinanceRoutes.TAB_CARDS -> 2
    FinanceRoutes.TAB_TXS -> 3
    else -> 0
}
