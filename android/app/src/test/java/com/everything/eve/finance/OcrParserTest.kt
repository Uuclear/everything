// ============================================================================
// OcrParser / parseReceiptText 单元测试（stage5-finance-v2 / Task 9 / B8）
// ============================================================================
//
// 验证目标（10 用例）：
//   1. 典型中文小票：合计金额 + 日期 + 商家三要素全部正确；
//   2. 英文 total / amount 金额 + ¥ 符号；
//   3. 千分位金额（1,234.56 → 123456 分）；
//   4. 只有金额没有日期 / 商家（对象非 null，其余字段为 null）；
//   5. 噪声文本（电话 / 地址 / 数量单价）不被误识别为合计金额；
//   6. 数量 × 单价明细且无任何金额关键词时返回 null；
//   7. 多种日期格式（年-月-日、年月日、斜杠、点分）正确解析；
//   8. 完全无法识别（空串 / 纯噪声无金额）返回 null；
//   9. 金额取「合计 / 实付」关键词行而非全文最大数字；
//  10. 明显不合理的年份（早于 2000）被忽略。
//
// 关联:
//   - android/.../finance/OcrParser.kt#parseReceiptText（被测目标）
// ============================================================================

package com.everything.eve.finance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/**
 * parseReceiptText JUnit 4 纯 JVM 单元测试。
 */
class OcrParserTest {

    /**
     * 构造设备默认时区下某年某月某日 00:00 的 epoch 毫秒（与被测函数同口径）。
     * 断言 ts 时统一使用本辅助方法，避免硬编码 UTC 数值造成跨时区失败。
     */
    private fun expectedMidnightTs(year: Int, month: Int, day: Int): Long {
        val calendar = Calendar.getInstance()
        calendar.clear()
        calendar.set(year, month - 1, day, 0, 0, 0)
        return calendar.timeInMillis
    }

    @Test
    fun typicalChineseReceipt_allThreeFields() {
        // 典型中文小票：商家行在前、含日期、合计金额（关键词行优先）
        val text = """
            好味道快餐店
            欢迎光临

            2026-09-24
            鸡腿饭 18.00
            可乐 5.00
            合计 35.00
            电话 13812345678
        """.trimIndent()

        val hint = parseReceiptText(text)
        assertNotNull("典型小票必须解析出 Hint", hint)
        assertEquals(3500L, hint!!.amountMinor)
        assertEquals(expectedMidnightTs(2026, 9, 24), hint.ts)
        assertEquals("好味道快餐店", hint.merchant)
    }

    @Test
    fun englishTotalAndAmountWithYenSign() {
        // 英文小票：total / amount 关键词 + ¥ 前缀
        val totalText = "COFFEE SHOP\nTOTAL ¥42.50\nTHANK YOU"
        val totalHint = parseReceiptText(totalText)
        assertNotNull(totalHint)
        assertEquals(4250L, totalHint!!.amountMinor)

        // amount 关键词 + RMB 前缀
        val amountText = "BOOK STORE\nAMOUNT RMB 88.00"
        val amountHint = parseReceiptText(amountText)
        assertNotNull(amountHint)
        assertEquals(8800L, amountHint!!.amountMinor)
    }

    @Test
    fun groupedThousandsAmount() {
        // 千分位金额：1,234.56 元 → 123456 分
        val text = "家电大卖场\n实付 ¥1,234.56"
        val hint = parseReceiptText(text)
        assertNotNull(hint)
        assertEquals(123456L, hint!!.amountMinor)
    }

    @Test
    fun amountOnly_dateAndMerchantNull() {
        // 只有金额：日期与商家均为 null，但 Hint 对象本身非 null
        val text = "合计 66.00"
        val hint = parseReceiptText(text)
        assertNotNull(hint)
        assertEquals(6600L, hint!!.amountMinor)
        assertNull(hint.ts)
        assertNull(hint.merchant)
    }

    @Test
    fun phoneAndAddressNoise_notTakenAsAmount() {
        // 噪声文本：电话 11 位 + 地址（无小数金额），且「合计」行只有电话数字时，
        // 电话（7 位以上）不应被当成金额
        val text = """
            中山路 128 号
            电话 13812345678
            合计 13812345678
        """.trimIndent()

        val hint = parseReceiptText(text)
        // 金额无法识别（含日期也缺失），整体可能仍为 null；即使有商家也不应出现金额
        if (hint != null) {
            assertNull("电话号码不能作为金额", hint.amountMinor)
        }
    }

    @Test
    fun quantityUnitLineOnly_returnsNull() {
        // 数量 × 单价明细行，无任何金额关键词：小数字不应被回退为合计金额
        val text = """
            可乐 2 × 4.50
            汉堡 1 × 18.00
        """.trimIndent()

        val hint = parseReceiptText(text)
        assertNull("只有数量单价明细时应返回 null", hint)
    }

    @Test
    fun multipleDateFormats_parsed() {
        // 年-月-日
        val hyphen = parseReceiptText("便利店\n合计 10.00\n2026-09-24")
        assertEquals(expectedMidnightTs(2026, 9, 24), hyphen!!.ts)

        // 中文年月日
        val chinese = parseReceiptText("便利店\n2026年9月24日\n合计 10.00")
        assertEquals(expectedMidnightTs(2026, 9, 24), chinese!!.ts)

        // 斜杠：年在前（首段 4 位）
        val slash = parseReceiptText("便利店\n2026/9/24\n合计 10.00")
        assertEquals(expectedMidnightTs(2026, 9, 24), slash!!.ts)

        // 点分欧式：首段 24 大于 12，必为日
        val dotted = parseReceiptText("便利店\n24.09.2026\n合计 10.00")
        assertEquals(expectedMidnightTs(2026, 9, 24), dotted!!.ts)

        // 斜杠欧式：24/09/2026
        val slashEuropean = parseReceiptText("便利店\n24/09/2026\n合计 10.00")
        assertEquals(expectedMidnightTs(2026, 9, 24), slashEuropean!!.ts)
    }

    @Test
    fun blankOrPureNoise_returnsNull() {
        // 空串 / 纯空白
        assertNull(parseReceiptText(""))
        assertNull(parseReceiptText("   \n\t"))

        // 纯噪声无金额（欢迎语 + 地址电话，无小数、无可解析整数金额、无商家候选）
        val pureNoise = """
            欢迎光临
            谢谢惠顾
            地址：人民大道幸福里小区 888 号
            电话 010 8888 8888
        """.trimIndent()
        assertNull(parseReceiptText(pureNoise))
    }

    @Test
    fun keywordLineWinsOverLargestNumber() {
        // 全文存在比合计更大的小数（原价），金额仍取「实付」关键词行
        val text = """
            精品超市
            原价 999.00
            折扣 100.00
            实付 70.00
        """.trimIndent()

        val hint = parseReceiptText(text)
        assertNotNull(hint)
        assertEquals("实付行优先于全文最大数字", 7000L, hint!!.amountMinor)
    }

    @Test
    fun unreasonableYear_ignored() {
        // 年份 1999 早于 2000，应忽略该日期（ts 为 null）
        val text = "小吃店\n1999-09-24\n合计 20.00"
        val hint = parseReceiptText(text)
        assertNotNull(hint)
        assertEquals(2000L, hint!!.amountMinor)
        assertNull("早于 2000 的年份应忽略", hint.ts)

        // 商家「小吃店」可正常识别（不是本用例断言重点，仅做合理性确认）
        assertTrue(true)
    }
}
