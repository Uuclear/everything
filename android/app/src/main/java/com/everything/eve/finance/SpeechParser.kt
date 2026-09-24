// ============================================================================
// SpeechParser —— 语音记账文本本地意图解析纯函数（stage5-finance-v2 / Task 9 / B8）
// ============================================================================
//
// 任务: stage5-finance-v2 / Task 9 / B8 批次（纯函数解析层）
// 路径: android/app/src/main/java/com/everything/eve/finance/SpeechParser.kt
// 作用: 对设备端语音识别（on-device ASR）出的自然语句做意图解析，输出
//       「金额 / 分类 / 时间」SpeechHint，供后续 UI 记账编辑器预填。
//
// 设计要点:
//   1. 纯函数 —— 无任何副作用：不入库、不打日志（零知识红线：语音原文
//      绝不持久化、绝不写 Log / println / System.out）；
//   2. 金额为语音记账必填要素，无法识别金额时整体返回 null；
//   3. 金额支持阿拉伯数字（35 / 35.5 / 35 元）与中文数字（三十五块、
//      一百二十元五角、两千三百五十九），全程 BigDecimal 精确换算为分；
//   4. 分类输出【中文标签】（餐饮 / 交通 / 购物 …），与仓库既有分类
//      自由文本存储惯例一致；多关键词命中时取金额动词之后最先出现者；
//   5. 时间语义：「昨天 / 前天」分别回退 1 / 2 天，时刻保持当前时分秒。
//
// 关联:
//   - tasks.md stage5-finance-v2 Task 9（AI 联动记账：OCR + 语音本地解析）
//   - spec FR-V2-E.2（语音预填要素，范例：买了个汉堡花了 35 元 → 餐饮 3500 分）
//   - 同目录 OcrParser.kt（小票文本解析，口径一致）
// ============================================================================

package com.everything.eve.finance

import java.math.BigDecimal
import java.util.Calendar

/** 语音记账预填提示（仅用于预填编辑器，绝不持久化）。 */
data class SpeechHint(
    val amountMinor: Long?,                       // 金额（分）
    val category: String?,                        // 分类标签
    val ts: Long = System.currentTimeMillis()     // 默认当前时刻
)

// ============================================================================
// 金额解析相关常量
// ============================================================================

/**
 * 金额触发动词：数字应出现在这些词「之后附近」。
 * 按文档顺序逐个尝试，保证「花了 35 元」这类句子的就近匹配。
 */
private val MONEY_TRIGGERS = listOf(
    "花了", "用了", "付了", "支出", "消费", "转了", "收入", "收到",
)

/** 触发词之后向右寻找金额的窗口长度（字符），覆盖口语中的少量插入语。 */
private const val MONEY_WINDOW = 12

/**
 * 阿拉伯数字金额：整数（千分位可选）加最多两位小数。
 * 前后否定边界防止从长数字串或连续小数片段中截取。
 */
private val ARABIC_AMOUNT_REGEX = Regex(
    """(?<![\d,.])(\d{1,3}(?:,\d{3})+|\d+)(?:\.(\d{1,2}))?(?![\d,.])"""
)

/** 标准千分位分组校验（如 1,234）。 */
private val GROUPED_NUMBER_REGEX = Regex("""^\d{1,3}(,\d{3})+$""")

// ============================================================================
// 中文数字解析相关常量
// ============================================================================

/** 基础中文数字字符（含「两」与大写「零」）。 */
private val CHINESE_DIGITS = "零〇一壹二贰两三叁四肆五伍六陆七柒八捌九玖"

/** 中文数位（节单位）字符。 */
private val CHINESE_UNITS = "十拾百佰千千万亿"

/**
 * 中文金额短语：
 * - 起点必须是中文数字 / 数位（十 / 百 等），不含阿拉伯数字；
 * - 长度 2 及以上时可独立成数（纯整数，如「三十五」），也可带后缀；
 * - 长度为 1 时必须带后缀，避免「一个人」中的「一」被误判为金额；
 * - 后缀形式一：点加一到两个中文数字（如「三十五点五」）；
 * - 后缀形式二：元 / 块后可选角分（如「一百二十元五角」「三十五块」）。
 */
