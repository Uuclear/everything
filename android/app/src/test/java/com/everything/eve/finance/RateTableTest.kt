// ============================================================================
// RateTable 纯函数单元测试（stage5-finance-v2 / B5 / TR-5.2 / FR-V2-C.2、C.3）
// ============================================================================
//
// 验证目标（≥8 用例）：
//   1. fixture SHA-256 硬编码守护（双端字节级一致, 与 Web rateTable.spec.ts 同 SHA）；
//   2. fixture convertCases 全量数据驱动（正向 / 反向 / 自交叉 / 缺失 null /
//      负数符号 / 半分边界, 11 条）；
//   3. parse 非法包：坏 key / 自交叉 key / rate<=0 / rate 非数字 / rate 无限 /
//      无 effective_ts / effective_ts 为负 / rates 空 / version 容错（缺失接受、
//      异值拒绝）/ 根非 JSON, 均抛 IllegalArgumentException 中文 message；
//   4. 负数符号双端锁定（fixture ⑨⑩⑪ + 大数同值双端守护）；
//   5. 业务大数（1e12 分 = 100 亿元）折算不溢出。
//
// 共享 fixture（__fixtures__/rate-table-cases.json, 与 Web 镜像同 SHA-256）。
//
// 关联:
//   - android/.../finance/RateTable.kt（被测目标）
//   - android/.../finance/__fixtures__/rate-table-cases.json（共享 fixture）
// ============================================================================

package com.everything.eve.finance

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.security.MessageDigest

/**
 * RateTables parse / convert JUnit 4 单元测试。
 */
class RateTableTest {

    companion object {
        /** fixture 文件 SHA-256（小写 hex）—— 双端一致性硬守护。 */
        const val FIXTURE_SHA256 = "7a1c77af553595423a3d0a338421e00eb7c0f59ca7b906d9768faf3abb2aea12"

        @BeforeClass
        @JvmStatic
        fun lockTimezone() {
            // 折算本身与时区无关；锁 Asia/Shanghai 仅为与 finance 套件口径一致。
            val cst = java.util.TimeZone.getTimeZone("Asia/Shanghai")
            java.util.TimeZone.setDefault(cst)
            System.setProperty("user.timezone", "Asia/Shanghai")
        }
    }

    /** 单条折算用例（fixture convertCases 真理源映射）。 */
    private data class ConvertCase(
        val name: String,
        val tableName: String,
        val amountMinor: Long,
        val from: String,
        val to: String,
        val expectedMinor: Long?,
    )

    private val fixtureBytes: ByteArray by lazy { loadFixtureBytes() }
    private val rootJson: JSONObject by lazy { JSONObject(String(fixtureBytes, Charsets.UTF_8)) }

    /** 按名解析 fixture tables 段（表对象重新序列化后走 parse 入口, 顺带验证 parse）。 */
    private val tables: Map<String, RateTable> by lazy {
        val tablesObj = rootJson.getJSONObject("tables")
        tablesObj.keys().asSequence().associateWith { name ->
            RateTables.parse(tablesObj.getJSONObject(name).toString())
        }
    }

    private val convertCases: List<ConvertCase> by lazy {
        val arr = rootJson.getJSONArray("convertCases")
        List(arr.length()) { i ->
            val c = arr.getJSONObject(i)
            ConvertCase(
                name = c.getString("name"),
                tableName = c.getString("table"),
                amountMinor = c.getLong("amountMinor"),
                from = c.getString("from"),
                to = c.getString("to"),
                expectedMinor = if (c.isNull("expectedMinor")) null else c.getLong("expectedMinor"),
            )
        }
    }

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

    /** 断言 parse 非法包必抛 IllegalArgumentException（中文 message 非空）。 */
    private fun assertRejected(json: String, clue: String) {
        val ex = assertThrows(
            "非法包未被拒绝：$clue",
            IllegalArgumentException::class.java,
        ) { RateTables.parse(json) }
        assertTrue("非法包错误 message 应为中文非空：$clue", ex.message?.isNotBlank() == true)
    }

    // ============================================================================
    // 1. fixture 完整性守护（SHA-256 + 用例数 + 表字段）
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
    fun fixture_hasAtLeastEightConvertCases() {
        assertTrue("convertCases 应不少于 8 条, 实际 = ${convertCases.size}", convertCases.size >= 8)
    }

    @Test
    fun fixture_mainTable_parsesWithEffectiveTsAndFourRates() {
        val main = tables.getValue("main")
        assertEquals("main 表生效时刻解析不一致", 1735689600000L, main.effectiveTs)
        assertEquals("main 表应有 4 条汇率", 4, main.rates.size)
        assertEquals(7.25, main.rates["USD/CNY"]!!, 0.0)
    }

    // ============================================================================
    // 2. fixture 数据驱动 —— 全部 convert / convertOrIdentity 用例
    // ============================================================================

