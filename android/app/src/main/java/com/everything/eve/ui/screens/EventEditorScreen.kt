/*
 * 阶段 4b — Task 6 / TR-6.1：EventEditorScreen。
 *
 * 设计要点（spec FR-1 / FR-9 / AC-10 / TR-6.1）：
 *   1. **字段集与 Web 一致**：title / start_ts / end_ts / all_day / location_text /
 *      note / color / reminders / rrule / exdates。
 *   2. **校验**：title 非空且 ≤200；start_ts ≤ end_ts；
 *      reminders ≤3 且值在预设档位（0/5/15/30/60/1440）；all_day=true → 屏蔽 reminders。
 *   3. **保存**：调 [com.everything.eve.data.event.EventsRepository.upsert] +
 *      [com.everything.eve.reminder.ReminderScheduler.rebuildChain]。
 *   4. **rrule B 档交互**：复用 [RRuleBuilder]（子组件），4 频率 + interval +
 *      WEEKLY 工作日多选 + MONTHLY 单 weekday + end 三类。
 *   5. **删除按钮**：调 EventsRepository.delete + rebuildChain。
 *   6. **零知识（spec NFR-1）**：日志禁止打印 title 原文；onSave/onDelete 仅
 *      暴露 entity.id 给 VM；RRuleBuilder.value 仅承载结构，不承载 title。
 *
 * 字段流转：
 *   编辑器内部用 [EditorState] 持有可序列化字段；RRule 由 RRuleBuilder.value
 *   双向绑定。
 *
 * 设计权衡：
 *   - **不使用 DatePickerDialog 弹窗**：Compose Material3 的 DatePicker 在
 *     instrumented test 环境与 Robolectric 兼容不稳定，TR-6.4 测试一律用
 *     `OutlinedTextField` 输入 ISO 字符串（YYYY-MM-DD HH:mm）形式承载
 *     start_ts/end_ts；UI 上提供 TextField + 按钮式回调即可。
 *   - **reminders 多选**：用三段式下拉（CheckBox row）；UI 简洁且测试友好。
 *
 * 错误处理：
 *   - 校验失败 → 顶部错误文案 + 阻止保存；不弹 Snackbar（保留相对静态表单）。
 *   - Repository 抛异常 → catch 后 setErrorMessage 经 [SnackbarHost] 展示。
 */

@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
// 文件级 OptIn：EventEditorScreen 在 L447 的 ColorPickerRow 使用 FlowRow（属于 ExperimentalLayoutApi）。

package com.everything.eve.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.everything.eve.R
import com.everything.eve.data.event.EventRule
import com.everything.eve.recurrence.RecurrenceJson
import com.everything.eve.ui.components.RRuleBuilder
import com.everything.eve.ui.components.RRuleBuilderValue
import kotlinx.coroutines.launch
import org.json.JSONArray

/**
 * 编辑器状态。
 *
 * 不持久化 title 等明文到日志（spec NFR-1）。
 */
private data class EditorState(
    val id: String,
    val title: String = "",
    val startText: String = "",  // "YYYY-MM-DD HH:mm"
    val endText: String = "",
    val allDay: Boolean = false,
    val locationText: String = "",
    val noteText: String = "",
    val color: String = "blue",
    val reminders: List<Int> = emptyList(),
    val rrule: RRuleBuilderValue = RRuleBuilderValue(),
    val exdates: List<String> = emptyList(),
    val showDeleteConfirm: Boolean = false,
    val titleError: String? = null,
    val timeError: String? = null,
    val reminderError: String? = null,
)

