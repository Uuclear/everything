// ============================================================================
// RateTable 纯函数 —— 财务模块 Android 端离线汇率包解析与多币种折算（stage5-finance-v2 / B5 / FR-V2-C.2、FR-V2-C.3）
// ============================================================================
//
// 任务: stage5-finance-v2 / B5 批次（多币种汇率纯函数层）
// 路径: android/app/src/main/java/com/everything/eve/finance/RateTable.kt
// 作用: 解析 spec FR-V2-C.2 定义的加密离线汇率包（解密后的明文 JSON），并按
//       "分"（minor = 最小货币单位）整数口径完成跨币种折算；纯函数, 无副作用,
//       不依赖 Android Framework / Room / Network / Log（org.json 与 BigDecimal
//       均为 JVM 内置能力, JUnit 单测可直接跑, 无需 Robolectric）。
//
// 汇率包格式（spec FR-V2-C.2 真理源）:
//   {
//     "version": 1,
//     "effective_ts": 1735689600000,
//     "rates": {
//       "USD/CNY": 7.25,
//       "EUR/CNY": 7.85,
//       "JPY/CNY": 0.048,
//       "HKD/CNY": 0.93
//     }
//   }
//
// 双端锁定规则（与 Web web/src/finance/rateTable.ts 逐行为锁定 —— 同一代理双端实现）:
//   1. 金额全程 minor Long（分）出入, 内部 Android 用 BigDecimal / Web 用
//      Number 乘除后取整; 业务量级（千亿元 = 1e13 分）双端均安全
//      （Web 侧 1e13 < 2^53 ≈ 9.007e15, 乘汇率后仍不越界）；
//   2. 舍入规则双端统一为"绝对值先取整、再恢复符号":
//      - Android BigDecimal setScale(0, RoundingMode.HALF_UP) 对绝对值舍入,
//        随后对负数取 negate；
//      - Web Math.round 对绝对值舍入后恢复符号。
//      对非负数 HALF_UP 与 Math.round 等价（x.5 均向上取整）; 二者差异仅在负数
//      （HALF_UP 远离零, Math.round 向正无穷）—— 绝对值规则下该差异被消除,
//      例如 −2 分 × 7.25 = −14.5, 双端均得 −15（朴素 Math.round(-14.5) 会得 −14）；
//   3. 正反向: 优先命中 rates["FROM/TO"]（乘法）, 其次 rates["TO/FROM"]
//      （除法, 用反向汇率除回）, 都缺失则 convert 返回 null,
//      convertOrIdentity 退回原值（aggregator 缺汇率时的面值 1:1 降级口径）；
//   4. Long × 汇率在极端（万亿级元以上）金额下可能溢出, 业务量级内安全,
//      本期不额外防御（与 Web Number 精度边界同口径, 见注释 1）。
//
// 关联:
//   - .trae/specs/stage5-finance-v2/spec.md FR-V2-C.2（汇率包格式真理源）
//   - .trae/specs/stage5-finance-v2/spec.md FR-V2-C.3（资产看板多币种折算）
//   - web/src/finance/rateTable.ts（Web 镜像版本, 同批由同一代理实现）
//   - android/.../finance/FinanceAggregator.kt#netWorth / monthlyReport（唯一消费方）
// ============================================================================

package com.everything.eve.finance

import org.json.JSONException
import org.json.JSONObject
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * 离线汇率表（汇率包解密后的内存形态）。
 *
 * @property effectiveTs 汇率包生效时刻（Unix 毫秒；与 spec 包字段 effective_ts 对应）
 * @property rates 汇率映射；key 形如 "USD/CNY", value = 1 单位 from 可兑换多少 to
 */
data class RateTable(
    val effectiveTs: Long,
    val rates: Map<String, Double>,
)

/**
 * RateTable 纯函数 object 容器（无状态, 全静态方法）。
 *
 * 命名与工程内 FinanceAggregator / NextCardFiring 等纯算法容器保持风格一致。
 */
object RateTables {

    // ============================================================================
    // 常量区 —— 校验正则（与 Web rateTable.ts 完全一致）
    // ============================================================================

