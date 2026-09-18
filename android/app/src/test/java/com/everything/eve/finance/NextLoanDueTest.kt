// ============================================================================
// nextLoanDue 纯函数单元测试（stage5-finance-v2 / Task 4 / TR-4.1）
// ============================================================================
//
// 验证目标（≥6 用例, 其中 ≥3 条直接来自共享 fixture）：
//   1. fixture SHA-256 硬编码守护（TR-4.6 双端字节级一致核验）；
//   2. 借款 fixture 用例数守护（≥3 条）+ 全量数据驱动断言；
//   3. 关键 fixture 场景具名断言（未来到期取最小 / paid 不提醒 / 全过期 null）；
//   4. 边界显式断言（overdue / partially_paid 状态仍提醒 / 空 reminders null /
//      未知状态按非 paid 处理）；
//   5. 纯函数幂等性。
//
// 共享 fixture（__fixtures__/next-v2-firing-cases.json, 与 Web 镜像同 SHA-256）。
//
// 关联:
//   - android/.../finance/NextCardFiring.kt#nextLoanDue（被测目标）
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
 * nextLoanDue JUnit 4 单元测试。
 *
 * 借款到期是一次性事件（不滚动）；仅 status=="paid" 早退,
 * active / partially_paid / overdue 均继续提醒。
 */
class NextLoanDueTest {

    companion object {
        /** fixture 文件 SHA-256（小写 hex, 与订阅/保单测试同一文件, TR-4.6）。 */
        const val FIXTURE_SHA256 = "c4ea61f7cc17c177ec93fc8cafc4b0cb616d8267ca79eb77bc17440f26edcb76"

        @BeforeClass
        @JvmStatic
        fun lockTimezone() {
            val cst = java.util.TimeZone.getTimeZone("Asia/Shanghai")
            java.util.TimeZone.setDefault(cst)
            System.setProperty("user.timezone", "Asia/Shanghai")
        }
    }

    /** 单条借款用例（fixture 真理源映射；此处 LoanLike 为 NextCardFiring.LoanLike）。 */
    private data class LoanFireCase(
        val name: String,
        val loan: NextCardFiring.LoanLike,
        val expected: Long?,
    )

    private val fixtureBytes: ByteArray by lazy { loadFixtureBytes() }
    private val rootJson: JSONObject by lazy { JSONObject(String(fixtureBytes, Charsets.UTF_8)) }
    private val nowMs: Long by lazy { rootJson.getLong("nowMs") }
    private val allCases: JSONArray by lazy { rootJson.getJSONArray("cases") }
    private val loanCases: List<LoanFireCase> by lazy { parseLoanCases() }

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

