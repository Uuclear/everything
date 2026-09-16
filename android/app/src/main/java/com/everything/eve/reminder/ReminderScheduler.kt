/*
 * 阶段 4b — 提醒链式调度器（tasks.md Task 5 / TR-5.1 + TR-5.2）。
 *
 * 设计要点（spec FR-5 / FR-6 / NFR-3 / NFR-4）：
 *  1) **单闹钟链式**：全局仅一个 PendingIntent 闹钟（requestCode = 0x45564556，
 *     "EVEEV" hex）；触发后由 ReminderReceiver 渲染通知并重算下一触发点 → 调
 *     scheduleNext 重注册。规避国产 ROM AlarmManager 配额与滥用检测。
 *  2) **nextTrigger 纯函数**：不依赖 Android Framework / 反射；输入 (rule,
 *     reminders, now)，输出未来最近触发时刻或 null。单次/重复两种语义一致
 *     （对每个 Occurrence 计算 reminders 内最早提醒）。
 *  3) **窗口约束**：LOOKAHEAD_MS = 14 天；超过窗口无候选 → null。覆盖典型
 *     "下两周"用户场景；rebuildChain 周期 15 分钟时即便偶发漏算也只会
 *     推迟到下一窗口。
 *  4) **权限分支**：
 *     - API 31+ 优先 `setExactAndAllowWhileIdle(RTC_WAKEUP)`；
 *     - API 33+ 持有 USE_EXACT_ALARM 自动授权；API 31+ 检测
 *       `canScheduleExactAlarms()=false` 时降级为 `setAndAllowWhileIdle`
 *       并写一条 event_reminder_log kind=alarm_killed；
 *     - 老版本 API ≤30 不需 SCHEDULE_EXACT_ALARM 默认允许精确闹钟。
 *  5) **零知识红线**：通知文案仅渲染 title + "N 分钟后开始"类抽象；不渲染
 *     start_ts 数值、note 原文。log 仅存 event_id + occurrence_ts + kind。
 *
 * 设计权衡：
 *  - `AlarmScheduler` 接口抽离 AlarmManager 操作（生产实现 + 测试 Fake）；
 *    这样 TR-5.6 测试不需要 Robolectric 即可验证调用顺序（spec NFR-3 + 任务
 *    纪律允许 Robolectric fallback）。
 *  - `ReminderScheduler` 主体单例（object）便于 BootReceiver / ReminderReceiver
 *    / 编辑器保存路径直接调用；状态全部走 ServiceLocator（避免双重实例）。
 */

package com.everything.eve.reminder

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.everything.eve.ServiceLocator
import com.everything.eve.data.event.EventReminderLogEntity
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import java.util.concurrent.TimeUnit

// =============================================================================
// 常量
// =============================================================================

/**
 * 全局 alarm PendingIntent requestCode（"EVEEV" hex）。
 *
 * 单一闹钟链式调度约束（spec NFR-4）：所有事件共用一个 requestCode，
 * 任意时刻仅一个 PendingIntent 处于激活态；rebuildChain 用 PendingIntent.FLAG_UPDATE_CURRENT
 * 覆盖，避免累积。
 */
const val REMINDER_REQUEST_CODE: Int = 0x45564556

/**
 * Intent extras key（ReminderReceiver 拉取事件 + 记录 occurrence_ts 用）。
 */
const val EXTRA_EVENT_ID: String = "com.everything.eve.reminder.EVENT_ID"
const val EXTRA_OCCURRENCE_TS: String = "com.everything.eve.reminder.OCCURRENCE_TS"

/**
 * 通知 channel id（spec FR-5；4a 既有 POST_NOTIFICATIONS 权限复用）。
 *
 * 仅一份 "events" channel；NotificationChannel 在 API 26+ 创建（minSdk=26
 * 兜底）。channelId 由 Android 系统维护，无需应用每次启动重建。
 */
const val REMINDER_CHANNEL_ID: String = "events"

/**
 * 提醒提前分钟 → 毫秒 的换算单位（spec FR-1 reminders 字段定义）。
 */
private const val MINUTE_MS: Long = 60_000L

/**
 * 重复事件 nextTrigger 计算窗口（14 天）。
 *
 * 选 14 天的理由：
 *  - 单用户量级假设千级事件以下（spec Assumptions）；
 *  - rebuildChain 周期 15 分钟 → 14 天窗口覆盖 100+ 个周期；
 *  - 足够覆盖典型"未来两周内"用户提醒需求；窗口外提醒由下一次 rebuildChain
 *    补齐（rebuildChain 自身会重算全局最小触发点）。
 */
