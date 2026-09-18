// ============================================================================
// nextPolicyExpiry 纯函数单元测试（stage5-finance-v2 / Task 4 / TR-4.1）
// ============================================================================
//
// 验证目标（≥6 用例, 其中 ≥3 条直接来自共享 fixture）：
//   1. fixture SHA-256 硬编码守护（TR-4.6 双端字节级一致核验）；
//   2. 保单 fixture 用例数守护（≥4 条）+ 全量数据驱动断言；
//   3. 关键 fixture 场景具名断言（多提醒取最小 / 全过期 null）；
//   4. 边界负例显式断言（部分提醒已过退回基准时刻 / 负偏移忽略）；
//   5. 纯函数幂等性。
//
// 共享 fixture（__fixtures__/next-v2-firing-cases.json, 与 Web 镜像同 SHA-256）。
//
// 关联:
//   - android/.../finance/NextCardFiring.kt#nextPolicyExpiry（被测目标）
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
 * nextPolicyExpiry JUnit 4 单元测试。
 *
 * 保单到期是一次性事件（不滚动）；本类与 NextSubscriptionRenewalTest 共享
 * 同一 fixture 文件, 仅过滤 kind=="policy" 的用例。
 */
class NextPolicyExpiryTest {

    companion object {
        /** fixture 文件 SHA-256（小写 hex, 与订阅测试同一文件同一常量, TR-4.6）。 */
        const val FIXTURE_SHA256 = "c4ea61f7cc17c177ec93fc8cafc4b0cb616d8267ca79eb77bc17440f26edcb76"

        @BeforeClass
        @JvmStatic
        fun lockTimezone() {
            val cst = java.util.TimeZone.getTimeZone("Asia/Shanghai")
            java.util.TimeZone.setDefault(cst)
            System.setProperty("user.timezone", "Asia/Shanghai")
        }
    }

    /** 单条保单用例（fixture 真理源映射）。 */
    private data class PolicyCase(
        val name: String,
        val policy: NextCardFiring.PolicyLike,
        val expected: Long?,
    )

    private val fixtureBytes: ByteArray by lazy { loadFixtureBytes() }
    private val rootJson: JSONObject by lazy { JSONObject(String(fixtureBytes, Charsets.UTF_8)) }
    private val nowMs: Long by lazy { rootJson.getLong("nowMs") }
    private val allCases: JSONArray by lazy { rootJson.getJSONArray("cases") }
    private val policyCases: List<PolicyCase> by lazy { parsePolicyCases() }

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

