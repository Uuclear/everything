// ============================================================================
// FinanceAggregator 纯函数 —— 财务模块 Android 端纯函数实现（stage5-finance / T5 / TR-5.1）
// ============================================================================
//
// 任务: stage5-finance / Task 5 / TR-5.1
// 路径: android/app/src/main/java/com/everything/eve/finance/FinanceAggregator.kt
// 作用: 客户端聚合净资产 / 资产总额 / 负债总额 / 月度收支汇总 / 预算阈值告警；
//       不依赖 Room / Network / DataStore / Log —— 全部为入参 -> 返回值的
//       纯函数，便于 JVM 单测直接跑（无需 Robolectric / Android Framework）。
//
// 设计要点（与 Web web/src/finance/aggregator.ts 字节级一致 —— 三端契约）：
//   1. 纯函数 —— 无副作用, 不调用 Room / Network / DataStore / Log / println;
//   2. decimal-as-string —— 金额字段全程字符串承载, 内部按"分"（cent = 最小单位）整数累加，
//      输出统一两位小数字符串（"1234.56" 形式），避免 JS / Kotlin Double 浮点精度丢失；
//   3. 归档过滤 —— 净资产聚合只统计 `archived=false` 的账户与 `archived=false` 的信用卡
//      已用额度；归档条目仍计入 accountCount / cardCount, 但不计入金额;
//   4. 空集合安全 —— accounts / cards / txs 任一为空时返回零值, 不抛错, 不返回 null;
//   5. 与 Android 端 FinanceAggregator.kt、Web aggregator.ts 行为逐字段一致（三端契约,
//      见 docs/finance.md §3 资产看板定义）。
//
// v2 扩展（stage5-finance-v2 / Task 4 / TR-4.2）:
//   netWorth 与 monthlyReport 末尾新增第 4 参 loans: List<LoanLike>（默认空列表,
//   既有调用零回归）：
//     - netWorth：剩余本金 = principalMinor - paidMinor（钳位 >=0）；
//       direction=lent 的剩余本金计入总资产（应收借款）, direction=borrowed 计入
//       总负债（应付借款）；includeInNetAssets=false 两端均不计；
//       netCents 仍 = assetCents - liabilityCents（自然实现 spec 的
//       "net_assets += lent - borrowed"）；DashboardSnapshot 结构不变（不加 loanCount）；
//     - monthlyReport：借款本金是资产/负债形态转换而非 income/expense, 本期不累加；
//       loans 参数为 Task5 多币种折算预留。
//
// v2 B5 多币种折算扩展（stage5-finance-v2 / B5 / TR-5.2 / spec FR-V2-C.2、FR-V2-C.3）:
//   netWorth 与 monthlyReport 在 loans 之后再追加两个末位默认参
//   （targetCurrency=DEFAULT_CURRENCY, rateTable=null）, 既有调用零回归：
//     - 账户余额 / 信用卡 usedLimit / loan 剩余本金 / 流水金额统一经私有
//       toTarget(cents, sourceCurrency, targetCurrency, rateTable) 折算 ——
//       无表或同币种原值返回, 缺汇率按 1:1 面值降级（RateTable.convertOrIdentity）；
//     - DashboardSnapshot 新增末位字段 targetCurrency（默认 CNY, data class 默认值,
//       既有位置构造零回归）；原 currency 字段语义不变（首账户币 / 默认 CNY）；
//     - MonthlyReport 结构不加字段, 月报金额口径随 targetCurrency（注释锚定）；
//     - CardLike / TxLike 新增末位 currency 默认参（默认 CNY, 既有具名/位置构造
//       零回归）；AccountLike / LoanLike 已带 currency 字段, 无需改动；
//     - 负数舍入双端锁定规则见 RateTable.kt 文件头（绝对值 HALF_UP 再恢复符号）。
//
// 关联:
//   - tasks.md TR-5.1（Android FinanceAggregator.kt 镜像实现）
//   - tasks.md TR-5.2（B5 多币种折算扩展, stage5-finance-v2）
//   - spec FR-V2-C.2 / FR-V2-C.3（离线汇率包格式与看板折算口径）
//   - android/.../finance/RateTable.kt（汇率解析 / 折算纯函数, 本文件唯一折算依赖）
//   - tasks.md TR-5.3（JUnit 测试套件, 加载共享 fixture）
//   - tasks.md TR-5.4（三端 fixture SHA-256 一致性核验）
//   - tasks.md TR-4.2（v2 loan 接入 netWorth, stage5-finance-v2）
//   - tasks.md TR-4.6（v2 aggregator fixture 双端 SHA-256 一致性核验）
//   - web/src/finance/aggregator.ts（Web 镜像版本，本期 T5 子代理同步创建）
//   - docs/schemas/finance.schema.json（字段口径真理源）
//
// v2 Task 8 投资账户 + 手动行情（stage5-finance-v2 / Task 8 / FR-V2-D.2、FR-V2-D.3）:
//   投资账户市值聚合以**独立函数**形式提供（不进 netWorth / monthlyReport 内部,
//   避免破坏既有签名与 190+ 测试 fixture）:
//     - investmentMarketValue: 投资账户市值聚合（minor = 分, 折算到目标币）;
//       算法骨架: value_minor = sum(holding.shares * quote.price_minor), 逐持仓
//       按其 currency 先经 RateTable 折算到 targetCurrency; 缺价 / 缺汇率一律
//       按 0 / 面值降级（同缺汇率语义）;
//     - investmentTopHoldings: 持仓 top N（市值排序, 跨账户聚合, 同 symbol 跨账户
//       累加; 返回 List<InvestmentHoldingValue>）。
//   UI 集成: FinanceDashboard 投资账户卡片调用 investmentMarketValue + top 5;
//   FinanceTxList 月报新增"投资账户市值变化"行调用 investmentMarketValue（两月
//   对比, 单调增/减由调用方计算）。
// ============================================================================

