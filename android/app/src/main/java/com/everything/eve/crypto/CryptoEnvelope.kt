package com.everything.eve.crypto

import android.util.Base64
import com.goterl.lazysodium.LazySodiumAndroid
import com.goterl.lazysodium.SodiumAndroid
import com.goterl.lazysodium.interfaces.AEAD
import com.goterl.lazysodium.interfaces.Box
import com.goterl.lazysodium.interfaces.PwHash
import java.nio.ByteBuffer

/**
 * 零知识加密信封（Android 端）。必须与 Go(internal/crypto)、Web(crypto/envelope.ts)
 * 逐字节一致，规范见 docs/crypto.md：
 *  - Argon2id t=3，m=64MiB，并行度=1，输出 32B，salt 16B
 *  - XChaCha20-Poly1305 IETF，密文布局 nonce(24) || ciphertext
 *  - 记录 AAD："eve:v1:record:" + id + ":" + module + ":" + BE(uint64 version)
 *  - 主密钥包裹 AAD："eve:v1:master-key/v1"
 *  - 恢复包裹 AAD："eve:v1:master-key-recovery/v1"
 *  - 轨迹块 AAD："eve:v1:location-block:" + blockId（阶段 4a，块不可变无版本号）
 *
 * 注意 lazysodium-android 5.1.0 没有 XChaCha20 的 Lazy 字符串重载，必须直接调
 * Native 9 参 API（长度参数 long[] 为出参），并自行前置随机 24B nonce。
 *
 * 设备配对端到端信道（X25519 + crypto_box，与 nacl/box 同协议）：
 *  - 设备身份由 32B seed 经 crypto_box_seed_keypair 恒定派生；
 *  - 审批端一次性临时密钥对 + 24B nonce 密封 MK，新设备用自身私钥开箱。
 */
object CryptoEnvelope {

    private val sodium by lazy { LazySodiumAndroid(SodiumAndroid()) }

    /** X25519/crypto_box 的 Native 字节接口（避免 lazy Base64 字符串 API 的歧义）。 */
    private val boxNative: Box.Native get() = sodium as Box.Native
    private val aeadNative: AEAD.Native get() = sodium as AEAD.Native

    const val KEY_LEN = 32
    const val SALT_LEN = 16
    private const val ARGON_TIME = 3L
    private const val ARGON_MEM = 64L * 1024 * 1024 // 64 MiB
    private const val NONCE_LEN = AEAD.XCHACHA20POLY1305_IETF_NPUBBYTES // 24
    private const val AEAD_TAG_LEN = AEAD.XCHACHA20POLY1305_IETF_ABYTES // 16

    private val WRAP_AAD = "eve:v1:master-key/v1".toByteArray()
    private val RECOVERY_WRAP_AAD = "eve:v1:master-key-recovery/v1".toByteArray()

    // ---- 随机量 ----
    fun randomSalt(): ByteArray = sodium.randomBytesBuf(SALT_LEN)
    fun newMasterKey(): ByteArray = sodium.randomBytesBuf(KEY_LEN)
    fun randomRecoveryKey(): ByteArray = sodium.randomBytesBuf(Crockford.RECOVERY_KEY_BYTES)

    /** Argon2id 派生 32 字节密钥（登录验证器、KEK、恢复 REK 同源不同盐）。 */
    fun deriveKey(password: String, salt: ByteArray): ByteArray {
        require(salt.size == SALT_LEN) { "salt 必须为 16 字节" }
        val pw = password.toByteArray(Charsets.UTF_8)
        val out = ByteArray(KEY_LEN)
        // lazysodium 5.1.0 Native 签名：内存参数为 JNA NativeLong；opsLimit 为 long。
        check(
            sodium.cryptoPwHash(
                pw, pw.size,
                out, KEY_LEN,
                salt,
                ARGON_TIME,
                com.sun.jna.NativeLong(ARGON_MEM),
                PwHash.Alg.PWHASH_ALG_ARGON2ID13,
            ),
        ) { "Argon2id 派生失败" }
        return out
    }

    // ---- XChaCha20-Poly1305 IETF：nonce(24) || ciphertext ----

    private fun aeadSeal(plain: ByteArray, aad: ByteArray, key: ByteArray): ByteArray {
        val nonce = sodium.randomBytesBuf(NONCE_LEN)
        val cipher = ByteArray(plain.size + AEAD_TAG_LEN)
        val cipherLen = LongArray(1)
        check(
            aeadNative.cryptoAeadXChaCha20Poly1305IetfEncrypt(
                cipher, cipherLen,
                plain, plain.size.toLong(),
                aad, aad.size.toLong(),
                null, // nsec：IETF 变体必须为 null
                nonce, key,
            ),
        ) { "XChaCha20 加密失败" }
        // 输出布局与 Go/Web 一致：nonce 前置。
        return nonce + cipher.copyOf(cipherLen[0].toInt())
    }

    private fun aeadOpen(sealed: ByteArray, aad: ByteArray, key: ByteArray): ByteArray {
        require(sealed.size > NONCE_LEN + AEAD_TAG_LEN) { "密文长度不合法" }
        val nonce = sealed.copyOfRange(0, NONCE_LEN)
        val cipher = sealed.copyOfRange(NONCE_LEN, sealed.size)
        val plain = ByteArray(cipher.size - AEAD_TAG_LEN)
        val plainLen = LongArray(1)
        check(
            aeadNative.cryptoAeadXChaCha20Poly1305IetfDecrypt(
                plain, plainLen,
                null, // nsec
                cipher, cipher.size.toLong(),
                aad, aad.size.toLong(),
                nonce, key,
            ),
        ) { "XChaCha20 解密失败（密钥/AAD 不匹配）" }
        return plain.copyOf(plainLen[0].toInt())
    }

