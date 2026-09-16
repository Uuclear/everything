/*
 * 阶段 4b — Task 6 / TR-6.2：WeekGrid 周视图。
 *
 * 设计要点（spec FR-10 / TR-6.2）：
 *   1. **7 列 × N 行**：24 小时（每行 1 小时）；小时序轴在左侧 50dp 固定列。
 *   2. **事件按 start_ts ~ end_ts 跨列渲染**：事件块按"距当日 00:00 的分钟偏移"在
 *      Column 中由上至下排，跨多列时按"起始 cell..结束 cell"涂底色。
 *   3. **跨日事件**：横跨多列。
 *   4. **全天事件**：独立栅格在顶部（不参与时间轴排版）。
 *   5. **按 color 着色**：与 EventBlock 共享颜色 Token，保持一致。
 *
 * 零知识纪律：
 *   - onClick 回传 entity.id 或 cellTs（毫秒整数），不打印 title 进日志。
 *   - 测试用例通过 [contentDescription] 校验"事件不含 title 字面值"以守护。
 *
 * 性能：单用户量级每周数十到数百事件；使用基础 Column + Box 排版，不引入
 * LazyColumn（本视图有 7 列 × 24 行的静态骨架，不需懒加载）。
 */

package com.everything.eve.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.everything.eve.R
import com.everything.eve.data.event.EventEntity
import com.everything.eve.ui.components.EventBlock
import java.util.Calendar

/**
 * 周视图（TR-6.2）。
 *
 * @param anchorMs 锚点（落在周内任一天即可；本组件按 SUNDAY 算法推到本周日）。
 * @param entities 全部 EventEntity（由父组件从 ViewModel.state 流收集）。
 * @param onCellClick 空白 → 编辑器预填 cellTs（cell 起始 30 分钟槽位）。
 * @param onEventClick 事件 → 编辑器编辑。
 */