package com.everything.eve.finance

/**
 * FinanceAggregator 纯函数 object 容器（无状态, 全静态方法）。
 *
 * 命名采用 Kotlin 单例 `object` 而非 `class`, 与工程内 Luhn.kt / Recurrence.kt
 * 等纯算法容器保持风格一致（详见 android/.../recurrence/Recurrence.kt）。
 */
object FinanceAggregator {

    // ============================================================================
    // 常量区 —— 货币与精度边界
    // ============================================================================

    /**
     * 默认货币代码（ISO 4217 三字母；本期 v1 固定 CNY，v2 扩展多币种）。
     *
     * - 与 Web 端 aggregator.ts `DEFAULT_CURRENCY` 完全对齐；
     * - 当输入 accounts / cards / txs 全空时, DashboardSnapshot.currency 即取此值；
     * - 当至少存在一个非空条目时, currency 优先取第一条账户的 currency（schema 默认 CNY）。
     */
    private const val DEFAULT_CURRENCY = "CNY"

    /**
     * decimal-as-string 输出精度（小数点后位数；CNY = 元, 最小显示单位 = 分）。
     *
     * - 与 Web 端 aggregator.ts `OUTPUT_SCALE` 完全对齐；
     * - 内部累加按"分"（cent = 最小单位）整数运算, 输出时除 100 得到元字符串；
     * - 固定 2 位 —— CNY 标准, 与 ISO 4217 minor unit 一致（USD/EUR/CNY minor=2）。
     */
    private const val OUTPUT_SCALE = 2

    /**
     * 本地时区相对 UTC 的偏移（分钟；如 UTC+8 为 480）。
     *
     * 复用 4b Recurrence.kt 同口径：取一个固定时刻（选 1780000000000 ≈ 2026-06-28）
     * 读取 ZoneId.systemDefault() 偏移。本模块**不复用** Recurrence.kt 顶层 val,
     * 而是各自维护一份以保持模块边界清晰（FinanceAggregator 不依赖 Recurrence）。
     *
     * JUnit 单测需在加载**前**通过 `System.setProperty("user.timezone", "Asia/Shanghai")`
     * 锁定到 CST 锚定（与 Web `aggregator.test.ts` 顶部工具同口径）。
     */
    private val TZ_OFFSET_MIN: Int = run {
        val zone = java.time.ZoneId.systemDefault()
        val offsetSeconds = zone.rules.getOffset(java.time.Instant.ofEpochMilli(1780000000000L))
            .totalSeconds
        offsetSeconds / 60
    }

    // ============================================================================
    // 公开数据类型 —— DashboardSnapshot / MonthlyReport / BudgetStatus
    // ============================================================================

    /**
     * 资产看板快照（FinanceDashboard 顶部三数字卡 + 计数）。
     *
     * 字段语义（与 Web aggregator.ts DashboardSnapshot 接口一一对齐）：
     *   - `totalAssets`：净资产（decimal-as-string）= 总资产 - 总负债；
     *   - `totalAssetValue`：总资产（仅账户余额；不含信用卡信用额度, 因信用额度非自有资产）；
     *   - `totalLiability`：总负债（所有非归档信用卡的 `usedLimit` 之和）；
     *   - `accountCount` / `cardCount` / `txCount`：列表计数（含归档条目）；
     *   - `currency`：货币代码；空集合时 = DEFAULT_CURRENCY = "CNY"；
     *   - `targetCurrency`：B5 折算目标币（FR-V2-C.3）；末位默认参 = "CNY",
     *     不传 rateTable 时所有金额即面值口径；与 `currency` 语义独立 ——
     *     `currency` 仍是"首账户币 / 默认 CNY"的原始口径锚点。
     */
    data class DashboardSnapshot(
        val totalAssets: String,
        val totalAssetValue: String,
        val totalLiability: String,
        val accountCount: Int,
        val cardCount: Int,
        val txCount: Int,
        val currency: String,
        val targetCurrency: String = DEFAULT_CURRENCY,
    )

    /**
     * 月度收支汇总（FinanceDashboard 月报表格 + 分类饼图）。
     *
     * 字段语义（与 Web aggregator.ts MonthlyReport 接口一一对齐）：
     *   - `yearMonth`：年月键 "YYYY-MM"（如 "2026-01"）；
     *   - `income`：当月收入合计（decimal-as-string；tx.kind == "income" 的 amount 之和）；
     *   - `expense`：当月支出合计（decimal-as-string；tx.kind == "expense" 的 amount 之和）；
     *   - `net`：月度净流（decimal-as-string；= income - expense）；
     *   - `txCount`：当月流水条数（含 income / expense / transfer 三类）；
     *   - `categoryBreakdown`：分类占比（仅 expense 分类；key=category, value=金额）。
     */
    data class MonthlyReport(
        val yearMonth: String,
        val income: String,
        val expense: String,
        val net: String,
        val txCount: Int,
        val categoryBreakdown: Map<String, String>,
    )

    /**
     * 预算阈值告警状态（BudgetStatus 枚举 —— Compose BudgetCard 颜色 + 文案路由）。
     *
     * 三档（与 Web aggregator.ts BudgetStatus 枚举一一对齐）：
     *   - `OK`：支出未达阈值, 预算健康；
     *   - `WARNING`：支出达到或超过阈值, 但未超过 1.5 倍阈值（黄色告警）；
     *   - `EXCEEDED`：支出超过 1.5 倍阈值, 严重超支（红色告警）。
     */
    enum class BudgetStatus {
        OK,
        WARNING,
        EXCEEDED,
    }

