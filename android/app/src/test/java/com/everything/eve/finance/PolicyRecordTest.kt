// ============================================================================
// PolicyRecord 单元测试（stage5-finance-v2 / Task 2 / TR-2.7c）
// ============================================================================
//
// 验证目标（≥16 用例, JUnit 4, 4 个 describe block × 4 个 it）：
//   1. describe("合法场景") —— 最小必填字段构造一个合法 record, validate* 返回 Ok；
//   2. describe("schemaVersion 校验") —— schemaVersion != 2 时返回 Invalid；
//   3. describe("关键字段非法") —— expiryTs < startTs / coverageMinor='0.00' /
//      attachment sha256 非法三个 Invalid 分支；
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
 * PolicyRecord 校验 & 工具函数 JUnit 4 单元测试。
 */
class PolicyRecordTest {

    // ============================================================================
    // fixture 工厂
    // ============================================================================

    private val baseStart = 1735689600000L
    private val baseSha256Hex = "a".repeat(64)

    /**
     * 构造一个最小必填字段的合法 PolicyRecord。
     *
     * expiryTs = baseStart + 365 days（一定 >= startTs）；
     * coverageMinor='500000.00'（> 0）；
     * attachments 含一个合法 sha256 / mime / size 的 PDF 附件。
     */
    private fun legalPolicy(
        id: String = "policy-001",
        coverageMinor: String = "500000.00",
        attachments: List<AttachmentRef> = listOf(
            AttachmentRef(
                id = "00000000-0000-4000-8000-000000000010",
                mime = "application/pdf",
                size = 1024L,
                sha256 = baseSha256Hex,
            ),
        ),
    ): PolicyRecord = PolicyRecord(
        id = id,
        name = "健康险",
        policyNumber = "ABC12345",
        policyNumberEncrypted = true,
        provider = "太平洋保险",
        premiumMinor = "1200.00",
        currency = "CNY",
        billingCycle = "yearly",
        startTs = baseStart,
        expiryTs = baseStart + 365L * 86_400_000L,
        reminders = listOf(0L, 10080L, 43200L),
        coverageMinor = coverageMinor,
        active = true,
        linkedAccountId = null,
        attachments = attachments,
        createdAt = baseStart,
        updatedAt = baseStart,
    )

    // ============================================================================
    // 1. describe("合法场景") —— 最小必填字段构造, validatePolicy 返回 Ok
    // ============================================================================

    /** yearly / 含 1 个附件 合法。 */
    @Test
    fun describe_legalScene__it_minimalFieldsReturnsOk() {
        val p = legalPolicy()
        assertEquals(ValidationResult.Ok, FinanceRecords.validatePolicy(p))
    }

    /** 空 attachments 合法（保单允许不挂附件）。 */
    @Test
    fun describe_legalScene__it_emptyAttachmentsPasses() {
        val p = legalPolicy(attachments = emptyList())
        assertEquals(ValidationResult.Ok, FinanceRecords.validatePolicy(p))
    }

    /** billingCycle='single' 合法（保费一次性）。 */
    @Test
    fun describe_legalScene__it_singleBillingCyclePasses() {
        val p = legalPolicy().copy(billingCycle = "single")
        assertEquals(ValidationResult.Ok, FinanceRecords.validatePolicy(p))
    }

    /** monthly / quarterly 周期合法。 */
    @Test
    fun describe_legalScene__it_monthlyAndQuarterlyCyclesPass() {
        val m = legalPolicy().copy(billingCycle = "monthly")
        val q = legalPolicy(id = "policy-002").copy(billingCycle = "quarterly")
        assertEquals(ValidationResult.Ok, FinanceRecords.validatePolicy(m))
        assertEquals(ValidationResult.Ok, FinanceRecords.validatePolicy(q))
    }

    // ============================================================================
    // 2. describe("schemaVersion 校验")
    // ============================================================================

    /** schemaVersion=1 应被拒绝。 */
    @Test
    fun describe_schemaVersion__it_rejectsSchemaVersion1() {
        val p = legalPolicy().copy(schemaVersion = 1)
        val result = FinanceRecords.validatePolicy(p)
        assertTrue("schemaVersion=1 应返回 Invalid, 实际 = $result", result is ValidationResult.Invalid)
        assertEquals(
            "schemaVersion 必须是 2",
            (result as ValidationResult.Invalid).reason
        )
    }

    /** schemaVersion=99 应被拒绝。 */
    @Test
    fun describe_schemaVersion__it_rejectsSchemaVersion99() {
        val p = legalPolicy().copy(schemaVersion = 99)
        val result = FinanceRecords.validatePolicy(p)
        assertTrue("schemaVersion=99 应返回 Invalid, 实际 = $result", result is ValidationResult.Invalid)
        assertEquals(
            "schemaVersion 必须是 2",
            (result as ValidationResult.Invalid).reason
        )
    }

    /** schemaVersion=-1 应被拒绝。 */
    @Test
    fun describe_schemaVersion__it_rejectsSchemaVersionNegative() {
        val p = legalPolicy().copy(schemaVersion = -1)
        val result = FinanceRecords.validatePolicy(p)
        assertTrue("schemaVersion=-1 应返回 Invalid, 实际 = $result", result is ValidationResult.Invalid)
        assertEquals(
            "schemaVersion 必须是 2",
            (result as ValidationResult.Invalid).reason
        )
    }

    /** 默认 schemaVersion=2 应返回 Ok（合法基线）。 */
    @Test
    fun describe_schemaVersion__it_acceptsSchemaVersion2() {
        val p = legalPolicy() // schemaVersion 默认 = 2
        assertEquals(ValidationResult.Ok, FinanceRecords.validatePolicy(p))
    }

