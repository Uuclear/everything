// ============================================================================
// FinanceRecords —— 财务 v2 子类型 Record 数据类（stage5-finance-v2 / Task 2 / TR-2.7）
// ============================================================================
//
// 任务: stage5-finance-v2 / Task 2 / TR-2.7
// 路径: android/app/src/main/java/com/everything/eve/finance/FinanceRecords.kt
// 作用: 客户端 4 个 v2 子类型明文 payload data class（SubscriptionRecord /
//       PolicyRecord / LoanRecord / ContractRecord）+ 校验函数；与 Web 端
//       web/src/finance/types.ts 字节级一致（三端契约）。
//
// 设计要点:
//   1. 纯 data class —— 无副作用, 不依赖 Room / Network / DataStore / Log;
//   2. decimal-as-string —— 金额字段全程 String 承载（"120.00"）, 与 schema 字段一致;
//   3. reminders 为 List<Long> 分钟偏移（与 v1 events ReminderKind 同口径）;
//   4. 校验函数不抛异常, 返回 ValidationResult sealed type;
//   5. 复用 FinanceAggregator 中类似轻量入参（AccountLike / CardLike / TxLike）
//      命名风格, 但因 v2 子类型字段独立, 单独建文件 + 单独 data class。
//
// 关联:
//   - tasks.md TR-2.7（Android 4 个 Record 数据类 + 校验函数）
//   - tasks.md TR-1.1（Web 镜像, types.ts 扩展 4 子类型）
//   - web/src/finance/types.ts（Web 镜像）
//   - docs/schemas/finance.schema.json（字段口径真理源）
//   - docs/finance.md §9 v2 子类型（v2 文档同步补充）
// ============================================================================

package com.everything.eve.finance

/**
 * v2 校验结果（与 Web `ValidationResult` 字节级一致）。
 */
sealed class ValidationResult {
    object Ok : ValidationResult()
    data class Invalid(val reason: String) : ValidationResult()
}

/** v2 子类型 schema_version 常量。 */
const val FINANCE_V2_SCHEMA_VERSION = 2

/** 单文件 ≤ 50MB 限制（端侧校验；超限直接拒收）。 */
const val ATTACHMENT_MAX_SIZE_BYTES = 50L * 1024L * 1024L

/**
 * 附件引用（policy / contract 挂的附件列表项）。
 *
 * 实际二进制走 records 通道 type='attachment'；这里只存元数据。
 */
data class AttachmentRef(
    val id: String,
    val mime: String,
    val size: Long,
    val sha256: String,
)

/**
 * 订阅条目（type='subscription'）明文 payload。
 *
 * 字段语义同 Web `FinanceSubscription`：
 *   - 金额字段 `amountMinor` 承载 decimal-as-string（"120.00"）;
 *   - `billingCycle=custom_days` 时 customDays 必须 > 0；否则为 null；
 *   - `nextRenewalTs` 由 nextSubscriptionRenewal 纯函数计算。
 */
data class SubscriptionRecord(
    val id: String,
    val schemaVersion: Int = FINANCE_V2_SCHEMA_VERSION,
    val name: String,
    val provider: String,
    val amountMinor: String,
    val currency: String,
    val billingCycle: String, // 'monthly' | 'quarterly' | 'yearly' | 'custom_days'
    val customDays: Long?,
    val startTs: Long,
    val nextRenewalTs: Long,
    val reminders: List<Long>,
    val active: Boolean,
    val category: String, // 'entertainment' | 'productivity' | 'utility' | 'other'
    val createdAt: Long,
    val updatedAt: Long,
)

/**
 * 保单条目（type='policy'）明文 payload。
 */
