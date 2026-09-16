// ============================================================================
// FinanceAggregator 纯函数单元测试（stage5-finance / Task 5 / TR-5.3）
// ============================================================================
//
// 验证目标（≥6 用例, 覆盖 TR-5.3 Pass Condition + 全部边界）：
//   1. fixture 数据驱动 —— 共享 aggregator-cases.json（6 条用例, ≥6 硬性指标）；
//   2. 关键场景显式断言（空集合 / 归档过滤 / 借记卡不计负债 / 多张信用卡求和）；
//   3. boundary 守护（用例数 ≥6, currency 默认 "CNY", 全 0 集合, decimal 精度）；
//   4. zero-knowledge 纪律（pure 语义 + 幂等性 + 不修改入参）；
//   5. accountBalance / cardUsedLimit / monthlyReport / budgetThreshold 联动断言。
//
// 共享 fixture（__fixtures__/aggregator-cases.json）由三端共同加载, SHA-256 必须
// 字节级一致 —— 见 tasks.md TR-5.4; 本测试仅读加载, 不修改 fixture 内容。
//
// 零知识纪律：
//   - 测试数据均为虚构（账户名/卡名/流水分类）, 非真实持卡人/真实账户；
//   - 不向 Room / DataStore / 网络写入任何数据；
//   - 不打印完整卡号 / 余额到 CI 日志（仅断言失败时打印汇总摘要）。
//
// 关联:
//   - android/.../finance/FinanceAggregator.kt（被测目标）
//   - android/.../finance/__fixtures__/aggregator-cases.json（共享 fixture）
//   - web/src/finance/__fixtures__/aggregator-cases.json（Web 镜像, 三端字节级一致）
// ============================================================================

package com.everything.eve.finance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.BeforeClass
import org.junit.Test

/**
 * FinanceAggregator 纯函数 JUnit 4 单元测试 —— 与 Web `aggregator.spec.ts` 行为对齐。
 *
 * 用例数守护:
 *   - fixture 至少 6 条用例（TR-5.3 硬性指标）, 当前 fixture 6 条;
 *   - 关键场景 + 边界 + 联动 + 零知识共 ~12 条 @Test。
 */
class FinanceAggregatorTest {

    // ============================================================================
    // 时区锁定（fixture CST 锚定；与 4b RecurrenceTest 同款）
    // ============================================================================

    companion object {
        /**
         * 在所有 @Test 执行前, 锁定 JVM 默认时区到 CST（UTC+8）。
         *
         * 原因：FinanceAggregator.yearMonthOf 使用 ZoneId.systemDefault() 拆
         * 本地日历分量；fixture 期望值（CST 2026-01 等）按 UTC+8 计算, 必须在
         * 单测 class 加载**前**完成时区锁定, 否则 java.time 在 CI 环境（CET/
         * UTC）会拆出错误 y/m, 导致 monthlyReport 用例失败。
         *
         * 与 4b RecurrenceTest 同款：见 RecurrenceTest.kt `setUpClass`。
         */
        @BeforeClass
        @JvmStatic
        fun lockTimezone() {
            val cst = java.util.TimeZone.getTimeZone("Asia/Shanghai")
            java.util.TimeZone.setDefault(cst)
            System.setProperty("user.timezone", "Asia/Shanghai")
        }
    }

    // ============================================================================
    // 共享 fixture 加载 —— 复用 LuhnTest 双路径兜底策略
    // ============================================================================

    /**
     * fixture 单条用例结构 —— 与 Web `AggregatorCase` 字段命名对齐。
     */
    private data class AggregatorCase(
        val name: String,
        val accounts: List<FinanceAggregator.AccountLike>,
        val cards: List<FinanceAggregator.CardLike>,
        val txs: List<FinanceAggregator.TxLike>,
        val expected: FinanceAggregator.DashboardSnapshot,
    )

    private val cases: List<AggregatorCase> by lazy { loadFixture() }

