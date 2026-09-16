// ============================================================================
// LoanRecord 单元测试（stage5-finance-v2 / Task 2 / TR-2.7c）
// ============================================================================
//
// 验证目标（≥16 用例, JUnit 4, 4 个 describe block × 4 个 it）：
//   1. describe("合法场景") —— 最小必填字段构造一个合法 record, validate* 返回 Ok；
//   2. describe("schemaVersion 校验") —— schemaVersion != 2 时返回 Invalid；
//   3. describe("关键字段非法") —— direction='invalid' / paidMinor > principalMinor /
//      status='invalid' 三个 Invalid 分支；
//   4. describe("工具函数") —— isValidDecimalString / isValidDecimalNonNegative /
//      isValidCurrencyCode / isValidSha256Hex 各 ≥1 个 valid + 1 个 invalid 场景。
//
// 编码纪律:
//   - 用嵌套类模拟 describe block, 方法名 `describe_<name>__it_<name>` 表达 it；
//   - fixture 用 baseStart = 1735689600000 (固定毫秒) + 偏移量构造合法时间戳；
//   - 错误断言用 `assertTrue(result is ValidationResult.Invalid)` +
//     `assertEquals("...", (result as ValidationResult.Invalid).reason)`。
//
// 关联:
//   - android/.../finance/FinanceRecords.kt（被测目标）
// ============================================================================

package com.everything.eve.finance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * LoanRecord 校验 & 工具函数 JUnit 4 单元测试。
 */
class LoanRecordTest {

    // ============================================================================
    // fixture 工厂
    // ============================================================================

    private val baseStart = 1735689600000L
    private val baseSha256Hex = "a".repeat(64)

    /**
     * 构造一个最小必填字段的合法 LoanRecord。
     *
     * direction='lent' / status='active' / paidMinor='0.00'（未还）；
     * dueTs = baseStart + 180 days（一定 >= issueTs）。
     */
    private fun legalLoan(
        id: String = "loan-001",
        direction: String = "lent",
        status: String = "active",
        paidMinor: String = "0.00",
        principalMinor: String = "10000.00",
    ): LoanRecord = LoanRecord(
        id = id,
        counterparty = "李四",
        principalMinor = principalMinor,
        currency = "CNY",
        direction = direction,
        issueTs = baseStart,
        dueTs = baseStart + 180L * 86_400_000L,
        interestRateApyBps = 360L,
        status = status,
        paidMinor = paidMinor,
        reminders = listOf(0L, 43200L),
        linkedAccountId = null,
        includeInNetAssets = true,
        createdAt = baseStart,
        updatedAt = baseStart,
    )

    // ============================================================================
    // 1. describe("合法场景") —— 最小必填字段构造, validateLoan 返回 Ok
    // ============================================================================

    /** lent / active / paid='0.00' 合法。 */
    @Test
    fun describe_legalScene__it_minimalFieldsReturnsOk() {
        val p = legalLoan()
        assertEquals(ValidationResult.Ok, FinanceRecords.validateLoan(p))
    }

    /** borrowed 合法（应收借款的另一面 —— 借入）。 */
    @Test
    fun describe_legalScene__it_borrowedDirectionPasses() {
        val p = legalLoan().copy(direction = "borrowed", includeInNetAssets = false)
        assertEquals(ValidationResult.Ok, FinanceRecords.validateLoan(p))
    }

    /** status='paid' + paidMinor=principal 合法。 */
    @Test
    fun describe_legalScene__it_paidStatusAndEqualPaidPasses() {
        val p = legalLoan().copy(status = "paid", paidMinor = "10000.00")
        assertEquals(ValidationResult.Ok, FinanceRecords.validateLoan(p))
    }

    /** status='partially_paid' / 'overdue' 都合法。 */
    @Test
    fun describe_legalScene__it_partiallyPaidAndOverdueStatusesPass() {
        val part = legalLoan().copy(status = "partially_paid", paidMinor = "3000.00")
        val od = legalLoan().copy(status = "overdue")
        assertEquals(ValidationResult.Ok, FinanceRecords.validateLoan(part))
        assertEquals(ValidationResult.Ok, FinanceRecords.validateLoan(od))
    }

    // ============================================================================
    // 2. describe("schemaVersion 校验")
    // ============================================================================

    /** schemaVersion=1 应被拒绝。 */
    @Test
    fun describe_schemaVersion__it_rejectsSchemaVersion1() {
        val p = legalLoan().copy(schemaVersion = 1)
        val result = FinanceRecords.validateLoan(p)
        assertTrue("schemaVersion=1 应返回 Invalid, 实际 = $result", result is ValidationResult.Invalid)
        assertEquals(
            "schemaVersion 必须是 2",
            (result as ValidationResult.Invalid).reason
        )
    }

    /** schemaVersion=2 + 默认参数应返回 Ok（合法基线）。 */
    @Test
    fun describe_schemaVersion__it_acceptsSchemaVersion2() {
        val p = legalLoan() // schemaVersion 默认 = 2
        assertEquals(ValidationResult.Ok, FinanceRecords.validateLoan(p))
    }

    /** schemaVersion=42 应被拒绝。 */
    @Test
    fun describe_schemaVersion__it_rejectsSchemaVersion42() {
        val p = legalLoan().copy(schemaVersion = 42)
        val result = FinanceRecords.validateLoan(p)
        assertTrue("schemaVersion=42 应返回 Invalid, 实际 = $result", result is ValidationResult.Invalid)
        assertEquals(
            "schemaVersion 必须是 2",
            (result as ValidationResult.Invalid).reason
        )
    }

