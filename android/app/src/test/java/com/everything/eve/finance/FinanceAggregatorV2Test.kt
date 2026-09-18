// ============================================================================
// FinanceAggregator v2 loan 接入单元测试（stage5-finance-v2 / Task 4 / TR-4.2）
// ============================================================================
//
// 验证目标（≥6 用例, fixture 用例全量驱动 + 边界补充）：
//   1. fixture SHA-256 硬编码守护（TR-4.6 双端字节级一致核验）；
//   2. aggregator-v2 fixture 用例数守护（≥9 条）+ netWorth 全量数据驱动；
//   3. 关键 fixture 场景具名断言（混合账户/卡/借款, net=assets-liabilities）；
//   4. 零回归守护：不传 loans 默认参数, v1 调用方式行为不变；
//   5. monthlyReport 不累加 loan 金额（本金非 income/expense）；
//   6. 纯函数幂等性。
//
// 共享 fixture（__fixtures__/aggregator-v2-cases.json, 与 Web 镜像同 SHA-256）。
//
// 关联:
//   - android/.../finance/FinanceAggregator.kt#netWorth / monthlyReport（被测目标）
//   - android/.../finance/__fixtures__/aggregator-v2-cases.json（共享 fixture）
// ============================================================================

package com.everything.eve.finance

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.security.MessageDigest

/**
 * FinanceAggregator v2（loan 接入）JUnit 4 单元测试。
 *
 * fixture 结构：{ version, cases: [ {name, input:{accounts,cards,loans},
 * expected:{七字段快照}} ] }；txCount 固定断言 0（本 fixture 不下发流水）。
 */
class FinanceAggregatorV2Test {

    companion object {
        /** fixture 文件 SHA-256（小写 hex）—— TR-4.6 双端一致性硬守护。 */
        const val FIXTURE_SHA256 = "9e5fd8761458df070b7cd6b9e362cdd4b9869cdfb4cba6b4fa6ab9a5b291db88"

        @BeforeClass
        @JvmStatic
        fun lockTimezone() {
            val cst = java.util.TimeZone.getTimeZone("Asia/Shanghai")
            java.util.TimeZone.setDefault(cst)
            System.setProperty("user.timezone", "Asia/Shanghai")
        }
    }

    /** 单条聚合用例（fixture 真理源映射）。 */
    private data class AggregatorV2Case(
        val name: String,
        val accounts: List<FinanceAggregator.AccountLike>,
        val cards: List<FinanceAggregator.CardLike>,
        val loans: List<FinanceAggregator.LoanLike>,
        val totalAssets: String,
        val totalAssetValue: String,
        val totalLiability: String,
        val accountCount: Int,
        val cardCount: Int,
        val txCount: Int,
        val currency: String,
    )

    private val fixtureBytes: ByteArray by lazy { loadFixtureBytes() }
    private val rootJson: JSONObject by lazy { JSONObject(String(fixtureBytes, Charsets.UTF_8)) }
    private val cases: List<AggregatorV2Case> by lazy { parseCases() }

    private fun loadFixtureBytes(): ByteArray {
        val classpathPath = "finance/__fixtures__/aggregator-v2-cases.json"
        val cpResource = this::class.java.classLoader?.getResource(classpathPath)
        if (cpResource != null) {
            return cpResource.openStream().use { it.readBytes() }
        }
        val candidates = listOf(
            "src/test/java/com/everything/eve/finance/__fixtures__/aggregator-v2-cases.json",
            "android/app/src/test/java/com/everything/eve/finance/__fixtures__/aggregator-v2-cases.json",
            "app/src/test/java/com/everything/eve/finance/__fixtures__/aggregator-v2-cases.json",
            "d:/github/everything/everything/android/app/src/test/java/com/everything/eve/finance/__fixtures__/aggregator-v2-cases.json",
            "D:/github/everything/everything/android/app/src/test/java/com/everything/eve/finance/__fixtures__/aggregator-v2-cases.json",
            "D:\\github\\everything\\everything\\android\\app\\src\\test\\java\\com\\everything\\eve\\finance\\__fixtures__\\aggregator-v2-cases.json",
        )
        val file = candidates
            .map { java.io.File(it) }
            .firstOrNull { it.exists() }
            ?: throw IllegalStateException(
                "fixture 资源缺失: 尝试路径 = [$classpathPath classpath, ${candidates.joinToString()}]"
            )
        return file.readBytes()
    }

