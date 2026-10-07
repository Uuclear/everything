// ============================================================================
// 二维码 payload 纯函数 —— JUnit（stage5-items / T3 / TR-3.2）
// ============================================================================

package com.everything.eve.items

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QrPayloadTest {

    private fun assertQrCase(name: String) {
        val c = ItemsFixture.caseByName(ItemsFixture.qrCases, name)
        val input = c.getJSONObject("input")
        val itemId = input.getString("itemId")
        val got = qrPayloadForItem(itemId)
        assertEquals("case=$name", c.getString("expected"), got)
        assertFalse("case=$name 不应包含 name/serial/密文", got.contains(Regex("name|serial|密文")))
    }

    @Test
    fun qrCases_atLeast6() {
        assertTrue(
            "qrCases 应不少于 6 条, 实际=${ItemsFixture.qrCases.length()}",
            ItemsFixture.qrCases.length() >= 6,
        )
    }

    @Test fun qrPayload_passthrough_uuid_v4() = assertQrCase("qrPayload_passthrough_uuid_v4")

    @Test fun qrPayload_preserves_casing() = assertQrCase("qrPayload_preserves_casing")

    @Test fun qrPayload_no_json_wrapper() = assertQrCase("qrPayload_no_json_wrapper")

    @Test fun qrPayload_empty_string_allowed() = assertQrCase("qrPayload_empty_string_allowed")

    @Test fun qrPayload_unicode_id_opaque() = assertQrCase("qrPayload_unicode_id_opaque")

    @Test fun qrPayload_no_name_or_serial_fields() = assertQrCase("qrPayload_no_name_or_serial_fields")

    @Test
    fun qrPayload_sameStringIdentity() {
        val id = "f47ac10b-58cc-4372-a567-0e02b2c3d479"
        assertEquals(id, qrPayloadForItem(id))
    }
}
