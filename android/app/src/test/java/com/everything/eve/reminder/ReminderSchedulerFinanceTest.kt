/*
 * 阶段 5 — Android ReminderScheduler 财务分支单元测试（tasks.md Task 6 / TR-6.4）。
 *
 * 测试策略（spec NFR-3 / TR-6.4）：
 *   1. **nextCardFiring 纯函数直接断言**：不依赖 Android Framework，全部 JVM 单测
 *      即可跑；账单日 / 还款日跨月滚动逻辑覆盖。
 *   2. **internal computeStatementTrigger / computePaymentTrigger 直接断言**：
 *      单张卡片的两种触发分别独立验证，便于定位跨月滚动失败原因。
 *   3. **内部工具常量 / 类型辅助**：仅验证 nextCardFiring 在下列边界返回正确
 *      值——kCredit/billingDay/dueDay 缺省、归档、借记卡、非法范围。
 *
 * 测试纪律（spec NFR-3 / 任务纪律）：
 *   - 不引入新依赖；测试仅用 JUnit 4 + java.time（与 4b ReminderSchedulerTest 同款）。
 *   - 时区锁：Asia/Shanghai（与 4b test 同源）。
 *   - ≥6 用例覆盖以下场景：
 *     (1) 信用卡 + 账单日 + 还款日 + 未来 → 返回 min(账单 T-3, 还款 T-1)；
 *     (2) 信用卡 + 仅账单日 → 返回账单 T-3；
 *     (3) 信用卡 + 仅还款日 → 返回还款 T-1；
 *     (4) 信用卡 + 跨月滚动：当月已过 → 下月同一日偏移；
 *     (5) archived=true → null（不触发）；
 *     (6) kind="debit" → null（不触发）；
 *     (7) billingDay 越界（32 或 0）→ 账单分支跳过，仅还款分支生效；
 *     (8) dueDay null + billingDay null → null（无任何候选）。
 *     (9) computeStatementTrigger 独立：账单日跨月滚动；
 *     (10) computePaymentTrigger 独立：还款日跨月滚动。
 *     ——共 10 用例，满足任务要求的 ≥6。
 *
 *   B4 追加（stage5-finance-v2 / TR：selectBestV2Trigger 纯函数）7 用例：
 *     (11) 单条订阅未来触发 → 返订阅候选（id/kind/ts）；
 *     (12) 活跃保单未来 + 已过期且周期非法的订阅 → 保单胜出；
 *     (13) paid 借款跳过，另一条未结清借款胜出；
 *     (14) 三类混合 → 取全局最小（借款 1 天后）且 kind 正确；
 *     (15) 订阅/保单均 active=false → null；
 *     (16) 三类全空列表 → null；
 *     (17) 三类同毫秒 tie-break → subscription(2) 胜（sub>policy>loan 稳定序）。
 */

package com.everything.eve.reminder

import com.everything.eve.data.finance.entity.FinanceCardEntity
import com.everything.eve.finance.NextCardFiring
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.BeforeClass
import org.junit.Test
import java.util.TimeZone
import java.util.concurrent.TimeUnit

class ReminderSchedulerFinanceTest {

    companion object {
        /**
         * 类加载**前**锁定运行时本地时区到 Asia/Shanghai（CST/UTC+8）。
         *
         * 必要性：nextCardFiring / computeMonthlyTriggerMs 用 ZoneId.systemDefault()
         * 算日历日；若运行环境非 CST，账单/还款日 0:00 的基准 ms 会漂移，导致
         * 断言的 expected ts 与实际 ts 不符。
         *
         * 与 4b ReminderSchedulerTest @BeforeClass 同源（spec FR-11 跨端镜像）。
         */
        @JvmStatic
        @BeforeClass
        fun lockTimezone() {
            System.setProperty("user.timezone", "Asia/Shanghai")
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"))
        }
    }

    // -------------------------------------------------------------------------
    // 测试锚定常量
    // -------------------------------------------------------------------------

    /**
     * 测试锚定 now：**2026-06-28 12:00:00 本地时间（CST, UTC+8）= 1782619200000 ms**。
     *
     * 选 6 月 28 日作为锚定的理由：
     *  - 当月（6 月）25 日已过，但 5 日 / 10 日 / 28 日 / 30 日 / 15 日等典型
     *    账单/还款日组合都能触发"跨月滚动到 7 月"的同一行为，避免特例；
     *  - 12:00 而非 00:00 选点：让 `2026-06-28 00:00 本地时间 < now`（今天上午触发点
     *    已过），下一触发日 = 下月同日偏移，与"下个月为 7 月"的测试直觉一致。
     *
     * 对应 Unix 毫秒 = `[DateTimeOffset]::Parse("2026-06-28T12:00:00+08:00").ToUnixTimeMilliseconds()`。
     */
    private val NOW: Long = 1_782_619_200_000L

