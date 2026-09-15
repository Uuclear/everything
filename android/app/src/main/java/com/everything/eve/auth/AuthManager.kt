package com.everything.eve.auth

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.everything.eve.ServiceLocator
import com.everything.eve.api.LoginRequest
import com.everything.eve.api.RegisterRequest
import com.everything.eve.crypto.CryptoEnvelope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 负责注册/登录、令牌保管（EncryptedSharedPreferences）与内存态主密钥。
 * 主密钥永不落盘；应用被杀后必须重新输入主密码解锁。
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
    }

    private val _isUnlocked = MutableStateFlow(false)
    val isUnlocked: StateFlow<Boolean> = _isUnlocked.asStateFlow()

    /** 仅内存，绝不持久化。 */
    @Volatile
    var masterKey: ByteArray? = ByteArray(0)
        private set

    val isLoggedIn: Boolean get() = prefs.getString(KEY_TOKEN, null) != null
    val username: String get() = prefs.getString(KEY_USERNAME, "") ?: ""

    var serverUrl: String
        get() = prefs.getString(PREF_SERVER, DEFAULT_SERVER) ?: DEFAULT_SERVER
        set(value) = prefs.edit().putString(PREF_SERVER, value).apply()

    suspend fun register(username: String, password: String, deviceName: String) {
        val authSalt = CryptoEnvelope.randomSalt()
        val kekSalt = CryptoEnvelope.randomSalt()
        val verifier = CryptoEnvelope.deriveKey(password, authSalt)
        val kek = CryptoEnvelope.deriveKey(password, kekSalt)
        val mk = CryptoEnvelope.newMasterKey()
        val wrapped = CryptoEnvelope.wrapMasterKey(kek, mk)

        val pair = ServiceLocator.api.register(
            RegisterRequest(
                username = username,
                authSalt = CryptoEnvelope.b64(authSalt),
                kekSalt = CryptoEnvelope.b64(kekSalt),
                authVerifier = CryptoEnvelope.b64(verifier),
                wrappedMasterKey = CryptoEnvelope.b64(wrapped),
                deviceName = deviceName,
            ),
        )
        saveSession(pair.accessToken, pair.refreshToken, pair.userId, pair.deviceId, username)
        masterKey = mk
        _isUnlocked.value = true
    }

    suspend fun login(username: String, password: String, deviceName: String) {
        val params = ServiceLocator.api.loginParams(username)
        val authSalt = CryptoEnvelope.unb64(params.authSalt)
        val kekSalt = CryptoEnvelope.unb64(params.kekSalt)
        val verifier = CryptoEnvelope.deriveKey(password, authSalt)
        val bundle = ServiceLocator.api.login(
            LoginRequest(username, CryptoEnvelope.b64(verifier), deviceName),
        )
        val kek = CryptoEnvelope.deriveKey(password, kekSalt)
        masterKey = CryptoEnvelope.unwrapMasterKey(kek, CryptoEnvelope.unb64(params.wrappedMasterKey))
        saveSession(bundle.accessToken, bundle.refreshToken, bundle.userId, bundle.deviceId, username)
        _isUnlocked.value = true
    }

    fun lock() {
        masterKey = null
        _isUnlocked.value = false
    }

    fun logout() {
        lock()
        prefs.edit().clear().apply()
    }

    fun accessToken(): String? = prefs.getString(KEY_TOKEN, null)

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
