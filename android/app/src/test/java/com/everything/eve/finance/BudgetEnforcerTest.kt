// ============================================================================
// BudgetEnforcer 纯函数单元测试（stage5-finance-v2 / Task 6 / B6 第一批）
// ============================================================================
//
// 验证目标：
//   1. fixture SHA-256 硬编码守护（双端字节级一致，与 Web
//      budgetEnforcer.spec.ts 同 SHA）；
//   2. fixture cases 段全部 20 条数据驱动，逐条比对 checkTx 结果的
//      level / budgetId / category / currency / spent / incoming /
//      projected / limit / usedPct / thresholdPct 全字段；
//   3. periodBucket 月 / 周 / 年 / custom / 有效期外 / 相邻周桶 6 条边界；
//   4. checkTx 对非 expense 流水直接返回 OK_EMPTY；
//   5. evaluate 三档边界直测（79 OK / 80 WARNING / 100 BLOCK）。
//
// 关联:
//   - android/.../finance/BudgetEnforcer.kt（被测目标）
//   - android/.../finance/__fixtures__/budget-enforcer-cases.json（共享 fixture）
// ============================================================================

package com.everything.eve.finance

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.BeforeClass
import org.junit.Test
import java.security.MessageDigest

/**
 * BudgetEnforcer.checkTx / periodBucket / evaluate JUnit 4 单元测试。
 */
class BudgetEnforcerTest {

    companion object {
        /** fixture 文件 SHA-256（小写 hex）—— 双端一致性硬守护。 */
        const val FIXTURE_SHA256 = "7e568077870c608172bd6a0b643aaf50fdbf15c8a571512871db113900342e44"

        /** 锚点时刻：2026-06-28 12:00:00 CST（周日）。 */
        const val ANCHOR_NOW_MS = 1_782_619_200_000L

        @BeforeClass
        @JvmStatic
        fun lockTimezone() {
            // 分桶口径固定 Asia/Shanghai（UTC+8），避免随运行机器默认时区漂移。
            val cst = java.util.TimeZone.getTimeZone("Asia/Shanghai")
            java.util.TimeZone.setDefault(cst)
            System.setProperty("user.timezone", "Asia/Shanghai")
        }
    }

    // ========================================================================
    // fixture 加载与解析
    // ========================================================================

    private val fixtureBytes: ByteArray by lazy { loadFixtureBytes() }
    private val rootJson: JSONObject by lazy { JSONObject(String(fixtureBytes, Charsets.UTF_8)) }

    /** 单条用例期望（字段名与 fixture expect 段 snake_case 对应）。 */
    private data class Expect(
        val level: String,
        val usedPct: Int,
        val category: String,
        val currency: String,
        val budgetId: String?,
        val spentMinor: Long,
        val incomingMinor: Long,
        val projectedMinor: Long,
        val limitMinor: Long,
        val thresholdPct: Int,
    )

    private fun loadFixtureBytes(): ByteArray {
        val classpathPath = "finance/__fixtures__/budget-enforcer-cases.json"
        val cpResource = this::class.java.classLoader?.getResource(classpathPath)
        if (cpResource != null) {
            return cpResource.openStream().use { it.readBytes() }
        }
        val candidates = listOf(
            "src/test/java/com/everything/eve/finance/__fixtures__/budget-enforcer-cases.json",
            "android/app/src/test/java/com/everything/eve/finance/__fixtures__/budget-enforcer-cases.json",
            "app/src/test/java/com/everything/eve/finance/__fixtures__/budget-enforcer-cases.json",
            "d:/github/everything/everything/android/app/src/test/java/com/everything/eve/finance/__fixtures__/budget-enforcer-cases.json",
            "D:/github/everything/everything/android/app/src/test/java/com/everything/eve/finance/__fixtures__/budget-enforcer-cases.json",
        )
        val file = candidates
            .map { java.io.File(it) }
            .firstOrNull { it.exists() }
            ?: throw IllegalStateException(
                "fixture 资源缺失: 尝试路径 = [$classpathPath classpath, ${candidates.joinToString()}]"
            )
        return file.readBytes()
    }

    /** JSON 对象 → BudgetTxLike；id 缺失按空串容错（incoming 允许省略 id）。 */
    private fun parseTx(o: JSONObject): BudgetTxLike = BudgetTxLike(
        id = if (o.isNull("id") || !o.has("id")) "" else o.optString("id", ""),
        kind = o.optString("kind", ""),
        amountMinor = o.optString("amount_minor", ""),
        category = o.optString("category", ""),
        currency = o.optString("currency", ""),
        occurredAt = o.optLong("occurred_at", 0L),
    )

    private fun parseTxArray(arr: JSONArray): List<BudgetTxLike> =
        List(arr.length()) { i -> parseTx(arr.getJSONObject(i)) }