    // ============================================================================
    // 3. describe("关键字段非法") —— expiryTs / coverageMinor / attachment sha256
    // ============================================================================

    /** expiryTs < startTs 非法。 */
    @Test
    fun describe_keyFieldsInvalid__it_rejectsExpiryBeforeStart() {
        val p = legalPolicy().copy(expiryTs = baseStart - 1L)
        val result = FinanceRecords.validatePolicy(p)
        assertTrue("expiryTs < startTs 应返回 Invalid, 实际 = $result", result is ValidationResult.Invalid)
        assertEquals(
            "expiryTs 必须 >= startTs",
            (result as ValidationResult.Invalid).reason
        )
    }

    /** coverageMinor='0.00' 非法（金额字段必填 > 0）。 */
    @Test
    fun describe_keyFieldsInvalid__it_rejectsZeroCoverage() {
        val p = legalPolicy(coverageMinor = "0.00")
        val result = FinanceRecords.validatePolicy(p)
        assertTrue("coverageMinor='0.00' 应返回 Invalid, 实际 = $result", result is ValidationResult.Invalid)
        assertEquals(
            "coverageMinor 非法",
            (result as ValidationResult.Invalid).reason
        )
    }

    /** attachment sha256 非法（64 个 'g' 不是 hex）应返回 Invalid。 */
    @Test
    fun describe_keyFieldsInvalid__it_rejectsAttachmentSha256Invalid() {
        val p = legalPolicy(
            attachments = listOf(
                AttachmentRef(
                    id = "00000000-0000-4000-8000-000000000020",
                    mime = "application/pdf",
                    size = 1024L,
                    sha256 = "g".repeat(64), // 'g' 不是 hex 字符
                ),
            ),
        )
        val result = FinanceRecords.validatePolicy(p)
        assertTrue("attachment.sha256 非法 应返回 Invalid, 实际 = $result", result is ValidationResult.Invalid)
        assertEquals(
            "attachment.sha256 非法",
            (result as ValidationResult.Invalid).reason
        )
    }

    /** attachment size > 50MB 应被拒。 */
    @Test
    fun describe_keyFieldsInvalid__it_rejectsAttachmentOversize() {
        val p = legalPolicy(
            attachments = listOf(
                AttachmentRef(
                    id = "00000000-0000-4000-8000-000000000021",
                    mime = "application/pdf",
                    size = ATTACHMENT_MAX_SIZE_BYTES + 1L, // 1 byte 超限
                    sha256 = baseSha256Hex,
                ),
            ),
        )
        val result = FinanceRecords.validatePolicy(p)
        assertTrue(
            "attachment.size 超 50MB 应返回 Invalid, 实际 = $result",
            result is ValidationResult.Invalid
        )
        assertEquals(
            "attachment.size 超 50MB 或非正",
            (result as ValidationResult.Invalid).reason
        )
    }

    // ============================================================================
    // 4. describe("工具函数")
    // ============================================================================

    /** isValidDecimalString 合法：非空 + 数字/小数 + > 0。 */
    @Test
    fun describe_util__it_isValidDecimalStringAccepts() {
        assertTrue(FinanceRecords.isValidDecimalString("1200.00"))
        assertTrue(FinanceRecords.isValidDecimalString("500000.00"))
        assertTrue(FinanceRecords.isValidDecimalString("1"))
    }

    /** isValidDecimalString 非法：0、负数、3 位小数、空。 */
    @Test
    fun describe_util__it_isValidDecimalStringRejects() {
        assertTrue(!FinanceRecords.isValidDecimalString("0"))
        assertTrue(!FinanceRecords.isValidDecimalString("-1.00"))
        assertTrue(!FinanceRecords.isValidDecimalString("1.235"))
        assertTrue(!FinanceRecords.isValidDecimalString(""))
    }

    /** isValidDecimalNonNegative 合法：允许 0。 */
    @Test
    fun describe_util__it_isValidDecimalNonNegativeAccepts() {
        assertTrue(FinanceRecords.isValidDecimalNonNegative("0"))
        assertTrue(FinanceRecords.isValidDecimalNonNegative("0.00"))
        assertTrue(FinanceRecords.isValidDecimalNonNegative("1200.00"))
    }

    /** sha256 + currency + decimal 非负边界。 */
    @Test
    fun describe_util__it_sha256AndCurrencyAndDecimalNonNegEdgeCases() {
        // sha256 —— 64 hex 合法
        assertTrue(FinanceRecords.isValidSha256Hex(baseSha256Hex))
        // 非 hex / 长度不对 / 大写都不合法
        assertTrue(!FinanceRecords.isValidSha256Hex("g".repeat(64)))
        assertTrue(!FinanceRecords.isValidSha256Hex(baseSha256Hex.uppercase()))
        assertTrue(!FinanceRecords.isValidSha256Hex("a".repeat(63)))
        // currency —— 3 大写合法
        assertTrue(FinanceRecords.isValidCurrencyCode("CNY"))
        // 小写 / 2 字母 / 4 字母不合法
        assertTrue(!FinanceRecords.isValidCurrencyCode("cny"))
        assertTrue(!FinanceRecords.isValidCurrencyCode("CN"))
        assertTrue(!FinanceRecords.isValidCurrencyCode("CNYY"))
        // decimal 非负 —— 负数 / 3 位小数 / 字母不合法
        assertTrue(!FinanceRecords.isValidDecimalNonNegative("-0.01"))
        assertTrue(!FinanceRecords.isValidDecimalNonNegative("1.235"))
        assertTrue(!FinanceRecords.isValidDecimalNonNegative("xyz"))
    }
}
