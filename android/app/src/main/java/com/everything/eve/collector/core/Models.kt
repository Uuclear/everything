package com.everything.eve.collector.core

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/**
 * 三类采集数据的明文 JSON 模型（阶段 3）。
 *
 * 这些结构被 sealRecord 加密后才离开内存：
 *  - JSON 字段 snake_case，与 docs/module-schemas.md 逐字段对应；
 *  - Kotlin 属性保持 camelCase，用 @Json 映射（同 api/Dtos.kt 既有风格）；
 *  - 仅 moshi（纯 JVM 反射序列化），不依赖 Android Framework，JVM 单测可直接断言。
 */

/** 采集溯源信息：让密文记录可回溯到设备系统行（不包含任何密钥材料）。 */
@JsonClass(generateAdapter = false)
data class CollectorSource(
    /** 系统 ContentProvider 行 _id（参与记录 id 拼接，全局稳定于本设备）。 */
    @Json(name = "system_id") val systemId: Long,
    /**
     * 联系人聚合查找键（通讯录专属，其他类为 null）：系统联系人合并/行 id 变化时
     * 仍可关联同一逻辑联系人。
     */
    @Json(name = "lookup_key") val lookupKey: String? = null,
    /**
     * 系统侧该行最后变更时间（Unix 毫秒）：
     * 联系人 CONTACT_LAST_UPDATED_TIMESTAMP；短信/通话为行内 date（语义=发生时间）。
     * 仅作展示/排查，增量游标不以该字段的客户端解释为准。
     */
    @Json(name = "last_updated") val lastUpdated: Long = 0,
)

/** 联系人姓名部件（全部可空，系统中常缺失）。 */
@JsonClass(generateAdapter = false)
data class ContactName(
    @Json(name = "family") val family: String? = null,
    @Json(name = "given") val given: String? = null,
    @Json(name = "middle") val middle: String? = null,
    @Json(name = "prefix") val prefix: String? = null,
    @Json(name = "suffix") val suffix: String? = null,
)

/** 电话号码条目；type 归一化为 home/work/mobile/fax/pager/other/unknown。 */
@JsonClass(generateAdapter = false)
data class ContactPhone(
    @Json(name = "number") val number: String,
    @Json(name = "type") val type: String = "unknown",
    /** 系统自定义标签原文（仅当 type=custom 时有值）。 */
    @Json(name = "label") val label: String? = null,
    @Json(name = "is_primary") val isPrimary: Boolean = false,
)

/** 邮箱条目；type 归一化同电话。 */
@JsonClass(generateAdapter = false)
data class ContactEmail(
    @Json(name = "email") val email: String,
    @Json(name = "type") val type: String = "unknown",
    @Json(name = "label") val label: String? = null,
    @Json(name = "is_primary") val isPrimary: Boolean = false,
)

/** 邮政地址条目；formatted 为系统拼好的单行/多行文本。 */
@JsonClass(generateAdapter = false)
data class ContactAddress(
    @Json(name = "formatted") val formatted: String,
    @Json(name = "type") val type: String = "unknown",
    @Json(name = "label") val label: String? = null,
    @Json(name = "is_primary") val isPrimary: Boolean = false,
)

/** 通讯录明文（module="contact", type="contact"）。 */
@JsonClass(generateAdapter = false)
data class ContactData(
    /** 系统聚合显示名（必填；极端异常源行可能为空串）。 */
    @Json(name = "display_name") val displayName: String,
    @Json(name = "name") val name: ContactName? = null,
    @Json(name = "organization") val organization: String? = null,
    @Json(name = "job_title") val jobTitle: String? = null,
    @Json(name = "phones") val phones: List<ContactPhone> = emptyList(),
    @Json(name = "emails") val emails: List<ContactEmail> = emptyList(),
    @Json(name = "addresses") val addresses: List<ContactAddress> = emptyList(),
    /** 生日/事件起始日，原样保留系统格式（"yyyy-MM-dd" 或 "--MM-dd" 等）。 */
    @Json(name = "birthday") val birthday: String? = null,
    @Json(name = "notes") val notes: String? = null,
    @Json(name = "source") val source: CollectorSource,
)

/** 短信明文（module="sms", type="sms"）。 */
@JsonClass(generateAdapter = false)
data class SmsData(
    /** 对方号码/会话地址；匿名通知类短信可能为空。 */
    @Json(name = "address") val address: String? = null,
    @Json(name = "body") val body: String,
    /** 系统时间戳（Unix 毫秒）。 */
    @Json(name = "date") val date: Long,
    /** 归一化方向/文件夹：inbox/sent/draft/outbox/failed/queued/unknown。 */
    @Json(name = "type") val type: String,
    @Json(name = "read") val read: Boolean,
    @Json(name = "thread_id") val threadId: Long? = null,
    @Json(name = "source") val source: CollectorSource,
)

/** 通话记录明文（module="calllog", type="call"）。 */
@JsonClass(generateAdapter = false)
data class CallData(
    /** 拨号号码；受限/未知来电可能为空。 */
    @Json(name = "number") val number: String? = null,
    /** 系统缓存的匹配联系人姓名（原始快照，可能为空）。 */
    @Json(name = "name") val name: String? = null,
    /** 通话发生时间（Unix 毫秒）。 */
    @Json(name = "date") val date: Long,
    /** 通话时长（秒）。 */
    @Json(name = "duration") val duration: Long,
    /** 归一化类型：incoming/outgoing/missed/rejected/blocked/voicemail/unknown。 */
    @Json(name = "type") val type: String,
    @Json(name = "source") val source: CollectorSource,
)
