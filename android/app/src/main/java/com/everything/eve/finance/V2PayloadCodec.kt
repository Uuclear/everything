// ============================================================================
// V2PayloadCodec —— 财务 v2 子类型明文 JSON 编解码（stage5-finance-v2 / B4）
// ============================================================================
//
// 作用: SubscriptionRecord / PolicyRecord / LoanRecord / ContractRecord 四类
//       v2 内存模型（camelCase）与 records 通道明文载荷（snake_case）互转；
//       字段名与 web/src/finance/types.ts 的 v2 接口逐字段一致（跨端同步契约）。
//
// 设计要点:
//   1. 纯函数 object —— 无状态, 不依赖 Room / Network / DataStore / Log;
//      仅依赖 org.json（与 FinanceRepository 序列化路径同款）。
//   2. encode 全字段输出 —— 含 schema_version=2、attachments 数组、
//      reminders 数组与所有时间戳；可空字段缺失时输出 null（由 org.json 决定
//      落为 null 字面量或省略键，decode 两侧都容错）。
//   3. decode 字段缺失按类型默认值容错（0 / 空串 / false / 空列表 / null 可空
//      字段），不主动抛异常；整段 JSON 语法损坏由上层（hydrateV2 /
//      rebuildChain / Receiver）try/catch 跳过该条, 不阻塞其余记录。
//   4. 与 envelope 版本解耦 —— records.version 是信封版本（固定 1，参与 AAD），
//      payload 内 schema_version 是业务 schema 版本（v2 固定 2），二者互不影响。
//
// 关联:
//   - web/src/finance/types.ts（FinanceSubscription / FinancePolicy /
//     FinanceLoan / FinanceContract / AttachmentRef 字段真理源）
//   - com.everything.eve.finance.FinanceRecords（data class 定义）
// ============================================================================

package com.everything.eve.finance

import org.json.JSONArray
import org.json.JSONObject

/**
 * v2 四类财务记录的明文 JSON 编解码器（纯函数容器）。
 *
 * 入口：
 *  - 写入侧：FinanceViewModel 四个 upsert 调 encodeX 生成 plaintextJson，
 *    交 RecordsRepository.upsertFinanceV2 密封入 records 表；
 *  - 读取侧：FinanceViewModel.hydrateV2 / ReminderScheduler.rebuildChain /
 *    ReminderReceiver 调 RecordsRepository.decryptFinanceV2 拿明文后调 decodeX。
 */
object V2PayloadCodec {

    // ============================================================================
    // encode：camelCase data class → snake_case JSON 字符串
    // ============================================================================

    /** 订阅记录 → 明文 JSON（字段顺序对齐 web FinanceSubscription）。 */
    fun encodeSubscription(r: SubscriptionRecord): String {
        val map = linkedMapOf<String, Any?>(
            "id" to r.id,
            "schema_version" to r.schemaVersion,
            "name" to r.name,
            "provider" to r.provider,
            "amount_minor" to r.amountMinor,
            "currency" to r.currency,
            "billing_cycle" to r.billingCycle,
            "custom_days" to r.customDays,
            "start_ts" to r.startTs,
            "next_renewal_ts" to r.nextRenewalTs,
            "reminders" to r.reminders.toList(),
            "active" to r.active,
            "category" to r.category,
            "created_at" to r.createdAt,
            "updated_at" to r.updatedAt,
        )
        return JSONObject(map).toString()
    }

    /** 保单记录 → 明文 JSON（字段顺序对齐 web FinancePolicy）。 */
    fun encodePolicy(r: PolicyRecord): String {
        val map = linkedMapOf<String, Any?>(
            "id" to r.id,
            "schema_version" to r.schemaVersion,
            "name" to r.name,
            "policy_number" to r.policyNumber,
            "policy_number_encrypted" to r.policyNumberEncrypted,
            "provider" to r.provider,
            "premium_minor" to r.premiumMinor,
            "currency" to r.currency,
            "billing_cycle" to r.billingCycle,
            "start_ts" to r.startTs,
            "expiry_ts" to r.expiryTs,
            "reminders" to r.reminders.toList(),
            "coverage_minor" to r.coverageMinor,
            "active" to r.active,
            "linked_account_id" to r.linkedAccountId,
            "attachments" to r.attachments.map { encodeAttachmentMap(it) },
            "created_at" to r.createdAt,
            "updated_at" to r.updatedAt,
        )
        return JSONObject(map).toString()
    }

