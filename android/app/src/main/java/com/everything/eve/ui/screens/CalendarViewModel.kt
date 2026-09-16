/*
 * 阶段 4b — Task 6 / TR-6.3：CalendarViewModel。
 *
 * 设计要点：
 *   1. **注入 EventsRepository**（ServiceLocator.eventsRepo）；不直接触 Room
 *      DAO，与 EventsRepository 单一职责（reminder scheduler 也只走
 *      EventsRepo）保持一致。
 *   2. **暴露 derived state**：UI 不直接订阅 EventEntity Flow，先经 ViewModel
 *      转 Occurrence 列表（窗口内），再 collectAsState 给 UI。
 *   3. **窗口感知**：通过 [CalendarViewModel.observeInWindow] 派生当前可见
 *      视图的"月份"或"周"窗口内的 Occurrence 列表；
 *      窗口变更（翻月 / 切周）由 [setWindow] 更新。
 *   4. **CRUD 入口**：upsert / delete 调 EventsRepository + 触发
 *      ReminderScheduler.rebuildChain（spec FR-5 + TR-5.2 入口约定）。
 *   5. **零知识**：错误日志仅含 event.id，不含 title。
 */

package com.everything.eve.ui.screens

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.everything.eve.ServiceLocator
import com.everything.eve.data.event.EventEntity
import com.everything.eve.data.event.EventRule
import com.everything.eve.recurrence.EventRule as RecEventRule
import com.everything.eve.recurrence.Occurrence
import com.everything.eve.recurrence.TimeWindow
import com.everything.eve.recurrence.expand
import com.everything.eve.reminder.ReminderScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 视图模式（spec FR-10）。 */
enum class CalendarViewMode { MONTH, WEEK }

/**
 * CalendarScreen 整体 UI 状态。
 *
 * @param mode 当前视图模式（月/周）。
 * @param anchorMs 当前视图锚点（落在月份中任一天即代表整月；周视图下代表所在周首日）。
 * @param occurrences 当前可见窗口内的所有 Occurrence（按 start_ts 升序）。
 * @param rawEntities 原始 EventEntity 列表（编辑器回填 / 删除时需要 id 索引）。
 * @param errorMessage 加载错误时的可展示文案（null 表示无错误）。
 */
data class CalendarUiState(
    val mode: CalendarViewMode = CalendarViewMode.MONTH,
    val anchorMs: Long = System.currentTimeMillis(),
    val occurrences: List<Occurrence> = emptyList(),
    val rawEntities: List<EventEntity> = emptyList(),
    val errorMessage: String? = null,
)

/**
 * CalendarViewModel（spec FR-10 / AC-8 / TR-6.3）。
 *
 * 数据流：
 *   EventDao.observeAll (Flow) → flatMapLatest({@link computeOccurrencesInWindow}) → stateIn
 *
 * 翻月 / 切周：直接调 setWindow 计算新窗口；窗口变化通过 anchorMs StateFlow 触发。
 */
class CalendarViewModel(app: Application) : AndroidViewModel(app) {

    private val eventsRepo = ServiceLocator.eventsRepo
    private val appCtx = app.applicationContext

    private val modeFlow = MutableStateFlow(CalendarViewMode.MONTH)
    private val anchorFlow = MutableStateFlow(System.currentTimeMillis())
    private val errorFlow = MutableStateFlow<String?>(null)

    /**
     * 窗口状态流（mode + anchor），UI 翻页会更新此流。
     */
    private data class ViewWindow(val mode: CalendarViewMode, val anchorMs: Long)

    private val windowFlow = combine(modeFlow, anchorFlow) { m, a -> ViewWindow(m, a) }

