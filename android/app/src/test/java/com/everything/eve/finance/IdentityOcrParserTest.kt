package com.everything.eve.finance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 身份证 OCR 解析合成文本测试（号码为算法合法样例，非真实证件）。 */
class IdentityOcrParserTest {

    // 校验位合法的 18 位测试号
    private val sampleId = "110101199003077432"

    @Test
    fun frontTypicalLayout() {
        val text = """
            姓名张三
            性别男 民族汉
            住址北京市东城区某某街道1号
            公民身份号码 $sampleId
        """.trimIndent()
        val hint = parseIdentityFrontText(text)
        assertNotNull(hint)
        assertEquals("张三", hint!!.name)
        assertEquals(sampleId, hint.number)
        assertTrueContains(hint.address, "东城区")
    }

    @Test
    fun frontSpacedIdNumber() {
        val spaced = "110101 19900307 7432"
        val text = "姓名李四\n公民身份号码 $spaced"
        val hint = parseIdentityFrontText(text)
        assertNotNull(hint)
        assertEquals(sampleId, hint!!.number)
        assertEquals("李四", hint.name)
    }

    @Test
    fun frontIdOnly() {
        val hint = parseIdentityFrontText("号码 $sampleId")
        assertNotNull(hint)
        assertEquals(sampleId, hint!!.number)
    }

    @Test
    fun frontInvalidChecksumIgnored() {
        assertNull(parseIdentityFrontText("110101199003077430"))
    }

    @Test
    fun frontNameBeforeGenderLine() {
        val text = "王五 性别女\n$sampleId"
        val hint = parseIdentityFrontText(text)
        assertNotNull(hint)
        assertEquals("王五", hint!!.name)
    }

    @Test
    fun backIssuerAndRange() {
        val text = """
            签发机关北京市公安局东城分局
            有效期限 2015.03.08-2025.03.08
        """.trimIndent()
        val hint = parseIdentityBackText(text)
        assertNotNull(hint)
        assertEquals("北京市公安局东城分局", hint!!.issuer)
        assertEquals("2015-03-08", hint.validFrom)
        assertEquals("2025-03-08", hint.expiresOn)
    }

    @Test
    fun backLongTerm() {
        val text = "签发机关上海市公安局\n有效期限 2020.01.01-长期"
        val hint = parseIdentityBackText(text)
        assertNotNull(hint)
        assertEquals("长期", hint!!.expiresOn)
        assertEquals("2020-01-01", hint.validFrom)
    }

    @Test
    fun backIssuerOnly() {
        val hint = parseIdentityBackText("签发机关广州市公安局天河分局")
        assertNotNull(hint)
        assertEquals("广州市公安局天河分局", hint!!.issuer)
    }

    @Test
    fun backDatesWithoutIssuer() {
        val hint = parseIdentityBackText("有效期 2018.05.10-2038.05.10")
        assertNotNull(hint)
        assertEquals("2018-05-10", hint!!.validFrom)
        assertEquals("2038-05-10", hint.expiresOn)
    }

    @Test
    fun backBlankNull() {
        assertNull(parseIdentityBackText(""))
    }

    @Test
    fun frontBlankNull() {
        assertNull(parseIdentityFrontText("   "))
    }

    @Test
    fun frontAddressLabel() {
        val text = "住址广东省深圳市南山区科技园路88号\n$sampleId"
        val hint = parseIdentityFrontText(text)
        assertNotNull(hint)
        assertTrueContains(hint!!.address, "深圳")
    }

    private fun assertTrueContains(hay: String?, needle: String) {
        assertNotNull(hay)
        assertTrue(hay!!.contains(needle))
    }
}
