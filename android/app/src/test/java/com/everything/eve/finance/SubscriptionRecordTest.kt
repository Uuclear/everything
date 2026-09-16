// ============================================================================
// SubscriptionRecord 单元测试（stage5-finance-v2 / Task 2 / TR-2.7c）
// ============================================================================
//
// 验证目标（≥16 用例, JUnit 4, 4 个 describe block × 4 个 it）：
//   1. describe("合法场景") —— 最小必填字段构造一个合法 record, validate* 返回 Ok；
//   2. describe("schemaVersion 校验") —— schemaVersion != 2 时返回 Invalid；
//   3. describe("关键字段非法") —— amountMinor='0.00' / billingCycle='invalid' /
//      customDays=null 但 cycle=custom_days 三个 Invalid 分支；
//   4. describe("工具函数") —— isValidDecimalString / isValidDecimalNonNegative /
//      isValidCurrencyCode / isValidSha256Hex 各 ≥1 个 valid + 1 个 invalid 场景。
//
// 编码纪律:
//   - JUnit 4 (@Test + @org.junit.Assert) 与项目一致；
//   - 用嵌套类模拟 describe block, 方法名 `describe_<name>__it_<name>` 表达 it；
//   - fixture 用 baseStart = 1735689600000 (固定毫秒) + 偏移量构造合法时间戳；
//   - 错误断言用 `assertTrue(result is ValidationResult.Invalid)` +
//     `assertEquals("...", (result as ValidationResult.Invalid).reason)`；
//   - 仅依赖 org.junit.Test / kotlin.test.assertEquals / kotlin.test.assertTrue。
//
// 关联:
//   - android/.../finance/FinanceRecords.kt（被测目标）
// ============================================================================

package com.everything.eve.finance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SubscriptionRecord 校验 & 工具函数 JUnit 4 单元测试。
 */
class SubscriptionRecordTest {

    // ============================================================================
    // fixture 工厂 —— 用固定的 baseStart 毫秒 + 偏移量构造合法时间戳
    // ============================================================================

    // 1735689600000 = 2025-01-01 00:00:00 UTC（与项目其他测试同款基准时间戳）。
    private val baseStart = 1735689600000L
    private val baseSha256Hex = "a".repeat(64)

    /**
     * 构造一个最小必填字段的合法 SubscriptionRecord。
     *
     * 用于"合法场景"块的成功断言；其他用例通过 .copy(...) 派生。
     */
    private fun legalSubscription(
        id: String = "sub-001",
        amountMinor: String = "120.00",
        billingCycle: String = "monthly",
        customDays: Long? = null,
    ): SubscriptionRecord = SubscriptionRecord(
        id = id,
        name = "Netflix",
        provider = "Netflix Inc.",
        amountMinor = amountMinor,
        currency = "CNY",
        billingCycle = billingCycle,
        customDays = customDays,
        startTs = baseStart,
        nextRenewalTs = baseStart + 30L * 86_400_000L, // 30 天后, 一定 >= startTs
        reminders = listOf(0L, 1440L),
        active = true,
        category = "entertainment",
        createdAt = baseStart,
        updatedAt = baseStart,
    )

    // ============================================================================
    // 1. describe("合法场景") —— 最小必填字段构造, validateSubscription 返回 Ok
    // ============================================================================

    /** monthly 周期 / amountMinor='120.00' 合法。 */
    @Test
    fun describe_legalScene__it_minimalFieldsReturnsOk() {
        val p = legalSubscription()
        assertEquals(ValidationResult.Ok, FinanceRecords.validateSubscription(p))
    }

    /** custom_days 周期 / customDays=30 合法（customDays 必须为正整数）。 */
    @Test
    fun describe_legalScene__it_customDaysCyclePasses() {
        val p = legalSubscription(billingCycle = "custom_days", customDays = 30L)
        assertEquals(ValidationResult.Ok, FinanceRecords.validateSubscription(p))
    }

    /** quarterly / yearly 周期合法。 */
    @Test
    fun describe_legalScene__it_quarterlyAndYearlyCyclesPass() {
        val q = legalSubscription(billingCycle = "quarterly")
        val y = legalSubscription(id = "sub-002", billingCycle = "yearly")
        assertEquals(ValidationResult.Ok, FinanceRecords.validateSubscription(q))
        assertEquals(ValidationResult.Ok, FinanceRecords.validateSubscription(y))
    }