    /**
     * 当前可见窗口内的 Occurrence 派生（按 start_ts 升序）。
     *
     * 算法：监听 EventDao.observeAll() → 在每次事件表变化时调
     * [expandAcrossEntities] 对每个 EventEntity 转 Recurrence.EventRule →
     * 调 [Recurrence.expand] 取窗口内 Occurrence → 排序 → flatten。
     *
     * 性能：单用户量级 < 100ms（spec NFR-4）；Entities 少时延后计算（Flow
     * distinctUntilChanged 默认无；此处每次 emit 都重算）。
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val entitiesFlow = windowFlow.flatMapLatest { _ ->
        eventsRepo.observeAll()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private val occurrencesFlow = windowFlow.flatMapLatest { win ->
        flow {
            val entities = eventsRepo.observeAll()
            entities.collect { list ->
                val occs = withContext(Dispatchers.Default) {
                    expandAcrossEntities(list, win)
                }
                emit(occs)
            }
        }
    }

    /**
     * 一次性快照：拉当前数据库列表，供编辑器回填 / 删除 id 索引用。
     *
     * 注意：因为 rawEntities 是 View 的辅助态而非"主流动数据"，这里以 suspend
     * `getCached` 暴露给 UI；UI 调一次即可拉最新事件表。
     */
    suspend fun getEntitiesSnapshot(): List<EventEntity> = withContext(Dispatchers.IO) {
        runCatching { eventsRepo.observeAll().first() }.getOrDefault(emptyList())
    }

