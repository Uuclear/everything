// ============================================================================
// BudgetEnforcer —— 财务 v2 B6 预算硬约束 / 超支拦截双端纯函数基础层（Android）
// ============================================================================
//
// 任务: stage5-finance-v2 / Task 6（预算硬约束 + 超支拦截）第一批纯函数
// 路径: android/app/src/main/java/com/everything/eve/finance/BudgetEnforcer.kt
// 作用: 保存（或编辑）一笔支出流水前，按候选预算实时计算预计累计占预算
//       比例，输出三档结果 OK / WARNING / BLOCK；异币流水经 B5 离线汇率表
//       RateTables.convert 折算到预算币种。纯函数 object，不依赖 Room /
//       Network / DataStore / Android Framework / Log，JVM 单测直接运行。
//
// 铁律口径:
//   1. 金额一律 minor（分）Long 整数算术：decimal 元字符串由 parseCents
//      拆整数段 / 小数段拼接（整数字符串直接乘 100，小数一至二位右侧补
//      零），禁止 Double 与除法浮点；占比为 Long 整除向下取整；
//   2. 月 / 周 / 年分桶一律 CST（UTC+8，固定 ZoneOffset.ofHours(8)，
//      中国无夏令时）取日历分量，与 FinanceAggregator.yearMonthOf 同口径；
//   3. 预算有效期 startTs / endTs 为双闭区间（含两端），流水发生时刻在
//      有效期外该预算完全不命中；桶区间为半开 [bucketStart, bucketEnd)，
//      custom 统一为 [startTs, endTs + 1) 以保证 endTs 当刻包含；
//   4. weekly 不以周一为锚，而以预算 startTs 所在 CST 日期零点为 epoch，
//      每 7 天一个滚动桶（与 Web budgetEnforcer.ts 逐分支锁定）；
//   5. 缺汇率保守放行：本笔无法折算到预算币种时整个预算跳过，不误拦；
//   6. 编辑场景：existing 中与本笔同 id 的旧记录排除，不重复累计；
//   7. 多预算命中取最严重（BLOCK 大于 WARNING 大于 OK），同档取 usedPct
//      更大，再同则取 budgetId 字典序最小（双端稳定性兜底）。
//
// 关联:
//   - web/src/finance/budgetEnforcer.ts（Web 镜像，bigint 口径逐函数对应）
//   - com.everything.eve.finance.RateTables（B5 折算纯函数，本文件唯一外部依赖）
//   - android/.../finance/__fixtures__/budget-enforcer-cases.json（双端共享 fixture）
// ============================================================================

package com.everything.eve.finance

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * 待存 / 已存支出流水最小形态（与持久化流水模型解耦）。
 *
 * 仅承载预算判定需要的六个字段；[amountMinor] 为 decimal 元字符串
 * （如 "100.00"），由 BudgetEnforcer 内部按 minor 分整数解析，与
 * v2 record 的金额字段同口径。
 *
 * @property id 流水 id；编辑既有流水时 existing 中同 id 旧记录会被排除
 * @property kind 流水类型；仅 "expense" 参与预算判定
 * @property amountMinor 流水金额 decimal 元字符串
 * @property category 流水分类；预算 "all" 匹配任意分类
 * @property currency 流水币种（ISO 4217 三字母代码）
 * @property occurredAt 流水发生时刻（Unix 毫秒；所属周期桶以此为锚）
 */
data class BudgetTxLike(
    val id: String,
    val kind: String,
    val amountMinor: String,
    val category: String,
    val currency: String,
    val occurredAt: Long,
)

/**
 * 单笔支出针对命中预算的三档判定级别。
 */
enum class BudgetLevel {
    /** 未达预警阈值。 */
    OK,

    /** 达到预警阈值但未达硬拦截阈值。 */
    WARNING,

    /** 达到硬拦截阈值（保存前需上层拦截 / 确认）。 */
    BLOCK,
}

/**
 * 单笔支出针对某条命中预算的判定结果。
 *
 * 金额字段全部为预算币种 minor 分整数：
 *   - projected = spent + incoming；
 *   - usedPct = projected * 100 / limit，Long 整除向下取整（80 表示 80%）。
 *
 * @property level 三档级别
 * @property budgetId 命中预算 id；无任何命中时为 null
 * @property category 命中预算的分类匹配值（可能为特殊值 "all"）；无命中为空串
 * @property currency 命中预算币种；无命中为空串
 * @property spentMinor 同周期桶内既有支出折算合计（已排除编辑自身）
 * @property incomingMinor 本笔待存支出折算到预算币种的金额
 * @property projectedMinor 预计累计 = spentMinor + incomingMinor
 * @property limitMinor 预算额度（minor 分）
 * @property usedPct 预计累计占预算百分比（整数，向下取整）
 * @property thresholdPct 命中档阈值百分数；OK 时填预警阈值
 */
