package com.everything.eve.collector.location.core

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 地理距离纯函数（阶段 4a，AC-12：与 Web 端同算法互证）。
 */
object GeoMath {

    /** WGS84 地球平均半径（米）。 */
    const val EARTH_RADIUS_M = 6_371_000.0

    /**
     * haversine 大圆距离（米）。
     *
     * 公式必须与 Web 端（stays.ts / stats.ts）逐行一致：
     *   a = sin²(Δφ/2) + cos φ1 · cos φ2 · sin²(Δλ/2)
     *   d = 2R · asin(√a)
     * 数值上 a 可能因浮点误差略超 1，clamp 处理防 NaN。
     */
    fun haversineM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val phi1 = Math.toRadians(lat1)
        val phi2 = Math.toRadians(lat2)
        val dPhi = Math.toRadians(lat2 - lat1)
        val dLambda = Math.toRadians(lon2 - lon1)
        val sinDPhi = sin(dPhi / 2)
        val sinDLambda = sin(dLambda / 2)
        val a = sinDPhi * sinDPhi + cos(phi1) * cos(phi2) * sinDLambda * sinDLambda
        return 2 * EARTH_RADIUS_M * asin(sqrt(a.coerceIn(0.0, 1.0)))
    }
}
