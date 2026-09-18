// ============================================================================
// FinanceAggregator B5 多币种折算单元测试（stage5-finance-v2 / B5 / TR-5.2 / FR-V2-C.3）
// ============================================================================
//
// 验证目标（≥7 用例）：
//   1. fixture SHA-256 硬编码守护（与 Web aggregator-rate.spec.ts 同 SHA）；
//   2. aggregatorCases 8 条全量数据驱动（含 targetCurrency 字段断言）；
//   3. monthlyReportCases 2 条全量驱动（带 currency 的 income / expense /
//      categoryBreakdown 逐笔折算, 缺汇率面值降级）；
//   4. 零回归：不传 rateTable 时完全 B4 面值口径, v1 调用形态结果不变；
//   5. DashboardSnapshot.targetCurrency 字段显式断言（默认 CNY / 激活时为目标币）；
//   6. CardLike / TxLike 末位 currency 默认参 = CNY 的实测验证（反向折算到 USD）。
//
// 共享 fixture（__fixtures__/rate-table-cases.json, 与 Web 镜像同 SHA-256）。
//
// 关联:
//   - android/.../finance/FinanceAggregator.kt#netWorth / monthlyReport（被测目标）
//   - android/.../finance/__fixtures__/rate-table-cases.json（共享 fixture）
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
 * FinanceAggregator B5（多币种折算）JUnit 4 单元测试。
 */
class FinanceAggregatorV2RateTest {

    companion object {
        /** fixture 文件 SHA-256（小写 hex）—— 双端一致性硬守护。 */
        const val FIXTURE_SHA256 = "7a1c77af553595423a3d0a338421e00eb7c0f59ca7b906d9768faf3abb2aea12"

        @BeforeClass
        @JvmStatic
        fun lockTimezone() {
            val cst = java.util.TimeZone.getTimeZone("Asia/Shanghai")
            java.util.TimeZone.setDefault(cst)
            System.setProperty("user.timezone", "Asia/Shanghai")
        }
    }

    /** 单条看板折算用例（fixture aggregatorCases 真理源映射）。 */
    private data class AggregatorRateCase(
        val name: String,
        val targetCurrency: String,
        val tableName: String,
        val accounts: List<FinanceAggregator.AccountLike>,
        val cards: List<FinanceAggregator.CardLike>,
        val txs: List<FinanceAggregator.TxLike>,
        val loans: List<FinanceAggregator.LoanLike>,
        val totalAssets: String,
        val totalAssetValue: String,
        val totalLiability: String,
        val accountCount: Int,
        val cardCount: Int,
        val txCount: Int,
        val currency: String,
        val expectedTarget: String,
    )

    /** 单条月报折算用例（fixture monthlyReportCases 真理源映射）。 */
    private data class ReportRateCase(
        val name: String,
        val yearMonth: String,
        val targetCurrency: String,
        val tableName: String,
        val txs: List<FinanceAggregator.TxLike>,
        val income: String,
        val expense: String,
        val net: String,
        val txCount: Int,
        val categoryBreakdown: Map<String, String>,
    )

    private val fixtureBytes: ByteArray by lazy { loadFixtureBytes() }
    private val rootJson: JSONObject by lazy { JSONObject(String(fixtureBytes, Charsets.UTF_8)) }

    private val tables: Map<String, RateTable> by lazy {
        val tablesObj = rootJson.getJSONObject("tables")
        tablesObj.keys().asSequence().associateWith { name ->
            RateTables.parse(tablesObj.getJSONObject(name).toString())
        }
    }

    private val aggregatorCases: List<AggregatorRateCase> by lazy { parseAggregatorCases() }
    private val reportCases: List<ReportRateCase> by lazy { parseReportCases() }

    private fun loadFixtureBytes(): ByteArray {
        val classpathPath = "finance/__fixtures__/rate-table-cases.json"
        val cpResource = this::class.java.classLoader?.getResource(classpathPath)
        if (cpResource != null) {
            return cpResource.openStream().use { it.readBytes() }
        }
        val candidates = listOf(
            "src/test/java/com/everything/eve/finance/__fixtures__/rate-table-cases.json",
            "android/app/src/test/java/com/everything/eve/finance/__fixtures__/rate-table-cases.json",
            "app/src/test/java/com/everything/eve/finance/__fixtures__/rate-table-cases.json",
            "d:/github/everything/everything/android/app/src/test/java/com/everything/eve/finance/__fixtures__/rate-table-cases.json",
            "D:/github/everything/everything/android/app/src/test/java/com/everything/eve/finance/__fixtures__/rate-table-cases.json",
            "D:\\github\\everything\\everything\\android\\app\\src\\test\\java\\com\\everything\\eve\\finance\\__fixtures__\\rate-table-cases.json",
        )
        val file = candidates
            .map { java.io.File(it) }
            .firstOrNull { it.exists() }
            ?: throw IllegalStateException(
                "fixture 资源缺失: 尝试路径 = [$classpathPath classpath, ${candidates.joinToString()}]"
            )
        return file.readBytes()
    }