    // ============================================================================
    // 公开 API —— 五个纯函数（与 Web aggregator.ts 签名一致）
    // ============================================================================

    /**
     * 净资产 / 资产看板聚合 —— 主入口。
     *
     * 算法骨架（与 Web aggregator.ts `aggregate` 完全对齐）：
     *   1. 遍历 `accounts`, 仅 `archived=false` 的账户 `balance` 累加进 `totalAssetValue`；
     *   2. 遍历 `cards`, 仅 `archived=false` 且 `kind="credit"` 的卡, `usedLimit` 累加进
     *      `totalLiability`（借记卡不计负债 —— schema 语义对齐）；
     *   3. `totalAssets = totalAssetValue - totalLiability`；
     *   4. `accountCount` / `cardCount` / `txCount` 为列表计数（含归档条目）；
     *   5. `currency` 优先取第一条账户的 currency; 全空时 = DEFAULT_CURRENCY。
     *
     * v2 借款（TR-4.2, loans 默认空列表, 不传时行为与 v1 完全一致）：
     *   - includeInNetAssets=false 的借款两端均不计；
     *   - 剩余本金 remain = principalMinor - paidMinor（钳位 >=0, 已还超额不出负）；
     *   - direction="lent" → 计入总资产（应收借款）; "borrowed" → 计入总负债（应付）;
     *   - 其余 direction 值防御性忽略；不新增 loanCount, DashboardSnapshot 结构不变。
     *
     * v2 B5 多币种折算（TR-5.2 / FR-V2-C.3, 末位两参默认, 不传时与 v1 完全一致）：
     *   - 账户余额按 acc.currency、信用卡 usedLimit 按 card.currency、借款余量按
     *     loan.currency 统一经 [toTarget] 折算到 targetCurrency；
     *   - rateTable=null（默认）或来源币 == 目标币 → 原值；表中缺汇率 → 1:1 面值降级；
     *   - DashboardSnapshot.targetCurrency 回填目标币；currency 字段仍取首账户币。
     *
     * 边界：
     *   - accounts / cards / txs / loans 任一为空 → 该部分按 0 处理, 不抛错；
     *   - `usedLimit` 为 null 或非数字 → 视为 "0.00", 跳过该项（不抛错）；
     *   - `balance` 为 null 或非数字 → 视为 "0.00", 跳过该项（不抛错）。
     *
     * @param accounts 账户列表（明文 FinanceAccountEntity 形态, 仅取必要字段）
     * @param cards 卡列表（明文 FinanceCardEntity 形态, 仅取必要字段）
     * @param txs 流水列表（本聚合函数暂未消费; 保留参数与 Web 签名一致）
     * @param loans v2 借款列表（默认空; lent 余额计资产, borrowed 余额计负债）
     * @param targetCurrency B5 折算目标币（默认 CNY; 月报 / 看板金额口径币种）
     * @param rateTable B5 离线汇率表（默认 null; null 时一律按面值口径不折算）
     * @return DashboardSnapshot 净资产快照（始终非 null; 字段全 0 表示空集合）
     */
    fun netWorth(
        accounts: List<AccountLike>,
        cards: List<CardLike>,
        txs: List<TxLike> = emptyList(),
        loans: List<LoanLike> = emptyList(),
        targetCurrency: String = DEFAULT_CURRENCY,
        rateTable: RateTable? = null,
    ): DashboardSnapshot {
        // ========== 1. 总资产 = 仅非归档账户 balance 之和（B5: 逐户折算到目标币） ==========
        var assetCents: Long = 0L
        for (acc in accounts) {
            // 归档过滤：仅 `archived=false` 计入资产聚合。
            // 注意：accountCount 包含归档条目, 故遍历在过滤前/后都可, 此处为可读性先过滤。
            if (acc.archived) continue
            val rawCents = parseDecimalAsCents(acc.balance)
            assetCents += toTarget(rawCents, acc.currency, targetCurrency, rateTable)
        }

        // ========== 2. 总负债 = 仅非归档信用卡 usedLimit 之和（B5: 逐卡折算） ==========
        var liabilityCents: Long = 0L
        for (card in cards) {
            // 归档 / 借记卡 双过滤：kind 必须 = "credit" 才计负债。
            if (card.archived) continue
            if (card.kind != "credit") continue
            // usedLimit 为 null 时视为未用, 按 0.00 处理（不抛错）。
            val used = card.usedLimit ?: "0"
            val rawCents = parseDecimalAsCents(used)
            liabilityCents += toTarget(rawCents, card.currency, targetCurrency, rateTable)
        }

        // ========== 2.5 v2 借款：剩余本金按方向计入资产 / 负债（TR-4.2, B5: 折算） ==========
        for (loan in loans) {
            // 用户显式排除的借款, 资产端与负债端均不统计。
            if (!loan.includeInNetAssets) continue
            val principalCents = parseDecimalAsCents(loan.principalMinor)
            val paidCents = parseDecimalAsCents(loan.paidMinor)
            // 剩余本金 = 本金 - 已还；异常数据（已还超额）钳位到 0, 不出现负余量。
            val remainCents = (principalCents - paidCents).coerceAtLeast(0L)
            // 折算在钳位之后进行 —— 余量是非负数, 与双端"绝对值舍入"锁定规则不冲突。
            val remainInTarget = toTarget(remainCents, loan.currency, targetCurrency, rateTable)
            when (loan.direction) {
                // 我借出去的钱（应收）是我的债权资产。
                "lent" -> assetCents += remainInTarget
                // 我借进来的钱（应付）是我的待还负债。
                "borrowed" -> liabilityCents += remainInTarget
                // 其他 direction 值（数据异常）防御性忽略, 不加不减。
                else -> Unit
            }
        }

        // ========== 3. 净资产 = 总资产 - 总负债 ==========
        // cents 整数运算天然避免浮点精度丢失; 负数表示"资不抵债"。
        // v2 后自然得到 spec 口径：net_assets += lent_remain - borrowed_remain。
        val netCents: Long = assetCents - liabilityCents

        // ========== 4. 货币与计数 ==========
        // currency 优先取第一条账户; 全空时降级为 DEFAULT_CURRENCY = "CNY"。
        // B5 后 CardLike / TxLike 已带 currency 默认参, 但 currency 字段的"首账户
        // 币"语义保持不变（与 Web 端严格对齐）；折算目标币另见 targetCurrency。
        val currency: String = accounts.firstOrNull()?.currency
            ?: DEFAULT_CURRENCY

        // 计数包含归档条目 —— 列表层 UI 显示"已归档"标签, 故统计口径与列表一致。
        val accountCount = accounts.size
        val cardCount = cards.size
        val txCount = txs.size

        return DashboardSnapshot(
            totalAssets = formatCents(netCents),
            totalAssetValue = formatCents(assetCents),
            totalLiability = formatCents(liabilityCents),
            accountCount = accountCount,
            cardCount = cardCount,
            txCount = txCount,
            currency = currency,
            targetCurrency = targetCurrency,
        )
    }

