/*
 * 阶段 4b + 阶段 5 — 提醒链式调度器（tasks.md Task 5 / Task 6 / TR-5.1 + TR-5.2
 * + TR-6.1）。
 *
 * 设计要点（spec FR-5 / FR-6 / NFR-3 / NFR-4 / 阶段 5 FR-4）：
 *  1) **单闹钟链式**：全局仅一个 PendingIntent 闹钟（requestCode = 0x45564556，
 *     "EVEEV" hex）；触发后由 ReminderReceiver 渲染通知并重算下一触发点 → 调
 *     scheduleNext 重注册。规避国产 ROM AlarmManager 配额与滥用检测。
 *  2) **nextTrigger 纯函数**：不依赖 Android Framework / 反射；输入 (rule,
 *     reminders, now) 或 (card, now)，输出未来最近触发时刻或 null。单次/重复
 *     / 信用卡账单/还款两类语义一致。
 *  3) **两类触发合并（阶段 5 TR-6.1）**：rebuildChain 同时扫描 4b EventRule
 *     触发 + 5 FinanceCard 账单/还款触发，取全局最小 nextTrigger 写**单闹钟**；
 *     严禁新建第二条调度链路。
 *  4) **模块分支（阶段 5 TR-6.2）**：Intent extras 新增 `module` +
 *     `ref_kind` 两字段；ReminderReceiver 按 module 路由 event / finance，
 *     财务分支按 ref_kind 渲染不同抽象文案。
 *  5) **窗口约束**：LOOKAHEAD_MS = 14 天；超过窗口无候选 → null。覆盖典型
 *     "下两周"用户场景；rebuildChain 周期 15 分钟时即便偶发漏算也只会
 *     推迟到下一窗口。
 *  6) **权限分支**：
 *     - API 31+ 优先 `setExactAndAllowWhileIdle(RTC_WAKEUP)`；
 *     - API 33+ 持有 USE_EXACT_ALARM 自动授权；API 31+ 检测
 *       `canScheduleExactAlarms()=false` 时降级为 `setAndAllowWhileIdle`
 *       并写一条 event_reminder_log kind=alarm_killed；
 *     - 老版本 API ≤30 不需 SCHEDULE_EXACT_ALARM 默认允许精确闹钟。
 *  7) **零知识红线**：通知文案仅渲染 title + "N 分钟后开始"类抽象；不渲染
 *     start_ts 数值、note 原文；财务通知文案不含金额/卡号后四位/具体日期数字。
 *     log 仅存 event_id + occurrence_ts + kind；财务 log 仅存 ref_id +
 *     ref_kind + fire_at + delivered。
 *
 * 设计权衡：
 *  - `AlarmScheduler` 接口抽离 AlarmManager 操作（生产实现 + 测试 Fake）；
 *    这样 TR-5.6 / TR-6.4 测试不需要 Robolectric 即可验证调用顺序
 *    （spec NFR-3 + 任务纪律允许 Robolectric fallback）。
 *  - `ReminderScheduler` 主体单例（object）便于 BootReceiver / ReminderReceiver
 *    / 编辑器保存路径直接调用；状态全部走 ServiceLocator（避免双重实例）。
 *  - 阶段 5 财务卡片 nextCardFiring 逻辑：本类内置"种子版"纯函数实现（与
 *    spec §阶段 5 TR-5.2 nextCardFiring 行为一致），待 T5 落地抽出
 *    `com.everything.eve.finance.NextCardFiring.kt` 时**直接迁移调用即可**——
 *    行为字节级一致，不破坏下游。
 */

package com.everything.eve.reminder

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.everything.eve.ServiceLocator
import com.everything.eve.data.event.EventReminderLogEntity
import com.everything.eve.data.finance.dao.FinanceCardDao
import com.everything.eve.data.finance.entity.FinanceCardEntity
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import java.util.concurrent.TimeUnit

// =============================================================================
// 常量
// =============================================================================

/**
 * 全局 alarm PendingIntent requestCode（"EVEEV" hex）。
 *
 * 单一闹钟链式调度约束（spec NFR-4）：所有事件 + 财务卡片共用一个 requestCode，
 * 任意时刻仅一个 PendingIntent 处于激活态；rebuildChain 用 PendingIntent.FLAG_UPDATE_CURRENT
 * 覆盖，避免累积。**严禁**为财务模块新建第二条调度链路。
 */
const val REMINDER_REQUEST_CODE: Int = 0x45564556

