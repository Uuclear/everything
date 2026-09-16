package com.everything.eve.collector.location.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Task 3 TR-3.1：分块策略与幂等块 id（spec FR-3 / AC-3）。
 */
class BlockPackerTest {

    private val deviceA = "dev-aaaa"
    private val deviceB = "dev-bbbb"
    private val baseTs = 1_700_000_000_000L

    /**
     * 构造 n 个点：ts 从 baseTs 起每点间隔 stepMs，坐标固定（分块与坐标无关）。
     * 默认 30s 间隔：100 点跨度 99*30s=49.5min < 1h，隔离"点数上限"单变量。
     */
    private fun points(n: Int, stepMs: Long = 30_000L, startTs: Long = baseTs): List<TrackPoint> =
        (0 until n).map { i ->
            TrackPoint(ts = startTs + i * stepMs, lat = 39.9042, lon = 116.4074, acc = 10f)
        }

    @Test
    fun exactly100Points_formSingleBlock() {
        // 恰好 100 点：点数达上限但无第 101 点触发结算，单块 100 点。
        val blocks = BlockPacker.pack(deviceA, points(100))
        assertEquals(1, blocks.size)
        assertEquals(100, blocks[0].points.size)
        assertEquals(baseTs, blocks[0].startTs)
        assertEquals(baseTs + 99 * 30_000L, blocks[0].endTs)
        assertEquals(deviceA, blocks[0].deviceId)
    }

    @Test
    fun oneHundredOnePoints_splitIntoTwoBlocks() {
        // 101 点：首块 100 点封块，余 1 点自成一块（100+1）。
        val blocks = BlockPacker.pack(deviceA, points(101))
        assertEquals(2, blocks.size)
        assertEquals(100, blocks[0].points.size)
        assertEquals(1, blocks[1].points.size)
        // 两块时间区间衔接不重叠：第二块首点 = 首块末点的下一采样时刻。
        assertEquals(blocks[0].endTs + 30_000L, blocks[1].startTs)
        assertEquals(blocks[1].startTs, blocks[1].endTs) // 单点块 start=end
    }

    @Test
    fun spanJustBelowOneHour_staysInSameBlock() {
        // 末点-首点 = 3_599_999ms（<1h）：2 点同块。
        val pts = listOf(
            TrackPoint(ts = baseTs, lat = 39.9, lon = 116.4, acc = 10f),
            TrackPoint(ts = baseTs + 3_599_999L, lat = 39.9, lon = 116.4, acc = 10f),
        )
        val blocks = BlockPacker.pack(deviceA, pts)
        assertEquals(1, blocks.size)
        assertEquals(2, blocks[0].points.size)
        assertEquals(baseTs + 3_599_999L, blocks[0].endTs)
    }

    @Test
    fun spanExactlyOneHour_opensNewBlock() {
        // 末点-首点 = 3_600_000ms（=1h）：触发结算，末点开新块 → 2 块各 1 点。
        val pts = listOf(
            TrackPoint(ts = baseTs, lat = 39.9, lon = 116.4, acc = 10f),
            TrackPoint(ts = baseTs + 3_600_000L, lat = 39.9, lon = 116.4, acc = 10f),
        )
        val blocks = BlockPacker.pack(deviceA, pts)
        assertEquals(2, blocks.size)
        assertEquals(1, blocks[0].points.size)
        assertEquals(1, blocks[1].points.size)
        assertEquals(baseTs + 3_600_000L, blocks[1].startTs)
    }

    @Test
    fun emptyInput_producesZeroBlocks() {
        assertTrue(BlockPacker.pack(deviceA, emptyList()).isEmpty())
    }

    @Test
    fun singlePoint_formsSingleBlock() {
        val blocks = BlockPacker.pack(deviceA, points(1))
        assertEquals(1, blocks.size)
        assertEquals(1, blocks[0].points.size)
        assertEquals(blocks[0].startTs, blocks[0].endTs)
    }

    @Test
    fun sameInput_packedTwice_blockIdsAreIdentical() {
        // 幂等：同输入两次 pack，逐块派生 id 完全一致（outbox/服务端按 id 去重的前提）。
        val input = points(150)
        val first = BlockPacker.pack(deviceA, input)
        val second = BlockPacker.pack(deviceA, input)
        assertEquals(first, second)
        val ids1 = first.map { BlockPacker.blockId(it.deviceId, it.startTs, it.endTs) }
        val ids2 = second.map { BlockPacker.blockId(it.deviceId, it.startTs, it.endTs) }
        assertEquals(ids1, ids2)
        // id 形态契约：{deviceId}:{startTs}:{endTs}。
        assertEquals("$deviceA:${first[0].startTs}:${first[0].endTs}", ids1[0])
    }

    @Test
    fun differentDevices_produceDifferentIdPrefixes() {
        // 同一块点跨设备：块 id 前缀天然隔离，多设备主键不冲突。
        val input = points(10)
        val a = BlockPacker.pack(deviceA, input).first()
        val b = BlockPacker.pack(deviceB, input).first()
        val idA = BlockPacker.blockId(a.deviceId, a.startTs, a.endTs)
        val idB = BlockPacker.blockId(b.deviceId, b.startTs, b.endTs)
        assertTrue(idA.startsWith("$deviceA:"))
        assertTrue(idB.startsWith("$deviceB:"))
        assertTrue(idA != idB)
    }

    @Test
    fun unsortedInput_isSortedBeforePacking() {
        // 纯函数健壮性：输入乱序时输出与升序输入一致（输出只依赖集合内容）。
        val sorted = points(5)
        val shuffled = listOf(sorted[3], sorted[0], sorted[4], sorted[1], sorted[2])
        assertEquals(
            BlockPacker.pack(deviceA, sorted),
            BlockPacker.pack(deviceA, shuffled),
        )
    }
}
