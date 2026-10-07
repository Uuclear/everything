package com.everything.eve.vault

import com.everything.eve.finance.ATTACHMENT_MAX_SIZE_BYTES
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 附件校验纯函数单测（无真实影像、无 native）。 */
class AttachmentRulesTest {

    @Test
    fun validate_rejects_empty_content() {
        val r = validateAttachmentUpload(
            ByteArray(0),
            sha256HexOf(byteArrayOf(1)),
            mime = "image/jpeg",
        )
        assertTrue(r is AttachmentValidation.Rejected)
    }

    @Test
    fun attachment_max_size_is_50_mib() {
        assertEquals(50L * 1024L * 1024L, ATTACHMENT_MAX_SIZE_BYTES)
    }

    @Test
    fun validate_rejects_bad_sha256_length() {
        val content = byteArrayOf(0x01, 0x02)
        val r = validateAttachmentUpload(content, "abc", mime = "image/png")
        assertTrue(r is AttachmentValidation.Rejected)
    }

    @Test
    fun validate_rejects_sha256_mismatch() {
        val content = byteArrayOf(0x0a)
        val wrong = "a".repeat(64)
        val r = validateAttachmentUpload(content, wrong, mime = "image/jpeg")
        assertTrue(r is AttachmentValidation.Rejected)
    }

    @Test
    fun validate_accepts_matching_hash() {
        val content = byteArrayOf(0x0a, 0x0b)
        val hex = sha256HexOf(content)
        val r = validateAttachmentUpload(content, hex, mime = "image/webp")
        assertEquals(AttachmentValidation.Ok, r)
    }

    @Test
    fun isValidSha256Hex_accepts_lowercase_64() {
        assertTrue(isValidSha256Hex(sha256HexOf(byteArrayOf(1, 2, 3))))
    }
}
