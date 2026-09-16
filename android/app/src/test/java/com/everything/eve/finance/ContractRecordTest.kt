// ============================================================================
// ContractRecord 单元测试（stage5-finance-v2 / Task 2 / TR-2.7c）
// ============================================================================
//
// 验证目标（≥16 用例, JUnit 4, 4 个 describe block × 4 个 it）：
//   1. describe("合法场景") —— 最小必填字段构造一个合法 record, validate* 返回 Ok；
//   2. describe("schemaVersion 校验") —— schemaVersion != 2 时返回 Invalid；
//   3. describe("关键字段非法") —— endTs < startTs / kind='invalid' /
//      noticeDeadlineTs != endTs - noticePeriodDays * 86400000 三个 Invalid 分支；
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
 * ContractRecord 校验 & 工具函数 JUnit 4 单元测试。
 */
class ContractRecordTest {

    // ============================================================================
    // fixture 工厂
    // ============================================================================

    private val baseStart = 1735689600000L
    private val baseSha256Hex = "a".repeat(64)

    /** 合同结束时间 = 起始时间 + 365 days（保证 end >= start）。 */
    private val contractEnd: Long get() = baseStart + 365L * 86_400_000L

    /** 默认 noticePeriodDays = 30。 */
    private val noticeDays: Long get() = 30L

    /** 按合约约定计算出的合法 noticeDeadlineTs = endTs - noticeDays * 86400000。 */
    private val expectedNoticeDeadline: Long get() = contractEnd - noticeDays * 86_400_000L

    /**
     * 构造一个最小必填字段的合法 ContractRecord。
     *
     * noticeDeadlineTs 已按公式算好, 与 endTs / noticePeriodDays 自洽。
     */
    private fun legalContract(
        id: String = "contract-001",
        endTs: Long = contractEnd,
        noticePeriodDays: Long = noticeDays,
        noticeDeadlineTs: Long = expectedNoticeDeadline,
        attachments: List<AttachmentRef> = emptyList(),
    ): ContractRecord = ContractRecord(
        id = id,
        title = "房屋租赁",
        counterparty = "北京物业有限公司",
        kind = "rental",
        amountMinor = "3600.00",
        currency = "CNY",
        signedTs = baseStart - 7L * 86_400_000L,
        startTs = baseStart,
        endTs = endTs,
        autoRenew = true,
        noticePeriodDays = noticePeriodDays,
        noticeDeadlineTs = noticeDeadlineTs,
        status = "active",
        linkedAccountId = null,
        attachments = attachments,
        createdAt = baseStart,
        updatedAt = baseStart,
    )

    // ============================================================================
    // 1. describe("合法场景") —— 最小必填字段构造, validateContract 返回 Ok
    // ============================================================================

    /** rental 周期 / 空附件 / noticeDeadline 与公式自洽 合法。 */
    @Test
    fun describe_legalScene__it_minimalFieldsReturnsOk() {
        val p = legalContract()
        assertEquals(ValidationResult.Ok, FinanceRecords.validateContract(p))
    }

    /** noticePeriodDays=0 + endTs=noticeDeadline 合法（提前期 0 天）。 */
    @Test
    fun describe_legalScene__it_zeroNoticeDaysPasses() {
        val p = legalContract(
            endTs = contractEnd,
            noticePeriodDays = 0L,
            noticeDeadlineTs = contractEnd, // end - 0 * 86400000 = end
        )
        assertEquals(ValidationResult.Ok, FinanceRecords.validateContract(p))
    }

    /** kind 在集合内（rental/service/purchase/loan/other）每个都合法。 */
    @Test
    fun describe_legalScene__it_allFiveKindsPass() {
        val kinds = listOf("rental", "service", "purchase", "loan", "other")
        for ((i, k) in kinds.withIndex()) {
            val p = legalContract(id = "contract-kind-${i + 1}").copy(kind = k)
            assertEquals(
                "kind=$k 应返回 Ok",
                ValidationResult.Ok,
                FinanceRecords.validateContract(p),
            )
        }
    }

