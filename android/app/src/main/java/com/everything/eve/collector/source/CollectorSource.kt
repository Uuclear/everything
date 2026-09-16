package com.everything.eve.collector.source

import android.content.ContentResolver
import com.everything.eve.collector.core.CollectorCursor
import com.everything.eve.collector.core.CollectorKind

/**
 * 系统 ContentProvider 读取适配层接口（阶段 3）。
 *
 * 职责边界：只做"系统行 → 明文 DTO"映射与游标分页查询；
 * 不做加密、入库、日志（这些归 CollectorEngine）。
 * ContentResolver 以参数注入，便于 instrumented 测试替换。
 *
 * 命名说明：core 包已有溯源数据类 `CollectorSource`，为避免同名混淆，
 * 本接口命名为 SystemSource。
 */
interface SystemSource<T> {

    /** 本源对应的采集类别。 */
    val kind: CollectorKind

    /**
     * 按复合游标增量查询，至多返回 [limit] 行。
     *
     * 查询形态固定（spec FR-3）：
     *   WHERE time > ? OR (time = ? AND _id > ?)
     *   ORDER BY time ASC, _id ASC  LIMIT limit
     *
     * @throws SecurityException 权限被回收时抛出（由引擎降级为跳过原因）
     */
    fun query(
        resolver: ContentResolver,
        cursor: CollectorCursor,
        limit: Int,
    ): List<RawEntry<T>>
}