val LOOKAHEAD_MS: Long = TimeUnit.DAYS.toMillis(14)

/**
 * Reminder 字段最小值（=0 = 事件开始时刻本身；负值无意义）。
 *
 * 任务纪律 + spec FR-1：reminders 数组的每个值应 ≥0；负值视为非法输入
 * （nextTrigger 直接忽略，不参与 min 计算），调用方负责表单校验。
 */
private const val REMINDER_MIN_VALUE: Int = 0

// =============================================================================
// AlarmScheduler 接口（生产 + 测试双实现）
// =============================================================================

/**
 * AlarmManager 操作抽象（仅 ReminderScheduler 内部使用）。
 *
 * 生产实现 [RealAlarmScheduler] 调 AlarmManager；测试 Fake 由
 * ReminderSchedulerTest 提供，验证 scheduleNext/rebuildChain 调用顺序与参数。
 *
 * 设计：抽离的目的是让 nextTrigger 之外的 schedule 逻辑可在纯 JUnit 4
 * 测试中跑（避免 Robolectric 依赖；spec NFR-3 允许"纯函数由 JVM 单测覆盖"）。
 */
internal interface AlarmScheduler {
    /**
     * 调度一次精确闹钟（API 31+ 需 SCHEDULE_EXACT_ALARM）。
     *
     * @return 是否成功注册到精确闹钟；false 表示降级为非精确闹钟或失败。
     */
    fun scheduleExact(triggerAtMillis: Long, pi: PendingIntent): Boolean

    /**
     * 调度一次非精确闹钟（API 31+ 无 SCHEDULE_EXACT_ALARM 时降级）。
     *
     * 精度约 15 分钟（Doze / 系统配额下可能更晚），但保证送达。
     */
    fun scheduleInexact(triggerAtMillis: Long, pi: PendingIntent)

    /** 取消闹钟（reminders 清空或事件全删时调用，避免悬挂 PendingIntent）。 */
    fun cancel(pi: PendingIntent)

    /** 当前是否被授权精确闹钟（API 31+ 检测；API ≤30 永远 true）。 */
    fun canScheduleExact(): Boolean
}

/**
 * 生产实现：调 Android AlarmManager + PendingIntent 指向 ReminderReceiver。
 */
internal class RealAlarmScheduler(
    private val context: Context,
) : AlarmScheduler {

    private val alarmManager: AlarmManager =
        context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    override fun scheduleExact(triggerAtMillis: Long, pi: PendingIntent): Boolean {
        // API 31+（Android 12+）：SCHEDULE_EXACT_ALARM 权限被拒时不能调精确闹钟。
        // 检测失败时返回 false，由上层降级为 scheduleInexact + 写日志。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (!alarmManager.canScheduleExactAlarms()) return false
        }
        return try {
            // RTC_WAKEUP：使用设备 RTC 时钟 + 唤醒 CPU；spec FR-5 要求"到点唤醒"。
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                triggerAtMillis,
                pi,
            )
            true
        } catch (e: SecurityException) {
            // 部分国产 ROM 在 SCHEDULE_EXACT_ALARM 被关时即便声明权限也抛
            // SecurityException；走降级路径。
            false
        }
    }

    override fun scheduleInexact(triggerAtMillis: Long, pi: PendingIntent) {
        // 非精确闹钟：精度约 15 分钟；用于降级场景（spec FR-6）。
        alarmManager.setAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            triggerAtMillis,
            pi,
        )
    }

    override fun cancel(pi: PendingIntent) {
        alarmManager.cancel(pi)
    }

    override fun canScheduleExact(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            alarmManager.canScheduleExactAlarms()
        } else {
            // API ≤30 永远精确闹钟可用（系统不支持 SCHEDULE_EXACT_ALARM 概念）。
            true
        }
    }
}

// =============================================================================
// 主入口（ReminderScheduler 单例）
// =============================================================================

/**
 * 链式闹钟调度器（全局单例）。
 *
 * 入口约定：
 *  - `nextTrigger(rule, reminders, now)`：纯函数；测试核心。
 *  - `scheduleNext(triggerAtMs, eventId, occurrenceTs, ctx)`：注册全局单闹钟；
 *    `eventId` 与 `occurrenceTs` 写入 extras 供 ReminderReceiver 拉数据。
 *  - `rebuildChain(ctx)`：遍历所有事件取全局最小 nextTrigger → 调 scheduleNext。
 *
 * 调用入口（任务清单）：
 *  - 编辑器保存事件后（Task 6 / T10）；
 *  - 应用冷启动进入主界面（Task 9）；
 *  - BootReceiver 触发时（设备开机/重启）—— TR-5.4；
 *  - ReminderReceiver 收到闹钟后（链式下一触发）—— TR-5.3。
 */
