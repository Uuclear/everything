// ============================================================================
// BudgetEnforcer —— 财务 v2 B6 预算硬约束 / 超支拦截双端纯函数基础层（Web）
// ============================================================================
//
// 任务: stage5-finance-v2 / Task 6（预算硬约束 + 超支拦截）第一批纯函数
// 路径: web/src/finance/budgetEnforcer.ts
// 作用: 保存（或编辑）一笔支出流水前，按候选预算实时计算预计累计占预算
//       比例，输出三档结果 OK / WARNING / BLOCK；异币流水经 B5 离线汇率表
//       convertMinor 折算到预算币种。纯函数模块，不依赖 Network / Database /
//       DOM / localStorage / store / 路由。
//
// 铁律口径（与 Android BudgetEnforcer.kt 逐分支锁定）:
//   1. 金额一律 minor（分）bigint 整数算术：decimal 元字符串由 parseCents
//      拆整数段 / 小数段拼接（整数字符串直接乘 100，小数一至二位右侧补
//      零），禁止 Number 浮点；占比为 bigint 整除向下取整；
//   2. 月 / 周 / 年分桶一律 CST（UTC+8，固定 +8 小时偏移，中国无夏令时）：
//      ts 加 8 小时后用 Date 的 getUTC* 系列读日历分量，与 aggregator.ts
//      月分桶手法一致，与运行机器系统时区无关；
//   3. 预算有效期 start_ts / end_ts 为双闭区间（含两端），流水发生时刻在
//      有效期外该预算完全不命中；桶区间为半开 [bucketStart, bucketEnd)，
//      custom 统一为 [startTs, endTs + 1) 以保证 endTs 当刻包含；
//   4. weekly 不以周一为锚，而以预算 start_ts 所在 CST 日期零点为 epoch，
//      每 7 天一个滚动桶；
//   5. 缺汇率保守放行：本笔无法折算到预算币种时整个预算跳过，不误拦；
//   6. 编辑场景：existing 中与本笔同 id 的旧记录排除，不重复累计；
//   7. 多预算命中取最严重（BLOCK 大于 WARNING 大于 OK），同档取 usedPct
//      更大，再同则取 budgetId 字典序最小（双端稳定性兜底）。
//
// 关联:
//   - android/app/src/main/java/com/everything/eve/finance/BudgetEnforcer.kt
//     （Android 镜像，Long 口径与本文件逐函数对应）
//   - web/src/finance/rateTable.ts（B5 折算纯函数 convertMinor）
//   - web/src/finance/types.ts（FinanceBudget 类型真理源）
//   - web/src/finance/__fixtures__/budget-enforcer-cases.json（双端共享 fixture）
// ============================================================================

import { convertMinor, type RateTable } from './rateTable'
import type { FinanceBudget } from './types'

/**
 * 待存 / 已存支出流水最小形态（与持久化流水模型解耦）。
 *
 * 仅承载预算判定需要的六个字段；amountMinor 为 decimal 元字符串
 * （如 "100.00"），由本模块内部按 minor 分 bigint 解析，与 v2 record 的
 * 金额字段同口径。
 */
export interface BudgetTxLike {
  /** 流水 id；编辑既有流水时 existing 中同 id 旧记录会被排除。 */
  id: string
  /** 流水类型；仅 'expense' 参与预算判定。 */
  kind: string
  /** 流水金额 decimal 元字符串。 */
  amountMinor: string
  /** 流水分类；预算 'all' 匹配任意分类。 */
  category: string
  /** 流水币种（ISO 4217 三字母代码）。 */
  currency: string
  /** 流水发生时刻（Unix 毫秒；所属周期桶以此为锚）。 */
  occurredAt: number
}

/**
 * 单笔支出针对命中预算的三档判定级别。
 */
export type BudgetLevel = 'OK' | 'WARNING' | 'BLOCK'

/**
 * 单笔支出针对某条命中预算的判定结果。
 *
 * 金额字段全部为预算币种 minor 分 bigint：
 *   - projected = spent + incoming；
 *   - usedPct = projected * 100 / limit，bigint 整除向下取整（80 表示 80%）。
 */
