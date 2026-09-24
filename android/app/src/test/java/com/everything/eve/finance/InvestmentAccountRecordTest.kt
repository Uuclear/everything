// ============================================================================
// InvestmentAccountRecord 单元测试（stage5-finance-v2 / Task 8 / FR-V2-D.1）
// ============================================================================
//
// 路径：android/app/src/test/java/com/everything/eve/finance/InvestmentAccountRecordTest.kt
//
// 验证目标（5 用例）：
//   1) parseHoldings 合法 holdings JSON：全部字段正常解析为 HoldingLike 列表；
//   2) parseHoldings holdings 段缺失：返回空列表（合法空仓）；
//   3) parseHoldings 任一字段非法（symbol 空 / shares=0 / shares 负数 / cost 负 /
//      currency 非 3 大写）：抛 IllegalArgumentException；
//   4) encodeHoldings 输出 JSON 与 parseHoldings 兼容（往返一致）；
//   5) build 工厂：kind 必须为 stock、currency 必须为 3 大写、id 非空校验。
//
// 设计要点：
//   - JUnit 4 + JVM 单测，不依赖 Robolectric（org.json 为 JVM 内置）；
//   - 与 Web 端 web/src/finance/__tests__/investmentAccountRecord.spec.ts
//     fixture 双向驱动守护。
// ============================================================================

package com.everything.eve.finance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class InvestmentAccountRecordTest {

    // ============================================================================
    // 1) parseHoldings 合法 holdings JSON
    // ============================================================================

    @Test
    fun parseHoldings_validJson_returnsAllHoldings() {
        val json = """
            {
              "holdings": [
                {"symbol": "AAPL", "shares": 10, "cost_basis_minor": 150000, "currency": "USD"},
                {"symbol": "0700.HK", "shares": 100, "cost_basis_minor": 380000, "currency": "HKD"}
              ]
            }
        """.trimIndent()

        val list = InvestmentAccountRecords.parseHoldings(json)

        assertEquals(2, list.size)
        val aapl = list[0]
        assertEquals("AAPL", aapl.symbol)
        assertEquals(10.0, aapl.shares, 1e-9)
        assertEquals(150000L, aapl.costBasisMinor)
        assertEquals("USD", aapl.currency)
        val hk = list[1]
        assertEquals("0700.HK", hk.symbol)
        assertEquals(100.0, hk.shares, 1e-9)
        assertEquals(380000L, hk.costBasisMinor)
        assertEquals("HKD", hk.currency)
    }

    // ============================================================================
    // 2) parseHoldings holdings 段缺失 → 空列表
    // ============================================================================

    @Test
    fun parseHoldings_missingHoldings_returnsEmpty() {
        val json = """{"note":"only note, no holdings"}"""
        val list = InvestmentAccountRecords.parseHoldings(json)
        assertEquals(0, list.size)
    }

    // ============================================================================
    // 3) 字段非法：symbol 空 / shares 0 / shares 负 / cost 负 / currency 小写
    // ============================================================================

    @Test
    fun parseHoldings_invalidFields_throwsIllegalArgument() {
        // symbol 空字符串
        try {
            InvestmentAccountRecords.parseHoldings(
                """{"holdings":[{"symbol":"","shares":1,"cost_basis_minor":1,"currency":"USD"}]}""",
            )
            fail("symbol 空字符串应抛 IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue("异常 message 必须含 symbol", e.message?.contains("symbol") == true)
        }

        // shares = 0
        try {
            InvestmentAccountRecords.parseHoldings(
                """{"holdings":[{"symbol":"A","shares":0,"cost_basis_minor":1,"currency":"USD"}]}""",
            )
            fail("shares=0 应抛 IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue("异常 message 必须含 shares", e.message?.contains("shares") == true)
        }

        // shares 负数
        try {
            InvestmentAccountRecords.parseHoldings(
                """{"holdings":[{"symbol":"A","shares":-3.5,"cost_basis_minor":1,"currency":"USD"}]}""",
            )
            fail("shares 负数应抛 IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue("异常 message 必须含 shares", e.message?.contains("shares") == true)
        }

        // cost_basis_minor 负数
        try {
            InvestmentAccountRecords.parseHoldings(
                """{"holdings":[{"symbol":"A","shares":1,"cost_basis_minor":-1,"currency":"USD"}]}""",
            )
            fail("cost_basis_minor 负数应抛 IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(
                "异常 message 必须含 cost_basis_minor",
                e.message?.contains("cost_basis_minor") == true,
            )
        }

        // currency 小写（非 3 大写）
        try {
            InvestmentAccountRecords.parseHoldings(
                """{"holdings":[{"symbol":"A","shares":1,"cost_basis_minor":1,"currency":"usd"}]}""",
            )
            fail("currency 小写应抛 IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue("异常 message 必须含 currency", e.message?.contains("currency") == true)
        }
    }

    // ============================================================================
    // 4) encodeHoldings ↔ parseHoldings 往返一致
    // ============================================================================

    @Test
    fun encodeHoldings_roundTripParseHoldings() {
        val original = listOf(
            HoldingLike("AAPL", 10.0, 150000L, "USD"),
            HoldingLike("0700.HK", 100.0, 380000L, "HKD"),
        )
        val encoded = InvestmentAccountRecords.encodeHoldings(original)
        val decoded = InvestmentAccountRecords.parseHoldings(encoded)

        assertEquals(2, decoded.size)
        assertEquals(original[0].symbol, decoded[0].symbol)
        assertEquals(original[0].shares, decoded[0].shares, 1e-9)
        assertEquals(original[0].costBasisMinor, decoded[0].costBasisMinor)
        assertEquals(original[0].currency, decoded[0].currency)
        assertEquals(original[1].symbol, decoded[1].symbol)
        assertEquals(original[1].shares, decoded[1].shares, 1e-9)
        assertEquals(original[1].costBasisMinor, decoded[1].costBasisMinor)
        assertEquals(original[1].currency, decoded[1].currency)
    }

    // ============================================================================
    // 5) build 工厂：kind/currency/id 校验
    // ============================================================================

    @Test
    fun build_invalidKindOrCurrency_throwsIllegalArgument() {
        // kind 非 stock
        try {
            InvestmentAccountRecords.build(
                id = "acc-1",
                kind = "investment",
                currency = "CNY",
                holdings = emptyList(),
                archived = false,
            )
            fail("kind != stock 应抛 IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("kind") == true)
        }

        // currency 非 3 大写
        try {
            InvestmentAccountRecords.build(
                id = "acc-1",
                kind = "stock",
                currency = "cny",
                holdings = emptyList(),
                archived = false,
            )
            fail("currency 小写应抛 IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("currency") == true)
        }

        // id 空
        try {
            InvestmentAccountRecords.build(
                id = "",
                kind = "stock",
                currency = "CNY",
                holdings = emptyList(),
                archived = false,
            )
            fail("id 空应抛 IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("id") == true)
        }

        // 合法字段：可构造成功
        val acc = InvestmentAccountRecords.build(
            id = "acc-1",
            kind = "stock",
            currency = "CNY",
            holdings = emptyList(),
            archived = true,
        )
        assertNotNull(acc)
        assertEquals("stock", acc.kind)
        assertEquals("CNY", acc.currency)
        assertEquals(0, acc.holdings.size)
        assertEquals(true, acc.archived)
    }
}