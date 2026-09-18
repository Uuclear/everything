// ============================================================================
// 财务模块聚合器纯函数 —— 资产看板 / 月报 / 预算阈值（Web 端）
// ============================================================================
//
// 任务: stage5-finance / Task 8 / TR-8.2
// 路径: web/src/finance/aggregator.ts
// 作用: 在客户端对账户 / 卡 / 流水三类条目做客户端聚合；不依赖 Network /
//       Database / DOM / localStorage —— 全部为入参 -> 返回值的纯函数，便于
//       Vitest 直接跑（无需 jsdom）。
//
// 设计要点（与 Android FinanceAggregator.kt 字节级一致 —— 三端契约）：
//   1. 纯函数 —— 无副作用，不调用 crypto / 网络 / 浏览器 API；
//   2. decimal-as-string —— 金额字段全程字符串承载，内部按"分"（cent = 最小单位）
//      整数累加，输出统一两位小数字符串，避免 JS Number 浮点精度丢失；
//   3. 归档过滤 —— 净资产聚合只统计 `archived=false` 的账户与 `archived=false`
//      的信用卡已用额度；归档条目仍计入 accountCount / cardCount，但不计入金额；
//   4. 空集合安全 —— accounts / cards / txs 任一为空时返回零值，不抛错，不返回 null；
//   5. 与 Android FinanceAggregator.kt 行为逐字段一致（三端契约）。
//
// 关联:
//   - tasks.md TR-8.2（Web aggregator.ts 纯函数实现）
//   - tasks.md TR-8.4（共享 fixture aggregator-cases.json）
//   - tasks.md TR-8.5（Vitest 测试套件 ≥16 用例）
//   - docs/finance.md §3 资产看板定义（公式与字段口径）
//   - docs/schemas/finance.schema.json（字段口径真理源）
// ============================================================================

import type {
  BudgetStatus,
  DashboardSnapshot,
  FinanceAccount,
  FinanceCard,
  FinanceLoan,
  FinanceTx,
  MonthlyReport,
} from './types'
import { DEFAULT_CURRENCY } from './types'

// -----------------------------------------------------------------------------
// 内部数据形态 —— 入参 DTO（与 Android FinanceAggregator.AccountLike /
// CardLike / TxLike 对齐；解耦 schema 完整字段，便于单测注入）
// -----------------------------------------------------------------------------

/**
 * 账户入参形态 —— 仅取聚合所需的最小字段集。
 */
export interface AccountLike {
  id: string
  balance: string
  currency: string
  archived: boolean
}

/**
 * 卡入参形态 —— 仅取聚合所需的最小字段集。
 */
export interface CardLike {
  id: string
  kind: string
  usedLimit: string | null
  archived: boolean
}

/**
 * 流水入参形态 —— 仅取聚合所需的最小字段集。
 */
export interface TxLike {
  id: string
  accountId: string | null
  cardId: string | null
  kind: string
  amount: string
  category: string
  occurredAt: number
  transferToAccountId: string | null
}

/**
 * v2 借款入参形态（stage5-finance-v2 / TR-4.2）—— 与 Android
 * FinanceAggregator.LoanLike 对齐，字段命名取 camelCase DTO。
 *
 * 注意：本接口与 nextCardFiring.ts 导出的同名 LoanLike 字段集不同 —— 本接口
 * 服务净资产聚合（金额 / 方向 / 是否计入），后者服务到期提醒（status / dueTs）。
 *
 * @property direction lent（我借出，应收）| borrowed（我借入，应付）；其它值防御性忽略
 * @property principalMinor 本金（decimal-as-string）
 * @property paidMinor 已还本金（decimal-as-string；允许 "0.00"）
 * @property includeInNetAssets 是否计入净资产看板；false 时资产 / 负债两端均忽略
 * @property currency ISO 4217 三字母代码（本期仅 CNY 参与，Task5 扩展多币种折算）
 * @property status active | partially_paid | paid | overdue（聚合口径不按其过滤，仅供 UI）
 */
export interface LoanLike {
  id: string
  direction: string
  principalMinor: string
  paidMinor: string
  includeInNetAssets: boolean
  currency: string
  status: string
}