/**
 * Intent extras key（ReminderReceiver 拉取事件 + 记录 occurrence_ts 用）。
 *
 * 阶段 5 TR-6.1 扩展：新增 `MODULE` / `REF_KIND` 两字段用于 module 分支路由；
 * 既有事件分支沿用 `EVENT_ID` + `OCCURRENCE_TS`，财务分支使用 `MODULE="finance"`
 * + `REF_KIND="card_statement_due"`（或 `card_payment_due`）+ `REF_ID=card.id`。
 */
const val EXTRA_EVENT_ID: String = "com.everything.eve.reminder.EVENT_ID"
const val EXTRA_OCCURRENCE_TS: String = "com.everything.eve.reminder.OCCURRENCE_TS"

/**
 * 阶段 5 TR-6.1 新增：模块标识 extras key。
 *
 * 取值：`"event"`（既有 4b）或 `"finance"`（5 T6）。
 * ReminderReceiver 按该字段路由 DAO 拉取 + 通知文案渲染。
 */
const val EXTRA_MODULE: String = "com.everything.eve.reminder.MODULE"

/**
 * 阶段 5 TR-6.1 新增：提醒种类 extras key（对应 finance_reminder_log.ref_kind）。
 *
 * v1 启用两值：`"card_statement_due"` / `"card_payment_due"`；
 * 既有 event 分支固定为空字符串（财务模块之前无该字段）。
 */
const val EXTRA_REF_KIND: String = "com.everything.eve.reminder.REF_KIND"

/**
 * 通知 channel id（spec FR-5；4a 既有 POST_NOTIFICATIONS 权限复用）。
 *
 * 阶段 5 TR-6.2 扩展：财务通知与日程通知共用 channel "events"，
 * 避免新增 channel 管理负担；
 * NotificationChannel 在 API 26+ 创建（minSdk=26 兜底）。
 * channelId 由 Android 系统维护，无需应用每次启动重建。
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

/**
 * 阶段 5 TR-6.1 财务账单日提前量（3 天 = 3 * 24h）。
 *
 * 取 3 天的理由：账单日是"消费账期截止"信号；提前 3 天给用户留出
 * 核对账单/规划还款的时间窗。账单日提醒发出后，用户有充足时间
 * 在还款日前准备资金。
 */
private const val STATEMENT_OFFSET_MS: Long = 3L * 24L * 60L * 60L * 1000L

/**
 * 阶段 5 TR-6.1 财务还款日提前量（1 天 = 24h）。
 *
 * 取 1 天的理由：还款日是"必须执行扣款"截止点；提前 1 天提醒
 * 用户确认资金到账，避免逾期影响征信。T-1 已在 4b 时段格式中
 * 测试过（T6 commit 配套）。
 */
private const val PAYMENT_OFFSET_MS: Long = 1L * 24L * 60L * 60L * 1000L

// =============================================================================
// AlarmScheduler 接口（生产 + 测试双实现）
// =============================================================================

