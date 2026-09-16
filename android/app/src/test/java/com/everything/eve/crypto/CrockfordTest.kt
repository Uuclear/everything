package com.everything.eve.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Crockford Base32 固定向量（TR-11.1 的纯算法部分）：与 Web selftest.ts、docs/crypto.md 一致。
 * 恢复 AAD / crypto_box / Argon2id 依赖 libsodium native，只能在 instrumented 测试覆盖
 *（本机无连接设备/模拟器，见评审环境限制说明）。
 */
class CrockfordTest {

    @Test
    fun zeroVector_encodesTo32Zeros() {
        assertEquals("0".repeat(32), Crockford.encode(ByteArray(20)))
    }

    @Test
    fun oneVector_encodesTo32Z() {
        assertEquals("Z".repeat(32), Crockford.encode(ByteArray(20) { 0xff.toByte() }))
    }

    @Test
    fun roundtrip_preservesBytes() {
        // 手工非平凡向量：首字节 0x61（'g' 落在 0x6？）按规范全字节往返验证。
        val bytes = ByteArray(20) { (it * 7 + 3).toByte() }
        val code = Crockford.encode(bytes)
        assertEquals(32, code.length)
        assertArrayEquals(bytes, Crockford.decode(code))
    }

    @Test
    fun normalize_stripsDashesSpacesAndUppercases() {
        val normalized = Crockford.normalize("6mk2mj04-hkbbffny-p2syakcm-g5pb6gc5")
        assertEquals("6MK2MJ04HKBBFFNYP2SYAKCMG5PB6GC5", normalized)
        assertEquals(32, normalized.length)
        // 内部空格、首尾空白同样容忍。
        assertEquals(
            normalized,
            Crockford.normalize("  6MK2MJ04 HKBBFFNY P2SYAKCM G5PB6GC5  "),
        )
    }

    @Test
    fun normalize_crockfordConfusionMapping() {
        // I/L → 1，O → 0（小写同样处理）。
        assertEquals(
            "1".repeat(16) + "0".repeat(16),
            Crockford.normalize("iiiiiiii-llllllll-oooooooo-00000000"),
        )
    }

    @Test
    fun normalize_rejectsIllegalCharU() {
        // U 不在 Crockford 字母表，且无纠错映射。
        assertThrows(IllegalArgumentException::class.java) {
            Crockford.normalize("uuuuuuuu-uuuuuuuu-uuuuuuuu-uuuuuuuu")
        }
    }

    @Test
    fun normalize_rejectsWrongLength() {
        assertThrows(IllegalArgumentException::class.java) {
            Crockford.normalize("ABCDEF")
        }
    }

    @Test
    fun format_groupsFourByEight() {
        assertEquals(
            "6MK2MJ04-HKBBFFNY-P2SYAKCM-G5PB6GC5",
            Crockford.format("6MK2MJ04HKBBFFNYP2SYAKCMG5PB6GC5"),
        )
    }

    @Test
    fun decode_wrongLengthThrows() {
        assertThrows(IllegalArgumentException::class.java) {
            Crockford.decode("ABC")
        }
    }
}
