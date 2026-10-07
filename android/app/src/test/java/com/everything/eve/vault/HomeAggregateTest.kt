package com.everything.eve.vault

import com.everything.eve.data.identity.IdentityEntity
import com.everything.eve.data.item.ItemEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class HomeAggregateTest {

    @Test
    fun globalSearchLocal_doesNotMatchIdNumber() {
        val id = IdentityEntity(
            id = "1",
            title = "护照",
            kind = "passport",
            name = "测试",
            number = "SECRET99",
            issuer = null,
            issuedOn = null,
            expiresOn = null,
            notes = null,
            frontAttachmentId = null,
            backAttachmentId = null,
            createdAt = 0,
            updatedAt = 0,
            dirty = false,
            deleted = false,
        )
        assertTrue(globalSearchLocal("测试", listOf(id), emptyList()).isNotEmpty())
        assertTrue(globalSearchLocal("secret", listOf(id), emptyList()).isEmpty())
    }

    @Test
    fun identitiesExpiringWithinDays_includesToday() {
        val today = LocalDate.of(2026, 6, 15)
        val row = IdentityEntity(
            id = "a",
            title = "A",
            kind = "generic",
            name = null,
            number = null,
            issuer = null,
            issuedOn = null,
            expiresOn = "2026-06-15",
            notes = null,
            frontAttachmentId = null,
            backAttachmentId = null,
            createdAt = 0,
            updatedAt = 0,
            dirty = false,
            deleted = false,
        )
        val out = identitiesExpiringWithinDays(listOf(row), 7, today)
        assertEquals(1, out.size)
        assertEquals(0, out[0].days)
    }

    @Test
    fun globalSearchLocal_matchesItemName() {
        val item = ItemEntity(
            id = "i1",
            name = "显示器",
            category = "electronics",
            tags_json = "[]",
            brand = null,
            model = null,
            serial_no = null,
            purchase_date = 0,
            purchase_price_cents = 0,
            currency = "CNY",
            warranty_duration_days = 0,
            warranty_until_ts = 0,
            receipt_url = null,
            note = null,
            location_text = null,
            created_ts = 0,
            updated_ts = 0,
            dirty = false,
        )
        val hits = globalSearchLocal("显示", emptyList(), listOf(item))
        assertEquals("item", hits[0].module)
    }
}
