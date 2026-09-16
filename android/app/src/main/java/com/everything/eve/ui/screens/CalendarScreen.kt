/*
 * 阶段 4b — Task 6 / TR-6.3：CalendarScreen 月/周视图容器。
 *
 * 设计要点（spec FR-10 / AC-8 / TR-6.3）：
 *   1. **月/周切换容器**：顶部 SegmentedButton（spec FR-10），状态由 ViewModel.mode 承载；
 *      UI 不直接持有本地 mode state，统一走 vm.setMode()。
 *   2. **翻页控件**：左右按钮调整 anchor（按月或按周）；点击"今天"重置回今日。
 *   3. **空白 / 事件点击**：
 *      - 空白 → 打开编辑器（id=null，预填初始 ts）。
 *      - 事件 → 打开编辑器（id=entity.id）。
 *   4. **编辑器跳转**：用内部 Compose state `editingId` + `editingStartMs` 控制何时显示
 *      [EventEditorScreen]；保存/取消/删除回调后置空回退到主视图。
 *   5. **删除按钮**：删除仅在编辑器内可见（[EventEditorScreen] 已有，TR-6.1）；本容器
 *      不重复暴露，避免两级删除入口歧义。
 *   6. **零知识**：onClick 只回传 entity.id / cellTs，不打印 title。
 *
 * 测试要点（TR-6.4）：
 *   - 月/周切换 → state.mode 更新。
 *   - 翻月 / 翻周 → state.anchorMs 更新。
 *   - 点击空白 → 编辑器打开（editingStartMs != null）。
 *   - 点击事件 → 编辑器打开（editingId != null）。
 *   - 编辑器保存 → 关闭编辑，state.occurrences 更新。
 *   - 编辑器删除 → 关闭编辑，state.occurrences 更新。
 */

package com.everything.eve.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
// ChevronLeft/Right 在 Compose 1.7+ 已迁到 AutoMirrored 命名空间（RTL 自动镜像）。
// KeyboardArrowLeft/Right 同为方向性 icon，使用 AutoMirrored 版本避免依赖 material-icons-extended。
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.everything.eve.R
import kotlinx.coroutines.launch
import java.util.Calendar

/**
 * 日历主屏幕容器（TR-6.3）。
 *
 * 容器职责：
 *   - 切换月/周
 *   - 翻页
 *   - 路由点击到编辑器
 *   - 展示 Snackbar 错误
 *
 * 编辑器职责（独立屏幕）：
 *   - 字段校验 / 保存 / 删除（TR-6.1）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalendarScreen(
    vm: CalendarViewModel = viewModel(),
) {
    val state by vm.state.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // 路由：编辑目标（null=回到主视图）
    var editingId by remember { mutableStateOf<String?>(null) }
    var editingStartMs by remember { mutableStateOf<Long?>(null) }

    // 错误 Snack（spec FR-11：保存/删除错误统一走 Snackbar）
    LaunchedEffect(state.errorMessage) {
        val msg = state.errorMessage ?: return@LaunchedEffect
        scope.launch {
            snackbar.showSnackbar(msg)
            vm.clearError()
        }
    }

    if (editingId != null || editingStartMs != null) {
        // 编辑器模式（独立屏幕，路由级跳转）
        EventEditorScreen(
            initialEntityId = editingId,
            initialStartMs = editingStartMs,
            onSaved = {
                editingId = null
                editingStartMs = null
            },
            onCancelled = {
                editingId = null
                editingStartMs = null
            },
            vm = vm,
        )
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.event_calendar_title)) },
                actions = {
                    TextButton(
                        onClick = { vm.goToToday() },
                        modifier = Modifier.semantics { testTag = "btn_today" },
                    ) {
                        Text(stringResource(R.string.event_today_btn))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
        ) {
            // 月/周切换
            ViewModeSwitcher(
                mode = state.mode,
                onChange = { vm.setMode(it) },
            )

            // 翻页行
            PagerBar(
                mode = state.mode,
                anchorMs = state.anchorMs,
                onPrev = { shiftAnchor(state.mode, state.anchorMs, -1) { vm.setAnchor(it) } },
                onNext = { shiftAnchor(state.mode, state.anchorMs, +1) { vm.setAnchor(it) } },
            )

            // 主体网格
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(4.dp),
            ) {
                // 当前收集到的 occurrences 由 ViewModel 在内部按窗口聚合；
                // 这里直接传 rawEntities 给 MonthGrid/WeekGrid 让它们按日分桶
                // （Occurrence → Entity 投影在 ViewModel 层完成；为减少映射，
                //  本期直接走 eventsRepo.observeAll 的最新快照 — 由 VM 的 getEntitiesSnapshot
                //  在编辑器入口拉取一次；这里用更轻的"全量路径"用 collectAsState 一次）。
                val entities = state.rawEntities
                when (state.mode) {
                    CalendarViewMode.MONTH -> MonthGrid(
                        anchorMs = state.anchorMs,
                        entities = entities,
                        onCellClick = { ts ->
                            editingId = null
                            editingStartMs = ts
                        },
                        onEventClick = { id ->
                            editingId = id
                            editingStartMs = null
                        },
                        onMoreClick = { /* 本期不开 agenda dialog，留作扩展 */ },
                    )
                    CalendarViewMode.WEEK -> WeekGrid(
                        anchorMs = state.anchorMs,
                        entities = entities,
                        onCellClick = { ts ->
                            editingId = null
                            editingStartMs = ts
                        },
                        onEventClick = { id ->
                            editingId = id
                            editingStartMs = null
                        },
                    )
                }
            }
        }
    }
}

