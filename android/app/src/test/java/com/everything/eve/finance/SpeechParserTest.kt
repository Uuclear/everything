// ============================================================================
// SpeechParser / parseSpeechText 单元测试（stage5-finance-v2 / Task 9 / B8）
// ============================================================================
//
// 验证目标（10 用例）：
//   1. spec FR-V2-E.2 范例「买了个汉堡花了 35 元」→ 3500 分 + 餐饮；
//   2. 中文数字：三十五块 → 3500 分；
//   3. 中文数字：一百二十元 → 12000 分；
//   4. 中文数字：两千三百五十九 → 235900 分；
//   5. 小数金额：35.5 元 → 3550 分；
//   6. 交通关键词：打车花了 28 元 → 交通；
//   7. 无金额文本返回 null；
//   8. 无分类关键词时 category 为 null 但金额有效；
//   9. 「昨天」时间语义：ts 比 now 早约 1 天（23 至 25 小时范围断言）；
//  10. 空串返回 null。
//
// 关联:
//   - android/.../finance/SpeechParser.kt#parseSpeechText（被测目标）
// ============================================================================

package com.everything.eve.finance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * parseSpeechText JUnit 4 纯 JVM 单元测试。
 */
class SpeechParserTest {

    @Test
    fun specExample_burgerAnd35Yuan() {
        // spec FR-V2-E.2 范例：金额 35 元、分类输出中文「餐饮」
        val hint = parseSpeechText("我刚买了个汉堡花了 35 元")
        assertNotNull("范例必须解析出 Hint", hint)
        assertEquals(3500L, hint!!.amountMinor)
        assertEquals("餐饮", hint.category)
    }

    @Test
    fun chineseNumber_thirtyFiveKuai() {
        // 中文数字 + 块（块等价于元）
        val hint = parseSpeechText("中午吃快餐三十五块")
        assertNotNull(hint)
        assertEquals(3500L, hint!!.amountMinor)
    }

    @Test
    fun chineseNumber_oneHundredTwentyYuan() {
        // 一百二十元 → 12000 分
        val hint = parseSpeechText("买水果花了一百二十元")
        assertNotNull(hint)
        assertEquals(12000L, hint!!.amountMinor)
    }

    @Test
    fun chineseNumber_twoThousandThreeHundredFiftyNine() {
        // 两千三百五十九 → 2359 元 → 235900 分
        val hint = parseSpeechText("交房租转了两千三百五十九")
        assertNotNull(hint)
        assertEquals(235900L, hint!!.amountMinor)
    }

    @Test
    fun arabicDecimal_35_5_yuan() {
        // 小数金额：35.5 元 → 3550 分
        val hint = parseSpeechText("吃饭花了 35.5 元")
        assertNotNull(hint)
        assertEquals(3550L, hint!!.amountMinor)
    }

    @Test
    fun transportCategory_taxi28Yuan() {
        // 交通类关键词：打车
        val hint = parseSpeechText("今天上班打车花了 28 元")
        assertNotNull(hint)
        assertEquals(2800L, hint!!.amountMinor)
        assertEquals("交通", hint.category)
    }

    @Test
    fun noAmount_returnsNull() {
        // 整句不含任何金额数字
        assertNull(parseSpeechText("今天天气真不错啊"))
    }

    @Test
    fun amountValidButCategoryNull() {
        // 金额有效、但无任何分类关键词：category 为 null
        val hint = parseSpeechText("花了 99")
        assertNotNull(hint)
        assertEquals(9900L, hint!!.amountMinor)
        assertNull("无分类关键词时 category 必须为 null", hint.category)
    }

    @Test
    fun yesterdayTs_isAboutOneDayBefore() {
        // 在解析前记录 now，解析后断言 ts 落在 now 之前 23 至 25 小时之间
        // （跨夏令时 / 边界误差留 1 小时容忍）
        val before = System.currentTimeMillis()
        val hint = parseSpeechText("昨天打车花了 28 元")
        val after = System.currentTimeMillis()
        assertNotNull(hint)

        val deltaBefore = before - hint!!.ts
        val deltaAfter = after - hint.ts
        val hour = 3_600_000L

        // ts 相对 before 与 after 的回退量都应落在 23 至 25 小时窗口内
        assert(deltaBefore in 23 * hour..25 * hour) {
            "ts 相对 before 回退量超出 23 至 25 小时：$deltaBefore"
        }
        assert(deltaAfter in 23 * hour..25 * hour) {
            "ts 相对 after 回退量超出 23 至 25 小时：$deltaAfter"
        }
    }

    @Test
    fun blankText_returnsNull() {
        // 空串 / 纯空白
        assertNull(parseSpeechText(""))
        assertNull(parseSpeechText("    "))
    }
}
