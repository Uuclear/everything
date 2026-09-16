package com.everything.eve.collector.core

/**
 * 系统整数类型码 → schema 字符串枚举的归一化映射（阶段 3，spec FR-1）。
 *
 * 全部为纯函数：Android Framework 常量值（Telephony.Sms.MESSAGE_TYPE_*、
 * CallLog.Calls.INCOMING_TYPE 等、CommonDataKinds.Phone.TYPE_*）在各 API 版本稳定，
 * 这里以字面值单点维护，使映射可被 JVM 单测全枚举覆盖。
 *
 * 未知码一律兜底 "unknown"，绝不抛异常中断整轮采集。
 */
object TypeMaps {

    // ---- 短信文件夹/方向（android.provider.Telephony.TextBasedSmsColumns）----
    // 1=inbox 2=sent 3=draft 4=outbox 5=failed 6=queued
    private val SMS_TYPES = mapOf(
        1 to "inbox",
        2 to "sent",
        3 to "draft",
        4 to "outbox",
        5 to "failed",
        6 to "queued",
    )

    fun smsType(code: Int): String = SMS_TYPES[code] ?: "unknown"

    // ---- 通话类型（android.provider.CallLog.Calls）----
    // 1=incoming 2=outgoing 3=missed 4=voicemail 5=rejected 6=blocked
    private val CALL_TYPES = mapOf(
        1 to "incoming",
        2 to "outgoing",
        3 to "missed",
        4 to "voicemail",
        5 to "rejected",
        6 to "blocked",
    )

    fun callType(code: Int): String = CALL_TYPES[code] ?: "unknown"

    // ---- 通讯录电话/邮箱/地址标签（CommonDataKinds.CommonColumns）----
    // 标准类型码在 Phone/Email/StructuredPostal 三表中同值：
    // 1=home 2=mobile(仅电话) 3=work 4=fax_work 5=fax_home 6=pager
    // 7=other 10=company_main 12=main 13=other_fax 17=work_mobile 18=work_pager
    // 0 是 TYPE_CUSTOM（自定义标签由调用方传入原文）。
    private val CONTACT_TYPES = mapOf(
        1 to "home",
        2 to "mobile",
        3 to "work",
        4 to "fax",
        5 to "fax",
        6 to "pager",
        7 to "other",
        10 to "work",
        12 to "main",
        13 to "fax",
        17 to "mobile",
        18 to "pager",
    )

    /**
     * @param code 系统 TYPE_* 整数码
     * @param customLabel TYPE_CUSTOM(0) 时系统给出的自定义标签原文，其余情况忽略
     */
    fun contactType(code: Int, customLabel: String? = null): String {
        if (code == 0) return "custom"
        return CONTACT_TYPES[code] ?: "unknown"
    }
}
