package com.everything.eve.vault

import com.everything.eve.data.finance.entity.FinanceCardEntity
import com.everything.eve.data.finance.fromJsonObj
import com.everything.eve.data.finance.toJson
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 跨端附件 / 银行卡字段契约单测。 */
class AttachmentMetadataTest {

    @Test
    fun metadataJson_uses_snake_case_parent_fields() {
        val json = attachmentMetadataJson(
            attachmentId = "att-1",
            parentRefId = "parent-1",
            parentModule = "identity",
            mime = "image/jpeg",
            size = 100L,
            sha256Hex = "a".repeat(64),
            name = "front.jpg",
            createdAtMs = 1_000L,
        )
        val o = JSONObject(json)
        assertEquals("parent-1", o.getString("parent_ref_id"))
        assertEquals("identity", o.getString("parent_module"))
        assertEquals("parent-1", o.getString("recordId"))
    }

    @Test
    fun parseParentRefId_prefers_snake_then_legacy() {
        val fromWeb = mapOf(
            "parent_ref_id" to "p-web",
            "recordId" to "p-record",
        )
        assertEquals("p-web", parseAttachmentParentRefId(fromWeb))

        val fromW3 = mapOf("parentRefId" to "p-w3", "recordId" to "p-record")
        assertEquals("p-w3", parseAttachmentParentRefId(fromW3))

        val financeOnly = mapOf("recordId" to "p-fin")
        assertEquals("p-fin", parseAttachmentParentRefId(financeOnly))
    }

    @Test
    fun parseParentModule_prefers_snake_then_record_fallback() {
        val o = mapOf("parent_module" to "finance", "parentModule" to "identity")
        assertEquals("finance", parseAttachmentParentModule(o, "pass"))

        assertEquals("identity", parseAttachmentParentModule(emptyMap(), "identity"))
    }

    @Test
    fun financeCard_fromJson_prefers_card_face_attachment_id() {
        val canonical = mapOf(
            "id" to "c1",
            "name" to "n",
            "kind" to "credit",
            "issuer" to "i",
            "last4" to "4242",
            "currency" to "CNY",
            "archived" to false,
            "created_at" to 1L,
            "updated_at" to 2L,
            "dirty" to false,
            "deleted" to false,
            "card_face_attachment_id" to "face-new",
            "card_attachment_id" to "face-old",
        )
        assertEquals("face-new", FinanceCardEntity.Companion.fromJsonObj(canonical).cardFaceAttachmentId)
    }

    @Test
    fun financeCard_fromJson_falls_back_to_legacy_card_attachment_id() {
        val legacy = mapOf(
            "id" to "c1",
            "name" to "n",
            "kind" to "credit",
            "issuer" to "i",
            "last4" to "4242",
            "currency" to "CNY",
            "archived" to false,
            "created_at" to 1L,
            "updated_at" to 2L,
            "dirty" to false,
            "deleted" to false,
            "card_attachment_id" to "face-legacy",
        )
        assertEquals("face-legacy", FinanceCardEntity.Companion.fromJsonObj(legacy).cardFaceAttachmentId)
    }

    @Test
    fun financeCard_toJson_emits_card_face_attachment_id_only() {
        val card = FinanceCardEntity(
            id = "c1",
            name = "n",
            kind = "credit",
            issuer = "i",
            last4 = "4242",
            currency = "CNY",
            creditLimit = null,
            usedLimit = null,
            billingDay = null,
            dueDay = null,
            brand = null,
            expiryMonth = null,
            expiryYear = null,
            holder = null,
            note = null,
            cardFaceAttachmentId = "att-face",
            icon = null,
            color = null,
            archived = false,
            createdAt = 1L,
            updatedAt = 2L,
            dirty = false,
            deleted = false,
        )
        assertEquals("att-face", card.toJson()["card_face_attachment_id"])
        assertNull(card.toJson()["card_attachment_id"])
    }
}