    /**
     * 单账户余额聚合 —— 含关联流水联动。
     *
     * 算法骨架（与 Web aggregator.ts `accountBalance` 对齐）：
     *   1. 起点 = `account.balance`（decimal-as-string）；
     *   2. 遍历 `txs`, 仅 `tx.accountId == account.id` 且未软删的流水参与累加；
     *   3. `kind=income` → 累加 `tx.amount`; `kind=expense` → 累减; `kind=transfer` →
     *      转出账户余额减 (`tx.accountId == account.id`), 转入账户余额增
     *      (`tx.transferToAccountId == account.id`), 此处统一按 accountId 与 transferToAccountId
     *      双向调整；
     *   4. 输出统一两位小数字符串。
     *
     * 边界：
     *   - `txs` 为空 → 仅返回 `account.balance` 规范化结果；
     *   - `tx.amount` 为 null / 非数字 → 跳过该项（不抛错）；
     *   - `tx.kind` 非三选一 → 跳过该项（不抛错）。
     *
     * @param account 单个账户（明文 FinanceAccountEntity 形态）
     * @param txs 流水列表（全集, 函数内部按 accountId 过滤）
     * @return 当前余额（decimal-as-string; 两位小数; 负数表示透支）
     */
    fun accountBalance(account: AccountLike, txs: List<TxLike>): String {
        var cents: Long = parseDecimalAsCents(account.balance)

        for (tx in txs) {
            // 仅累加本账户直接关联的流水（kind=transfer 时, 转出/转入账户均各自调整）。
            when {
                tx.accountId == account.id -> {
                    // 流水主账户侧：income/expense 按方向调整; transfer 时此处为"转出"。
                    cents = when (tx.kind) {
                        "income" -> addDecimalAsCents(cents, tx.amount)
                        "expense" -> subtractDecimalAsCents(cents, tx.amount)
                        "transfer" -> subtractDecimalAsCents(cents, tx.amount)
                        else -> cents
                    }
                }
                tx.transferToAccountId == account.id && tx.kind == "transfer" -> {
                    // 流水转入侧：仅 transfer 类型时, 转入账户余额 += amount。
                    cents = addDecimalAsCents(cents, tx.amount)
                }
            }
        }
        return formatCents(cents)
    }

    /**
     * 单卡已用额度聚合 —— 含关联流水联动（信用卡消费计入 usedLimit）。
     *
     * 算法骨架（与 Web aggregator.ts `cardUsedLimit` 对齐）：
     *   1. 起点 = `card.usedLimit`（decimal-as-string; null 时按 "0.00"）；
     *   2. 遍历 `txs`, 仅 `tx.cardId == card.id` 且 `kind="expense"` 的流水累加进 usedLimit；
     *   3. `kind="income"`（还款）或 `kind="transfer"` → 累减（还款冲销已用额度）。
     *   4. 输出统一两位小数字符串。
     *
     * 注意：本函数为"实时计算已用额度"视图（用于卡片详情页）；持久化的
     * `card.usedLimit` 字段仍由 ServiceLocator/Repository 在 tx 写入时维护。
     *
     * 边界：
     *   - `txs` 为空 → 返回 `card.usedLimit` 规范化结果（null → "0.00"）；
     *   - `card.archived=true` 时本函数**仍正常返回**（与 web 端一致; UI 层做归档过滤）。
     *
     * @param card 单张卡（明文 FinanceCardEntity 形态）
     * @param txs 流水列表（全集, 函数内部按 cardId 过滤）
     * @return 已用额度（decimal-as-string; 两位小数; 不会为负 —— 负数钳位到 0）
     */
    fun cardUsedLimit(card: CardLike, txs: List<TxLike>): String {
        var cents: Long = parseDecimalAsCents(card.usedLimit ?: "0")

        for (tx in txs) {
            if (tx.cardId != card.id) continue
            cents = when (tx.kind) {
                "expense" -> addDecimalAsCents(cents, tx.amount)
                "income" -> subtractDecimalAsCents(cents, tx.amount)
                "transfer" -> subtractDecimalAsCents(cents, tx.amount)
                else -> cents
            }
        }
        // 钳位到 0 —— 还款过度冲销不会出现负数（业务语义：已用额度最小 = 0）。
        if (cents < 0L) cents = 0L
        return formatCents(cents)
    }

