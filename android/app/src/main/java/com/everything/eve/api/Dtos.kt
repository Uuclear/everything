package com.everything.eve.api

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/** 所有字节字段均以标准 base64 字符串传输（与 Go encoding/json、Web 端一致）。 */

@JsonClass(generateAdapter = false)
data class RegisterRequest(
    val username: String,
    @Json(name = "auth_salt") val authSalt: String,
    @Json(name = "kek_salt") val kekSalt: String,
    @Json(name = "auth_verifier") val authVerifier: String,
    @Json(name = "wrapped_master_key") val wrappedMasterKey: String,
    @Json(name = "device_name") val deviceName: String,
)

@JsonClass(generateAdapter = false)
data class LoginRequest(
    val username: String,
    @Json(name = "auth_verifier") val authVerifier: String,
    @Json(name = "device_name") val deviceName: String,
)

@JsonClass(generateAdapter = false)
data class TokenPair(
    @Json(name = "access_token") val accessToken: String,
    @Json(name = "refresh_token") val refreshToken: String,
    @Json(name = "expires_in") val expiresIn: Long = 0,
    @Json(name = "user_id") val userId: String = "",
    @Json(name = "device_id") val deviceId: String = "",
)

@JsonClass(generateAdapter = false)
data class LoginBundle(
    @Json(name = "access_token") val accessToken: String,
    @Json(name = "refresh_token") val refreshToken: String,
    @Json(name = "expires_in") val expiresIn: Long = 0,
    @Json(name = "user_id") val userId: String = "",
    @Json(name = "device_id") val deviceId: String = "",
    val username: String = "",
)

@JsonClass(generateAdapter = false)
data class LoginParams(
    @Json(name = "auth_salt") val authSalt: String,
    @Json(name = "kek_salt") val kekSalt: String,
    @Json(name = "wrapped_master_key") val wrappedMasterKey: String,
)

@JsonClass(generateAdapter = false)
data class RemoteRecord(
    val id: String,
    val module: String,
    val type: String = "",
    val ciphertext: String,
    val version: Long,
    @Json(name = "device_id") val deviceId: String = "",
    @Json(name = "created_at") val createdAt: Long,
    @Json(name = "updated_at") val updatedAt: Long,
    val deleted: Boolean = false,
)

@JsonClass(generateAdapter = false)
data class BatchRequest(val records: List<RemoteRecord>)

@JsonClass(generateAdapter = false)
data class BatchResult(val applied: Int = 0, val skipped: Int = 0)

@JsonClass(generateAdapter = false)
data class SyncResponse(
    val records: List<RemoteRecord> = emptyList(),
    @Json(name = "has_more") val hasMore: Boolean = false,
)
