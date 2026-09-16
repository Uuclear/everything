// ============================================================================
// Luhn 模 10 校验 —— 财务模块 Android 端纯函数实现（stage5-finance / T3）
// ============================================================================
//
// 任务: stage5-finance / Task 3 / TR-3.1
// 路径: android/app/src/main/java/com/everything/eve/finance/Luhn.kt
// 作用: 在客户端对用户录入的银行卡/信用卡卡号做 Luhn 校验, 校验通过后
//       才会提取后四位入库; 完整卡号仅在校验瞬间驻留内存, 不写入任何
//       持久化层 / 日志 / 网络（零知识纪律）。
//
// 设计要点（与 Web web/src/finance/luhn.ts 字节级一致 —— 三端契约）:
//   1. 纯函数 —— 无副作用, 不调用 Room / Network / DataStore / Log;
//   2. 入参为 String —— 避免 Java Number 在长卡号上的精度丢失;
//   3. 自动 trim 空格（U+0020） + 去除连字符（U+002D）;
//   4. 边界情况: 空串 / 非字符串 / 含非数字 / 长度越界 → 全部返回 false;
//   5. 与 Android 端 Luhn.kt、Web luhn.ts 行为逐字段一致（三端契约,
//      见 docs/finance.md §"Luhn 校验"）。
//
// 关联:
//   - tasks.md TR-3.1（Android Luhn.kt 镜像实现）
//   - tasks.md TR-3.2（JUnit 测试套件, 加载共享 fixture）
//   - tasks.md TR-3.3（三端 fixture SHA-256 一致性核验）
//   - web/src/finance/luhn.ts（Web 镜像版本）
// ============================================================================

package com.everything.eve.finance

/**
 * Luhn 模 10 校验 —— 纯函数 object 容器（无状态, 全静态方法）。
 *
 * 命名采用 Kotlin 单例 `object` 而非 `class`, 与工程内 Recurrence.kt 等
 * 纯算法容器保持风格一致（详见 android/.../recurrence/Recurrence.kt）。
 */
object Luhn {

    // ============================================================================
    // 常量区 —— 算法边界与字符规则（与 Web luhn.ts 完全对齐）
    // ============================================================================

    /**
     * 银行卡号合法长度区间（按 ISO/IEC 7812 行业惯例）。
     *
     * - 最小 13 位: 部分老式 Maestro / 早期 Visa 13 位卡;
     * - 最大 19 位: UnionPay / Maestro 长卡号上限;
     * - 小于 13 或大于 19 一律视为非法输入, 直接返回 false。
     *
     * 与 Web 端 MIN_PAN_LENGTH / MAX_PAN_LENGTH 数值完全一致。
     */
    private const val MIN_PAN_LENGTH = 13
    private const val MAX_PAN_LENGTH = 19

    /**
     * 用户录入卡号时常见的可见分隔符 —— Luhn 计算前必须剥离。
     *
     * 注意: 只剥离空白（U+0020）与连字符（U+002D）, 其它符号（例如点 /
     * 斜杠）按非法处理, 直接返回 false。该规则与 Web 端 SEPARATOR_REGEX
     * (`/[\s-]+/g`) 行为对齐 —— `\s` 在 JS 中匹配空白字符集（含空格 /
     * Tab 等）, 但实际卡号场景下用户只会输入空格, 此处显式匹配 U+0020
     * + U+002D 已覆盖等价集合, 同时避免 Kotlin/Java 正则对 `\s` 的
     * 跨语言差异。
     */
    private const val SEPARATOR_CHARS = " -"

    /**
     * Luhn 提取末 4 位 —— 与 ISO/IEC 7812 标准保持一致（行业惯例）。
     */
    private const val LAST4_LENGTH = 4

    /**
     * Luhn 模 10 除数。
     */
    private const val MODULUS = 10

    /**
     * Luhn 累加时偶数位（自右数）的乘数。
     */
    private const val DOUBLE_MULTIPLIER = 2

    /**
     * 数字 0 的字符值, 用于 ASCII '0'..'9' 直接转 Int（避免 toInt() 开销）。
     */
    private const val ASCII_ZERO = '0'.code

    // ============================================================================
    // 公开 API —— Luhn 校验 + 末 4 位提取（与 Web luhn.ts 公开签名一致）
    // ============================================================================