    /** 空 reminders 列表合法（允许不设置任何提醒偏移）。 */
    @Test
    fun describe_legalScene__it_emptyRemindersPasses() {
        val p = legalSubscription().copy(reminders = emptyList())
        assertEquals(ValidationResult.Ok, FinanceRecords.validateSubscription(p))
    }

    // ============================================================================
    // 2. describe("schemaVersion 校验") —— schemaVersion != 2 返回 Invalid
    // ============================================================================

    /** schemaVersion=1 应该被拒绝。 */
    @Test
    fun describe_schemaVersion__it_rejectsSchemaVersion1() {
        val p = legalSubscription().copy(schemaVersion = 1)
        val result = FinanceRecords.validateSubscription(p)
        assertTrue("schemaVersion=1 应返回 Invalid, 实际 = $result", result is ValidationResult.Invalid)
        assertEquals(
            "schemaVersion 必须是 2",
            (result as ValidationResult.Invalid).reason
        )
    }

    /** schemaVersion=0 应该被拒绝。 */
    @Test
    fun describe_schemaVersion__it_rejectsSchemaVersion0() {
        val p = legalSubscription().copy(schemaVersion = 0)
        val result = FinanceRecords.validateSubscription(p)
        assertTrue("schemaVersion=0 应返回 Invalid, 实际 = $result", result is ValidationResult.Invalid)
        assertEquals(
            "schemaVersion 必须是 2",
            (result as ValidationResult.Invalid).reason
        )
    }

    /** schemaVersion=3 应该被拒绝（仅 2 是当前允许版本）。 */
    @Test
    fun describe_schemaVersion__it_rejectsSchemaVersion3() {
        val p = legalSubscription().copy(schemaVersion = 3)
        val result = FinanceRecords.validateSubscription(p)
        assertTrue("schemaVersion=3 应返回 Invalid, 实际 = $result", result is ValidationResult.Invalid)
        assertEquals(
            "schemaVersion 必须是 2",
            (result as ValidationResult.Invalid).reason
        )
    }

    /** schemaVersion=2 + 默认参数应该返回 Ok（合法基线）。 */
    @Test
    fun describe_schemaVersion__it_acceptsSchemaVersion2() {
        val p = legalSubscription() // schemaVersion 默认 = FINANCE_V2_SCHEMA_VERSION = 2
        assertEquals(ValidationResult.Ok, FinanceRecords.validateSubscription(p))
    }

    // ============================================================================
    // 3. describe("关键字段非法") —— 至少测 3 个 Invalid 分支
    // ============================================================================

    /** amountMinor='0.00' 非法（必填金额字段必须 > 0）。 */
    @Test
    fun describe_keyFieldsInvalid__it_rejectsZeroAmount() {
        val p = legalSubscription().copy(amountMinor = "0.00")
        val result = FinanceRecords.validateSubscription(p)
        assertTrue("amountMinor='0.00' 应返回 Invalid, 实际 = $result", result is ValidationResult.Invalid)
        assertEquals(
            "amountMinor 非法",
            (result as ValidationResult.Invalid).reason
        )
    }

    /** billingCycle='invalid' 非法（不在 monthly/quarterly/yearly/custom_days 集合内）。 */
    @Test
    fun describe_keyFieldsInvalid__it_rejectsInvalidBillingCycle() {
        val p = legalSubscription().copy(billingCycle = "invalid")
        val result = FinanceRecords.validateSubscription(p)
        assertTrue("billingCycle='invalid' 应返回 Invalid, 实际 = $result", result is ValidationResult.Invalid)
        assertEquals(
            "billingCycle 非法",
            (result as ValidationResult.Invalid).reason
        )
    }

