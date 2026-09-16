package com.everything.eve.collector.location.core

/**
 * 轨迹分块与块 id 派生（FR-3）。
 *
 * 纯函数：输入明文点流，输出待加密封块的块明文 DTO 列表；
 * 块 id 确定性派生保证同参数重复封块产生同 id（outbox / 服务端按 id 幂等）。
 */
object BlockPacker {

    /**
     * 块 id 规则（三端契约，docs/module-schemas.md 登记）：
     *
     *   "{deviceId}:{startTs}:{endTs}"
     *
     * 时间均为 UTC 毫秒。确定性派生是幂等链路的锚点：同设备同一批点重复封块
     * 必然得到同 id，outbox INSERT OR IGNORE 与服务端 INSERT OR IGNORE 据此去重。
     */
    fun blockId(deviceId: String, startTs: Long, endTs: Long): String {
        require(deviceId.isNotEmpty()) { "deviceId 为空：未配对设备不得封块" }
        return "$deviceId:$startTs:$endTs"
    }

    /**
     * 把明文点流切成轨迹块。
     *
     * 规则（spec FR-3）：
     *  - 按 ts 升序遍历（输入乱序时先排序，保证纯函数输出只依赖集合内容）；
     *  - 块内点数达 [LocationParams.MAX_POINTS_PER_BLOCK]（100）即结算；
     *  - 当前点 ts 距块首点 ts ≥ [LocationParams.MAX_BLOCK_SPAN_MS]（1 小时）
     *    即结算（末点-首点 = 3_599_999 同块，= 3_600_000 开新块）；
     *  - 空输入返回空列表；单点自成一块。
     *
     * @param deviceId 采集设备 id（写入块头并参与块 id 派生），不允许为空。
     * @param points   明文点流（通常来自 Room oldestFirst 全量取出的缓冲点）。
     * @return 块明文 DTO 列表（ts 升序），块 id 用 [blockId] 另行派生。
     */
    fun pack(deviceId: String, points: List<TrackPoint>): List<LocationBlockJson> {
        require(deviceId.isNotEmpty()) { "deviceId 为空：未配对设备不得封块" }
        if (points.isEmpty()) return emptyList()

        val sorted = points.sortedBy { it.ts }
        val blocks = mutableListOf<LocationBlockJson>()
        var current = mutableListOf<TrackPoint>()
        var blockStartTs = sorted.first().ts

        fun settle() {
            if (current.isEmpty()) return
            blocks.add(
                LocationBlockJson(
                    deviceId = deviceId,
                    startTs = current.first().ts,
                    endTs = current.last().ts,
                    points = current.toList(),
                ),
            )
            current = mutableListOf()
        }

        for (p in sorted) {
            // 先判结算条件，再入点：超限点归入新块块首。
            if (current.isNotEmpty() &&
                (current.size >= LocationParams.MAX_POINTS_PER_BLOCK ||
                    p.ts - blockStartTs >= LocationParams.MAX_BLOCK_SPAN_MS)
            ) {
                settle()
                blockStartTs = p.ts
            }
            if (current.isEmpty()) blockStartTs = p.ts
            current.add(p)
        }
        settle()
        return blocks
    }
}
