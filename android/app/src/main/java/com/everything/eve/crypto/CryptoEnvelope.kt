package com.everything.eve.crypto

import android.util.Base64
import com.goterl.lazysodium.LazySodiumAndroid
import com.goterl.lazysodium.SodiumAndroid
import com.goterl.lazysodium.interfaces.PwHash
import java.nio.ByteBuffer

/**
 * 零知识加密信封（Android 端）。必须与 Go(internal/crypto)、Web(crypto/envelope.ts)
 * 逐字节一致，规范见 docs/crypto.md：
 *  - Argon2id t=3，m=64MiB，并行度=1，输出 32B，salt 16B
 *  - XChaCha20-Poly1305 IETF，密文布局 nonce(24) || ciphertext
 *  - 记录 AAD："eve:v1:record:" + id + ":" + module + ":" + BE(uint64 version)
 *  - 主密钥包裹 AAD："eve:v1:master-key/v1"
 *
 * lazysodium 的 easy API 自动生成 nonce 并前置，正好得到 nonce||ciphertext 布局。
 */
object CryptoEnvelope {

    private val sodium by lazy { LazySodiumAndroid(SodiumAndroid()) }

    const val KEY_LEN = 32
    const val SALT_LEN = 16
    private const val ARGON_TIME = 3L
    private const val ARGON_MEM = 64L * 1024 * 1024 // 64 MiB
    private val WRAP_AAD = "eve:v1:master-key/v1".toByteArray()

    fun randomSalt(): ByteArray = sodium.randomBytesBuf(SALT_LEN)

    fun newMasterKey(): ByteArray = sodium.randomBytesBuf(KEY_LEN)

    /** Argon2id 派生 32 字节密钥（登录验证器与 KEK 同源不同盐）。 */
    fun deriveKey(password: String, salt: ByteArray): ByteArray {
        require(salt.size == SALT_LEN) { "salt 必须为 16 字节" }
        return sodium.cryptoPwHash(
            password.toByteArray(Charsets.UTF_8),
            KEY_LEN,
            salt,
            ARGON_TIME,
            ARGON_MEM,
            PwHash.Alg.PwhashAlgArgon2id13,
        )
    }

    fun wrapMasterKey(kek: ByteArray, mk: ByteArray): ByteArray =
        sodium.cryptoAeadXChaCha20Poly1305IetfEncrypt(mk, WRAP_AAD, kek)

    fun unwrapMasterKey(kek: ByteArray, wrapped: ByteArray): ByteArray =
        sodium.cryptoAeadXChaCha20Poly1305IetfDecrypt(wrapped, WRAP_AAD, kek)

    private fun recordAAD(id: String, module: String, version: Long): ByteArray {
        val prefix = "eve:v1:record:$id:$module:".toByteArray(Charsets.UTF_8)
        val out = ByteBuffer.allocate(prefix.size + 8)
        out.put(prefix)
        out.putLong(version) // 大端
        return out.array()
    }

    fun sealRecord(
        key: ByteArray,
        plaintext: ByteArray,
        id: String,
        module: String,
        version: Long,
    ): ByteArray = sodium.cryptoAeadXChaCha20Poly1305IetfEncrypt(
        plaintext, recordAAD(id, module, version), key,
    )

    fun openRecord(
        key: ByteArray,
        sealed: ByteArray,
        id: String,
        module: String,
        version: Long,
    ): ByteArray = sodium.cryptoAeadXChaCha20Poly1305IetfDecrypt(
        sealed, recordAAD(id, module, version), key,
    )

    fun b64(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)
    fun unb64(b64: String): ByteArray = Base64.decode(b64, Base64.NO_WRAP)
}
