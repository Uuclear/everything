// ============================================================================
// nextSubscriptionRenewal 纯函数单元测试（stage5-finance-v2 / Task 4 / TR-4.1）
// ============================================================================
//
// 验证目标（≥6 用例, 其中 ≥4 条直接来自共享 fixture）：
//   1. fixture SHA-256 硬编码守护（TR-4.6 双端字节级一致核验）；
//   2. 订阅 fixture 用例数守护（≥8 条）+ 全量数据驱动断言；
//   3. 关键 fixture 场景显式断言（monthly 当月未来取最小提醒 / 过期滚下月 /
//      yearly 滚一年）；
//   4. 边界负例显式断言（提前一天候选已过时退回 r=0 / 月末 java.time 裁剪链 /
//      custom_days 缺 customDays / 未知周期）；
//   5. 纯函数幂等性。
//
// 共享 fixture（__fixtures__/next-v2-firing-cases.json, 与 Web 镜像同 SHA-256）。
// 期望毫秒均为 CST 口径独立手工精算值, 测试只加载断言, 不用同一算法自证。
//
// 关联:
//   - android/.../finance/NextCardFiring.kt#nextSubscriptionRenewal（被测目标）
//   - android/.../finance/__fixtures__/next-v2-firing-cases.json（共享 fixture）
// ============================================================================

package com.everything.eve.finance

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.security.MessageDigest

/**
 * nextSubscriptionRenewal JUnit 4 单元测试。
 *
 * fixture 结构：{ version, tz, nowMs, cases: [ {name, kind, input, expected} ] }，
 * 本类仅取 kind=="subscription" 的用例；expected 为数字或 null。
 */
class NextSubscriptionRenewalTest {

    companion object {
        /**
         * fixture 文件 SHA-256（小写 hex）—— TR-4.6 双端一致性硬守护。
         * 任何一端改动 fixture 都会导致本断言失败, 必须双端同步后更新此常量。
         */
        const val FIXTURE_SHA256 = "c4ea61f7cc17c177ec93fc8cafc4b0cb616d8267ca79eb77bc17440f26edcb76"

        /** 锁定 JVM 默认时区到 CST（UTC+8），与 NextCardFiringTest 同款。 */
        @BeforeClass
        @JvmStatic
        fun lockTimezone() {
            val cst = java.util.TimeZone.getTimeZone("Asia/Shanghai")
            java.util.TimeZone.setDefault(cst)
            System.setProperty("user.timezone", "Asia/Shanghai")
        }
    }

    /** 单条订阅用例（fixture 真理源映射）。 */
    private data class SubCase(
        val name: String,
        val sub: NextCardFiring.SubscriptionLike,
        val expected: Long?,
    )

    /** classpath 首选 + 文件系统兜底（照搬 NextCardFiringTest 多候选路径加载器）。 */
    private val fixtureBytes: ByteArray by lazy { loadFixtureBytes() }
    private val rootJson: JSONObject by lazy { JSONObject(String(fixtureBytes, Charsets.UTF_8)) }
    private val nowMs: Long by lazy { rootJson.getLong("nowMs") }
    private val allCases: JSONArray by lazy { rootJson.getJSONArray("cases") }
    private val subCases: List<SubCase> by lazy { parseSubCases() }

    private fun loadFixtureBytes(): ByteArray {
        val classpathPath = "finance/__fixtures__/next-v2-firing-cases.json"
        val cpResource = this::class.java.classLoader?.getResource(classpathPath)
        if (cpResource != null) {
            return cpResource.openStream().use { it.readBytes() }
        }
        val candidates = listOf(
            "src/test/java/com/everything/eve/finance/__fixtures__/next-v2-firing-cases.json",
            "android/app/src/test/java/com/everything/eve/finance/__fixtures__/next-v2-firing-cases.json",
            "app/src/test/java/com/everything/eve/finance/__fixtures__/next-v2-firing-cases.json",
            "d:/github/everything/everything/android/app/src/test/java/com/everything/eve/finance/__fixtures__/next-v2-firing-cases.json",
            "D:/github/everything/everything/android/app/src/test/java/com/everything/eve/finance/__fixtures__/next-v2-firing-cases.json",
            "D:\\github\\everything\\everything\\android\\app\\src\\test\\java\\com\\everything\\eve\\finance\\__fixtures__\\next-v2-firing-cases.json",
        )
        val file = candidates
            .map { java.io.File(it) }
            .firstOrNull { it.exists() }
            ?: throw IllegalStateException(
                "fixture 资源缺失: 尝试路径 = [$classpathPath classpath, ${candidates.joinToString()}]"
            )
        return file.readBytes()
    }

