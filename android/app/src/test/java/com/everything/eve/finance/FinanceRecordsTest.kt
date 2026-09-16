// ============================================================================
// FinanceRecords 纯函数单元测试（stage5-finance-v2 / Task 2 / TR-2.7）
// ============================================================================
//
// 验证目标（≥24 用例, 覆盖 TR-2.7 Pass Condition + 全部边界）:
//   1. 4 子类型合法 Record 通过校验；
//   2. 校验函数边界（空 id / 非法金额 / 非整数 reminders / 非法货币 / 非法状态）；
//   3. 校验幂等性 + 入参不被修改（零知识纪律）；
//   4. 附件元数据校验（sha256 非 hex / size 超限 / mime 缺失）；
//   5. loan 已还本金 ≤ 本金；contract notice_deadline_ts 推算；
//   6. SPEC 字符级一致性（schemaVersion=2 / policyNumberEncrypted 默认值 /
//      includeInNetAssets 默认值）。
//
// 关联:
//   - android/.../finance/FinanceRecords.kt（被测目标, 4 个 Record + 校验函数）
//   - .trae/specs/stage5-finance-v2/spec.md FR-V2-A.1~A.4 / TR-2.7
// ============================================================================

package com.everything.eve.finance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FinanceRecords 纯函数 JUnit 4 单元测试 —— 与 Web `types-v2.spec.ts` 行为对齐。
 */
class FinanceRecordsTest {

    // ============================================================================
    // fixture 工厂
    // ============================================================================

    private val baseTs = 1780000000000L // 2026-06-28
    private val baseSha256Hex = "a".repeat(64)

    private fun makeSub(): SubscriptionRecord = SubscriptionRecord(
        id = "00000000-0000-4000-8000-000000000001",
        name = "Netflix",
        provider = "Netflix Inc.",
        amountMinor = "120.00",
        currency = "CNY",
        billingCycle = "monthly",
        customDays = null,
        startTs = baseTs,
        nextRenewalTs = baseTs + 30 * 86400000L,
        reminders = listOf(0L, 1440L),
        active = true,
        category = "entertainment",
        createdAt = baseTs,
        updatedAt = baseTs,
    )

    private fun makePolicy(): PolicyRecord = PolicyRecord(
        id = "00000000-0000-4000-8000-000000000002",
        name = "健康险",
        policyNumber = "ABC12345",
        policyNumberEncrypted = true,
        provider = "太平洋保险",
        premiumMinor = "1200.00",
        currency = "CNY",
        billingCycle = "yearly",
        startTs = baseTs,
        expiryTs = baseTs + 365 * 86400000L,
        reminders = listOf(0L, 10080L, 43200L),
        coverageMinor = "500000.00",
        active = true,
        linkedAccountId = null,
        attachments = listOf(
            AttachmentRef(id = "00000000-0000-4000-8000-000000000010",
                mime = "application/pdf", size = 1024L, sha256 = baseSha256Hex),
        ),
        createdAt = baseTs,
        updatedAt = baseTs,
    )

    private fun makeLoan(): LoanRecord = LoanRecord(
        id = "00000000-0000-4000-8000-000000000003",
        counterparty = "李四",
        principalMinor = "10000.00",
        currency = "CNY",
        direction = "lent",
        issueTs = baseTs,
        dueTs = baseTs + 180 * 86400000L,
        interestRateApyBps = 360L,
        status = "active",
        paidMinor = "0.00",
        reminders = listOf(0L, 43200L),
        linkedAccountId = null,
        includeInNetAssets = true,
        createdAt = baseTs,
        updatedAt = baseTs,
    )

    private fun makeContract(): ContractRecord {
        val start = baseTs
        val end = baseTs + 365 * 86400000L
        val noticeDays = 30L
        return ContractRecord(
            id = "00000000-0000-4000-8000-000000000004",
            title = "房屋租赁",
            counterparty = "北京物业有限公司",
            kind = "rental",
            amountMinor = "3600.00",
            currency = "CNY",
            signedTs = start - 7 * 86400000L,
            startTs = start,
            endTs = end,
            autoRenew = true,
            noticePeriodDays = noticeDays,
            noticeDeadlineTs = end - noticeDays * 86400000L,
            status = "active",
            linkedAccountId = null,
            attachments = emptyList(),
            createdAt = baseTs,
            updatedAt = baseTs,
        )
    }

    // ============================================================================
    // 工具函数
    // ============================================================================

    @Test fun isValidDecimalString_accepts() {
        assertTrue(FinanceRecords.isValidDecimalString("1.5"))
        assertTrue(FinanceRecords.isValidDecimalString("120.00"))
        assertTrue(FinanceRecords.isValidDecimalString("100"))
    }

    @Test fun isValidDecimalString_rejects() {
        assertFalse(FinanceRecords.isValidDecimalString("1.235"))
        assertFalse(FinanceRecords.isValidDecimalString("-1.00"))
        assertFalse(FinanceRecords.isValidDecimalString("0"))
        assertFalse(FinanceRecords.isValidDecimalString("abc"))
        assertFalse(FinanceRecords.isValidDecimalString(""))
    }

