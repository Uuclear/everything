package com.everything.eve.api

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/** 所有字节字段均以标准 base64 字符串传输（与 Go encoding/json、Web 端一致）。 */

// ---- 注册 / 登录 ----

@JsonClass(generateAdapter = false)
data class RegisterRequest(
    val username: String,
    @Json(name = "auth_salt") val authSalt: String,
    @Json(name = "kek_salt") val kekSalt: String,
    @Json(name = "auth_verifier") val authVerifier: String,
    @Json(name = "wrapped_master_key") val wrappedMasterKey: String,
    @Json(name = "device_name") val deviceName: String,
    // 0002 起强制：X25519 设备公钥 + 恢复密钥四材料。
    @Json(name = "device_public_key") val devicePublicKey: String,
    @Json(name = "recovery_auth_salt") val recoveryAuthSalt: String,
    @Json(name = "recovery_kek_salt") val recoveryKekSalt: String,
    @Json(name = "recovery_verifier") val recoveryVerifier: String,
    @Json(name = "wrapped_master_key_recovery") val wrappedMasterKeyRecovery: String,
)

@JsonClass(generateAdapter = false)
data class LoginRequest(
    val username: String,
    @Json(name = "auth_verifier") val authVerifier: String,
    @Json(name = "device_name") val deviceName: String,
    @Json(name = "device_public_key") val devicePublicKey: String,
)

@JsonClass(generateAdapter = false)
data class TokenPair(
    @Json(name = "access_token") val accessToken: String = "",
    @Json(name = "refresh_token") val refreshToken: String = "",
    @Json(name = "expires_in") val expiresIn: Long = 0,
    @Json(name = "user_id") val userId: String = "",
    @Json(name = "device_id") val deviceId: String = "",
)

/**
 * approved 分支的完整包裹：令牌对 + 解开 MK 所需材料。
 * 服务端 Go 结构体内联了 TokenPair 并额外回传 kek_salt/wrapped_master_key。
 */
@JsonClass(generateAdapter = false)
data class LoginBundle(
    @Json(name = "access_token") val accessToken: String = "",
    @Json(name = "refresh_token") val refreshToken: String = "",
    @Json(name = "expires_in") val expiresIn: Long = 0,
    @Json(name = "user_id") val userId: String = "",
    @Json(name = "device_id") val deviceId: String = "",
    @Json(name = "kek_salt") val kekSalt: String = "",
    @Json(name = "wrapped_master_key") val wrappedMasterKey: String = "",
)

@JsonClass(generateAdapter = false)
data class LoginParams(
    @Json(name = "auth_salt") val authSalt: String = "",
    @Json(name = "kek_salt") val kekSalt: String = "",
    @Json(name = "wrapped_master_key") val wrappedMasterKey: String = "",
    // 恢复向导所需材料（盐可公开；wrapped 只有恢复码派生的 REK 能解开）。
    @Json(name = "recovery_auth_salt") val recoveryAuthSalt: String = "",
    @Json(name = "recovery_kek_salt") val recoveryKekSalt: String = "",
    @Json(name = "wrapped_master_key_recovery") val wrappedMasterKeyRecovery: String = "",
)

/** 配对信息（服务端时间戳均为毫秒级 Unix）。 */
@JsonClass(generateAdapter = false)
data class PairingInfo(
    val id: String = "",
    @Json(name = "device_id") val deviceId: String = "",
    @Json(name = "device_name") val deviceName: String = "",
    @Json(name = "device_public_key") val devicePublicKey: String = "",
    @Json(name = "ephemeral_public_key") val ephemeralPublicKey: String? = null,
    val nonce: String? = null,
    @Json(name = "wrapped_master_key") val wrappedMasterKey: String? = null,
    val state: String = "",
    @Json(name = "created_at") val createdAt: Long = 0,
    @Json(name = "expires_at") val expiresAt: Long = 0,
    @Json(name = "responded_device_id") val respondedDeviceId: String? = null,
    @Json(name = "pairing_code") val pairingCode: String = "",
    val fingerprint: String = "",
)

@JsonClass(generateAdapter = false)
data class PendingLogin(
    @Json(name = "access_token") val accessToken: String,
    @Json(name = "expires_in") val expiresIn: Long = 0,
    @Json(name = "user_id") val userId: String = "",
    @Json(name = "device_id") val deviceId: String = "",
    val pairing: PairingInfo = PairingInfo(),
)

/**
 * 登录 / TOTP 验证统一三态响应：
 * status=approved → bundle；pending → pending；mfa_required → mfaToken。
 */
@JsonClass(generateAdapter = false)
data class LoginResponse(
    val status: String,
    val bundle: LoginBundle? = null,
    val pending: PendingLogin? = null,
    @Json(name = "mfa_token") val mfaToken: String? = null,
    @Json(name = "expires_in") val expiresIn: Long = 0,
) {
    companion object {
        const val STATUS_APPROVED = "approved"
        const val STATUS_PENDING = "pending"
        const val STATUS_MFA_REQUIRED = "mfa_required"
    }
}

@JsonClass(generateAdapter = false)
data class TotpVerifyRequest(
    val code: String,
    @Json(name = "device_public_key") val devicePublicKey: String,
    @Json(name = "device_name") val deviceName: String,
)

// ---- 恢复 ----

@JsonClass(generateAdapter = false)
data class RecoveryStartRequest(
    val username: String,
    @Json(name = "recovery_verifier") val recoveryVerifier: String,
)

