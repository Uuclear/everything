// ============================================================================
// NextCardFiring 纯函数 —— 财务模块 Android 端纯函数实现（stage5-finance / T5 / TR-5.2）
// ============================================================================
//
// 任务: stage5-finance / Task 5 / TR-5.2
// 路径: android/app/src/main/java/com/everything/eve/finance/NextCardFiring.kt
// 作用: 计算信用卡下一触发时刻（账单日 T+0 09:00 / 还款日 T-1 09:00）, 供
//       ReminderScheduler 单闹钟链式调度复用（沿用 4b rebuildChain 链路）；
//       纯函数, 无副作用, 不依赖 Room / Network / DataStore / Log。
//
// 设计要点（与 Web web/src/finance/nextCardFiring.ts 字节级一致 —— 三端契约）：
//   1. 纯函数 —— 入参 (card, nowMs) -> 返回 nextTrigger 候选 ms 数组;
//
//   2. 双触发口径（与 spec FR-5 / docs/finance.md §5 一致）：
//      - **账单日触发**：card.billingDay 当月/下月 T+0 09:00 CST；
//        spec 设计意图 —— 账单出账当日提醒用户核对消费明细。
//      - **还款日触发**：card.billingDay + card.dueDayOffset 当月/下月 T-1 09:00 CST；
//        spec 设计意图 —— 还款前一天提醒用户准备资金（避免逾期影响征信）。
//
//   3. due_day 语义（与 docs/schemas/finance.schema.json 一致）：
//      `due_day` 是相对账单日的 **offset 天数**（1-31），不是绝对日；
//      例：billingDay=5, dueDay=25 → 还款日 = 当月 (5+25)=30 日；31 日在 2 月
//      按当月最大日裁剪（2 月 = 28/29 日, spec schema 注释）；
//
//   4. nextTrigger 选取规则 —— 取两类候选中**最近一次未来触发**：
//      - 两类候选各自计算当月与下月两次（4 个候选）, 筛掉 ≤ nowMs 的, 取最小；
//      - 都过期时, 返回 null（业务语义: 该卡近期无提醒）；
//
//   5. 边界场景（与 fixture 一致）：
//      - `card.archived=true` → 返回 null（不触发）；
//      - `card.billingDay=null` → 返回 null（未配置账单日）；
//      - `card.dueDay=null` → 仅返回账单日触发, 还款日跳过；
//
// v2 扩展（stage5-finance-v2 / Task 4 / TR-4.1）:
//   在同一 object 内追加三个 v2 提醒纯函数与对应入参 DTO：
//     - nextSubscriptionRenewal（订阅续费提醒, 支持 monthly/quarterly/yearly/custom_days
//       日历滚动, 防御性上限 MAX_CYCLE_LOOKAHEAD=24 个周期）；
//     - nextPolicyExpiry（保单到期一次性提醒, 不滚动）；
//     - nextLoanDue（借款到期一次性提醒, status=paid 不提醒）。
//   三函数签名统一为 (like, nowMs) -> Long?, 共享 reminders 分钟偏移候选口径
//   （候选 = 基准时刻 - r*60000, 严格 > nowMs 才有效, 取最小）。
//
// 关联:
//   - tasks.md TR-5.2（Android NextCardFiring.kt 镜像实现）
//   - tasks.md TR-5.4（JUnit 测试套件, 加载共享 fixture）
//   - tasks.md TR-5.4（三端 fixture SHA-256 一致性核验）
//   - tasks.md TR-4.1（v2 订阅/保单/借款下一提醒纯函数, stage5-finance-v2）
//   - tasks.md TR-4.6（v2 提醒 fixture 双端 SHA-256 一致性核验）
//   - web/src/finance/nextCardFiring.ts（Web 镜像版本, 本期 T5 子代理同步创建）
//   - docs/schemas/finance.schema.json#/$defs/FinanceCard（字段真理源）
// ============================================================================

package com.everything.eve.finance

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * NextCardFiring 纯函数 object 容器（无状态, 全静态方法）。
 *
 * 命名采用 Kotlin 单例 `object` 而非 `class`, 与工程内 Luhn.kt / FinanceAggregator.kt
 * / Recurrence.kt 等纯算法容器保持风格一致。
 */
object NextCardFiring {

    // ============================================================================
    // 常量区 —— 触发时刻边界（与 spec FR-5 / docs/finance.md §5 一致）
    // ============================================================================