data class BudgetCheckResult(
    val level: BudgetLevel,
    val budgetId: String?,
    val category: String,
    val currency: String,
    val spentMinor: Long,
    val incomingMinor: Long,
    val projectedMinor: Long,
    val limitMinor: Long,
    val usedPct: Int,
    val thresholdPct: Int,
) {
    companion object {
        /** 无任何命中预算时的统一空结果（level=OK，金额全 0，标识为空）。 */
        val OK_EMPTY: BudgetCheckResult = BudgetCheckResult(
            level = BudgetLevel.OK,
            budgetId = null,
            category = "",
            currency = "",
            spentMinor = 0L,
            incomingMinor = 0L,
            projectedMinor = 0L,
            limitMinor = 0L,
            usedPct = 0,
            thresholdPct = 0,
        )
    }
}

/**
 * B6 预算硬约束纯函数容器（无状态，全静态方法）。
 */
object BudgetEnforcer {

    /** 分桶口径时区：Asia/Shanghai = UTC+8（中国无夏令时，全年恒定）。 */
    private val CST: ZoneOffset = ZoneOffset.ofHours(8)

    /** 一天的毫秒数（周桶推导用）。 */
    private const val DAY_MS: Long = 86_400_000L

    /** 一周的毫秒数。 */
    private const val WEEK_MS: Long = 7L * DAY_MS

    /** decimal 元字符串合法形态：非负整数部分加最多两位小数。 */
    private val AMOUNT_REGEX = Regex("""^(\d+)(?:\.(\d{1,2}))?$""")

    // ========================================================================
    // 金额解析
    // ========================================================================

    /**
     * decimal 元字符串解析为 minor 分整数（双端同口径，禁止浮点）。
     *
     * 整数字符串直接乘 100；含一至两位小数时把小数段右侧补零后按整数
     * 拼接（"12.3" 的 3 表示 30 分）。非法字符串（空串 / 符号 / 字母 /
     * 超过两位小数等）一律返回 0，不抛异常。
     */
    private fun parseCents(decimal: String): Long {
        val match = AMOUNT_REGEX.matchEntire(decimal) ?: return 0L
        val whole = match.groupValues[1].toLongOrNull() ?: return 0L
        val fracPart = match.groupValues[2]
        val frac = when (fracPart.length) {
            0 -> 0L
            1 -> (fracPart + "0").toLong()
            else -> fracPart.toLong()
        }
        return whole * 100L + frac
    }

    // ========================================================================
    // 周期分桶
    // ========================================================================

    /**
     * 返回 [txTs] 所属预算周期桶 [bucketStartMs, bucketEndMs)（两端均为
     * Unix 毫秒，末点为开区间）。
     *
     * 先做有效期判定：[txTs] 不在 [[startTs], [endTs]] 双闭区间内直接
     * 返回 null（该预算对该笔完全不适用）。有效期内再按 scope 分桶：
     *   - custom：统一返回 Pair(startTs, endTs + 1)，把含终点的有效期
     *     转成半开区间，保证 endTs 当天 / 当刻包含、endTs 之后一毫秒排除；
     *   - monthly：txTs 所在 CST 自然月 1 日 00:00 到次月 1 日 00:00；
     *   - yearly：txTs 所在 CST 自然年 1 月 1 日 00:00 到次年 1 月 1 日；
     *   - weekly：epoch 为 startTs 所在 CST 日期 00:00；按
     *     idx = floor((txCstMidnight - epoch) / 7 天) 取桶，桶为
     *     [epoch + idx * 7 天, epoch + (idx + 1) * 7 天)。
     *
     * 未知 scope 返回 null。全程 java.time 固定 UTC+8 偏移，跨年 / 跨月
     * 正确，且与运行机器默认时区无关。
     */
    fun periodBucket(
        scope: String,
        startTs: Long,
        endTs: Long,
        txTs: Long,
    ): Pair<Long, Long>? {
        // 有效期双闭区间判定（所有 scope 共用）。
        if (txTs < startTs || txTs > endTs) return null
        when (scope) {
            "custom" -> return Pair(startTs, endTs + 1L)
            "monthly" -> {
                val firstDay = Instant.ofEpochMilli(txTs).atOffset(CST).toLocalDate().withDayOfMonth(1)
                val bucketStart = firstDay.atStartOfDay(CST).toInstant().toEpochMilli()
                val bucketEnd = firstDay.plusMonths(1).atStartOfDay(CST).toInstant().toEpochMilli()
                return Pair(bucketStart, bucketEnd)
            }
            "yearly" -> {
                val txDate = Instant.ofEpochMilli(txTs).atOffset(CST).toLocalDate()
                val firstDay = LocalDate.of(txDate.year, 1, 1)
                val bucketStart = firstDay.atStartOfDay(CST).toInstant().toEpochMilli()
                val bucketEnd = firstDay.plusYears(1).atStartOfDay(CST).toInstant().toEpochMilli()
                return Pair(bucketStart, bucketEnd)
            }
            "weekly" -> {
                // epoch 锚定预算 startTs 所在 CST 日期零点（不是周一）。
                val epochDate = Instant.ofEpochMilli(startTs).atOffset(CST).toLocalDate()
                val epoch = epochDate.atStartOfDay(CST).toInstant().toEpochMilli()
                val txMidnight = Instant.ofEpochMilli(txTs).atOffset(CST).toLocalDate()
                    .atStartOfDay(CST).toInstant().toEpochMilli()
                // floorDiv 保证极早期时间戳（理论负值天数差）也向下取整。
                val idx = Math.floorDiv(txMidnight - epoch, WEEK_MS)
                val bucketStart = epoch + idx * WEEK_MS
                return Pair(bucketStart, bucketStart + WEEK_MS)
            }
            else -> return null
        }
    }

