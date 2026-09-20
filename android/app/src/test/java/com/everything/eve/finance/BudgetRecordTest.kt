// ============================================================================
// BudgetRecord / validateBudget 单元测试（stage5-finance-v2 / B6 纯函数层）
// ============================================================================
//
// 验证目标（7 用例）：合法记录通过；scope 非法；金额非法；预警阈值大于阻断
// 阈值（倒挂）；endTs 小于 startTs；id 缺失；startTs 非正 / category 超长。
//
// 关联:
//   - android/.../finance/FinanceRecords.kt#validateBudget（被测目标）
// ============================================================================

package com.everything.eve.finance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FinanceRecords.validateBudget JUnit 4 单元测试。
 */
class BudgetRecordTest {

    /** 构造一条合法预算，各用例按需改坏单个字段。 */
    private fun validBudget() = BudgetRecord(
        id = "budget-0001",
        scope = "monthly",
        category = "餐饮",
        amountMinor = "3000.00",
        currency = "CNY",
        startTs = 1_767_196_800_000L,
        endTs = 1_798_732_799_999L,
        warningThresholdPct = 80,
        blockThresholdPct = 100,
        active = true,
        createdAt = 1_767_196_800_000L,
        updatedAt = 1_767_196_800_000L,
    )

    private fun invalidReason(p: BudgetRecord): String =
        (FinanceRecords.validateBudget(p) as ValidationResult.Invalid).reason

    @Test
    fun validBudget_passes() {
        // custom scope 且 endTs 恰好等于 startTs 也合法（有效期双闭区间允许等长一刻）。
        val custom = validBudget().copy(
            scope = "custom",
            startTs = 1_780_243_200_000L,
            endTs = 1_780_243_200_000L,
        )
        assertEquals(ValidationResult.Ok, FinanceRecords.validateBudget(validBudget()))
        assertEquals(ValidationResult.Ok, FinanceRecords.validateBudget(custom))
    }

    @Test
    fun invalidScope_rejected() {
        val p = validBudget().copy(scope = "quarterly")
        val result = FinanceRecords.validateBudget(p)
        assertTrue(result is ValidationResult.Invalid)
        assertEquals("scope 非法", invalidReason(p))
    }

    @Test
    fun invalidAmount_rejected() {
        // 0 元、三位小数、带字母均属非法金额。
        assertEquals("amountMinor 非法", invalidReason(validBudget().copy(amountMinor = "0.00")))
        assertEquals("amountMinor 非法", invalidReason(validBudget().copy(amountMinor = "10.999")))
        assertEquals("amountMinor 非法", invalidReason(validBudget().copy(amountMinor = "abc")))
    }

    @Test
    fun invertedThresholds_rejected() {
        val p = validBudget().copy(warningThresholdPct = 120, blockThresholdPct = 100)
        val result = FinanceRecords.validateBudget(p)
        assertTrue(result is ValidationResult.Invalid)
        assertTrue(invalidReason(p).contains("阈值"))
    }

    @Test
    fun endBeforeStart_rejected() {
        val p = validBudget().copy(
            startTs = 1_780_243_200_000L,
            endTs = 1_780_156_800_000L,
        )
        assertEquals("endTs 必须为整数且大于等于 startTs", invalidReason(p))
    }

    @Test
    fun missingId_rejected() {
        val p = validBudget().copy(id = "")
        assertEquals("id 缺失", invalidReason(p))
    }

    @Test
    fun nonPositiveStartAndTooLongCategory_rejected() {
        assertEquals(
            "startTs 必须为正整数毫秒",
            invalidReason(validBudget().copy(startTs = 0L, endTs = 0L)),
        )
        val longCategory = "分".repeat(21)
        val p = validBudget().copy(category = longCategory)
        assertEquals("category 长度需在 1-20 字符", invalidReason(p))
    }
}
