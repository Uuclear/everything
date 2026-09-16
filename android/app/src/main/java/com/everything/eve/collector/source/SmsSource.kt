package com.everything.eve.collector.source

import android.content.ContentResolver
import android.provider.Telephony
import com.everything.eve.collector.core.CollectorCursor
import com.everything.eve.collector.core.CollectorKind
import com.everything.eve.collector.core.CollectorSource
import com.everything.eve.collector.core.SmsData
import com.everything.eve.collector.core.TypeMaps

/**
 * 短信数据源：Telephony.Sms.CONTENT_URI 全文件夹（收件箱/发件箱/草稿等），
 * 时间列 date（毫秒），系统行 _id 参与游标与记录 id。
 * 仅读取映射，不做任何日志输出（零知识：正文/号码不落日志）。
 */
class SmsSource : SystemSource<SmsData> {

    override val kind: CollectorKind = CollectorKind.SMS

    override fun query(
        resolver: ContentResolver,
        cursor: CollectorCursor,
        limit: Int,
    ): List<RawEntry<SmsData>> {
        val out = ArrayList<RawEntry<SmsData>>(limit)
        resolver.query(
            Telephony.Sms.CONTENT_URI,
            PROJECTION,
            cursorQueryBundle(cursor, limit, Telephony.Sms.DATE, Telephony.Sms._ID),
            null,
        )?.use { c ->
            while (c.moveToNext()) {
                val systemId = c.longOrNull(Telephony.Sms._ID) ?: continue
                val date = c.longOrNull(Telephony.Sms.DATE) ?: continue
                out += RawEntry(
                    systemId = systemId,
                    timestamp = date,
                    data = SmsData(
                        address = c.stringOrNull(Telephony.Sms.ADDRESS),
                        // 极端源行 body 可能为 NULL，DTO 必填则降级为空串
                        body = c.stringOrNull(Telephony.Sms.BODY).orEmpty(),
                        date = date,
                        type = TypeMaps.smsType(c.intOrDefault(Telephony.Sms.TYPE)),
                        read = c.intOrDefault(Telephony.Sms.READ) == 1,
                        threadId = c.longOrNull(Telephony.Sms.THREAD_ID),
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
            Telephony.Sms._ID,
            Telephony.Sms.ADDRESS,
            Telephony.Sms.BODY,
            Telephony.Sms.DATE,
            Telephony.Sms.TYPE,
            Telephony.Sms.READ,
            Telephony.Sms.THREAD_ID,
        )
    }
}