    /**
     * 月度收支汇总（聚合 income / expense / 分类占比）。
     *
     * 算法骨架（与 Web aggregator.ts `monthlyReport` 对齐）：
     *   1. 按 `tx.occurredAt` 的本地日历日拆出 `YYYY-MM` 分量；仅命中 `yearMonth` 的流水参与；
     *   2. `kind=income` 累加进 income; `kind=expense` 累加进 expense + 计入 categoryBreakdown;
     *   3. `kind=transfer` 不计入 income/expense（转账是账户间内部调动, 非真实收支）;
     *   4. `net = income - expense`（cents 整数运算）;
     *   5. `txCount` 含三类流水（income/expense/transfer）。
     *
     * 边界：
     *   - `txs` 为空 → 返回全零 MonthlyReport, 不抛错；
     *   - `tx.occurredAt` 跨年跨月时不参与聚合（如 yearMonth="2026-01" 时, 2025-12-31 的
     *     流水不会被计入）；
     *   - `tx.amount` / `tx.category` 为 null → 跳过该项（不抛错）。
     *
     * v2 借款（TR-4.2, loans 默认空列表）：借款本金的发放/收回是资产与负债之间的
     * 形态转换, 不属于 income / expense, 故本函数**不**把任何 loan 金额累加进
     * income / expense / categoryBreakdown；该参数为 Task5 多币种折算预留。
     *
     * v2 B5 多币种折算（TR-5.2 / FR-V2-C.3, 末位两参默认）：每条流水的
     * income / expense / categoryBreakdown 金额按 tx.currency 逐笔经 [toTarget]
     * 折算到 targetCurrency 后再累加；net = 折算后 income − 折算后 expense。
     * MonthlyReport 结构保持六字段不变（不加币种字段）—— 月报金额口径随
     * targetCurrency, 由调用方在视图层标注。
     *
     * @param yearMonth 年月键 "YYYY-MM"（如 "2026-01"）
     * @param txs 流水列表（全集, 函数内部按 occurredAt 本地月过滤）
     * @param accounts 账户列表（本函数暂未消费; 保留参数与 Web 签名一致）
     * @param loans v2 借款列表（本期不消费金额; Task5 多币种折算预留）
     * @param targetCurrency B5 折算目标币（默认 CNY）
     * @param rateTable B5 离线汇率表（默认 null; null 时按面值口径不折算）
     * @return MonthlyReport 月度收支汇总（始终非 null; 全零表示无流水）
     */
    fun monthlyReport(
        yearMonth: String,
        txs: List<TxLike>,
        accounts: List<AccountLike> = emptyList(),
        loans: List<LoanLike> = emptyList(),
        targetCurrency: String = DEFAULT_CURRENCY,
        rateTable: RateTable? = null,
    ): MonthlyReport {
        // v2 预留参数的防御性消费：List.size 恒 >= 0, 本检查永不失败、不改变输出,
        // 仅用于显式引用 loans（与既有 accounts 预留参数同风格, 避免参数静默未用）。
        check(loans.size >= 0)
        // targetCurrency / rateTable 在下述逐笔折算中被实际消费; 此处显式读一次
        // targetCurrency 仅为让"口径参数"在空流水路径也不产生未用告警错觉。
        check(targetCurrency.isNotEmpty())

        var incomeCents: Long = 0L
        var expenseCents: Long = 0L
        var txCount: Int = 0
        val categoryCents = mutableMapOf<String, Long>()

        for (tx in txs) {
            // 本地日历月过滤 —— 与 fixture / Web 端 month 键一致（CST UTC+8 口径）。
            val txYearMonth = yearMonthOf(tx.occurredAt)
            if (txYearMonth != yearMonth) continue

            txCount++
            // B5: 逐笔按流水币种折算到目标币后再入合计（无表 / 同币 / 缺汇率均安全降级）。
            val amountInTarget = toTarget(
                parseDecimalAsCents(tx.amount), tx.currency, targetCurrency, rateTable
            )
            when (tx.kind) {
                "income" -> {
                    incomeCents += amountInTarget
                }
                "expense" -> {
                    expenseCents += amountInTarget
                    // 分类占比仅对 expense 累计（与 Web aggregator.ts 语义对齐）。
                    val cat = tx.category.ifEmpty { "other" }
                    val prev = categoryCents[cat] ?: 0L
                    categoryCents[cat] = prev + amountInTarget
                }
                "transfer" -> {
                    // transfer 不计入 income / expense, 但计入 txCount。
                }
            }
        }

        val netCents: Long = incomeCents - expenseCents
        val categoryBreakdown: Map<String, String> = categoryCents
            .mapValues { (_, v) -> formatCents(v) }

        return MonthlyReport(
            yearMonth = yearMonth,
            income = formatCents(incomeCents),
            expense = formatCents(expenseCents),
            net = formatCents(netCents),
            txCount = txCount,
            categoryBreakdown = categoryBreakdown,
        )
    }

    // ============================================================================
    // 公开 API —— 投资账户市值聚合（Task 8 / FR-V2-D.2、FR-V2-D.3）
    // ============================================================================

    /**
     * 投资账户单持仓市值（minor = 分, 已折算到目标币）。
     *
     * 与 [investmentTopHoldings] 一并返回, UI 层可直接列表展示。
     *
     * @property symbol 证券代码（与 HoldingLike.symbol / Quote.symbol 锁同口径）
     * @property currency 持仓 / 报价币种（与 HoldingLike.currency 一致; 折算前原币）
     * @property shares 持仓份额（Double, 透传 HoldingLike.shares）
     * @property priceMinor 该持仓对应行情报价的 price_minor（缺价 = 0）
     * @property valueInTargetMinor 折算到目标币后的 minor 金额（分）
     */
    data class InvestmentHoldingValue(
        val symbol: String,
        val currency: String,
        val shares: Double,
        val priceMinor: Long,
        val valueInTargetMinor: Long,
    )