private val CHINESE_AMOUNT_REGEX = Regex(
    """(?<![$CHINESE_DIGITS$CHINESE_UNITS])""" +
        """(""" +
        """[$CHINESE_DIGITS$CHINESE_UNITS]{2,}""" +
        """(?:点[$CHINESE_DIGITS]{1,2}""" +
        """|[元块](?:[$CHINESE_DIGITS]+角?)?(?:[$CHINESE_DIGITS]+分?)?)?""" +
        """|""" +
        """[$CHINESE_DIGITS$CHINESE_UNITS]""" +
        """(?:点[$CHINESE_DIGITS]{1,2}""" +
        """|[元块](?:[$CHINESE_DIGITS]+角?)?(?:[$CHINESE_DIGITS]+分?)?)""" +
        """)"""
)

/** 仅由中文数字 / 数位字符构成（用于判定纯整数形式）。 */
private val CHINESE_NUMBER_ONLY_REGEX = Regex("""^[$CHINESE_DIGITS$CHINESE_UNITS]+$""")

// ============================================================================
// 分类关键词表（中文标签，顺序即判定优先级）
// ============================================================================

/**
 * 分类映射：关键词 → 中文分类标签。
 * 多个分类共用同一词时（如「机票」同时接近交通与旅行），按本表声明顺序
 * 取先出现者；判定时在金额动词之后取最先命中的关键词。
 */
private val CATEGORY_KEYWORDS: List<Pair<String, String>> = buildList {
    // 餐饮（短词「餐 / 饭 / 面 / 粉」兜底各种口语说法）
    listOf(
        "吃饭", "早餐", "午餐", "晚餐", "外卖",
        "汉堡", "奶茶", "咖啡", "火锅", "烧烤",
        "餐", "饭", "面", "粉",
    ).forEach { add(it to "餐饮") }

    // 交通
    listOf(
        "打车", "地铁", "公交", "加油", "停车",
        "高铁", "火车", "机票", "车票", "滴滴", "出行",
    ).forEach { add(it to "交通") }

    // 购物
    listOf(
        "买了", "购物", "淘宝", "京东",
        "超市", "商场", "衣服", "鞋",
    ).forEach { add(it to "购物") }

    // 居家
    listOf("房租", "水电", "燃气", "物业", "家用").forEach { add(it to "居家") }

    // 娱乐
    listOf("电影", "游戏", "KTV", "旅游", "门票", "视频会员").forEach { add(it to "娱乐") }

    // 医疗
    listOf("医院", "看病", "药", "挂号", "体检").forEach { add(it to "医疗") }

    // 教育
    listOf("学费", "书", "课程", "培训").forEach { add(it to "教育") }

    // 通讯
    listOf("话费", "流量", "宽带").forEach { add(it to "通讯") }

    // 旅行
    listOf("酒店", "住宿", "旅行").forEach { add(it to "旅行") }
}

/**
 * 分类搜索锚点动词：
 * - 优先使用动作锚点「买了」（它本身也是购物关键词），保证
 *   「我刚买了个汉堡花了 35 元」中「汉堡」出现在「花了」之前仍能命中；
 * - 句中没有「买了」时，才退而以金额触发动词为锚点（如「打车花了 28 元」）。
 */
private val ACTION_ANCHORS = listOf("买了")

/**
 * 语音识别文本本地意图解析（纯函数，无副作用，不打日志）。
 * 无法识别金额时返回 null（金额为语音记账必填要素）。
 *
 * @param text 语音识别出的完整自然语句
 * @return 金额 / 分类 / 时间提示；金额无法识别时返回 null
 */
fun parseSpeechText(text: String): SpeechHint? {
    val input = text.trim()
    if (input.isEmpty()) return null

    // 1. 金额：先在触发动词之后的窗口内就近查找，再回退全文扫描
    val amountMinor = findAmount(input) ?: return null

    // 2. 分类：金额 / 动作动词之后最先命中的关键词；无命中则为 null
    val category = findCategory(input)

    // 3. 时间：昨天 / 前天语义回退，否则默认当前时刻
    val ts = resolveTs(input)

    return SpeechHint(
        amountMinor = amountMinor,
        category = category,
        ts = ts,
    )
}

