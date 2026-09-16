package com.everything.eve.crypto

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.goterl.lazysodium.LazySodiumAndroid
import com.goterl.lazysodium.SodiumAndroid
import com.goterl.lazysodium.interfaces.AEAD
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * FU-2：恢复信封跨端锁定向量（与 Go TestRecoveryInteropVector、Web selftest.ts 同一来源）。
 * 固定盐/nonce/MK：Argon2id 派生 + XChaCha20-Poly1305 加密必须产出锁定 base64，
 * 生产解包路径必须能解开同一向量；任何一端原语参数或 AAD 文本漂移都会在此暴露。
 *
 * 注意：lazysodium 需加载设备原生库，本测试只能作为 instrumented 测试运行
 * （connectedDebugAndroidTest）；本机无设备时仅参与编译，不执行。
 */
@RunWith(AndroidJUnit4::class)
class RecoveryEnvelopeVectorTest {

    companion object {
        /** 完整 Crockford 字母表恢复码（与 Go/Web 向量一致，无连字符分隔）。 */
        private const val LOCKED_CODE = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"

        /** 锁定的 sealed（nonce||ciphertext）base64。 */
        private const val LOCKED_B64 =
            "ZmZmZmZmZmZmZmZmZmZmZmZmZmZmZmZmIWDn9l9j4ZLuseuCaVv4fttrfeQEX6yIjtKfYJ+tAQAXXyOjaMvn6A/BQ0hc9idK"
    }

    @Test
    fun recoveryEnvelopeMatchesLockedVector() {
        val salt = ByteArray(16) { 0x44 }
        val nonce = ByteArray(24) { 0x66 }
        val mk = ByteArray(32) { 0x5A }
        // AAD 独立构造而非引用 CryptoEnvelope 私有常量——AAD 文本漂移也必须被本测试捕获。
        val aad = "eve:v1:master-key-recovery/v1".toByteArray(Charsets.UTF_8)

        // 生产派生函数：REK = Argon2id(恢复码归一化串, salt)。
        val rek = CryptoEnvelope.deriveKey(LOCKED_CODE, salt)

        // 手工固定 nonce 加密，比对锁定 base64（生产 aeadSeal 用随机 nonce 不可控，
        // 这里直接调 Native 9 参原语，签名与 CryptoEnvelope 内部一致）。
        val sodium = LazySodiumAndroid(SodiumAndroid())
        val aeadNative = sodium as AEAD.Native
        val cipher = ByteArray(mk.size + AEAD.XCHACHA20POLY1305_IETF_ABYTES)
        val cipherLen = LongArray(1)
        val ok = aeadNative.cryptoAeadXChaCha20Poly1305IetfEncrypt(
            cipher, cipherLen,
            mk, mk.size.toLong(),
            aad, aad.size.toLong(),
            null, // nsec：IETF 变体必须为 null
            nonce, rek,
        )
        assertTrue("XChaCha20 加密失败", ok)
        val sealed = nonce + cipher.copyOf(cipherLen[0].toInt())
        assertEquals("恢复信封跨端锁定向量漂移（派生/加密参数或 AAD 变更）", LOCKED_B64, CryptoEnvelope.b64(sealed))

        // 生产解包路径必须能解开同一固定向量（nonce||ciphertext 布局与 AAD 一致性）。
        val opened = CryptoEnvelope.unwrapForRecovery(rek, CryptoEnvelope.unb64(LOCKED_B64))
        assertArrayEquals("恢复信封固定向量解包失败", mk, opened)
    }
}