export interface BudgetCheckResult {
  /** 三档级别。 */
  level: BudgetLevel
  /** 命中预算 id；无任何命中时为 null。 */
  budgetId: string | null
  /** 命中预算的分类匹配值（可能为特殊值 'all'）；无命中为空串。 */
  category: string
  /** 命中预算币种；无命中为空串。 */
  currency: string
  /** 同周期桶内既有支出折算合计（已排除编辑自身）。 */
  spentMinor: bigint
  /** 本笔待存支出折算到预算币种的金额。 */
  incomingMinor: bigint
  /** 预计累计 = spentMinor + incomingMinor。 */
  projectedMinor: bigint
  /** 预算额度（minor 分）。 */
  limitMinor: bigint
  /** 预计累计占预算百分比（整数，向下取整）。 */
  usedPct: number
  /** 命中档阈值百分数；OK 时填预警阈值。 */
  thresholdPct: number
}

// =============================================================================
// 常量
// =============================================================================

/** 无任何命中预算时的统一空结果（level=OK，金额全 0，标识为空）。 */
export const OK_EMPTY: BudgetCheckResult = {
  level: 'OK',
  budgetId: null,
  category: '',
  currency: '',
  spentMinor: 0n,
  incomingMinor: 0n,
  projectedMinor: 0n,
  limitMinor: 0n,
  usedPct: 0,
  thresholdPct: 0,
}

/** Asia/Shanghai（CST）相对 UTC 的固定偏移毫秒（中国无夏令时，全年 +8h）。 */
const CST_OFFSET_MS = 8 * 60 * 60 * 1000

/** 一天的毫秒数（周桶推导用）。 */
const DAY_MS = 24 * 60 * 60 * 1000

/** 一周的毫秒数。 */
const WEEK_MS = 7 * DAY_MS

/**
 * decimal 元字符串合法形态：非负整数部分加最多两位小数。
 */
const AMOUNT_RE = /^(\d+)(?:\.(\d{1,2}))?$/

// =============================================================================
// 金额解析
// =============================================================================

/**
 * decimal 元字符串解析为 minor 分 bigint（双端同口径，禁止浮点）。
 *
 * 整数字符串直接乘 100；含一至两位小数时把小数段右侧补零后按整数
 * 拼接（"12.3" 的 3 表示 30 分）。非法字符串（空串 / 符号 / 字母 /
 * 超过两位小数等）一律返回 0n，不抛异常。
 */
function parseCents(decimal: string): bigint {
  const m = AMOUNT_RE.exec(decimal)
  if (!m) return 0n
  const whole = m[1]
  // 无小数段补 "00"；一位小数右侧补一个 0（"12.3" 的 3 表示 30 分）。
  const fracRaw = m[2] ?? ''
  const frac = fracRaw.length === 0 ? '00' : fracRaw.padEnd(2, '0')
  return BigInt(whole) * 100n + BigInt(frac)
}

// =============================================================================
// 周期分桶
// =============================================================================

/**
 * 返回 txTs 所属预算周期桶 [bucketStartMs, bucketEndMs)（末点开区间）。
 *
 * 先做有效期判定：txTs 不在 [startTs, endTs] 双闭区间内直接返回 null
 * （该预算对该笔完全不适用）。有效期内再按 scope 分桶：
 *   - custom：统一返回 [startTs, endTs + 1)，把含终点的有效期转成半开
 *     区间，保证 endTs 当天 / 当刻包含、endTs 之后一毫秒排除；
 *   - monthly：txTs 所在 CST 自然月 1 日 00:00 到次月 1 日 00:00；
 *   - yearly：txTs 所在 CST 自然年 1 月 1 日 00:00 到次年 1 月 1 日；
 *   - weekly：epoch 为 startTs 所在 CST 日期 00:00；按
 *     idx = floor((txCstMidnight - epoch) / 7 天) 取桶。
 *
 * 未知 scope 返回 null。全程固定 +8h 偏移读 UTC 分量，跨年 / 跨月正确，
 * 且与运行机器默认时区无关。
 */
