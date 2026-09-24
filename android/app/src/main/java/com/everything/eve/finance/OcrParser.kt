// ============================================================================
// OcrParser —— 小票 OCR 文本本地启发式解析纯函数（stage5-finance-v2 / Task 9 / B8）
// ============================================================================
//
// 任务: stage5-finance-v2 / Task 9 / B8 批次（纯函数解析层）
// 路径: android/app/src/main/java/com/everything/eve/finance/OcrParser.kt
// 作用: 对 ML Kit on-device 识别出的小票完整文本做启发式解析，输出
//       「金额 / 日期 / 商家」三要素 ReceiptHint，供后续 UI 记账编辑器预填。
//
// 设计要点:
//   1. 纯函数 —— 无任何副作用：不入库、不打日志（零知识红线：OCR 原文
//      绝不持久化、绝不写 Log / println / System.out）；
//   2. 金额全程 BigDecimal 精确换算为分（Long），禁止使用 Double；
//      关键词行（合计 / 总计 / 金额 / 应付 / 实付 / total / amount）
//      优先级最高，无关键词时回退为全文最大的形如 0.00 的小数数字；
//   3. 金额兼容 ¥ / ￥ / RMB / 元 / 块 前后缀与千分位逗号；连续 7 位
//      以上纯数字（电话号码）不作为金额；
//   4. 日期支持 年-月-日、年/月/日、中文年月日、点分 / 斜杠欧式
//      （日.月.年）等形式；按设备默认时区统一转当天 00:00 的 epoch 毫秒；
//      年份早于 2000 或晚于当前年加 1 一律忽略；
//   5. 商家取文本前若干行中「基本不含数字」且长度合适的行，过滤
//      「欢迎光临 / 谢谢惠顾 / 小票 / 收银」等噪声词。
//
// 关联:
//   - tasks.md stage5-finance-v2 Task 9（AI 联动记账：OCR + 语音本地解析）
//   - spec FR-V2-E.2（OCR / 语音预填三要素）
//   - 同目录 SpeechParser.kt（语音文本解析，口径一致）
// ============================================================================

package com.everything.eve.finance

import java.math.BigDecimal
import java.util.Calendar

/** 小票识别预填提示（三要素；全部可空，仅用于预填编辑器，绝不持久化）。 */
data class ReceiptHint(
    val amountMinor: Long?, // 金额（最小货币单位，人民币为分；35.00 元 → 3500）
    val ts: Long?,          // 小票上的日期时间（epoch 毫秒）
    val merchant: String?   // 商家名
)

// ============================================================================
// 金额解析相关常量
// ============================================================================

/** 金额关键词（所在行的数字优先于全文其它数字）；英文按小写匹配。 */
private val AMOUNT_KEYWORDS = listOf(
    "合计", "总计", "金额", "应付", "实付",
    "total", "amount",
)

/**
 * 带货币符号 / 单位的金额数字。
 * 第 1 组：前缀符号（¥ / ￥ / RMB）后的数字；
 * 第 2 组：后跟「元 / 块」的数字。
 */
private val CURRENCY_NUMBER_REGEX = Regex(
    """(?:[¥￥]|RMB)\s*(\d[\d,]*(?:\.\d{1,2})?)""" +
        """|(\d[\d,]*(?:\.\d{1,2})?)\s*[元块]"""
)

/**
 * 普通小数金额：支持千分位（1,234.56）或纯小数（35.00）。
 * 前后附加否定边界，避免从日期（24.09.2026）或长数字中间截取片段。
 */
private val DECIMAL_REGEX = Regex(
    """(?<![\d,.])(\d{1,3}(?:,\d{3})+(?:\.\d{1,2})?|\d+\.\d{1,2})(?![\d,.])"""
)

/** 纯整数金额（最多 6 位），用于关键词行没有小数数字的兜底；7 位以上视为电话。 */
private val PLAIN_INTEGER_REGEX = Regex("""(?<!\d)(\d{1,6})(?!\d)""")

/** 标准千分位分组校验（如 1,234.56）。 */
private val GROUPED_NUMBER_REGEX = Regex("""^\d{1,3}(,\d{3})+(\.\d{1,2})?$""")

// ============================================================================
// 日期解析相关常量
// ============================================================================

/** 年在前的日期：2026-09-24 / 2026/9/24 / 2026年9月24日（尾部「日」可缺）。 */
private val DATE_YEAR_FIRST_REGEX = Regex(
    """(\d{4})\s*[-/.年]\s*(\d{1,2})\s*[-/.月]\s*(\d{1,2})\s*日?"""
)

/** 年在后的点分 / 斜杠日期：24.09.2026 / 24/09/2026（欧式，日在月前）。 */
private val DATE_YEAR_LAST_REGEX = Regex(
    """(\d{1,2})\s*[/.]\s*(\d{1,2})\s*[/.]\s*(\d{4})"""
)