    /** 借款记录 → 明文 JSON（字段顺序对齐 web FinanceLoan）。 */
    fun encodeLoan(r: LoanRecord): String {
        val map = linkedMapOf<String, Any?>(
            "id" to r.id,
            "schema_version" to r.schemaVersion,
            "counterparty" to r.counterparty,
            "principal_minor" to r.principalMinor,
            "currency" to r.currency,
            "direction" to r.direction,
            "issue_ts" to r.issueTs,
            "due_ts" to r.dueTs,
            "interest_rate_apy_bps" to r.interestRateApyBps,
            "status" to r.status,
            "paid_minor" to r.paidMinor,
            "reminders" to r.reminders.toList(),
            "linked_account_id" to r.linkedAccountId,
            "include_in_net_assets" to r.includeInNetAssets,
            "created_at" to r.createdAt,
            "updated_at" to r.updatedAt,
        )
        return JSONObject(map).toString()
    }

    /** 合同 / 发票记录 → 明文 JSON（字段顺序对齐 web FinanceContract）。 */
    fun encodeContract(r: ContractRecord): String {
        val map = linkedMapOf<String, Any?>(
            "id" to r.id,
            "schema_version" to r.schemaVersion,
            "title" to r.title,
            "counterparty" to r.counterparty,
            "kind" to r.kind,
            "amount_minor" to r.amountMinor,
            "currency" to r.currency,
            "signed_ts" to r.signedTs,
            "start_ts" to r.startTs,
            "end_ts" to r.endTs,
            "auto_renew" to r.autoRenew,
            "notice_period_days" to r.noticePeriodDays,
            "notice_deadline_ts" to r.noticeDeadlineTs,
            "status" to r.status,
            "linked_account_id" to r.linkedAccountId,
            "attachments" to r.attachments.map { encodeAttachmentMap(it) },
            "created_at" to r.createdAt,
            "updated_at" to r.updatedAt,
        )
        return JSONObject(map).toString()
    }

    /** 预算记录 → 明文 JSON（B6；字段顺序对齐 web FinanceBudget）。 */
    fun encodeBudget(r: BudgetRecord): String {
        val map = linkedMapOf<String, Any?>(
            "id" to r.id,
            "schema_version" to r.schemaVersion,
            "scope" to r.scope,
            "category" to r.category,
            "amount_minor" to r.amountMinor,
            "currency" to r.currency,
            "start_ts" to r.startTs,
            "end_ts" to r.endTs,
            "warning_threshold_pct" to r.warningThresholdPct,
            "block_threshold_pct" to r.blockThresholdPct,
            "active" to r.active,
            "created_at" to r.createdAt,
            "updated_at" to r.updatedAt,
        )
        return JSONObject(map).toString()
    }

    // ============================================================================
    // decode：snake_case JSON → camelCase data class（缺字段按默认值容错）
    // ============================================================================
    // 注意：JSONObject 构造本身可能因 JSON 语法损坏抛异常 —— 按契约由上层
    // try/catch 跳过该条；本层只保证"字段缺失 / null / 类型轻度漂移"不抛。
    // ============================================================================

    /** 明文 JSON → 订阅记录；字段缺失按默认值兜底。 */
    fun decodeSubscription(json: String): SubscriptionRecord {
        val o = JSONObject(json)
        return SubscriptionRecord(
            id = o.strOrDefault("id", ""),
            schemaVersion = o.intOrDefault("schema_version", FINANCE_V2_SCHEMA_VERSION),
            name = o.strOrDefault("name", ""),
            provider = o.strOrDefault("provider", ""),
            amountMinor = o.strOrDefault("amount_minor", ""),
            currency = o.strOrDefault("currency", ""),
            billingCycle = o.strOrDefault("billing_cycle", ""),
            customDays = o.nullableLong("custom_days"),
            startTs = o.longOrDefault("start_ts", 0L),
            nextRenewalTs = o.longOrDefault("next_renewal_ts", 0L),
            reminders = o.longArray("reminders"),
            active = o.boolOrDefault("active", false),
            category = o.strOrDefault("category", ""),
            createdAt = o.longOrDefault("created_at", 0L),
            updatedAt = o.longOrDefault("updated_at", 0L),
        )
    }

