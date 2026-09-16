package com.everything.eve.collector.core

/**
 * 采集器类别（阶段 3）。
 *
 * 该枚举是"系统数据源 ↔ 零知识记录 (module, type)"之间的唯一映射点：
 *  - [shortName] 参与记录 id 拼接（见 [CollectorIds]）；
 *  - [module]/[type] 决定密封信封的 AAD 域与服务端归类；
 * 三端 module/type 约定以 docs/module-schemas.md 为准。
 *
 * 注意：本文件不得依赖 Android Framework 或 libsodium（属于 JVM 纯函数核心，
 * 由 testDebugUnitTest 直接覆盖）。
 */
enum class CollectorKind(
    val shortName: String,
    val module: String,
    val type: String,
) {
    /** 通讯录：module="contact", type="contact"。 */
    CONTACT("contact", "contact", "contact"),

    /** 短信：module="sms", type="sms"。 */
    SMS("sms", "sms", "sms"),

    /** 通话记录：module="calllog", type="call"。 */
    CALLLOG("calllog", "calllog", "call"),
    ;

    companion object {
        /** 按 shortName 解析（持久化游标表 key 使用 shortName）。 */
        fun fromShortName(name: String): CollectorKind? =
            entries.firstOrNull { it.shortName == name }
    }
}