/** 商家行噪声词（命中即排除）。 */
private val MERCHANT_NOISE_WORDS = listOf(
    "欢迎光临", "谢谢惠顾", "欢迎", "惠顾",
    "小票", "收银", "凭条",
)

/** 商家行只在文本前若干个非空行中寻找。 */
private const val MERCHANT_SCAN_LINE_LIMIT = 10

/**
 * 小票 OCR 文本启发式解析（纯函数，无副作用，不打日志）。
 * 输入 ML Kit on-device 识别出的完整文本，输出三要素 Hint；三要素全部无法识别时返回 null。
 *
 * @param text OCR 识别出的完整多行文本（中文小票为主，兼容英文）
 * @return 三要素提示；金额、日期、商家均无法识别时返回 null
 */
fun parseReceiptText(text: String): ReceiptHint? {
    // 空文本直接判定为无法识别
    if (text.isBlank()) return null

    // 按行拆分：去首尾空白并丢弃空行，保留原始出现顺序
    val lines = text.lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .toList()

    // 三要素分别独立解析，互不影响
    val amountMinor = extractAmount(lines)
    val ts = extractDate(text)
    val merchant = extractMerchant(lines)

    // 三要素全部缺失时返回 null
    if (amountMinor == null && ts == null && merchant == null) return null

    return ReceiptHint(
        amountMinor = amountMinor,
        ts = ts,
        merchant = merchant,
    )
}

// ============================================================================
// 金额解析
// ============================================================================

/**
 * 提取金额（分为单位）。
 * 1. 逐行查找金额关键词行，命中行解析成功即返回（文档顺序优先）；
 * 2. 无关键词行或关键词行均无数字时，回退取全文中形如 0.00 的最大小数。
 */
private fun extractAmount(lines: List<String>): Long? {
    for (line in lines) {
        // 英文关键词统一转小写后匹配，中文不受影响
        val lowerLine = line.lowercase()
        if (AMOUNT_KEYWORDS.any { lowerLine.contains(it) }) {
            parseKeywordLineAmount(line)?.let { return it }
        }
    }
    // 回退：逐行扫描小数数字并跨行取最大（纯整数不参与，避免误取电话 / 门牌号）；
    // 「数量 × 单价」明细行整行跳过，避免其中小数字被误当金额
    return lines.asSequence()
        .filterNot { isQuantityUnitLine(it) }
        .mapNotNull { line ->
            DECIMAL_REGEX.findAll(line)
                .mapNotNull { tokenToBigDecimal(it.groupValues[1]) }
                .maxOrNull()
                ?.toCents()
        }
        .maxOrNull()
}

/**
 * 判断一行是否为「数量 × 单价」明细行：
 * - 行内含乘号（× / x / X / *）且同时有数字；
 * - 或一行出现两个及以上小数数字（数量、单价、小计并列）。
 */
private fun isQuantityUnitLine(line: String): Boolean {
    val hasMultiplySign = Regex("""[×xX*]""").containsMatchIn(line)
    if (hasMultiplySign && line.any { it.isDigit() }) return true

    val decimalCount = DECIMAL_REGEX.findAll(line).count()
    return decimalCount >= 2
}

/**
 * 解析金额关键词所在行的数字。选择优先级：
 * 1. 带货币符号 / 单位的数字（多个时取最后一个，「实付 ¥70」位于「原价 ¥99」之后的场景）；
 * 2. 普通小数数字（多个时取最大，排除「合计 70.00 含配送费 5.00」中的小数字）；
 * 3. 剔除日期片段后的纯整数（最多 6 位，多个时取最大）。
 */
private fun parseKeywordLineAmount(line: String): Long? {
    // 1. 带货币符号 / 单位的数字：两个捕获组任一命中即可
    val symbolAmounts = CURRENCY_NUMBER_REGEX.findAll(line)
        .mapNotNull { match ->
            val token = match.groupValues[1].ifEmpty { match.groupValues[2] }
            tokenToBigDecimal(token)
        }
        .toList()
    if (symbolAmounts.isNotEmpty()) {
        return symbolAmounts.last().toCents()
    }

    // 2. 普通小数数字：取最大
    val decimalAmount = DECIMAL_REGEX.findAll(line)
        .mapNotNull { tokenToBigDecimal(it.groupValues[1]) }
        .maxOrNull()
    if (decimalAmount != null) {
        return decimalAmount.toCents()
    }

    // 3. 纯整数兜底：先剔除行内日期片段（避免把 2026 等年份当成金额）
    val withoutDates = stripDateFragments(line)
    return PLAIN_INTEGER_REGEX.findAll(withoutDates)
        .mapNotNull { tokenToBigDecimal(it.groupValues[1]) }
        .maxOrNull()
        ?.toCents()
}

/**
 * 把一个金额数字 token 解析为 BigDecimal；不合法返回 null。
 * - 去除千分位逗号后构造 BigDecimal（精确十进制，禁止 Double）；
 * - 必须为正数，小数位数不超过 2；
 * - 含逗号时额外校验标准千分位分组。
 */