    /**
     * 触发时刻小时 —— 09:00 CST（本地时区上午 9 点整）。
     *
     * 业务语义（spec FR-5）：用户开始工作日的"日常核对窗口"；
     * 不在 00:00 / 12:00 / 18:00 是因为 —— 00:00 用户已入睡, 12:00 工作会议, 18:00 下班通勤,
     * 均不利于打开 App 查看提醒。09:00 上午工作开始时是相对合理的时间点。
     */
    private const val TRIGGER_HOUR = 9

    /** 触发时刻分钟 —— 0 分整（与 spec FR-5 一致）。 */
    private const val TRIGGER_MINUTE = 0

    /**
     * 还款日提前天数 —— T-1 = 还款日前 1 天（与 spec FR-5 / tasks.md TR-5.2 完全一致）。
     *
     * 业务语义：提前 1 天提醒用户准备资金, 避免次日直接逾期。
     */
    private const val PAYMENT_DUE_DAYS_BEFORE = 1

    /**
     * 还款日相对账单日的 offset 天数（spec schema 注释明示）。
     *
     * 注意：本常量**未被使用**——`card.dueDay` 即代表 offset 数值本身,
     * 这里仅作语义锚点提示（与 Web 端 `DUE_DAY_OFFSET_KEY` 对齐）。
     */
    private const val DUE_DAY_OFFSET_KEY = "dueDay"

    /**
     * v2 订阅续费向前滚动的防御性周期上限（stage5-finance-v2 / TR-4.1）。
     *
     * 业务语义：当 nextRenewalTs 因数据异常停留在远古时刻（如 1970 年）时,
     * 纯函数不能无限循环滚动；最多向前滚动 24 个计费周期（monthly 约 2 年,
     * custom_days=1 为 24 天）仍不超过 nowMs 即放弃, 返回 null。
     * 精神同 v1 常量 MAX_MONTH_LOOKAHEAD（见 ReminderScheduler 链路）。
     */
    private const val MAX_CYCLE_LOOKAHEAD = 24

    /** 一分钟对应的毫秒数（reminders 偏移单位换算）。 */
    private const val MINUTE_MS = 60_000L

    /**
     * 本地时区相对 UTC 的偏移（分钟；如 UTC+8 为 480）。
     *
     * 复用 FinanceAggregator.kt 同口径（各自维护一份以保持模块边界清晰）。
     * JUnit 单测需在加载**前**通过 `System.setProperty("user.timezone", "Asia/Shanghai")`
     * 锁定到 CST 锚定。
     */
    private val TZ_OFFSET_MIN: Int = run {
        val zone = ZoneId.systemDefault()
        val offsetSeconds = zone.rules.getOffset(Instant.ofEpochMilli(1780000000000L))
            .totalSeconds
        offsetSeconds / 60
    }

    // ============================================================================
    // 公开数据类型 —— 入参 DTO（解耦 Room Entity, 便于 JUnit 注入 fixture 数据）
    // ============================================================================

    /**
     * 卡入参形态 —— 仅取触发计算所需的最小字段集。
     *
     * 字段语义与 [com.everything.eve.data.finance.entity.FinanceCardEntity] 对齐,
     * 但聚合函数不消费 last4 / issuer / holder 等敏感/展示字段, 故不入参。
     */
    data class CardLike(
        val id: String,
        val kind: String,
        val billingDay: Int?,
        val dueDay: Int?,
        val archived: Boolean,
    )

    // ============================================================================
    // v2 入参 DTO（stage5-finance-v2 / TR-4.1）—— 解耦 Room Entity
    // ============================================================================

    /**
     * 订阅入参形态（v2）—— 字段命名与 [SubscriptionRecord] camelCase 对齐。
     *
     * @param billingCycle monthly | quarterly | yearly | custom_days
     * @param customDays billingCycle=custom_days 时的周期天数；其余周期为 null
     * @param reminders 提醒分钟偏移列表（0 = 续费当日; 1440 = 提前一天）
     */
    data class SubscriptionLike(
        val id: String,
        val active: Boolean,
        val nextRenewalTs: Long,
        val billingCycle: String,
        val customDays: Long?,
        val reminders: List<Long>,
    )

