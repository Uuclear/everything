package com.everything.eve.collector.source

import android.content.ContentResolver
import android.provider.CallLog
import com.everything.eve.collector.core.CallData
import com.everything.eve.collector.core.CollectorCursor
import com.everything.eve.collector.core.CollectorKind
import com.everything.eve.collector.core.CollectorSource
import com.everything.eve.collector.core.TypeMaps

/**
 * 通话记录数据源：CallLog.Calls.CONTENT_URI，
 * 时间列 date（毫秒），duration 单位秒，type 归一化见 [TypeMaps.callType]。
 * 仅读取映射，不做任何日志输出（零知识：号码/姓名不落日志）。
 */
class CallLogSource : SystemSource<CallData> {

    override val kind: CollectorKind = CollectorKind.CALLLOG

    override fun query(
        resolver: ContentResolver,
        cursor: CollectorCursor,
        limit: Int,
    ): List<RawEntry<CallData>> {
        val out = ArrayList<RawEntry<CallData>>(limit)
        resolver.query(
            CallLog.Calls.CONTENT_URI,
            PROJECTION,
            cursorQueryBundle(cursor, limit, CallLog.Calls.DATE, CallLog.Calls._ID),
            null,
        )?.use { c ->
            while (c.moveToNext()) {
                val systemId = c.longOrNull(CallLog.Calls._ID) ?: continue
                val date = c.longOrNull(CallLog.Calls.DATE) ?: continue
                out += RawEntry(
                    systemId = systemId,
                    timestamp = date,
                    data = CallData(
                        number = c.stringOrNull(CallLog.Calls.NUMBER),
                        name = c.stringOrNull(CallLog.Calls.CACHED_NAME),
                        date = date,
                        duration = c.longOrNull(CallLog.Calls.DURATION) ?: 0L,
                        type = TypeMaps.callType(c.intOrDefault(CallLog.Calls.TYPE)),
                        source = CollectorSource(
                            systemId = systemId,
                            lastUpdated = date,
                        ),
                    ),
                )
            }
        }
        return out
    }

    private companion object {
        val PROJECTION = arrayOf(
            CallLog.Calls._ID,
            CallLog.Calls.NUMBER,
            CallLog.Calls.CACHED_NAME,
            CallLog.Calls.DATE,
            CallLog.Calls.DURATION,
            CallLog.Calls.TYPE,
        )
    }
}