// ============================================================================
// 金额解析
// ============================================================================

/**
 * 提取金额（分）。
 * 1. 逐个金额触发动词，在其后的窗口内先匹配阿拉伯数字、再匹配中文数字；
 * 2. 所有窗口均未命中时，对全文做同样的两轮扫描兜底。
 */
private fun findAmount(text: String): Long? {
    // 1. 触发动词就近匹配
    for (trigger in MONEY_TRIGGERS) {
        val index = text.indexOf(trigger)
        if (index < 0) continue
        // 窗口为触发词结束位置之后的一段文本
        val start = index + trigger.length
        val window = text.substring(start, (start + MONEY_WINDOW).coerceAtMost(text.length))
        readNumberFromWindow(window)?.let { return it }
    }

    // 2. 全文兜底
    return readNumberFromWindow(text)
}

/**
 * 在一段文本窗口内读取一个金额数字：阿拉伯数字优先，其次中文数字。
 */
private fun readNumberFromWindow(window: String): Long? {
    // 阿拉伯数字：取窗口内首个命中
    ARABIC_AMOUNT_REGEX.findAll(window).forEach { match ->
        arabicMatchToCents(match)?.let { return it }
    }
    // 中文数字：取窗口内首个可成功换算的短语
    CHINESE_AMOUNT_REGEX.findAll(window).forEach { match ->
        chineseAmountToCents(match.groupValues[1])?.let { return it }
    }
    return null
}

/**
 * 阿拉伯数字正则命中转分：
 * - 整数部分做千分位校验后去逗号；
 * - 小数部分缺位补零（如 35.5 → 35.50 → 3550 分）。
 */
private fun arabicMatchToCents(match: MatchResult): Long? {
    val intPart = match.groupValues[1]
    // 含逗号时必须符合标准分组
    if (intPart.contains(",") && !GROUPED_NUMBER_REGEX.matches(intPart)) return null

    val fraction = match.groupValues[2]
    val decimalText = intPart.replace(",", "") + "." + fraction.padEnd(2, '0')
    return runCatching {
        BigDecimal(decimalText).multiply(BigDecimal(100)).setScale(0).longValueExact()
    }.getOrNull()
}

// ============================================================================
// 中文数字金额解析
// ============================================================================

/**
 * 中文金额短语转分。支持三种形式：
 * 1. 「N 点 dd」：点后一到两个中文数字按十分位、百分位处理（三十五点五）；
 * 2. 「N 元 …」：元后可跟一个或两个中文数字（角 / 分），如五角、五角三分；
 * 3. 「N」：纯中文整数（三十五）。
 *
 * @return 金额分数；短语无法换算时返回 null
 */
private fun chineseAmountToCents(token: String): Long? {
    // 形式一：点分
    val dotIndex = token.indexOf('点')
    if (dotIndex >= 0) {
        val integer = chineseNumberToBigDecimal(token.substring(0, dotIndex)) ?: return null
        val fractionDigits = token.substring(dotIndex + 1)
        if (fractionDigits.isEmpty() || fractionDigits.length > 2) return null

        var fraction = BigDecimal.ZERO
        var divisor = BigDecimal.TEN
        for (ch in fractionDigits) {
            val digit = chineseDigitValue(ch) ?: return null
            fraction = fraction.add(BigDecimal(digit).divide(divisor))
            divisor = divisor.multiply(BigDecimal.TEN)
        }
        return integer.add(fraction)
            .multiply(BigDecimal(100))
            .setScale(0)
            .longValueExact()
    }

    // 形式二：元 / 块 + 角分（块在口语中等价于元）
    val yuanIndex = token.indexOf('元').let { if (it >= 0) it else token.indexOf('块') }
    if (yuanIndex >= 0) {
        // 单位前至少要有一个字（「块钱」的块不会出现在 token 首位的独立数字场景）
        if (yuanIndex == 0) return null
        val integer = chineseNumberToBigDecimal(token.substring(0, yuanIndex)) ?: return null
        val rest = token.substring(yuanIndex + 1)
        // 角分部分只允许一到两个中文数字（尾部可能带「角」「分」单位字）
        val digits = rest.filter { isChineseDigitChar(it) }
        if (digits.length > 2) return null

        var cents = integer.multiply(BigDecimal(100))
        if (digits.isNotEmpty()) {
            cents = cents.add(BigDecimal(chineseDigitValue(digits[0])!!.toLong() * 10))
        }
        if (digits.length == 2) {
            cents = cents.add(BigDecimal(chineseDigitValue(digits[1])!!.toLong()))
        }
        return cents.setScale(0).longValueExact()
    }

    // 形式三：纯中文整数
    if (CHINESE_NUMBER_ONLY_REGEX.matches(token)) {
        return chineseNumberToBigDecimal(token)
            ?.multiply(BigDecimal(100))
            ?.setScale(0)
            ?.longValueExact()
    }
    return null
}