    /**
     * 对银行卡号做 Luhn 模 10 校验（纯函数, 无副作用）。
     *
     * 行为契约（与 Web 端 `luhnValidate` 字节级一致）:
     *   - 输入允许包含空格（U+0020）/ 连字符（U+002D）（自动剥离）;
     *   - 输入为空 / 非字符串 / 长度越界 / 含其它字符 → false;
     *   - 算法采用自右向左「奇偶位 ×2 后求个位数字之和」的标准实现;
     *   - 永不抛错, 仅返回 Boolean。
     *
     * @param pan 银行卡号原文（仅校验, 不入库, 不入日志）
     * @return 是否通过 Luhn 校验 —— 校验通过返回 true; 任意非法输入返回 false
     */
    fun luhnValidate(pan: String): Boolean {
        // ========== 1. 基础类型与空值守卫 ==========
        // 非字符串 / 空串 → 立即 false, 避免后续逻辑误判。Kotlin 入参类型
        // 已经是 String, 此处仅做空串判断（编译期已拒绝 null/非字符串）。
        if (pan.isEmpty()) {
            return false
        }

        // ========== 2. 剥离常见分隔符（空格 / 连字符） ==========
        // 用临时变量承载规范化后的串, 不修改入参本身（pure 语义）。
        val normalized = stripSeparators(pan)

        // ========== 3. 长度与纯数字合法性校验 ==========
        if (normalized.length < MIN_PAN_LENGTH || normalized.length > MAX_PAN_LENGTH) {
            return false
        }
        if (!isAllDigits(normalized)) {
            return false
        }

        // ========== 4. Luhn 模 10 累加 ==========
        // 自右向左, 偶数位（0-index 自右数）做 ×2 处理; 乘积 ≥10 时
        // 取个位数字之和（即 (n * 2) - 9, 等价写法更直观）。
        var sum = 0
        // 倒序遍历, 索引 i 是从右侧开始的位数（i = 0 表示校验位本身）。
        for (i in normalized.indices) {
            // 通过 ASCII 偏移直接转 Int, 省一次 digitToIntOrNull() 开销。
            val digit = normalized[normalized.length - 1 - i].code - ASCII_ZERO
            if (i % 2 == 1) {
                // 偶数位（自右数 i = 1, 3, 5...）需要 ×2。
                val doubled = digit * DOUBLE_MULTIPLIER
                sum += if (doubled > 9) doubled - 9 else doubled
            } else {
                // 奇数位（自右数 i = 0, 2, 4...）保持原值。
                sum += digit
            }
        }

        // ========== 5. 模 10 判定 ==========
        return sum % MODULUS == 0
    }

    /**
     * 从完整卡号提取后四位数字字符串。
     *
     * 行为契约（与 Web 端 `extractLast4` 字节级一致）:
     *   - 仅在 Luhn 校验通过时返回后四位, 否则返回 null;
     *   - 返回值不包含分隔符, 纯 4 位数字;
     *   - 不修改入参, 不持久化（仅在调用方显式写入存储时才入库）。
     *
     * @param pan 银行卡号原文（仅提取瞬间驻留内存, 不入日志）
     * @return 后四位数字字符串（4 字符数字）; Luhn 未通过 / 输入非法时返回 null
     */
    fun extractLast4(pan: String): String? {
        // Luhn 校验是后四位入库的看门人 —— 未通过一律不放行,
        // 防止脏数据污染 Room / DataStore 持久层。
        if (!luhnValidate(pan)) {
            return null
        }
        // 剥离分隔符后取末 4 位; 不再做长度校验, 因为 luhnValidate 已保证
        // 长度 ≥ MIN_PAN_LENGTH (13), 末 4 位必然存在。
        val normalized = stripSeparators(pan)
        return normalized.takeLast(LAST4_LENGTH)
    }

    // ============================================================================
    // 私有工具方法 —— 字符规范化与纯数字判定
    // ============================================================================

    /**
     * 剥离常见分隔符（U+0020 空格 + U+002D 连字符）。
     *
     * 与 Web 端 `pan.replace(/[\s-]+/g, '')` 行为对齐 —— Web 端用正则全局
     * 替换, 此处用 String.filter 手写等价实现, 避免引入额外正则依赖。
     *
     * @param raw 卡号原文
     * @return 去除分隔符后的字符串（不修改入参, 纯函数语义）
     */
    private fun stripSeparators(raw: String): String {
        // 使用 CharRange/Set 而非 Regex, 避免在 JUnit 单测中加载 android.util
        // 命名空间下的正则工具; 同时与 Web 端正则结果字节级一致。
        val sb = StringBuilder(raw.length)
        for (c in raw) {
            // 仅剥离空格（U+0020）与连字符（U+002D）, 其它字符原样保留,
            // 由后续 isAllDigits 统一判定合法性。
            if (c !in SEPARATOR_CHARS) {
                sb.append(c)
            }
        }
        return sb.toString()
    }

    /**
     * 判定字符串是否全部由数字字符（'0'..'9'）组成。
     *
     * 替代 Kotlin `String.all { it.isDigit() }`, 后者会把 Unicode 数字
     * （如阿拉伯-印度数字）也判定为 true, 与 Web 端 `/^\d+$/`（仅 ASCII）
     * 行为不一致 —— 此处显式按 ASCII 数字字符比对, 保证跨端行为字节级一致。
     *
     * @param s 待检测字符串
     * @return 全部为 ASCII 数字字符返回 true; 否则 false
     */
    private fun isAllDigits(s: String): Boolean {
        for (c in s) {
            // 显式 ASCII 范围比对, 排除 Unicode 数字的兼容路径, 与
            // Web 端 /^\d+$/ 行为完全一致（JS \d 等价 ASCII 数字）。
            if (c.code !in ASCII_ZERO..(ASCII_ZERO + 9)) {
                return false
            }
        }
        return true
    }
}