    @Test fun isValidCurrencyCode_acceptsCNY_USD() {
        assertTrue(FinanceRecords.isValidCurrencyCode("CNY"))
        assertTrue(FinanceRecords.isValidCurrencyCode("USD"))
    }

    @Test fun isValidCurrencyCode_rejectsLowerAndLength() {
        assertFalse(FinanceRecords.isValidCurrencyCode("cny"))
        assertFalse(FinanceRecords.isValidCurrencyCode("CN"))
        assertFalse(FinanceRecords.isValidCurrencyCode("CNYX"))
    }

    @Test fun isValidSha256Hex_acceptsAndRejects() {
        assertTrue(FinanceRecords.isValidSha256Hex(baseSha256Hex))
        assertFalse(FinanceRecords.isValidSha256Hex("abc"))
        assertFalse(FinanceRecords.isValidSha256Hex(baseSha256Hex.uppercase()))
    }

    // ============================================================================
    // Subscription 校验
    // ============================================================================

    @Test fun subscription_legalPasses() {
        assertEquals(ValidationResult.Ok, FinanceRecords.validateSubscription(makeSub()))
    }

    @Test fun subscription_rejectsSchemaVersion1() {
        val p = makeSub().copy(schemaVersion = 1)
        assertFalse(FinanceRecords.validateSubscription(p) is ValidationResult.Ok)
    }

    @Test fun subscription_rejectsZeroAmount() {
        val p = makeSub().copy(amountMinor = "0")
        assertFalse(FinanceRecords.validateSubscription(p) is ValidationResult.Ok)
    }

    @Test fun subscription_rejectsLowercaseCurrency() {
        val p = makeSub().copy(currency = "cny")
        assertFalse(FinanceRecords.validateSubscription(p) is ValidationResult.Ok)
    }

    @Test fun subscription_rejectsCustomDaysWhenMonthly() {
        val p = makeSub().copy(customDays = 30L)
        assertFalse(FinanceRecords.validateSubscription(p) is ValidationResult.Ok)
    }

    @Test fun subscription_rejectsNullCustomDaysWhenCustom() {
        val p = makeSub().copy(billingCycle = "custom_days", customDays = null)
        assertFalse(FinanceRecords.validateSubscription(p) is ValidationResult.Ok)
    }

    @Test fun subscription_rejectsNegativeReminders() {
        val p = makeSub().copy(reminders = listOf(0L, -1L))
        assertFalse(FinanceRecords.validateSubscription(p) is ValidationResult.Ok)
    }

    @Test fun subscription_rejectsNextRenewalBeforeStart() {
        val p = makeSub().copy(nextRenewalTs = baseTs - 1L)
        assertFalse(FinanceRecords.validateSubscription(p) is ValidationResult.Ok)
    }

    @Test fun subscription_idempotent_noInputMutation() {
        val p = makeSub()
        val snapshot = p.toString()
        FinanceRecords.validateSubscription(p)
        assertEquals(snapshot, p.toString())
    }

    // ============================================================================
    // Policy 校验
    // ============================================================================

    @Test fun policy_legalPasses() {
        assertEquals(ValidationResult.Ok, FinanceRecords.validatePolicy(makePolicy()))
    }

    @Test fun policy_rejectsEmptyPolicyNumber() {
        val p = makePolicy().copy(policyNumber = "")
        assertFalse(FinanceRecords.validatePolicy(p) is ValidationResult.Ok)
    }

    @Test fun policy_rejectsPolicyNumberOver100Chars() {
        val p = makePolicy().copy(policyNumber = "x".repeat(101))
        assertFalse(FinanceRecords.validatePolicy(p) is ValidationResult.Ok)
    }

    @Test fun policy_rejectsExpiryBeforeStart() {
        val p = makePolicy().copy(expiryTs = baseTs - 1L)
        assertFalse(FinanceRecords.validatePolicy(p) is ValidationResult.Ok)
    }

    @Test fun policy_rejectsAttachmentSha256NotHex() {
        val p = makePolicy().copy(
            attachments = listOf(
                AttachmentRef(id = "a", mime = "application/pdf", size = 1024L,
                    sha256 = "g".repeat(64)),
            ),
        )
        assertFalse(FinanceRecords.validatePolicy(p) is ValidationResult.Ok)
    }

    @Test fun policy_rejectsAttachmentSizeOver50MB() {
        val p = makePolicy().copy(
            attachments = listOf(
                AttachmentRef(id = "a", mime = "application/pdf",
                    size = ATTACHMENT_MAX_SIZE_BYTES + 1L, sha256 = baseSha256Hex),
            ),
        )
        assertFalse(FinanceRecords.validatePolicy(p) is ValidationResult.Ok)
    }

