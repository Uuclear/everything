// ============================================================================
// InvestmentAccountRecord 纯函数 —— 投资账户 holdings 解析与校验
// （stage5-finance-v2 / Task 8 / FR-V2-D.1）
// ============================================================================
//
// 任务: stage5-finance-v2 / Task 8 批次（投资账户 + 手动行情）
// 路径: android/app/src/main/java/com/everything/eve/finance/InvestmentAccountRecord.kt
// 作用: 解析 spec FR-V2-D.1 定义的 holdings JSON 字段（账户 envelope 解密后
//       的明文 JSON 段）, 校验合法性, 提供 holdings 列表的不可变内存形态
//       以便 FinanceAggregator 聚合与 UI 渲染; 纯函数, 无副作用,
//       不依赖 Android Framework / Room / Network / Log（org.json 为 JVM 内置）。
//
// holdings JSON 段格式（spec FR-V2-D.1 真理源）：
//   {
//     "holdings": [
//       {"symbol": "AAPL", "shares": 10, "cost_basis_minor": 150000, "currency": "USD"},
//       {"symbol": "0700.HK", "shares": 100, "cost_basis_minor": 380000, "currency": "HKD"}
//     ]
//   }
//
// 双端锁定规则（与 Web web/src/finance/investmentAccountRecord.ts 逐行为锁定
// —— 同一代理双端实现, fixture 双向驱动守护）：
//   1. shares 必须是有限正数（NaN / Infinity / 负数 / 0 一律拒绝）；
//   2. cost_basis_minor 必须为非负整数（>= 0, 不允许小数, 不允许负数）
//      —— 用 Long 承载, 业务量级（亿级份额 × 千倍市盈率 = 万亿）安全；
//   3. currency 必须是 3 个大写字母 ISO 4217 代码（与 RateTable CODE_REGEX 同口径）；
//   4. symbol 是非空字符串, 长度 1..32（股票代码 + 港股后缀 / 美股盘前盘后标识）；
//   5. 整段 holdings 缺失按"空持仓"接受（合法账户可以没有持股）；
//   6. 同一 symbol 在 holdings 数组中可出现多次（不同批次建仓）—— 不做去重,
//      aggregator 求和时按序累加, 与 Web 端按位置求和完全一致。
//
// 关联:
//   - .trae/specs/stage5-finance-v2/spec.md FR-V2-D.1（投资账户子类型）
//   - web/src/finance/investmentAccountRecord.ts（Web 镜像, 同批由同一代理实现）
//   - android/.../finance/FinanceAggregator.kt#netWorth（投资账户市值聚合消费方）
//   - android/.../finance/QuoteTable.kt（行情聚合输入）
// ============================================================================

package com.everything.eve.finance

import org.json.JSONException
import org.json.JSONObject

/**
 * 投资账户单笔持仓（holdings 数组元素）。
 *
 * @property symbol 证券代码（AAPL / 0700.HK / 600519.SH 等, 长度 1..32）
 * @property shares 持仓份额（有限正数; NaN / Infinity / 负数 / 0 一律拒）
 * @property costBasisMinor 建仓成本（minor = 分, 非负整数; 跨币种按各持仓货币计）
 * @property currency 持仓货币 ISO 4217 三字母大写代码
 */
data class HoldingLike(
    val symbol: String,
    val shares: Double,
    val costBasisMinor: Long,
    val currency: String,
)

/**
 * 投资账户记录形态（聚合函数入参 DTO, 解耦 Room Entity）。
 *
 * 设计意图：与 [com.everything.eve.data.finance.entity.FinanceAccountEntity]
 * 解耦, 避免 JUnit 单测被迫构造完整 Room Entity; 聚合函数只关心 kind +
 * currency + holdings + id 四元组。
 *
 * @property id 账户 id（UUID v4）
 * @property kind 账户类型, 严格 = "stock"（与现行 FinanceAccountEntity 枚举一致;
 *   spec 范例写 investment 系勘误, 见 Completion Evidence）
 * @property currency 账户主货币（持仓货币可能与之不同, 多币种聚合由
 *   RateTable 折算, holdings 价值聚合由 QuoteTable + RateTable 双折算）
 * @property holdings 持仓列表（不可变, 可为空）
 * @property archived 归档标记（true 时聚合跳过, 与其它账户同口径）
 */
data class InvestmentAccountRecord(
    val id: String,
    val kind: String,
    val currency: String,
    val holdings: List<HoldingLike>,
    val archived: Boolean,
)