data class PolicyRecord(
    val id: String,
    val schemaVersion: Int = FINANCE_V2_SCHEMA_VERSION,
    val name: String,
    val policyNumber: String,
    val policyNumberEncrypted: Boolean,
    val provider: String,
    val premiumMinor: String,
    val currency: String,
    val billingCycle: String, // 'monthly' | 'quarterly' | 'yearly' | 'single'
    val startTs: Long,
    val expiryTs: Long,
    val reminders: List<Long>,
    val coverageMinor: String,
    val active: Boolean,
    val linkedAccountId: String?,
    val attachments: List<AttachmentRef>,
    val createdAt: Long,
    val updatedAt: Long,
)

/**
 * 应收借款条目（type='loan'）明文 payload。
 */
data class LoanRecord(
    val id: String,
    val schemaVersion: Int = FINANCE_V2_SCHEMA_VERSION,
    val counterparty: String,
    val principalMinor: String,
    val currency: String,
    val direction: String, // 'lent' | 'borrowed'
    val issueTs: Long,
    val dueTs: Long,
    val interestRateApyBps: Long,
    val status: String, // 'active' | 'partially_paid' | 'paid' | 'overdue'
    val paidMinor: String,
    val reminders: List<Long>,
    val linkedAccountId: String?,
    val includeInNetAssets: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
)

/**
 * 合同 / 发票条目（type='contract'）明文 payload。
 *
 * notice_deadline_ts 提醒 v3 评估；contract 不接入 v1 Reminders 通道。
 */
data class ContractRecord(
    val id: String,
    val schemaVersion: Int = FINANCE_V2_SCHEMA_VERSION,
    val title: String,
    val counterparty: String,
    val kind: String, // 'rental' | 'service' | 'purchase' | 'loan' | 'other'
    val amountMinor: String,
    val currency: String,
    val signedTs: Long,
    val startTs: Long,
    val endTs: Long,
    val autoRenew: Boolean,
    val noticePeriodDays: Long,
    val noticeDeadlineTs: Long,
    val status: String, // 'active' | 'expired' | 'terminated' | 'renewed'
    val linkedAccountId: String?,
    val attachments: List<AttachmentRef>,
    val createdAt: Long,
    val updatedAt: Long,
)

/**
 * FinanceRecords 纯函数 object 容器（无状态, 全静态方法）。
 *
 * 命名风格与 Luhn.kt / FinanceAggregator.kt 保持一致（Kotlin 单例 + 顶层常量）。
 */
object FinanceRecords {

    private const val DAY_MS = 86_400_000L

    // ============================================================================
    // 工具函数（与 Web isValid* 同口径）
    // ============================================================================

    /**
     * decimal-as-string 校验（必填金额字段：非负 + 最多 2 位小数 + > 0）。
     *
     * 用于 `amountMinor` / `principalMinor` / `premiumMinor` / `coverageMinor`
     * 等"必填金额"字段（值必须 > 0）。`paidMinor`（已还）允许 0，用
     * `isValidDecimalNonNegative` 替代。
     */
    fun isValidDecimalString(s: String): Boolean {
        if (s.isEmpty()) return false
        if (!Regex("""^\d+(\.\d{1,2})?$""").matches(s)) return false
        return s != "0" && s != "0.0" && s != "0.00"
    }

    /**
     * decimal-as-string 非负校验（非负 + 最多 2 位小数，允许 0）。
     *
     * 用于 `paidMinor` / `remainingMinor` 等"累计 / 余量"字段。
     */
    fun isValidDecimalNonNegative(s: String): Boolean {
        if (s.isEmpty()) return false
        return Regex("""^\d+(\.\d{1,2})?$""").matches(s)
    }

    /** ISO 4217 三字母代码（粗校验：3 个大写字母）。 */
    fun isValidCurrencyCode(c: String): Boolean = Regex("""^[A-Z]{3}$""").matches(c)

    /** sha-256 hex 字符串校验（64 个十六进制字符）。 */
    fun isValidSha256Hex(s: String): Boolean = Regex("""^[0-9a-f]{64}$""").matches(s)

    // ============================================================================
    // 4 子类型校验函数
    // ============================================================================

