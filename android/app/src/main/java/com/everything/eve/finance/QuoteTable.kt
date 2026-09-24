// ============================================================================
// QuoteTable 纯函数 —— 手动行情包解析与价格查询（stage5-finance-v2 / Task 8 / FR-V2-D.2）
// ============================================================================
//
// 任务: stage5-finance-v2 / Task 8 批次（投资账户 + 手动行情）
// 路径: android/app/src/main/java/com/everything/eve/finance/QuoteTable.kt
// 作用: 解析 spec FR-V2-D.2 定义的"行情 RSS / JSON 包"（HTTP GET 自托管端点, 解密
//       后的明文 JSON）, 校验合法性, 提供不可变内存形态以便 FinanceAggregator
//       聚合投资账户市值; 纯函数, 无副作用, 不依赖 Android Framework / Room /
//       Network / Log（org.json 与 BigDecimal 均为 JVM 内置, JUnit 可直接跑）。
//
// 行情包格式（spec FR-V2-D.2 真理源）：
//   {
//     "version": 1,
//     "ts": 1735689600000,
//     "base": "CNY",
//     "quotes": [
//       {"symbol": "AAPL", "price_minor": 18500, "currency": "USD", "ts": 1735689600000},
//       {"symbol": "0700.HK", "price_minor": 38000, "currency": "HKD", "ts": 1735689600000}
//     ]
//   }
//
// 双端锁定规则（与 Web design/src/finance/quoteTable.ts 逐行为锁定 —— 同一代理
// 双端实现, fixture 双向驱动守护）：
//   1. symbol 非空字符串, 长度 1..32（与 HoldingLike 锁同口径, 便于跨表匹配）；
//   2. price_minor 必须为非负整数（>= 0; 价格可为零但不允许负, NaN / Infinity /
//      小数 / 字符串 / null 一律拒绝）；
//   3. currency 必须是 3 个大写字母 ISO 4217 代码（与 RateTable / HoldingLike 同口径）；
//   4. ts 必须是整数毫秒（>= 0, 不允许小数 / 负数 / NaN）；
//   5. 包顶层 ts：必填、整数毫秒、>= 0（行情包整体生效时刻, 与单 quote ts 可不同）；
//   6. base：可选；显式给出时必须满足 CURRENCY_REGEX（用于跨币种聚合时声明基准币,
//      不强制一致, aggregator 会按 holdings 实际币种走 RateTable 折算）；
//   7. 同 symbol 多次出现：取数组最后一条覆盖（同 Web 端 last-write-wins 锁口径）。
//
// 关联:
//   - .trae/specs/stage5-finance-v2/spec.md FR-V2-D.2（手动行情同步）
//   - web/src/finance/quoteTable.ts（Web 镜像, 同批由同一代理实现）
//   - android/.../finance/InvestmentAccountRecord.kt（投资账户持仓消费方）
//   - android/.../finance/FinanceAggregator.kt#netWorth（投资账户市值聚合消费方）
//   - android/.../finance/RateTable.kt（多币种折算消费方）
// ============================================================================

package com.everything.eve.finance

import org.json.JSONException
import org.json.JSONObject

/**
 * 行情包单条报价。
 *
 * @property symbol 证券代码（与 [HoldingLike.symbol] 锁同口径, 1..32 字符）
 * @property priceMinor 报价（minor = 分, 非负整数; AAPL 185.00 USD → price_minor=18500）
 * @property currency 报价币种（ISO 4217 三字母大写代码）
 * @property ts 该条报价生效时刻（Unix 毫秒）
 */
data class Quote(
    val symbol: String,
    val priceMinor: Long,
    val currency: String,
    val ts: Long,
)

/**
 * 手动行情包（解密后内存形态）。
 *
 * @property ts 行情包整体生效时刻（Unix 毫秒；与单 quote.ts 可不同）
 * @property quotes 报价映射（key = symbol; 同 symbol 多次出现时取数组最后一条）
 * @property base 基准币（可选；null 表示包未声明基准, aggregator 按 holdings 实际
 *   币种折算）
 */
data class QuoteTable(
    val ts: Long,
    val quotes: Map<String, Quote>,
    val base: String? = null,
)

/**
 * QuoteTable 纯函数 object 容器（无状态, 全静态方法）。
 *
 * 命名与工程内 FinanceAggregator / RateTables / InvestmentAccountRecords 等纯算法
 * 容器保持风格一致。
 */
object QuoteTables {

    // ============================================================================
    // 常量区 —— 校验正则与边界（与 Web quoteTable.ts 完全一致）
    // ============================================================================