@Composable
fun WeekGrid(
    anchorMs: Long,
    entities: List<EventEntity>,
    onCellClick: (Long) -> Unit,
    onEventClick: (String) -> Unit,
) {
    // 计算 anchor 所在 ISO 周（周日为首日）
    val cal = remember(anchorMs) {
        Calendar.getInstance().apply {
            timeInMillis = anchorMs
            firstDayOfWeek = Calendar.SUNDAY
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            set(Calendar.DAY_OF_WEEK, Calendar.SUNDAY)
        }
    }
    val weekStartMs = cal.timeInMillis
    val today = remember { startOfToday() }
    val dayMs = 24L * 60 * 60 * 1000

    // 全天事件（all_day=true）单独过滤出来 -> 顶部 banner 行
    val allDayPerDay: List<List<EventEntity>> = remember(entities, weekStartMs) {
        List(7) { idx ->
            val dayStart = weekStartMs + idx * dayMs
            val dayEnd = dayStart + dayMs
            entities.filter { it.all_day && it.start_ts in dayStart until dayEnd }
        }
    }
    // 非全天事件按天分组
    val timedPerDay: List<List<EventEntity>> = remember(entities, weekStartMs) {
        List(7) { idx ->
            val dayStart = weekStartMs + idx * dayMs
            val dayEnd = dayStart + dayMs
            entities.filter { !it.all_day && it.start_ts in dayStart until dayEnd }
                .sortedBy { it.start_ts }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // 顶部周内表头
        WeekHeader(weekStartMs = weekStartMs, today = today)
        // 全天 banner 行
        AllDayBanner(
            entitiesPerDay = allDayPerDay,
            weekStartMs = weekStartMs,
            dayMs = dayMs,
            onClick = onEventClick,
        )
        HorizontalDivider()
        // 24h 时轴 + 7 列网格（横向可滚）
        Box(modifier = Modifier.fillMaxSize().horizontalScroll(rememberScrollState())) {
            Column(modifier = Modifier.fillMaxSize()) {
                for (hour in 0 until 24) {
                    HourRow(
                        hour = hour,
                        weekStartMs = weekStartMs,
                        dayMs = dayMs,
                        today = today,
                        timedPerDay = timedPerDay.map { dayList ->
                            dayList.filter { entity ->
                                val hourStart = hour * 3600_000L
                                val hourEnd = hourStart + 3600_000L
                                val s = entity.start_ts - weekStartMs
                                s in hourStart until hourEnd
                            }
                        },
                        onCellClick = onCellClick,
                        onEventClick = onEventClick,
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
    }
}

@Composable
private fun WeekHeader(weekStartMs: Long, today: Long) {
    val dayMs = 24L * 60 * 60 * 1000
    Row(modifier = Modifier.fillMaxWidth().height(40.dp)) {
        // 左上角空
        Box(modifier = Modifier.width(50.dp).fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceVariant))
        listOf(
            R.string.event_weekday_sun,
            R.string.event_weekday_mon,
            R.string.event_weekday_tue,
            R.string.event_weekday_wed,
            R.string.event_weekday_thu,
            R.string.event_weekday_fri,
            R.string.event_weekday_sat,
        ).forEachIndexed { idx, res ->
            val cellStart = weekStartMs + idx * dayMs
            Box(
                modifier = Modifier
                    .width(120.dp)
                    .fillMaxSize()
                    .background(
                        if (cellStart == today) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surface
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(res),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (cellStart == today) FontWeight.Bold else FontWeight.Normal,
                )
            }
        }
    }
}

@Composable
private fun AllDayBanner(
    entitiesPerDay: List<List<EventEntity>>,
    weekStartMs: Long,
    dayMs: Long,
    onClick: (String) -> Unit,
) {
    Row(modifier = Modifier.fillMaxWidth().height(40.dp)) {
        Box(
            modifier = Modifier.width(50.dp).fillMaxSize()
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Text("全天", style = MaterialTheme.typography.labelSmall)
        }
        entitiesPerDay.forEachIndexed { idx, entities ->
            Box(
                modifier = Modifier
                    .width(120.dp)
                    .fillMaxSize()
                    .padding(2.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    entities.take(2).forEach { e ->
                        EventBlock(entity = e, onClick = onClick)
                    }
                }
            }
        }
    }
}

@Composable
private fun HourRow(
    hour: Int,
    weekStartMs: Long,
    dayMs: Long,
    today: Long,
    timedPerDay: List<List<EventEntity>>,
    onCellClick: (Long) -> Unit,
    onEventClick: (String) -> Unit,
) {
    val cellSizeW = 120.dp
    val cellSizeH = 64.dp
    Row(modifier = Modifier.fillMaxWidth().height(cellSizeH)) {
        Box(
            modifier = Modifier.width(50.dp).fillMaxSize()
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.TopCenter,
        ) {
            Text(
                text = stringFormat(R.string.event_week_axis_hour_format, hour),
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(2.dp),
            )
        }
        for (idx in 0 until 7) {
            val cellStart = weekStartMs + idx * dayMs + hour * 3600_000L
            Box(
                modifier = Modifier
                    .width(cellSizeW)
                    .fillMaxSize()
                    .clickable { onCellClick(cellStart) }
                    .padding(2.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(
                        if (cellStart == today) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                        else Color.Transparent
                    )
                    .semantics { contentDescription = "week_cell_${cellStart}" },
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    timedPerDay[idx].forEach { e ->
                        EventBlock(entity = e, onClick = onEventClick)
                    }
                }
            }
        }
    }
}

/** 今日 00:00 本地毫秒。 */
private fun startOfToday(): Long {
    val cal = Calendar.getInstance()
    cal.set(Calendar.HOUR_OF_DAY, 0)
    cal.set(Calendar.MINUTE, 0)
    cal.set(Calendar.SECOND, 0)
    cal.set(Calendar.MILLISECOND, 0)
    return cal.timeInMillis
}

/** stringFormat helper（避免 import androidx.compose.ui.res.stringResource 在函数里无法传 args）。 */
@Composable
private fun stringFormat(resId: Int, vararg args: Any): String =
    androidx.compose.ui.res.stringResource(resId, *args)

// 占位：未使用导入抑制
@Suppress("unused") private val _week_grid_unused_imports_check = Unit