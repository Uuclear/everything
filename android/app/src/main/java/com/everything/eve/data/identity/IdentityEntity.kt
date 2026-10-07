// 证件模块本地明文缓存（stage 2b / module-schemas §3）
package com.everything.eve.data.identity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 证件本地表：UI 列表与编辑用；密文以 records module=identity 为准。
 */
@Entity(
    tableName = "identity",
    indices = [
        Index(value = ["updated_at"]),
        Index(value = ["dirty"]),
    ],
)
data class IdentityEntity(
    @PrimaryKey val id: String,
    val title: String,
    /** 与 records.type 一致：id_card / passport / driver_license / generic */
    val kind: String,
    val name: String?,
    val number: String?,
    val issuer: String?,
    @ColumnInfo(name = "issued_on") val issuedOn: String?,
    @ColumnInfo(name = "expires_on") val expiresOn: String?,
    val notes: String?,
    @ColumnInfo(name = "front_attachment_id") val frontAttachmentId: String?,
    @ColumnInfo(name = "back_attachment_id") val backAttachmentId: String?,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    val dirty: Boolean,
    val deleted: Boolean,
)