    private fun parseSubCases(): List<SubCase> {
        val out = mutableListOf<SubCase>()
        for (i in 0 until allCases.length()) {
            val c = allCases.getJSONObject(i)
            if (c.getString("kind") != "subscription") continue
            val inp = c.getJSONObject("input")
            val sub = NextCardFiring.SubscriptionLike(
                id = inp.getString("id"),
                active = inp.getBoolean("active"),
                nextRenewalTs = inp.getLong("nextRenewalTs"),
                billingCycle = inp.getString("billingCycle"),
                customDays = if (inp.isNull("customDays")) null else inp.getLong("customDays"),
                reminders = inp.getJSONArray("reminders").toLongList(),
            )
            val expected = if (c.isNull("expected")) null else c.getLong("expected")
            out.add(SubCase(c.getString("name"), sub, expected))
        }
        return out
    }

    // ============================================================================
    // 1. fixture 完整性守护（SHA-256 + 用例数）
    // ============================================================================

    @Test
    fun fixture_sha256_matchesHardcodedConstant() {
        val digest = MessageDigest.getInstance("SHA-256").digest(fixtureBytes)
        val actual = digest.joinToString("") { "%02x".format(it) }
        assertEquals(
            "fixture SHA-256 与硬编码常量不一致（双端镜像可能失步, 见 TR-4.6）",
            FIXTURE_SHA256, actual
        )
    }

    @Test
    fun fixture_subscriptionHasAtLeastEightCases() {
        assertTrue(
            "fixture 总用例数应不少于 12 条, 实际 = ${allCases.length()}",
            allCases.length() >= 12
        )
        assertTrue(
            "subscription 用例应不少于 8 条, 实际 = ${subCases.size}",
            subCases.size >= 8
        )
    }

    // ============================================================================
    // 2. fixture 数据驱动（8 条订阅用例全量断言）
    // ============================================================================

    @Test
    fun fixture_driven_allSubscriptionCasesPass() {
        for (tc in subCases) {
            val actual = NextCardFiring.nextSubscriptionRenewal(tc.sub, nowMs)
            if (tc.expected == null) {
                assertNull("case[${tc.name}] 期望返回 null, 实际 = $actual", actual)
            } else {
                assertNotNull("case[${tc.name}] 期望非 null, 实际 = null", actual)
                assertEquals("case[${tc.name}] 下一续费提醒时刻不一致", tc.expected, actual)
            }
        }
    }

    // ============================================================================
    // 3. 关键 fixture 场景具名显式断言（≥3 条直接来自 fixture）
    // ============================================================================

    @Test
    fun fixture_case_monthlyFuture_picksSmallestReminder() {
        val tc = subCases.first { it.name == "sub_monthly_future_pickMinReminder" }
        // renewal=2026-06-30 09:00, r=0 与 r=1440 候选均未来, 取最小 = 06-29 09:00
        assertEquals(1782694800000L, NextCardFiring.nextSubscriptionRenewal(tc.sub, nowMs))
    }

    @Test
    fun fixture_case_monthlyPast_rollsToNextMonthPreservingClock() {
        val tc = subCases.first { it.name == "sub_monthly_past_rollsToNextMonth" }
        // 06-15 08:30 已过 → 滚到 2026-07-15 08:30（日历加月, 保留 08:30 时分秒）
        assertEquals(1784075400000L, NextCardFiring.nextSubscriptionRenewal(tc.sub, nowMs))
    }

    @Test
    fun fixture_case_yearlyPast_rollsOneCalendarYear() {
        val tc = subCases.first { it.name == "sub_yearly_rollsOneYear" }
        // 2025-12-31 23:30 → 2026-12-31 23:30（跨年, 保留时分秒）
        assertEquals(1798731000000L, NextCardFiring.nextSubscriptionRenewal(tc.sub, nowMs))
    }

    @Test
    fun fixture_case_quarterlyPast_rollsThreeCalendarMonths() {
        val tc = subCases.first { it.name == "sub_quarterly_rollsThreeMonths" }
        // 2026-05-20 10:00 → 2026-08-20 10:00, r=60 → 09:00
        assertEquals(1787187600000L, NextCardFiring.nextSubscriptionRenewal(tc.sub, nowMs))
    }