export function periodBucket(
  scope: string,
  startTs: number,
  endTs: number,
  txTs: number,
): readonly [number, number] | null {
  // 有效期双闭区间判定（四种 scope 共用）。
  if (txTs < startTs || txTs > endTs) return null

  if (scope === 'custom') {
    return [startTs, endTs + 1]
  }

  // 加固定 8 小时偏移后按 UTC 读分量，等价于在 UTC+8 时区读取本地日历。
  const shifted = new Date(txTs + CST_OFFSET_MS)
  const y = shifted.getUTCFullYear()
  const month0 = shifted.getUTCMonth()

  if (scope === 'monthly') {
    const bucketStart = Date.UTC(y, month0, 1) - CST_OFFSET_MS
    const bucketEnd = Date.UTC(y, month0 + 1, 1) - CST_OFFSET_MS
    return [bucketStart, bucketEnd]
  }

  if (scope === 'yearly') {
    const bucketStart = Date.UTC(y, 0, 1) - CST_OFFSET_MS
    const bucketEnd = Date.UTC(y + 1, 0, 1) - CST_OFFSET_MS
    return [bucketStart, bucketEnd]
  }

  if (scope === 'weekly') {
    // epoch 锚定预算 startTs 所在 CST 日期零点（不是周一）。
    const startShifted = new Date(startTs + CST_OFFSET_MS)
    const epoch =
      Date.UTC(
        startShifted.getUTCFullYear(),
        startShifted.getUTCMonth(),
        startShifted.getUTCDate(),
      ) - CST_OFFSET_MS
    // txTs 所在 CST 日期零点。
    const txMidnight =
      Date.UTC(y, month0, shifted.getUTCDate()) - CST_OFFSET_MS
    // floor 语义与 Kotlin Math.floorDiv 对齐（JS 除法配合 Math.floor 对
    // 极早期负天数差同样向下取整）。
    const idx = Math.floor((txMidnight - epoch) / WEEK_MS)
    const bucketStart = epoch + idx * WEEK_MS
    return [bucketStart, bucketStart + WEEK_MS]
  }

  // 未知 scope 不命中。
  return null
}

// =============================================================================
// 折算与单预算评估
// =============================================================================

/**
 * 把 decimal 元金额折算到预算币种 minor 分 bigint。
 *
 * 同币种直接 parseCents；异币种委托 convertMinor；table 为 null 或
 * convertMinor 返回 null（缺双向汇率）时返回 null，由调用方保守跳过
 * 该预算（不误拦）。
 */
function toBudgetCents(
  amountDecimal: string,
  fromCcy: string,
  budgetCcy: string,
  table: RateTable | null,
): bigint | null {
  if (fromCcy === budgetCcy) return parseCents(amountDecimal)
  if (table === null) return null
  return convertMinor(parseCents(amountDecimal), fromCcy, budgetCcy, table)
}

/**
 * 在已知命中预算与同桶已花金额的前提下，评估三档结果。
 *
 * limit 为预算额度 minor 分（入参预算应已通过 validateBudget，额度必为
 * 正）；projected = spent + incoming；usedPct 为 bigint 整除向下取整后
 * 转回 number（取值仅万级，远小于 Number 安全整数上限）。达到 block
 * 阈值为 BLOCK，否则达到 warning 阈值为 WARNING，否则 OK；thresholdPct
 * 填命中档阈值（OK 时填预警阈值）。
 */
export function evaluateBudget(
  budget: FinanceBudget,
  spentMinor: bigint,
  incomingMinor: bigint,
): BudgetCheckResult {
  const limitMinor = parseCents(budget.amount_minor)
  const projectedMinor = spentMinor + incomingMinor
  const usedPct = Number((projectedMinor * 100n) / limitMinor)
  let level: BudgetLevel
  if (usedPct >= budget.block_threshold_pct) {
    level = 'BLOCK'
  } else if (usedPct >= budget.warning_threshold_pct) {
    level = 'WARNING'
  } else {
    level = 'OK'
  }
  const thresholdPct =
    level === 'BLOCK'
      ? budget.block_threshold_pct
      : level === 'WARNING'
        ? budget.warning_threshold_pct
        : budget.warning_threshold_pct
  return {
    level,
    budgetId: budget.id,
    category: budget.category,
    currency: budget.currency,
    spentMinor,
    incomingMinor,
    projectedMinor,
    limitMinor,
    usedPct,
    thresholdPct,
  }
}

// =============================================================================
// 门面：单笔支出对全量候选预算的最严重命中
// =============================================================================

