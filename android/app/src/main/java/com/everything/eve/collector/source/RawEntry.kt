package com.everything.eve.collector.source

/**
 * 系统数据源的一行原始结果：系统行身份 + 游标时间列 + 已映射的明文 DTO。
 *
 * @param T 三类 DTO 之一（ContactData / SmsData / CallData）
 * @param systemId 系统 ContentProvider 行 _id（参与记录 id 拼接与游标推进）
 * @param timestamp 游标时间列值：联系人=最后变更时间，短信/通话=发生时间
 */
data class RawEntry<out T>(
    val systemId: Long,
    val timestamp: Long,
    val data: T,
)