/**
 * 判断字符是否为中文基础数字（不含十百千万等数位）。
 */
private fun isChineseDigitChar(ch: Char): Boolean = chineseDigitValue(ch) != null

/**
 * 单个中文字符映射为数字 0 到 9；非数字字符返回 null。
 * 兼容「〇 / 零」「两 / 二」以及财务大写「壹贰叁肆伍陆柒捌玖」。
 */
private fun chineseDigitValue(ch: Char): Int? = when (ch) {
    '零', '〇' -> 0
    '一', '壹' -> 1
    '二', '贰', '两' -> 2
    '三', '叁' -> 3
    '四', '肆' -> 4
    '五', '伍' -> 5
    '六', '陆' -> 6
    '七', '柒' -> 7
    '八', '捌' -> 8
    '九', '玖' -> 9
    else -> null
}

/**
 * 中文整数转 BigDecimal（纯函数，无副作用）。
 *
 * 解析思路（单遍扫描 + 分节累加）：
 *   1. 维护两个累加器：
 *      - current：当前小节（亿 / 万以内）的值；
 *      - total：已跨过的高位节（亿、万）总和。
 *   2. 逐字符处理：
 *      - 基础数字（零到九）记为 lastDigit；
 *      - 「十 / 百 / 千」：将上个数字（缺位时按 1，如「十五」中的十）
 *        乘对应倍率后并入 current；
 *      - 「万」：把 current 乘 10000 并入 total 后清零 current；
 *      - 「亿」：把（total 加 current）乘 1 亿并入 total 后清零 current。
 *   3. 扫描结束后，尾部剩余的单个数字按「个位」并入 current；
 *      口语省读规则：若该数字位于串尾且紧跟百 / 千 / 万等单位
 *      （如「一百二」「一万五」「一千六」），按该单位的下一级单位
 *      换算，即 120 / 15000 / 1600。
 *
 * 支持组合：三十五（35）、一百二（120）、两千三百五十九（2359）、
 * 一万二千三百四十五（12345）、一亿两千万（120000000）等。
 *
 * @param s 纯中文数字 / 数位字符组成的字符串
 * @return 对应 BigDecimal；无法解析时返回 null
 */