    /** status 在 active/expired/terminated/renewed 集合内合法。 */
    @Test
    fun describe_legalScene__it_allFourStatusesPass() {
        val statuses = listOf("active", "expired", "terminated", "renewed")
        for ((i, s) in statuses.withIndex()) {
            val p = legalContract(id = "contract-stat-${i + 1}").copy(status = s)
            assertEquals(
                "status=$s 应返回 Ok",
                ValidationResult.Ok,
                FinanceRecords.validateContract(p),
            )
        }
    }

    // ============================================================================
    // 2. describe("schemaVersion 校验")
    // ============================================================================

    /** schemaVersion=1 应被拒绝。 */
    @Test
    fun describe_schemaVersion__it_rejectsSchemaVersion1() {
        val p = legalContract().copy(schemaVersion = 1)
        val result = FinanceRecords.validateContract(p)
        assertTrue("schemaVersion=1 应返回 Invalid, 实际 = $result", result is ValidationResult.Invalid)
        assertEquals(
            "schemaVersion 必须是 2",
            (result as ValidationResult.Invalid).reason
        )
    }

    /** schemaVersion=5 应被拒绝。 */
    @Test
    fun describe_schemaVersion__it_rejectsSchemaVersion5() {
        val p = legalContract().copy(schemaVersion = 5)
        val result = FinanceRecords.validateContract(p)
        assertTrue("schemaVersion=5 应返回 Invalid, 实际 = $result", result is ValidationResult.Invalid)
        assertEquals(
            "schemaVersion 必须是 2",
            (result as ValidationResult.Invalid).reason
        )
    }

    /** schemaVersion=-1 应被拒绝。 */
    @Test
    fun describe_schemaVersion__it_rejectsSchemaVersionNegative() {
        val p = legalContract().copy(schemaVersion = -1)
        val result = FinanceRecords.validateContract(p)
        assertTrue("schemaVersion=-1 应返回 Invalid, 实际 = $result", result is ValidationResult.Invalid)
        assertEquals(
            "schemaVersion 必须是 2",
            (result as ValidationResult.Invalid).reason
        )
    }

    /** schemaVersion=2 + 默认参数应返回 Ok（合法基线）。 */
    @Test
    fun describe_schemaVersion__it_acceptsSchemaVersion2() {
        val p = legalContract() // schemaVersion 默认 = 2
        assertEquals(ValidationResult.Ok, FinanceRecords.validateContract(p))
    }

    // ============================================================================
    // 3. describe("关键字段非法") —— endTs / kind / noticeDeadlineTs
    // ============================================================================

    /** endTs < startTs 非法。 */
    @Test
    fun describe_keyFieldsInvalid__it_rejectsEndBeforeStart() {
        val p = legalContract().copy(endTs = baseStart - 1L)
        val result = FinanceRecords.validateContract(p)
        assertTrue("endTs < startTs 应返回 Invalid, 实际 = $result", result is ValidationResult.Invalid)
        assertEquals(
            "endTs 必须 >= startTs",
            (result as ValidationResult.Invalid).reason
        )
    }

    /** kind='invalid' 非法（不在 rental/service/purchase/loan/other 集合内）。 */
    @Test
    fun describe_keyFieldsInvalid__it_rejectsInvalidKind() {
        val p = legalContract().copy(kind = "invalid")
        val result = FinanceRecords.validateContract(p)
        assertTrue("kind='invalid' 应返回 Invalid, 实际 = $result", result is ValidationResult.Invalid)
        assertEquals(
            "kind 非法",
            (result as ValidationResult.Invalid).reason
        )
    }

    /** noticeDeadlineTs 不等于 endTs - noticePeriodDays * 86400000 非法。 */
    @Test
    fun describe_keyFieldsInvalid__it_rejectsNoticeDeadlineMismatch() {
        val p = legalContract().copy(
            // 注意此处 noticeDeadlineTs 故意与公式不符 —— 偏移 1 ms。
            noticeDeadlineTs = expectedNoticeDeadline + 1L,
        )
        val result = FinanceRecords.validateContract(p)
        assertTrue(
            "noticeDeadlineTs 与公式不一致应返回 Invalid, 实际 = $result",
            result is ValidationResult.Invalid
        )
        assertEquals(
            "noticeDeadlineTs 必须等于 endTs - noticePeriodDays * 86400000",
            (result as ValidationResult.Invalid).reason
        )
    }