    // ========================================================================
    // 折算与单预算评估
    // ========================================================================

    /**
     * 把 decimal 元金额折算到预算币种 minor 分整数。
     *
     * 同币种直接 [parseCents]；异币种委托 [RateTables.convert]；
     * [rateTable] 为 null 或 convert 返回 null（缺双向汇率）时返回 null，
     * 由调用方保守跳过该预算（不误拦）。
     */
    private fun toBudgetCents(
        amountDecimal: String,
        fromCcy: String,
        budgetCcy: String,
        rateTable: RateTable?,
    ): Long? {
        if (fromCcy == budgetCcy) return parseCents(amountDecimal)
        if (rateTable == null) return null
        return RateTables.convert(parseCents(amountDecimal), fromCcy, budgetCcy, rateTable)
    }

    /**
     * 在已知命中预算与同桶已花金额的前提下，评估三档结果。
     *
     * limit 为预算额度 minor 分（入参预算应已通过 validateBudget，额度
     * 必为正）；projected = spent + incoming；usedPct 为 Long 整除向下
     * 取整。达到 block 阈值为 BLOCK，否则达到 warning 阈值为 WARNING，
     * 否则 OK；thresholdPct 填命中档阈值（OK 时填预警阈值）。
     */
    fun evaluate(
        budget: BudgetRecord,
        spentMinor: Long,
        incomingMinor: Long,
    ): BudgetCheckResult {
        val limitMinor = parseCents(budget.amountMinor)
        val projectedMinor = spentMinor + incomingMinor
        val usedPct = (projectedMinor * 100L / limitMinor).toInt()
        val level = when {
            usedPct >= budget.blockThresholdPct -> BudgetLevel.BLOCK
            usedPct >= budget.warningThresholdPct -> BudgetLevel.WARNING
            else -> BudgetLevel.OK
        }
        val thresholdPct = when (level) {
            BudgetLevel.BLOCK -> budget.blockThresholdPct
            BudgetLevel.WARNING -> budget.warningThresholdPct
            BudgetLevel.OK -> budget.warningThresholdPct
        }
        return BudgetCheckResult(
            level = level,
            budgetId = budget.id,
            category = budget.category,
            currency = budget.currency,
            spentMinor = spentMinor,
            incomingMinor = incomingMinor,
            projectedMinor = projectedMinor,
            limitMinor = limitMinor,
            usedPct = usedPct,
            thresholdPct = thresholdPct,
        )
    }

    // ========================================================================
    // 门面：单笔支出对全量候选预算的最严重命中
    // ========================================================================

