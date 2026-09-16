/*
 * 阶段 4b — Task 6 / TR-6.1：RRuleBuilder 子组件。
 *
 * 对应 spec FR-2 / FR-9 / AC-10：RRULE B 档子集（DAILY/WEEKLY/MONTHLY/YEARLY +
 * interval + WEEKLY 多 weekday + MONTHLY 单 weekday + end 三选一）的 Compose
 * 表单交互。
 *
 * 字段流（无内部可变状态）—— value 是一个 `RRuleBuilderValue` 简单 data class，
 * 由父组件持有，所有 onValueChange 回传新值；不维护"原始 freq/interval"两个
 * 状态机，简化一致性。
 *
 * spec FR-9 表单不暴露 tz_mode；本组件只暴露 freq/interval/byweekday/end 四字段。
 */

@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
// 文件级 OptIn：RRuleBuilder 在 L236 / L287 使用 FlowRow（属于 ExperimentalLayoutApi）。
// 顶层声明避免对每个 @Composable 单独加 @OptIn，保持函数签名不变。

package com.everything.eve.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.everything.eve.R
import com.everything.eve.recurrence.Frequency
import com.everything.eve.recurrence.RRule
import com.everything.eve.recurrence.RRuleEnd
import com.everything.eve.recurrence.Weekday
import androidx.compose.foundation.text.KeyboardOptions

/**
 * RRuleBuilder 的 value 容器（与父 EventEditorScreen 双向绑定）。
 *
 * freq=null 表示"单次事件"（spec FR-2）；其他字段在 freq 非空时生效。
 * interval 默认 1。
 * byweekday：WEEKLY 可多选；MONTHLY 只取 [0]，多余值忽略（spec FR-2 "MONTHLY 单 weekday"）。
 * end 三类互斥。
 */
data class RRuleBuilderValue(
    val freq: Frequency? = null,
    val interval: Int = 1,
    val byweekday: List<Weekday> = emptyList(),
    val end: RRuleEnd = RRuleEnd.Never,
) {
    /** 序列化为 B 档 JSON 字符串（与 Web EventEditorDialog 一致；null → 单次）。 */
    fun toJsonOrNull(): String? {
        val f = freq ?: return null
        // spec FR-2 强校验：MONTHLY 只取 byweekday[0]（如多元素也截断到首元素）。
        val normalizedByWeekday: List<Weekday> = when (f) {
            Frequency.MONTHLY -> byweekday.take(1)
            else -> byweekday
        }
        val r = RRule(f, interval.coerceAtLeast(1), normalizedByWeekday, end)
        return com.everything.eve.recurrence.RecurrenceJson.encode(r)
    }
}