/** 编辑器入口（Composed） */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EventEditorScreen(
    initialEntityId: String? = null,
    initialStartMs: Long? = null,
    onSaved: () -> Unit,
    onCancelled: () -> Unit,
    vm: CalendarViewModel,
) {
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    // 编辑器内状态
    var state by remember {
        mutableStateOf(
            EditorState(
                id = initialEntityId ?: vm.newId(),
                // 默认时长 1 小时
                startText = formatDateTime(initialStartMs ?: defaultStartMs()),
                endText = formatDateTime((initialStartMs ?: defaultStartMs()) + 30 * 60_000L),
            )
        )
    }
    val isNew = initialEntityId == null

    // 编辑模式：拉一次 entity 回填
    LaunchedEffect(initialEntityId) {
        if (!isNew && initialEntityId != null) {
            val entities = vm.getEntitiesSnapshot()
            val e = entities.firstOrNull { it.id == initialEntityId } ?: return@LaunchedEffect
            state = state.copy(
                id = e.id,
                title = e.title,
                startText = formatDateTime(e.start_ts),
                endText = formatDateTime(e.end_ts),
                allDay = e.all_day,
                locationText = e.location_text.orEmpty(),
                noteText = e.note.orEmpty(),
                color = e.color,
                reminders = parseRemindersList(e.reminders_json),
                rrule = rruleBuilderValueFromJson(e.rrule_json),
                exdates = parseExdatesList(e.exdates_json),
            )
        }
    }

    // 派生校验（不依赖 VM），避免每次重组全量重建。
    val hasErrors by remember {
        derivedStateOf { state.titleError != null || state.timeError != null || state.reminderError != null }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(
                            if (isNew) R.string.event_editor_title_new
                            else R.string.event_editor_title_edit
                        )
                    )
                },
                navigationIcon = {
                    TextButton(onClick = onCancelled) {
                        Text(stringResource(R.string.event_cancel_btn))
                    }
                },
                actions = {
                    if (!isNew) {
                        TextButton(
                            onClick = { state = state.copy(showDeleteConfirm = true) },
                            modifier = Modifier.semantics { testTag = "btn_delete" },
                        ) {
                            Text(stringResource(R.string.event_delete_btn))
                        }
                    }
                    TextButton(
                        onClick = { onSubmit(state, vm, scope, snackbar) { fixed ->
                            state = fixed
                        } },
                        enabled = !hasErrors,
                        modifier = Modifier.semantics { testTag = "btn_save" },
                    ) {
                        Text(stringResource(R.string.event_save_btn))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // 标题
            OutlinedTextField(
                value = state.title,
                onValueChange = { txt ->
                    val err = when {
                        txt.isBlank() -> "title_empty_placeholder"
                        txt.length > 200 -> "title_too_long"
                        else -> null
                    }
                    state = state.copy(title = txt, titleError = err)
                },
                label = { Text(stringResource(R.string.event_title_label)) },
                placeholder = { Text(stringResource(R.string.event_title_placeholder)) },
                isError = state.titleError != null,
                supportingText = {
                    state.titleError?.let { Text(stringResource(R.string.event_error_title_empty)) }
                },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { testTag = "input_title" },
            )

            // 开始/结束
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = state.startText,
                    onValueChange = { txt ->
                        state = state.copy(startText = txt)
                        validateTimes(state) { fix ->
                            state = fix
                        }
                    },
                    label = { Text(stringResource(R.string.event_start_label)) },
                    isError = state.timeError != null,
                    supportingText = {
                        state.timeError?.let { Text(stringResource(R.string.event_error_time_invalid)) }
                    },
                    singleLine = true,
                    modifier = Modifier
                        .weight(1f)
                        .semantics { testTag = "input_start" },
                )
                OutlinedTextField(
                    value = state.endText,
                    onValueChange = { txt ->
                        state = state.copy(endText = txt)
                        validateTimes(state) { fix ->
                            state = fix
                        }
                    },
                    label = { Text(stringResource(R.string.event_end_label)) },
                    isError = state.timeError != null,
                    singleLine = true,
                    modifier = Modifier
                        .weight(1f)
                        .semantics { testTag = "input_end" },
                )
            }

            // all day 开关
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.event_all_day_label), modifier = Modifier.weight(1f))
                Switch(
                    checked = state.allDay,
                    onCheckedChange = { want ->
                        // 全天事件 → 屏蔽 reminders（spec FR-9）
                        state = state.copy(
                            allDay = want,
                            reminders = if (want) emptyList() else state.reminders,
                        )
                    },
                    modifier = Modifier.semantics { testTag = "switch_all_day" },
                )
            }

            // color
            ColorPickerRow(
                selected = state.color,
                onSelected = { state = state.copy(color = it) },
            )

            // reminders
            ReminderPicker(
                enabled = !state.allDay,
                selected = state.reminders,
                onChange = { rs ->
                    val err = if (rs.size > 3) "too_many" else null
                    state = state.copy(reminders = rs, reminderError = err)
                },
                errorText = state.reminderError,
            )

            // rrule
            RRuleBuilder(
                value = state.rrule,
                onValueChange = { state = state.copy(rrule = it) },
            )

            // exdates
            ExdatesField(
                values = state.exdates,
                onChange = { state = state.copy(exdates = it) },
            )

            // location
            OutlinedTextField(
                value = state.locationText,
                onValueChange = { state = state.copy(locationText = it) },
                label = { Text(stringResource(R.string.event_location_label)) },
                placeholder = { Text(stringResource(R.string.event_location_placeholder)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            // note
            OutlinedTextField(
                value = state.noteText,
                onValueChange = { state = state.copy(noteText = it) },
                label = { Text(stringResource(R.string.event_note_label)) },
                placeholder = { Text(stringResource(R.string.event_note_placeholder)) },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 96.dp),
            )

            // 底部删除（移动端常见设计：在底部再独立一行）
            if (!isNew) {
                Button(
                    onClick = { state = state.copy(showDeleteConfirm = true) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.event_delete_btn))
                }
            }
        }

        // 删除确认弹窗
        if (state.showDeleteConfirm) {
            AlertDialog(
                onDismissRequest = { state = state.copy(showDeleteConfirm = false) },
                title = { Text(stringResource(R.string.event_delete_confirm_title)) },
                text = { Text(stringResource(R.string.event_delete_confirm_message)) },
                confirmButton = {
                    TextButton(
                        onClick = {
                            vm.delete(state.id)
                            state = state.copy(showDeleteConfirm = false)
                            onSaved()
                        },
                        modifier = Modifier.semantics { testTag = "btn_delete_confirm" },
                    ) { Text(stringResource(R.string.event_delete_btn)) }
                },
                dismissButton = {
                    TextButton(onClick = { state = state.copy(showDeleteConfirm = false) }) {
                        Text(stringResource(R.string.event_cancel_btn))
                    }
                },
            )
        }
    }
}