    /** 1 天毫秒。 */
    private val DAY_MS: Long = TimeUnit.DAYS.toMillis(1)

    /** 账单日提前量（T-3）；与 ReminderScheduler.STATEMENT_OFFSET_MS 字节一致。 */
    private val STATEMENT_OFFSET_MS: Long = 3L * 24L * 60L * 60L * 1000L

    /** 还款日提前量（T-1）；与 ReminderScheduler.PAYMENT_OFFSET_MS 字节一致。 */
    private val PAYMENT_OFFSET_MS: Long = 1L * 24L * 60L * 60L * 1000L

    // -------------------------------------------------------------------------
    // 测试 fixture 构造器
    // -------------------------------------------------------------------------

    /**
     * 构造一张信用卡实体（默认活跃 + 信用 + 必要时间戳）。
     *
     * 仅用于 nextCardFiring / computeStatementTrigger / computePaymentTrigger 输入；
     * 其余字段（issuer/last4/creditLimit 等）不参与纯函数计算，随手填占位。
     */
    private fun creditCard(
        id: String = "card-test-1",
        billingDay: Int? = 5,
        dueDay: Int? = 25,
        kind: String = CARD_KIND_CREDIT,
        archived: Boolean = false,
        updatedAt: Long = NOW,
    ): FinanceCardEntity {
        return FinanceCardEntity(
            id = id,
            name = "测试信用卡",
            kind = kind,
            issuer = "测试银行",
            last4 = "0000",
            currency = "CNY",
            creditLimit = null,
            usedLimit = null,
            billingDay = billingDay,
            dueDay = dueDay,
            brand = "visa",
            expiryMonth = 12,
            expiryYear = 2030,
            holder = null,
            note = null,
            icon = null,
            color = "blue",
            archived = archived,
            createdAt = NOW,
            updatedAt = updatedAt,
            schema_version = 1,
            module = "finance",
            type = "card",
            dirty = false,
            deleted = false,
        )
    }

    /**
     * 计算本地日历日"YYYY-MM-DD 当日 0:00 本地时间"的 Unix 毫秒。
     *
     * 与 ReminderScheduler.computeMonthlyTriggerMs 同口径；测试断言时直接
     * 用此函数构造 expected，避免在测试里手工写魔法数字。
     */
    private fun localMidnightMs(year: Int, month: Int, day: Int): Long {
        val zone = java.time.ZoneId.systemDefault()
        return java.time.LocalDate.of(year, month, day)
            .atStartOfDay(zone)
            .toInstant()
            .toEpochMilli()
    }

    // -------------------------------------------------------------------------
    // nextCardFiring 纯函数测试
    // -------------------------------------------------------------------------

    /**
     * 用例 1：信用卡 + 账单日(5) + 还款日(25) → 返回 min(账单 T-3, 还款 T-1)。
     *
     * 锚定：now=2026-06-28；账单日 5 → 本月 7 月 5 日 0:00 - 3d = 7月2日 0:00；
     * 还款日 25 → 本月 6 月 25 日 0:00 - 1d = 6 月 24 日 0:00（在 now 之前→下月）。
     *
     * 6 月 25 日已过 → 推到 7 月 25 日 0:00 - 1d = 7 月 24 日 0:00；
     * 全局最小：7 月 2 日 0:00（账单 T-3）。
     */
    @Test
    fun nextCardFiring_credit_withBothDays_returnsStatementEarlier() {
        val card = creditCard(billingDay = 5, dueDay = 25)
        val result = ReminderScheduler.nextCardFiring(card, NOW)
        assertNotNull(result)
        val expected = localMidnightMs(2026, 7, 5) - STATEMENT_OFFSET_MS
        assertEquals(expected, result)
    }

    /**
     * 用例 2：信用卡 + 仅账单日（dueDay=null）→ 返回账单 T-3。
     *
     * 锚定：billingDay=10；now=2026-06-28 → 本月 6 月 10 日 0:00 - 3d = 6 月 7 日 0:00
     * （已过）→ 推到 7 月 10 日 0:00 - 3d = 7 月 7 日 0:00。
     */
    @Test
    fun nextCardFiring_credit_onlyBillingDay_returnsBillingTrigger() {
        val card = creditCard(billingDay = 10, dueDay = null)
        val result = ReminderScheduler.nextCardFiring(card, NOW)
        assertNotNull(result)
        val expected = localMidnightMs(2026, 7, 10) - STATEMENT_OFFSET_MS
        assertEquals(expected, result)
    }