    /** rate_table 段非 null 时重新序列化后走 RateTables.parse 入口（顺带验证 parse）。 */
    private fun parseRateTable(o: JSONObject): RateTable? =
        if (o.isNull("rate_table")) null else RateTables.parse(o.getJSONObject("rate_table").toString())

    private fun parseExpect(o: JSONObject): Expect = Expect(
        level = o.getString("level"),
        usedPct = o.getInt("used_pct"),
        category = o.getString("category"),
        currency = o.getString("currency"),
        budgetId = if (o.isNull("budget_id")) null else o.getString("budget_id"),
        spentMinor = o.getLong("spent_minor"),
        incomingMinor = o.getLong("incoming_minor"),
        projectedMinor = o.getLong("projected_minor"),
        limitMinor = o.getLong("limit_minor"),
        thresholdPct = o.getInt("threshold_pct"),
    )

    // ========================================================================
    // 1. fixture 完整性守护
    // ========================================================================

    @Test
    fun fixture_sha256_matchesHardcodedConstant() {
        val digest = MessageDigest.getInstance("SHA-256").digest(fixtureBytes)
        val actual = digest.joinToString("") { "%02x".format(it) }
        assertEquals(
            "fixture SHA-256 与硬编码常量不一致（双端镜像可能失步）",
            FIXTURE_SHA256, actual,
        )
    }

    @Test
    fun fixture_hasAtLeast18Cases() {
        val count = rootJson.getJSONArray("cases").length()
        assertEquals("fixture anchor_now_ms 与契约锚点不一致", ANCHOR_NOW_MS, rootJson.getLong("anchor_now_ms"))
        org.junit.Assert.assertTrue("cases 应不少于 18 条, 实际 = $count", count >= 18)
    }

    // ========================================================================
    // 2. fixture 全量数据驱动
    // ========================================================================