    /** ISO 4217 三字母代码粗校验正则（与 RateTable / HoldingLike 同口径）。 */
    private val CURRENCY_REGEX = Regex("^[A-Z]{3}$")

    /** 证券代码长度边界（1..32 字符，与 HoldingLike 锁同口径）。 */
    private const val SYMBOL_MIN_LEN = 1
    private const val SYMBOL_MAX_LEN = 32

    // ============================================================================
    // 公开 API —— parse / priceMinorOf / encodeQuoteTable
    // ============================================================================

    /**
     * 解析 spec FR-V2-D.2 行情包明文 JSON。
     *
     * 容错与校验策略：
     *   - version 缺失按 1 接受；显式给出且不为 1 则拒绝（前向兼容守门）；
     *   - ts 必填、整数、>= 0;
     *   - quotes 必填数组、至少 1 条（空数组视为非法 —— 与 RateTable "至少 1 汇率"
     *     同口径, 行情包不允许空载同步）；
     *   - base 可选；显式给出时必须满足 CURRENCY_REGEX;
     *   - 每条 quote.symbol / price_minor / currency / ts 全部按上文锁口径校验；
     *   - 同 symbol 多次出现：取数组最后一条覆盖（last-write-wins）。
     *
     * @param json 行情包明文 JSON 字符串
     * @return 解析后的 [QuoteTable]
     * @throws IllegalArgumentException 任一校验不通过（message 为中文人类可读说明）
     */
    fun parse(json: String): QuoteTable {
        val root: JSONObject = try {
            JSONObject(json)
        } catch (e: JSONException) {
            throw IllegalArgumentException("行情包解析失败：根节点不是合法 JSON 对象", e)
        }

        // ========== 1. version：缺失按 1 接受, 显式异值拒绝 ==========
        if (root.has("version") && !root.isNull("version")) {
            val version = root.opt("version")
            if (version !is Number || version.toInt() != 1) {
                throw IllegalArgumentException(
                    "行情包 version 不受支持：$version（当前仅支持版本 1）"
                )
            }
        }

        // ========== 2. ts：必填、整数、>= 0 ==========
        val tsRaw = if (root.has("ts") && !root.isNull("ts")) {
            root.opt("ts")
        } else {
            null
        }
        if (tsRaw !is Number || !tsRaw.toDouble().isFinite() || tsRaw.toDouble() < 0.0) {
            throw IllegalArgumentException(
                "行情包 ts 缺失或非法（必须为 >= 0 的 Unix 毫秒整数）"
            )
        }
        val ts = tsRaw.toLong()
        if (tsRaw.toDouble() != ts.toDouble()) {
            throw IllegalArgumentException(
                "行情包 ts 必须为整数毫秒（不允许小数）"
            )
        }

        // ========== 3. base：可选；显式给出时必须满足 CURRENCY_REGEX ==========
        val base: String? = if (root.has("base") && !root.isNull("base")) {
            val bRaw = root.opt("base")
            if (bRaw !is String) {
                throw IllegalArgumentException(
                    "行情包 base 类型非法：必须是字符串"
                )
            }
            val b = bRaw.trim()
            if (!CURRENCY_REGEX.matches(b)) {
                throw IllegalArgumentException(
                    "行情包 base 非法：必须是 3 个大写字母 ISO 4217 代码（实际 = $b）"
                )
            }
            b
        } else {
            null
        }

        // ========== 4. quotes：必填数组、至少 1 条 ==========
        val arr = root.optJSONArray("quotes")
            ?: throw IllegalArgumentException("行情包 quotes 缺失或非法（必须为数组）")
        if (arr.length() == 0) {
            throw IllegalArgumentException("行情包 quotes 至少需要 1 条报价")
        }

        // ========== 5. 逐条解析与校验 ==========
        // 同 symbol 多次出现：取数组最后一条覆盖（last-write-wins）。
        val quotes = LinkedHashMap<String, Quote>(arr.length())
        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i)
                ?: throw IllegalArgumentException(
                    "quotes[$i] 维度非法：必须是 JSON 对象"
                )

            // ---- 5.1 symbol ----
            if (!item.has("symbol") || item.isNull("symbol")) {
                throw IllegalArgumentException("quotes[$i].symbol 缺失")
            }
            val symbolRaw = item.opt("symbol")
            if (symbolRaw !is String) {
                throw IllegalArgumentException(
                    "quotes[$i].symbol 类型非法：必须是字符串"
                )
            }
            val symbol = symbolRaw.trim()
            if (symbol.length < SYMBOL_MIN_LEN || symbol.length > SYMBOL_MAX_LEN) {
                throw IllegalArgumentException(
                    "quotes[$i].symbol 长度非法：必须在 $SYMBOL_MIN_LEN..$SYMBOL_MAX_LEN " +
                        "字符之间（实际 = ${symbol.length}）"
                )
            }