// -----------------------------------------------------------------------------
// 常量区 —— 货币与精度边界
// -----------------------------------------------------------------------------

/** decimal-as-string 输出精度（CNY = 元；固定 2 位与 ISO 4217 minor unit 一致）。 */
const OUTPUT_SCALE = 2

/**
 * 本地时区相对 UTC 的偏移（分钟；CST = UTC+8 = 480）。
 *
 * 复用 4b Recurrence.ts 同口径：取固定时刻读取 Intl.DateTimeFormat 偏移。
 *
 * 注：JS Date.getTimezoneOffset() 返回"本地时间减去 UTC 分钟数"——UTC+8 时
 * 返回 -480；本常量存 +480 用于将 UTC ms 推到本地日历分量。
 */
const TZ_OFFSET_MIN: number = (() => {
  // 选 1780000000000 ≈ 2026-06-28；不同月份夏令时切换时偏移可能变化，
  // 但 CST（UTC+8）全年中国无夏令时，故固定 480 即可。
  const d = new Date(1780000000000)
  return -d.getTimezoneOffset()
})()

// -----------------------------------------------------------------------------
// 公开 API —— 五个纯函数（与 Android FinanceAggregator 签名一致）
// -----------------------------------------------------------------------------

/**
 * 净资产 / 资产看板聚合 —— 主入口。
 *
 * 算法骨架：
 *   1. 遍历 accounts，仅 archived=false 的账户 balance 累加进 totalAssetValue；
 *   2. 遍历 cards，仅 archived=false 且 kind="credit" 的卡 usedLimit 累加进
 *      totalLiability（借记卡不计负债）；
 *   3. totalAssets = totalAssetValue - totalLiability；
 *   4. accountCount / cardCount / txCount 为列表计数（含归档条目）；
 *   5. currency 优先取第一条账户 currency；全空时 = DEFAULT_CURRENCY = "CNY"。
 *
 * v2 借款（TR-4.2，loans 默认空列表，不传时行为与 v1 完全一致）：
 *   - includeInNetAssets=false 的借款两端均不计；
 *   - 剩余本金 remain = parseDecimalAsCents(principalMinor) -
 *     parseDecimalAsCents(paidMinor)，钳位 >= 0n（已还超额不出负）；
 *   - direction="lent" → 计入总资产（应收借款）；"borrowed" → 计入总负债（应付）；
 *     其余 direction 值防御性忽略；
 *   - 不新增 loanCount，DashboardSnapshot 结构保持 v1 七字段不变；
 *   - netCents 仍 = assetCents - liabilityCents，自然实现
 *     "net_assets += lent - borrowed" 口径。
 *
 * 边界：
 *   - accounts / cards / txs / loans 任一为空 → 该部分按 0 处理，不抛错；
 *   - usedLimit 为 null / 非数字 → 视为 "0.00"，跳过该项；
 *   - balance 为 null / 非数字 → 视为 "0.00"，跳过该项。
 *
 * @param accounts 账户列表（明文 FinanceAccount 形态，仅取必要字段）
 * @param cards 卡列表（明文 FinanceCard 形态，仅取必要字段）
 * @param txs 流水列表（本聚合函数暂未消费；保留参数与签名一致）
 * @param loans v2 借款列表（默认空；lent 余额计资产，borrowed 余额计负债）
 * @return DashboardSnapshot 净资产快照（始终非 null）
 */