    private fun parseLoanCases(): List<LoanFireCase> {
        val out = mutableListOf<LoanFireCase>()
        for (i in 0 until allCases.length()) {
            val c = allCases.getJSONObject(i)
            if (c.getString("kind") != "loan") continue
            val inp = c.getJSONObject("input")
            val loan = NextCardFiring.LoanLike(
                id = inp.getString("id"),
                status = inp.getString("status"),
                dueTs = inp.getLong("dueTs"),
                reminders = inp.getJSONArray("reminders").toLongList(),
            )
            val expected = if (c.isNull("expected")) null else c.getLong("expected")
            out.add(LoanFireCase(c.getString("name"), loan, expected))
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
    fun fixture_loanHasAtLeastThreeCases() {
        assertTrue(
            "loan 用例应不少于 3 条, 实际 = ${loanCases.size}",
            loanCases.size >= 3
        )
    }

    // ============================================================================
    // 2. fixture 数据驱动（3 条借款用例全量断言）
    // ============================================================================

    @Test
    fun fixture_driven_allLoanCasesPass() {
        for (tc in loanCases) {
            val actual = NextCardFiring.nextLoanDue(tc.loan, nowMs)
            if (tc.expected == null) {
                assertNull("case[${tc.name}] 期望返回 null, 实际 = $actual", actual)
            } else {
                assertNotNull("case[${tc.name}] 期望非 null, 实际 = null", actual)
                assertEquals("case[${tc.name}] 借款到期提醒时刻不一致", tc.expected, actual)
            }
        }
    }

    // ============================================================================
    // 3. 关键 fixture 场景具名显式断言（≥3 条直接来自 fixture）
    // ============================================================================

    @Test
    fun fixture_case_dueFuture_picksSmallestReminder() {
        val tc = loanCases.first { it.name == "loan_dueFuture_pickMinReminder" }
        // due=2026-07-05 10:00, r=0/1440 全未来, 最小 = 07-04 10:00
        assertEquals(1783130400000L, NextCardFiring.nextLoanDue(tc.loan, nowMs))
    }

    @Test
    fun fixture_case_paid_returnsNull() {
        val tc = loanCases.first { it.name == "loan_paid_returnsNull" }
        assertNull(NextCardFiring.nextLoanDue(tc.loan, nowMs))
    }

    @Test
    fun fixture_case_overdueButAllExpired_returnsNull() {
        val tc = loanCases.first { it.name == "loan_overdue_allExpired_returnsNull" }
        // 状态 overdue 本应提醒, 但到期日已过且借款不滚动 → null
        assertNull(NextCardFiring.nextLoanDue(tc.loan, nowMs))
    }

    // ============================================================================
    // 4. 边界与负例（fixture 外显式构造）
    // ============================================================================

    /** overdue 状态 + 未来到期日 → 仍正常提醒（逾期未还更应提醒）。 */
    @Test
    fun overdueStatus_withFutureDue_stillFires() {
        val loan = NextCardFiring.LoanLike(
            id = "loan-edge-1",
            status = "overdue",
            // 2026-06-29 08:00 CST（未来）
            dueTs = 1782691200000L,
            reminders = listOf(0L),
        )
        assertEquals(1782691200000L, NextCardFiring.nextLoanDue(loan, nowMs))
    }

    /** partially_paid 状态 + 未来到期日 → 仍正常提醒（未结清）。 */
    @Test
    fun partiallyPaidStatus_withFutureDue_stillFires() {
        val loan = NextCardFiring.LoanLike(
            id = "loan-edge-2",
            status = "partially_paid",
            dueTs = 1782691200000L,
            reminders = listOf(0L, 60L),
        )
        // r=60 候选 = 06-29 07:00（到期前 1 小时）, 最小未来候选
        assertEquals(1782687600000L, NextCardFiring.nextLoanDue(loan, nowMs))
    }

    /** reminders 为空 → 即使 active 且到期日在未来也返回 null。 */
    @Test
    fun emptyReminders_returnsNull() {
        val loan = NextCardFiring.LoanLike(
            id = "loan-edge-3",
            status = "active",
            dueTs = 1783216800000L,
            reminders = emptyList(),
        )
        assertNull("空 reminders 应返回 null", NextCardFiring.nextLoanDue(loan, nowMs))
    }

    /** 未知状态字符串（非 paid）防御性按"未结清"处理, 仍参与提醒。 */
    @Test
    fun unknownStatusOtherThanPaid_stillFires() {
        val loan = NextCardFiring.LoanLike(
            id = "loan-edge-4",
            status = "renegotiated",
            dueTs = 1782691200000L,
            reminders = listOf(0L),
        )
        assertEquals(1782691200000L, NextCardFiring.nextLoanDue(loan, nowMs))
    }

    /** 纯函数幂等：相同输入多次调用结果一致。 */
    @Test
    fun nextLoanDue_isIdempotent() {
        val loan = NextCardFiring.LoanLike(
            id = "loan-idem",
            status = "active",
            dueTs = 1783216800000L,
            reminders = listOf(0L, 1440L),
        )
        val a = NextCardFiring.nextLoanDue(loan, nowMs)
        val b = NextCardFiring.nextLoanDue(loan, nowMs)
        assertEquals(a, b)
    }
}

/** org.json.JSONArray → List<Long>（文件内私有, 不污染生产代码）。 */
private fun JSONArray.toLongList(): List<Long> = List(length()) { getLong(it) }