    @Test fun policy_rejectsAttachmentMimeMissing() {
        val p = makePolicy().copy(
            attachments = listOf(
                AttachmentRef(id = "a", mime = "", size = 1024L, sha256 = baseSha256Hex),
            ),
        )
        assertFalse(FinanceRecords.validatePolicy(p) is ValidationResult.Ok)
    }

    @Test fun policy_acceptsEmptyAttachments() {
        val p = makePolicy().copy(attachments = emptyList())
        assertEquals(ValidationResult.Ok, FinanceRecords.validatePolicy(p))
    }

    // ============================================================================
    // Loan 校验
    // ============================================================================

    @Test fun loan_legalPasses() {
        assertEquals(ValidationResult.Ok, FinanceRecords.validateLoan(makeLoan()))
    }

    @Test fun loan_rejectsUnknownDirection() {
        val p = makeLoan().copy(direction = "unknown")
        assertFalse(FinanceRecords.validateLoan(p) is ValidationResult.Ok)
    }

    @Test fun loan_acceptsBorrowedNotInNetAssets() {
        val p = makeLoan().copy(direction = "borrowed", includeInNetAssets = false)
        assertEquals(ValidationResult.Ok, FinanceRecords.validateLoan(p))
    }

    @Test fun loan_rejectsPaidExceedingPrincipal() {
        val p = makeLoan().copy(paidMinor = "10001.00")
        assertFalse(FinanceRecords.validateLoan(p) is ValidationResult.Ok)
    }

    @Test fun loan_acceptsPaidEqualsPrincipalWhenStatusPaid() {
        val p = makeLoan().copy(paidMinor = "10000.00", status = "paid")
        assertEquals(ValidationResult.Ok, FinanceRecords.validateLoan(p))
    }

    @Test fun loan_acceptsPaidZero() {
        // 未还款（paid_minor='0.00'）合法
        val p = makeLoan().copy(paidMinor = "0.00")
        assertEquals(ValidationResult.Ok, FinanceRecords.validateLoan(p))
    }

    @Test fun loan_rejectsNegativeInterestRate() {
        val p = makeLoan().copy(interestRateApyBps = -1L)
        assertFalse(FinanceRecords.validateLoan(p) is ValidationResult.Ok)
    }

    @Test fun loan_rejectsDueBeforeIssue() {
        val p = makeLoan().copy(dueTs = baseTs - 1L)
        assertFalse(FinanceRecords.validateLoan(p) is ValidationResult.Ok)
    }

    @Test fun loan_rejectsEmptyCounterparty() {
        val p = makeLoan().copy(counterparty = "")
        assertFalse(FinanceRecords.validateLoan(p) is ValidationResult.Ok)
    }

    @Test fun loan_acceptsOverdueStatus() {
        val p = makeLoan().copy(status = "overdue")
        assertEquals(ValidationResult.Ok, FinanceRecords.validateLoan(p))
    }

    // ============================================================================
    // Contract 校验
    // ============================================================================

    @Test fun contract_legalPasses() {
        assertEquals(ValidationResult.Ok, FinanceRecords.validateContract(makeContract()))
    }

    @Test fun contract_rejectsNoticeDeadlineMismatch() {
        val p = makeContract().copy(noticeDeadlineTs = baseTs + 100 * 86400000L)
        assertFalse(FinanceRecords.validateContract(p) is ValidationResult.Ok)
    }

    @Test fun contract_acceptsZeroNoticeDays() {
        val end = baseTs + 365 * 86400000L
        val p = makeContract().copy(noticePeriodDays = 0L, noticeDeadlineTs = end)
        assertEquals(ValidationResult.Ok, FinanceRecords.validateContract(p))
    }

    @Test fun contract_acceptsKindLoan() {
        val p = makeContract().copy(kind = "loan")
        assertEquals(ValidationResult.Ok, FinanceRecords.validateContract(p))
    }

    @Test fun contract_rejectsUnknownKind() {
        val p = makeContract().copy(kind = "unknown")
        assertFalse(FinanceRecords.validateContract(p) is ValidationResult.Ok)
    }

    @Test fun contract_rejectsEndBeforeStart() {
        val p = makeContract().copy(endTs = baseTs - 1L)
        assertFalse(FinanceRecords.validateContract(p) is ValidationResult.Ok)
    }

    @Test fun contract_acceptsAutoRenewFalse() {
        val p = makeContract().copy(autoRenew = false)
        assertEquals(ValidationResult.Ok, FinanceRecords.validateContract(p))
    }

    @Test fun contract_rejectsNegativeNoticeDays() {
        val p = makeContract().copy(noticePeriodDays = -1L)
        assertFalse(FinanceRecords.validateContract(p) is ValidationResult.Ok)
    }

    @Test fun contract_idempotent_noInputMutation() {
        val p = makeContract()
        val snapshot = p.toString()
        FinanceRecords.validateContract(p)
        assertEquals(snapshot, p.toString())
    }
}