/**
 * InvestmentAccountRecord 纯函数 object 容器（无状态, 全静态方法）。
 *
 * 命名与工程内 FinanceAggregator / RateTables 等纯算法容器保持风格一致。
 */
object InvestmentAccountRecords {

    // ============================================================================
    // 常量区 —— 校验正则与边界（与 Web investmentAccountRecord.ts 完全一致）
    // ============================================================================

    /** 投资账户类型枚举：仅 "stock" 合法（与现行 FinanceAccountEntity.kind 对齐）。 */
    private const val KIND_STOCK = "stock"

    /** ISO 4217 三字母代码粗校验正则（与 RateTable.CODE_REGEX 同口径）。 */
    private val CURRENCY_REGEX = Regex("^[A-Z]{3}$")

    /** 证券代码长度边界（1..32 字符）。 */
    private const val SYMBOL_MIN_LEN = 1
    private const val SYMBOL_MAX_LEN = 32

    // ============================================================================
    // 公开 API —— parseHoldings / encodeHoldings
    // ============================================================================

    /**
     * 解析 spec FR-V2-D.1 holdings JSON 段。
     *
     * 容错与校验策略：
     *   - holdings 缺失或为 null → 返回空列表（合法空仓）；
     *   - holdings 非数组 → 拒绝；
     *   - 元素非对象 → 拒绝；
     *   - 元素内任一字段缺失 / 类型不符 → 拒绝（中文人类可读 message）；
     *   - 同 symbol 多次出现 → 不去重, 按序保留（与 Web 端位置求和锁口径）。
     *
     * @param json 账户明文 JSON（含 holdings 段; 全段或仅 holdings 子段均可）
     * @return 不可变 holdings 列表（空 = 无持仓）
     * @throws IllegalArgumentException 任一校验不通过
     */
    fun parseHoldings(json: String): List<HoldingLike> {
        val root: JSONObject = try {
            JSONObject(json)
        } catch (e: JSONException) {
            throw IllegalArgumentException("账户 JSON 解析失败：根节点不是合法 JSON 对象", e)
        }

        // ========== 1. holdings 段可缺失 → 空仓 ==========
        if (!root.has("holdings") || root.isNull("holdings")) {
            return emptyList()
        }
        val arr = root.optJSONArray("holdings")
            ?: throw IllegalArgumentException(
                "holdings 段非法：必须是 JSON 数组（缺失 / null / 非数组一律视为非法）"
            )

        // ========== 2. 逐元素解析与校验 ==========
        val result = ArrayList<HoldingLike>(arr.length())
        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i)
                ?: throw IllegalArgumentException(
                    "holdings[$i] 维度非法：必须是 JSON 对象"
                )

            // ---- 2.1 symbol ----
            if (!item.has("symbol") || item.isNull("symbol")) {
                throw IllegalArgumentException("holdings[$i].symbol 缺失")
            }
            val symbolRaw = item.opt("symbol")
            if (symbolRaw !is String) {
                throw IllegalArgumentException(
                    "holdings[$i].symbol 类型非法：必须是字符串"
                )
            }
            val symbol = symbolRaw.trim()
            if (symbol.length < SYMBOL_MIN_LEN || symbol.length > SYMBOL_MAX_LEN) {
                throw IllegalArgumentException(
                    "holdings[$i].symbol 长度非法：" +
                        "必须在 $SYMBOL_MIN_LEN..$SYMBOL_MAX_LEN 字符之间（实际 = ${symbol.length}）"
                )
            }

            // ---- 2.2 shares ----
            if (!item.has("shares") || item.isNull("shares")) {
                throw IllegalArgumentException("holdings[$i].shares 缺失")
            }
            val sharesRaw = item.opt("shares")
            if (sharesRaw !is Number) {
                throw IllegalArgumentException(
                    "holdings[$i].shares 类型非法：必须是数字（不允许字符串 / null / 布尔）"
                )
            }
            val shares = sharesRaw.toDouble()
            if (!shares.isFinite() || shares <= 0.0) {
                throw IllegalArgumentException(
                    "holdings[$i].shares 值非法：必须是有限正数（实际 = $sharesRaw）"
                )
            }