    /** 汇率键正则：3 个大写字母 / 3 个大写字母, 如 "USD/CNY"。 */
    private val PAIR_REGEX = Regex("^[A-Z]{3}/[A-Z]{3}$")

    /** ISO 4217 三字母代码粗校验正则。 */
    private val CODE_REGEX = Regex("^[A-Z]{3}$")

    // ============================================================================
    // 公开 API —— parse / convert / convertOrIdentity
    // ============================================================================

    /**
     * 解析 spec FR-V2-C.2 离线汇率包明文 JSON。
     *
     * 容错与校验策略：
     *   - version 缺失按 1 接受；显式给出且不为 1 则拒绝（前向兼容守门）；
     *   - effective_ts 必须存在、为数字、整数且 >= 0, 否则 [IllegalArgumentException]；
     *   - rates 必须为对象且至少 1 条；
     *   - 每个 key 必须匹配 ^[A-Z]{3}/[A-Z]{3}$ 且 from ≠ to；
     *   - 每个 value 必须为有限数且 > 0（NaN / Infinity / 字符串 / null 一律拒绝）。
     *
     * @param json 汇率包明文 JSON 字符串
     * @return 解析后的 [RateTable]
     * @throws IllegalArgumentException 任一校验不通过（message 为中文人类可读说明）
     */
    fun parse(json: String): RateTable {
        val root: JSONObject = try {
            JSONObject(json)
        } catch (e: JSONException) {
            throw IllegalArgumentException("汇率包解析失败：根节点不是合法 JSON 对象", e)
        }

        // ========== 1. version：缺失按 1 接受, 显式异值拒绝 ==========
        if (root.has("version") && !root.isNull("version")) {
            val version = root.opt("version")
            if (version !is Number || version.toInt() != 1) {
                throw IllegalArgumentException(
                    "汇率包 version 不受支持：$version（当前仅支持版本 1）"
                )
            }
        }

        // ========== 2. effective_ts：必填、整数、>= 0 ==========
        val effRaw = if (root.has("effective_ts") && !root.isNull("effective_ts")) {
            root.opt("effective_ts")
        } else {
            null
        }
        if (effRaw !is Number || !effRaw.toDouble().isFinite() || effRaw.toDouble() < 0.0) {
            throw IllegalArgumentException(
                "汇率包 effective_ts 缺失或非法（必须为 >= 0 的 Unix 毫秒整数）"
            )
        }
        val effectiveTs = effRaw.toLong()
        if (effRaw.toDouble() != effectiveTs.toDouble()) {
            throw IllegalArgumentException(
                "汇率包 effective_ts 必须为整数毫秒（不允许小数）"
            )
        }

        // ========== 3. rates：必填对象、至少 1 条 ==========
        val ratesObj = root.optJSONObject("rates")
            ?: throw IllegalArgumentException("汇率包 rates 缺失或非法（必须为对象）")
        if (ratesObj.length() == 0) {
            throw IllegalArgumentException("汇率包 rates 至少需要 1 条汇率")
        }

        // ========== 4. 逐条校验 key 与 value ==========
        val rates = LinkedHashMap<String, Double>(ratesObj.length())
        val keys = ratesObj.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            // key 正则 + from ≠ to。
            if (!PAIR_REGEX.matches(key)) {
                throw IllegalArgumentException(
                    "汇率包汇率键非法：$key（必须形如 USD/CNY 的三字母代码对）"
                )
            }
            val from = key.substring(0, 3)
            val to = key.substring(4, 7)
            if (from == to) {
                throw IllegalArgumentException(
                    "汇率包汇率键非法：$key（源货币与目标货币不能相同）"
                )
            }
            val valueRaw = ratesObj.opt(key)
            if (valueRaw !is Number) {
                throw IllegalArgumentException(
                    "汇率包 $key 的汇率非法：必须为数字（不允许字符串 / null / 布尔）"
                )
            }
            val rate = valueRaw.toDouble()
            if (!rate.isFinite() || rate <= 0.0) {
                throw IllegalArgumentException(
                    "汇率包 $key 的汇率非法：必须为有限正数（实际 = $valueRaw）"
                )
            }
            rates[key] = rate
        }