/**
 * AlarmManager 操作抽象（仅 ReminderScheduler 内部使用）。
 *
 * 生产实现 [RealAlarmScheduler] 调 AlarmManager；测试 Fake 由
 * ReminderSchedulerTest / ReminderSchedulerFinanceTest 提供，验证
 * scheduleNext / rebuildChain 调用顺序与参数。
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
 *  - `nextTrigger(rule, reminders, now)`：纯函数；测试核心（事件分支）。
 *  - `nextCardFiring(card, now)`：纯函数；测试核心（阶段 5 财务分支）。
 *  - `scheduleNext(...)`：注册全局单闹钟（事件模块）；extras 携带 event_id +
 *    occurrence_ts。
 *  - `scheduleNextFinance(...)`：注册全局单闹钟（财务模块；阶段 5 TR-6.1）；
 *    extras 携带 module + ref_id + ref_kind + occurrence_ts。
 *  - `rebuildChain(ctx)`：遍历事件 + 财务卡片取全局最小 nextTrigger → 调
 *    scheduleNext**统一**注册。**严禁**新建第二条调度链路（spec NFR-4）。
 *
 * 调用入口（任务清单）：
 *  - 编辑器保存事件后（Task 6 / T10）；
 *  - 应用冷启动进入主界面（Task 9）；
 *  - BootReceiver 触发时（设备开机/重启）—— TR-5.4 / 阶段 5 沿用；
 *  - ReminderReceiver 收到闹钟后（链式下一触发）—— TR-5.3 / 阶段 5 TR-6.2。
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
     * 阶段 5 TR-6.1：计算信用卡卡片的最近触发时刻（纯函数；不依赖 Android Framework）。
     *
     * 算法（与 spec §阶段 5 TR-5.2 nextCardFiring 行为一致）：
     *  1) **跳过归档卡**（`card.archived == true`）→ 返回 null；
     *  2) **跳过非信用卡**（`card.kind != "credit"`）→ 返回 null；
     *  3) 取 `billingDay`（账单日 1-31）与 `dueDay`（还款日 1-31）：
     *     - 账单日触发：日期 = 本月（或下月）`billingDay` 当日 0:00 本地时间；
     *       提前 3 天（STATEMENT_OFFSET_MS）→ 触发时刻 = billingDay_ts - 3d；
     *     - 还款日触发：日期 = 本月（或下月）`dueDay` 当日 0:00 本地时间；
     *       提前 1 天（PAYMENT_OFFSET_MS）→ 触发时刻 = dueDay_ts - 1d；
     *     - 若账单日不存在（null）或不在 1-31 范围 → 跳过账单提醒；
     *     - 若还款日不存在（null）或不在 1-31 范围 → 跳过还款提醒；
     *  4) 若两者皆有 → 取全局最小（账单 vs 还款）；都 ≤ now → 推到下个周期
     *     （账单日走 billingDay 的下月实例；还款日走 dueDay 的下月实例）；
     *  5) **跨月滚动**：当月日期已过，下月实例的相对偏移（账单日 T-3 / 还款日 T-1）
     *     不变，仅基础日切换到下月同一日；
     *  6) **未来无触发**：若信用卡无账单日/还款日（缺省 null）则返回 null；
     *  7) **kind 边界**：只有 `kind == "credit"` 触发；`kind == "debit"`
     *     直接返回 null（避免借记卡账单日/还款日误触发）。
     *
     * @param card 信用卡实体（含 billingDay / dueDay，可能为 null）。
     * @param now 当前本地 Unix 毫秒。
     * @return 最近触发时刻；未来无触发返回 null。
     */
    fun nextCardFiring(
        card: FinanceCardEntity,
        now: Long,
    ): Long? {
        // 跳过归档卡（spec FR-4 归档过滤规则）
        if (card.archived) return null
        // 仅信用卡支持账单/还款提醒（kind == "credit"）；借记卡无账单日概念
        if (card.kind != CARD_KIND_CREDIT) return null

        // 用 JVM 默认时区构造日历日（与 4b Recurrence.expand 保持一致口径）
        val zone = java.time.ZoneId.systemDefault()

        // 计算账单日触发时刻（账单日 T-3）
        val statementTrigger: Long? = if (card.billingDay != null &&
            card.billingDay in BILLING_DAY_RANGE
        ) {
            computeMonthlyTriggerMs(
                dayOfMonth = card.billingDay,
                offsetMs = STATEMENT_OFFSET_MS,
                now = now,
                zone = zone,
            )
        } else {
            null
        }

        // 计算还款日触发时刻（还款日 T-1）
        val paymentTrigger: Long? = if (card.dueDay != null &&
            card.dueDay in BILLING_DAY_RANGE
        ) {
            computeMonthlyTriggerMs(
                dayOfMonth = card.dueDay!!,
                offsetMs = PAYMENT_OFFSET_MS,
                now = now,
                zone = zone,
            )
        } else {
            null
        }

        // 两个候选取全局最小
        val candidates = listOfNotNull(statementTrigger, paymentTrigger)
        if (candidates.isEmpty()) return null
        return candidates.min()
    }

    /**
     * 计算月度触发时刻（账单日 / 还款日的偏移提醒）。
     *
     * 算法：
     *  - 本月 `dayOfMonth` 当日 0:00 本地时间的 ts = baseMs；
     *  - 触发时刻 = baseMs - offsetMs（offsetMs = STATEMENT_OFFSET_MS 或
     *    PAYMENT_OFFSET_MS）；
     *  - 若触发时刻 ≤ now，则视作本月已过；回退计算下月同一日 0:00 → 减
     *    offsetMs；若下月同日仍 ≤ now（极端罕见：卡片新建在很靠近触发点的
     *    时间窗），按月推直到 > now。
     *
     * 注：本实现刻意保持纯函数形态（不依赖 Android Framework / Room），
     * 单元测试可直接在 JVM 跑（spec NFR-3 / TR-6.4）。
     *
     * @param dayOfMonth 1-31 的账单日或还款日。
     * @param offsetMs 提前量（账单 T-3 / 还款 T-1）。
     * @param now 当前本地 Unix 毫秒。
     * @param zone 时区（默认 systemDefault）。
     * @return 触发时刻 Unix 毫秒。
     */
    private fun computeMonthlyTriggerMs(
        dayOfMonth: Int,
        offsetMs: Long,
        now: Long,
        zone: java.time.ZoneId,
    ): Long {
        // 本月基础日
        var yearMonth: java.time.YearMonth = java.time.YearMonth.from(
            java.time.Instant.ofEpochMilli(now).atZone(zone),
        )
        // 账单/还款日若超过当月最大日数（28/29/30/31）→ 截到当月最后一天
        val effectiveDay = minOf(dayOfMonth, yearMonth.lengthOfMonth())
        var baseDate = yearMonth.atDay(effectiveDay)
        var trigger = baseDate.atStartOfDay(zone).toInstant().toEpochMilli() - offsetMs

        // 本月触发时刻已过 → 推到下月
        // 防御性 while：避免 setMonth 边界造成潜在死循环（理论上下月必然 > now）
        var safetyCounter = 0
        while (trigger <= now && safetyCounter < MAX_MONTH_LOOKAHEAD) {
            yearMonth = yearMonth.plusMonths(1)
            val nextEffectiveDay = minOf(dayOfMonth, yearMonth.lengthOfMonth())
            baseDate = yearMonth.atDay(nextEffectiveDay)
            trigger = baseDate.atStartOfDay(zone).toInstant().toEpochMilli() - offsetMs
            safetyCounter += 1
        }
        return trigger
    }

    /**
     * 注册全局单闹钟（事件模块；spec FR-5 / TR-5.2）。
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
        val pi = buildPendingIntent(
            ctx = ctx.applicationContext,
            module = MODULE_EVENT,
            refId = eventId,
            refKind = "",
            occurrenceTs = occurrenceTs,
        )

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
     * 阶段 5 TR-6.1：注册全局单闹钟（财务模块）。
     *
     * 调用方：`rebuildChain` 算完财务卡片全局最小触发点后调一次；
     * 与事件模块共用**同一** requestCode = REMINDER_REQUEST_CODE（spec NFR-4）
     * —— 严禁为财务新建第二条调度链路。
     *
     * 权限分支与 [scheduleNext] 一致（事件 / 财务共用 AlarmManager 状态）。
     *
     * @param triggerAtMs 触发时刻 Unix 毫秒（已 > now）。
     * @param refId 卡片 UUID；写入 extras 供 ReminderReceiver 拉数据。
     * @param refKind 提醒种类（`card_statement_due` / `card_payment_due`）；
     *   v1 仅启用这两类。
     * @param fireAt 计划触发时刻（写 finance_reminder_log 用）。
     * @param ctx 应用 Context。
     */
    suspend fun scheduleNextFinance(
        triggerAtMs: Long,
        refId: String,
        refKind: String,
        fireAt: Long,
        ctx: Context,
    ) {
        val alarmScheduler: AlarmScheduler = RealAlarmScheduler(ctx.applicationContext)
        val pi = buildPendingIntent(
            ctx = ctx.applicationContext,
            module = MODULE_FINANCE,
            refId = refId,
            refKind = refKind,
            occurrenceTs = fireAt,
        )

        val exactOk = alarmScheduler.scheduleExact(triggerAtMs, pi)
        if (!exactOk) {
            // 精确闹钟被拒 → 降级 + 写 finance_reminder_log
            alarmScheduler.scheduleInexact(triggerAtMs, pi)
            try {
                ServiceLocator.db.financeReminderLogDao().insertRaw(
                    refId = refId,
                    refKind = refKind,
                    fireAt = fireAt,
                    delivered = false,
                )
            } catch (e: Exception) {
                // 日志写入失败不阻断调度（best-effort）
            }
        }
    }

    /**
     * 重建全局闹钟链头（spec FR-5 / AC-7 / TR-5.2 + 阶段 5 TR-6.1 扩展）。
     *
     * 算法：
     *  1) 遍历 `ServiceLocator.eventsRepo.observeAll().first()` 取全表事件；
     *  2) 对每个事件调 `nextTrigger(rule, reminders, now)`；
     *  3) **新增（阶段 5 TR-6.1）**：遍历
     *     `ServiceLocator.financeRepo.cardDao().observeAll().first()` 取
     *     全表信用卡（observeActive 优先，归档卡天然不参与），
     *     对每个卡片调 `nextCardFiring(card, now)`；并记录该次触发的
     *     `module` + `refKind`，用于构造 PendingIntent extras；
     *  4) 合并事件 + 财务两类触发，取全局最小 nextTrigger；
     *  5) 全局无触发（未来无 reminder）→ 取消已有 PendingIntent，避免悬挂。
     *
     * 入口（任务描述）：
     *  - BootReceiver 触发时（设备开机/重启）—— TR-5.4；
     *  - 编辑器保存事件后（Task 6 / T10）；
     *  - 应用冷启动进入主界面（Task 9）；
     *  - ReminderReceiver 收到闹钟后（链式下一触发）—— TR-5.3。
     *
     * 性能：O(N_events + N_cards)；单用户量级千级以下；4a SyncWorker 周期
     * 15 分钟。SPEC NFR-4 约束单用户量级 < 100ms（本实现 N 次纯函数计算 +
     * 一次 AlarmManager.setExact，复杂度可控）。
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

        // 2) 事件分支：全局最小 nextTrigger → (eventId, triggerTs)。
        var bestEventTrigger: Long? = null
        var bestEventId: String? = null
        for (entity in events) {
            val rule = entityToRule(entity) ?: continue
            val reminders = parseReminders(entity.reminders_json)
            if (reminders.isEmpty()) continue
            val ts = nextTrigger(rule, reminders, now) ?: continue
            if (bestEventTrigger == null || ts < bestEventTrigger) {
                bestEventTrigger = ts
                bestEventId = rule.id
            }
        }

        // 3) 阶段 5 TR-6.1 财务分支：全局最小 nextCardFiring →
        //    (refId, refKind, triggerTs)。
        //    注：observeActive 已经过滤 archived/deleted；
        //    nextCardFiring 内部仍校验 kind=="credit" + billingDay/dueDay 范围。
        var bestFinanceTrigger: Long? = null
        var bestFinanceRefId: String? = null
        var bestFinanceRefKind: String? = null
        val cards: List<FinanceCardEntity> = try {
            // observeActive 优先（DAO 层做运行时过滤，少一次遍历过滤）
            ServiceLocator.financeRepo.cardDao
                .observeActive()
                .first()
        } catch (e: Exception) {
            // ServiceLocator 未初始化或 Room 异常 → 仅按事件分支继续
            emptyList()
        }

        for (card in cards) {
            // 仅 credit 卡片进入计算（nextCardFiring 内部也会再校验一次）
            if (card.kind != CARD_KIND_CREDIT) continue
            // archived=true 直接跳过（observeActive 已过滤；此处双保险）
            if (card.archived) continue
            // 计算账单日触发与还款日触发，取全局最小
            val stmtTrigger = computeStatementTrigger(card, now)
            val payTrigger = computePaymentTrigger(card, now)
            val candidates = listOfNotNull(stmtTrigger, payTrigger)
            if (candidates.isEmpty()) continue
            val ts = candidates.min()
            // 同时记录 kind（账单优先：同 ts 退化为账单提醒，便于用户理解）
            val kind = if (stmtTrigger == ts) {
                REF_KIND_CARD_STATEMENT_DUE
            } else {
                REF_KIND_CARD_PAYMENT_DUE
            }
            if (bestFinanceTrigger == null || ts < bestFinanceTrigger) {
                bestFinanceTrigger = ts
                bestFinanceRefId = card.id
                bestFinanceRefKind = kind
            }
        }

        // 4) 合并事件 + 财务两类，取全局最小
        val alarmScheduler = RealAlarmScheduler(appCtx)
        when {
            bestEventTrigger == null && bestFinanceTrigger == null -> {
                // 全局无触发 → 取消已有 PendingIntent，避免悬挂
                val pi = buildPendingIntent(
                    ctx = appCtx,
                    module = MODULE_EVENT,
                    refId = "",
                    refKind = "",
                    occurrenceTs = 0L,
                )
                alarmScheduler.cancel(pi)
                return
            }
            bestFinanceTrigger == null ||
                (bestEventTrigger != null && bestEventTrigger <= bestFinanceTrigger) -> {
                // 事件分支最小；既有 scheduleNext 沿用
                scheduleNext(
                    triggerAtMs = bestEventTrigger!!,
                    eventId = bestEventId!!,
                    occurrenceTs = bestEventTrigger!!,
                    ctx = appCtx,
                )
            }
            else -> {
                // 财务分支最小；调 scheduleNextFinance 走单闹钟（同 requestCode）
                scheduleNextFinance(
                    triggerAtMs = bestFinanceTrigger!!,
                    refId = bestFinanceRefId!!,
                    refKind = bestFinanceRefKind!!,
                    fireAt = bestFinanceTrigger!!,
                    ctx = appCtx,
                )
            }
        }
    }

    /**
     * 阶段 5 TR-6.1：单张信用卡的账单日触发时刻（账单日 T-3）。
     *
     * 内部辅助：被 rebuildChain 调用；也可被单元测试直接调（internal 可见性）。
     */
    internal fun computeStatementTrigger(card: FinanceCardEntity, now: Long): Long? {
        if (card.archived || card.kind != CARD_KIND_CREDIT) return null
        if (card.billingDay == null || card.billingDay !in BILLING_DAY_RANGE) return null
        return computeMonthlyTriggerMs(
            dayOfMonth = card.billingDay,
            offsetMs = STATEMENT_OFFSET_MS,
            now = now,
            zone = java.time.ZoneId.systemDefault(),
        )
    }

    /**
     * 阶段 5 TR-6.1：单张信用卡的还款日触发时刻（还款日 T-1）。
     *
     * 内部辅助：被 rebuildChain 调用；也可被单元测试直接调（internal 可见性）。
     */
    internal fun computePaymentTrigger(card: FinanceCardEntity, now: Long): Long? {
        if (card.archived || card.kind != CARD_KIND_CREDIT) return null
        if (card.dueDay == null || card.dueDay !in BILLING_DAY_RANGE) return null
        return computeMonthlyTriggerMs(
            dayOfMonth = card.dueDay,
            offsetMs = PAYMENT_OFFSET_MS,
            now = now,
            zone = java.time.ZoneId.systemDefault(),
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
     *  - 阶段 5 TR-6.1 扩展：extras 携带 `module` + `ref_kind`，ReminderReceiver
     *    据此路由 event / finance 分支。
     */
    internal fun buildPendingIntent(
        ctx: Context,
        module: String,
        refId: String,
        refKind: String,
        occurrenceTs: Long,
    ): PendingIntent {
        val intent = Intent(ctx, ReminderReceiver::class.java).apply {
            action = "com.everything.eve.reminder.FIRE"
            // 4b 既有 extras（向后兼容事件分支）
            putExtra(EXTRA_EVENT_ID, refId)
            putExtra(EXTRA_OCCURRENCE_TS, occurrenceTs)
            // 阶段 5 TR-6.1 新增 extras：module + ref_kind
            putExtra(EXTRA_MODULE, module)
            putExtra(EXTRA_REF_KIND, refKind)
        }
        var flags = PendingIntent.FLAG_UPDATE_CURRENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            flags = flags or PendingIntent.FLAG_IMMUTABLE
        }
        return PendingIntent.getBroadcast(ctx, REMINDER_REQUEST_CODE, intent, flags)
    }
}

