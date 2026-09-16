package com.everything.eve.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 采集器状态表（v3 迁移新增）。
 *
 * 每个采集类别（contact/sms/calllog，即 [com.everything.eve.collector.core.CollectorKind.shortName]）
 * 一行，记录复合增量游标与最近一次运行的统计信息。
 *
 * 零知识约束（NFR-1）：本表只存游标、计数与枚举化跳过原因，
 * 严禁写入任何明文业务字段（号码、姓名、正文等）。
 */
@Entity(tableName = "collector_state")
data class CollectorStateEntity(
    /** 采集类别短名：contact / sms / calllog */
    @PrimaryKey val kind: String,
    /** 复合游标：已确认入库记录的最大业务时间戳（毫秒） */
    val lastTimestamp: Long = 0,
    /** 复合游标：同时间戳下已推进到的最大系统 _id */
    val lastSystemId: Long = 0,
    /** 最近一次采集运行完成的本地毫秒时间戳；null 表示从未运行 */
    val lastRunAt: Long? = null,
    /** 最近一次运行实际扫描的系统行数（用于 UI 展示与诊断） */
    val lastScannedCount: Int = 0,
    /** 最近一次跳过的枚举化原因（如 "no_permission" / "mk_locked"）；null 表示未跳过 */
    val lastSkipReason: String? = null,
)