            // ---- 5.2 price_minor ----
            if (!item.has("price_minor") || item.isNull("price_minor")) {
                throw IllegalArgumentException("quotes[$i].price_minor 缺失")
            }
            val priceRaw = item.opt("price_minor")
            if (priceRaw !is Number) {
                throw IllegalArgumentException(
                    "quotes[$i].price_minor 类型非法：必须是数字"
                )
            }
            val priceDouble = priceRaw.toDouble()
            if (!priceDouble.isFinite() || priceDouble < 0.0) {
                throw IllegalArgumentException(
                    "quotes[$i].price_minor 值非法：必须是非负有限数（实际 = $priceRaw）"
                )
            }
            if (priceDouble != kotlin.math.floor(priceDouble)) {
                throw IllegalArgumentException(
                    "quotes[$i].price_minor 必须为整数（不允许小数）"
                )
            }
            val priceMinor = priceRaw.toLong()

            // ---- 5.3 currency ----
            if (!item.has("currency") || item.isNull("currency")) {
                throw IllegalArgumentException("quotes[$i].currency 缺失")
            }
            val currencyRaw = item.opt("currency")
            if (currencyRaw !is String) {
                throw IllegalArgumentException(
                    "quotes[$i].currency 类型非法：必须是字符串"
                )
            }
            val currency = currencyRaw.trim()
            if (!CURRENCY_REGEX.matches(currency)) {
                throw IllegalArgumentException(
                    "quotes[$i].currency 非法：必须是 3 个大写字母 ISO 4217 " +
                        "代码（实际 = $currency）"
                )
            }

            // ---- 5.4 ts ----
            if (!item.has("ts") || item.isNull("ts")) {
                throw IllegalArgumentException("quotes[$i].ts 缺失")
            }
            val qTsRaw = item.opt("ts")
            if (qTsRaw !is Number) {
                throw IllegalArgumentException(
                    "quotes[$i].ts 类型非法：必须是数字"
                )
            }
            val qTsDouble = qTsRaw.toDouble()
            if (!qTsDouble.isFinite() || qTsDouble < 0.0) {
                throw IllegalArgumentException(
                    "quotes[$i].ts 值非法：必须是非负有限数（实际 = $qTsRaw）"
                )
            }
            if (qTsDouble != kotlin.math.floor(qTsDouble)) {
                throw IllegalArgumentException(
                    "quotes[$i].ts 必须为整数（不允许小数）"
                )
            }
            val qTs = qTsRaw.toLong()

            quotes[symbol] = Quote(
                symbol = symbol,
                priceMinor = priceMinor,
                currency = currency,
                ts = qTs,
            )
        }

        return QuoteTable(ts = ts, quotes = quotes, base = base)
    }

    /**
     * 查某 symbol 的报价（minor = 分）。
     *
     * 找不到 / symbol 为空 / symbol 超长时一律返回 null（不抛）。
     * aggregator 在缺价时按 0 计值（与缺汇率时面值 1:1 降级同口径, 行为可预期）。
     *
     * @param symbol 证券代码（聚合时直接传入 HoldingLike.symbol）
     * @param table 已解析的行情包
     * @return 该 symbol 的 price_minor；缺价时返回 null
     */
    fun priceMinorOf(symbol: String, table: QuoteTable): Long? {
        if (symbol.length < SYMBOL_MIN_LEN || symbol.length > SYMBOL_MAX_LEN) return null
        return table.quotes[symbol]?.priceMinor
    }

    /**
     * 将行情包编码回 JSON 段（用于 records 通道密封前的明文拼装）。
     *
     * 编码格式与 spec FR-V2-D.2 严格对齐（同 symbol 多次出现按入参 Map 顺序输出；
     * Map 已通过 LinkedHashMap 保持 last-write-wins 顺序）。
     *
     * @param table 待编码的行情包
     * @return JSON 字符串（org.json 序列化, 不含缩进）
     */
    fun encodeQuoteTable(table: QuoteTable): String {
        val root = JSONObject()
        root.put("version", 1)
        root.put("ts", table.ts)
        if (table.base != null) root.put("base", table.base)
        val arr = org.json.JSONArray()
        for ((_, q) in table.quotes) {
            val obj = JSONObject()
            obj.put("symbol", q.symbol)
            obj.put("price_minor", q.priceMinor)
            obj.put("currency", q.currency)
            obj.put("ts", q.ts)
            arr.put(obj)
        }
        root.put("quotes", arr)
        return root.toString()
    }
}