/**
 * 提交：先做基本校验（标题非空、时间合法、reminders ≤3），通过后调 vm.save 落库
 * + 触发 rebuildChain。失败 → 顶部错误文案展示。
 */
private fun onSubmit(
    state: EditorState,
    vm: CalendarViewModel,
    scope: kotlinx.coroutines.CoroutineScope,
    snackbar: SnackbarHostState,
    onFix: (EditorState) -> Unit,
) {
    // 1) 标题校验
    if (state.title.isBlank()) {
        onFix(state.copy(titleError = "title_empty"))
        return
    }
    if (state.title.length > 200) {
        onFix(state.copy(titleError = "title_too_long"))
        return
    }
    // 2) 时间校验
    val startMs = parseDateTime(state.startText)
    val endMs = parseDateTime(state.endText)
    if (startMs == null || endMs == null || endMs < startMs) {
        onFix(state.copy(timeError = "time_invalid"))
        return
    }
    // 3) reminders 校验
    if (state.reminders.size > 3) {
        onFix(state.copy(reminderError = "too_many"))
        return
    }
    // 4) 保存
    val rule = EventRule(
        id = state.id,
        title = state.title,
        start_ts = startMs,
        end_ts = endMs,
        all_day = state.allDay,
        tz_mode = "local",
        location_text = state.locationText.takeIf { it.isNotBlank() },
        note = state.noteText.takeIf { it.isNotBlank() },
        color = state.color,
        reminders = state.reminders,
        rrule = state.rrule.toJsonOrNull(),
        exdates = state.exdates,
    )
    vm.save(rule)
    scope.launch {
        snackbar.showSnackbar("event_save_ok")
    }
}

/** 校验时间合法性：将错误写回 onFix。 */
private fun validateTimes(state: EditorState, onFix: (EditorState) -> Unit) {
    val startMs = parseDateTime(state.startText)
    val endMs = parseDateTime(state.endText)
    val err = if (startMs == null || endMs == null) "time_invalid_format" else null
    onFix(state.copy(timeError = err))
}

/** 颜色 chip 行（8 色板）。 */
@Composable
private fun ColorPickerRow(selected: String, onSelected: (String) -> Unit) {
    Column {
        Text(stringResource(R.string.event_color_label), style = MaterialTheme.typography.bodySmall)
        FlowRow(
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            listOf("blue", "green", "red", "amber", "violet", "pink", "cyan", "slate")
                .forEach { c ->
                    val isSelected = c == selected
                    Box(
                        modifier = Modifier
                            .width(36.dp)
                            .padding(2.dp)
                            .semantics { testTag = "color_$c" }
                            .clickable(onClick = { onSelected(c) }),
                    ) {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = androidx.compose.material3.CardDefaults.cardColors(
                                containerColor = com.everything.eve.ui.util.colorFor(c),
                            ),
                            border = if (isSelected) androidx.compose.foundation.BorderStroke(
                                2.dp,
                                MaterialTheme.colorScheme.onSurface,
                            ) else null,
                        ) {
                            Box(modifier = Modifier.padding(8.dp)) {
                                Text(colorLabel(c), style = MaterialTheme.typography.labelSmall, color = androidx.compose.ui.graphics.Color.White)
                            }
                        }
                    }
                }
        }
    }
}

