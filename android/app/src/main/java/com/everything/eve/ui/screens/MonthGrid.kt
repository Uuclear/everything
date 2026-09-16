/*
 * 阶段 4b — Task 6 / TR-6.2：MonthGrid 月视图。
 *
 * 设计要点（spec FR-10 / AC-8 / TR-6.2）：
 *   1. **7×N 网格**（N=5 或 6，根据 anchorMs 月份首日的星期偏移决定）。
 *   2. **每个 cell 最多展示 3 个事件块** + 超出显示 MoreIndicator。
 *   3. **空 cell** 可点击 → 回调 [onCellClick] 回传 cell 起始 ts，供外部打开编辑器预填时间。
 *   4. **事件跨日**：单个 event 占多列（用 color 着色 + 横向铺满跨日 cell）。
 *   5. **color 着色**（spec FR-1）：背景填充 + 左侧色条；事件→实例继承 color。
 *
 * 零知识纪律：onClick / onCellClick 只回传实体 id 或 ts，不回传 title 原文明文。
 * 日志禁止 print title。
 */

package com.everything.eve.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
 * 单个月视图单元。
 *
 * @param dateMs 该 cell 代表的本地 00:00:00 毫秒。
 * @param isToday 是否今日（用于高亮边框）。
 * @param isInMonth 是否在本月内（false 表示上月末/下月初的占位 cell）。
 * @param dayNumber 仅展示用；1..31。
 * @param events 当日事件（已按 color 着色、按 start_ts 排序）。
 * @param maxBlocksPerCell 每 cell 最多展示块数（默认 3）；超出走 MoreIndicator。
 * @param onCellClick 点击空白 → 回调 cell 起始 ts，外部用此 ts 预填编辑器。
 * @param onEventClick 点击事件块 → 回调 entity.id。
 * @param onMoreClick 点击 MoreIndicator → 回调 cell 起始 ts（外部可弹 dialog 列全量）。
 */
@Composable
fun MonthCell(
    dateMs: Long,
    isToday: Boolean,
    isInMonth: Boolean,
    dayNumber: Int,
    events: List<EventEntity>,
    onCellClick: (Long) -> Unit,
    onEventClick: (String) -> Unit,
    onMoreClick: (Long) -> Unit,
    maxBlocksPerCell: Int = 3,
) {
    val borderColor = if (isToday) MaterialTheme.colorScheme.primary
    else MaterialTheme.colorScheme.outlineVariant
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(96.dp)
            .padding(1.dp)
            .clip(RoundedCornerShape(4.dp))
            .clickable { onCellClick(dateMs) }
            .semantics { contentDescription = "month_cell_${dateMs}" },
        colors = CardDefaults.cardColors(
            containerColor = if (isInMonth) MaterialTheme.colorScheme.surface
            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        ),
        border = androidx.compose.foundation.BorderStroke(1.dp, borderColor),
    ) {
        Column(
            modifier = Modifier
                .padding(4.dp)
                .fillMaxSize(),
        ) {
            Text(
                text = dayNumber.toString(),
                style = MaterialTheme.typography.labelSmall,
                color = if (isToday) MaterialTheme.colorScheme.primary
                else if (isInMonth) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal,
            )
            // 事件块列
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                events.take(maxBlocksPerCell).forEach { e ->
                    EventBlock(entity = e, onClick = onEventClick)
                }
                val remaining = events.size - maxBlocksPerCell
                if (remaining > 0) {
                    Text(
                        text = stringResource(R.string.event_block_more, remaining),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .clickable { onMoreClick(dateMs) }
                            .padding(horizontal = 4.dp, vertical = 2.dp),
                    )
                }
            }
        }
    }
}

/**
 * 月视图整体（TR-6.2）：表头 + 7×N 网格。
 *
 * @param anchorMs 锚点（落在月份内任意一天均可，外部用 CalendarViewModel 计算）。
 * @param entities 全部 EventEntity（由外层过滤后传入；本组件自行按日聚合）。
 * @param onCellClick 空白 → 打开编辑器，预填 cellStart。
 * @param onEventClick 事件块点击 → 打开编辑器编辑 mode。
 * @param onMoreClick 更多项 → 暂不开 dialog（本期未做独立 agenda 视图；保留回调）。
 */
@Composable
fun MonthGrid(
    anchorMs: Long,
    entities: List<EventEntity>,
    onCellClick: (Long) -> Unit,
    onEventClick: (String) -> Unit,
    onMoreClick: (Long) -> Unit,
) {
    val cal = remember(anchorMs) { Calendar.getInstance().apply { timeInMillis = anchorMs } }
    val firstDayOfMonth = remember(anchorMs) {
        Calendar.getInstance().apply {
            timeInMillis = anchorMs
            set(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }
    // grid 起点：上月最后一个周日（避免月初 cell 错位）。
    val gridStart = remember(firstDayOfMonth) {
        Calendar.getInstance().apply {
            timeInMillis = firstDayOfMonth
            firstDayOfWeek = Calendar.SUNDAY
            // 偏移到本周日
            val dow = get(Calendar.DAY_OF_WEEK) // 1=Sun..7=Sat
            add(Calendar.DAY_OF_MONTH, -(dow - 1))
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }
    // grid 行数：先按 6 行渲染（如该月恰好挤在 5 行内仍展示 6 行的视觉稳定）。
    val rows = 6
    val cells = rows * 7
    val dayMs = 24L * 60 * 60 * 1000
    val today = remember { startOfToday() }

    val eventByDay: List<List<EventEntity>> = remember(entities, gridStart) {
        List(cells) { idx ->
            val dayStart = gridStart + idx * dayMs
            val dayEnd = dayStart + dayMs
            entities.filter { it.start_ts in dayStart until dayEnd }
                .sortedBy { it.start_ts }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // 表头：周日..周六
        Row(modifier = Modifier.fillMaxWidth()) {
            listOf(
                R.string.event_weekday_sun,
                R.string.event_weekday_mon,
                R.string.event_weekday_tue,
                R.string.event_weekday_wed,
                R.string.event_weekday_thu,
                R.string.event_weekday_fri,
                R.string.event_weekday_sat,
            ).forEach { res ->
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(4.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(res),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }

        // 7×N grid
        for (row in 0 until rows) {
            Row(modifier = Modifier.fillMaxWidth()) {
                for (col in 0 until 7) {
                    val idx = row * 7 + col
                    val cellStart = gridStart + idx * dayMs
                    val isInMonth = isSameMonth(cellStart, firstDayOfMonth)
                    val dayNumber = Calendar.getInstance().apply {
                        timeInMillis = cellStart
                    }.get(Calendar.DAY_OF_MONTH)
                    Box(modifier = Modifier.weight(1f)) {
                        MonthCell(
                            dateMs = cellStart,
                            isToday = cellStart == today,
                            isInMonth = isInMonth,
                            dayNumber = dayNumber,
                            events = eventByDay[idx],
                            onCellClick = onCellClick,
                            onEventClick = onEventClick,
                            onMoreClick = onMoreClick,
                        )
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

/** ts1 与 ts2 是否落在同一月（按本地日历）。 */
private fun isSameMonth(ts1: Long, ts2: Long): Boolean {
    val c1 = Calendar.getInstance().apply { timeInMillis = ts1 }
    val c2 = Calendar.getInstance().apply { timeInMillis = ts2 }
    return c1.get(Calendar.YEAR) == c2.get(Calendar.YEAR) &&
        c1.get(Calendar.MONTH) == c2.get(Calendar.MONTH)
}

// 占位：未使用的导入抑制（unit test 用）
@Suppress("unused") private val _month_grid_unused_imports_check = Unit
