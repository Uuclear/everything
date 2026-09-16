package com.everything.eve.collector.location.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Task 6 TR-6.2：轨迹服务启动前置检查四分支（tasks.md Task 6 / spec FR-2、FR-8）。
 *
 * 四分支按 tasks.md 列举顺序短路：轨迹开关 → MK 非空 → FINE 已授权 →
 * 后台定位"始终允许"；任一不满足即阻止启动并返回对应枚举原因。
 */
class TrackStartCheckTest {

    /** 全条件齐备的基线调用，返回 null（允许启动）。 */
    private fun decide(
        trackingEnabled: Boolean = true,
        mkAvailable: Boolean = true,
        fineGranted: Boolean = true,
        backgroundGranted: Boolean = true,
    ): TrackStopReason? = TrackStartCheck.blockedReason(
        trackingEnabled = trackingEnabled,
        mkAvailable = mkAvailable,
        fineGranted = fineGranted,
        backgroundGranted = backgroundGranted,
    )

    @Test
    fun allConditionsMet_returnsNull() {
        assertNull(decide())
    }

    @Test
    fun trackingDisabled_blocksWithDisabled() {
        assertEquals(TrackStopReason.DISABLED, decide(trackingEnabled = false))
    }

    @Test
    fun mkUnavailable_blocksWithMkUnavailable() {
        assertEquals(TrackStopReason.MK_UNAVAILABLE, decide(mkAvailable = false))
    }

    @Test
    fun fineDenied_blocksWithFineDenied() {
        assertEquals(TrackStopReason.FINE_DENIED, decide(fineGranted = false))
    }

    @Test
    fun backgroundDenied_blocksWithBackgroundDenied() {
        assertEquals(TrackStopReason.BACKGROUND_DENIED, decide(backgroundGranted = false))
    }

    @Test
    fun multipleFailures_shortCircuitsInSpecOrder() {
        // 开关关 + 无 MK：开关分支优先（tasks.md 列举顺序）
        assertEquals(
            TrackStopReason.DISABLED,
            decide(trackingEnabled = false, mkAvailable = false),
        )
        // 无 MK + 无 FINE：MK 分支优先
        assertEquals(
            TrackStopReason.MK_UNAVAILABLE,
            decide(mkAvailable = false, fineGranted = false),
        )
        // 无 FINE + 无后台：FINE 分支优先
        assertEquals(
            TrackStopReason.FINE_DENIED,
            decide(fineGranted = false, backgroundGranted = false),
        )
    }

    @Test
    fun wireNames_stableContract() {
        // wireName 是持久化/展示契约，防误改
        assertEquals("disabled", TrackStopReason.DISABLED.wireName)
        assertEquals("mk_unavailable", TrackStopReason.MK_UNAVAILABLE.wireName)
        assertEquals("fine_denied", TrackStopReason.FINE_DENIED.wireName)
        assertEquals("background_denied", TrackStopReason.BACKGROUND_DENIED.wireName)
        assertEquals("service_error", TrackStopReason.SERVICE_ERROR.wireName)
    }
}