@JsonClass(generateAdapter = false)
data class RecoverySession(
    @Json(name = "recovery_token") val recoveryToken: String,
    @Json(name = "expires_in") val expiresIn: Long = 0,
    @Json(name = "user_id") val userId: String = "",
    @Json(name = "recovery_kek_salt") val recoveryKekSalt: String = "",
    @Json(name = "wrapped_master_key_recovery") val wrappedMasterKeyRecovery: String = "",
)

@JsonClass(generateAdapter = false)
data class RecoveryResetRequest(
    // 新主密码四材料
    @Json(name = "auth_salt") val authSalt: String,
    @Json(name = "kek_salt") val kekSalt: String,
    @Json(name = "auth_verifier") val authVerifier: String,
    @Json(name = "wrapped_master_key") val wrappedMasterKey: String,
    // 强制轮换的新恢复码四材料
    @Json(name = "recovery_auth_salt") val recoveryAuthSalt: String,
    @Json(name = "recovery_kek_salt") val recoveryKekSalt: String,
    @Json(name = "recovery_verifier") val recoveryVerifier: String,
    @Json(name = "wrapped_master_key_recovery") val wrappedMasterKeyRecovery: String,
    @Json(name = "device_public_key") val devicePublicKey: String,
    @Json(name = "device_name") val deviceName: String,
)

// ---- 改密 ----

@JsonClass(generateAdapter = false)
data class ChangePasswordRequest(
    @Json(name = "auth_salt") val authSalt: String,
    @Json(name = "kek_salt") val kekSalt: String,
    @Json(name = "auth_verifier") val authVerifier: String,
    @Json(name = "wrapped_master_key") val wrappedMasterKey: String,
    // 可选轮换恢复码（四字段整组出现或整组缺省）
    @Json(name = "recovery_auth_salt") val recoveryAuthSalt: String? = null,
    @Json(name = "recovery_kek_salt") val recoveryKekSalt: String? = null,
    @Json(name = "recovery_verifier") val recoveryVerifier: String? = null,
    @Json(name = "wrapped_master_key_recovery") val wrappedMasterKeyRecovery: String? = null,
)

// ---- 设备与配对 ----

@JsonClass(generateAdapter = false)
data class DeviceInfo(
    val id: String,
    val name: String = "",
    val state: String = "",
    val fingerprint: String = "",
    @Json(name = "last_seen") val lastSeen: Long = 0,
    @Json(name = "created_at") val createdAt: Long = 0,
    val current: Boolean = false,
)

@JsonClass(generateAdapter = false)
data class DeviceListResponse(val devices: List<DeviceInfo> = emptyList())

@JsonClass(generateAdapter = false)
data class PairingListResponse(val pairings: List<PairingInfo> = emptyList())

@JsonClass(generateAdapter = false)
data class ApprovePairingRequest(
    @Json(name = "ephemeral_public_key") val ephemeralPublicKey: String,
    val nonce: String,
    @Json(name = "wrapped_master_key") val wrappedMasterKey: String,
)

/** pairing/status：PairingInfo + approved 瞬间附带的令牌对。 */
@JsonClass(generateAdapter = false)
data class PairingStatusResponse(
    val id: String = "",
    @Json(name = "device_id") val deviceId: String = "",
    @Json(name = "device_name") val deviceName: String = "",
    @Json(name = "device_public_key") val devicePublicKey: String = "",
    @Json(name = "ephemeral_public_key") val ephemeralPublicKey: String? = null,
    val nonce: String? = null,
    @Json(name = "wrapped_master_key") val wrappedMasterKey: String? = null,
    val state: String = "",
    @Json(name = "created_at") val createdAt: Long = 0,
    @Json(name = "expires_at") val expiresAt: Long = 0,
    @Json(name = "pairing_code") val pairingCode: String = "",
    val fingerprint: String = "",
    val tokens: TokenPair? = null,
)

// ---- 记录同步 ----

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
data class BatchResult(
    val applied: Int = 0,
    val skipped: Int = 0,
    // 服务端权威写入时间（毫秒）；推送成功后用它覆盖本地 updatedAt，校准拉取游标（FU-1）。
    @Json(name = "server_time") val serverTime: Long = 0,
)

@JsonClass(generateAdapter = false)
data class SyncResponse(
    val records: List<RemoteRecord> = emptyList(),
    @Json(name = "has_more") val hasMore: Boolean = false,
)

// ---- 位置轨迹（阶段 4a Task 7）----
// 与服务端 internal/api/locations_handler.go 的 locationBlockInput 逐字段对齐：
// 仅 id/start_ts/end_ts/point_count/cipher(base64) 五字段；
// user_id/device_id 由服务端从 token claims 注入，客户端一律不传（不信任自声明）。

/** 上行轨迹密文块 DTO：cipher 为 XChaCha20-Poly1305 信封密文的 base64（ByteArray 在上行组装处转码）。 */
@JsonClass(generateAdapter = false)
data class LocationBlockDto(
    val id: String,
    @Json(name = "start_ts") val startTs: Long,
    @Json(name = "end_ts") val endTs: Long,
    @Json(name = "point_count") val pointCount: Int,
    val cipher: String,
)

/** POST /api/v1/locations/batch 请求体（服务端限额：1..50 块/批，单块密文解码后 ≤256KB）。 */
@JsonClass(generateAdapter = false)
data class LocationBatchRequest(val blocks: List<LocationBlockDto>)

/** 上行响应：applied 新入库块数，skipped 同 id 幂等跳过块数。 */
@JsonClass(generateAdapter = false)
data class LocationBatchResult(
    val applied: Int = 0,
    val skipped: Int = 0,
)