/**
 * 月/周模式切换（spec FR-10 / TR-6.3）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ViewModeSwitcher(
    mode: CalendarViewMode,
    onChange: (CalendarViewMode) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.Center,
    ) {
        SingleChoiceSegmentedButtonRow {
            SegmentedButton(
                selected = mode == CalendarViewMode.MONTH,
                onClick = { onChange(CalendarViewMode.MONTH) },
                shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                modifier = Modifier.semantics { testTag = "mode_month" },
            ) {
                Text(stringResource(R.string.event_view_month))
            }
            SegmentedButton(
                selected = mode == CalendarViewMode.WEEK,
                onClick = { onChange(CalendarViewMode.WEEK) },
                shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                modifier = Modifier.semantics { testTag = "mode_week" },
            ) {
                Text(stringResource(R.string.event_view_week))
            }
        }
    }
}

/**
 * 翻页行（左右按钮 + 月份/周标题）。
 */
@Composable
private fun PagerBar(
    mode: CalendarViewMode,
    anchorMs: Long,
    onPrev: () -> Unit,
    onNext: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(
            onClick = onPrev,
            modifier = Modifier.semantics { testTag = "btn_prev" },
        ) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "prev")
        }
        Text(
            text = formatAnchor(mode, anchorMs),
            modifier = Modifier
                .weight(1f)
                .semantics { contentDescription = "anchor_label" },
            style = MaterialTheme.typography.titleMedium,
        )
        IconButton(
            onClick = onNext,
            modifier = Modifier.semantics { testTag = "btn_next" },
        ) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "next")
        }
    }
}

/** 翻页算法：按 mode 加减 1 月 / 1 周。 */
private inline fun shiftAnchor(
    mode: CalendarViewMode,
    anchorMs: Long,
    delta: Int,
    setAnchor: (Long) -> Unit,
) {
    val cal = Calendar.getInstance().apply {
        timeInMillis = anchorMs
        firstDayOfWeek = Calendar.SUNDAY
    }
    when (mode) {
        CalendarViewMode.MONTH -> cal.add(Calendar.MONTH, delta)
        CalendarViewMode.WEEK -> cal.add(Calendar.WEEK_OF_YEAR, delta)
    }
    setAnchor(cal.timeInMillis)
}

/** 标题栏文案："2026年9月" / "9月14日-20日"。 */
private fun formatAnchor(mode: CalendarViewMode, anchorMs: Long): String {
    val cal = Calendar.getInstance().apply { timeInMillis = anchorMs }
    val year = cal.get(Calendar.YEAR)
    val month = cal.get(Calendar.MONTH) + 1
    return when (mode) {
        CalendarViewMode.MONTH -> "${year}年${month}月"
        CalendarViewMode.WEEK -> {
            val s = Calendar.getInstance().apply {
                timeInMillis = anchorMs
                firstDayOfWeek = Calendar.SUNDAY
                set(Calendar.DAY_OF_WEEK, Calendar.SUNDAY)
            }
            val e = Calendar.getInstance().apply { timeInMillis = s.timeInMillis }
                .apply { add(Calendar.DAY_OF_MONTH, 6) }
            "${s.get(Calendar.MONTH) + 1}月${s.get(Calendar.DAY_OF_MONTH)}日-${e.get(Calendar.MONTH) + 1}月${e.get(Calendar.DAY_OF_MONTH)}日"
        }
    }
}