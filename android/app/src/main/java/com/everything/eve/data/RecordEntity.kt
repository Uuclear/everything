package com.everything.eve.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/** 本地加密记录信封；ciphertext 是 base64(nonce||cipher)，与服务端形态一致。 */
@Entity(tableName = "records")
data class RecordEntity(
    @PrimaryKey val id: String,
    val module: String,
    val type: String = "",
    val ciphertext: String,
    val version: Long,
    val createdAt: Long,
    val updatedAt: Long,
    val deleted: Boolean = false,
    /** 本地新建/修改尚未成功推送。 */
    val dirty: Boolean = false,
)
