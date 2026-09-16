package com.everything.eve.collector.location.core

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/**
 * 位置轨迹（阶段 4a）数据模型。
 *
 * 块明文 JSON 字段名（snake_case）是 Android / docs/module-schemas.md /
 * Web decode.ts 三方契约（tasks.md Task 3 Notes），定稿后逐字段一致，
 * 任何改动必须三方同步。
 *
 * 仅 moshi 反射序列化（与 collector/core/Models.kt 同风格），
 * 不依赖 Android Framework，JVM 单测可直接断言。
 */

/** 单个轨迹点（明文仅存本地 Room 缓冲，封块加密后即删）。 */
@JsonClass(generateAdapter = false)
data class TrackPoint(
    /** 采样时刻（UTC 毫秒，取设备系统时间）。 */
    @Json(name = "ts") val ts: Long,
    /** 纬度（WGS84 度）。 */
    @Json(name = "lat") val lat: Double,
    /** 经度（WGS84 度）。 */
    @Json(name = "lon") val lon: Double,
    /** 定位精度半径（米）；入库前必经 PointFilter 过滤（>100m 丢弃）。 */
    @Json(name = "acc") val acc: Float,
    /** 瞬时速度（米/秒），系统未提供时为 null。 */
    @Json(name = "speed") val speed: Float? = null,
    /** 航向角（度，正北为 0 顺时针），系统未提供时为 null。 */
    @Json(name = "bearing") val bearing: Float? = null,
    /** 海拔（米），系统未提供时为 null。 */
    @Json(name = "altitude") val altitude: Double? = null,
    /** 定位来源（"gps"/"network" 等 LocationManager provider 名），未知为 null。 */
    @Json(name = "provider") val provider: String? = null,
)

/**
 * 轨迹块明文 DTO（封块后以 MK 加密为 XChaCha20-Poly1305 信封上行）。
 *
 * 块 id 不在 JSON 内冗余存储，由 [BlockPacker.blockId] 纯函数从
 * (device_id, start_ts, end_ts) 派生，保证同参数重复封块产生同 id（幂等）。
 */
@JsonClass(generateAdapter = false)
data class LocationBlockJson(
    /** 采集设备 id（服务端配对设备 id，多设备块经该前缀天然隔离）。 */
    @Json(name = "device_id") val deviceId: String,
    /** 块首点时刻（UTC 毫秒，服务端月表归属按该值 UTC 月份）。 */
    @Json(name = "start_ts") val startTs: Long,
    /** 块末点时刻（UTC 毫秒）。 */
    @Json(name = "end_ts") val endTs: Long,
    /** 块内轨迹点（ts 升序，≤ [LocationParams.MAX_POINTS_PER_BLOCK]）。 */
    @Json(name = "points") val points: List<TrackPoint>,
)
