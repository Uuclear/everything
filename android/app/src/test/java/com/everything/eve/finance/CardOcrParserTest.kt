package com.everything.eve.finance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** parseCardText 合成文本向量测试（无真实卡面）。 */
class CardOcrParserTest {

    // Visa 测试 PAN（公开 Luhn 样例）
    private val visaPan = "4111111111111111"

    @Test
    fun spacedPanWithValidThru() {
        val text = """
            CHINA MERCHANTS BANK
            4111 1111 1111 1111
            VALID THRU 12/28
            CARD HOLDER ZHANG SAN
        """.trimIndent()
        val hint = parseCardText(text)
        assertNotNull(hint)
        assertEquals(visaPan, hint!!.pan)
        assertEquals("1111", hint.last4)
        assertEquals(12, hint.expiryMonth)
        assertEquals(2028, hint.expiryYear)
        assertEquals("ZHANG SAN", hint.holder)
    }

    @Test
    fun compactPanChineseExpiryLabel() {
        val text = "4111111111111111\n有效期至 03/2027"
        val hint = parseCardText(text)
        assertNotNull(hint)
        assertTrue(hint!!.pan!!.length in 13..19)
        assertEquals(3, hint.expiryMonth)
        assertEquals(2027, hint.expiryYear)
    }

    @Test
    fun hyphenSeparatedPan() {
        val text = "4111-1111-1111-1111\nEXP 09/26"
        val hint = parseCardText(text)
        assertNotNull(hint)
        assertEquals(visaPan, hint!!.pan)
        assertEquals(9, hint.expiryMonth)
        assertEquals(2026, hint.expiryYear)
    }

    @Test
    fun holderLabelChinese() {
        val text = "6228480402564890018\n持卡人 李明"
        val hint = parseCardText(text)
        assertNotNull(hint)
        assertEquals("李明", hint!!.holder)
    }

    @Test
    fun invalidLuhnIgnored() {
        val text = "1234567890123456\nEXP 01/30"
        assertNull(parseCardText(text))
    }

    @Test
    fun expiryOnlyNoPan() {
        assertNull(parseCardText("欢迎光临\nEXP 11/29"))
    }

    @Test
    fun panOnlyNoExpiry() {
        val hint = parseCardText("4111111111111111")
        assertNotNull(hint)
        assertEquals(visaPan, hint!!.pan)
        assertNull(hint.expiryMonth)
    }

    @Test
    fun gluedDigitsInNoise() {
        val text = "NO:4111111111111111END"
        val hint = parseCardText(text)
        assertNotNull(hint)
        assertEquals(visaPan, hint!!.pan)
    }

    @Test
    fun twoYearExpiry() {
        val text = "4111111111111111 GOOD THRU 05/31"
        val hint = parseCardText(text)
        assertNotNull(hint)
        assertEquals(5, hint!!.expiryMonth)
        assertEquals(2031, hint.expiryYear)
    }

    @Test
    fun mastercardSample() {
        val pan = "5555555555554444"
        val hint = parseCardText("MASTERCARD\n$pan\nVALID THRU 07/27")
        assertNotNull(hint)
        assertEquals(pan, hint!!.pan)
        assertEquals("4444", hint.last4)
    }

    @Test
    fun blankReturnsNull() {
        assertNull(parseCardText(""))
        assertNull(parseCardText("   \n  "))
    }

    @Test
    fun phoneNotPan() {
        assertNull(parseCardText("客服电话 400-123-4567"))
    }
}