/**
 * RRuleBuilder（无状态子组件；value 由父持有）。
 *
 * @param value 当前 RRULE 状态。
 * @param onValueChange 子组件调整后回传新 value。
 * @param modifier 父布局修饰。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RRuleBuilder(
    value: RRuleBuilderValue,
    onValueChange: (RRuleBuilderValue) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            stringResource(R.string.event_rrule_section_label),
            style = MaterialTheme.typography.titleSmall,
        )

        // 频率切换：单次 + 4 档；用 SegmentedButton 紧凑呈现。
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            val segs = listOf<Pair<String, Frequency?>>(
                stringResource(R.string.event_rrule_none) to null,
                stringResource(R.string.event_rrule_freq_daily) to Frequency.DAILY,
                stringResource(R.string.event_rrule_freq_weekly) to Frequency.WEEKLY,
                stringResource(R.string.event_rrule_freq_monthly) to Frequency.MONTHLY,
                stringResource(R.string.event_rrule_freq_yearly) to Frequency.YEARLY,
            )
            segs.forEachIndexed { idx, (label, f) ->
                SegmentedButton(
                    selected = value.freq == f,
                    onClick = {
                        onValueChange(
                            value.copy(
                                freq = f,
                                byweekday = if (f == Frequency.MONTHLY && value.byweekday.isNotEmpty()) {
                                    listOf(value.byweekday[0])
                                } else value.byweekday,
                            )
                        )
                    },
                    shape = SegmentedButtonDefaults.itemShape(idx, segs.size),
                    label = { Text(label) },
                )
            }
        }

        // freq=null 不再展开其余面板。
        val f = value.freq ?: return@Column

        // interval
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                stringResource(R.string.event_rrule_interval_label),
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedTextField(
                value = if (value.interval <= 0) "" else value.interval.toString(),
                onValueChange = { txt ->
                    val n = txt.toIntOrNull() ?: 0
                    onValueChange(value.copy(interval = n.coerceAtLeast(0)))
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(0.4f),
                label = { Text(stringResource(R.string.event_rrule_interval_format, 1)) },
            )
        }

        // WEEKLY 多 weekday
        if (f == Frequency.WEEKLY) {
            Text(
                stringResource(R.string.event_rrule_byweekday_label),
                style = MaterialTheme.typography.bodySmall,
            )
            WeekdayChips(
                selected = value.byweekday.toSet(),
                onToggle = { w ->
                    val next = value.byweekday.toMutableSet().apply {
                        if (!add(w)) remove(w)
                    }.toList()
                    onValueChange(value.copy(byweekday = next))
                },
                singleSelect = false,
            )
        }
        // MONTHLY 单 weekday
        if (f == Frequency.MONTHLY) {
            Text(
                stringResource(R.string.event_rrule_monthly_weekday_label),
                style = MaterialTheme.typography.bodySmall,
            )
            // 当前月内第几个该 weekday：N 由 rule.start_ts 推算；此处 UI 只暴露 weekday 选择，
            // N 由展开算法根据 start_ts 自动推导（Recurrence.nthOfWeekdayInMonth）。
            WeekdayChips(
                selected = value.byweekday.take(1).toSet(),
                onToggle = { w -> onValueChange(value.copy(byweekday = listOf(w))) },
                singleSelect = true,
            )
        }

        // end 三选一
        EndRow(
            end = value.end,
            onChange = { e -> onValueChange(value.copy(end = e)) },
        )

        // end 子条件输入
        when (val e = value.end) {
            is RRuleEnd.Date -> {
                OutlinedTextField(
                    value = e.until,
                    onValueChange = { txt ->
                        onValueChange(value.copy(end = RRuleEnd.Date(txt)))
                    },
                    singleLine = true,
                    label = { Text(stringResource(R.string.event_rrule_until_label)) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            is RRuleEnd.Count -> {
                OutlinedTextField(
                    value = if (e.count <= 0) "" else e.count.toString(),
                    onValueChange = { txt ->
                        val n = txt.toIntOrNull() ?: 0
                        onValueChange(value.copy(end = RRuleEnd.Count(n.coerceAtLeast(0))))
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    label = { Text(stringResource(R.string.event_rrule_count_label)) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            RRuleEnd.Never -> Unit
        }
    }
}

/**
 * WORKDAY chip 多/单选（用 AssistChip + checkbox 表达显式选择状态，
 * 比 FlowRow + Checkbox 更接近 Material3 习惯用法）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WeekdayChips(
    selected: Set<Weekday>,
    onToggle: (Weekday) -> Unit,
    singleSelect: Boolean,
) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Weekday.entries.forEach { w ->
            val isSelected = w in selected
            AssistChip(
                onClick = { onToggle(w) },
                label = { Text(weekdayLabel(w)) },
                leadingIcon = {
                    Checkbox(
                        checked = isSelected,
                        onCheckedChange = null, // 状态由父决定，这里只展示
                    )
                },
                colors = AssistChipDefaults.assistChipColors(),
            )
        }
    }
    // 抑制 unused（singleSelect 当前由 value 自身状态承担；保留参数便于将来扩展）。
    @Suppress("UNUSED_EXPRESSION") singleSelect
}

@Composable
private fun weekdayLabel(w: Weekday): String = when (w) {
    Weekday.MO -> stringResource(R.string.event_weekday_mon)
    Weekday.TU -> stringResource(R.string.event_weekday_tue)
    Weekday.WE -> stringResource(R.string.event_weekday_wed)
    Weekday.TH -> stringResource(R.string.event_weekday_thu)
    Weekday.FR -> stringResource(R.string.event_weekday_fri)
    Weekday.SA -> stringResource(R.string.event_weekday_sat)
    Weekday.SU -> stringResource(R.string.event_weekday_sun)
}

/**
 * End 三选一：Never / Date / Count。本子组件内部维护"展开哪个子输入面板"
 * （已透传 end 类型，UI 由 RRuleBuilder 主体处理），这里只渲染切换按钮。
 */
@Composable
private fun EndRow(
    end: RRuleEnd,
    onChange: (RRuleEnd) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            stringResource(R.string.event_rrule_end_label),
            style = MaterialTheme.typography.bodySmall,
        )
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            SegmentedChoiceChip(
                label = stringResource(R.string.event_rrule_end_never),
                selected = end is RRuleEnd.Never,
                onClick = { onChange(RRuleEnd.Never) },
            )
            SegmentedChoiceChip(
                label = stringResource(R.string.event_rrule_end_date),
                selected = end is RRuleEnd.Date,
                onClick = { onChange(RRuleEnd.Date("")) },
            )
            SegmentedChoiceChip(
                label = stringResource(R.string.event_rrule_end_count),
                selected = end is RRuleEnd.Count,
                onClick = { onChange(RRuleEnd.Count(10)) },
            )
        }
    }
}

/**
 * 简化的"单选 chip"（用 TextButton + 颜色表达选中态；不引入额外组件）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SegmentedChoiceChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    AssistChip(
        onClick = onClick,
        label = { Text(label) },
        colors = AssistChipDefaults.assistChipColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surface,
        ),
    )
}
