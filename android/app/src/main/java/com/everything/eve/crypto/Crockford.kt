package com.everything.eve.crypto

/**
 * Crockford Base32（https://www.crockford.com/base32.html）：
 * 恢复密钥的人工备份编码。必须与 Web(crypto/crockford.ts)、docs/crypto.md
 * 的固定向量逐字节一致。
 *
 * 字母表去除易混淆字符（无 I/L/O/U）：
 *   0123456789ABCDEFGHJKMNPQRSTVWXYZ
 * 20 字节随机量恰好编码为 32 个字符，展示为 4 组×8 字符（连字符仅展示用）。
 *
 * 服务端不接触恢复码明文：归一化只在端侧发生，归一化后的 32 字符串才进入
 * Argon2id 派生恢复验证器 / REK。
 */
object Crockford {

    const val RECOVERY_KEY_BYTES = 20
    const val CODE_LEN = 32
    private const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
    private const val GROUP_SIZE = 8
    private const val GROUP_COUNT = 4

    /** 把字节按 MSB 优先的 5bit 分组编码；20 字节 → 恰好 32 字符。 */
    fun encode(bytes: ByteArray): String {
        var bits = 0
        var value = 0
        val sb = StringBuilder()
        for (b in bytes) {
            value = (value shl 8) or (b.toInt() and 0xff)
            bits += 8
            while (bits >= 5) {
                bits -= 5
                sb.append(ALPHABET[(value ushr bits) and 0x1f])
            }
        }
        // 非 5bit 整数倍输入补 0 位（恢复密钥场景不会走到，保留通用正确性）。
        if (bits > 0) {
            sb.append(ALPHABET[(value shl (5 - bits)) and 0x1f])
        }
        return sb.toString()
    }

    /**
     * 规范化用户输入的恢复码：
     *  - 去首尾空白、内部空格与连字符（支持 "XXXX-XXXX" 分组粘贴）；
     *  - 小写转大写；
     *  - Crockford 经典纠错映射 I/L→1、O→0；U 不在字母表内，直接报错。
     * 返回 32 字符大写串；长度或字符非法时抛 IllegalArgumentException。
     */
    fun normalize(input: String): String {
        val cleaned = input
            .trim()
            .uppercase()
            .replace(Regex("[\\s-]"), "")
            .replace("I", "1")
            .replace("L", "1")
            .replace("O", "0")
        require(cleaned.length == CODE_LEN) { "恢复码应为 32 个字符，当前 ${cleaned.length} 个" }
        require(cleaned.all { ALPHABET.indexOf(it) >= 0 }) { "恢复码包含非法字符" }
        return cleaned
    }

    /** 解码规范化后的 32 字符恢复码，必须恰好还原 20 字节。 */
    fun decode(normalized: String): ByteArray {
        var bits = 0
        var value = 0
        val out = ByteArray(CODE_LEN * 5 / 8) // 32*5/8 = 20
        var pos = 0
        for (ch in normalized) {
            val idx = ALPHABET.indexOf(ch)
            require(idx >= 0) { "非法字符：$ch" }
            value = (value shl 5) or idx
            bits += 5
            if (bits >= 8) {
                bits -= 8
                out[pos++] = ((value ushr bits) and 0xff).toByte()
            }
        }
        require(pos == RECOVERY_KEY_BYTES && bits == 0) { "恢复码解码结果不是 20 字节" }
        return out
    }

    /** 生成 4 组×8 字符的展示串（连字符只用于展示，不参与派生）。 */
    fun format(code: String): String {
        require(code.length == CODE_LEN) { "恢复码必须为 32 字符" }
        return (0 until GROUP_COUNT).joinToString("-") {
            code.substring(it * GROUP_SIZE, (it + 1) * GROUP_SIZE)
        }
    }

    /** 便捷方法：规范化 → 分组展示（输入框失焦回显用）。 */
    fun normalizeAndFormat(input: String): String = format(normalize(input))

    /**
     * 固定向量自测（与 Web selftest.ts、docs/crypto.md 一致）：
     * 20 个 0x00 → 32 个 '0'；20 个 0xFF → 32 个 'Z'；
     * 归一化纠错（i/l→1、o→0）后往返一致。仪器化测试环境受限时，
     * 注册/登录路径执行本自测作为密码学底座健全性证据。
     */
    fun selfTest() {
        check(encode(ByteArray(RECOVERY_KEY_BYTES)) == "0".repeat(CODE_LEN)) { "Crockford 零向量不符" }
        check(encode(ByteArray(RECOVERY_KEY_BYTES) { 0xff.toByte() }) == "Z".repeat(CODE_LEN)) {
            "Crockford 全一向量不符"
        }
        val normalized = normalize("6mk2mj04-hkbbffny-p2syakcm-g5pb6gc5")
        val roundtrip = encode(decode(normalized))
        check(roundtrip == normalized) { "Crockford 往返不一致" }
        // 纠错映射：小写 + I/L/O 全部归一
        check(normalize("iiiiiiii-llllllll-oooooooo-00000000") ==
            "1".repeat(16) + "0".repeat(16)) { "Crockford 纠错映射不符" }
    }
}