    /** noticePeriodDays < 0 非法（必须为非负整数）。 */
    @Test
    fun describe_keyFieldsInvalid__it_rejectsNegativeNoticeDays() {
        val p = legalContract().copy(
            noticePeriodDays = -1L,
            noticeDeadlineTs = contractEnd, // 故意设为 end（公式要求是 end - (-1)*day = end + day, 必不一致）
        )
        val result = FinanceRecords.validateContract(p)
        assertTrue(
            "noticePeriodDays<0 应返回 Invalid, 实际 = $result",
            result is ValidationResult.Invalid
        )
        assertEquals(
            "noticePeriodDays 必须为非负整数",
            (result as ValidationResult.Invalid).reason
        )
    }

    // ============================================================================
    // 4. describe("工具函数")
    // ============================================================================

    /** isValidDecimalString 合法（合同金额 > 0）。 */
    @Test
    fun describe_util__it_isValidDecimalStringAccepts() {
        assertTrue(FinanceRecords.isValidDecimalString("3600.00"))
        assertTrue(FinanceRecords.isValidDecimalString("0.01"))
        assertTrue(FinanceRecords.isValidDecimalString("100"))
    }

    /** isValidDecimalString 非法：0 / 负数 / 多余小数位 / 空串。 */
    @Test
    fun describe_util__it_isValidDecimalStringRejects() {
        assertTrue(!FinanceRecords.isValidDecimalString("0"))
        assertTrue(!FinanceRecords.isValidDecimalString("-100"))
        assertTrue(!FinanceRecords.isValidDecimalString("1.235"))
        assertTrue(!FinanceRecords.isValidDecimalString(""))
    }

    /** isValidDecimalNonNegative 合法：> 0 / 0 / 0.00。 */
    @Test
    fun describe_util__it_isValidDecimalNonNegativeAccepts() {
        assertTrue(FinanceRecords.isValidDecimalNonNegative("0"))
        assertTrue(FinanceRecords.isValidDecimalNonNegative("0.00"))
        assertTrue(FinanceRecords.isValidDecimalNonNegative("3600.00"))
    }

    /** sha256 / currency / decimal 非负边界。 */
    @Test
    fun describe_util__it_sha256AndCurrencyAndDecimalNonNegEdgeCases() {
        // sha256 —— 64 hex 合法
        assertTrue(FinanceRecords.isValidSha256Hex(baseSha256Hex))
        // 长度偏差 / 含大写 / 含非 hex 字符不合法
        assertTrue(!FinanceRecords.isValidSha256Hex("a".repeat(63)))
        assertTrue(!FinanceRecords.isValidSha256Hex("a".repeat(65)))
        assertTrue(!FinanceRecords.isValidSha256Hex(baseSha256Hex.uppercase()))
        assertTrue(!FinanceRecords.isValidSha256Hex("g".repeat(64)))
        // currency —— 3 大写合法
        assertTrue(FinanceRecords.isValidCurrencyCode("CNY"))
        // 小写 / 2 字母 / 4 字母 / 含数字不合法
        assertTrue(!FinanceRecords.isValidCurrencyCode("cny"))
        assertTrue(!FinanceRecords.isValidCurrencyCode("CN"))
        assertTrue(!FinanceRecords.isValidCurrencyCode("CNYX"))
        assertTrue(!FinanceRecords.isValidCurrencyCode("123"))
        // decimal 非负 —— 负数 / 3 位小数 / 字母不合法
        assertTrue(!FinanceRecords.isValidDecimalNonNegative("-1.00"))
        assertTrue(!FinanceRecords.isValidDecimalNonNegative("1.235"))
        assertTrue(!FinanceRecords.isValidDecimalNonNegative("abc"))
    }
}
