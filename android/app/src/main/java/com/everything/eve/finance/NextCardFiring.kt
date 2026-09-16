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
// 关联:
//   - tasks.md TR-5.2（Android NextCardFiring.kt 镜像实现）
//   - tasks.md TR-5.4（JUnit 测试套件, 加载共享 fixture）
//   - tasks.md TR-5.4（三端 fixture SHA-256 一致性核验）
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
    // 私有工具方法 —— 本地日历推理（与 4b Recurrence.kt 同款算法骨架）
    // ============================================================================

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