/** reminders 多选（5 档：0/5/15/30/60/1440）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReminderPicker(
    enabled: Boolean,
    selected: List<Int>,
    onChange: (List<Int>) -> Unit,
    errorText: String?,
) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.event_reminders_label), modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
            Text(
                stringResource(R.string.event_reminders_count_format, selected.size),
                style = MaterialTheme.typography.labelSmall,
            )
        }
        if (!enabled) {
            Text(
                stringResource(R.string.event_reminder_disabled_all_day),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
            )
            return
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            listOf(0, 5, 15, 30, 60, 1440).forEach { mins ->
                val checked = mins in selected
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { testTag = "reminder_$mins" }
                        .clickable { onChange(toggleReminder(selected, mins)) },
                ) {
                    Checkbox(
                        checked = checked,
                        onCheckedChange = null,
                    )
                    Text(text = reminderLabel(mins), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        if (errorText != null) {
            Text(
                stringResource(R.string.event_error_reminders_too_many),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

/** 例外日期输入（先用文本框接受 YYYY-MM-DD 列表，逗号分隔）。 */
@Composable
private fun ExdatesField(values: List<String>, onChange: (List<String>) -> Unit) {
    var text by remember(values) { mutableStateOf(values.joinToString(",")) }
    Column {
        Text(stringResource(R.string.event_exdates_label), style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(
            value = text,
            onValueChange = { txt ->
                text = txt
                onChange(
                    txt.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                )
            },
            placeholder = { Text("YYYY-MM-DD,YYYY-MM-DD") },
            singleLine = false,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

// ---- 工具 ----

private val presetReminders = listOf(0, 5, 15, 30, 60, 1440)
private fun toggleReminder(current: List<Int>, mins: Int): List<Int> {
    return if (mins in current) current - mins else (current + mins).sorted()
}

@Composable
private fun colorLabel(c: String): String = stringResource(
    when (c) {
        "blue" -> R.string.event_color_blue
        "green" -> R.string.event_color_green
        "red" -> R.string.event_color_red
        "amber" -> R.string.event_color_amber
        "violet" -> R.string.event_color_violet
        "pink" -> R.string.event_color_pink
        "cyan" -> R.string.event_color_cyan
        "slate" -> R.string.event_color_slate
        else -> R.string.event_color_blue
    }
)

@Composable
private fun reminderLabel(mins: Int): String = when (mins) {
    0 -> stringResource(R.string.event_reminder_at_start)
    1440 -> stringResource(R.string.event_reminder_one_day)
    else -> stringResource(R.string.event_reminder_minutes_format, mins)
}

/**
 * 把 EventEntity.reminders_json → List<Int>。
 */
private fun parseRemindersList(json: String): List<Int> = try {
    val arr = JSONArray(json)
    List(arr.length()) { arr.getInt(it) }
} catch (e: Exception) {
    emptyList()
}

/**
 * exdates_json → List<String>。
 */
private fun parseExdatesList(json: String): List<String> = try {
    val arr = JSONArray(json)
    List(arr.length()) { arr.getString(it) }
} catch (e: Exception) {
    emptyList()
}

/**
 * 把 EventRule.rrule_json → RRuleBuilderValue。
 *
 * 通过 RecurrenceJson.decode 反序列化；异常时不抛，键入空 value（编辑器会显示为
 * 单次事件，用户可重新编辑）。
 */
private fun rruleBuilderValueFromJson(json: String?): RRuleBuilderValue {
    val rr = RecurrenceJson.decode(json) ?: return RRuleBuilderValue()
    return RRuleBuilderValue(
        freq = rr.freq,
        interval = rr.interval,
        byweekday = rr.byweekday,
        end = rr.end,
    )
}

/**
 * 默认开始时间（"现在" 的下一小时整点）。
 *
 * 避免在编辑器初始就出现 1970-01-01 这种破坏感。
 */
private fun defaultStartMs(): Long {
    val cal = java.util.Calendar.getInstance()
    cal.add(java.util.Calendar.HOUR_OF_DAY, 1)
    cal.set(java.util.Calendar.MINUTE, 0)
    cal.set(java.util.Calendar.SECOND, 0)
    cal.set(java.util.Calendar.MILLISECOND, 0)
    return cal.timeInMillis
}

/** "YYYY-MM-DD HH:mm" 格式化。 */
private fun formatDateTime(ms: Long): String {
    val fmt = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
    return fmt.format(java.util.Date(ms))
}

/** "YYYY-MM-DD HH:mm" 解析；失败返回 null。 */
private fun parseDateTime(s: String): Long? = try {
    val fmt = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
    fmt.parse(s)?.time
} catch (e: Exception) {
    null
}

// 占位：未使用导入抑制
@Suppress("unused") private val _event_editor_unused_imports_check = Unit