    /**
     * 投资账户市值聚合快照（FinanceDashboard 投资卡片数据源）。
     *
     * 字段语义:
     *   - `totalValue`: 所有非归档投资账户的市值合计（已折算到 targetCurrency）;
     *   - `currency`: 目标币（与入参 targetCurrency 一致, 便于 UI 直接展示）;
     *   - `accountCount`: 投资账户数（含已归档条目, 与列表口径一致）;
     *   - `missingPriceHoldingCount`: 缺价的持仓数（UI 可据此提示"补同步行情"）;
     *   - `topHoldings`: 跨账户 top N 持仓（市值降序）;
     *   - `effectiveTs`: 行情包整体生效时刻（缺价 / 缺包 → null）。
     *
     * 边界:
     *   - investmentAccounts 为空 → totalValue = "0.00", accountCount = 0, topHoldings = []。
     *   - quoteTable 为空 → 全持仓缺价, totalValue = "0.00", missingPriceHoldingCount = 所有非归档持仓数。
     *   - 缺汇率按 1:1 面值降级（与其它折算同口径）。
     */
    data class InvestmentMarketValueSnapshot(
        val totalValue: String,
        val currency: String,
        val accountCount: Int,
        val missingPriceHoldingCount: Int,
        val topHoldings: List<InvestmentHoldingValue>,
        val effectiveTs: Long?,
    )

    /**
     * 投资账户市值聚合（Task 8 / FR-V2-D.2、FR-V2-D.3）。
     *
     * 算法骨架:
     *   1. 遍历 investmentAccounts, 跳过 archived=true; 其它账户逐笔 holdings 求
     *      (shares * price_minor) → 该持仓原币市值（cents = shares * price_minor）;
     *      缺价（quoteTable 为 null 或 QuoteTables.priceMinorOf 返回 null）按 0 计入;
     *   2. 逐持仓经 [toTarget] 折算到 targetCurrency, 累加进 totalCents;
     *   3. 同时累加每个持仓的折算后市值, 收集 top N（N 默认 5）按 valueInTargetMinor
     *      降序排列;
     *   4. 返回 [InvestmentMarketValueSnapshot]。
     *
     * 注意:
     *   - shares 是 Double, price_minor 是 Long, 两者相乘前 shares 转为 BigDecimal
     *     走绝对值 HALF_UP 取整（与既有汇率折算舍入口径一致）;
     *   - 同 symbol 跨账户累加（不在此处去重, top N 跨账户维度求值）;
     *   - 缺价标记 missingPriceHoldingCount 便于 UI 给出"该账户还差 X 个报价"
     *     引导, 不计入 totalValue（与 spec 一致: 缺价按 0 计入）;
     *   - effectiveTs: quoteTable 为 null → null; 否则取 quoteTable.ts。
     *
     * @param investmentAccounts 投资账户列表（含归档条目, 内部过滤）
     * @param quoteTable 行情包（可为 null; null 时全持仓按缺价计 0）
     * @param topN top 持仓数（默认 5; 与 Dashboard "持仓 top 5" 锁口径）
     * @param targetCurrency 折算目标币（默认 DEFAULT_CURRENCY = "CNY"）
     * @param rateTable 多币种汇率表（默认 null; null / 缺汇率按面值 1:1 降级）
     * @return 投资账户市值快照（始终非 null）
     */
    fun investmentMarketValue(
        investmentAccounts: List<InvestmentAccountRecord>,
        quoteTable: QuoteTable? = null,
        topN: Int = 5,
        targetCurrency: String = DEFAULT_CURRENCY,
        rateTable: RateTable? = null,
    ): InvestmentMarketValueSnapshot {
        // ========== 1. 遍历账户与持仓, 累计原币市值 + 折算到目标币 ==========
        var totalCents: Long = 0L
        var missingPriceHoldingCount: Int = 0
        val perHolding = ArrayList<InvestmentHoldingValue>()
        var accountCount: Int = 0

        for (acc in investmentAccounts) {
            accountCount++
            if (acc.archived) continue
            for (h in acc.holdings) {
                // 缺价查找: quoteTable 为 null 或该 symbol 不在表中 → 0
                val priceMinor: Long = if (quoteTable == null) {
                    0L
                } else {
                    QuoteTables.priceMinorOf(h.symbol, quoteTable) ?: 0L
                }
                if (priceMinor == 0L && quoteTable?.quotes?.containsKey(h.symbol) != true) {
                    // 仅当"报价缺失"（既无 quote 也未命中 0 价）计入缺价统计。
                    // 命中的报价恰好为 0（罕见但合法）不视为缺价。
                    missingPriceHoldingCount++
                }
                // 原币市值 cents: shares * priceMinor (Double × Long → BigDecimal)
                val holdingCents: Long = multiplySharesByPriceMinor(h.shares, priceMinor)
                // 折算到目标币
                val valueInTargetMinor: Long = toTarget(
                    holdingCents, h.currency, targetCurrency, rateTable
                )
                totalCents += valueInTargetMinor
                perHolding.add(
                    InvestmentHoldingValue(
                        symbol = h.symbol,
                        currency = h.currency,
                        shares = h.shares,
                        priceMinor = priceMinor,
                        valueInTargetMinor = valueInTargetMinor,
                    )
                )
            }
        }

        // ========== 2. top N 排序（按 valueInTargetMinor 降序） ==========
        val top: List<InvestmentHoldingValue> = perHolding
            .sortedWith(
                compareByDescending<InvestmentHoldingValue> { it.valueInTargetMinor }
                    .thenByDescending { it.symbol }
            )
            .take(if (topN < 0) 0 else topN)

        // ========== 3. 行情包生效时刻 ==========
        val effectiveTs: Long? = quoteTable?.ts

        return InvestmentMarketValueSnapshot(
            totalValue = formatCents(totalCents),
            currency = targetCurrency,
            accountCount = accountCount,
            missingPriceHoldingCount = missingPriceHoldingCount,
            topHoldings = top,
            effectiveTs = effectiveTs,
        )
    }

