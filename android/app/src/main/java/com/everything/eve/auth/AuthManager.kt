package com.everything.eve.auth

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.everything.eve.ServiceLocator
import com.everything.eve.api.ApprovePairingRequest
import com.everything.eve.api.ChangePasswordRequest
import com.everything.eve.api.DeviceInfo
import com.everything.eve.api.LoginBundle
import com.everything.eve.api.LoginRequest
import com.everything.eve.api.LoginResponse
import com.everything.eve.api.LoginParams
import com.everything.eve.api.PairingInfo
import com.everything.eve.api.PairingStatusResponse
import com.everything.eve.api.RecoveryResetRequest
import com.everything.eve.api.RecoveryStartRequest
import com.everything.eve.api.RegisterRequest
import com.everything.eve.api.TotpVerifyRequest
import com.everything.eve.crypto.Crockford
import com.everything.eve.crypto.CryptoEnvelope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 登录 / TOTP 验证后的三态分流（UI 据此切换解锁、配对等待、两步验证屏）。 */
sealed class LoginOutcome {
    /** 已批准：MK 已解开入库，直接进入资料库。 */
    object Approved : LoginOutcome()
    /** 新设备待审批：配对码/指纹供等待屏展示与轮询。 */
    data class Pending(val pairing: PairingInfo, val expiresIn: Long) : LoginOutcome()
    /** 要求 TOTP：mfa 短期会话已存于管理器，等待 finishMfa。 */
    data class MfaRequired(val expiresIn: Long) : LoginOutcome()
}

/** 新生成的恢复码材料：展示串只返回一次，管理器不落盘不保留明文。 */
data class GeneratedRecovery(val code: String, val formatted: String)

/** recoveryStart 的产物：短期会话令牌 + 已离线解开的 MK。 */
data class RecoveryStartResult(val recoveryToken: String, val mk: ByteArray) {
    override fun equals(other: Any?): Boolean = this === other
    override fun hashCode(): Int = recoveryToken.hashCode()
}

/**
 * 负责注册/登录、令牌保管（EncryptedSharedPreferences）、设备 X25519 身份与内存态主密钥。
 * 主密钥与设备私钥推导种子永不离开本设备：seed 加密落盘（重装/清除数据即丢失身份），
 * MK 只在内存，应用被杀后必须重新输入主密码（或经审批端端到端下发）解锁。
 */
class AuthManager private constructor(private val prefs: SharedPreferences) {

