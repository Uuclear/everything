package com.everything.eve.collector.location.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 位置轨迹明文缓冲点表（v4 迁移新增，阶段 4a）。
 *
 * 字段与 [com.everything.eve.collector.location.core.TrackPoint] 逐一对齐，
 * 仅多出本地管理列 [id]（自增主键，不随块上行）与 [createdAt]（入库时间）。
 *
 * 零知识红线（spec FR-4 / NFR-1）：本表是全库唯一明文坐标驻留点，
 * 其生命周期由后续封块编排（Task 5 LocationPackager）保证——
 * 封块加密后即删（[LocationDao.deleteUpToTs]）、超 24 小时未加密强制过期删除
 * （[LocationDao.deleteExpired]）；坐标明文严禁进日志 / 通知 /
 * SharedPreferences / 异常消息。
 */
@Entity(
    tableName = "location_points",
    // (ts) 索引：封块全取（ts ASC）、已封段删除、24h 过期清理、今日点数统计均按 ts 过滤/排序。
    indices = [Index(value = ["ts"])],
)
data class LocationPointEntity(
    /** 自增主键（仅本地行标识，不入块、不上行）。 */
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** 采样时刻（UTC 毫秒，取设备系统时间）。 */
    val ts: Long,
    /** 纬度（WGS84 度）。 */
    val lat: Double,
    /** 经度（WGS84 度）。 */
    val lon: Double,
    /** 定位精度半径（米）；入库前必经 PointFilter 过滤（>100m 丢弃）。 */
    val acc: Float,
    /** 瞬时速度（米/秒），系统未提供时为 null。 */
    val speed: Float? = null,
    /** 航向角（度，正北为 0 顺时针），系统未提供时为 null。 */
    val bearing: Float? = null,
    /** 海拔（米），系统未提供时为 null。 */
    val altitude: Double? = null,
    /** 定位来源（"gps"/"network" 等 LocationManager provider 名），未知为 null。 */
    val provider: String? = null,
    /** 入库本地毫秒时间戳（观测/调试用；封块与过期判定均以采样时刻 ts 为准）。 */
    @ColumnInfo(name = "created_at") val createdAt: Long,
)