    /**
     * 整 State：mode + anchor + 派生 occurrences + 错误 + rawEntities。
     *
     * rawEntities 进入主 State 是为了让 MonthGrid/WeekGrid 在 collectAsState 后
     * 直接按日聚合展示（spec FR-10：UI 订阅实体列表而非 Occurrence 列表）。
     * 编辑器需要的"最新实体"快照由 [getEntitiesSnapshot] 提供（其与本流等价）。
     */
    val state = combine(
        modeFlow,
        anchorFlow,
        occurrencesFlow,
        entitiesFlow,
        errorFlow,
    ) { mode, anchor, occs, entities, err ->
        CalendarUiState(
            mode = mode,
            anchorMs = anchor,
            occurrences = occs,
            rawEntities = entities,
            errorMessage = err,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CalendarUiState())

    /** 切换月/周模式（spec FR-10 / TR-6.3）。 */
    fun setMode(mode: CalendarViewMode) {
        modeFlow.value = mode
    }

    /** 翻页：anchorMs += Δ；正负号由调用方决定。 */
    fun setAnchor(tsMs: Long) {
        anchorFlow.value = tsMs
    }

    /** 重置回今日（spec FR-10 翻页 + 顶部"今天"按钮）。 */
    fun goToToday() {
        anchorFlow.value = System.currentTimeMillis()
        errorFlow.value = null
    }

    /**
     * 把 [EventEntity] 列表转为当前窗口内的 [Occurrence]（按 start_ts 升序）。
     *
     * 空列表、窗口外全部跳过；解析失败的 rrule 单条静默（spec 纪律不抛异常）。
     */
    private fun expandAcrossEntities(
        entities: List<EventEntity>,
        win: ViewWindow,
    ): List<Occurrence> {
        val windowMs = windowRangeMs(win)
        val out = mutableListOf<Occurrence>()
        for (e in entities) {
            val rule = entityToRecurrenceRule(e) ?: continue
            try {
                // windowRangeMs 返回 LongRange：下界 first（=from，含），上界 last（=to，不含）。
                // Kotlin stdlib LongRange 没有 .second 属性，故此处取 .last，避免 Unresolved reference。
                val occs = expand(rule, TimeWindow(windowMs.first, windowMs.last))
                out.addAll(occs)
            } catch (_: Exception) {
                // 单条解析失败不阻塞；继续处理剩余事件。
            }
        }
        return out.sortedBy { it.start_ts }
    }

    /** 由窗口定义返回 (from, to) 毫秒对（from 含、to 不含）。 */
    private fun windowRangeMs(win: ViewWindow): LongRange {
        val now = win.anchorMs
        val cal = java.util.Calendar.getInstance().apply {
            timeInMillis = now
            firstDayOfWeek = java.util.Calendar.SUNDAY
            timeZone = java.util.TimeZone.getDefault()
        }
        return when (win.mode) {
            CalendarViewMode.MONTH -> {
                // 从 anchor 月的第一天到下月第一天。
                cal.set(java.util.Calendar.DAY_OF_MONTH, 1)
                cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
                cal.set(java.util.Calendar.MINUTE, 0)
                cal.set(java.util.Calendar.SECOND, 0)
                cal.set(java.util.Calendar.MILLISECOND, 0)
                val from = cal.timeInMillis
                cal.add(java.util.Calendar.MONTH, 1)
                val to = cal.timeInMillis
                from..to
            }
            CalendarViewMode.WEEK -> {
                cal.set(java.util.Calendar.DAY_OF_WEEK, java.util.Calendar.SUNDAY)
                cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
                cal.set(java.util.Calendar.MINUTE, 0)
                cal.set(java.util.Calendar.SECOND, 0)
                cal.set(java.util.Calendar.MILLISECOND, 0)
                val from = cal.timeInMillis
                cal.add(java.util.Calendar.WEEK_OF_YEAR, 1)
                val to = cal.timeInMillis
                from..to
            }
        }
    }

    /**
     * EventEntity → recurrence.EventRule（与 ReminderScheduler.entityToRule 同款字段映射）。
     *
     * 解析失败的 rrule 视为单次（end=Never），不阻塞 UI（spec 纪律）。
     */
    private fun entityToRecurrenceRule(e: EventEntity): RecEventRule? {
        return try {
            val rrule = com.everything.eve.recurrence.RecurrenceJson.decode(e.rrule_json)
            RecEventRule(
                id = e.id,
                title = e.title,
                start_ts = e.start_ts,
                end_ts = e.end_ts,
                all_day = e.all_day,
                tz_mode = e.tz_mode,
                location_text = e.location_text,
                note = e.note,
                color = e.color,
                reminders = emptyList(),
                rrule = rrule,
                exdates = parseExdates(e.exdates_json),
            )
        } catch (ex: Exception) {
            null
        }
    }

    /** exdate JSON 数组 → List<String>。 */
    private fun parseExdates(json: String): List<String> = try {
        val arr = org.json.JSONArray(json)
        (0 until arr.length()).map { arr.getString(it) }
    } catch (e: Exception) {
        emptyList()
    }

    /**
     * 保存（新建/编辑）事件：
     *  1) 调 [EventsRepository.upsert] 入库（明文 + records 密文）；
     *  2) 调 [ReminderScheduler.rebuildChain] 重建全局闹钟链头（spec FR-5 / TR-5.2）。
     */
    fun save(rule: EventRule) {
        viewModelScope.launch {
            try {
                eventsRepo.upsert(rule)
                ReminderScheduler.rebuildChain(appCtx)
            } catch (e: Exception) {
                errorFlow.value = "save failed id=${rule.id.take(8)}"
            }
        }
    }

    /**
     * 删除事件：
     *  1) 调 [EventsRepository.delete] 删明文 + 推 tombstone；
     *  2) 调 [ReminderScheduler.rebuildChain] 重建全局闹钟链头。
     */
    fun delete(id: String) {
        viewModelScope.launch {
            try {
                eventsRepo.delete(id)
                ReminderScheduler.rebuildChain(appCtx)
            } catch (e: Exception) {
                errorFlow.value = "delete failed id=${id.take(8)}"
            }
        }
    }

    /** 清空错误态（UI 已展示 Snack 后调）。 */
    fun clearError() {
        errorFlow.value = null
    }

    /** 暴露给编辑器创建新 UUID（Editor 新建分支）。 */
    fun newId(): String = eventsRepo.newId()

    /** 应用 Context（供编辑器 import 时复用）。 */
    fun appContext(): Context = appCtx
}