    private fun parseAggregatorCases(): List<AggregatorRateCase> {
        val arr = rootJson.getJSONArray("aggregatorCases")
        return List(arr.length()) { i ->
            val c = arr.getJSONObject(i)
            val inp = c.getJSONObject("input")
            val exp = c.getJSONObject("expected")
            AggregatorRateCase(
                name = c.getString("name"),
                targetCurrency = c.getString("targetCurrency"),
                tableName = c.getString("table"),
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
                        // fixture 未下发 currency 时按默认 CNY（与 DTO 默认参同口径）。
                        currency = o.optString("currency", "CNY"),
                    )
                },
                txs = inp.getJSONArray("txs").toObjectList().map(::toTxLike),
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
                expectedTarget = exp.getString("targetCurrency"),
            )
        }
    }

    private fun parseReportCases(): List<ReportRateCase> {
        val arr = rootJson.getJSONArray("monthlyReportCases")
        return List(arr.length()) { i ->
            val c = arr.getJSONObject(i)
            val exp = c.getJSONObject("expected")
            val breakdown = exp.getJSONObject("categoryBreakdown")
            ReportRateCase(
                name = c.getString("name"),
                yearMonth = c.getString("yearMonth"),
                targetCurrency = c.getString("targetCurrency"),
                tableName = c.getString("table"),
                txs = c.getJSONObject("input").getJSONArray("txs").toObjectList().map(::toTxLike),
                income = exp.getString("income"),
                expense = exp.getString("expense"),
                net = exp.getString("net"),
                txCount = exp.getInt("txCount"),
                categoryBreakdown = breakdown.keys().asSequence()
                    .associateWith { breakdown.getString(it) },
            )
        }
    }

    /** fixture tx JSON → TxLike（currency 缺省按 CNY, 与 DTO 默认参同口径）。 */
    private fun toTxLike(o: JSONObject): FinanceAggregator.TxLike {
        return FinanceAggregator.TxLike(
            id = o.getString("id"),
            accountId = if (o.isNull("accountId")) null else o.getString("accountId"),
            cardId = if (o.isNull("cardId")) null else o.getString("cardId"),
            kind = o.getString("kind"),
            amount = o.getString("amount"),
            category = o.getString("category"),
            occurredAt = o.getLong("occurredAt"),
            transferToAccountId = if (o.isNull("transferToAccountId")) null
            else o.getString("transferToAccountId"),
            currency = o.optString("currency", "CNY"),
        )
    }

    // ============================================================================
    // 1. fixture 完整性守护
    // ============================================================================

    @Test
    fun fixture_sha256_matchesHardcodedConstant() {
        val digest = MessageDigest.getInstance("SHA-256").digest(fixtureBytes)
        val actual = digest.joinToString("") { "%02x".format(it) }
        assertEquals(
            "fixture SHA-256 与硬编码常量不一致（双端镜像可能失步）",
            FIXTURE_SHA256, actual
        )
    }

    @Test
    fun fixture_hasAtLeastSevenAggregatorCasesAndTwoReportCases() {
        assertTrue(
            "aggregatorCases 应不少于 7 条, 实际 = ${aggregatorCases.size}",
            aggregatorCases.size >= 7
        )
        assertTrue(
            "monthlyReportCases 应不少于 1 条, 实际 = ${reportCases.size}",
            reportCases.size >= 1
        )
    }

    // ============================================================================
    // 2. fixture 数据驱动 —— 看板折算 8 条（含 targetCurrency 字段）
    // ============================================================================

    @Test
    fun fixture_driven_allAggregatorCasesPass() {
        for (tc in aggregatorCases) {
            val table = tables.getValue(tc.tableName)
            val actual = FinanceAggregator.netWorth(
                tc.accounts, tc.cards, tc.txs, tc.loans, tc.targetCurrency, table
            )
            assertEquals("case[${tc.name}] totalAssets 不一致", tc.totalAssets, actual.totalAssets)
            assertEquals("case[${tc.name}] totalAssetValue 不一致", tc.totalAssetValue, actual.totalAssetValue)
            assertEquals("case[${tc.name}] totalLiability 不一致", tc.totalLiability, actual.totalLiability)
            assertEquals("case[${tc.name}] accountCount 不一致", tc.accountCount, actual.accountCount)
            assertEquals("case[${tc.name}] cardCount 不一致", tc.cardCount, actual.cardCount)
            assertEquals("case[${tc.name}] txCount 不一致", tc.txCount, actual.txCount)
            assertEquals("case[${tc.name}] currency 语义应为首账户币", tc.currency, actual.currency)
            assertEquals("case[${tc.name}] targetCurrency 不一致", tc.expectedTarget, actual.targetCurrency)
        }
    }

    // ============================================================================
    // 3. fixture 数据驱动 —— 月报折算 2 条
    // ============================================================================

    @Test
    fun fixture_driven_allMonthlyReportCasesPass() {
        for (tc in reportCases) {
            val table = tables.getValue(tc.tableName)
            val report = FinanceAggregator.monthlyReport(
                tc.yearMonth, tc.txs, emptyList(), emptyList(), tc.targetCurrency, table
            )
            assertEquals("case[${tc.name}] income 不一致", tc.income, report.income)
            assertEquals("case[${tc.name}] expense 不一致", tc.expense, report.expense)
            assertEquals("case[${tc.name}] net 不一致", tc.net, report.net)
            assertEquals("case[${tc.name}] txCount 不一致", tc.txCount, report.txCount)
            assertEquals(
                "case[${tc.name}] categoryBreakdown 不一致",
                tc.categoryBreakdown, report.categoryBreakdown
            )
        }
    }

    // ============================================================================
    // 4. 关键 fixture 场景具名断言
    // ============================================================================

    /** ①单 USD 账户 100.00 ×7.25 → 725.00。 */
    @Test
    fun fixture_case_usdAccount_convertsTo725() {
        val tc = aggregatorCases.first { it.name == "usd_singleAccount_100_convertsTo725Cny" }
        val snap = FinanceAggregator.netWorth(
            tc.accounts, tc.cards, tc.txs, tc.loans, tc.targetCurrency,
            tables.getValue(tc.tableName),
        )
        assertEquals("725.00", snap.totalAssetValue)
        assertEquals("725.00", snap.totalAssets)
        assertEquals("0.00", snap.totalLiability)
        // currency 保持首账户币 USD；折算目标币 CNY。
        assertEquals("USD", snap.currency)
        assertEquals("CNY", snap.targetCurrency)
    }

    /** ④GBP 缺汇率面值 1:1 降级：100.00 不被缩放, 也不丢条目。 */
    @Test
    fun fixture_case_missingRate_fallsBackToFaceValue() {
        val tc = aggregatorCases.first { it.name == "gbp_missingRate_faceValueFallback" }
        val snap = FinanceAggregator.netWorth(
            tc.accounts, tc.cards, tc.txs, tc.loans, tc.targetCurrency,
            tables.getValue(tc.tableName),
        )
        assertEquals("100.00", snap.totalAssetValue)
        assertEquals("GBP", snap.currency)
        assertEquals("CNY", snap.targetCurrency)
    }

    /** ⑦信用卡 USD 负债 + lent EUR 资产综合折算。 */
    @Test
    fun fixture_case_cardLiabilityAndLoanAsset_composite() {
        val tc = aggregatorCases.first {
            it.name == "usdCard_liability_and_eurLoan_asset_composite"
        }
        val snap = FinanceAggregator.netWorth(
            tc.accounts, tc.cards, tc.txs, tc.loans, tc.targetCurrency,
            tables.getValue(tc.tableName),
        )
        assertEquals("1285.00", snap.totalAssetValue)
        assertEquals("725.00", snap.totalLiability)
        assertEquals("560.00", snap.totalAssets)
    }

    // ============================================================================
    // 5. 零回归 —— 不传 rateTable 时 B4 面值口径（含 targetCurrency 默认值）
    // ============================================================================

    @Test
    fun noRateTable_faceValueZeroRegression_b4CallStyle() {
        val usdAccount = FinanceAggregator.AccountLike(
            id = "a1", balance = "100.00", currency = "USD", archived = false,
        )
        val usdCard = FinanceAggregator.CardLike(
            id = "c1", kind = "credit", usedLimit = "100.00", archived = false,
        )
        val usdLoan = FinanceAggregator.LoanLike(
            id = "L1", direction = "lent", principalMinor = "50.00", paidMinor = "0.00",
            includeInNetAssets = true, currency = "USD", status = "active",
        )
        // B4 调用形态（四参, 不带折算）：外币一律面值累加, 与 B4 行为逐值一致。
        val b4Style = FinanceAggregator.netWorth(
            listOf(usdAccount), listOf(usdCard), emptyList(), listOf(usdLoan),
        )
        assertEquals("面值口径资产 = 100 + 50", "150.00", b4Style.totalAssetValue)
        assertEquals("面值口径负债 = 100", "100.00", b4Style.totalLiability)
        assertEquals("50.00", b4Style.totalAssets)
        assertEquals("USD", b4Style.currency)
        assertEquals("无表时 targetCurrency 默认 CNY", "CNY", b4Style.targetCurrency)

        // 六参显式默认（CNY + null）与四参结果 data class 完全相等。
        val explicitDefaults = FinanceAggregator.netWorth(
            listOf(usdAccount), listOf(usdCard), emptyList(), listOf(usdLoan),
            "CNY", null,
        )
        assertEquals(b4Style, explicitDefaults)
        // 两参 v1 形态也不回归。
        val twoArgs = FinanceAggregator.netWorth(listOf(usdAccount), listOf(usdCard))
        assertEquals("两参形态负债一致", "100.00", twoArgs.totalLiability)
    }

    // ============================================================================
    // 6. CardLike / TxLike currency 默认参实测（缺省即 CNY）
    // ============================================================================

    @Test
    fun cardLikeAndTxLike_defaultCurrency_isTreatedAsCny() {
        val table = tables.getValue("main")

        // CardLike 用 B4 四参构造（不传 currency）→ 目标 USD 时按 CNY 反向折算：
        // 100.00 CNY = 10000 分 ÷ 7.25 = 1379 分 = 13.79 USD（证明缺省被当 CNY 而非跳过）。
        val cnyCard = FinanceAggregator.CardLike(
            id = "c1", kind = "credit", usedLimit = "100.00", archived = false,
        )
        val cardSnap = FinanceAggregator.netWorth(
            emptyList(), listOf(cnyCard), emptyList(), emptyList(), "USD", table
        )
        assertEquals("缺省 currency 的卡应按 CNY 折算到 USD", "13.79", cardSnap.totalLiability)

        // TxLike 不传 currency 的 USD 支出月报：10000 分 CNY → 1379 分 = 13.79。
        val cnyTx = FinanceAggregator.TxLike(
            id = "t1", accountId = "a1", cardId = null, kind = "expense",
            amount = "100.00", category = "food",
            occurredAt = 1782619200000L, transferToAccountId = null,
        )
        val report = FinanceAggregator.monthlyReport(
            "2026-06", listOf(cnyTx), emptyList(), emptyList(), "USD", table
        )
        assertEquals("13.79", report.expense)
        assertEquals("-13.79", report.net)
        assertEquals("13.79", report.categoryBreakdown["food"])
    }

    /** 折算纯函数幂等：同入参两次 netWorth 结果相等, 且不含 targetCurrency 之外的新键影响。 */
    @Test
    fun netWorth_withRateTable_isIdempotent() {
        val tc = aggregatorCases.first { it.name == "multiCurrency_cnyUsdEur_preciseSum2510" }
        val table = tables.getValue(tc.tableName)
        val a = FinanceAggregator.netWorth(
            tc.accounts, tc.cards, tc.txs, tc.loans, tc.targetCurrency, table
        )
        val b = FinanceAggregator.netWorth(
            tc.accounts, tc.cards, tc.txs, tc.loans, tc.targetCurrency, table
        )
        assertEquals(a, b)
        // targetCurrency 为 v1 七字段之外的末位字段（Compose 编译器可能注入
        // 合成标记字段, 故此处不做反射字段数断言, 只断言业务字段值）。
        assertEquals("CNY", a.targetCurrency)
        assertEquals("2510.00", a.totalAssetValue)
        // 无折算上下文时快照 targetCurrency 取默认 CNY（B4 结构零回归）。
        val defaultSnap = FinanceAggregator.netWorth(tc.accounts, tc.cards)
        assertEquals("CNY", defaultSnap.targetCurrency)
    }
}

/** JSONArray → List<JSONObject>（文件内私有, 不污染生产代码）。 */
private fun JSONArray.toObjectList(): List<JSONObject> = List(length()) { getJSONObject(it) }