    /**
     * 校验订阅条目。
     */
    fun validateSubscription(p: SubscriptionRecord): ValidationResult {
        if (p.schemaVersion != FINANCE_V2_SCHEMA_VERSION) return ValidationResult.Invalid("schemaVersion 必须是 2")
        if (p.id.isEmpty()) return ValidationResult.Invalid("id 缺失")
        if (p.name.isEmpty() || p.name.length > 200) return ValidationResult.Invalid("name 长度需在 1-200 字符")
        if (p.provider.isEmpty() || p.provider.length > 200) return ValidationResult.Invalid("provider 长度需在 1-200 字符")
        if (!isValidDecimalString(p.amountMinor)) return ValidationResult.Invalid("amountMinor 非法")
        if (!isValidCurrencyCode(p.currency)) return ValidationResult.Invalid("currency 必须为 ISO 4217 三字母大写代码")
        val cycles = setOf("monthly", "quarterly", "yearly", "custom_days")
        if (p.billingCycle !in cycles) return ValidationResult.Invalid("billingCycle 非法")
        if (p.billingCycle == "custom_days") {
            if (p.customDays == null || p.customDays <= 0) {
                return ValidationResult.Invalid("customDays 在 billingCycle=custom_days 时必须为正整数")
            }
        } else if (p.customDays != null) {
            return ValidationResult.Invalid("customDays 在非 custom_days 周期时必须为 null")
        }
        if (p.nextRenewalTs < p.startTs) return ValidationResult.Invalid("nextRenewalTs 必须 >= startTs")
        if (p.reminders.any { it < 0 }) return ValidationResult.Invalid("reminders 必须为非负整数数组（分钟偏移）")
        return ValidationResult.Ok
    }

    /**
     * 校验保单条目。
     */
    fun validatePolicy(p: PolicyRecord): ValidationResult {
        if (p.schemaVersion != FINANCE_V2_SCHEMA_VERSION) return ValidationResult.Invalid("schemaVersion 必须是 2")
        if (p.id.isEmpty()) return ValidationResult.Invalid("id 缺失")
        if (p.name.isEmpty() || p.name.length > 200) return ValidationResult.Invalid("name 长度需在 1-200 字符")
        if (p.policyNumber.isEmpty() || p.policyNumber.length > 100) {
            return ValidationResult.Invalid("policyNumber 长度需在 1-100 字符")
        }
        if (p.provider.isEmpty() || p.provider.length > 200) return ValidationResult.Invalid("provider 长度需在 1-200 字符")
        if (!isValidDecimalString(p.premiumMinor)) return ValidationResult.Invalid("premiumMinor 非法")
        if (!isValidCurrencyCode(p.currency)) return ValidationResult.Invalid("currency 必须为 ISO 4217 三字母大写代码")
        val cycles = setOf("monthly", "quarterly", "yearly", "single")
        if (p.billingCycle !in cycles) return ValidationResult.Invalid("billingCycle 非法")
        if (p.expiryTs < p.startTs) return ValidationResult.Invalid("expiryTs 必须 >= startTs")
        if (!isValidDecimalString(p.coverageMinor)) return ValidationResult.Invalid("coverageMinor 非法")
        for (a in p.attachments) {
            if (!isValidSha256Hex(a.sha256)) return ValidationResult.Invalid("attachment.sha256 非法")
            if (a.size <= 0 || a.size > ATTACHMENT_MAX_SIZE_BYTES) {
                return ValidationResult.Invalid("attachment.size 超 50MB 或非正")
            }
            if (a.mime.isEmpty()) return ValidationResult.Invalid("attachment.mime 缺失")
        }
        if (p.reminders.any { it < 0 }) return ValidationResult.Invalid("reminders 必须为非负整数数组（分钟偏移）")
        return ValidationResult.Ok
    }

