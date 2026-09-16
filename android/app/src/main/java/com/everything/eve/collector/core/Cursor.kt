package com.everything.eve.collector.core

/**
 * 增量采集复合游标（spec FR-3）：(系统时间戳, 系统 _id)。
 *
 * 三类数据源都按 "(time ASC, _id ASC)" 全序遍历；仅以 time 做 high-watermark 会在
 * 同一毫秒/秒（通话记录部分设备精度到秒、短信/联系人毫秒但仍可能撞值）并列时漏行，
 * 因此并列时以 _id 同向续进：
 *
 *   WHERE time > ? OR (time = ? AND _id > ?)
 *
 * 首轮 (0, 0) 即从头全量。游标在每类每轮成功处理后持久化到 collector_state。
 * 纯数据 + 纯 SQL 片段生成，不接触 Cursor/ContentResolver（JVM 可测）。
 */
data class CollectorCursor(
    val timestamp: Long,
    val systemId: Long,
) {

    /** 首轮零值：全量采集。 */
    val isInitial: Boolean get() = timestamp == 0L && systemId == 0L

    companion object {
        /** 单轮单类处理上限（spec FR-4）：到量即停，下轮从最后行之后续采。 */
        const val MAX_PER_KIND_PER_RUN = 400

        val INITIAL = CollectorCursor(0L, 0L)

        /**
         * 生成 WHERE 子句（不含 LIMIT）。列名由 Source 适配层以常量传入，
         * 防止用户输入注入（调用方只可能传代码内常量）。
         */
        fun selection(timeColumn: String, idColumn: String): String =
            "($timeColumn > ? OR ($timeColumn = ? AND $idColumn > ?))"

        /** WHERE 绑定参数，顺序与 [selection] 占位符一致：time, time, id。 */
        fun selectionArgs(cursor: CollectorCursor): Array<String> = arrayOf(
            cursor.timestamp.toString(),
            cursor.timestamp.toString(),
            cursor.systemId.toString(),
        )

        /** 排序：与游标全序一致。 */
        fun orderBy(timeColumn: String, idColumn: String): String =
            "$timeColumn ASC, $idColumn ASC"

        /**
         * 由刚处理完的行推进游标。行必须严格在旧游标之后（Source 查询保证），
         * 这里做防御性单调校验：不允许游标回退。
         */
        fun advance(current: CollectorCursor, rowTime: Long, rowId: Long): CollectorCursor {
            val movedForward = rowTime > current.timestamp ||
                (rowTime == current.timestamp && rowId > current.systemId)
            require(movedForward || current.isInitial) {
                "游标回退：current=($current) row=($rowTime,$rowId)"
            }
            return CollectorCursor(rowTime, rowId)
        }

        /**
         * 单轮预算判定：本类本轮是否到量。
         * 已处理数达到 [MAX_PER_KIND_PER_RUN] 即截断，游标停在最后处理行。
         */
        fun reachedLimit(processedCount: Int): Boolean =
            processedCount >= MAX_PER_KIND_PER_RUN
    }
}