    // ---- 主密码包裹的 MK ----
    fun wrapMasterKey(kek: ByteArray, mk: ByteArray): ByteArray = aeadSeal(mk, WRAP_AAD, kek)
    fun unwrapMasterKey(kek: ByteArray, wrapped: ByteArray): ByteArray =
        aeadOpen(wrapped, WRAP_AAD, kek)

    // ---- 恢复码包裹的 MK（REK = Argon2id(恢复码归一化串, recovery_kek_salt)）----
    fun wrapForRecovery(rek: ByteArray, mk: ByteArray): ByteArray = aeadSeal(mk, RECOVERY_WRAP_AAD, rek)
    fun unwrapForRecovery(rek: ByteArray, wrapped: ByteArray): ByteArray =
        aeadOpen(wrapped, RECOVERY_WRAP_AAD, rek)

    // ---- 记录信封 ----
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
    ): ByteArray = aeadSeal(plaintext, recordAAD(id, module, version), key)

    fun openRecord(
        key: ByteArray,
        sealed: ByteArray,
        id: String,
        module: String,
        version: Long,
    ): ByteArray = aeadOpen(sealed, recordAAD(id, module, version), key)

    // ---- 轨迹块信封（阶段 4a）----
    /** 轨迹块 AAD 域："eve:v1:location-block:" + blockId（块不可变，无版本号）。 */
    private fun locationBlockAAD(blockId: String): ByteArray =
        ("eve:v1:location-block:$blockId").toByteArray(Charsets.UTF_8)

    fun sealLocationBlock(key: ByteArray, plaintext: ByteArray, blockId: String): ByteArray =
        aeadSeal(plaintext, locationBlockAAD(blockId), key)

    fun openLocationBlock(key: ByteArray, sealed: ByteArray, blockId: String): ByteArray =
        aeadOpen(sealed, locationBlockAAD(blockId), key)

    // ---- 设备 X25519 身份 ----

    data class DeviceKeyPair(val publicKey: ByteArray, val secretKey: ByteArray) {
        override fun equals(other: Any?): Boolean = this === other
        override fun hashCode(): Int = publicKey.contentHashCode()
    }

    /** 由 32B seed 恒定派生 X25519 密钥对（同 seed 必得同公钥，设备行据此复用）。 */
    fun deviceKeypairFromSeed(seed: ByteArray): DeviceKeyPair {
        require(seed.size == Box.SEEDBYTES) { "设备 seed 必须为 32 字节" }
        val pk = ByteArray(Box.PUBLICKEYBYTES)
        val sk = ByteArray(Box.SECRETKEYBYTES)
        check(boxNative.cryptoBoxSeedKeypair(pk, sk, seed)) { "crypto_box_seed_keypair 失败" }
        return DeviceKeyPair(pk, sk)
    }

    fun randomDeviceSeed(): ByteArray = sodium.randomBytesBuf(Box.SEEDBYTES)

    /** 审批端一次性临时密钥对（每次批准都必须重新生成，禁止复用）。 */
    private fun boxKeypair(): DeviceKeyPair {
        val pk = ByteArray(Box.PUBLICKEYBYTES)
        val sk = ByteArray(Box.SECRETKEYBYTES)
        check(boxNative.cryptoBoxKeypair(pk, sk)) { "crypto_box_keypair 失败" }
        return DeviceKeyPair(pk, sk)
    }

    data class SealedBox(
        val ephemeralPublicKey: ByteArray, // 32B
        val nonce: ByteArray, // 24B
        val sealed: ByteArray, // crypto_box_easy 密文（含 16B Poly1305 标签）
    )

    /**
     * 审批端：用待审批设备公钥密封 MK。
     * sealed = crypto_box_easy(MK, nonce, recipient_pk=新设备公钥, eph_sk)
     */
    fun boxSeal(message: ByteArray, recipientPublicKey: ByteArray): SealedBox {
        require(recipientPublicKey.size == Box.PUBLICKEYBYTES) { "接收方公钥必须为 32 字节" }
        val ephemeral = boxKeypair()
        val nonce = sodium.randomBytesBuf(Box.NONCEBYTES)
        val cipher = ByteArray(message.size + Box.MACBYTES)
        check(
            boxNative.cryptoBoxEasy(
                cipher, message, message.size.toLong(),
                nonce, recipientPublicKey, ephemeral.secretKey,
            ),
        ) { "crypto_box_easy 密封失败" }
        return SealedBox(ephemeral.publicKey, nonce, cipher)
    }

    /**
     * 新设备：用本机私钥开箱还原 MK。
     * sender_pk=审批端一次性临时公钥；认证失败（盒被调包/材料不匹配）时抛异常。
     */
    fun boxOpen(
        sealedBox: SealedBox,
        senderPublicKey: ByteArray,
        ownSecretKey: ByteArray,
    ): ByteArray {
        require(senderPublicKey.size == Box.PUBLICKEYBYTES) { "发送方公钥必须为 32 字节" }
        require(sealedBox.nonce.size == Box.NONCEBYTES) { "nonce 必须为 24 字节" }
        val plain = ByteArray(sealedBox.sealed.size - Box.MACBYTES)
        check(
            boxNative.cryptoBoxOpenEasy(
                plain, sealedBox.sealed, sealedBox.sealed.size.toLong(),
                sealedBox.nonce, senderPublicKey, ownSecretKey,
            ),
        ) { "crypto_box_open_easy 开箱失败（材料不匹配或盒被调包）" }
        return plain
    }

    // ---- Base64 ----
    fun b64(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)
    fun unb64(b64: String): ByteArray = Base64.decode(b64, Base64.NO_WRAP)
}