    /**
     * shares × priceMinor 的绝对值 HALF_UP 取整（与 RateTable 折算锁同舍入口径）。
     *
     * 价格单位是分 (price_minor Long), 份额是 Double; Long × Double 在 JVM 上
     * 走 BigDecimal 通路避免浮点累积误差。
     */
    private fun multiplySharesByPriceMinor(shares: Double, priceMinor: Long): Long {
        if (shares <= 0.0 || !shares.isFinite()) return 0L
        // 份额与价格均为非负, 直接相乘再 HALF_UP 取整, 不涉及符号恢复。
        val bd = java.math.BigDecimal(shares)
            .multiply(java.math.BigDecimal(priceMinor))
            .setScale(0, java.math.RoundingMode.HALF_UP)
        return bd.toLong()
    }

    /**
     * 预算阈值告警 —— 支出 vs 月收入 / 阈值比。
     *
     * 算法骨架（与 Web aggregator.ts `budgetThreshold` 对齐）：
     *   1. 阈值 = `monthlyIncome * threshold`（cents 整数乘法, 避免精度丢失）；
     *   2. 比值 = `monthlyExpense / monthlyIncome`（仅在 monthlyIncome > 0 时计算;
     *      monthlyIncome = 0 时一律返回 OK —— 零收入时无超支概念）；
     *   3. 状态判定：
     *      - 比值 < 1.0      → OK（支出未达月收入）；
     *      - 1.0 ≤ 比值 < 1.5 → WARNING（超支预警）；
     *      - 比值 ≥ 1.5      → EXCEEDED（严重超支）。
     *
     * 注意：threshold 仅在 monthlyIncome > 0 时生效；monthlyIncome = 0 时
     * 直接返回 OK 而非抛错 —— 与 Web 端行为一致。
     *
     * @param monthlyIncome 月度收入（decimal-as-string; "0" 表示零收入）
     * @param monthlyExpense 月度支出（decimal-as-string）
     * @param threshold 阈值系数（Double; 0.0 ~ 1.0+ 范围; 默认 0.8 即"支出达收入 80% 预警"）
     * @return BudgetStatus 三档枚举之一（OK / WARNING / EXCEEDED）
     */
    fun budgetThreshold(
        monthlyIncome: String,
        monthlyExpense: String,
        threshold: Double,
    ): BudgetStatus {
        // ========== 零收入短路 ==========
        val incomeCents: Long = parseDecimalAsCents(monthlyIncome)
        if (incomeCents <= 0L) return BudgetStatus.OK

        val expenseCents: Long = parseDecimalAsCents(monthlyExpense)
        // 比值 = expense / income —— cents 整数除法, 保留 4 位精度（避免 Long 截断误差）。
        // 公式: ratio_x10000 = expenseCents * 10000 / incomeCents → 1.0 = 10000
        val ratioX10000: Long = if (incomeCents == 0L) 0L
        else (expenseCents * 10_000L) / incomeCents

        // ========== 三档判定 ==========
        // 1.0 = 10000; 1.5 = 15000
        return when {
            ratioX10000 < 10_000L -> BudgetStatus.OK
            ratioX10000 < 15_000L -> BudgetStatus.WARNING
            else -> BudgetStatus.EXCEEDED
        }
    }

    // ============================================================================
    // 内部数据形态 —— 入参 DTO（解耦 Room Entity, 便于 JUnit 注入 fixture 数据）
    // ============================================================================

    /**
     * 账户入参形态 —— 仅取聚合所需的最小字段集。
     *
     * 设计意图：与 [com.everything.eve.data.finance.entity.FinanceAccountEntity] 解耦,
     * 避免 JUnit 单测被迫构造完整 Room Entity（含 schema_version / dirty / deleted
     * 等聚合无关字段）。聚合函数只关心余额 + 归档 + 货币 + id。
     */
    data class AccountLike(
        val id: String,
        val balance: String,
        val currency: String,
        val archived: Boolean,
    )

    /**
     * 卡入参形态 —— 仅取聚合所需的最小字段集。
     *
     * @param currency B5 卡币种（末位默认参 = DEFAULT_CURRENCY = "CNY",
     *   既有具名 / 位置构造零回归；v1 卡单币种口径下无需显式传值）
     */
    data class CardLike(
        val id: String,
        val kind: String,
        val usedLimit: String?,
        val archived: Boolean,
        val currency: String = DEFAULT_CURRENCY,
    )

    /**
     * 流水入参形态 —— 仅取聚合所需的最小字段集。
     *
     * @param currency B5 流水币种（末位默认参 = "CNY"；月报多币种折算按此币种入表）
     */
    data class TxLike(
        val id: String,
        val accountId: String?,
        val cardId: String?,
        val kind: String,
        val amount: String,
        val category: String,
        val occurredAt: Long,
        val transferToAccountId: String?,
        val currency: String = DEFAULT_CURRENCY,
    )