    /**
     * 用例 3：信用卡 + 仅还款日（billingDay=null）→ 返回还款 T-1。
     *
     * 锚定：dueDay=28；now=2026-06-28 → 本月 6 月 28 日 0:00 - 1d = 6 月 27 日 0:00
     * （已过）→ 推到 7 月 28 日 0:00 - 1d = 7 月 27 日 0:00。
     */
    @Test
    fun nextCardFiring_credit_onlyDueDay_returnsPaymentTrigger() {
        val card = creditCard(billingDay = null, dueDay = 28)
        val result = ReminderScheduler.nextCardFiring(card, NOW)
        assertNotNull(result)
        val expected = localMidnightMs(2026, 7, 28) - PAYMENT_OFFSET_MS
        assertEquals(expected, result)
    }

    /**
     * 用例 4：跨月滚动——账单日 30（仅 6 月有），now=2026-06-28 → 本月 6 月 30 日 0:00
     * - 3d = 6 月 27 日 0:00（已过）→ 推到 7 月 30 日 0:00 - 3d = 7 月 27 日 0:00。
     *
     * 验证：当月触发时刻 ≤ now 时，自动滚到下月同一日偏移。
     */
    @Test
    fun nextCardFiring_credit_billingDayAlreadyPassed_rollsToNextMonth() {
        val card = creditCard(billingDay = 30, dueDay = null)
        val result = ReminderScheduler.nextCardFiring(card, NOW)
        assertNotNull(result)
        val expected = localMidnightMs(2026, 7, 30) - STATEMENT_OFFSET_MS
        assertEquals(expected, result)
    }

    /**
     * 用例 5：归档卡（archived=true）→ null（spec FR-4 / TR-6.1 跳过规则）。
     */
    @Test
    fun nextCardFiring_archivedCard_returnsNull() {
        val card = creditCard(archived = true)
        val result = ReminderScheduler.nextCardFiring(card, NOW)
        assertNull(result)
    }

    /**
     * 用例 6：借记卡（kind="debit"）→ null（spec FR-4 / TR-6.1 仅信用卡触发）。
     */
    @Test
    fun nextCardFiring_debitCard_returnsNull() {
        val card = creditCard(kind = CARD_KIND_DEBIT)
        val result = ReminderScheduler.nextCardFiring(card, NOW)
        assertNull(result)
    }

    /**
     * 用例 7：账单日越界（billingDay=32 → 不在 BILLING_DAY_RANGE=1..31）→ 账单分支
     * 跳过；仅还款分支生效（dueDay=15 → 6 月 15 日 0:00 - 1d = 6 月 14 日已过 →
     * 7 月 14 日 0:00 - 1d）。
     */
    @Test
    fun nextCardFiring_billingDayOutOfRange_skipsBillingBranch() {
        val card = creditCard(billingDay = 32, dueDay = 15)
        val result = ReminderScheduler.nextCardFiring(card, NOW)
        assertNotNull(result)
        val expected = localMidnightMs(2026, 7, 15) - PAYMENT_OFFSET_MS
        assertEquals(expected, result)
    }

    /**
     * 用例 8：账单日 + 还款日皆 null → null（无任何候选）。
     */
    @Test
    fun nextCardFiring_noDays_returnsNull() {
        val card = creditCard(billingDay = null, dueDay = null)
        val result = ReminderScheduler.nextCardFiring(card, NOW)
        assertNull(result)
    }

    // -------------------------------------------------------------------------
    // computeStatementTrigger / computePaymentTrigger 独立测试（跨月滚动）
    // -------------------------------------------------------------------------

    /**
     * 用例 9：computeStatementTrigger 独立验证——信用卡 + 账单日 5，now=2026-06-28 →
     * 7 月 5 日 0:00 - 3d = 7 月 2 日 0:00。
     */
    @Test
    fun computeStatementTrigger_rollsToNextMonth() {
        val card = creditCard(billingDay = 5, dueDay = null)
        val result = ReminderScheduler.computeStatementTrigger(card, NOW)
        assertNotNull(result)
        val expected = localMidnightMs(2026, 7, 5) - STATEMENT_OFFSET_MS
        assertEquals(expected, result)
    }