export function netWorth(
  accounts: AccountLike[],
  cards: CardLike[],
  txs: TxLike[] = [],
  loans: LoanLike[] = [],
): DashboardSnapshot {
  // ========== 1. 总资产 = 仅非归档账户 balance 之和 ==========
  let assetCents = 0n
  for (const acc of accounts) {
    if (acc.archived) continue
    assetCents = assetCents + parseDecimalAsCents(acc.balance)
  }

  // ========== 2. 总负债 = 仅非归档信用卡 usedLimit 之和 ==========
  let liabilityCents = 0n
  for (const card of cards) {
    if (card.archived) continue
    if (card.kind !== 'credit') continue
    liabilityCents = liabilityCents + parseDecimalAsCents(card.usedLimit ?? '0')
  }

  // ========== 2.5 v2 借款：剩余本金按方向计入资产 / 负债（TR-4.2） ==========
  for (const loan of loans) {
    // 用户显式排除的借款，资产端与负债端均不统计。
    if (!loan.includeInNetAssets) continue
    const principalCents = parseDecimalAsCents(loan.principalMinor)
    const paidCents = parseDecimalAsCents(loan.paidMinor)
    // 剩余本金 = 本金 - 已还；异常数据（已还超额）钳位到 0，不出现负余量。
    const remainCents = principalCents - paidCents > 0n
      ? principalCents - paidCents
      : 0n
    if (loan.direction === 'lent') {
      // 我借出去的钱（应收）是我的债权资产。
      assetCents = assetCents + remainCents
    } else if (loan.direction === 'borrowed') {
      // 我借进来的钱（应付）是我的待还负债。
      liabilityCents = liabilityCents + remainCents
    }
    // 其他 direction 值（数据异常）防御性忽略，不加不减。
  }

  // ========== 3. 净资产 = 总资产 - 总负债 ==========
  // BigInt 整数运算天然避免浮点精度丢失；负数表示"资不抵债"。
  // v2 后自然得到 spec 口径：net_assets += lent_remain - borrowed_remain。
  const netCents: bigint = assetCents - liabilityCents

  // ========== 4. 货币与计数 ==========
  const currency: string = accounts[0]?.currency ?? DEFAULT_CURRENCY

  // 计数包含归档条目 —— 列表层 UI 显示"已归档"标签，故统计口径与列表一致。
  const accountCount = accounts.length
  const cardCount = cards.length
  const txCount = txs.length

  return {
    totalAssets: formatCents(netCents),
    totalAssetValue: formatCents(assetCents),
    totalLiability: formatCents(liabilityCents),
    accountCount,
    cardCount,
    txCount,
    currency,
  }
}

/**
 * 单账户余额聚合 —— 含关联流水联动。
 *
 * 算法骨架：
 *   1. 起点 = account.balance（decimal-as-string）；
 *   2. 遍历 txs，仅 tx.accountId == account.id 的流水参与累加；
 *   3. kind=income → +amount; kind=expense → -amount; kind=transfer →
 *      转出账户余额减（tx.accountId == account.id），转入账户余额增
 *      （tx.transferToAccountId == account.id）；
 *   4. 输出统一两位小数字符串。
 *
 * 边界：
 *   - txs 为空 → 仅返回 account.balance 规范化结果；
 *   - tx.amount 为 null / 非数字 → 跳过该项（不抛错）；
 *   - tx.kind 非三选一 → 跳过该项（不抛错）。
 *
 * @param account 单个账户（明文 FinanceAccount 形态）
 * @param txs 流水列表（全集，函数内部按 accountId 过滤）
 * @return 当前余额（decimal-as-string；两位小数；负数表示透支）
 */
export function accountBalance(account: AccountLike, txs: TxLike[]): string {
  let cents = parseDecimalAsCents(account.balance)

  for (const tx of txs) {
    if (tx.accountId === account.id) {
      // 流水主账户侧：income/expense 按方向调整；transfer 时此处为"转出"。
      if (tx.kind === 'income') {
        cents = cents + parseDecimalAsCents(tx.amount)
      } else if (tx.kind === 'expense') {
        cents = cents - parseDecimalAsCents(tx.amount)
      } else if (tx.kind === 'transfer') {
        cents = cents - parseDecimalAsCents(tx.amount)
      }
      // 其它 kind 跳过。
    } else if (tx.transferToAccountId === account.id && tx.kind === 'transfer') {
      // 流水转入侧：仅 transfer 类型时，转入账户余额 += amount。
      cents = cents + parseDecimalAsCents(tx.amount)
    }
  }

  return formatCents(cents)
}