    private fun parseCases(): List<AggregatorV2Case> {
        val arr = rootJson.getJSONArray("cases")
        val out = mutableListOf<AggregatorV2Case>()
        for (i in 0 until arr.length()) {
            val c = arr.getJSONObject(i)
            val inp = c.getJSONObject("input")
            val exp = c.getJSONObject("expected")
            out.add(
                AggregatorV2Case(
                    name = c.getString("name"),
                    accounts = inp.getJSONArray("accounts").toObjectList().map { o ->
                        FinanceAggregator.AccountLike(
                            id = o.getString("id"),
                            balance = o.getString("balance"),
                            currency = o.getString("currency"),
                            archived = o.getBoolean("archived"),
                        )
                    },
                    cards = inp.getJSONArray("cards").toObjectList().map { o ->
                        FinanceAggregator.CardLike(
                            id = o.getString("id"),
                            kind = o.getString("kind"),
                            usedLimit = if (o.isNull("usedLimit")) null else o.getString("usedLimit"),
                            archived = o.getBoolean("archived"),
                        )
                    },
                    loans = inp.getJSONArray("loans").toObjectList().map { o ->
                        FinanceAggregator.LoanLike(
                            id = o.getString("id"),
                            direction = o.getString("direction"),
                            principalMinor = o.getString("principalMinor"),
                            paidMinor = o.getString("paidMinor"),
                            includeInNetAssets = o.getBoolean("includeInNetAssets"),
                            currency = o.getString("currency"),
                            status = o.getString("status"),
                        )
                    },
                    totalAssets = exp.getString("totalAssets"),
                    totalAssetValue = exp.getString("totalAssetValue"),
                    totalLiability = exp.getString("totalLiability"),
                    accountCount = exp.getInt("accountCount"),
                    cardCount = exp.getInt("cardCount"),
                    txCount = exp.getInt("txCount"),
                    currency = exp.getString("currency"),
                )
            )
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
    fun fixture_hasAtLeastSixCases() {
        assertTrue("fixture 用例应不少于 6 条, 实际 = ${cases.size}", cases.size >= 6)
    }

    // ============================================================================
    // 2. fixture 数据驱动（全部 loan 聚合用例, 七字段快照断言）
    // ============================================================================

    @Test
    fun fixture_driven_allCasesPass() {
        for (tc in cases) {
            val actual = FinanceAggregator.netWorth(tc.accounts, tc.cards, emptyList(), tc.loans)
            assertEquals("case[${tc.name}] totalAssets 不一致", tc.totalAssets, actual.totalAssets)
            assertEquals(
                "case[${tc.name}] totalAssetValue 不一致",
                tc.totalAssetValue, actual.totalAssetValue
            )
            assertEquals(
                "case[${tc.name}] totalLiability 不一致",
                tc.totalLiability, actual.totalLiability
            )
            assertEquals("case[${tc.name}] accountCount 不一致", tc.accountCount, actual.accountCount)
            assertEquals("case[${tc.name}] cardCount 不一致", tc.cardCount, actual.cardCount)
            assertEquals("case[${tc.name}] txCount 不一致", tc.txCount, actual.txCount)
            assertEquals("case[${tc.name}] currency 不一致", tc.currency, actual.currency)
        }
    }

    // ============================================================================
    // 3. 关键 fixture 场景具名断言
    // ============================================================================

    /** ⑥混合场景：资产 3000（账户 2000 + lent 1000）, 负债 900（卡 400 + borrowed 500）, 净 2100。 */
    @Test
    fun fixture_case_mixed_netIsAssetsMinusLiabilities() {
        val tc = cases.first { it.name == "mixed_accountsCardsLoans_netIsAssetsMinusLiabilities" }
        val snap = FinanceAggregator.netWorth(tc.accounts, tc.cards, emptyList(), tc.loans)
        assertEquals("3000.00", snap.totalAssetValue)
        assertEquals("900.00", snap.totalLiability)
        assertEquals("2100.00", snap.totalAssets)
        // 排除的 lent 999 不影响任何一端；计数仍只数账户与卡。
        assertEquals(1, snap.accountCount)
        assertEquals(1, snap.cardCount)
    }

    /** ①lent 应收：1000.00 全额计入资产。 */
    @Test
    fun fixture_case_lent_countsAsAsset() {
        val tc = cases.first { it.name == "loan_lent_outstanding_countsAsAsset" }
        val snap = FinanceAggregator.netWorth(emptyList(), emptyList(), emptyList(), tc.loans)
        assertEquals("1000.00", snap.totalAssetValue)
        assertEquals("0.00", snap.totalLiability)
        assertEquals("1000.00", snap.totalAssets)
    }

    /** ⑦钳位：已还 150 > 本金 100 时剩余本金钳到 0, 不出现负资产。 */
    @Test
    fun fixture_case_overpaid_remainClampedToZero() {
        val tc = cases.first { it.name == "loan_overpaid_remainClampedToZero" }
        val snap = FinanceAggregator.netWorth(emptyList(), emptyList(), emptyList(), tc.loans)
        assertEquals("0.00", snap.totalAssetValue)
        assertEquals("0.00", snap.totalLiability)
        assertEquals("0.00", snap.totalAssets)
    }

    // ============================================================================
    // 4. 零回归守护 —— 默认参数保证 v1 调用方式不变
    // ============================================================================

    /** 不传 loans（甚至不传 txs）即可得到与 v1 完全一致的快照。 */
    @Test
    fun defaultParams_zeroRegression_v1CallStyleUnchanged() {
        val accounts = listOf(
            FinanceAggregator.AccountLike("a1", "2000.00", "CNY", archived = false),
        )
        val cards = listOf(
            FinanceAggregator.CardLike("c1", kind = "credit", usedLimit = "400.00", archived = false),
        )
        // 两参形式（v1 部分调用点的写法）。
        val twoArgs = FinanceAggregator.netWorth(accounts, cards)
        assertEquals("1600.00", twoArgs.totalAssets)
        assertEquals("2000.00", twoArgs.totalAssetValue)
        assertEquals("400.00", twoArgs.totalLiability)
        // 三参形式（FinanceAggregatorTest 既有写法）。
        val threeArgs = FinanceAggregator.netWorth(accounts, cards, emptyList())
        assertEquals(twoArgs, threeArgs)
        // 四参显式空 loans 与默认值等价。
        val fourArgs = FinanceAggregator.netWorth(accounts, cards, emptyList(), emptyList())
        assertEquals(twoArgs, fourArgs)
    }

    // ============================================================================
    // 5. monthlyReport —— loan 本金不进 income/expense（TR-4.2）
    // ============================================================================

    /** 当月有一笔收入 1000.00, 同时存在 lent/borrowed 借款 → 月报只反映收入。 */
    @Test
    fun monthlyReport_loansDoNotAffectIncomeOrExpense() {
        val txs = listOf(
            FinanceAggregator.TxLike(
                id = "t1", accountId = "a1", cardId = null, kind = "income",
                amount = "1000.00", category = "salary",
                occurredAt = 1782619200000L, transferToAccountId = null,
            ),
        )
        val loans = listOf(
            FinanceAggregator.LoanLike(
                id = "L1", direction = "lent", principalMinor = "500.00", paidMinor = "0.00",
                includeInNetAssets = true, currency = "CNY", status = "active",
            ),
            FinanceAggregator.LoanLike(
                id = "L2", direction = "borrowed", principalMinor = "200.00", paidMinor = "0.00",
                includeInNetAssets = true, currency = "CNY", status = "active",
            ),
        )
        val report = FinanceAggregator.monthlyReport("2026-06", txs, emptyList(), loans)
        assertEquals("借款本金不应计入 income", "1000.00", report.income)
        assertEquals("借款本金不应计入 expense", "0.00", report.expense)
        assertEquals("1000.00", report.net)
        assertEquals(1, report.txCount)
        assertTrue(report.categoryBreakdown.isEmpty())
    }

    /** monthlyReport 新增 loans 参数有默认值, v1 两参/三参调用零回归。 */
    @Test
    fun monthlyReport_defaultLoansParam_zeroRegression() {
        val twoArgs = FinanceAggregator.monthlyReport("2026-06", emptyList())
        val fourArgs = FinanceAggregator.monthlyReport(
            "2026-06", emptyList(), emptyList(),
            listOf(
                FinanceAggregator.LoanLike(
                    id = "L1", direction = "lent", principalMinor = "500.00", paidMinor = "0.00",
                    includeInNetAssets = true, currency = "CNY", status = "active",
                ),
            ),
        )
        assertEquals(twoArgs, fourArgs)
        assertEquals("0.00", twoArgs.income)
        assertEquals("0.00", twoArgs.expense)
    }

    // ============================================================================
    // 6. 纯函数幂等性
    // ============================================================================

    @Test
    fun netWorth_withLoans_isIdempotent() {
        val loans = listOf(
            FinanceAggregator.LoanLike(
                id = "L1", direction = "lent", principalMinor = "1000.00", paidMinor = "300.00",
                includeInNetAssets = true, currency = "CNY", status = "partially_paid",
            ),
        )
        val a = FinanceAggregator.netWorth(emptyList(), emptyList(), emptyList(), loans)
        val b = FinanceAggregator.netWorth(emptyList(), emptyList(), emptyList(), loans)
        assertEquals(a, b)
        assertEquals("700.00", a.totalAssetValue)
    }
}

/** JSONArray → List<JSONObject>（文件内私有, 不污染生产代码）。 */
private fun JSONArray.toObjectList(): List<JSONObject> = List(length()) { getJSONObject(it) }