    /**
     * 从 classpath 或文件系统加载共享 fixture。
     *
     * 加载策略（双路径兜底）:
     *   1. **首选 classpath**: 路径 `finance/__fixtures__/aggregator-cases.json`
     *      （若未来 fixture 移至 resources/）;
     *   2. **兜底文件系统**: 相对当前工作目录解析 6 个候选路径
     *      （与 LuhnTest 同款策略; Gradle Test Executor 工作目录 = android/app/）。
     */
    private fun loadFixture(): List<AggregatorCase> {
        val classpathPath = "finance/__fixtures__/aggregator-cases.json"
        val cpResource = this::class.java.classLoader?.getResource(classpathPath)
        val text = if (cpResource != null) {
            cpResource.readText(Charsets.UTF_8)
        } else {
            val candidates = listOf(
                "src/test/java/com/everything/eve/finance/__fixtures__/aggregator-cases.json",
                "android/app/src/test/java/com/everything/eve/finance/__fixtures__/aggregator-cases.json",
                "app/src/test/java/com/everything/eve/finance/__fixtures__/aggregator-cases.json",
                "d:/github/everything/everything/android/app/src/test/java/com/everything/eve/finance/__fixtures__/aggregator-cases.json",
                "D:/github/everything/everything/android/app/src/test/java/com/everything/eve/finance/__fixtures__/aggregator-cases.json",
                "D:\\github\\everything\\everything\\android\\app\\src\\test\\java\\com\\everything\\eve\\finance\\__fixtures__\\aggregator-cases.json",
            )
            val file = candidates
                .map { java.io.File(it) }
                .firstOrNull { it.exists() }
                ?: throw IllegalStateException(
                    "fixture 资源缺失: 尝试路径 = [$classpathPath classpath, ${candidates.joinToString()}]"
                )
            file.readText(Charsets.UTF_8)
        }
        return parseAggregatorCases(text)
    }

    /**
     * 极简 JSON 解析 —— 仅服务于本测试 fixture（结构稳定）。
     *
     * fixture 顶层: { "cases": [ {name, input: {accounts, cards, txs}, expected: {...}}, ... ] }
     * accounts[i] / cards[i] / txs[i] 元素为简单键值对, 字段顺序稳定。
     */
    private fun parseAggregatorCases(json: String): List<AggregatorCase> {
        val result = mutableListOf<AggregatorCase>()
        // 提取 cases 数组内容（[...] 之间的子串）。
        val casesStart = json.indexOf("\"cases\"")
        if (casesStart < 0) return emptyList()
        val arrayStart = json.indexOf('[', casesStart)
        if (arrayStart < 0) return emptyList()
        // 找到与 arrayStart 配对的 ']'。
        var depth = 0
        var arrayEnd = -1
        for (i in arrayStart until json.length) {
            when (json[i]) {
                '[' -> depth++
                ']' -> {
                    depth--
                    if (depth == 0) {
                        arrayEnd = i
                        break
                    }
                }
            }
        }
        if (arrayEnd < 0) return emptyList()
        val casesBody = json.substring(arrayStart + 1, arrayEnd)

        // 拆出每个顶层对象 {...}（不计嵌套）。
        val topObjects = splitTopLevelObjects(casesBody)
        for (obj in topObjects) {
            val name = extractStringField(obj, "name") ?: continue
            // input 块含 accounts/cards/txs 三个数组。
            val inputBlock = extractObjectField(obj, "input") ?: continue
            val accounts = parseAccounts(inputBlock)
            val cards = parseCards(inputBlock)
            val txs = parseTxs(inputBlock)
            // expected 块含 totalAssets / totalAssetValue / totalLiability / accountCount /
            // cardCount / txCount / currency。
            val expectedBlock = extractObjectField(obj, "expected") ?: continue
            val expected = FinanceAggregator.DashboardSnapshot(
                totalAssets = extractStringField(expectedBlock, "totalAssets") ?: "0.00",
                totalAssetValue = extractStringField(expectedBlock, "totalAssetValue") ?: "0.00",
                totalLiability = extractStringField(expectedBlock, "totalLiability") ?: "0.00",
                accountCount = extractIntField(expectedBlock, "accountCount"),
                cardCount = extractIntField(expectedBlock, "cardCount"),
                txCount = extractIntField(expectedBlock, "txCount"),
                currency = extractStringField(expectedBlock, "currency") ?: "CNY",
            )
            result.add(AggregatorCase(name, accounts, cards, txs, expected))
        }
        return result
    }

