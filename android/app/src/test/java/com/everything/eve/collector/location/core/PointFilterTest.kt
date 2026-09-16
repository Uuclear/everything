package com.everything.eve.collector.location.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Task 3 TR-3.1：精度过滤边界（spec FR-1 / AC-1）。
 */
class PointFilterTest {

    @Test
    fun accuracyExactlyAtThreshold_isAccepted() {
        // 边界等值：acc=100 恰为上限，接受。
        assertTrue(PointFilter.accept(100f))
    }

    @Test
    fun accuracyBelowThreshold_isAccepted() {
        assertTrue(PointFilter.accept(5f))
        assertTrue(PointFilter.accept(99.9f))
    }

    @Test
    fun accuracyAboveThreshold_isDropped() {
        // 超差丢弃：100.5 与 101 均不接受。
        assertFalse(PointFilter.accept(100.5f))
        assertFalse(PointFilter.accept(101f))
        assertFalse(PointFilter.accept(500f))
    }

    @Test
    fun nullAccuracy_isConservativelyDropped() {
        // 系统未提供精度信息（hasAccuracy=false）→ 视为不可用，保守丢弃。
        assertFalse(PointFilter.accept(null))
    }
}