private fun chineseNumberToBigDecimal(s: String): BigDecimal? {
    if (s.isEmpty()) return null

    var total = BigDecimal.ZERO   // 已确定的高位节之和
    var current = BigDecimal.ZERO // 当前节内累计
    var lastDigit = -1            // 最近一个基础数字；负一表示缺位
    var lastUnit = 0              // 最近一次使用的节内倍率（10 / 100 / 1000；0 表示无）

    for ((index, ch) in s.withIndex()) {
        val digit = chineseDigitValue(ch)
        if (digit != null) {
            lastDigit = digit
            lastUnit = 0
            continue
        }

        when (ch) {
            '十', '拾' -> {
                val value = if (lastDigit < 0) 1 else lastDigit
                current = current.add(BigDecimal(value.toLong() * 10))
                lastDigit = -1
                lastUnit = 10
            }
            '百', '佰' -> {
                if (lastDigit < 0) return null
                current = current.add(BigDecimal(lastDigit.toLong() * 100))
                lastDigit = -1
                lastUnit = 100
            }
            '千' -> {
                if (lastDigit < 0) return null
                current = current.add(BigDecimal(lastDigit.toLong() * 1000))
                lastDigit = -1
                lastUnit = 1000
            }
            '万' -> {
                // current 之外，万还可能直接挂在一个数字上（如「两万」）
                val section = if (lastDigit >= 0) {
                    current.add(BigDecimal(lastDigit.toLong() * 10000))
                } else {
                    current.multiply(BigDecimal(10000))
                }
                total = total.add(section)
                current = BigDecimal.ZERO
                lastDigit = -1
                lastUnit = 10000
            }
            '亿' -> {
                val base = total.add(current).let {
                    if (lastDigit >= 0) it.add(BigDecimal(lastDigit.toLong())) else it
                }
                total = base.multiply(BigDecimal(100_000_000))
                current = BigDecimal.ZERO
                lastDigit = -1
                lastUnit = 100_000_000
            }
            else -> return null // 非数字非数位字符，拒绝
        }
    }

    // 处理尾部剩余数字
    if (lastDigit >= 0) {
        val tailValue = if (isChineseDigitChar(s.last()) && lastUnit >= 100) {
            // 口语省读：结尾数字按上一单位的下一级换算，
            // 百后乘 10（一百二 → 120），千后乘 100（一千六 → 1600），
            // 万后乘 1000（一万五 → 15000）
            lastDigit * (lastUnit / 10)
        } else {
            lastDigit
        }
        current = current.add(BigDecimal(tailValue.toLong()))
    }

    val result = total.add(current)
    // 至少要有一个有效数字（避免纯单位误判为 0）
    return if (result.signum() > 0) result else null
}

// ============================================================================
// 分类解析
// ============================================================================

/**
 * 提取分类中文标签。
 * 1. 锚点选择：句中出现动作锚点「买了」时以它为准；否则以最早出现的
 *    金额触发动词为准；二者都不存在时从全文开头搜索；
 * 2. 在锚点结束位置之后寻找最先出现的关键词，命中即返回对应标签；
 * 3. 锚点之后无命中时回退全文搜索；全文也无命中则返回 null。
 */
private fun findCategory(text: String): String? {
    // 优先动作锚点（保证「买了个汉堡花了 35 元」命中餐饮而非买了自身的购物标签）
    var searchFrom = -1
    for (verb in ACTION_ANCHORS) {
        val index = text.indexOf(verb)
        if (index >= 0) searchFrom = index + verb.length
    }

    // 无动作锚点时，退而取最早的金额触发动词
    if (searchFrom < 0) {
        var earliest = Int.MAX_VALUE
        for (verb in MONEY_TRIGGERS) {
            val index = text.indexOf(verb)
            if (index >= 0 && index < earliest) {
                earliest = index
                searchFrom = index + verb.length
            }
        }
    }

    // 在锚点之后寻找最先命中的关键词
    if (searchFrom >= 0) {
        scanKeywords(text, searchFrom)?.let { return it }
        // 锚点之后无命中：全文兜底（含锚点自身所在的关键词，如「买了」→ 购物）
        return scanKeywords(text, 0)
    }

    // 完全没有锚点：全文搜索
    return scanKeywords(text, 0)
}

/** 在 [start] 之后的文本中检索最先出现的分类关键词，返回对应中文标签。 */
private fun scanKeywords(text: String, start: Int): String? {
    var bestIndex = Int.MAX_VALUE
    var bestLabel: String? = null
    for ((keyword, label) in CATEGORY_KEYWORDS) {
        val index = text.indexOf(keyword, start)
        if (index >= 0 && index < bestIndex) {
            bestIndex = index
            bestLabel = label
        }
    }
    return bestLabel
}

// ============================================================================
// 时间语义解析
// ============================================================================

/**
 * 解析时间语义：「前天」回退 2 天、「昨天」回退 1 天（先判前天避免包含误判）；
 * 均基于设备默认时区日历操作，时刻保持当前时分秒；无命中时返回当前时刻。
 */
private fun resolveTs(text: String): Long {
    val now = System.currentTimeMillis()
    val daysAgo = when {
        text.contains("前天") -> 2
        text.contains("昨天") -> 1
        else -> 0
    }
    if (daysAgo == 0) return now

    val calendar = Calendar.getInstance()
    calendar.timeInMillis = now
    calendar.add(Calendar.DAY_OF_MONTH, -daysAgo)
    return calendar.timeInMillis
}
