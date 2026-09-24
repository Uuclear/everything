// ============================================================================
// QuoteTable 单元测试（stage5-finance-v2 / Task 8 / FR-V2-D.2）
// ============================================================================
//
// 路径：android/app/src/test/java/com/everything/eve/finance/QuoteTableTest.kt
//
// 验证目标（5 用例）：
//   1) parse 合法行情包：含 ts / base / 3 条 quotes，priceMinor/currency/ts 全部解析；
//   2) parse 同 symbol 多次出现：取最后一条覆盖（last-write-wins）；
//   3) parse 字段非法（price_minor 负 / currency 非 3 大写 / ts 小数 / quotes 为空 /
//      version=2 异值 / ts 缺失）：抛 IllegalArgumentException；
//   4) priceMinorOf 命中 symbol 返回 minor；缺价返回 null；
//   5) encodeQuoteTable ↔ parse 往返一致。
//
// 设计要点：
//   - JUnit 4 + JVM 单测，不依赖 Robolectric（org.json 为 JVM 内置）；
//   - 与 Web 端 web/src/finance/__tests__/quoteTable.spec.ts fixture 双向驱动守护。
// ============================================================================

package com.everything.eve.finance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class QuoteTableTest {

    // ============================================================================
    // 1) parse 合法行情包
    // ============================================================================

    @Test
    fun parse_validPackage_returnsThreeQuotes() {
        val ts = 1735689600000L
        val json = """
            {
              "version": 1,
              "ts": $ts,
              "base": "CNY",
              "quotes": [
                {"symbol": "AAPL", "price_minor": 18500, "currency": "USD", "ts": $ts},
                {"symbol": "0700.HK", "price_minor": 38000, "currency": "HKD", "ts": $ts},
                {"symbol": "600519.SH", "price_minor": 170000, "currency": "CNY", "ts": $ts}
              ]
            }
        """.trimIndent()

        val table = QuoteTables.parse(json)

        assertEquals(ts, table.ts)
        assertEquals("CNY", table.base)
        assertEquals(3, table.quotes.size)
        val aapl = table.quotes["AAPL"]
        assertEquals(18500L, aapl?.priceMinor)
        assertEquals("USD", aapl?.currency)
        assertEquals(ts, aapl?.ts)
        val hk = table.quotes["0700.HK"]
        assertEquals(38000L, hk?.priceMinor)
        val sh = table.quotes["600519.SH"]
        assertEquals(170000L, sh?.priceMinor)
        assertEquals("CNY", sh?.currency)
    }

    // ============================================================================
    // 2) 同 symbol 多次出现 → last-write-wins
    // ============================================================================

    @Test
    fun parse_sameSymbolLastWins() {
        val ts = 1735689600000L
        val json = """
            {
              "version": 1,
              "ts": $ts,
              "quotes": [
                {"symbol": "AAPL", "price_minor": 18000, "currency": "USD", "ts": $ts},
                {"symbol": "AAPL", "price_minor": 18500, "currency": "USD", "ts": $ts}
              ]
            }
        """.trimIndent()

        val table = QuoteTables.parse(json)

        assertEquals(1, table.quotes.size)
        assertEquals(18500L, table.quotes["AAPL"]?.priceMinor)
    }

    // ============================================================================
    // 3) 字段非法：price_minor 负 / currency 非 3 大写 / ts 小数 / quotes 空 /
    //    version=2 异值 / ts 缺失
    // ============================================================================

    @Test
    fun parse_invalidFields_throwsIllegalArgument() {
        // price_minor 负数
        try {
            QuoteTables.parse(
                """{"version":1,"ts":1,"quotes":[{"symbol":"A","price_minor":-1,"currency":"USD","ts":1}]}""",
            )
            fail("price_minor 负数应抛 IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("price_minor") == true)
        }

        // currency 小写
        try {
            QuoteTables.parse(
                """{"version":1,"ts":1,"quotes":[{"symbol":"A","price_minor":1,"currency":"usd","ts":1}]}""",
            )
            fail("currency 小写应抛 IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("currency") == true)
        }

        // ts 小数
        try {
            QuoteTables.parse(
                """{"version":1,"ts":1.5,"quotes":[{"symbol":"A","price_minor":1,"currency":"USD","ts":1}]}""",
            )
            fail("ts 小数应抛 IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("ts") == true)
        }

        // quotes 空数组
        try {
            QuoteTables.parse("""{"version":1,"ts":1,"quotes":[]}""")
            fail("quotes 空数组应抛 IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("quotes") == true)
        }

        // version=2
        try {
            QuoteTables.parse(
                """{"version":2,"ts":1,"quotes":[{"symbol":"A","price_minor":1,"currency":"USD","ts":1}]}""",
            )
            fail("version=2 应抛 IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("version") == true)
        }

        // ts 缺失
        try {
            QuoteTables.parse(
                """{"version":1,"quotes":[{"symbol":"A","price_minor":1,"currency":"USD","ts":1}]}""",
            )
            fail("ts 缺失应抛 IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("ts") == true)
        }
    }

    // ============================================================================
    // 4) priceMinorOf 命中 / 缺价
    // ============================================================================

    @Test
    fun priceMinorOf_hitAndMissing() {
        val ts = 1L
        val json = """
            {"version":1,"ts":$ts,"quotes":[
              {"symbol":"AAPL","price_minor":18500,"currency":"USD","ts":$ts}
            ]}
        """.trimIndent()

        val table = QuoteTables.parse(json)

        assertEquals(18500L, QuoteTables.priceMinorOf("AAPL", table))
        assertNull("缺价应返回 null", QuoteTables.priceMinorOf("OTHER", table))
        // 非法 symbol 长度
        assertNull(
            "空 symbol 应返回 null",
            QuoteTables.priceMinorOf("", table),
        )
    }

    // ============================================================================
    // 5) encodeQuoteTable ↔ parse 往返一致
    // ============================================================================

    @Test
    fun encodeQuoteTable_roundTripParse() {
        val original = QuoteTable(
            ts = 1735689600000L,
            quotes = linkedMapOf(
                "AAPL" to Quote("AAPL", 18500L, "USD", 1735689600000L),
                "0700.HK" to Quote("0700.HK", 38000L, "HKD", 1735689600000L),
            ),
            base = "CNY",
        )

        val encoded = QuoteTables.encodeQuoteTable(original)
        val decoded = QuoteTables.parse(encoded)

        assertEquals(original.ts, decoded.ts)
        assertEquals(original.base, decoded.base)
        assertEquals(original.quotes.size, decoded.quotes.size)
        original.quotes.forEach { (symbol, q) ->
            val dq = decoded.quotes[symbol]
            assertEquals(q.symbol, dq?.symbol)
            assertEquals(q.priceMinor, dq?.priceMinor)
            assertEquals(q.currency, dq?.currency)
            assertEquals(q.ts, dq?.ts)
        }
    }
}