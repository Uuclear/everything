// ============================================================================
// IdentityOcrParser —— 身份证正反面 OCR 文本启发式解析（stage2b / P2）
// ============================================================================
//
// 纯函数：姓名/号码/住址（正面），签发机关/有效期（反面）。不入库、不打日志。
// ============================================================================

package com.everything.eve.finance

/** 身份证正面识别预填。 */
data class IdentityFrontOcrHint(
    val name: String?,
    val number: String?,
    val address: String?,
)

/** 身份证反面识别预填。 */
data class IdentityBackOcrHint(
    val issuer: String?,
    val validFrom: String?,
    val expiresOn: String?,
)

private val ID_NUMBER_REGEX = Regex("""\d{6}\s*\d{8}\s*\d{3}[\dXx]""")

private val ID_NUMBER_COMPACT = Regex("""\d{17}[\dXx]""")

private val NAME_LABEL_REGEX = Regex("""姓名""")

private val ADDRESS_LABEL_REGEX = Regex("""住址""")

private val GENDER_LABEL_REGEX = Regex("""性别""")

private val ISSUER_LABEL_REGEX = Regex("""签发机关""")

private val VALID_PERIOD_LABEL_REGEX = Regex("""有效期限|有效期""")

private val DATE_RANGE_REGEX = Regex(
    """(\d{4})\s*[.\-/年]\s*(\d{1,2})\s*[.\-/月]\s*(\d{1,2})\s*日?\s*[-—~至]\s*(\d{4})\s*[.\-/年]\s*(\d{1,2})\s*[.\-/月]\s*(\d{1,2})\s*日?""",
)

private val LONG_TERM_REGEX = Regex("""长期""")

private val ID_CHECK_WEIGHTS = intArrayOf(7, 9, 10, 5, 8, 4, 2, 1, 6, 3, 7, 9, 10, 5, 8, 4, 2)

private val ID_CHECK_CHARS = "10X98765432"

/** 解析身份证正面 OCR 文本。 */
fun parseIdentityFrontText(text: String): IdentityFrontOcrHint? {
    if (text.isBlank()) return null
    val lines = text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
    val number = extractIdNumber(text)
    val name = extractName(lines)
    val address = extractAddress(text, lines)
    if (number == null && name == null && address == null) return null
    return IdentityFrontOcrHint(name = name, number = number, address = address)
}

/** 解析身份证反面 OCR 文本。 */
fun parseIdentityBackText(text: String): IdentityBackOcrHint? {
    if (text.isBlank()) return null
    val lines = text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
    val issuer = extractIssuer(lines)
    val (from, to) = extractValidPeriod(text)
    if (issuer == null && from == null && to == null) return null
    return IdentityBackOcrHint(issuer = issuer, validFrom = from, expiresOn = to)
}

private fun extractIdNumber(text: String): String? {
    val spaced = ID_NUMBER_REGEX.find(text)?.value?.replace(Regex("""\s+"""), "")
    val candidate = spaced ?: ID_NUMBER_COMPACT.find(text)?.value
    return candidate?.uppercase()?.takeIf { isValidChineseId(it) }
}

private fun isValidChineseId(id: String): Boolean {
    if (id.length != 18) return false
    if (!id.substring(0, 17).all { it.isDigit() }) return false
    val last = id[17]
    if (!(last.isDigit() || last == 'X')) return false
    var sum = 0
    for (i in 0 until 17) {
        sum += (id[i].code - '0'.code) * ID_CHECK_WEIGHTS[i]
    }
    val expected = ID_CHECK_CHARS[sum % 11]
    return last == expected
}

private fun extractName(lines: List<String>): String? {
    for (line in lines) {
        val label = NAME_LABEL_REGEX.find(line)
        if (label != null) {
            val rest = line.substring(label.range.last + 1).trim()
            val name = rest.substringBefore("性别").trim()
                .ifBlank { rest.split(Regex("""\s+""")).firstOrNull() ?: "" }
            if (isPlausibleChineseName(name)) return name
        }
    }
    for (line in lines) {
        if (GENDER_LABEL_REGEX.containsMatchIn(line)) {
            val before = line.substringBefore("性别").trim()
            if (isPlausibleChineseName(before)) return before
        }
    }
    return null
}

private fun isPlausibleChineseName(s: String): Boolean {
    if (s.length !in 2..8) return false
    val cjk = s.count { it.code in 0x4E00..0x9FFF }
    return cjk >= 2 && cjk * 2 >= s.length
}

private fun extractAddress(text: String, lines: List<String>): String? {
    val label = ADDRESS_LABEL_REGEX.find(text)
    if (label != null) {
        val after = text.substring(label.range.last + 1).lineSequence().first().trim()
        if (after.length >= 4) return after.take(80)
    }
    return lines.firstOrNull { it.length >= 8 && it.count { it.isDigit() } <= 4 && !ID_NUMBER_COMPACT.containsMatchIn(it) }
        ?.take(80)
}

private fun extractIssuer(lines: List<String>): String? {
    for (line in lines) {
        val label = ISSUER_LABEL_REGEX.find(line)
        if (label != null) {
            val rest = line.substring(label.range.last + 1).trim()
            if (rest.length in 4..40) return rest
        }
    }
    return null
}

private fun extractValidPeriod(text: String): Pair<String?, String?> {
    if (LONG_TERM_REGEX.containsMatchIn(text)) {
        val range = DATE_RANGE_REGEX.find(text)
        if (range != null) {
            val from = formatYmd(
                range.groupValues[1].toInt(),
                range.groupValues[2].toInt(),
                range.groupValues[3].toInt(),
            )
            return from to "长期"
        }
    }
    val match = DATE_RANGE_REGEX.find(text)
    if (match != null) {
        val from = formatYmd(
            match.groupValues[1].toInt(),
            match.groupValues[2].toInt(),
            match.groupValues[3].toInt(),
        )
        val to = formatYmd(
            match.groupValues[4].toInt(),
            match.groupValues[5].toInt(),
            match.groupValues[6].toInt(),
        )
        return from to to
    }
    for (line in text.lineSequence()) {
        if (VALID_PERIOD_LABEL_REGEX.containsMatchIn(line)) {
            val dates = Regex("""\d{4}[.\-/年]\d{1,2}[.\-/月]\d{1,2}""")
                .findAll(line)
                .map { normalizeDateToken(it.value) }
                .toList()
            if (dates.size >= 2) return dates[0] to dates[1]
            if (dates.size == 1 && LONG_TERM_REGEX.containsMatchIn(line)) return dates[0] to "长期"
        }
    }
    return null to null
}

private fun normalizeDateToken(raw: String): String {
    val parts = Regex("""\d+""").findAll(raw).map { it.value.toInt() }.toList()
    if (parts.size < 3) return raw
    return formatYmd(parts[0], parts[1], parts[2])
}

private fun formatYmd(year: Int, month: Int, day: Int): String =
    "%04d-%02d-%02d".format(year, month, day)