    /** 明文 JSON → 保单记录；字段缺失按默认值兜底。 */
    fun decodePolicy(json: String): PolicyRecord {
        val o = JSONObject(json)
        return PolicyRecord(
            id = o.strOrDefault("id", ""),
            schemaVersion = o.intOrDefault("schema_version", FINANCE_V2_SCHEMA_VERSION),
            name = o.strOrDefault("name", ""),
            policyNumber = o.strOrDefault("policy_number", ""),
            policyNumberEncrypted = o.boolOrDefault("policy_number_encrypted", false),
            provider = o.strOrDefault("provider", ""),
            premiumMinor = o.strOrDefault("premium_minor", ""),
            currency = o.strOrDefault("currency", ""),
            billingCycle = o.strOrDefault("billing_cycle", ""),
            startTs = o.longOrDefault("start_ts", 0L),
            expiryTs = o.longOrDefault("expiry_ts", 0L),
            reminders = o.longArray("reminders"),
            coverageMinor = o.strOrDefault("coverage_minor", ""),
            active = o.boolOrDefault("active", false),
            linkedAccountId = o.nullableString("linked_account_id"),
            attachments = o.attachmentArray("attachments"),
            createdAt = o.longOrDefault("created_at", 0L),
            updatedAt = o.longOrDefault("updated_at", 0L),
        )
    }

    /** 明文 JSON → 借款记录；字段缺失按默认值兜底。 */
    fun decodeLoan(json: String): LoanRecord {
        val o = JSONObject(json)
        return LoanRecord(
            id = o.strOrDefault("id", ""),
            schemaVersion = o.intOrDefault("schema_version", FINANCE_V2_SCHEMA_VERSION),
            counterparty = o.strOrDefault("counterparty", ""),
            principalMinor = o.strOrDefault("principal_minor", ""),
            currency = o.strOrDefault("currency", ""),
            direction = o.strOrDefault("direction", ""),
            issueTs = o.longOrDefault("issue_ts", 0L),
            dueTs = o.longOrDefault("due_ts", 0L),
            interestRateApyBps = o.longOrDefault("interest_rate_apy_bps", 0L),
            status = o.strOrDefault("status", ""),
            paidMinor = o.strOrDefault("paid_minor", ""),
            reminders = o.longArray("reminders"),
            linkedAccountId = o.nullableString("linked_account_id"),
            includeInNetAssets = o.boolOrDefault("include_in_net_assets", false),
            createdAt = o.longOrDefault("created_at", 0L),
            updatedAt = o.longOrDefault("updated_at", 0L),
        )
    }

    /** 明文 JSON → 合同 / 发票记录；字段缺失按默认值兜底。 */
    fun decodeContract(json: String): ContractRecord {
        val o = JSONObject(json)
        return ContractRecord(
            id = o.strOrDefault("id", ""),
            schemaVersion = o.intOrDefault("schema_version", FINANCE_V2_SCHEMA_VERSION),
            title = o.strOrDefault("title", ""),
            counterparty = o.strOrDefault("counterparty", ""),
            kind = o.strOrDefault("kind", ""),
            amountMinor = o.strOrDefault("amount_minor", ""),
            currency = o.strOrDefault("currency", ""),
            signedTs = o.longOrDefault("signed_ts", 0L),
            startTs = o.longOrDefault("start_ts", 0L),
            endTs = o.longOrDefault("end_ts", 0L),
            autoRenew = o.boolOrDefault("auto_renew", false),
            noticePeriodDays = o.longOrDefault("notice_period_days", 0L),
            noticeDeadlineTs = o.longOrDefault("notice_deadline_ts", 0L),
            status = o.strOrDefault("status", ""),
            linkedAccountId = o.nullableString("linked_account_id"),
            attachments = o.attachmentArray("attachments"),
            createdAt = o.longOrDefault("created_at", 0L),
            updatedAt = o.longOrDefault("updated_at", 0L),
        )
    }