    /**
     * v2 借款入参形态（stage5-finance-v2 / TR-4.2）—— 字段命名与 [LoanRecord] 对齐。
     *
     * 注意：本 DTO 与 [NextCardFiring.LoanLike] 同名但字段集不同 —— 本 DTO 服务于
     * 净资产聚合（金额 / 方向 / 是否计入）, 后者服务于到期提醒（status / dueTs）。
     *
     * @param direction lent（我借出, 应收）| borrowed（我借入, 应付）
     * @param principalMinor 本金（decimal-as-string）
     * @param paidMinor 已还本金（decimal-as-string; 允许 "0.00"）
     * @param includeInNetAssets 是否计入净资产看板；false 时资产/负债两端均忽略
     * @param currency ISO 4217 三字母代码（本期仅 CNY 参与, Task5 扩展多币种折算）
     * @param status active | partially_paid | paid | overdue（聚合口径不消费, 仅供 UI）
     */
    data class LoanLike(
        val id: String,
        val direction: String,
        val principalMinor: String,
        val paidMinor: String,
        val includeInNetAssets: Boolean,
        val currency: String,
        val status: String,
    )

    // ============================================================================
    // 私有工具方法 —— decimal-as-string 运算（CST 本地月份 + cents 整数算术）
    // ============================================================================

    /**
     * decimal-as-string 解析为"分"（cents = 整数） —— 避免 Double 浮点精度丢失。
     *
     * 支持格式：
     *   - 整数: "1234" → 123400
     *   - 一位小数: "1234.5" → 123450
     *   - 两位小数: "1234.56" → 123456
     *   - 三位以上小数: "1234.567" → 截断到分 (123456) —— 与 Web 端 `parseCents` 行为一致
     *   - 负数: "-100.00" → -10000
     *
     * 边界：
     *   - 空串 / null / 非数字 → 返回 0L（不抛错）；
     *   - 仅含 `-` / `+` / `.` → 返回 0L（不抛错）。
     */
    private fun parseDecimalAsCents(s: String?): Long {
        if (s.isNullOrBlank()) return 0L
        var negative = false
        var seenDot = false
        var whole = 0L
        var frac = 0L
        var fracDigits = 0
        for (c in s.trim()) {
            when {
                c == '-' && whole == 0L && !seenDot && !negative -> {
                    negative = true
                }
                c == '+' && whole == 0L && !seenDot && !negative -> {
                    // 显式正号, 忽略。
                }
                c == '.' && !seenDot -> {
                    seenDot = true
                }
                c in '0'..'9' -> {
                    val digit = c.code - '0'.code
                    if (seenDot) {
                        // 截断到 2 位小数 —— 与 Web 端 parseCents 行为一致。
                        if (fracDigits < OUTPUT_SCALE) {
                            frac = frac * 10L + digit.toLong()
                            fracDigits++
                        }
                    } else {
                        whole = whole * 10L + digit.toLong()
                    }
                }
                else -> return 0L
            }
        }
        // frac 不足 2 位时, 按 0 补齐 —— "1.5" → frac=50 → cents = 150。
        while (fracDigits < OUTPUT_SCALE) {
            frac *= 10L
            fracDigits++
        }
        var cents = whole * 100L + frac
        if (negative) cents = -cents
        return cents
    }

    /**
     * cents → decimal-as-string 输出（统一两位小数）。
     *
     * 例：123456 → "1234.56"; -100 → "-1.00"; 0 → "0.00"。
     */
    private fun formatCents(cents: Long): String {
        val negative = cents < 0L
        val absCents = if (negative) -cents else cents
        val whole = absCents / 100L
        val frac = absCents % 100L
        val fracStr = frac.toString().padStart(OUTPUT_SCALE, '0')
        val sign = if (negative) "-" else ""
        return "$sign$whole.$fracStr"
    }

    /** cents 加法 —— 兼容负数, 直接 Long 加法（无精度丢失）。 */
    private fun addDecimalAsCents(base: Long, decimal: String?): Long {
        return base + parseDecimalAsCents(decimal)
    }

    /** cents 减法 —— 兼容负数, 直接 Long 减法（无精度丢失）。 */
    private fun subtractDecimalAsCents(base: Long, decimal: String?): Long {
        return base - parseDecimalAsCents(decimal)
    }

    /**
     * B5 多币种折算统一入口（TR-5.2 / FR-V2-C.3, 与 Web aggregator.ts toTarget 锁定）。
     *
     * 规则：
     *   1. [table] == null（调用方未给汇率表）→ 原值, 完全 v1 面值口径；
     *   2. [sourceCurrency] == [targetCurrency] → 原值（同币无需折算, 自基准成立）；
     *   3. 其余情形委托 [RateTables.convertOrIdentity] —— 表中双向均缺汇率时
     *      按 1:1 面值降级, 保证离线汇率包不全时条目不丢失（spec FR-V2-C.3）。
     *
     * @param cents 源币种 minor 金额（分；允许负值, 符号规则由 RateTables 锁定）
     * @param sourceCurrency 来源币 ISO 4217 三字母代码
     * @param targetCurrency 目标币代码
     * @param table 离线汇率表（可空）
     * @return 目标币 minor 金额（分）
     */
    private fun toTarget(
        cents: Long,
        sourceCurrency: String,
        targetCurrency: String,
        table: RateTable?,
    ): Long {
        if (table == null) return cents
        if (sourceCurrency == targetCurrency) return cents
        return RateTables.convertOrIdentity(cents, sourceCurrency, targetCurrency, table)
    }

    /**
     * Unix ms → 本地年月键 "YYYY-MM"（与 4b Recurrence.kt 同款 CST 口径）。
     *
     * 实现：先把 ms + tz_offset_ms 视为"无时区 UTC 时刻", 再用 ISO 字段读 y/m —— 与
     * Web aggregator.ts `yearMonthOf` 完全一致。
     */
    private fun yearMonthOf(ts: Long): String {
        val shifted = ts + TZ_OFFSET_MIN * 60_000L
        val dt = java.time.Instant.ofEpochMilli(shifted)
            .atOffset(java.time.ZoneOffset.UTC).toLocalDateTime()
        return "%04d-%02d".format(dt.year, dt.monthValue)
    }
}