    /**
     * 保单入参形态（v2）—— 字段命名与 [PolicyRecord] camelCase 对齐。
     *
     * @param expiryTs 保单到期时刻（一次性基准, 不滚动）
     * @param reminders 提醒分钟偏移列表（0 = 到期当日）
     */
    data class PolicyLike(
        val id: String,
        val active: Boolean,
        val expiryTs: Long,
        val reminders: List<Long>,
    )

    /**
     * 借款入参形态（v2）—— 字段命名与 [LoanRecord] camelCase 对齐。
     *
     * 注意：本 DTO 与 [FinanceAggregator.LoanLike] 同名但位于不同 object
     * （NextCardFiring.LoanLike vs FinanceAggregator.LoanLike）, 各自只承载
     * 本场景所需最小字段集, 可在同文件以 import 别名消歧。
     *
     * @param status active | partially_paid | paid | overdue；仅 paid 不提醒
     * @param dueTs 借款到期时刻（一次性基准, 不滚动）
     * @param reminders 提醒分钟偏移列表（0 = 到期当日）
     */
    data class LoanLike(
        val id: String,
        val status: String,
        val dueTs: Long,
        val reminders: List<Long>,
    )

    // ============================================================================
    // 公开 API —— 两个纯函数（与 Web nextCardFiring.ts 签名一致）
    // ============================================================================

    /**
     * 计算单张卡的下一次触发时刻（账单日 / 还款日 T-1, 取最近未来）。
     *
     * 算法骨架（与 Web nextCardFiring.ts `nextTrigger` 完全对齐）：
     *   1. 早退 —— `archived=true` 或 `billingDay=null` → 返回 null；
     *   2. 计算当月账单日 T+0 09:00 CST 对应 ms；若 < nowMs → 改为下月账单日；
     *   3. 计算当月还款日 T-1 09:00 CST 对应 ms；若 < nowMs → 改为下月还款日；
     *   4. `dueDay=null` → 跳过还款日候选；
     *   5. 在所有未来候选中取最小值（即"最近一次未来触发"）。
     *
     * @param card 单张信用卡（含 billingDay / dueDay / archived 字段）
     * @param nowMs 当前时刻（Unix 毫秒；由调用方提供, 便于测试时锚定）
     * @return 最近一次未来触发的 Unix 毫秒；无候选时返回 null
     */
    fun nextTrigger(card: CardLike, nowMs: Long): Long? {
        // ========== 1. 早退守卫 ==========
        // 归档卡 / 未配置账单日一律不触发 —— 业务语义: 不打扰。
        if (card.archived) return null
        val billingDay = card.billingDay ?: return null

        // ========== 2. 拆 nowMs 本地日历分量 ==========
        // 与 4b Recurrence.kt 同口径：ms + tz_offset_ms 当 UTC ms 读, y/m/d 拆出来。
        val (nowY, nowM, _) = localPartsOfAsUtc(nowMs)

        // ========== 3. 计算账单日候选（当月 + 下月, 选 ≥ nowMs） ==========
        val statementCandidates: List<Long> = buildList {
            // 当月账单日 T+0 09:00 CST
            val currentMonth = tryLocalDateToMs(nowY, nowM, billingDay, TRIGGER_HOUR, TRIGGER_MINUTE)
            if (currentMonth != null) add(currentMonth)
            // 下月账单日 T+0 09:00 CST（兜底: 当月已过时使用）
            val nextMonthDate = LocalDate.of(nowY, nowM, 1).plusMonths(1L)
            val nextMonth = tryLocalDateToMs(
                nextMonthDate.year, nextMonthDate.monthValue, billingDay, TRIGGER_HOUR, TRIGGER_MINUTE
            )
            if (nextMonth != null) add(nextMonth)
        }.filter { it > nowMs }

        // ========== 4. 计算还款日 T-1 候选（仅在 dueDay 非空时） ==========
        val paymentCandidates: List<Long> = if (card.dueDay == null) {
            // dueDay 未配置 → 跳过还款日候选, 仅返回账单日触发。
            emptyList()
        } else {
            val dueDayOffset = card.dueDay
            buildList {
                // 还款日 = billingDay + dueDayOffset; 当月无该日 → 裁剪到月底。
                val currentMonthDue = computePaymentDueDayMs(nowY, nowM, billingDay, dueDayOffset)
                if (currentMonthDue != null) add(currentMonthDue)
                val nextMonthDate = LocalDate.of(nowY, nowM, 1).plusMonths(1L)
                val nextMonthDue = computePaymentDueDayMs(
                    nextMonthDate.year, nextMonthDate.monthValue, billingDay, dueDayOffset
                )
                if (nextMonthDue != null) add(nextMonthDue)
            }.filter { it > nowMs }
        }

        // ========== 5. 取两类候选的最小值（最近一次未来触发） ==========
        val allCandidates = (statementCandidates + paymentCandidates)
        return if (allCandidates.isEmpty()) null else allCandidates.min()
    }