    /** 明文 JSON → 预算记录；字段缺失按默认值兜底（B6）。 */
    fun decodeBudget(json: String): BudgetRecord {
        val o = JSONObject(json)
        return BudgetRecord(
            id = o.strOrDefault("id", ""),
            schemaVersion = o.intOrDefault("schema_version", FINANCE_V2_SCHEMA_VERSION),
            scope = o.strOrDefault("scope", ""),
            category = o.strOrDefault("category", ""),
            amountMinor = o.strOrDefault("amount_minor", ""),
            currency = o.strOrDefault("currency", ""),
            startTs = o.longOrDefault("start_ts", 0L),
            endTs = o.longOrDefault("end_ts", 0L),
            warningThresholdPct = o.intOrDefault("warning_threshold_pct", 0),
            blockThresholdPct = o.intOrDefault("block_threshold_pct", 0),
            active = o.boolOrDefault("active", false),
            createdAt = o.longOrDefault("created_at", 0L),
            updatedAt = o.longOrDefault("updated_at", 0L),
        )
    }

    // ============================================================================
    // 私有辅助
    // ============================================================================

    /** AttachmentRef → snake_case Map（四字段名两端天然一致）。 */
    private fun encodeAttachmentMap(a: AttachmentRef): Map<String, Any?> = linkedMapOf(
        "id" to a.id,
        "mime" to a.mime,
        "size" to a.size,
        "sha256" to a.sha256,
    )

    /** 取字符串字段；键缺失 / JSONObject.NULL / 非字符串类型 → 默认值。 */
    private fun JSONObject.strOrDefault(key: String, default: String): String {
        if (!has(key) || isNull(key)) return default
        return optString(key, default)
    }

    /** 取可空字符串字段；键缺失 / 显式 null → null。 */
    private fun JSONObject.nullableString(key: String): String? {
        if (!has(key) || isNull(key)) return null
        return optString(key, "").takeIf { it.isNotEmpty() }
    }

    /** 取 Long 字段；键缺失 / null / 非数值 → 默认值（Int 自动拓宽为 Long）。 */
    private fun JSONObject.longOrDefault(key: String, default: Long): Long {
        if (!has(key) || isNull(key)) return default
        return when (val v = opt(key)) {
            is Number -> v.toLong()
            is String -> v.toLongOrNull() ?: default
            else -> default
        }
    }

    /** 取可空 Long 字段（custom_days 等）；键缺失 / 显式 null → null。 */
    private fun JSONObject.nullableLong(key: String): Long? {
        if (!has(key) || isNull(key)) return null
        return when (val v = opt(key)) {
            is Number -> v.toLong()
            is String -> v.toLongOrNull()
            else -> null
        }
    }

    /** 取 Int 字段；键缺失 / null / 非数值 → 默认值。 */
    private fun JSONObject.intOrDefault(key: String, default: Int): Int {
        if (!has(key) || isNull(key)) return default
        return when (val v = opt(key)) {
            is Number -> v.toInt()
            is String -> v.toIntOrNull() ?: default
            else -> default
        }
    }

    /** 取 Boolean 字段；键缺失 / null → 默认值。 */
    private fun JSONObject.boolOrDefault(key: String, default: Boolean): Boolean {
        if (!has(key) || isNull(key)) return default
        return optBoolean(key, default)
    }

    /**
     * 取 Long 数组（reminders 分钟偏移）；键缺失 / 非数组 → 空列表；
     * 单个元素非数值时跳过（容错，不抛异常）。
     */
    private fun JSONObject.longArray(key: String): List<Long> {
        if (!has(key) || isNull(key)) return emptyList()
        val arr: JSONArray = optJSONArray(key) ?: return emptyList()
        val out = ArrayList<Long>(arr.length())
        for (i in 0 until arr.length()) {
            when (val v = arr.opt(i)) {
                is Number -> out.add(v.toLong())
                is String -> v.toLongOrNull()?.let { out.add(it) }
                else -> Unit // 非法元素跳过
            }
        }
        return out
    }

    /**
     * 取附件引用数组（attachments）；键缺失 / 非数组 → 空列表；
     * 单个元素非对象 / 解析失败时跳过（容错，不抛异常）。
     */
    private fun JSONObject.attachmentArray(key: String): List<AttachmentRef> {
        if (!has(key) || isNull(key)) return emptyList()
        val arr: JSONArray = optJSONArray(key) ?: return emptyList()
        val out = ArrayList<AttachmentRef>(arr.length())
        for (i in 0 until arr.length()) {
            val item: JSONObject = arr.optJSONObject(i) ?: continue
            out.add(
                AttachmentRef(
                    id = item.strOrDefault("id", ""),
                    mime = item.strOrDefault("mime", ""),
                    size = item.longOrDefault("size", 0L),
                    sha256 = item.strOrDefault("sha256", ""),
                ),
            )
        }
        return out
    }
}
