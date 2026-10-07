package com.everything.eve.data.identity

import org.json.JSONObject

/** 证件明文形态（module-schemas §3 + 2b 影像字段）。 */
data class Identity(
    val id: String,
    val title: String,
    val kind: String,
    val name: String? = null,
    val number: String? = null,
    val issuer: String? = null,
    val issuedOn: String? = null,
    val expiresOn: String? = null,
    val notes: String? = null,
    val frontAttachmentId: String? = null,
    val backAttachmentId: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
) {
    fun toJson(): String {
        val o = JSONObject()
            .put("title", title)
            .put("kind", kind)
        if (name != null) o.put("name", name)
        if (number != null) o.put("number", number)
        if (issuer != null) o.put("issuer", issuer)
        if (issuedOn != null) o.put("issued_on", issuedOn)
        if (expiresOn != null) o.put("expires_on", expiresOn)
        if (notes != null) o.put("notes", notes)
        if (frontAttachmentId != null) o.put("front_attachment_id", frontAttachmentId)
        if (backAttachmentId != null) o.put("back_attachment_id", backAttachmentId)
        return o.toString()
    }

    fun toEntity(dirty: Boolean, deleted: Boolean = false): IdentityEntity =
        IdentityEntity(
            id = id,
            title = title,
            kind = kind,
            name = name,
            number = number,
            issuer = issuer,
            issuedOn = issuedOn,
            expiresOn = expiresOn,
            notes = notes,
            frontAttachmentId = frontAttachmentId,
            backAttachmentId = backAttachmentId,
            createdAt = createdAt,
            updatedAt = updatedAt,
            dirty = dirty,
            deleted = deleted,
        )

    companion object {
        fun fromJson(id: String, json: String, createdAt: Long, updatedAt: Long): Identity {
            val o = JSONObject(json)
            return Identity(
                id = id,
                title = o.optString("title", ""),
                kind = o.optString("kind", "generic"),
                name = o.optString("name").takeIf { it.isNotEmpty() },
                number = o.optString("number").takeIf { it.isNotEmpty() },
                issuer = o.optString("issuer").takeIf { it.isNotEmpty() },
                issuedOn = o.optString("issued_on").takeIf { it.isNotEmpty() },
                expiresOn = o.optString("expires_on").takeIf { it.isNotEmpty() },
                notes = o.optString("notes").takeIf { it.isNotEmpty() },
                frontAttachmentId = o.optString("front_attachment_id").takeIf { it.isNotEmpty() },
                backAttachmentId = o.optString("back_attachment_id").takeIf { it.isNotEmpty() },
                createdAt = createdAt,
                updatedAt = updatedAt,
            )
        }
    }
}
