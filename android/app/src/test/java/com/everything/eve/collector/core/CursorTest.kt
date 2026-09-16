package com.everything.eve.collector.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Task 1 TR-1.1：复合游标边界（spec FR-3）与单轮预算截断（FR-4）。
 */
class CursorTest {

    private val timeCol = "date"
    private val idCol = "_id"

    @Test
    fun initialCursor_meansFullScan() {
        assertTrue(CollectorCursor.INITIAL.isInitial)
        assertFalse(CollectorCursor(1, 0).isInitial)
        // 首轮参数：time>0 永远不成立，time=0 AND _id>0 也不成立 → 全表。
        val args = CollectorCursor.selectionArgs(CollectorCursor.INITIAL)
        assertEquals(listOf("0", "0", "0"), args.toList())
    }

    @Test
    fun sqlFragments_haveTieBreakerShape() {
        assertEquals(
            "(date > ? OR (date = ? AND _id > ?))",
            CollectorCursor.selection(timeCol, idCol),
        )
        assertEquals("date ASC, _id ASC", CollectorCursor.orderBy(timeCol, idCol))
        val mid = CollectorCursor(1_700_000_000_000L, 55L)
        assertEquals(
            listOf("1700000000000", "1700000000000", "55"),
            CollectorCursor.selectionArgs(mid).toList(),
        )
    }

    @Test
    fun advance_movesForwardWithinSameTimestampByid() {
        // 同一毫秒并列：必须靠 _id 同向续进，否则漏行。
        val c0 = CollectorCursor(1000L, 10L)
        val c1 = CollectorCursor.advance(c0, 1000L, 11L)
        assertEquals(CollectorCursor(1000L, 11L), c1)
        // 时间戳前进时 _id 可从更小值重新开始（新一批行）。
        val c2 = CollectorCursor.advance(c1, 1001L, 1L)
        assertEquals(CollectorCursor(1001L, 1L), c2)
    }

    @Test
    fun advance_rejectsBackwardRows() {
        val c0 = CollectorCursor(1000L, 10L)
        // 时间戳后退、同时间戳 _id 后退都属于 Source 排序被破坏，快速失败。
        assertThrows(IllegalArgumentException::class.java) {
            CollectorCursor.advance(c0, 999L, 100L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            CollectorCursor.advance(c0, 1000L, 9L)
        }
    }

    @Test
    fun budget_cutsAt400() {
        assertEquals(400, CollectorCursor.MAX_PER_KIND_PER_RUN)
        assertFalse(CollectorCursor.reachedLimit(399))
        assertTrue(CollectorCursor.reachedLimit(400))
        assertTrue(CollectorCursor.reachedLimit(401))
    }
}