    /** 在 JSON 文本中找出所有顶层对象（不计嵌套的 {...}）的内容。 */
    private fun splitTopLevelObjects(body: String): List<String> {
        val out = mutableListOf<String>()
        var i = 0
        while (i < body.length) {
            val start = body.indexOf('{', i)
            if (start < 0) break
            var depth = 0
            var end = -1
            for (j in start until body.length) {
                when (body[j]) {
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) {
                            end = j
                            break
                        }
                    }
                }
            }
            if (end < 0) break
            out.add(body.substring(start + 1, end))
            i = end + 1
        }
        return out
    }

    /** 提取字符串字段值（"key": "value"）。 */
    private fun extractStringField(obj: String, field: String): String? {
        val regex = Regex("\"$field\"\\s*:\\s*\"([^\"]*)\"")
        return regex.find(obj)?.groupValues?.get(1)
    }

    /** 提取整数字段值（"key": 数字）。 */
    private fun extractIntField(obj: String, field: String): Int {
        val regex = Regex("\"$field\"\\s*:\\s*(-?\\d+)")
        return regex.find(obj)?.groupValues?.get(1)?.toInt() ?: 0
    }

    /** 提取对象字段的原始 JSON 片段（"key": {...}）。 */
    private fun extractObjectField(obj: String, field: String): String? {
        val keyIdx = obj.indexOf("\"$field\"")
        if (keyIdx < 0) return null
        val colonIdx = obj.indexOf(':', keyIdx)
        if (colonIdx < 0) return null
        // 跳过空白, 找到 '{' 起始。
        var i = colonIdx + 1
        while (i < obj.length && obj[i].isWhitespace()) i++
        if (i >= obj.length || obj[i] != '{') return null
        var depth = 0
        var end = -1
        for (j in i until obj.length) {
            when (obj[j]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) {
                        end = j
                        break
                    }
                }
            }
        }
        return if (end < 0) null else obj.substring(i + 1, end)
    }

    /** 提取数组字段的元素数组（"key": [...]）。 */
    private fun extractArrayElements(obj: String, field: String): List<String> {
        val keyIdx = obj.indexOf("\"$field\"")
        if (keyIdx < 0) return emptyList()
        val colonIdx = obj.indexOf(':', keyIdx)
        if (colonIdx < 0) return emptyList()
        var i = colonIdx + 1
        while (i < obj.length && obj[i].isWhitespace()) i++
        if (i >= obj.length || obj[i] != '[') return emptyList()
        // 在 [...] 范围内, 拆出顶层元素。
        var depth = 0
        var arrayEnd = -1
        for (j in i until obj.length) {
            when (obj[j]) {
                '[' -> depth++
                ']' -> {
                    depth--
                    if (depth == 0) {
                        arrayEnd = j
                        break
                    }
                }
            }
        }
        if (arrayEnd < 0) return emptyList()
        return splitTopLevelObjects(obj.substring(i + 1, arrayEnd))
    }

    /** 解析 accounts 数组 → List<AccountLike>。 */
    private fun parseAccounts(inputBlock: String): List<FinanceAggregator.AccountLike> {
        return extractArrayElements(inputBlock, "accounts").map { obj ->
            FinanceAggregator.AccountLike(
                id = extractStringField(obj, "id") ?: "",
                balance = extractStringField(obj, "balance") ?: "0",
                currency = extractStringField(obj, "currency") ?: "CNY",
                archived = extractBoolField(obj, "archived"),
            )
        }
    }

    /** 解析 cards 数组 → List<CardLike>。 */
    private fun parseCards(inputBlock: String): List<FinanceAggregator.CardLike> {
        return extractArrayElements(inputBlock, "cards").map { obj ->
            FinanceAggregator.CardLike(
                id = extractStringField(obj, "id") ?: "",
                kind = extractStringField(obj, "kind") ?: "credit",
                usedLimit = extractStringField(obj, "usedLimit"),
                archived = extractBoolField(obj, "archived"),
            )
        }
    }

    /** 解析 txs 数组 → List<TxLike>。 */
    private fun parseTxs(inputBlock: String): List<FinanceAggregator.TxLike> {
        return extractArrayElements(inputBlock, "txs").map { obj ->
            FinanceAggregator.TxLike(
                id = extractStringField(obj, "id") ?: "",
                accountId = extractStringField(obj, "accountId"),
                cardId = extractStringField(obj, "cardId"),
                kind = extractStringField(obj, "kind") ?: "expense",
                amount = extractStringField(obj, "amount") ?: "0",
                category = extractStringField(obj, "category") ?: "",
                occurredAt = extractLongField(obj, "occurredAt"),
                transferToAccountId = extractStringField(obj, "transferToAccountId"),
            )
        }
    }

    /** 提取布尔字段（"key": true|false）。 */
    private fun extractBoolField(obj: String, field: String): Boolean {
        val regex = Regex("\"$field\"\\s*:\\s*(true|false)")
        return regex.find(obj)?.groupValues?.get(1) == "true"
    }

    /** 提取长整型字段（"key": 数字）。 */
    private fun extractLongField(obj: String, field: String): Long {
        val regex = Regex("\"$field\"\\s*:\\s*(-?\\d+)")
        return regex.find(obj)?.groupValues?.get(1)?.toLong() ?: 0L
    }

    // ============================================================================
    // 1. fixture 加载驱动 —— 从 JSON 数据驱动全部用例（≥6 硬性指标）
    // ============================================================================

    /**
     * 守护测试 —— fixture 用例数 ≥6（TR-5.3 硬性指标）。
     */
    @Test
    fun fixture_hasAtLeastSixCases() {
        assertTrue(
            "fixture 用例数应不少于 6 条, 实际 = ${cases.size}",
            cases.size >= 6
        )
    }

    /**
     * fixture 数据驱动 —— 每条用例生成一个 @Test。
     *
     * 一次跑完所有用例, 失败时给出 case name + 实际 vs 期望摘要（避免把整个
     * accounts/cards 列表打印到 CI 日志 → 信息泄漏）。
     */
    @Test
    fun fixture_driven_allCasesPass() {
        for (tc in cases) {
            val actual = FinanceAggregator.netWorth(tc.accounts, tc.cards, tc.txs)
            // 逐字段断言；任一字段不等时, 抛 AssertionError 包含 case name。
            assertEquals(
                "case[${tc.name}] totalAssets 不一致",
                tc.expected.totalAssets, actual.totalAssets
            )
            assertEquals(
                "case[${tc.name}] totalAssetValue 不一致",
                tc.expected.totalAssetValue, actual.totalAssetValue
            )
            assertEquals(
                "case[${tc.name}] totalLiability 不一致",
                tc.expected.totalLiability, actual.totalLiability
            )
            assertEquals(
                "case[${tc.name}] accountCount 不一致",
                tc.expected.accountCount, actual.accountCount
            )
            assertEquals(
                "case[${tc.name}] cardCount 不一致",
                tc.expected.cardCount, actual.cardCount
            )
            assertEquals(
                "case[${tc.name}] txCount 不一致",
                tc.expected.txCount, actual.txCount
            )
            assertEquals(
                "case[${tc.name}] currency 不一致",
                tc.expected.currency, actual.currency
            )
        }
    }

    // ============================================================================
    // 2. 关键场景显式断言（覆盖空集合 / 归档过滤 / 借记卡不计负债）
    // ============================================================================

    /**
     * 空集合 → 返回全零 DashboardSnapshot, 不抛错。
     */
    @Test
    fun netWorth_emptyAll_returnsZero() {
        val snapshot = FinanceAggregator.netWorth(emptyList(), emptyList(), emptyList())
        assertEquals("0.00", snapshot.totalAssets)
        assertEquals("0.00", snapshot.totalAssetValue)
        assertEquals("0.00", snapshot.totalLiability)
        assertEquals(0, snapshot.accountCount)
        assertEquals(0, snapshot.cardCount)
        assertEquals(0, snapshot.txCount)
        assertEquals("CNY", snapshot.currency)
    }

    /**
     * 归档账户不计入净资产（仅未归档账户余额参与聚合）。
     */
    @Test
    fun netWorth_archivedAccountsExcluded() {
        val accounts = listOf(
            FinanceAggregator.AccountLike("a1", "1000.00", "CNY", archived = false),
            FinanceAggregator.AccountLike("a2", "9999.99", "CNY", archived = true),
        )
        val snapshot = FinanceAggregator.netWorth(accounts, emptyList(), emptyList())
        // 净资产 = 仅 a1 的 1000.00; accountCount 仍 = 2（含归档）。
        assertEquals("1000.00", snapshot.totalAssets)
        assertEquals("1000.00", snapshot.totalAssetValue)
        assertEquals(2, snapshot.accountCount)
    }

    /**
     * 借记卡不计负债（仅信用卡纳入总负债）。
     */
    @Test
    fun netWorth_debitCardDoesNotContributeToLiability() {
        val accounts = listOf(
            FinanceAggregator.AccountLike("a1", "5000.00", "CNY", archived = false),
        )
        val cards = listOf(
            FinanceAggregator.CardLike("c1", kind = "debit", usedLimit = null, archived = false),
        )
        val snapshot = FinanceAggregator.netWorth(accounts, cards, emptyList())
        assertEquals("5000.00", snapshot.totalAssets)
        assertEquals("0.00", snapshot.totalLiability)
        assertEquals(1, snapshot.cardCount)
    }

    /**
     * 多张信用卡的 usedLimit 求和进总负债。
     */
    @Test
    fun netWorth_multipleCreditCardsSumLiability() {
        val accounts = listOf(
            FinanceAggregator.AccountLike("a1", "10000.00", "CNY", archived = false),
        )
        val cards = listOf(
            FinanceAggregator.CardLike("c1", kind = "credit", usedLimit = "1500.00", archived = false),
            FinanceAggregator.CardLike("c2", kind = "credit", usedLimit = "500.00", archived = false),
        )
        val snapshot = FinanceAggregator.netWorth(accounts, cards, emptyList())
        assertEquals("8000.00", snapshot.totalAssets)
        assertEquals("10000.00", snapshot.totalAssetValue)
        assertEquals("2000.00", snapshot.totalLiability)
    }

    // ============================================================================
    // 3. accountBalance / cardUsedLimit 联动断言
    // ============================================================================

    /**
     * 单账户余额聚合 —— 起点 + 流水联动（income + / expense - / transfer 转出减）。
     */
    @Test
    fun accountBalance_incomeAndExpenseApply() {
        val account = FinanceAggregator.AccountLike("a1", "1000.00", "CNY", archived = false)
        val txs = listOf(
            FinanceAggregator.TxLike(
                id = "t1", accountId = "a1", cardId = null, kind = "income",
                amount = "500.00", category = "salary",
                occurredAt = 1735689600000, transferToAccountId = null,
            ),
            FinanceAggregator.TxLike(
                id = "t2", accountId = "a1", cardId = null, kind = "expense",
                amount = "200.00", category = "food",
                occurredAt = 1735776000000, transferToAccountId = null,
            ),
        )
        // 1000 + 500 - 200 = 1300
        assertEquals("1300.00", FinanceAggregator.accountBalance(account, txs))
    }

    /**
     * 单卡已用额度聚合 —— expense 累加 / income 累减（还款冲销）。
     */
    @Test
    fun cardUsedLimit_expenseIncreasesAndIncomeDecreases() {
        val card = FinanceAggregator.CardLike(
            id = "c1", kind = "credit", usedLimit = "1000.00", archived = false,
        )
        val txs = listOf(
            FinanceAggregator.TxLike(
                id = "t1", accountId = null, cardId = "c1", kind = "expense",
                amount = "300.00", category = "food",
                occurredAt = 1735689600000, transferToAccountId = null,
            ),
            FinanceAggregator.TxLike(
                id = "t2", accountId = null, cardId = "c1", kind = "income",
                amount = "100.00", category = "repay",
                occurredAt = 1735776000000, transferToAccountId = null,
            ),
        )
        // 1000 + 300 - 100 = 1200
        assertEquals("1200.00", FinanceAggregator.cardUsedLimit(card, txs))
    }

    /**
     * 单卡已用额度钳位到 0（还款过度冲销不出负数）。
     */
    @Test
    fun cardUsedLimit_clampsToZeroWhenRepayExceeds() {
        val card = FinanceAggregator.CardLike(
            id = "c1", kind = "credit", usedLimit = "100.00", archived = false,
        )
        val txs = listOf(
            FinanceAggregator.TxLike(
                id = "t1", accountId = null, cardId = "c1", kind = "income",
                amount = "500.00", category = "repay",
                occurredAt = 1735689600000, transferToAccountId = null,
            ),
        )
        // 100 - 500 = -400 → 钳位到 0.00
        assertEquals("0.00", FinanceAggregator.cardUsedLimit(card, txs))
    }

    // ============================================================================
    // 4. monthlyReport / budgetThreshold 边界断言
    // ============================================================================

    /**
     * monthlyReport 仅按年月键过滤；其他月份流水不参与。
     */
    @Test
    fun monthlyReport_filtersByYearMonth() {
        // 1735689600000 = 2025-01-01 00:00 UTC = 2025-01-01 08:00 CST
        // 1738368000000 = 2025-02-01 00:00 UTC = 2025-02-01 08:00 CST
        val txs = listOf(
            FinanceAggregator.TxLike(
                id = "t1", accountId = "a1", cardId = null, kind = "income",
                amount = "5000.00", category = "salary",
                occurredAt = 1735689600000, transferToAccountId = null,
            ),
            FinanceAggregator.TxLike(
                id = "t2", accountId = "a1", cardId = null, kind = "expense",
                amount = "1000.00", category = "food",
                occurredAt = 1735689600000, transferToAccountId = null,
            ),
            FinanceAggregator.TxLike(
                id = "t3", accountId = "a1", cardId = null, kind = "income",
                amount = "999.99", category = "salary",
                occurredAt = 1738368000000, transferToAccountId = null,
            ),
        )
        val report = FinanceAggregator.monthlyReport("2025-01", txs, emptyList())
        assertEquals("5000.00", report.income)
        assertEquals("1000.00", report.expense)
        assertEquals("4000.00", report.net)
        assertEquals(2, report.txCount)
        assertEquals("1000.00", report.categoryBreakdown["food"])
    }

    /**
     * monthlyReport 分类占比仅累加 expense 流水（income / transfer 不计入分类）。
     */
    @Test
    fun monthlyReport_categoryBreakdownOnlyExpense() {
        val txs = listOf(
            FinanceAggregator.TxLike(
                id = "t1", accountId = "a1", cardId = null, kind = "income",
                amount = "5000.00", category = "salary",
                occurredAt = 1735689600000, transferToAccountId = null,
            ),
            FinanceAggregator.TxLike(
                id = "t2", accountId = "a1", cardId = null, kind = "expense",
                amount = "100.00", category = "food",
                occurredAt = 1735689600000, transferToAccountId = null,
            ),
            FinanceAggregator.TxLike(
                id = "t3", accountId = "a1", cardId = null, kind = "transfer",
                amount = "200.00", category = "transfer",
                occurredAt = 1735689600000, transferToAccountId = "a2",
            ),
        )
        val report = FinanceAggregator.monthlyReport("2025-01", txs, emptyList())
        // income = 5000; expense = 100; net = 4900; txCount = 3
        assertEquals("5000.00", report.income)
        assertEquals("100.00", report.expense)
        assertEquals(3, report.txCount)
        // 分类仅有 food（100），salary 和 transfer 不计入 expense 分类。
        assertEquals("100.00", report.categoryBreakdown["food"])
        assertNull("income 不应计入 categoryBreakdown", report.categoryBreakdown["salary"])
    }

    /**
     * budgetThreshold 三档判定 —— OK / WARNING / EXCEEDED。
     */
    @Test
    fun budgetThreshold_threeTiers() {
        // OK: 支出 < 收入
        assertEquals(
            FinanceAggregator.BudgetStatus.OK,
            FinanceAggregator.budgetThreshold("10000.00", "5000.00", 0.8)
        )
        // WARNING: 1.0 ≤ 支出/收入 < 1.5
        assertEquals(
            FinanceAggregator.BudgetStatus.WARNING,
            FinanceAggregator.budgetThreshold("10000.00", "12000.00", 0.8)
        )
        // EXCEEDED: 支出/收入 ≥ 1.5
        assertEquals(
            FinanceAggregator.BudgetStatus.EXCEEDED,
            FinanceAggregator.budgetThreshold("10000.00", "20000.00", 0.8)
        )
    }

    /**
     * budgetThreshold 零收入短路 —— monthlyIncome = 0 一律返回 OK。
     */
    @Test
    fun budgetThreshold_zeroIncome_returnsOk() {
        assertEquals(
            FinanceAggregator.BudgetStatus.OK,
            FinanceAggregator.budgetThreshold("0", "9999.99", 0.8)
        )
    }

    // ============================================================================
    // 5. 零知识纪律（pure 语义 + 幂等性）
    // ============================================================================

    /**
     * netWorth 不修改入参（pure 语义）。
     */
    @Test
    fun netWorth_doesNotMutateInput() {
        val accounts = listOf(
            FinanceAggregator.AccountLike("a1", "1000.00", "CNY", archived = false),
        )
        val originalCount = accounts.size
        val originalBalance = accounts[0].balance
        FinanceAggregator.netWorth(accounts, emptyList(), emptyList())
        assertEquals(originalCount, accounts.size)
        assertEquals(originalBalance, accounts[0].balance)
    }

    /**
     * 纯函数无副作用 —— 相同输入多次调用结果一致（幂等性）。
     */
    @Test
    fun netWorth_isIdempotent() {
        val accounts = listOf(
            FinanceAggregator.AccountLike("a1", "1000.00", "CNY", archived = false),
        )
        val a = FinanceAggregator.netWorth(accounts, emptyList(), emptyList())
        val b = FinanceAggregator.netWorth(accounts, emptyList(), emptyList())
        val c = FinanceAggregator.netWorth(accounts, emptyList(), emptyList())
        assertEquals(a, b)
        assertEquals(b, c)
    }

    /**
     * 防御性兜底 —— 用例非空校验（避免 IDE 警告 "Test class should have at
     * least one public test method"）。
     */
    @Test
    fun sanityCheck_casesNotEmpty() {
        if (cases.isEmpty()) {
            fail("fixture 加载异常: cases 应非空")
        }
        // 顺手验证第一例字段非 null（防御性）。
        assertNotNull(cases.first().name)
    }
}
