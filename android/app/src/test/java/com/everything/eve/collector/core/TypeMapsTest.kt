package com.everything.eve.collector.core

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Task 1 TR-1.1：系统类型码全枚举映射与未知兜底（spec FR-1）。
 */
class TypeMapsTest {

    @Test
    fun smsType_mapsAllKnownCodes() {
        assertEquals("inbox", TypeMaps.smsType(1))
        assertEquals("sent", TypeMaps.smsType(2))
        assertEquals("draft", TypeMaps.smsType(3))
        assertEquals("outbox", TypeMaps.smsType(4))
        assertEquals("failed", TypeMaps.smsType(5))
        assertEquals("queued", TypeMaps.smsType(6))
    }

    @Test
    fun callType_mapsAllKnownCodes() {
        assertEquals("incoming", TypeMaps.callType(1))
        assertEquals("outgoing", TypeMaps.callType(2))
        assertEquals("missed", TypeMaps.callType(3))
        assertEquals("voicemail", TypeMaps.callType(4))
        assertEquals("rejected", TypeMaps.callType(5))
        assertEquals("blocked", TypeMaps.callType(6))
    }

    @Test
    fun unknownCodes_fallbackToUnknown() {
        // 未来系统新增类型码时不得中断整轮采集。
        assertEquals("unknown", TypeMaps.smsType(999))
        assertEquals("unknown", TypeMaps.callType(-1))
        assertEquals("unknown", TypeMaps.contactType(88))
    }

    @Test
    fun contactType_groupsEquivalentCodesAndCustom() {
        assertEquals("home", TypeMaps.contactType(1))
        assertEquals("mobile", TypeMaps.contactType(2))
        assertEquals("work", TypeMaps.contactType(3))
        // 工作传真/家庭传真/其他传真归一为 fax；公司主机归 work；主机归 main。
        assertEquals("fax", TypeMaps.contactType(4))
        assertEquals("fax", TypeMaps.contactType(5))
        assertEquals("fax", TypeMaps.contactType(13))
        assertEquals("pager", TypeMaps.contactType(6))
        assertEquals("other", TypeMaps.contactType(7))
        assertEquals("work", TypeMaps.contactType(10))
        assertEquals("main", TypeMaps.contactType(12))
        assertEquals("mobile", TypeMaps.contactType(17))
        assertEquals("pager", TypeMaps.contactType(18))
        // TYPE_CUSTOM(0) 归一 custom（标签原文由 DTO label 承载）。
        assertEquals("custom", TypeMaps.contactType(0, "公司前台"))
    }
}