    /**
     * 多卡批量计算下一次触发时刻（取全局最小 nextTrigger, 沿用 4b 单闹钟链式调度）。
     *
     * 算法骨架（与 Web nextCardFiring.ts `upcomingTriggers` 对齐）：
     *   1. 遍历 `cards`, 对每张卡调 `nextTrigger(card, nowMs)`；
     *   2. 收集所有非 null 候选, 按 ms 升序排序；
     *   3. 返回前 N 个（默认 N=5, 与 ReminderScheduler rebuildChain 取"最近未来"语义一致）。
     *
     * @param cards 卡列表（全集, 函数内部按 archived / billingDay 过滤）
     * @param nowMs 当前时刻（Unix 毫秒）
     * @param limit 返回上限（默认 5; 与 4b ReminderScheduler 一次性取全局最小一致）
     * @return 升序排列的未来触发时刻列表（最长 `limit` 条; 全空时返回空列表）
     */
    fun upcomingTriggers(
        cards: List<CardLike>,
        nowMs: Long,
        limit: Int = 5,
    ): List<Long> {
        if (limit <= 0) return emptyList()
        // 调用 nextTrigger 内部已处理 archived / billingDay=null 守卫。
        return cards
            .mapNotNull { nextTrigger(it, nowMs) }
            .sorted()
            .take(limit)
    }

    // ============================================================================
    // v2 公开 API —— 订阅 / 保单 / 借款下一提醒（stage5-finance-v2 / TR-4.1）
    // ============================================================================

    /**
     * 计算订阅条目的下一次续费提醒时刻（取最近未来候选）。
     *
     * 算法骨架：
     *   1. 早退 —— active=false 或 reminders 为空 → 返回 null；
     *   2. 基准实例 = sub.nextRenewalTs；若已 ≤ nowMs, 按 billingCycle 用 java.time
     *      系统时区 LocalDateTime 做日历加法向前滚动（保留原 ts 的时分秒）：
     *        - monthly     → plusMonths(1)；
     *        - quarterly   → plusMonths(3)；
     *        - yearly      → plusYears(1)；
     *        - custom_days → plusDays(customDays)；customDays 为 null 或 ≤0 → null；
     *      月末日期滚动由 java.time 自然裁剪（如 1/31 + 1 月 = 2/28）；
     *   3. 防御性上限 —— 连续滚动 [MAX_CYCLE_LOOKAHEAD] 个周期仍 ≤ nowMs → null；
     *   4. 对最终续费实例计算候选 { renewal - r*60000 | r in reminders, r>=0 },
     *      仅保留严格 > nowMs 的候选, 返回最小值；无候选返回 null。
     *
     * @param sub 订阅入参 DTO
     * @param nowMs 当前时刻（Unix 毫秒；由调用方提供, 便于测试锚定）
     * @return 最近一次未来提醒的 Unix 毫秒；无候选时返回 null
     */
    fun nextSubscriptionRenewal(sub: SubscriptionLike, nowMs: Long): Long? {
        // ========== 1. 早退守卫 ==========
        if (!sub.active) return null
        if (sub.reminders.isEmpty()) return null

        // ========== 2. 基准实例过期则按周期向前滚动 ==========
        var renewal = sub.nextRenewalTs
        var cycles = 0
        while (renewal <= nowMs) {
            // 达到防御性上限仍落过去 → 视为异常数据, 放弃提醒。
            if (cycles >= MAX_CYCLE_LOOKAHEAD) return null
            renewal = rollRenewal(renewal, sub.billingCycle, sub.customDays) ?: return null
            cycles++
        }

        // ========== 3. 在未来提醒候选中取最小值 ==========
        return earliestFutureReminder(renewal, sub.reminders, nowMs)
    }