/**
 * 单卡已用额度聚合 —— 含关联流水联动（信用卡消费计入 usedLimit）。
 *
 * 算法骨架：
 *   1. 起点 = card.usedLimit（decimal-as-string；null 时按 "0.00"）；
 *   2. 遍历 txs，仅 tx.cardId == card.id 的流水参与累加；
 *   3. kind=expense → +amount; kind=income（还款）/ transfer → -amount;
 *   4. 输出统一两位小数字符串；负数钳位到 0（业务语义：已用额度最小 = 0）。
 *
 * 边界：
 *   - txs 为空 → 返回 card.usedLimit 规范化结果（null → "0.00"）；
 *   - card.archived=true 时本函数仍正常返回（与 Android 一致；UI 层做归档过滤）。
 *
 * @param card 单张卡（明文 FinanceCard 形态）
 * @param txs 流水列表（全集，函数内部按 cardId 过滤）
 * @return 已用额度（decimal-as-string；两位小数；不会为负）
 */
export function cardUsedLimit(card: CardLike, txs: TxLike[]): string {
  let cents = parseDecimalAsCents(card.usedLimit ?? '0')

  for (const tx of txs) {
    if (tx.cardId !== card.id) continue
    if (tx.kind === 'expense') {
      cents = cents + parseDecimalAsCents(tx.amount)
    } else if (tx.kind === 'income' || tx.kind === 'transfer') {
      cents = cents - parseDecimalAsCents(tx.amount)
    }
    // 其它 kind 跳过。
  }

  // 钳位到 0 —— 还款过度冲销不会出现负数。
  if (cents < 0n) cents = 0n
  return formatCents(cents)
}

/**
 * 月度收支汇总（聚合 income / expense / 分类占比）。
 *
 * 算法骨架：
 *   1. 按 tx.occurredAt 的本地日历日拆出 YYYY-MM 分量；仅命中 yearMonth 的流水参与；
 *   2. kind=income 累加进 income; kind=expense 累加进 expense + 计入 categoryBreakdown;
 *   3. kind=transfer 不计入 income / expense（账户间内部调动）；
 *   4. net = income - expense（cents 整数运算）；
 *   5. txCount 含三类流水（income / expense / transfer）。
 *
 * 边界：
 *   - txs 为空 → 返回全零 MonthlyReport，不抛错；
 *   - tx.occurredAt 跨年跨月时不参与聚合；
 *   - tx.amount / tx.category 为 null → 跳过该项。
 *
 * v2 借款（TR-4.2，loans 默认空列表）：借款本金的发放 / 收回是资产与负债之间
 * 的形态转换，不属于 income / expense，故本函数不把任何 loan 金额累加进
 * income / expense / categoryBreakdown；该参数为 Task5 多币种折算预留。
 *
 * @param yearMonth 年月键 "YYYY-MM"（如 "2026-01"）
 * @param txs 流水列表（全集，函数内部按 occurredAt 本地月过滤）
 * @param _accounts 账户列表（本函数暂未消费；保留参数与签名一致）
 * @param loans v2 借款列表（本期不消费金额；Task5 多币种折算预留）
 * @return MonthlyReport 月度收支汇总（始终非 null）
 */
export function monthlyReport(
  yearMonth: string,
  txs: TxLike[],
  // 保留参数与 Android 签名一致；当前实现按 yearMonth + txs 过滤聚合。
  // 下划线前缀示意有意保留 —— tsconfig noUnusedParameters 下不会告警。
  _accounts: AccountLike[] = [],
  loans: LoanLike[] = [],
): MonthlyReport {
  // v2 预留参数的防御性消费：数组 length 恒 >= 0，本引用不改变任何输出，
  // 仅用于显式消费 loans（对齐 Android check(loans.size >= 0) 的预留风格，
  // 同时通过 noUnusedParameters 检查）。
  void loans

  let incomeCents = 0n
  let expenseCents = 0n
  let txCount = 0
  const categoryCents = new Map<string, bigint>()

  for (const tx of txs) {
    const txYearMonth = yearMonthOf(tx.occurredAt)
    if (txYearMonth !== yearMonth) continue

    txCount++
    if (tx.kind === 'income') {
      incomeCents = incomeCents + parseDecimalAsCents(tx.amount)
    } else if (tx.kind === 'expense') {
      expenseCents = expenseCents + parseDecimalAsCents(tx.amount)
      // 分类占比仅对 expense 累计（与 Android aggregator 语义对齐）。
      const cat = tx.category || 'other'
      const prev = categoryCents.get(cat) ?? 0n
      categoryCents.set(cat, prev + parseDecimalAsCents(tx.amount))
    }
    // transfer 不计入 income / expense 但计入 txCount。
  }

  const netCents: bigint = incomeCents - expenseCents
  const categoryBreakdown: Record<string, string> = {}
  for (const [k, v] of categoryCents.entries()) {
    categoryBreakdown[k] = formatCents(v)
  }

  return {
    yearMonth,
    income: formatCents(incomeCents),
    expense: formatCents(expenseCents),
    net: formatCents(netCents),
    txCount,
    categoryBreakdown,
  }
}