    @Test
    fun fixture_driven_allConvertCasesPass() {
        for (tc in convertCases) {
            val table = tables.getValue(tc.tableName)
            val actual = RateTables.convert(tc.amountMinor, tc.from, tc.to, table)
            assertEquals("case[${tc.name}] convert 结果不一致", tc.expectedMinor, actual)
            // convertOrIdentity：null 时退回原值, 非 null 时与 convert 一致。
            val fallback = RateTables.convertOrIdentity(tc.amountMinor, tc.from, tc.to, table)
            val expectedFallback = tc.expectedMinor ?: tc.amountMinor
            assertEquals("case[${tc.name}] convertOrIdentity 结果不一致", expectedFallback, fallback)
        }
    }

    // ============================================================================
    // 3. parse 容错与非法包（version 容错 + 四类非法 + 扩展非法）
    // ============================================================================

    @Test
    fun parse_versionMissing_acceptedAsV1() {
        // version 缺失按 1 接受。
        val json = """{"effective_ts":1,"rates":{"USD/CNY":7.25}}"""
        val table = RateTables.parse(json)
        assertEquals(1L, table.effectiveTs)
        assertEquals(7.25, table.rates["USD/CNY"]!!, 0.0)
    }

    @Test
    fun parse_badVersion_rejected() {
        assertRejected(
            """{"version":2,"effective_ts":1,"rates":{"USD/CNY":7.25}}""",
            "version=2 必须拒绝"
        )
    }

    @Test
    fun parse_missingEffectiveTs_rejected() {
        assertRejected(
            """{"version":1,"rates":{"USD/CNY":7.25}}""",
            "缺少 effective_ts"
        )
    }

    @Test
    fun parse_negativeEffectiveTs_rejected() {
        assertRejected(
            """{"version":1,"effective_ts":-1,"rates":{"USD/CNY":7.25}}""",
            "effective_ts 为负"
        )
    }

    @Test
    fun parse_emptyRates_rejected() {
        assertRejected(
            """{"version":1,"effective_ts":1,"rates":{}}""",
            "rates 为空"
        )
    }

    @Test
    fun parse_badPairKey_rejected() {
        assertRejected(
            """{"version":1,"effective_ts":1,"rates":{"USD-CNY":7.25}}""",
            "键不是 FROM/TO 形态"
        )
    }

    @Test
    fun parse_sameCurrencyPairKey_rejected() {
        assertRejected(
            """{"version":1,"effective_ts":1,"rates":{"USD/USD":1.0}}""",
            "源币与目标币相同"
        )
    }

    @Test
    fun parse_rateZeroAndNegative_rejected() {
        assertRejected(
            """{"version":1,"effective_ts":1,"rates":{"USD/CNY":0}}""",
            "汇率为 0"
        )
        assertRejected(
            """{"version":1,"effective_ts":1,"rates":{"USD/CNY":-7.25}}""",
            "汇率为负"
        )
    }

    @Test
    fun parse_rateNonNumber_rejected() {
        assertRejected(
            """{"version":1,"effective_ts":1,"rates":{"USD/CNY":"7.25"}}""",
            "汇率为字符串"
        )
        assertRejected(
            """{"version":1,"effective_ts":1,"rates":{"USD/CNY":null}}""",
            "汇率为 null"
        )
    }

    @Test
    fun parse_rateInfinite_rejected() {
        // 1e999 超出 Double 有限范围, org.json 读为 Infinity, 必须拒绝。
        assertRejected(
            """{"version":1,"effective_ts":1,"rates":{"USD/CNY":1e999}}""",
            "汇率无限大"
        )
    }

    @Test
    fun parse_rootNotJson_rejected() {
        assertRejected("not-a-json", "根为裸字符串")
        assertRejected("[]", "根为数组")
    }

    // ============================================================================
    // 4. convert 边界 —— 空表自交叉 / 非法代码 / 大数
    // ============================================================================

    @Test
    fun convert_selfIdentity_holdsEvenWithEmptyTable() {
        // 自交叉不读表, 内存空表（parse 不允许空 rates, 但数据类可直接构造）也成立。
        val emptyTable = RateTable(effectiveTs = 0L, rates = emptyMap())
        assertEquals(
            "自交叉必须恒等返回, 与汇率表无关",
            12345L, RateTables.convert(12345L, "USD", "USD", emptyTable)
        )
    }

    @Test
    fun convert_illegalCode_returnsNull() {
        val table = tables.getValue("main")
        assertNull("两字母代码应返回 null", RateTables.convert(100L, "US", "CNY", table))
        assertNull("小写代码应返回 null", RateTables.convert(100L, "USD", "cny", table))
        // 非法代码即便 from==to 也不能恒等（不是合法币种）。
        assertNull("非法代码自交叉也应返回 null", RateTables.convert(100L, "usd", "usd", table))
    }

    @Test
    fun convert_bigBusinessAmount_doesNotOverflow() {
        // 100 亿元 = 1e12 分；×7.25 = 7.25e12 分, 远小于 Long.MAX_VALUE ≈ 9.22e18。
        val table = tables.getValue("main")
        val result = RateTables.convert(1_000_000_000_000L, "USD", "CNY", table)
        assertEquals("1e12 分 USD 折算 CNY 不一致", 7_250_000_000_000L, result)
    }
}