    @Test
    fun fixture_driven_allCasesPass() {
        val cases = rootJson.getJSONArray("cases")
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            val name = c.getString("name")
            val budgets = parseBudgets(c.getJSONArray("budgets"))
            val existing = parseTxArray(c.getJSONArray("existing"))
            val incoming = parseTx(c.getJSONObject("incoming"))
            val table = parseRateTable(c)
            val expect = parseExpect(c.getJSONObject("expect"))

            val actual = BudgetEnforcer.checkTx(
                incoming, budgets, existing, table, ANCHOR_NOW_MS,
            )
            assertResultMatches("case[$name]", expect, actual)
        }
    }

    /** fixture budgets 段走 V2PayloadCodec.decodeBudget（顺带守护编解码字段）。 */
    private fun parseBudgets(arr: JSONArray): List<BudgetRecord> =
        List(arr.length()) { i -> V2PayloadCodec.decodeBudget(arr.getJSONObject(i).toString()) }

    /** 全字段比对期望与实际，失败信息带 case 名与字段名。 */
    private fun assertResultMatches(clue: String, e: Expect, a: BudgetCheckResult) {
        assertEquals("$clue level 不一致", e.level, a.level.name)
        assertEquals("$clue budgetId 不一致", e.budgetId, a.budgetId)
        assertEquals("$clue category 不一致", e.category, a.category)
        assertEquals("$clue currency 不一致", e.currency, a.currency)
        assertEquals("$clue spentMinor 不一致", e.spentMinor, a.spentMinor)
        assertEquals("$clue incomingMinor 不一致", e.incomingMinor, a.incomingMinor)
        assertEquals("$clue projectedMinor 不一致", e.projectedMinor, a.projectedMinor)
        assertEquals("$clue limitMinor 不一致", e.limitMinor, a.limitMinor)
        assertEquals("$clue usedPct 不一致", e.usedPct, a.usedPct)
        assertEquals("$clue thresholdPct 不一致", e.thresholdPct, a.thresholdPct)
    }

    // ========================================================================
    // 3. periodBucket 边界（月 / 周 / 年 / custom / 有效期外 / 相邻周桶）
    // ========================================================================

    /** 2026 年预算有效期：2026-01-01 00:00 CST 至 2026-12-31 23:59:59.999 CST。 */
    private val yearStart = 1_767_196_800_000L
    private val yearEnd = 1_798_732_799_999L

    @Test
    fun periodBucket_monthly_anchorJune_sixFirstToJulyFirst() {
        // 锚点 2026-06-28 12:00 CST → 桶 [2026-06-01 00:00, 2026-07-01 00:00)。
        val bucket = BudgetEnforcer.periodBucket("monthly", yearStart, yearEnd, ANCHOR_NOW_MS)
        assertEquals(Pair(1_780_243_200_000L, 1_782_835_200_000L), bucket)
    }

    @Test
    fun periodBucket_yearly_anchor2026_wholeYearHalfOpen() {
        val bucket = BudgetEnforcer.periodBucket("yearly", yearStart, yearEnd, ANCHOR_NOW_MS)
        assertEquals(Pair(1_767_196_800_000L, 1_798_732_800_000L), bucket)
    }

    @Test
    fun periodBucket_weekly_anchorWeek_bucketFromStartTsDateMidnight() {
        // weekly epoch = startTs 所在 CST 日期零点（2026-06-22 00:00，周一）；
        // 锚点 6 月 28 日（周日）属第 0 桶 [06-22, 06-29)。
        val weeklyStart = 1_782_057_600_000L
        val bucket = BudgetEnforcer.periodBucket("weekly", weeklyStart, yearEnd, ANCHOR_NOW_MS)
        assertEquals(Pair(1_782_057_600_000L, 1_782_662_400_000L), bucket)
    }

    @Test
    fun periodBucket_weekly_adjacentNextMonday_fallsIntoNextBucket() {
        // 2026-06-29 00:00 CST（下周一零点，恰为上桶半开右端点）落入下一桶。
        val weeklyStart = 1_782_057_600_000L
        val nextMonday = 1_782_662_400_000L
        val bucket = BudgetEnforcer.periodBucket("weekly", weeklyStart, yearEnd, nextMonday)
        assertEquals(Pair(1_782_662_400_000L, 1_783_267_200_000L), bucket)
    }

    @Test
    fun periodBucket_custom_endInclusiveViaHalfOpenEndPlusOne() {
        val start = 1_780_243_200_000L // 2026-06-01 00:00 CST
        val end = 1_781_020_800_000L // 2026-06-10 00:00 CST（含）
        // 终点当刻仍在桶内（右端 = end + 1ms，开区间）。
        assertEquals(Pair(start, end + 1L), BudgetEnforcer.periodBucket("custom", start, end, end))
        // 终点之后一毫秒已在有效期外，返回 null。
        assertNull(BudgetEnforcer.periodBucket("custom", start, end, end + 1L))
    }

    @Test
    fun periodBucket_outsideValidity_returnsNull() {
        // monthly 预算有效期自 2026-06-01 起；5 月 31 日的流水不命中。
        assertNull(
            BudgetEnforcer.periodBucket("monthly", 1_780_243_200_000L, yearEnd, 1_780_156_800_000L)
        )
        // 未知 scope 同样返回 null。
        assertNull(BudgetEnforcer.periodBucket("quarterly", yearStart, yearEnd, ANCHOR_NOW_MS))
    }

    // ========================================================================
    // 4 / 5. 门面非支出短路与 evaluate 三档边界
    // ========================================================================

    @Test
    fun checkTx_nonExpense_returnsOkEmpty() {
        val budget = BudgetRecord(
            id = "b", scope = "monthly", category = "餐饮", amountMinor = "1000.00",
            currency = "CNY", startTs = yearStart, endTs = yearEnd,
            warningThresholdPct = 80, blockThresholdPct = 100, active = true,
            createdAt = yearStart, updatedAt = yearStart,
        )
        val income = BudgetTxLike("tx-income", "income", "5000.00", "餐饮", "CNY", ANCHOR_NOW_MS)
        val result = BudgetEnforcer.checkTx(income, listOf(budget), emptyList(), null, ANCHOR_NOW_MS)
        assertEquals(BudgetCheckResult.OK_EMPTY, result)
    }

    @Test
    fun evaluate_thresholdBoundaries_classifyThreeLevels() {
        val budget = BudgetRecord(
            id = "b", scope = "monthly", category = "餐饮", amountMinor = "1000.00",
            currency = "CNY", startTs = yearStart, endTs = yearEnd,
            warningThresholdPct = 80, blockThresholdPct = 100, active = true,
            createdAt = yearStart, updatedAt = yearStart,
        )
        // 790.00 / 1000.00 = 79% → OK，thresholdPct 填预警阈值 80。
        BudgetEnforcer.evaluate(budget, 690_00L, 100_00L).let {
            assertEquals(BudgetLevel.OK, it.level)
            assertEquals(79, it.usedPct)
            assertEquals(80, it.thresholdPct)
        }
        // 恰好 80% → WARNING。
        BudgetEnforcer.evaluate(budget, 700_00L, 100_00L).let {
            assertEquals(BudgetLevel.WARNING, it.level)
            assertEquals(80, it.usedPct)
            assertEquals(80, it.thresholdPct)
        }
        // 恰好 100% → BLOCK，thresholdPct 填阻断阈值 100。
        BudgetEnforcer.evaluate(budget, 900_00L, 100_00L).let {
            assertEquals(BudgetLevel.BLOCK, it.level)
            assertEquals(100, it.usedPct)
            assertEquals(100, it.thresholdPct)
        }
    }
}