/**
 * 预算阈值告警 —— 支出 vs 月收入比。
 *
 * 算法骨架：
 *   1. 比值 = monthlyExpense / monthlyIncome（仅在 monthlyIncome > 0 时计算；
 *      monthlyIncome = 0 时一律返回 OK —— 零收入时无超支概念）；
 *   2. 状态判定：
 *      - 比值 < 1.0      → OK（支出未达月收入）；
 *      - 1.0 ≤ 比值 < 1.5 → WARNING（超支预警）；
 *      - 比值 ≥ 1.5      → EXCEEDED（严重超支）。
 *
 * @param monthlyIncome 月度收入（decimal-as-string；"0" 表示零收入）
 * @param monthlyExpense 月度支出（decimal-as-string）
 * @param threshold 阈值系数（保留参数与 Android 签名一致；当前按支出/收入比判定）
 * @return BudgetStatus 三档枚举之一（OK / WARNING / EXCEEDED）
 */
export function budgetThreshold(
  monthlyIncome: string,
  monthlyExpense: string,
  threshold: number,
): BudgetStatus {
  // ========== 零收入短路 ==========
  const incomeCents = parseDecimalAsCents(monthlyIncome)
  if (incomeCents <= 0n) return 'OK'

  const expenseCents = parseDecimalAsCents(monthlyExpense)
  // 比值 = expense / income —— 乘 10000 取整数避免 BigInt 截断误差。
  // 公式：ratio_x10000 = expenseCents * 10000 / incomeCents → 1.0 = 10000
  const ratioX10000: bigint = incomeCents === 0n ? 0n : (expenseCents * 10000n) / incomeCents

  // ========== 三档判定 ==========
  // 1.0 = 10000; 1.5 = 15000
  if (ratioX10000 < 10000n) return 'OK'
  if (ratioX10000 < 15000n) return 'WARNING'
  return 'EXCEEDED'

  // threshold 参数保留以与 Android 签名一致；当前三档判定基于"支出/收入比"
  // 而非 threshold 系数（与 Android FinanceAggregator.budgetThreshold 同款）。
  void threshold
}

// -----------------------------------------------------------------------------
// 私有工具方法 —— decimal-as-string 运算（CST 本地月份 + cents 整数算术）
// -----------------------------------------------------------------------------

/**
 * decimal-as-string 解析为"分"（cents = 整数，BigInt）—— 避免 Double 浮点精度丢失。
 *
 * 支持格式：
 *   - 整数 "1234" → 123400
 *   - 一位小数 "1234.5" → 123450
 *   - 两位小数 "1234.56" → 123456
 *   - 三位以上小数 "1234.567" → 截断到分 (123456) —— 与 Android 端一致
 *   - 负数 "-100.00" → -10000
 *
 * 边界：
 *   - 空串 / null / 非数字 → 返回 0n（不抛错）；
 *   - 仅含 - / + / . → 返回 0n（不抛错）。
 */