        return RateTable(effectiveTs = effectiveTs, rates = rates)
    }

    /**
     * 按汇率表把 minor 金额从 [from] 折算到 [to]。
     *
     * 解析顺序：
     *   1. from / to 任一不是合法三字母代码 → 返回 null；
     *   2. from == to（合法自交叉）→ 原值返回（与汇率表内容无关, 空表也成立）；
     *   3. rates["FROM/TO"] 命中 → 金额绝对值 × 汇率, HALF_UP 取整后恢复符号；
     *   4. rates["TO/FROM"] 命中（反向）→ 金额绝对值 ÷ 反向汇率, 同口径取整；
     *   5. 双向均缺失 → null。
     *
     * @param amountMinor 源币种 minor 金额（分；允许负数, 如负债）
     * @param from 源币种 ISO 4217 三字母代码
     * @param to 目标币种 ISO 4217 三字母代码
     * @param table 已解析的汇率表
     * @return 目标币种 minor 金额；无法折算时返回 null
     */
    fun convert(amountMinor: Long, from: String, to: String, table: RateTable): Long? {
        // 非法币种代码不做任何折算尝试。
        if (!CODE_REGEX.matches(from) || !CODE_REGEX.matches(to)) return null
        // 自交叉恒等 —— 不读表, 空汇率表也成立。
        if (from == to) return amountMinor

        // 双端舍入锁定：绝对值参与乘除取整, 最后恢复符号（见文件头注释 2）。
        val negative = amountMinor < 0L
        val absMinor = if (negative) -amountMinor else amountMinor

        val forward = table.rates["$from/$to"]
        val absResult: Long = if (forward != null) {
            multiplyHalfUp(absMinor, forward)
        } else {
            val reverse = table.rates["$to/$from"] ?: return null
            divideHalfUp(absMinor, reverse)
        }
        return if (negative) -absResult else absResult
    }

    /**
     * [convert] 的面值降级版本：无法折算（缺汇率 / 非法代码对）时原样返回金额。
     *
     * 业务语义：aggregator 在汇率表缺失某币种时按 1:1 面值口径计入看板,
     * 保证总量不因离线汇率包不全而丢条目（spec FR-V2-C.3 降级约定）。
     */
    fun convertOrIdentity(amountMinor: Long, from: String, to: String, table: RateTable): Long {
        return convert(amountMinor, from, to, table) ?: amountMinor
    }

    // ============================================================================
    // 私有工具方法 —— BigDecimal 绝对值 HALF_UP 舍入（与 Web Math.round 锁口径）
    // ============================================================================

    /**
     * 绝对值乘法：amountAbsMinor × rate, 0 位小数 HALF_UP 取整。
     *
     * 入参约定为非负金额（符号在 [convert] 层统一恢复）。
     * 注：BigDecimal(Double) 承载二进制浮点真值, 乘后取整与 Web Number 乘法 +
     * Math.round 在业务选取的汇率/金额组合下结果一致（fixture 全量双端驱动守护）。
     */
    private fun multiplyHalfUp(amountAbsMinor: Long, rate: Double): Long {
        return BigDecimal(amountAbsMinor)
            .multiply(BigDecimal(rate))
            .setScale(0, RoundingMode.HALF_UP)
            .toLong()
    }

    /**
     * 绝对值反向除法：amountAbsMinor ÷ reverseRate, 0 位小数 HALF_UP 取整。
     *
     * BigDecimal.divide 直接给定结果 scale=0 + HALF_UP, 对除不尽的情形也不会抛
     * ArithmeticException（等价于"先全精度求商再按 HALF_UP 截到个位"）。
     */
    private fun divideHalfUp(amountAbsMinor: Long, reverseRate: Double): Long {
        return BigDecimal(amountAbsMinor)
            .divide(BigDecimal(reverseRate), 0, RoundingMode.HALF_UP)
            .toLong()
    }
}