object ReminderScheduler {

    /**
     * 计算规则 + reminders 在 `now` 之后的最近触发时刻（纯函数；不依赖 Android Framework）。
     *
     * 算法骨架（spec FR-5 / AC-5）：
     *  1) **单次事件（rrule=null 或 reminders 空）**：
     *     - reminders 为空 → null（spec FR-5 "无 reminders 不触发"）；
     *     - reminders 含 0 → 若 start_ts > now 则返回 start_ts；否则忽略；
     *     - reminders 含 r → 返回 min(start_ts - r * 60_000)（过滤 ≤ now）；
     *     - 若所有候选 ≤ now → null。
     *  2) **重复事件（rrule 非空）**：
     *     - 调 `Recurrence.expand(rule, TimeWindow(now, now + LOOKAHEAD_MS))`
     *       取窗口内所有 Occurrence；
     *     - 对每个 Occurrence 计算 reminders 内的最早提醒（同一逻辑），
     *       取全局最小；
     *     - 若窗口内无 Occurrence 或所有候选 ≤ now → null。
     *  3) **reminders[0] 边界**：负值视为非法输入忽略；≥0 才参与。
     *
     * 单位：now 与返回值均为 Unix 毫秒（与 EventRule.start_ts 同口径）。
     *
     * @param rule 事件规则（含 rrule 字段；rrule=null 表示单次）。
     * @param reminders 提前分钟数组；0 = 事件开始时刻；负值忽略。
     * @param now 当前本地 Unix 毫秒（> now 的触发才返回）。
     * @return 最近触发时刻；未来无触发返回 null。
     */
    fun nextTrigger(
        rule: com.everything.eve.recurrence.EventRule,
        reminders: IntArray,
        now: Long,
    ): Long? {
        if (reminders.isEmpty()) return null
        // reminders 已按升序排（任务描述："min(start_ts - reminders[i]*60_000)"）
        // —— 排序后取首项即全局最小。但为鲁棒性显式算一次 min。

        // 候选触发时刻集合：先计算 reminders 内每个合法值的偏移 → 过滤 > now。
        fun earliestFor(startTs: Long): Long? {
            var minTs: Long? = null
            for (r in reminders) {
                if (r < REMINDER_MIN_VALUE) continue
                val ts = startTs - r * MINUTE_MS
                if (ts <= now) continue
                if (minTs == null || ts < minTs) minTs = ts
            }
            return minTs
        }

        // 单次事件：直接对 start_ts 算一次 earliestFor。
        if (rule.rrule == null) {
            return earliestFor(rule.start_ts)
        }

        // 重复事件：扩窗口内所有 Occurrence，对每个算一次 earliestFor，取最小。
        val window = com.everything.eve.recurrence.TimeWindow(now, now + LOOKAHEAD_MS)
        val occurrences = com.everything.eve.recurrence.expand(rule, window)
        var globalMin: Long? = null
        for (occ in occurrences) {
            val ts = earliestFor(occ.start_ts) ?: continue
            if (globalMin == null || ts < globalMin) globalMin = ts
        }
        return globalMin
    }

    /**
     * 注册全局单闹钟（spec FR-5 / TR-5.2）。
     *
     * 调用方：
     *  - `rebuildChain`：算完全局最小 nextTrigger 后调一次；
     *  - `ReminderReceiver.onReceive`：弹完通知后算下一触发点调一次。
     *
     * 权限分支（spec FR-6）：
     *  - 优先 `setExactAndAllowWhileIdle(RTC_WAKEUP)`（API 31+ 需
     *    SCHEDULE_EXACT_ALARM / API 33+ USE_EXACT_ALARM 自动授权）；
     *  - 被拒时降级 `setAndAllowWhileIdle` 并写一条
     *    event_reminder_log kind=alarm_killed。
     *
     * @param triggerAtMs 触发时刻 Unix 毫秒（已 > now）。
     * @param eventId 事件 UUID；写入 extras 供 ReminderReceiver 拉数据。
     * @param occurrenceTs 对应 Occurrence 的 start_ts（写 log 用）。
     * @param ctx 应用 Context。
     */
    suspend fun scheduleNext(
        triggerAtMs: Long,
        eventId: String,
        occurrenceTs: Long,
        ctx: Context,
    ) {
        val alarmScheduler: AlarmScheduler = RealAlarmScheduler(ctx.applicationContext)
        val pi = buildPendingIntent(ctx.applicationContext, eventId, occurrenceTs)

        val exactOk = alarmScheduler.scheduleExact(triggerAtMs, pi)
        if (!exactOk) {
            // 精确闹钟被拒 → 降级 + 写日志（spec FR-6 / NFR-1 严守 event_id
            // + occurrence_ts + kind 三列；不写 title/note/start_ts 原文）。
            alarmScheduler.scheduleInexact(triggerAtMs, pi)
            try {
                ServiceLocator.db.eventReminderLogDao().insertRaw(
                    eventId = eventId,
                    occurrenceTs = occurrenceTs,
                    kind = "alarm_killed",
                    createdTs = System.currentTimeMillis(),
                )
            } catch (e: Exception) {
                // 日志写入失败不阻断调度（设置页展示降级 banner 是 best-effort）。
            }
        }
    }