    companion object {
        fun create(context: Context): AuthManager {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            val prefs = EncryptedSharedPreferences.create(
                context,
                "eve-secrets",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
            return AuthManager(prefs)
        }

        // 服务器地址（非敏感，单独存于明文配置；用户名随加密偏好保存）
        const val PREF_SERVER = "eve.server_url"
        const val DEFAULT_SERVER = "http://10.0.2.2:8787/" // Android 模拟器访问宿主机

        const val KEY_TOKEN = "access_token"
        const val KEY_REFRESH = "refresh_token"
        const val KEY_USER_ID = "user_id"
        const val KEY_DEVICE_ID = "device_id"
        const val KEY_USERNAME = "username"
        /** X25519 设备 seed（32B，base64）：恒定派生设备公私钥，重装即换新身份。 */
        const val KEY_DEVICE_SEED = "device_seed"
    }

    private val _isUnlocked = MutableStateFlow(false)
    val isUnlocked: StateFlow<Boolean> = _isUnlocked.asStateFlow()

    /** 仅内存，绝不持久化。 */
    @Volatile
    var masterKey: ByteArray? = null
        private set

    // 设备身份：seed 加密持久化，密钥对首次使用时派生并缓存于内存。
    private var deviceSeed: ByteArray? = null
    private var deviceKeyPair: CryptoEnvelope.DeviceKeyPair? = null

    // 登录流程中的短期会话（仅内存，绝不落盘）。
    private var mfaToken: String = ""
    private var mfaPassword: String = ""
    private var pendingAccessToken: String = ""
    /** pending 期间正式令牌未落盘，用户名暂存内存，批准换发会话时写回。 */
    private var pendingUsername: String = ""
    var pendingPairing: PairingInfo? = null
        private set

    val isLoggedIn: Boolean get() = prefs.getString(KEY_TOKEN, null) != null
    val username: String get() = prefs.getString(KEY_USERNAME, "") ?: ""
    val deviceId: String get() = prefs.getString(KEY_DEVICE_ID, "") ?: ""

    /** 配对等待屏读取：配对码/指纹/TTL（毫秒时间戳）。 */
    val currentPairing: PairingInfo? get() = pendingPairing

    var serverUrl: String
        get() = prefs.getString(PREF_SERVER, DEFAULT_SERVER) ?: DEFAULT_SERVER
        set(value) = prefs.edit().putString(PREF_SERVER, value).apply()

    private val api get() = ServiceLocator.api

    // ---- 设备身份 ----

    /** 取设备 seed（不存在则生成并加密保存），返回据此派生的 X25519 密钥对。 */
    @Synchronized
    private fun device(): CryptoEnvelope.DeviceKeyPair {
        deviceKeyPair?.let { return it }
        val seed = deviceSeed
            ?: prefs.getString(KEY_DEVICE_SEED, null)?.let(CryptoEnvelope::unb64)
                ?.takeIf { it.size == 32 }
            ?: CryptoEnvelope.randomDeviceSeed().also { fresh ->
                deviceSeed = fresh
                prefs.edit().putString(KEY_DEVICE_SEED, CryptoEnvelope.b64(fresh)).apply()
            }
        deviceSeed = seed
        return CryptoEnvelope.deviceKeypairFromSeed(seed).also { deviceKeyPair = it }
    }

    // ---- 密码/恢复材料组装（与 Web stores/auth.ts 完全同构）----

    /** 用主密码生成 auth 验证器 + KEK 包裹 MK 四元组（字段名为线上 JSON）。 */
    private fun passwordFields(password: String, mk: ByteArray): PasswordFields {
        val authSalt = CryptoEnvelope.randomSalt()
        val kekSalt = CryptoEnvelope.randomSalt()
        val verifier = CryptoEnvelope.deriveKey(password, authSalt)
        val kek = CryptoEnvelope.deriveKey(password, kekSalt)
        return PasswordFields(
            authSalt = CryptoEnvelope.b64(authSalt),
            kekSalt = CryptoEnvelope.b64(kekSalt),
            authVerifier = CryptoEnvelope.b64(verifier),
            wrappedMasterKey = CryptoEnvelope.b64(CryptoEnvelope.wrapMasterKey(kek, mk)),
        )
    }

    private data class PasswordFields(
        val authSalt: String,
        val kekSalt: String,
        val authVerifier: String,
        val wrappedMasterKey: String,
    )

    /** 随机恢复码（20B Crockford）+ 恢复验证器 + REK 包裹 MK 四元组。 */
    private fun generateRecovery(mk: ByteArray): RecoveryBundle {
        val raw = CryptoEnvelope.randomRecoveryKey()
        val code = Crockford.encode(raw)
        return RecoveryBundle(code, recoveryFields(code, mk))
    }

    private data class RecoveryBundle(
        val code: String,
        val fields: RecoveryFields,
    )

    private data class RecoveryFields(
        val recoveryAuthSalt: String,
        val recoveryKekSalt: String,
        val recoveryVerifier: String,
        val wrappedMasterKeyRecovery: String,
    )

    /** 由用户输入的恢复码（任意大小写/连字符，Crockford 归一化）构造恢复四元组。 */
    private fun recoveryFields(inputCode: String, mk: ByteArray): RecoveryFields {
        val code = Crockford.normalize(inputCode)
        val authSalt = CryptoEnvelope.randomSalt()
        val kekSalt = CryptoEnvelope.randomSalt()
        val verifier = CryptoEnvelope.deriveKey(code, authSalt)
        val rek = CryptoEnvelope.deriveKey(code, kekSalt)
        return RecoveryFields(
            recoveryAuthSalt = CryptoEnvelope.b64(authSalt),
            recoveryKekSalt = CryptoEnvelope.b64(kekSalt),
            recoveryVerifier = CryptoEnvelope.b64(verifier),
            wrappedMasterKeyRecovery = CryptoEnvelope.b64(CryptoEnvelope.wrapForRecovery(rek, mk)),
        )
    }

    // ---- 注册 / 登录 ----

    /** 注册：首设备自动批准；恢复码仅本次返回，UI 必须强制离线备份。 */
    suspend fun register(username: String, password: String, deviceName: String): GeneratedRecovery {
        Crockford.selfTest() // 首次密码学操作前跑固定向量自测（与 Web selftest 对齐）
        val mk = CryptoEnvelope.newMasterKey()
        val pw = passwordFields(password, mk)
        val rec = generateRecovery(mk)
        val keyPair = device()
        val pair = api.register(
            RegisterRequest(
                username = username,
                authSalt = pw.authSalt,
                kekSalt = pw.kekSalt,
                authVerifier = pw.authVerifier,
                wrappedMasterKey = pw.wrappedMasterKey,
                deviceName = deviceName,
                devicePublicKey = CryptoEnvelope.b64(keyPair.publicKey),
                recoveryAuthSalt = rec.fields.recoveryAuthSalt,
                recoveryKekSalt = rec.fields.recoveryKekSalt,
                recoveryVerifier = rec.fields.recoveryVerifier,
                wrappedMasterKeyRecovery = rec.fields.wrappedMasterKeyRecovery,
            ),
        )
        saveSession(pair.accessToken, pair.refreshToken, pair.userId, pair.deviceId, username)
        masterKey = mk
        _isUnlocked.value = true
        return GeneratedRecovery(rec.code, Crockford.format(rec.code))
    }

    /** 密码登录，三态分流。approved 时 MK 已在本地解开。 */
    suspend fun login(username: String, password: String, deviceName: String): LoginOutcome {
        // 清掉上一轮的短期会话残留。
        pendingAccessToken = ""
        pendingPairing = null
        pendingUsername = ""
        mfaToken = ""
        mfaPassword = ""

        val params = api.loginParams(username)
        val verifier = CryptoEnvelope.deriveKey(password, CryptoEnvelope.unb64(params.authSalt))
        val keyPair = device()
        val result = api.login(
            LoginRequest(
                username = username,
                authVerifier = CryptoEnvelope.b64(verifier),
                deviceName = deviceName,
                devicePublicKey = CryptoEnvelope.b64(keyPair.publicKey),
            ),
        )
        return applyLoginResult(result, params, username, password)
    }

    /** MFA 二步验证；通过后仍可能 approved（已批设备）或 pending（新设备继续审批）。 */
    suspend fun finishMfa(code: String, deviceName: String): LoginOutcome {
        check(mfaToken.isNotEmpty()) { "MFA 会话不存在或已过期" }
        val result = api.totpVerify(
            "Bearer $mfaToken",
            TotpVerifyRequest(
                code = code,
                devicePublicKey = CryptoEnvelope.b64(device().publicKey),
                deviceName = deviceName,
            ),
        )
        // approved 分支需要登录时内存保留的主密码派生 KEK；服务端不回盐，直接复用 parameters。
        return applyLoginResult(result, cachedLoginParams!!, username, mfaPassword)
    }

    // loginParams 不含用户主键，approved 分支解 MK 所需盐在 finishMfa 时还要用，缓存一次。
    private var cachedLoginParams: LoginParams? = null

    private fun applyLoginResult(
        result: LoginResponse,
        params: LoginParams,
        username: String,
        password: String,
    ) : LoginOutcome = when (result.status) {
        LoginResponse.STATUS_APPROVED -> {
            result.bundle?.let { applyApprovedBundle(it, password) }
                ?: error("approved 响应缺少 bundle")
            LoginOutcome.Approved
        }
        LoginResponse.STATUS_MFA_REQUIRED -> {
            mfaToken = result.mfaToken.orEmpty()
            mfaPassword = password
            cachedLoginParams = params
            LoginOutcome.MfaRequired(result.expiresIn)
        }
        LoginResponse.STATUS_PENDING -> {
            val p = result.pending ?: error("pending 响应缺少 pending")
            // pending 只有短期 access，不落正式令牌；MK 必须等审批盒端到端下发（见 pairingPoll）。
            pendingAccessToken = p.accessToken
            pendingPairing = p.pairing
            pendingUsername = username
            LoginOutcome.Pending(p.pairing, p.expiresIn)
        }
        else -> error("未知登录状态：${result.status}")
    }

    /** approved：落令牌并用主密码派生 KEK 解开 MK。 */
    private fun applyApprovedBundle(bundle: LoginBundle, password: String) {
        val kek = CryptoEnvelope.deriveKey(password, CryptoEnvelope.unb64(bundle.kekSalt))
        masterKey = CryptoEnvelope.unwrapMasterKey(
            kek, CryptoEnvelope.unb64(bundle.wrappedMasterKey),
        )
        saveSession(bundle.accessToken, bundle.refreshToken, bundle.userId, bundle.deviceId, username)
        _isUnlocked.value = true
    }

    /** 放弃 MFA：清短期会话，回账号密码表单。 */
    fun rejectMfa() {
        mfaToken = ""
        mfaPassword = ""
        cachedLoginParams = null
    }

    // ---- 待审批设备轮询 ----

    /**
     * 待审批设备轮询配对状态。approved 时用本机私钥开箱还原 MK、换发正式令牌并解锁。
     * rejected/expired 由 UI 据状态提示并停轮询。
     */
    suspend fun pairingPoll(): PairingStatusResponse {
        check(pendingAccessToken.isNotEmpty()) { "没有待审批会话" }
        val st = api.pairingStatus("Bearer $pendingAccessToken")
        if (st.state == "approved" && st.tokens != null &&
            st.ephemeralPublicKey != null && st.nonce != null && st.wrappedMasterKey != null
        ) {
            val mk = CryptoEnvelope.boxOpen(
                CryptoEnvelope.SealedBox(
                    ephemeralPublicKey = CryptoEnvelope.unb64(st.ephemeralPublicKey),
                    nonce = CryptoEnvelope.unb64(st.nonce),
                    sealed = CryptoEnvelope.unb64(st.wrappedMasterKey),
                ),
                senderPublicKey = CryptoEnvelope.unb64(st.ephemeralPublicKey),
                ownSecretKey = device().secretKey,
            )
            saveSession(
                st.tokens.accessToken, st.tokens.refreshToken,
                st.tokens.userId, st.tokens.deviceId, pendingUsername.ifEmpty { username },
            )
            masterKey = mk
            pendingAccessToken = ""
            pendingPairing = null
            pendingUsername = ""
            _isUnlocked.value = true
        }
        return st
    }

    /** 放弃等待 / 被拒绝 / 过期：清待审批会话。 */
    fun rejectPairing() {
        pendingAccessToken = ""
        pendingPairing = null
        pendingUsername = ""
    }

    // ---- 审批端 ----

    suspend fun listDevices(): List<DeviceInfo> = api.listDevices().devices
    suspend fun listPairings(): List<PairingInfo> = api.listPairings().pairings

    /** 审批端：用待审批设备公钥把内存 MK 经一次性 crypto_box 密封后提交批准。 */
    suspend fun approvePairing(pairingId: String, devicePublicKeyB64: String) {
        val mk = masterKey?.takeIf { it.isNotEmpty() } ?: error("资料库未解锁")
        val box = CryptoEnvelope.boxSeal(mk, CryptoEnvelope.unb64(devicePublicKeyB64))
        api.approvePairing(
            pairingId,
            ApprovePairingRequest(
                ephemeralPublicKey = CryptoEnvelope.b64(box.ephemeralPublicKey),
                nonce = CryptoEnvelope.b64(box.nonce),
                wrappedMasterKey = CryptoEnvelope.b64(box.sealed),
            ),
        )
    }

    suspend fun rejectPairing(pairingId: String) {
        api.rejectPairing(pairingId)
    }

    suspend fun revokeDevice(targetDeviceId: String) {
        api.revokeDevice(targetDeviceId)
    }

    // ---- 恢复 ----

    /**
     * 恢复第一步：校验恢复码，成功则离线解开 MK（服务端全程接触不到 MK/恢复码明文）。
     * 错码与用户不存在同形（服务端统一 invalid_credentials）。
     */
    suspend fun recoveryStart(username: String, inputCode: String): RecoveryStartResult {
        val code = Crockford.normalize(inputCode)
        val params = api.loginParams(username)
        val verifier = CryptoEnvelope.deriveKey(
            code, CryptoEnvelope.unb64(params.recoveryAuthSalt),
        )
        val session = api.recoveryStart(
            RecoveryStartRequest(username, CryptoEnvelope.b64(verifier)),
        )
        val rek = CryptoEnvelope.deriveKey(
            code, CryptoEnvelope.unb64(params.recoveryKekSalt),
        )
        val mk = CryptoEnvelope.unwrapForRecovery(
            rek, CryptoEnvelope.unb64(params.wrappedMasterKeyRecovery),
        )
        return RecoveryStartResult(session.recoveryToken, mk)
    }

    /** 恢复第二步：新主密码材料 + 强制轮换新恢复码；reset 后当前恢复设备即正式设备。 */
    suspend fun recoveryReset(
        recoveryToken: String,
        mk: ByteArray,
        newPassword: String,
        username: String,
        deviceName: String,
    ): GeneratedRecovery {
        val pw = passwordFields(newPassword, mk)
        val rec = generateRecovery(mk)
        val keyPair = device()
        val pair = api.recoveryReset(
            "Bearer $recoveryToken",
            RecoveryResetRequest(
                authSalt = pw.authSalt,
                kekSalt = pw.kekSalt,
                authVerifier = pw.authVerifier,
                wrappedMasterKey = pw.wrappedMasterKey,
                recoveryAuthSalt = rec.fields.recoveryAuthSalt,
                recoveryKekSalt = rec.fields.recoveryKekSalt,
                recoveryVerifier = rec.fields.recoveryVerifier,
                wrappedMasterKeyRecovery = rec.fields.wrappedMasterKeyRecovery,
                devicePublicKey = CryptoEnvelope.b64(keyPair.publicKey),
                deviceName = deviceName,
            ),
        )
        saveSession(pair.accessToken, pair.refreshToken, pair.userId, pair.deviceId, username)
        masterKey = mk
        _isUnlocked.value = true
        return GeneratedRecovery(rec.code, Crockford.format(rec.code))
    }

    // ---- 改密 ----

    /**
     * 已解锁设备改密：MK 不变，用新密码重新派生包裹；其他设备 refresh 全部吊销。
     * rotateRecovery=true 时同时轮换新恢复码（返回值携带新码，UI 强制备份）。
     */
    suspend fun changePassword(
        newPassword: String,
        rotateRecovery: Boolean,
        deviceName: String,
    ): GeneratedRecovery? {
        val mk = masterKey?.takeIf { it.isNotEmpty() } ?: error("资料库未解锁")
        val pw = passwordFields(newPassword, mk)
        var generated: GeneratedRecovery? = null
        val req = if (rotateRecovery) {
            val rec = generateRecovery(mk)
            generated = GeneratedRecovery(rec.code, Crockford.format(rec.code))
            ChangePasswordRequest(
                authSalt = pw.authSalt,
                kekSalt = pw.kekSalt,
                authVerifier = pw.authVerifier,
                wrappedMasterKey = pw.wrappedMasterKey,
                recoveryAuthSalt = rec.fields.recoveryAuthSalt,
                recoveryKekSalt = rec.fields.recoveryKekSalt,
                recoveryVerifier = rec.fields.recoveryVerifier,
                wrappedMasterKeyRecovery = rec.fields.wrappedMasterKeyRecovery,
            )
        } else {
            ChangePasswordRequest(
                authSalt = pw.authSalt,
                kekSalt = pw.kekSalt,
                authVerifier = pw.authVerifier,
                wrappedMasterKey = pw.wrappedMasterKey,
            )
        }
        val pair = api.changePassword(req)
        // 当前设备令牌对立即换发。
        saveSession(pair.accessToken, pair.refreshToken, pair.userId, pair.deviceId, username)
        return generated
    }

    // ---- 会话生命周期 ----

    fun lock() {
        masterKey = null
        _isUnlocked.value = false
    }

    fun logout() {
        lock()
        // seed 保留：同设备重新登录仍是已批准设备，不必再次配对；清除数据/重装才重置身份。
        pendingAccessToken = ""
        pendingPairing = null
        pendingUsername = ""
        mfaToken = ""
        mfaPassword = ""
        cachedLoginParams = null
        prefs.edit().remove(KEY_TOKEN).remove(KEY_REFRESH)
            .remove(KEY_USER_ID).remove(KEY_DEVICE_ID).remove(KEY_USERNAME).apply()
    }

    fun accessToken(): String? = prefs.getString(KEY_TOKEN, null)
    fun refreshToken(): String? = prefs.getString(KEY_REFRESH, null)

    /** 用 refresh token 换发新令牌对（401 自动刷新用）；失败返回 false。 */
    suspend fun tryRefresh(): Boolean {
        val rt = refreshToken() ?: return false
        return try {
            val pair = api.refresh(mapOf("refresh_token" to rt))
            saveSession(pair.accessToken, pair.refreshToken, pair.userId, pair.deviceId, username)
            true
        } catch (_: Exception) {
            false
        }
    }

    @Suppress("SameParameterValue")
    private fun saveSession(
        token: String,
        refresh: String,
        userId: String,
        deviceId: String,
        username: String,
    ) {
        prefs.edit()
            .putString(KEY_TOKEN, token)
            .putString(KEY_REFRESH, refresh)
            .putString(KEY_USER_ID, userId)
            .putString(KEY_DEVICE_ID, deviceId)
            .putString(KEY_USERNAME, username)
            .apply()
    }
}