    private fun parsePolicyCases(): List<PolicyCase> {
        val out = mutableListOf<PolicyCase>()
        for (i in 0 until allCases.length()) {
            val c = allCases.getJSONObject(i)
            if (c.getString("kind") != "policy") continue
            val inp = c.getJSONObject("input")
            val policy = NextCardFiring.PolicyLike(
                id = inp.getString("id"),
                active = inp.getBoolean("active"),
                expiryTs = inp.getLong("expiryTs"),
                reminders = inp.getJSONArray("reminders").toLongList(),
            )
            val expected = if (c.isNull("expected")) null else c.getLong("expected")
            out.add(PolicyCase(c.getString("name"), policy, expected))
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
    fun fixture_policyHasAtLeastFourCases() {
        assertTrue(
            "policy 用例应不少于 4 条, 实际 = ${policyCases.size}",
            policyCases.size >= 4
        )
    }

    // ============================================================================
    // 2. fixture 数据驱动（4 条保单用例全量断言）
    // ============================================================================

    @Test
    fun fixture_driven_allPolicyCasesPass() {
        for (tc in policyCases) {
            val actual = NextCardFiring.nextPolicyExpiry(tc.policy, nowMs)
            if (tc.expected == null) {
                assertNull("case[${tc.name}] 期望返回 null, 实际 = $actual", actual)
            } else {
                assertNotNull("case[${tc.name}] 期望非 null, 实际 = null", actual)
                assertEquals("case[${tc.name}] 保单到期提醒时刻不一致", tc.expected, actual)
            }
        }
    }

    // ============================================================================
    // 3. 关键 fixture 场景具名显式断言（≥3 条直接来自 fixture）
    // ============================================================================

    @Test
    fun fixture_case_futureExpiry_picksSmallestReminder() {
        val tc = policyCases.first { it.name == "pol_future_expiry_pickMinReminder" }
        // expiry=2026-07-15 00:00, r=0/1440/4320 全未来, 最小 = 07-12 00:00
        assertEquals(1783785600000L, NextCardFiring.nextPolicyExpiry(tc.policy, nowMs))
    }

    @Test
    fun fixture_case_inactive_returnsNull() {
        val tc = policyCases.first { it.name == "pol_inactive_returnsNull" }
        assertNull(NextCardFiring.nextPolicyExpiry(tc.policy, nowMs))
    }

    @Test
    fun fixture_case_allExpired_returnsNull() {
        val tc = policyCases.first { it.name == "pol_allExpired_returnsNull" }
        // expiry=2026-01-01 00:00 已过, 保单不滚动 → null
        assertNull(NextCardFiring.nextPolicyExpiry(tc.policy, nowMs))
    }

    // ============================================================================
    // 4. 边界与负例（fixture 外显式构造）
    // ============================================================================

    /**
     * 到期时刻仅比 now 晚 6 小时：r=0 候选（当日 18:00）未来, r=1440 候选
     * （前一天 18:00）已过 → 最小未来候选 = 到期时刻本身。
     */
    @Test
    fun expirySixHoursAway_dailyReminderAlreadyPast_returnsExpiryItself() {
        val policy = NextCardFiring.PolicyLike(
            id = "pol-edge-1",
            active = true,
            // 2026-06-28 18:00 CST（now 12:00 + 6 小时）
            expiryTs = 1782640800000L,
            reminders = listOf(0L, 1440L),
        )
        assertEquals(1782640800000L, NextCardFiring.nextPolicyExpiry(policy, nowMs))
    }

    /** 负分钟偏移（语义为到期后提醒）防御性忽略, 仅 r=0 候选生效。 */
    @Test
    fun negativeReminderOffset_isIgnored() {
        val policy = NextCardFiring.PolicyLike(
            id = "pol-edge-2",
            active = true,
            expiryTs = 1782640800000L,
            // -60 会算出 expiry+1h, 但负偏移必须被忽略；有效值只有 r=0
            reminders = listOf(-60L, 0L),
        )
        assertEquals(1782640800000L, NextCardFiring.nextPolicyExpiry(policy, nowMs))
    }

    /** 候选恰好等于 nowMs 视为已过（严格 > 口径）→ 无未来候选 null。 */
    @Test
    fun candidateExactlyAtNow_isTreatedAsPast_returnsNull() {
        val policy = NextCardFiring.PolicyLike(
            id = "pol-edge-3",
            active = true,
            // expiry = now + 60 分钟；r=60 候选恰好 = nowMs, r=0 未来, 应返回 r=0
            expiryTs = nowMs + 60_000L,
            reminders = listOf(60L, 0L),
        )
        // r=60 候选 == nowMs 被过滤；r=0 = expiry 未来 → 返回 expiry
        assertEquals(nowMs + 60_000L, NextCardFiring.nextPolicyExpiry(policy, nowMs))

        // 仅含 r=60 时, 唯一候选恰好等于 nowMs → null
        val onlyAtNow = policy.copy(reminders = listOf(60L))
        assertNull(
            "候选恰好等于 nowMs 时应按已过处理返回 null",
            NextCardFiring.nextPolicyExpiry(onlyAtNow, nowMs)
        )
    }

    /** 纯函数幂等：相同输入多次调用结果一致。 */
    @Test
    fun nextPolicyExpiry_isIdempotent() {
        val policy = NextCardFiring.PolicyLike(
            id = "pol-idem",
            active = true,
            expiryTs = 1784044800000L,
            reminders = listOf(0L, 1440L, 4320L),
        )
        val a = NextCardFiring.nextPolicyExpiry(policy, nowMs)
        val b = NextCardFiring.nextPolicyExpiry(policy, nowMs)
        assertEquals(a, b)
    }
}

/** org.json.JSONArray → List<Long>（文件内私有, 不污染生产代码）。 */
private fun JSONArray.toLongList(): List<Long> = List(length()) { getLong(it) }