    /**
     * 重建全局闹钟链头（spec FR-5 / AC-7 / TR-5.2）。
     *
     * 算法：
     *  1) 遍历 `ServiceLocator.eventsRepo.observeAll().first()` 取全表事件；
     *  2) 对每个事件调 `nextTrigger(rule, reminders, now)`；
     *  3) 取全局最小 nextTrigger → 调 `scheduleNext`；
     *  4) 全局无触发（未来无 reminder）→ 取消已有 PendingIntent，避免悬挂。
     *
     * 入口（任务描述）：
     *  - BootReceiver 触发时（设备开机/重启）—— TR-5.4；
     *  - 编辑器保存事件后（Task 6 / T10）；
     *  - 应用冷启动进入主界面（Task 9）；
     *  - ReminderReceiver 收到闹钟后（链式下一触发）—— TR-5.3。
     *
     * 性能：O(N)；单用户量级千级以下；4a SyncWorker 周期 15 分钟。
     * SPEC NFR-4 约束单用户量级 < 100ms（本实现 N 次纯函数计算 + 一次
     * AlarmManager.setExact，复杂度可控）。
     */
    suspend fun rebuildChain(ctx: Context) {
        val now = System.currentTimeMillis()
        val appCtx = ctx.applicationContext

        // 1) 拉全表事件（一次性快照；不订阅 Flow 避免常驻）。
        val events = try {
            ServiceLocator.eventsRepo.observeAll().first()
        } catch (e: Exception) {
            // ServiceLocator 未初始化（极早启动场景）或 Room 异常 → 静默退出。
            return
        }

        // 2) 全局最小 nextTrigger：eventId + occurrenceTs + triggerTs 三元组。
        var best: Triple<String, Long, Long>? = null
        for (entity in events) {
            val rule = entityToRule(entity) ?: continue
            val reminders = parseReminders(entity.reminders_json)
            if (reminders.isEmpty()) continue
            val ts = nextTrigger(rule, reminders, now) ?: continue
            if (best == null || ts < best.third) {
                best = Triple(rule.id, ts, ts)
                // 注：occurrenceTs == triggerTs 仅在 reminders=[0] 时；
                // 多 reminders 时 triggerTs = start_ts - r*60_000，对应 occurrence
                // 是触发"所从属"的 Occurrence（即 reminder 锚定的 start_ts）——
                // 重建时我们记 start_ts，ReminderReceiver 拉数据时再用 reminders 重算。
            }
        }

        val alarmScheduler = RealAlarmScheduler(appCtx)

        if (best == null) {
            // 3) 全局无触发 → 取消已有 PendingIntent，避免悬挂。
            val pi = buildPendingIntent(appCtx, eventId = "", occurrenceTs = 0L)
            alarmScheduler.cancel(pi)
            return
        }

        // 4) 调 scheduleNext 注册全局闹钟。
        scheduleNext(
            triggerAtMs = best.third,
            eventId = best.first,
            occurrenceTs = best.third,
            ctx = appCtx,
        )
    }

