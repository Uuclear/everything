// ============================================================================
// 物品保修纯函数 —— JUnit（stage5-items / T3 / TR-3.2）
// ============================================================================
//
// 从 items/__fixtures__/cases.json 加载 warrantyCases，逐用例断言与 expected 一致。
// ============================================================================

package com.everything.eve.items

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.util.TimeZone

class WarrantyTest {

    companion object {
        @JvmStatic
        @BeforeClass
        fun lockTimezone() {
            System.setProperty("user.timezone", "Asia/Shanghai")
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"))
        }
    }

    private fun assertCase(name: String) {
        val c = ItemsFixture.caseByName(ItemsFixture.warrantyCases, name)
        val got = ItemsFixture.runWarrantyCase(c)
        val exp = ItemsFixture.expectedWarranty(c)
        assertEquals("case=$name", exp, got)
    }

    @Test
    fun fixture_sha256_matchesWebMirror() {
        assertEquals(
            "fixture SHA-256 应与 web/src/items/__fixtures__/cases.json 一致（TR-3.3）",
            ItemsFixture.FIXTURE_SHA256,
            ItemsFixture.sha256Hex(ItemsFixture.bytes),
        )
    }

    @Test
    fun warrantyCases_atLeast12() {
        assertTrue(
            "warrantyCases 应不少于 12 条, 实际=${ItemsFixture.warrantyCases.length()}",
            ItemsFixture.warrantyCases.length() >= 12,
        )
    }

    @Test fun warrantyUntilTs_365d_from_purchase() = assertCase("warrantyUntilTs_365d_from_purchase")

    @Test fun warrantyUntilTs_zero_duration_returns_purchase() =
        assertCase("warrantyUntilTs_zero_duration_returns_purchase")

    @Test fun warrantyUntilTs_dst_accept_ms_arithmetic() =
        assertCase("warrantyUntilTs_dst_accept_ms_arithmetic")

    @Test fun nextItemTrigger_zero_warranty_returns_null() =
        assertCase("nextItemTrigger_zero_warranty_returns_null")

    @Test fun nextItemTrigger_picks_30d_before_expiry() =
        assertCase("nextItemTrigger_picks_30d_before_expiry")

    @Test fun nextItemTrigger_picks_7d_before_expiry() =
        assertCase("nextItemTrigger_picks_7d_before_expiry")

    @Test fun nextItemTrigger_picks_1d_before_expiry() =
        assertCase("nextItemTrigger_picks_1d_before_expiry")

    @Test fun nextItemTrigger_expired_returns_null() =
        assertCase("nextItemTrigger_expired_returns_null")

    @Test fun nextItemTrigger_after_last_reminder_returns_null() =
        assertCase("nextItemTrigger_after_last_reminder_returns_null")

    @Test fun normalizeTags_dedupe_case_insensitive() =
        assertCase("normalizeTags_dedupe_case_insensitive")

    @Test fun normalizeTags_truncate_unicode_cap_eight() =
        assertCase("normalizeTags_truncate_unicode_cap_eight")

    @Test fun isValidReceiptUrl_https_ok() = assertCase("isValidReceiptUrl_https_ok")

    @Test fun isValidReceiptUrl_http_rejected() = assertCase("isValidReceiptUrl_http_rejected")

    @Test
    fun isValidReceiptUrl_empty_rejected() {
        assertFalse(isValidReceiptUrl(""))
    }

    @Test
    fun isValidReceiptUrl_malformed_https_rejected() {
        assertFalse(isValidReceiptUrl("https://"))
    }
}