private fun tokenToBigDecimal(token: String): BigDecimal? {
    if (token.isEmpty()) return null
    // 含逗号时先做分组格式校验（防止「1,23」这类非法片段）
    if (token.contains(",") && !GROUPED_NUMBER_REGEX.matches(token)) return null

    val normalized = token.replace(",", "")
    // 用 runCatching 兜住构造异常（理论上正则已保证是数字）
    val value = runCatching { BigDecimal(normalized) }.getOrNull() ?: return null

    if (value.signum() <= 0) return null
    if (value.scale() > 2) return null
    return value
}

/** 元为单位的 BigDecimal 精确换算为分（乘 100 后取整；超出 Long 范围时抛 ArithmeticException）。 */
private fun BigDecimal.toCents(): Long =
    this.multiply(BigDecimal(100))
        .setScale(0)
        .longValueExact()

/** 剔除字符串中的日期片段（替换为空格），供整数扫描使用。 */
private fun stripDateFragments(s: String): String {
    val withoutYearFirst = DATE_YEAR_FIRST_REGEX.replace(s) { " ".repeat(it.value.length) }
    return DATE_YEAR_LAST_REGEX.replace(withoutYearFirst) { " ".repeat(it.value.length) }
}

// ============================================================================
// 日期解析
// ============================================================================

/**
 * 提取小票日期，转设备默认时区当天 00:00 的 epoch 毫秒。
 * 先找「年在前」形式，再找「年在后」欧式形式；首个通过合理性校验的日期生效。
 */
private fun extractDate(text: String): Long? {
    // 年在前：年-月-日 / 年/月/日 / 年月日
    DATE_YEAR_FIRST_REGEX.findAll(text).forEach { match ->
        val (year, month, day) = Triple(
            match.groupValues[1].toInt(),
            match.groupValues[2].toInt(),
            match.groupValues[3].toInt(),
        )
        buildMidnightTs(year, month, day)?.let { return it }
    }

    // 年在后：日.月.年 / 日/月/年；需根据月日取值消歧
    DATE_YEAR_LAST_REGEX.findAll(text).forEach { match ->
        val first = match.groupValues[1].toInt()
        val second = match.groupValues[2].toInt()
        val year = match.groupValues[3].toInt()

        val month: Int
        val day: Int
        when {
            // 首段大于 12，必为日：日 / 月 / 年（如 24.09.2026）
            first > 12 -> {
                day = first
                month = second
            }
            // 次段大于 12，首段为月：月 / 日 / 年（美式，如 09/24/2026）
            second > 12 -> {
                month = first
                day = second
            }
            // 两段均不超过 12 时无法消歧，按欧式日 / 月 / 年处理
            else -> {
                day = first
                month = second
            }
        }
        buildMidnightTs(year, month, day)?.let { return it }
    }

    return null
}

/**
 * 构造设备默认时区下某年某月某日 00:00 的 epoch 毫秒。
 * - 年份范围：2000（含）至 当前年加 1（含），超出视为不合理日期；
 * - Calendar 关闭宽松解析，月日非法（如 2 月 30 日）时抛异常并返回 null。
 */
private fun buildMidnightTs(year: Int, month: Int, day: Int): Long? {
    val currentYear = Calendar.getInstance().get(Calendar.YEAR)
    if (year < 2000 || year > currentYear + 1) return null

    val calendar = Calendar.getInstance()
    calendar.isLenient = false
    calendar.clear()
    // 月、日、时分秒全部归零（毫秒在 clear 后亦为 0）
    calendar.set(year, month - 1, day, 0, 0, 0)

    return try {
        calendar.timeInMillis
    } catch (e: IllegalArgumentException) {
        // 月 / 日不合法，忽略该日期
        null
    }
}

// ============================================================================
// 商家解析
// ============================================================================

/**
 * 提取商家名：在前若干个非空行中寻找第一个符合商家特征的行。
 */
private fun extractMerchant(lines: List<String>): String? =
    lines.take(MERCHANT_SCAN_LINE_LIMIT)
        .firstOrNull { isMerchantLine(it) }

/**
 * 判断一行文本是否像商家名：
 * - 长度 2 至 20；
 * - 不含任何噪声词（欢迎光临 / 谢谢惠顾 / 小票 / 收银 等）；
 * - 数字最多 2 个（商家名可能带「1 层」等字样，基本不含数字即可）；
 * - 至少包含 2 个中日韩统一表意文字，或以字母为主（兼容英文商家）。
 */
private fun isMerchantLine(line: String): Boolean {
    if (line.length < 2 || line.length > 20) return false
    if (MERCHANT_NOISE_WORDS.any { line.contains(it) }) return false

    val digitCount = line.count { it.isDigit() }
    if (digitCount > 2) return false

    val cjkCount = line.count { it.code in 0x4E00..0x9FFF }
    if (cjkCount >= 2) return true

    val letterCount = line.count { it.isLetter() }
    // 英文商家：至少 2 个字母且字母占行长度一半以上
    return letterCount >= 2 && letterCount * 2 >= line.length
}