    /**
     * 把 EventEntity 反序列化为 Recurrence.EventRule（reminders_json → IntArray 转换）。
     *
     * 字段映射（与 EventEntity 字段一一对应）：
     *  - `reminders_json`：JSONArray 字符串；空即 `[]`；解析失败则视为空数组。
     *  - `rrule_json`：可 null；非 null 时为 JSON 对象字符串（freq/interval/byweekday/end）。
     *  - `exdates_json`：JSONArray 字符串；空即 `[]`。
     *
     * 设计：JSON 解析用 org.json（与 EventsRepository 同款，避免引入新依赖）。
     * 解析失败视为字段缺失并兜底默认值——不抛异常以避免 rebuildChain 阻塞
     * BootReceiver。
     */
    private fun entityToRule(entity: com.everything.eve.data.event.EventEntity):
        com.everything.eve.recurrence.EventRule? {
        return try {
            val rruleStr = entity.rrule_json
            val rrule: com.everything.eve.recurrence.RRule? = if (rruleStr.isNullOrEmpty()) {
                null
            } else {
                parseRRule(rruleStr)
            }
            val exdates = parseExdates(entity.exdates_json)
            com.everything.eve.recurrence.EventRule(
                id = entity.id,
                title = entity.title,
                start_ts = entity.start_ts,
                end_ts = entity.end_ts,
                all_day = entity.all_day,
                tz_mode = entity.tz_mode,
                location_text = entity.location_text,
                note = entity.note,
                color = entity.color,
                reminders = parseRemindersList(entity.reminders_json),
                rrule = rrule,
                exdates = exdates,
            )
        } catch (e: Exception) {
            null
        }
    }

    /** 把 reminders_json（JSONArray 字符串）解析为 IntArray。 */
    internal fun parseReminders(json: String): IntArray {
        return try {
            val arr = JSONArray(json)
            IntArray(arr.length()) { arr.getInt(it) }
        } catch (e: Exception) {
            IntArray(0)
        }
    }

    /** 同上但返回 List<Int>（Recurrence.EventRule.reminders 字段类型是 List<Int>）。 */
    private fun parseRemindersList(json: String): List<Int> =
        parseReminders(json).toList()

    /** 把 rrule_json（JSONObject 字符串）解析为 RRule。 */
    private fun parseRRule(json: String): com.everything.eve.recurrence.RRule? {
        return try {
            val o = org.json.JSONObject(json)
            val freqStr = o.optString("freq")
            val freq = com.everything.eve.recurrence.Frequency.entries.firstOrNull {
                it.name == freqStr
            } ?: return null
            val interval = o.optInt("interval", 1).coerceAtLeast(1)
            val byweekdayArr = o.optJSONArray("byweekday")
            val byweekday: List<com.everything.eve.recurrence.Weekday> = if (byweekdayArr != null) {
                (0 until byweekdayArr.length()).mapNotNull { i ->
                    com.everything.eve.recurrence.Weekday.entries.firstOrNull {
                        it.name == byweekdayArr.getString(i)
                    }
                }
            } else emptyList()
            val endObj = o.optJSONObject("end") ?: return null
            val kindStr = endObj.optString("kind")
            val end: com.everything.eve.recurrence.RRuleEnd = when (kindStr) {
                "never" -> com.everything.eve.recurrence.RRuleEnd.Never
                "date" -> com.everything.eve.recurrence.RRuleEnd.Date(endObj.optString("until"))
                "count" -> com.everything.eve.recurrence.RRuleEnd.Count(endObj.optInt("count"))
                else -> return null
            }
            com.everything.eve.recurrence.RRule(freq, interval, byweekday, end)
        } catch (e: Exception) {
            null
        }
    }

    /** 把 exdates_json（JSONArray 字符串）解析为 List<String>。 */
    private fun parseExdates(json: String): List<String> {
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).map { arr.getString(it) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * 构造指向 ReminderReceiver 的 PendingIntent（全局单闹钟 requestCode）。
     *
     * 设计要点：
     *  - requestCode 固定 REMINDER_REQUEST_CODE（"EVEEV" hex）→ 任意时刻单闹钟；
     *  - FLAG_UPDATE_CURRENT：rebuildChain 重复调用覆盖前一个 PendingIntent；
     *  - FLAG_IMMUTABLE：API 31+ 强制（PendingIntent 必须显式 immutable）。
     */
    internal fun buildPendingIntent(
        ctx: Context,
        eventId: String,
        occurrenceTs: Long,
    ): PendingIntent {
        val intent = Intent(ctx, ReminderReceiver::class.java).apply {
            action = "com.everything.eve.reminder.FIRE"
            putExtra(EXTRA_EVENT_ID, eventId)
            putExtra(EXTRA_OCCURRENCE_TS, occurrenceTs)
        }
        var flags = PendingIntent.FLAG_UPDATE_CURRENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            flags = flags or PendingIntent.FLAG_IMMUTABLE
        }
        return PendingIntent.getBroadcast(ctx, REMINDER_REQUEST_CODE, intent, flags)
    }
}