            // ---- 2.3 cost_basis_minor ----
            if (!item.has("cost_basis_minor") || item.isNull("cost_basis_minor")) {
                throw IllegalArgumentException("holdings[$i].cost_basis_minor 缺失")
            }
            val costRaw = item.opt("cost_basis_minor")
            if (costRaw !is Number) {
                throw IllegalArgumentException(
                    "holdings[$i].cost_basis_minor 类型非法：必须是数字"
                )
            }
            val costDouble = costRaw.toDouble()
            if (!costDouble.isFinite() || costDouble < 0.0) {
                throw IllegalArgumentException(
                    "holdings[$i].cost_basis_minor 值非法：必须是非负有限数（实际 = $costRaw）"
                )
            }
            if (costDouble != kotlin.math.floor(costDouble)) {
                throw IllegalArgumentException(
                    "holdings[$i].cost_basis_minor 必须为整数（不允许小数）"
                )
            }
            val costBasisMinor = costRaw.toLong()
            // Long 上溢防护：cost_basis_minor 必须落在 [0, Long.MAX_VALUE]。
            // 业务量级（亿级 = 1e8）远小于 Long 上界, 仅需负值与小数防御。
            // toLong() 自身已处理浮点超界, 此处不额外抛。

            // ---- 2.4 currency ----
            if (!item.has("currency") || item.isNull("currency")) {
                throw IllegalArgumentException("holdings[$i].currency 缺失")
            }
            val currencyRaw = item.opt("currency")
            if (currencyRaw !is String) {
                throw IllegalArgumentException(
                    "holdings[$i].currency 类型非法：必须是字符串"
                )
            }
            val currency = currencyRaw.trim()
            if (!CURRENCY_REGEX.matches(currency)) {
                throw IllegalArgumentException(
                    "holdings[$i].currency 非法：必须是 3 个大写字母 ISO 4217 代码（实际 = $currency）"
                )
            }

            result.add(
                HoldingLike(
                    symbol = symbol,
                    shares = shares,
                    costBasisMinor = costBasisMinor,
                    currency = currency,
                )
            )
        }

        return result.toList()
    }

    /**
     * 将 holdings 列表编码回 JSON 段（用于 records 通道密封前的明文拼装）。
     *
     * 编码格式与 spec FR-V2-D.1 严格对齐：
     *   {
     *     "holdings": [
     *       {"symbol": "...", "shares": ..., "cost_basis_minor": ..., "currency": "..."}
     *     ]
     *   }
     *
     * 空仓列表也输出 holdings=[]（不省略键, 与 parseHoldings 缺失分支兼容）。
     *
     * @param holdings 待编码的持仓列表（空 = 空仓）
     * @return JSON 段字符串（org.json 序列化, 不含缩进）
     */
    fun encodeHoldings(holdings: List<HoldingLike>): String {
        val root = JSONObject()
        val arr = org.json.JSONArray()
        for (h in holdings) {
            val obj = JSONObject()
            obj.put("symbol", h.symbol)
            obj.put("shares", h.shares)
            obj.put("cost_basis_minor", h.costBasisMinor)
            obj.put("currency", h.currency)
            arr.put(obj)
        }
        root.put("holdings", arr)
        return root.toString()
    }

    /**
     * 构造一个投资账户记录（聚合函数入参 DTO 工厂）。
     *
     * 校验口径：
     *   - kind 必须为 "stock"（其它枚举值不属于投资账户范围, 一律拒）；
     *   - currency 必须是 3 个大写字母 ISO 4217 代码；
     *   - 列表可空（合法空仓）, 不做 holdings 字段深校验（解析校验在
     *     [parseHoldings] 处完成, 本工厂仅做账户壳校验）。
     *
     * @throws IllegalArgumentException kind / currency 非法
     */
    fun build(
        id: String,
        kind: String,
        currency: String,
        holdings: List<HoldingLike>,
        archived: Boolean,
    ): InvestmentAccountRecord {
        if (kind != KIND_STOCK) {
            throw IllegalArgumentException(
                "投资账户 kind 非法：必须是 \"$KIND_STOCK\"（实际 = $kind）"
            )
        }
        if (!CURRENCY_REGEX.matches(currency)) {
            throw IllegalArgumentException(
                "投资账户 currency 非法：必须是 3 个大写字母 ISO 4217 代码（实际 = $currency）"
            )
        }
        if (id.isBlank()) {
            throw IllegalArgumentException("投资账户 id 不能为空")
        }
        return InvestmentAccountRecord(
            id = id,
            kind = kind,
            currency = currency,
            holdings = holdings.toList(),
            archived = archived,
        )
    }
}