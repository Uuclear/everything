// 阶段 2b：vault 级附件校验纯函数（与财务附件共用口径）
package com.everything.eve.vault

import com.everything.eve.finance.ATTACHMENT_MAX_SIZE_BYTES
import java.security.MessageDigest

/** records 通道附件子类型（各 parent_module 共用）。 */
const val VAULT_ATTACHMENT_TYPE: String = "attachment"

/** 影像 MIME 白名单（证件 / 卡面；PDF 走财务合同路径）。 */
val VAULT_IMAGE_MIME_WHITELIST: Set<String> = setOf(
    "image/jpeg",
    "image/png",
    "image/webp",
)

/** 上传前校验结果。 */
sealed class AttachmentValidation {
    data object Ok : AttachmentValidation()
    data class Rejected(val reason: String) : AttachmentValidation()
}

/**
 * 校验附件字节与元数据（size / sha256 / 可选 MIME）。
 * 不访问 IO、不写日志。
 */
fun validateAttachmentUpload(
    content: ByteArray,
    sha256Hex: String,
    mime: String? = null,
    requireImageMime: Boolean = false,
): AttachmentValidation {
    if (content.isEmpty()) {
        return AttachmentValidation.Rejected("附件字节数为 0")
    }
    val size = content.size.toLong()
    if (size > ATTACHMENT_MAX_SIZE_BYTES) {
        return AttachmentValidation.Rejected(
            "附件超过 ${ATTACHMENT_MAX_SIZE_BYTES} 字节上限",
        )
    }
    if (!isValidSha256Hex(sha256Hex)) {
        return AttachmentValidation.Rejected("sha256 hex 非法：必须 64 字符 [0-9a-f]")
    }
    if (requireImageMime && mime != null && mime !in VAULT_IMAGE_MIME_WHITELIST) {
        return AttachmentValidation.Rejected("MIME 不在影像白名单内")
    }
    val computed = sha256HexOf(content)
    if (!computed.equals(sha256Hex, ignoreCase = true)) {
        return AttachmentValidation.Rejected("sha256 与内容不一致")
    }
    return AttachmentValidation.Ok
}

/** 计算 SHA-256 小写 hex（64 字符）。 */
fun sha256HexOf(content: ByteArray): String {
    val digest = MessageDigest.getInstance("SHA-256")
    return digest.digest(content).toHexLower()
}

/** 校验 sha256 hex 格式。 */
fun isValidSha256Hex(sha256Hex: String): Boolean {
    if (sha256Hex.length != 64) return false
    for (c in sha256Hex) {
        val ok = c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F'
        if (!ok) return false
    }
    return true
}

private fun ByteArray.toHexLower(): String {
    val chars = "0123456789abcdef".toCharArray()
    val sb = StringBuilder(size * 2)
    for (b in this) {
        val v = b.toInt() and 0xFF
        sb.append(chars[v ushr 4])
        sb.append(chars[v and 0x0F])
    }
    return sb.toString()
}
