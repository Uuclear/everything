package com.everything.eve.collector.source

import android.content.ContentResolver
import android.database.Cursor
import android.os.Bundle
import com.everything.eve.collector.core.CollectorCursor

/**
 * 把纯函数游标产物打包成 ContentResolver.query 的 Bundle 参数（API 26+）。
 * selection / orderBy 由 [CollectorCursor] 生成，列名只能是代码内常量。
 */
internal fun cursorQueryBundle(
    cursor: CollectorCursor,
    limit: Int,
    timeColumn: String,
    idColumn: String,
): Bundle = Bundle().apply {
    putString(
        ContentResolver.QUERY_ARG_SQL_SELECTION,
        CollectorCursor.selection(timeColumn, idColumn),
    )
    putStringArray(
        ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS,
        CollectorCursor.selectionArgs(cursor),
    )
    putString(
        ContentResolver.QUERY_ARG_SQL_SORT_ORDER,
        CollectorCursor.orderBy(timeColumn, idColumn),
    )
    putInt(ContentResolver.QUERY_ARG_LIMIT, limit)
}

// ---- Cursor 安全读取辅助：列缺失/NULL 一律回退，绝不让单行异常中断整轮采集 ----

internal fun Cursor.longOrNull(column: String): Long? {
    val i = getColumnIndex(column)
    return if (i < 0 || isNull(i)) null else getLong(i)
}

internal fun Cursor.stringOrNull(column: String): String? {
    val i = getColumnIndex(column)
    return if (i < 0 || isNull(i)) null else getString(i)
}

internal fun Cursor.intOrDefault(column: String, defaultValue: Int = 0): Int {
    val i = getColumnIndex(column)
    return if (i < 0 || isNull(i)) defaultValue else getInt(i)
}
