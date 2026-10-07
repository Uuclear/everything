// ============================================================================
// CardOcrParser —— 银行卡 OCR 文本本地启发式解析（stage2b / P2）
// ============================================================================
//
// 对 ML Kit 识别出的卡面文本提取 PAN（仅内存）、Luhn 校验、last4、有效期。
// 纯函数：不入库、不打日志。
// ============================================================================

package com.everything.eve.finance

/** 银行卡识别预填（完整 PAN 仅驻留内存，持久化仅 last4）。 */
data class CardOcrHint(
    val pan: String?,
    val last4: String?,
    val expiryMonth: Int?,
    val expiryYear: Int?,
    val holder: String?,
)

private val EXPIRY_LABEL_REGEX = Regex(
    """(?:VALID\s*THRU|GOOD\s*THRU|有效期|有效期至|EXP(?:IRY)?)""",
    RegexOption.IGNORE_CASE,
)

private val EXPIRY_DATE_REGEX = Regex(
    """(\d{1,2})\s*[/\-.年]\s*(\d{2,4})""",
)

private val HOLDER_LABEL_REGEX = Regex("""(?:持卡人|CARD\s*HOLDER)""", RegexOption.IGNORE_CASE)

/** 从 OCR 全文解析银行卡要素；全部无法识别时返回 null。 */
fun parseCardText(text: String): CardOcrHint? {
    if (text.isBlank()) return null

    val pan = extractLuhnPan(text)
    val last4 = pan?.let { Luhn.extractLast4(it) }
    val (month, year) = extractExpiry(text)
    val holder = extractHolder(text.lines().map { it.trim() }.filter { it.isNotEmpty() })

    // 仅有有效期、无 PAN / 持卡人时不视为有效识别（避免误把日期当卡号）。
    if (pan == null && holder == null) return null

    return CardOcrHint(
        pan = pan,
        last4 = last4,
        expiryMonth = month,
        expiryYear = year,
        holder = holder,
    )
}

private val PAN_GROUPED_REGEX = Regex("""(?:\d{4}[\s-]){2,4}\d{1,4}""")

private val PAN_RUN_REGEX = Regex("""\d{13,19}""")

/** 在各行内扫描 PAN：分组格式或整段 13–19 位数字，经 Luhn 校验后取最长。 */
private fun extractLuhnPan(text: String): String? {
    var best: String? = null
    for (line in text.lineSequence()) {
        val trimmed = line.trim()
        for (match in PAN_GROUPED_REGEX.findAll(trimmed)) {
            considerPanCandidate(match.value, best)?.let { best = it }
        }
        for (match in PAN_RUN_REGEX.findAll(trimmed)) {
            considerPanCandidate(match.value, best)?.let { best = it }
        }
    }
    return best
}

private fun considerPanCandidate(raw: String, currentBest: String?): String? {
    val normalized = raw.replace(Regex("""[\s-]"""), "")
    if (normalized.length !in 13..19 || !Luhn.luhnValidate(normalized)) return currentBest
    if (currentBest == null || normalized.length > currentBest.length) return normalized
    return currentBest
}

private fun extractExpiry(text: String): Pair<Int?, Int?> {
    val lines = text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
    val prioritized = lines.filter { EXPIRY_LABEL_REGEX.containsMatchIn(it) } + lines
    for (line in prioritized) {
        val match = EXPIRY_DATE_REGEX.find(line) ?: continue
        val month = match.groupValues[1].toIntOrNull()?.takeIf { it in 1..12 } ?: continue
        val rawYear = match.groupValues[2]
        val year = when (rawYear.length) {
            2 -> 2000 + rawYear.toInt()
            4 -> rawYear.toInt()
            else -> continue
        }
        if (year in 2000..2099) return month to year
    }
    return null to null
}

private fun extractHolder(lines: List<String>): String? {
    for (line in lines) {
        val label = HOLDER_LABEL_REGEX.find(line)
        if (label != null) {
            val rest = line.substring(label.range.last + 1).trim()
            if (rest.length in 2..30 && rest.count { it.isDigit() } <= 2) return rest
        }
    }
    return null
}