// =============================================================================
// 阶段 5 TR-6.1 模块常量（与 docs/module-schemas.md §9 finance 严格一致）
// =============================================================================

/** 阶段 5 模块标识：事件（4b 既有）。 */
internal const val MODULE_EVENT: String = "event"

/** 阶段 5 模块标识：财务（5 新增）。 */
internal const val MODULE_FINANCE: String = "finance"

/** 财务卡片类型：信用卡（账单/还款提醒仅对 credit 卡片生效）。 */
internal const val CARD_KIND_CREDIT: String = "credit"

/** 财务卡片类型：借记卡（不触发提醒；保留常量便于 v2 扩展）。 */
internal const val CARD_KIND_DEBIT: String = "debit"

/** 提醒种类：信用卡账单日（v1 启用）。 */
internal const val REF_KIND_CARD_STATEMENT_DUE: String = "card_statement_due"

/** 提醒种类：信用卡还款日（v1 启用）。 */
internal const val REF_KIND_CARD_PAYMENT_DUE: String = "card_payment_due"

/** 账单/还款日合法范围（1-31 日）。 */
internal val BILLING_DAY_RANGE: IntRange = 1..31

/**
 * 月度跨月滚动最大回退次数（防御性 bound）。
 *
 * 选 24 个月的依据：单用户场景下"本月触发时刻刚过 → 下月仍未触发"
 * 是极端异常（如卡片新建在很靠近触发点的时间窗 < 1s）；
 * 24 个月足够覆盖任何边界场景，又避免人为构造时间导致死循环。
 */
internal const val MAX_MONTH_LOOKAHEAD: Int = 24