    // ============================================================================
    // 4. 边界与负例（fixture 外显式构造）
    // ============================================================================

    /**
     * 续费时刻仅比 now 晚 1 小时：r=0 候选（13:00）未来, r=1440 候选（前一天 13:00）
     * 已过 → 过滤后最小未来候选即续费时刻本身。
     */
    @Test
    fun renewalOneHourAway_dailyReminderAlreadyPast_returnsRenewalItself() {
        val sub = NextCardFiring.SubscriptionLike(
            id = "sub-edge-1",
            active = true,
            // 2026-06-28 13:00 CST = now + 1 小时
            nextRenewalTs = 1782622800000L,
            billingCycle = "monthly",
            customDays = null,
            reminders = listOf(0L, 1440L),
        )
        assertEquals(1782622800000L, NextCardFiring.nextSubscriptionRenewal(sub, nowMs))
    }

    /**
     * 月末日期按月滚动链：1970 式异常数据外的真实月末裁剪。
     * 2026-01-31 10:00 monthly：01-31 → 02-28 → 03-28 → 04-28 → 05-28 → 06-28 10:00
     * （06-28 10:00 仍 ≤ now 12:00）→ 再滚一次 07-28 10:00 才进未来。
     */
    @Test
    fun monthEndDate_javaTimeClampsEachRoll_untilFuture() {
        val sub = NextCardFiring.SubscriptionLike(
            id = "sub-edge-2",
            active = true,
            // 2026-01-31 10:00 CST
            nextRenewalTs = 1769824800000L,
            billingCycle = "monthly",
            customDays = null,
            reminders = listOf(0L),
        )
        // 期望 2026-07-28 10:00 CST（共滚动 6 次, 未触上限）
        assertEquals(1785204000000L, NextCardFiring.nextSubscriptionRenewal(sub, nowMs))
    }

    /** custom_days 缺 customDays 或 customDays<=0 且基准已过时 → null（数据非法不提醒）。 */
    @Test
    fun customDaysMissingOrNonPositive_returnsNull() {
        val base = NextCardFiring.SubscriptionLike(
            id = "sub-edge-3",
            active = true,
            // 2026-06-20 12:00 CST（已过, 必然触发滚动分支）
            nextRenewalTs = 1781928000000L,
            billingCycle = "custom_days",
            customDays = null,
            reminders = listOf(0L),
        )
        assertNull("customDays=null 时应返回 null", NextCardFiring.nextSubscriptionRenewal(base, nowMs))
        assertNull(
            "customDays=0 时应返回 null",
            NextCardFiring.nextSubscriptionRenewal(base.copy(customDays = 0L), nowMs)
        )
        assertNull(
            "customDays=-5 时应返回 null",
            NextCardFiring.nextSubscriptionRenewal(base.copy(customDays = -5L), nowMs)
        )
    }

    /** 未知 billingCycle 且基准已过 → null（防御性忽略, 不抛异常）。 */
    @Test
    fun unknownBillingCycle_returnsNull() {
        val sub = NextCardFiring.SubscriptionLike(
            id = "sub-edge-4",
            active = true,
            nextRenewalTs = 1781928000000L,
            billingCycle = "weekly",
            customDays = null,
            reminders = listOf(0L),
        )
        assertNull("未知周期应返回 null", NextCardFiring.nextSubscriptionRenewal(sub, nowMs))
    }

    /** 纯函数幂等：相同输入多次调用结果一致。 */
    @Test
    fun nextSubscriptionRenewal_isIdempotent() {
        val sub = NextCardFiring.SubscriptionLike(
            id = "sub-idem",
            active = true,
            nextRenewalTs = 1781483400000L,
            billingCycle = "monthly",
            customDays = null,
            reminders = listOf(0L, 1440L),
        )
        val a = NextCardFiring.nextSubscriptionRenewal(sub, nowMs)
        val b = NextCardFiring.nextSubscriptionRenewal(sub, nowMs)
        assertEquals(a, b)
    }
}

/** org.json.JSONArray → List<Long>（文件内私有, 不污染生产代码）。 */
private fun JSONArray.toLongList(): List<Long> = List(length()) { getLong(it) }