/**
 * 保存 / 编辑单笔支出前的预算硬约束门面判定。
 *
 * 流程：
 *   1. incoming.kind 不等于 'expense'（收入 / 转账等）直接返回 OK_EMPTY；
 *   2. 遍历 budgets，依次跳过：active=false；incoming.occurredAt 不在
 *      预算有效期 / 所属周期桶为 null；分类不匹配（预算 category 非
 *      'all' 且不等于流水分类）；本笔无法折算到预算币种（无表 / 缺汇率，
 *      保守放行）；
 *   3. spentMinor 汇总 existing 中同时满足：kind 为 expense、id 与本笔
 *      不同（编辑场景排除自身旧额）、与本笔同周期桶（桶起点相等）、分类
 *      匹配、金额可折算到预算币种的全部金额；
 *   4. 调 evaluateBudget 得候选结果，多预算取最严重，同档取 usedPct
 *      更大，再同取 budgetId 字典序最小；无任何命中返回 OK_EMPTY。
 *
 * 关于 nowMs：当前周期一律以 incoming.occurredAt 为锚（补录 / 编辑历史
 * 日期时按业务日期所属周期判定），因此 nowMs 不参与任何计算；保留该形参
 * 供未来“保存时刻”口径扩展，双端签名对称，当前调用传任意毫秒均可。
 *
 * @param incoming 本笔待存 / 待更新支出（金额为 decimal 元字符串）
 * @param budgets 候选预算全集
 * @param existing 既有支出流水全集（编辑场景同 id 旧记录自动排除）
 * @param rateTable 可选 B5 离线汇率表；null 时异币金额不可折算
 * @param nowMs 保存时刻（预留，当前不参与判定）
 */
export function checkTx(
  incoming: BudgetTxLike,
  budgets: readonly FinanceBudget[],
  existing: readonly BudgetTxLike[],
  rateTable: RateTable | null = null,
  nowMs: number,
): BudgetCheckResult {
  // 形参 nowMs 语义预留：显式标记未使用，避免后续接线时误以为它参与分桶。
  void nowMs
  if (incoming.kind !== 'expense') return OK_EMPTY

  let winner: BudgetCheckResult | null = null
  for (const budget of budgets) {
    if (!budget.active) continue
    const incomingBucket = periodBucket(
      budget.scope,
      budget.start_ts,
      budget.end_ts,
      incoming.occurredAt,
    )
    if (incomingBucket === null) continue
    if (!categoryMatch(budget.category, incoming.category)) continue
    const incomingConverted = toBudgetCents(
      incoming.amountMinor,
      incoming.currency,
      budget.currency,
      rateTable,
    )
    if (incomingConverted === null) continue

    let spentMinor = 0n
    for (const history of existing) {
      if (history.kind !== 'expense') continue
      // 编辑既有流水：排除自身旧额，避免同一笔被重复累计。
      if (history.id === incoming.id) continue
      if (!categoryMatch(budget.category, history.category)) continue
      const historyBucket = periodBucket(
        budget.scope,
        budget.start_ts,
        budget.end_ts,
        history.occurredAt,
      )
      if (historyBucket === null) continue
      // 同周期桶：以桶起点相等判定（末点开区间不直接参与比较）。
      if (historyBucket[0] !== incomingBucket[0]) continue
      const historyConverted = toBudgetCents(
        history.amountMinor,
        history.currency,
        budget.currency,
        rateTable,
      )
      // 无法折算的异币历史条目跳过（不做面值降级，也不阻断其余条目）。
      if (historyConverted === null) continue
      spentMinor += historyConverted
    }

    const candidate = evaluateBudget(budget, spentMinor, incomingConverted)
    if (isMoreSevere(candidate, winner)) winner = candidate
  }
  return winner ?? OK_EMPTY
}

// =============================================================================
// 私有辅助
// =============================================================================

/** 分类匹配：预算分类为 'all' 时匹配任意支出分类，否则要求严格相等。 */
function categoryMatch(budgetCategory: string, txCategory: string): boolean {
  return budgetCategory === 'all' || budgetCategory === txCategory
}

/** 严重度序数：BLOCK 最高。 */
function severityRank(level: BudgetLevel): number {
  if (level === 'OK') return 0
  if (level === 'WARNING') return 1
  return 2
}

/**
 * 候选之间的严格优先比较：级别更严优先；同级 usedPct 更大优先；仍相同
 * budgetId 字典序更小优先（current 为 null 时候选直接胜出）。
 */
function isMoreSevere(
  candidate: BudgetCheckResult,
  current: BudgetCheckResult | null,
): boolean {
  if (current === null) return true
  const rankDelta = severityRank(candidate.level) - severityRank(current.level)
  if (rankDelta !== 0) return rankDelta > 0
  if (candidate.usedPct !== current.usedPct) return candidate.usedPct > current.usedPct
  const currentId = current.budgetId ?? ''
  const candidateId = candidate.budgetId ?? ''
  return candidateId < currentId
}