    /**
     * 校验应收借款条目。
     */
    fun validateLoan(p: LoanRecord): ValidationResult {
        if (p.schemaVersion != FINANCE_V2_SCHEMA_VERSION) return ValidationResult.Invalid("schemaVersion 必须是 2")
        if (p.id.isEmpty()) return ValidationResult.Invalid("id 缺失")
        if (p.counterparty.isEmpty() || p.counterparty.length > 200) {
            return ValidationResult.Invalid("counterparty 长度需在 1-200 字符")
        }
        if (!isValidDecimalString(p.principalMinor)) return ValidationResult.Invalid("principalMinor 非法")
        if (!isValidCurrencyCode(p.currency)) return ValidationResult.Invalid("currency 必须为 ISO 4217 三字母大写代码")
        if (p.direction !in setOf("lent", "borrowed")) return ValidationResult.Invalid("direction 非法")
        if (p.dueTs < p.issueTs) return ValidationResult.Invalid("dueTs 必须 >= issueTs")
        if (p.interestRateApyBps < 0) return ValidationResult.Invalid("interestRateApyBps 必须为非负整数")
        val statuses = setOf("active", "partially_paid", "paid", "overdue")
        if (p.status !in statuses) return ValidationResult.Invalid("status 非法")
        // paidMinor 允许 0（未还款），用 isValidDecimalNonNegative
        if (!isValidDecimalNonNegative(p.paidMinor)) return ValidationResult.Invalid("paidMinor 非法")
        // 已还本金不能超过本金（数值比较；与 schema decimal-as-string 一致）
        if (p.paidMinor.toDouble() > p.principalMinor.toDouble()) {
            return ValidationResult.Invalid("paidMinor 不能超过 principalMinor")
        }
        if (p.reminders.any { it < 0 }) return ValidationResult.Invalid("reminders 必须为非负整数数组（分钟偏移）")
        return ValidationResult.Ok
    }

    /**
     * 校验合同 / 发票条目。
     */
    fun validateContract(p: ContractRecord): ValidationResult {
        if (p.schemaVersion != FINANCE_V2_SCHEMA_VERSION) return ValidationResult.Invalid("schemaVersion 必须是 2")
        if (p.id.isEmpty()) return ValidationResult.Invalid("id 缺失")
        if (p.title.isEmpty() || p.title.length > 200) return ValidationResult.Invalid("title 长度需在 1-200 字符")
        if (p.counterparty.isEmpty() || p.counterparty.length > 200) {
            return ValidationResult.Invalid("counterparty 长度需在 1-200 字符")
        }
        val kinds = setOf("rental", "service", "purchase", "loan", "other")
        if (p.kind !in kinds) return ValidationResult.Invalid("kind 非法")
        if (!isValidDecimalString(p.amountMinor)) return ValidationResult.Invalid("amountMinor 非法")
        if (!isValidCurrencyCode(p.currency)) return ValidationResult.Invalid("currency 必须为 ISO 4217 三字母大写代码")
        if (p.endTs < p.startTs) return ValidationResult.Invalid("endTs 必须 >= startTs")
        if (p.noticePeriodDays < 0) return ValidationResult.Invalid("noticePeriodDays 必须为非负整数")
        val expected = p.endTs - p.noticePeriodDays * DAY_MS
        if (p.noticeDeadlineTs != expected) {
            return ValidationResult.Invalid("noticeDeadlineTs 必须等于 endTs - noticePeriodDays * 86400000")
        }
        val statuses = setOf("active", "expired", "terminated", "renewed")
        if (p.status !in statuses) return ValidationResult.Invalid("status 非法")
        for (a in p.attachments) {
            if (!isValidSha256Hex(a.sha256)) return ValidationResult.Invalid("attachment.sha256 非法")
            if (a.size <= 0 || a.size > ATTACHMENT_MAX_SIZE_BYTES) {
                return ValidationResult.Invalid("attachment.size 超 50MB 或非正")
            }
            if (a.mime.isEmpty()) return ValidationResult.Invalid("attachment.mime 缺失")
        }
        return ValidationResult.Ok
    }
}