    /** billingCycle='custom_days' 但 customDays=null 非法（custom_days 必须配正整数）。 */
    @Test
    fun describe_keyFieldsInvalid__it_rejectsNullCustomDaysWhenCustomCycle() {
        val p = legalSubscription().copy(billingCycle = "custom_days", customDays = null)
        val result = FinanceRecords.validateSubscription(p)
        assertTrue(
            "billingCycle=custom_days 但 customDays=null 应返回 Invalid, 实际 = $result",
            result is ValidationResult.Invalid
        )
        assertEquals(
            "customDays 在 billingCycle=custom_days 时必须为正整数",
            (result as ValidationResult.Invalid).reason
        )
    }

    /** billingCycle='custom_days' 但 customDays=0 也非法（必须 > 0）。 */
    @Test
    fun describe_keyFieldsInvalid__it_rejectsZeroCustomDaysWhenCustomCycle() {
        val p = legalSubscription().copy(billingCycle = "custom_days", customDays = 0L)
        val result = FinanceRecords.validateSubscription(p)
        assertTrue(
            "billingCycle=custom_days 但 customDays=0 应返回 Invalid, 实际 = $result",
            result is ValidationResult.Invalid
        )
        assertEquals(
            "customDays 在 billingCycle=custom_days 时必须为正整数",
            (result as ValidationResult.Invalid).reason
        )
    }

    // ============================================================================
    // 4. describe("工具函数") —— 4 个工具函数各 ≥1 valid + ≥1 invalid 场景
    // ============================================================================

    /** isValidDecimalString 合法：非负、最多 2 位小数、> 0。 */
    @Test
    fun describe_util__it_isValidDecimalStringAccepts() {
        assertTrue(FinanceRecords.isValidDecimalString("120.00"))
        assertTrue(FinanceRecords.isValidDecimalString("1.5"))
        assertTrue(FinanceRecords.isValidDecimalString("100"))
    }

    /** isValidDecimalString 非法：负数 / 0 / 多余小数位 / 非数字 / 空串。 */
    @Test
    fun describe_util__it_isValidDecimalStringRejects() {
        assertTrue(!FinanceRecords.isValidDecimalString("0"))
        assertTrue(!FinanceRecords.isValidDecimalString("0.00"))
        assertTrue(!FinanceRecords.isValidDecimalString("-1.00"))
        assertTrue(!FinanceRecords.isValidDecimalString("1.235"))
        assertTrue(!FinanceRecords.isValidDecimalString("abc"))
        assertTrue(!FinanceRecords.isValidDecimalString(""))
    }

    /** isValidDecimalNonNegative 合法：非负、最多 2 位小数、允许 0。 */
    @Test
    fun describe_util__it_isValidDecimalNonNegativeAccepts() {
        assertTrue(FinanceRecords.isValidDecimalNonNegative("0"))
        assertTrue(FinanceRecords.isValidDecimalNonNegative("0.00"))
        assertTrue(FinanceRecords.isValidDecimalNonNegative("120.00"))
    }

    /** isValidDecimalNonNegative 非法：负数 / 多余小数位 / 非数字。 */
    @Test
    fun describe_util__it_isValidDecimalNonNegativeAndSha256AndCurrency() {
        // decimal 非负 —— 负数 / 3 位小数 / 字母应被拒
        assertTrue(!FinanceRecords.isValidDecimalNonNegative("-1.00"))
        assertTrue(!FinanceRecords.isValidDecimalNonNegative("1.235"))
        assertTrue(!FinanceRecords.isValidDecimalNonNegative("abc"))
        // sha256 —— 64 hex 合法；非 hex / 长度不对 / 包含大写应被拒
        assertTrue(FinanceRecords.isValidSha256Hex(baseSha256Hex))
        assertTrue(!FinanceRecords.isValidSha256Hex("g".repeat(64)))
        assertTrue(!FinanceRecords.isValidSha256Hex("abc"))
        assertTrue(!FinanceRecords.isValidSha256Hex(baseSha256Hex.uppercase()))
        // 货币代码 —— 3 大写字母合法；小写 / 长度不对应被拒
        assertTrue(FinanceRecords.isValidCurrencyCode("CNY"))
        assertTrue(FinanceRecords.isValidCurrencyCode("USD"))
        assertTrue(!FinanceRecords.isValidCurrencyCode("cny"))
        assertTrue(!FinanceRecords.isValidCurrencyCode("CN"))
    }
}
