package com.everything.eve.collector.location.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Task 3 TR-3.1：haversine 已知对照（spec AC-12，与 Web 同算法互证）。
 */
class GeoMathTest {

    @Test
    fun beijingToShanghai_isAbout1067km() {
        // 北京(39.9042, 116.4074) → 上海(31.2304, 121.4737)，公开参考约 1067km。
        val d = GeoMath.haversineM(39.9042, 116.4074, 31.2304, 121.4737)
        val expected = 1_067_000.0
        // 容差 1%。
        assertTrue(
            "haversine 北京→上海=$d 应落在 1067km ±1% 内",
            d > expected * 0.99 && d < expected * 1.01,
        )
    }

    @Test
    fun zeroDistance_isZero() {
        assertEquals(0.0, GeoMath.haversineM(39.9042, 116.4074, 39.9042, 116.4074), 1e-9)
        // 极点/对跖等极端坐标也不应 NaN。
        assertEquals(0.0, GeoMath.haversineM(90.0, 0.0, 90.0, 180.0), 1e-9)
    }

    @Test
    fun smallDistance_scalesWithLatitudeDegree() {
        // 纬度 0.001° 在低纬度约 111.2m（1/111320 度每米），验证量级正确。
        val d = GeoMath.haversineM(39.9042, 116.4074, 39.9052, 116.4074)
        assertEquals(111.2, d, 2.0)
    }

    @Test
    fun antipodal_isAboutHalfCircumference() {
        // 赤道对跖点：约半个赤道周长 πR ≈ 20015km。
        val d = GeoMath.haversineM(0.0, 0.0, 0.0, 180.0)
        assertEquals(Math.PI * GeoMath.EARTH_RADIUS_M, d, 1.0)
    }
}