    /**
     * 计算保单条目的下一次到期提醒时刻（一次性基准, 不滚动）。
     *
     * 算法骨架：
     *   1. 早退 —— active=false 或 reminders 为空 → 返回 null；
     *   2. 基准实例固定 = policy.expiryTs（保单到期是一次性事件, 不做周期滚动）；
     *   3. 候选 { expiryTs - r*60000 | r in reminders, r>=0 } 过滤 > nowMs 取最小；
     *      全部已过期 → null。
     *
     * @param policy 保单入参 DTO
     * @param nowMs 当前时刻（Unix 毫秒）
     * @return 最近一次未来提醒的 Unix 毫秒；无候选时返回 null
     */
    fun nextPolicyExpiry(policy: PolicyLike, nowMs: Long): Long? {
        if (!policy.active) return null
        if (policy.reminders.isEmpty()) return null
        return earliestFutureReminder(policy.expiryTs, policy.reminders, nowMs)
    }

    /**
     * 计算借款条目的下一次到期提醒时刻（一次性基准, 不滚动）。
     *
     * 算法骨架：
     *   1. 早退 —— status=="paid"（已结清）或 reminders 为空 → 返回 null；
     *      active / partially_paid / overdue 三种状态均继续提醒（逾期未还更应提醒）；
     *   2. 基准实例固定 = loan.dueTs（借款到期是一次性事件）；
     *   3. 候选 { dueTs - r*60000 | r in reminders, r>=0 } 过滤 > nowMs 取最小；
     *      全部已过期 → null。
     *
     * @param loan 借款入参 DTO
     * @param nowMs 当前时刻（Unix 毫秒）
     * @return 最近一次未来提醒的 Unix 毫秒；无候选时返回 null
     */
    fun nextLoanDue(loan: LoanLike, nowMs: Long): Long? {
        // 已结清借款不再提醒；其余状态（含 overdue 逾期）一律继续提醒。
        if (loan.status == "paid") return null
        if (loan.reminders.isEmpty()) return null
        return earliestFutureReminder(loan.dueTs, loan.reminders, nowMs)
    }

    // ============================================================================
    // 私有工具方法 —— 本地日历推理（与 4b Recurrence.kt 同款算法骨架）
    // ============================================================================

    /**
     * v2 订阅续费实例按计费周期向前滚动一个周期（TR-4.1）。
     *
     * 走 java.time 系统时区 LocalDateTime 日历加法, 保留原 ts 的时分秒；
     * 与本文件 localPartsOfAsUtc 同一 CST 偏移口径（CST 无夏令时, 两种拆法等价）。
     *
     * @return 滚动后的 Unix 毫秒；周期非法或 customDays 缺失/非正时返回 null
     */
    private fun rollRenewal(ts: Long, billingCycle: String, customDays: Long?): Long? {
        val base = toLocalDateTime(ts)
        val next = when (billingCycle) {
            "monthly" -> base.plusMonths(1L)
            "quarterly" -> base.plusMonths(3L)
            "yearly" -> base.plusYears(1L)
            "custom_days" -> {
                // custom_days 必须显式给出正整数天数；否则数据非法, 不提醒。
                if (customDays == null || customDays <= 0L) return null
                base.plusDays(customDays)
            }
            // 未知周期字符串防御性处理。
            else -> return null
        }
        return fromLocalDateTime(next)
    }

    /**
     * 计算基准时刻的未来提醒候选最小值（v2 三函数共享口径）。
     *
     * 候选 = baseTs - r*60000（r 为提前分钟数; r=0 即基准时刻本身）；
     * 负偏移（r<0, 语义为"之后提醒"）按防御性策略忽略；
     * 仅保留严格 > nowMs 的候选, 返回其中最小值。
     *
     * @return 最近一次未来提醒 ms；无未来候选时返回 null
     */
    private fun earliestFutureReminder(baseTs: Long, reminders: List<Long>, nowMs: Long): Long? {
        var best: Long? = null
        for (r in reminders) {
            if (r < 0L) continue
            val candidate = baseTs - r * MINUTE_MS
            // 严格大于 nowMs —— 与 v1 nextTrigger 的 filter { it > nowMs } 口径一致,
            // 整点恰好等于 nowMs 视为已过, 不再触发。
            if (candidate > nowMs && (best == null || candidate < best!!)) {
                best = candidate
            }
        }
        return best
    }