    /** schemaVersion=-2 应被拒绝。 */
    @Test
    fun describe_schemaVersion__it_rejectsSchemaVersionNegative() {
        val p = legalLoan().copy(schemaVersion = -2)
        val result = FinanceRecords.validateLoan(p)
        assertTrue("schemaVersion=-2 应返回 Invalid, 实际 = $result", result is ValidationResult.Invalid)
        assertEquals(
            "schemaVersion 必须是 2",
            (result as ValidationResult.Invalid).reason
        )
    }

    // ============================================================================
    // 3. describe("关键字段非法") —— direction / paid>principal / status
    // ============================================================================

    /** direction='invalid' 非法（不在 lent/borrowed 集合内）。 */
    @Test
    fun describe_keyFieldsInvalid__it_rejectsInvalidDirection() {
        val p = legalLoan().copy(direction = "invalid")
        val result = FinanceRecords.validateLoan(p)
        assertTrue("direction='invalid' 应返回 Invalid, 实际 = $result", result is ValidationResult.Invalid)
        assertEquals(
            "direction 非法",
            (result as ValidationResult.Invalid).reason
        )
    }

    /** paidMinor > principalMinor 非法（已还本金不能超过本金）。 */
    @Test
    fun describe_keyFieldsInvalid__it_rejectsPaidExceedingPrincipal() {
        val p = legalLoan().copy(principalMinor = "10000.00", paidMinor = "10001.00")
        val result = FinanceRecords.validateLoan(p)
        assertTrue(
            "paidMinor > principalMinor 应返回 Invalid, 实际 = $result",
            result is ValidationResult.Invalid
        )
        assertEquals(
            "paidMinor 不能超过 principalMinor",
            (result as ValidationResult.Invalid).reason
        )
    }

    /** status='invalid' 非法（不在 active/partially_paid/paid/overdue 集合内）。 */
    @Test
    fun describe_keyFieldsInvalid__it_rejectsInvalidStatus() {
        val p = legalLoan().copy(status = "invalid")
        val result = FinanceRecords.validateLoan(p)
        assertTrue("status='invalid' 应返回 Invalid, 实际 = $result", result is ValidationResult.Invalid)
        assertEquals(
            "status 非法",
            (result as ValidationResult.Invalid).reason
        )
    }

    /** interestRateApyBps < 0 非法（必须为非负整数）。 */
    @Test
    fun describe_keyFieldsInvalid__it_rejectsNegativeInterestRate() {
        val p = legalLoan().copy(interestRateApyBps = -1L)
        val result = FinanceRecords.validateLoan(p)
        assertTrue(
            "interestRateApyBps<0 应返回 Invalid, 实际 = $result",
            result is ValidationResult.Invalid
        )
        assertEquals(
            "interestRateApyBps 必须为非负整数",
            (result as ValidationResult.Invalid).reason
        )
    }

    // ============================================================================
    // 4. describe("工具函数")
    // ============================================================================

    /** isValidDecimalString 合法：必填金额（> 0, ≤ 2 位小数）。 */
    @Test
    fun describe_util__it_isValidDecimalStringAccepts() {
        assertTrue(FinanceRecords.isValidDecimalString("10000.00"))
        assertTrue(FinanceRecords.isValidDecimalString("1"))
        assertTrue(FinanceRecords.isValidDecimalString("0.01"))
    }

    /** isValidDecimalString 非法：0、负数、3 位小数、字母。 */
    @Test
    fun describe_util__it_isValidDecimalStringRejects() {
        assertTrue(!FinanceRecords.isValidDecimalString("0"))
        assertTrue(!FinanceRecords.isValidDecimalString("0.00"))
        assertTrue(!FinanceRecords.isValidDecimalString("-100"))
        assertTrue(!FinanceRecords.isValidDecimalString("1.235"))
        assertTrue(!FinanceRecords.isValidDecimalString("abc"))
    }

    /** isValidDecimalNonNegative 合法：paidMinor 允许 0。 */
    @Test
    fun describe_util__it_isValidDecimalNonNegativeAccepts() {
        assertTrue(FinanceRecords.isValidDecimalNonNegative("0"))
        assertTrue(FinanceRecords.isValidDecimalNonNegative("0.00"))
        assertTrue(FinanceRecords.isValidDecimalNonNegative("10000.00"))
    }

    /** sha256 / currency / decimal 非负边界。 */
    @Test
    fun describe_util__it_sha256AndCurrencyAndDecimalNonNegEdgeCases() {
        // sha256 —— 64 hex 合法
        assertTrue(FinanceRecords.isValidSha256Hex(baseSha256Hex))
        // 非 hex / 大写 / 长度不够不合法
        assertTrue(!FinanceRecords.isValidSha256Hex("z".repeat(64)))
        assertTrue(!FinanceRecords.isValidSha256Hex(baseSha256Hex.uppercase()))
        assertTrue(!FinanceRecords.isValidSha256Hex("a".repeat(65)))
        // currency —— 3 大写合法
        assertTrue(FinanceRecords.isValidCurrencyCode("CNY"))
        // 2 字母 / 4 字母 / 数字混合不合法
        assertTrue(!FinanceRecords.isValidCurrencyCode("CN"))
        assertTrue(!FinanceRecords.isValidCurrencyCode("CNYX"))
        assertTrue(!FinanceRecords.isValidCurrencyCode("C3Y"))
        // decimal 非负 —— 负数 / 3 位小数 / 含字母不合法
        assertTrue(!FinanceRecords.isValidDecimalNonNegative("-0.01"))
        assertTrue(!FinanceRecords.isValidDecimalNonNegative("1.235"))
        assertTrue(!FinanceRecords.isValidDecimalNonNegative("1.0a"))
    }
}