    /**
     * 用例 10：computePaymentTrigger 独立验证——信用卡 + 还款日 28，now=2026-06-28 →
     * 7 月 28 日 0:00 - 1d = 7 月 27 日 0:00。
     */
    @Test
    fun computePaymentTrigger_rollsToNextMonth() {
        val card = creditCard(billingDay = null, dueDay = 28)
        val result = ReminderScheduler.computePaymentTrigger(card, NOW)
        assertNotNull(result)
        val expected = localMidnightMs(2026, 7, 28) - PAYMENT_OFFSET_MS
        assertEquals(expected, result)
    }

    // =========================================================================
    // B4：selectBestV2Trigger 纯函数测试（subscription / policy / loan）
    // =========================================================================

    /** 一分钟毫秒；reminders 单位换算（与 NextCardFiring.MINUTE_MS 一致）。 */
    private val MINUTE_MS: Long = 60_000L

    /**
     * 构造订阅 Like DTO（默认活跃 + monthly + 未来 7 天续费 + 提前 60 分钟提醒）。
     */
    private fun subLike(
        id: String = "sub-test-1",
        active: Boolean = true,
        nextRenewalTs: Long = NOW + 7L * DAY_MS,
        billingCycle: String = "monthly",
        customDays: Long? = null,
        reminders: List<Long> = listOf(60L),
    ): NextCardFiring.SubscriptionLike = NextCardFiring.SubscriptionLike(
        id = id,
        active = active,
        nextRenewalTs = nextRenewalTs,
        billingCycle = billingCycle,
        customDays = customDays,
        reminders = reminders,
    )

    /**
     * 构造保单 Like DTO（默认活跃 + 未来 7 天到期 + 提前 60 分钟提醒）。
     */
    private fun policyLike(
        id: String = "policy-test-1",
        active: Boolean = true,
        expiryTs: Long = NOW + 7L * DAY_MS,
        reminders: List<Long> = listOf(60L),
    ): NextCardFiring.PolicyLike = NextCardFiring.PolicyLike(
        id = id,
        active = active,
        expiryTs = expiryTs,
        reminders = reminders,
    )

    /**
     * 构造借款 Like DTO（默认 active + 未来 7 天到期 + 到期当日提醒）。
     */
    private fun loanLike(
        id: String = "loan-test-1",
        status: String = "active",
        dueTs: Long = NOW + 7L * DAY_MS,
        reminders: List<Long> = listOf(0L),
    ): NextCardFiring.LoanLike = NextCardFiring.LoanLike(
        id = id,
        status = status,
        dueTs = dueTs,
        reminders = reminders,
    )

    /**
     * 用例 11：单条活跃订阅，续费在未来 2 天、提前 60 分钟提醒 →
     * 候选 = renewal - 60min，kind=subscription_renewal，id 透传。
     */
    @Test
    fun selectBestV2Trigger_singleFutureSubscription_returnsSubscriptionCandidate() {
        val renewal = NOW + 2L * DAY_MS
        val sub = subLike(id = "sub-11", nextRenewalTs = renewal, reminders = listOf(60L))

        val result = ReminderScheduler.selectBestV2Trigger(
            subs = listOf(sub),
            policies = emptyList(),
            loans = emptyList(),
            nowMs = NOW,
        )

        assertNotNull(result)
        assertEquals("sub-11", result!!.refId)
        assertEquals(REF_KIND_SUBSCRIPTION_RENEWAL, result.refKind)
        assertEquals(renewal - 60L * MINUTE_MS, result.triggerTs)
    }

    /**
     * 用例 12：活跃保单未来 5 天到期 + 订阅已过期且 billingCycle 非法
     * （nextRenewalTs=0 远古 + cycle="bad" → rollRenewal=null → 无候选）→
     * 保单胜出；保单 reminders=[0,60] → 最近候选 = expiry - 60min。
     */
    @Test
    fun selectBestV2Trigger_staleSubscriptionPolicyWins_returnsPolicyCandidate() {
        val staleSub = subLike(
            id = "sub-12-stale",
            // 1970 远古时刻；while 循环第一次滚动即遇非法 cycle 返回 null。
            nextRenewalTs = 0L,
            billingCycle = "bad",
            reminders = listOf(60L),
        )
        val expiry = NOW + 5L * DAY_MS
        val policy = policyLike(
            id = "policy-12",
            expiryTs = expiry,
            reminders = listOf(0L, 60L),
        )

        val result = ReminderScheduler.selectBestV2Trigger(
            subs = listOf(staleSub),
            policies = listOf(policy),
            loans = emptyList(),
            nowMs = NOW,
        )

        assertNotNull(result)
        assertEquals("policy-12", result!!.refId)
        assertEquals(REF_KIND_POLICY_EXPIRY, result.refKind)
        assertEquals(expiry - 60L * MINUTE_MS, result.triggerTs)
    }