    /**
     * 保存 / 编辑单笔支出前的预算硬约束门面判定。
     *
     * 流程：
     *   1. incoming.kind 不等于 "expense"（收入 / 转账等）直接返回
     *      [BudgetCheckResult.OK_EMPTY]；
     *   2. 遍历 budgets，依次跳过：active=false；incoming.occurredAt 不在
     *      预算有效期 / 所属周期桶为 null；分类不匹配（预算 category
     *      非 "all" 且不等于流水分类）；本笔无法折算到预算币种（无表 /
     *      缺汇率，保守放行）；
     *   3. spentMinor 汇总 existing 中同时满足：kind 为 expense、id 与本
     *      笔不同（编辑场景排除自身旧额）、与本笔同周期桶（桶起点相等）、
     *      分类匹配、金额可折算到预算币种的全部金额；
     *   4. 调 [evaluate] 得候选结果，多预算取最严重，同档取 usedPct
     *      更大，再同取 budgetId 字典序最小；无任何命中返回 OK_EMPTY。
     *
     * 关于 [nowMs]：当前周期一律以 incoming.occurredAt 为锚（补录 / 编辑
     * 历史日期时按业务日期所属周期判定），因此 nowMs 不参与任何计算；
     * 保留该形参供未来“保存时刻”口径（例如按当前周期复核已过期补录）
     * 扩展，双端签名对称，当前调用传任意毫秒均可。
     *
     * @param incoming 本笔待存 / 待更新支出（金额为 decimal 元字符串）
     * @param budgets 候选预算全集
     * @param existing 既有支出流水全集（编辑场景同 id 旧记录自动排除）
     * @param rateTable 可选 B5 离线汇率表；null 时异币金额不可折算
     * @param nowMs 保存时刻（预留，当前不参与判定）
     */
    @Suppress("UNUSED_PARAMETER")
    fun checkTx(
        incoming: BudgetTxLike,
        budgets: List<BudgetRecord>,
        existing: List<BudgetTxLike>,
        rateTable: RateTable? = null,
        nowMs: Long,
    ): BudgetCheckResult {
        if (incoming.kind != "expense") return BudgetCheckResult.OK_EMPTY

        var winner: BudgetCheckResult? = null
        for (budget in budgets) {
            if (!budget.active) continue
            val incomingBucket = periodBucket(
                budget.scope, budget.startTs, budget.endTs, incoming.occurredAt,
            ) ?: continue
            if (!categoryMatch(budget.category, incoming.category)) continue
            val incomingConverted = toBudgetCents(
                incoming.amountMinor, incoming.currency, budget.currency, rateTable,
            ) ?: continue

            var spentMinor = 0L
            for (history in existing) {
                if (history.kind != "expense") continue
                // 编辑既有流水：排除自身旧额，避免同一笔被重复累计。
                if (history.id == incoming.id) continue
                if (!categoryMatch(budget.category, history.category)) continue
                val historyBucket = periodBucket(
                    budget.scope, budget.startTs, budget.endTs, history.occurredAt,
                ) ?: continue
                // 同周期桶：以桶起点相等判定（末点开区间不直接参与比较）。
                if (historyBucket.first != incomingBucket.first) continue
                val historyConverted = toBudgetCents(
                    history.amountMinor, history.currency, budget.currency, rateTable,
                ) ?: continue
                spentMinor += historyConverted
            }

            val candidate = evaluate(budget, spentMinor, incomingConverted)
            if (isMoreSevere(candidate, winner)) winner = candidate
        }
        return winner ?: BudgetCheckResult.OK_EMPTY
    }

    // ========================================================================
    // 私有辅助
    // ========================================================================

    /** 分类匹配：预算分类为 "all" 时匹配任意支出分类，否则要求严格相等。 */
    private fun categoryMatch(budgetCategory: String, txCategory: String): Boolean {
        return budgetCategory == "all" || budgetCategory == txCategory
    }

    /** 严重度序数：BLOCK 最高。 */
    private fun severityRank(level: BudgetLevel): Int = when (level) {
        BudgetLevel.OK -> 0
        BudgetLevel.WARNING -> 1
        BudgetLevel.BLOCK -> 2
    }

    /**
     * 候选之间的严格优先比较：级别更严优先；同级 usedPct 更大优先；
     * 仍相同 budgetId 字典序更小优先（current 为 null 时候选直接胜出）。
     */
    private fun isMoreSevere(candidate: BudgetCheckResult, current: BudgetCheckResult?): Boolean {
        if (current == null) return true
        val rankDelta = severityRank(candidate.level) - severityRank(current.level)
        if (rankDelta != 0) return rankDelta > 0
        if (candidate.usedPct != current.usedPct) return candidate.usedPct > current.usedPct
        val currentId = current.budgetId ?: ""
        val candidateId = candidate.budgetId ?: ""
        return candidateId < currentId
    }
}
