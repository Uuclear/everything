package com.everything.eve.collector.core

/**
 * 采集记录 id 规则（阶段 3，spec FR-2）：
 *
 *   "{deviceId}:{kind.shortName}:{systemId}"
 *
 * - deviceId：服务端配对设备 id（AuthManager.deviceId）。同一用户多台 Android 设备
 *   采集相同系统 _id 时不会发生主键冲突；
 * - systemId：系统 ContentProvider 行 _id（Long），在本设备本类数据源内稳定；
 * - 确定性派生使重复采集天然幂等（同 id upsert + version LWW），无需额外去重表。
 *
 * 纯函数、无框架依赖。
 */
object CollectorIds {

    /** 生成信封记录 id。deviceId 不允许为空（未配对设备不应进入采集流程）。 */
    fun recordId(deviceId: String, kind: CollectorKind, systemId: Long): String {
        require(deviceId.isNotEmpty()) { "deviceId 为空：未配对设备不得采集" }
        return "$deviceId:${kind.shortName}:$systemId"
    }

    /**
     * 反解（仅用于排查/测试）：返回 Pair(kind, systemId)。
     * 无法识别格式或类别时返回 null——生产比对逻辑应直接持有 systemId，不依赖反解。
     */
    fun parse(recordIdValue: String, deviceId: String): Pair<CollectorKind, Long>? {
        val prefix = "$deviceId:"
        if (!recordIdValue.startsWith(prefix)) return null
        val rest = recordIdValue.substring(prefix.length)
        val sep = rest.indexOf(':')
        if (sep <= 0 || sep == rest.lastIndex) return null
        val kind = CollectorKind.fromShortName(rest.substring(0, sep)) ?: return null
        val sysId = rest.substring(sep + 1).toLongOrNull() ?: return null
        return kind to sysId
    }
}
