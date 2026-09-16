package com.everything.eve.collector.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Task 1 TR-1.1：记录 id 确定性派生（spec FR-2）。
 */
class CollectorIdsTest {

    private val deviceA = "dev-aaaa"
    private val deviceB = "dev-bbbb"

    @Test
    fun recordId_isStableAndShapeCorrect() {
        val id = CollectorIds.recordId(deviceA, CollectorKind.SMS, 42L)
        assertEquals("dev-aaaa:sms:42", id)
        // 同参数重复派生必须逐字符一致（幂等 upsert 的前提）。
        assertEquals(id, CollectorIds.recordId(deviceA, CollectorKind.SMS, 42L))
    }

    @Test
    fun sameSystemIdAcrossKindsAndDevices_doesNotCollide() {
        // 同设备不同类：命名空间隔离。
        val contact = CollectorIds.recordId(deviceA, CollectorKind.CONTACT, 7L)
        val call = CollectorIds.recordId(deviceA, CollectorKind.CALLLOG, 7L)
        assertEquals("dev-aaaa:contact:7", contact)
        assertEquals("dev-aaaa:calllog:7", call)
        // 不同设备同一系统 _id（两台手机都有 _id=7）：主键不冲突。
        val otherDevice = CollectorIds.recordId(deviceB, CollectorKind.CONTACT, 7L)
        assertEquals("dev-bbbb:contact:7", otherDevice)
    }

    @Test
    fun emptyDeviceId_isRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            CollectorIds.recordId("", CollectorKind.SMS, 1L)
        }
    }

    @Test
    fun parse_roundTripsAndRejectsForeignIds() {
        val id = CollectorIds.recordId(deviceA, CollectorKind.CALLLOG, 99L)
        assertEquals(CollectorKind.CALLLOG to 99L, CollectorIds.parse(id, deviceA))
        // 其他设备的 id、损坏格式、未知 kind 均反解为 null（不影响主流程）。
        assertNull(CollectorIds.parse(id, deviceB))
        assertNull(CollectorIds.parse("dev-aaaa:sms:abc", deviceA))
        assertNull(CollectorIds.parse("dev-aaaa:photos:1", deviceA))
    }
}
