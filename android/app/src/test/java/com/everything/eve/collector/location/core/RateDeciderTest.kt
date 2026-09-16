package com.everything.eve.collector.location.core

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Task 3 TR-3.1：静止降频判定（spec FR-1 / AC-1）。
 *
 * 参考纬度：lat 差 0.0001° ≈ 11.1m（静止簇内抖动）；
 * lat 差 0.001° ≈ 111m（明确位移，远超 50m 阈值）。
 */
class RateDeciderTest {

    private val now = 1_000_000_000_000L

    /** 构造点：相对 now 前 offsetMs 时刻，纬度加 dLat 度。 */
    private fun pt(offsetMs: Long, dLat: Double = 0.0): TrackPoint =
        TrackPoint(ts = now - offsetMs, lat = 39.9042 + dLat, lon = 116.4074, acc = 10f)

    @Test
    fun stillWithinWindow_allDisplacementsBelow50m_returns300s() {
        // 窗口内 3 个点，两两位移均约 11~22m（<50m）→ 静止档 300s。
        val points = listOf(
            pt(offsetMs = 9 * 60_000L, dLat = 0.0),
            pt(offsetMs = 5 * 60_000L, dLat = 0.0001),
            pt(offsetMs = 60_000L, dLat = -0.0001),
        )
        assertEquals(LocationParams.STILL_INTERVAL_MS, RateDecider.decideInterval(points, now))
    }

    @Test
    fun anyDisplacementAbove50m_returns60s() {
        // 窗口内存在一对点位移约 111m（≥50m）→ 常规档 60s。
        val points = listOf(
            pt(offsetMs = 9 * 60_000L, dLat = 0.0),
            pt(offsetMs = 5 * 60_000L, dLat = 0.001), // 111m 外
            pt(offsetMs = 60_000L, dLat = 0.001),
        )
        assertEquals(LocationParams.MIN_TIME_MS, RateDecider.decideInterval(points, now))
    }

    @Test
    fun fewerThanTwoPointsInWindow_returns60s() {
        // 窗口内 0 个点。
        assertEquals(LocationParams.MIN_TIME_MS, RateDecider.decideInterval(emptyList(), now))
        // 窗口内仅 1 个点（无法判定位移，维持常规档）。
        assertEquals(
            LocationParams.MIN_TIME_MS,
            RateDecider.decideInterval(listOf(pt(offsetMs = 60_000L)), now),
        )
    }

    @Test
    fun windowSlide_oldPointsLeaveWindow_stillSetRecoversTo300s() {
        // 11 分钟前的远点（111m 位移）已出窗，窗内只剩静止簇 → 恢复静止档。
        val points = listOf(
            pt(offsetMs = 11 * 60_000L, dLat = 0.001), // 出窗旧点，不参与判定
            pt(offsetMs = 8 * 60_000L, dLat = 0.0),
            pt(offsetMs = 60_000L, dLat = 0.0001),
        )
        assertEquals(LocationParams.STILL_INTERVAL_MS, RateDecider.decideInterval(points, now))
    }

    @Test
    fun windowSlide_pointsLeaveWindow_insufficientPointsRecoversTo60s() {
        // 静止簇整体滑出窗口后窗内不足 2 点 → 恢复常规档 60s。
        val points = listOf(
            pt(offsetMs = 30 * 60_000L, dLat = 0.0),
            pt(offsetMs = 20 * 60_000L, dLat = 0.0001),
        )
        assertEquals(LocationParams.MIN_TIME_MS, RateDecider.decideInterval(points, now))
    }

    @Test
    fun futurePointsBeyondNow_areIgnored() {
        // 时钟异常产生的"未来点"（ts > now）不计入窗口：窗内仍 2 点静止 → 300s。
        val points = listOf(
            pt(offsetMs = 8 * 60_000L, dLat = 0.0),
            pt(offsetMs = 60_000L, dLat = 0.0001),
            TrackPoint(ts = now + 60_000L, lat = 40.9, lon = 116.4074, acc = 10f), // 未来远点
        )
        assertEquals(LocationParams.STILL_INTERVAL_MS, RateDecider.decideInterval(points, now))
    }
}