    /**
     * Unix ms（CST 口径）→ 系统时区 LocalDateTime（保留时分秒）。
     *
     * 与 [localPartsOfAsUtc] 同一手法：ts 先加时区偏移再当 UTC 读,
     * 得到的字段即 CST 本地日历分量。
     */
    private fun toLocalDateTime(ts: Long): LocalDateTime {
        return Instant.ofEpochMilli(ts + TZ_OFFSET_MIN * MINUTE_MS)
            .atOffset(ZoneOffset.UTC)
            .toLocalDateTime()
    }

    /**
     * 系统时区 LocalDateTime → Unix ms（[toLocalDateTime] 的逆运算）。
     */
    private fun fromLocalDateTime(ldt: LocalDateTime): Long {
        return ldt.toInstant(ZoneOffset.UTC).toEpochMilli() - TZ_OFFSET_MIN * MINUTE_MS
    }

    /**
     * "无时区 ts + offset" 拆成本地日历分量（与 4b Recurrence.kt 完全等价）。
     *
     * 算法：先把 ms 视为 UTC 时刻, 用 UTC 字段读 y/m/d —— 这是 JS Date 的"无时区
     * Date"语义。例：CST 2026-01-01 09:00 = 1767229200000（UTC 01:00）,
     * ts + offset = 1767258000000 → 当 UTC ms → 2026-01-01 09:00 ✅
     *
     * @return Triple(year, month, day) 三元组（month = 1..12）
     */
    private fun localPartsOfAsUtc(ts: Long): Triple<Int, Int, Int> {
        val shifted = ts + TZ_OFFSET_MIN * 60_000L
        val dt = Instant.ofEpochMilli(shifted).atOffset(ZoneOffset.UTC).toLocalDateTime()
        return Triple(dt.year, dt.monthValue, dt.dayOfMonth)
    }

    /**
     * 由本地日历分量 (y, m, d, h, mi) 重构 Unix 毫秒（与 4b Recurrence.kt 同口径）。
     *
     * @return Unix 毫秒（UTC ms）；该月无该日（如 2/30）时返回 null
     */
    private fun tryLocalDateToMs(y: Int, m: Int, d: Int, h: Int, mi: Int): Long? {
        // 月份进位（m=13 → 次年 1 月）；日字段越界（如 2/30）由 java.time 自动进位到 3/2。
        val date = LocalDate.of(y, m, 1)
        val daysInMonth = date.lengthOfMonth()
        if (d < 1 || d > daysInMonth) return null
        val shifted = LocalDateTime.of(y, m, d, h, mi)
            .toInstant(ZoneOffset.UTC).toEpochMilli()
        return shifted - TZ_OFFSET_MIN * 60_000L
    }

    /**
     * 计算还款日 T-1 触发时刻 ms。
     *
     * 算法：
     *   1. 还款日 = billingDay + dueDayOffset（offset 模式, spec schema 注释）；
     *   2. 若超过当月最大天数 → 钳位到当月最大日（如 1+30=31 在 2 月按 28/29 日）；
     *   3. T-1 = 还款日 - 1 天；还款日为月初 1 日时, T-1 退到上月最后一天；
     *   4. 触发时刻固定 09:00 CST。
     *
     * @return 还款日 T-1 09:00 CST 对应的 Unix 毫秒；该月无账单日时返回 null
     */
    private fun computePaymentDueDayMs(
        y: Int, m: Int, billingDay: Int, dueDayOffset: Int,
    ): Long? {
        // ========== 1. 还款日 = billingDay + offset ==========
        // billingDay 为 1-31; offset 为 1-31; 二者相加结果 2-62, 钳位到当月最大日。
        val rawDueDay = billingDay + dueDayOffset
        val date = LocalDate.of(y, m, 1)
        val daysInMonth = date.lengthOfMonth()
        val dueDay = rawDueDay.coerceAtMost(daysInMonth)

        // ========== 2. T-1 = 还款日 - 1 天 ==========
        // 当 dueDay = 1 时, T-1 退到上月最后一天（minusDays 自动跨月）。
        // 当 dueDay > 1 时, T-1 直接在当月减一天。
        val triggerDate = LocalDate.of(y, m, dueDay).minusDays(PAYMENT_DUE_DAYS_BEFORE.toLong())

        return tryLocalDateToMs(
            triggerDate.year, triggerDate.monthValue, triggerDate.dayOfMonth,
            TRIGGER_HOUR, TRIGGER_MINUTE,
        )
    }
}