    /**
     * 用例 13：paid 借款跳过（即便到期更近）；另一条 active 借款未来 3 天 →
     * 选 active 那条。
     */
    @Test
    fun selectBestV2Trigger_paidLoanSkipped_returnsActiveLoan() {
        val paidLoan = loanLike(
            id = "loan-13-paid",
            status = "paid",
            // 仅 1 天后到期；若未正确跳过，它会以更小 ts 胜出。
            dueTs = NOW + 1L * DAY_MS,
            reminders = listOf(0L),
        )
        val activeLoan = loanLike(
            id = "loan-13-active",
            status = "active",
            dueTs = NOW + 3L * DAY_MS,
            reminders = listOf(0L),
        )

        val result = ReminderScheduler.selectBestV2Trigger(
            subs = emptyList(),
            policies = emptyList(),
            loans = listOf(paidLoan, activeLoan),
            nowMs = NOW,
        )

        assertNotNull(result)
        assertEquals("loan-13-active", result!!.refId)
        assertEquals(REF_KIND_LOAN_DUE, result.refKind)
        assertEquals(NOW + 3L * DAY_MS, result.triggerTs)
    }

    /**
     * 用例 14：三类混合 —— 订阅 10 天后、保单 3 天后、借款 1 天后；
     * 全部 reminders=[0] → 全局最小为借款（NOW+1d），kind=loan_due。
     */
    @Test
    fun selectBestV2Trigger_allThreeKinds_returnsGlobalMinimum() {
        val sub = subLike(id = "sub-14", nextRenewalTs = NOW + 10L * DAY_MS, reminders = listOf(0L))
        val policy = policyLike(id = "policy-14", expiryTs = NOW + 3L * DAY_MS, reminders = listOf(0L))
        val loan = loanLike(id = "loan-14", status = "active", dueTs = NOW + 1L * DAY_MS, reminders = listOf(0L))

        val result = ReminderScheduler.selectBestV2Trigger(
            subs = listOf(sub),
            policies = listOf(policy),
            loans = listOf(loan),
            nowMs = NOW,
        )

        assertNotNull(result)
        assertEquals("loan-14", result!!.refId)
        assertEquals(REF_KIND_LOAN_DUE, result.refKind)
        assertEquals(NOW + 1L * DAY_MS, result.triggerTs)
    }

    /**
     * 用例 15：订阅 active=false、保单 active=false（借款列表为空）→ null。
     */
    @Test
    fun selectBestV2Trigger_allInactive_returnsNull() {
        val sub = subLike(id = "sub-15", active = false)
        val policy = policyLike(id = "policy-15", active = false)

        val result = ReminderScheduler.selectBestV2Trigger(
            subs = listOf(sub),
            policies = listOf(policy),
            loans = emptyList(),
            nowMs = NOW,
        )

        assertNull(result)
    }

    /**
     * 用例 16：三类全空列表 → null（无任何候选；rebuildChain 走 cancel/其他来源分支）。
     */
    @Test
    fun selectBestV2Trigger_allEmptyLists_returnsNull() {
        val result = ReminderScheduler.selectBestV2Trigger(
            subs = emptyList(),
            policies = emptyList(),
            loans = emptyList(),
            nowMs = NOW,
        )

        assertNull(result)
    }

    /**
     * 用例 17：三类候选同毫秒（基准均为 NOW+2d、reminders=[0]）→ 按稳定
     * tie-break 序 subscription(2) 胜出（全局 rank 表 event<card<sub<policy<loan），
     * 避免 DAO 返回顺序漂移导致闹钟种类跳变。
     */
    @Test
    fun selectBestV2Trigger_sameTimestampTieBreak_subscriptionWins() {
        val sameTs = NOW + 2L * DAY_MS
        val sub = subLike(id = "sub-17", nextRenewalTs = sameTs, reminders = listOf(0L))
        val policy = policyLike(id = "policy-17", expiryTs = sameTs, reminders = listOf(0L))
        val loan = loanLike(id = "loan-17", status = "active", dueTs = sameTs, reminders = listOf(0L))

        val result = ReminderScheduler.selectBestV2Trigger(
            subs = listOf(sub),
            policies = listOf(policy),
            loans = listOf(loan),
            nowMs = NOW,
        )

        assertNotNull(result)
        assertEquals("sub-17", result!!.refId)
        assertEquals(REF_KIND_SUBSCRIPTION_RENEWAL, result.refKind)
        assertEquals(sameTs, result.triggerTs)
    }
}