function parseDecimalAsCents(s: string | null | undefined): bigint {
  if (s == null || typeof s !== 'string') return 0n
  const trimmed = s.trim()
  if (trimmed.length === 0) return 0n

  let negative = false
  let seenDot = false
  let whole = 0n
  let frac = 0n
  let fracDigits = 0

  for (let i = 0; i < trimmed.length; i++) {
    const c = trimmed.charCodeAt(i)
    if (c === 45 /* '-' */ && whole === 0n && !seenDot && !negative) {
      negative = true
    } else if (c === 43 /* '+' */ && whole === 0n && !seenDot && !negative) {
      // 显式正号，忽略。
    } else if (c === 46 /* '.' */ && !seenDot) {
      seenDot = true
    } else if (c >= 48 && c <= 57 /* 0-9 */) {
      const digit = BigInt(c - 48)
      if (seenDot) {
        // 截断到 2 位小数 —— 与 Android 端 parseDecimalAsCents 行为一致。
        if (fracDigits < OUTPUT_SCALE) {
          frac = frac * 10n + digit
          fracDigits++
        }
      } else {
        whole = whole * 10n + digit
      }
    } else {
      // 非数字字符 → 返回 0（不抛错）。
      return 0n
    }
  }

  // frac 不足 2 位时按 0 补齐 —— "1.5" → frac=50 → cents = 150。
  while (fracDigits < OUTPUT_SCALE) {
    frac = frac * 10n
    fracDigits++
  }

  let cents = whole * 100n + frac
  if (negative) cents = -cents
  return cents
}

/**
 * cents → decimal-as-string 输出（统一两位小数）。
 *
 * 例：123456n → "1234.56"; -100n → "-1.00"; 0n → "0.00"。
 */
function formatCents(cents: bigint): string {
  const negative = cents < 0n
  const abs = negative ? -cents : cents
  const whole = abs / 100n
  const frac = abs % 100n
  // frac 补齐两位 —— 0 → "00", 5 → "05"。
  const fracStr = frac.toString().padStart(OUTPUT_SCALE, '0')
  const sign = negative ? '-' : ''
  return `${sign}${whole.toString()}.${fracStr}`
}

/**
 * Unix ms → 本地年月键 "YYYY-MM"（与 Android yearMonthOf 同款 CST 口径）。
 *
 * 实现：先把 ms 加上 tz_offset_ms 视为"无时区 UTC 时刻"，再用 ISO 字段读 y/m。
 */
function yearMonthOf(ts: number): string {
  const shifted = ts + TZ_OFFSET_MIN * 60_000
  const dt = new Date(shifted)
  const y = dt.getUTCFullYear()
  const m = dt.getUTCMonth() + 1
  const mm = m.toString().padStart(2, '0')
  return `${y}-${mm}`
}

// -----------------------------------------------------------------------------
// 便捷适配器 —— 把 schema FinanceAccount / FinanceCard / FinanceTx 形态转换为
// aggregator 入参 DTO（store 层调聚合时使用）
// -----------------------------------------------------------------------------

/** FinanceAccount → AccountLike。 */
export function toAccountLike(a: FinanceAccount): AccountLike {
  return {
    id: a.id,
    balance: a.balance,
    currency: a.currency,
    archived: a.archived,
  }
}

/** FinanceCard → CardLike。 */
export function toCardLike(c: FinanceCard): CardLike {
  return {
    id: c.id,
    kind: c.kind,
    usedLimit: c.used_limit ?? null,
    archived: c.archived,
  }
}

/** FinanceTx → TxLike。 */
export function toTxLike(tx: FinanceTx): TxLike {
  return {
    id: tx.id,
    accountId: tx.account_id,
    cardId: tx.card_id ?? null,
    kind: tx.kind,
    amount: tx.amount,
    category: tx.category,
    occurredAt: tx.occurred_at,
    transferToAccountId: tx.transfer_to_account_id ?? null,
  }
}

/**
 * FinanceLoan → 净资产聚合 LoanLike（snake_case → camelCase DTO）。
 *
 * 仅承载金额 / 方向 / 是否计入 / 货币 / status；到期提醒场景请改用
 * nextCardFiring.ts 的同名 toLoanLike（字段集为 status / dueTs / reminders）。
 */
export function toLoanLike(p: FinanceLoan): LoanLike {
  return {
    id: p.id,
    direction: p.direction,
    principalMinor: p.principal_minor,
    paidMinor: p.paid_minor,
    includeInNetAssets: p.include_in_net_assets,
    currency: p.currency,
    status: p.status,
  }
}