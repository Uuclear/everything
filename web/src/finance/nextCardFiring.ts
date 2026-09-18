// ============================================================================
// 财务模块 —— 信用卡下一触发时刻纯函数（stage5-finance / T8 / TR-8.3）
// ============================================================================
//
// 任务: stage5-finance / Task 8 / TR-8.3
// 路径: web/src/finance/nextCardFiring.ts
// 作用: 计算信用卡下一触发时刻（账单日 T+0 09:00 / 还款日 T-1 09:00 CST），
//       供 Reminder / 卡片列表"下次提醒"列复用；纯函数，无副作用，不依赖 DOM /
//       localStorage / 网络。
//
// 设计要点（与 Android NextCardFiring.kt 字节级一致 —— 三端契约）：
//   1. 纯函数 —— 入参 (card, nowMs) -> 返回 nextTrigger 候选 ms；
//   2. 双触发口径（与 spec FR-5 / docs/finance.md §5 一致）：
//      - **账单日触发**：card.billingDay 当月 / 下月 T+0 09:00 CST；
//      - **还款日触发**：card.billingDay + card.dueDayOffset 当月 / 下月 T-1 09:00 CST；
//   3. due_day 语义 —— 是相对账单日的 offset 天数（1-31），不是绝对日；
//   4. nextTrigger 选取规则 —— 取两类候选中**最近一次未来触发**；
//   5. 边界 —— archived=true / billingDay=null → 返回 null；dueDay=null → 跳过还款日候选。
//
// 关联:
//   - tasks.md TR-8.3（Web nextCardFiring.ts 镜像实现）
//   - tasks.md TR-8.5（Vitest 测试套件 ≥6 用例）
//   - android/app/src/main/java/com/everything/eve/finance/NextCardFiring.kt（Android 镜像）
//   - web/src/finance/__fixtures__/nextCardFiring-cases.json（共享 fixture）
//   - docs/schemas/finance.schema.json#/$defs/FinanceCard（字段真理源）
// ============================================================================

import type { FinanceCard, FinanceLoan, FinancePolicy, FinanceSubscription } from './types'

// -----------------------------------------------------------------------------
// 内部数据形态 —— 入参 DTO（与 Android NextCardFiring.CardLike 对齐）
// -----------------------------------------------------------------------------

/**
 * 卡入参形态 —— 仅取触发计算所需的最小字段集（解耦 schema 完整字段）。
 *
 * Web 与 Android 三端契约字段一致：id / kind / billingDay / dueDay / archived。
 */
export interface CardLike {
  id: string
  kind: string
  /** 账单日（每月 day_of_month；1-31）；null 表示未配置。 */
  billingDay: number | null
  /** 还款日相对账单日的 offset 天数（1-31）；null 表示未配置。 */
  dueDay: number | null
  archived: boolean
}

// ============================================================================
// v2 入参 DTO（stage5-finance-v2 / TR-4.1）—— 与 Android
// NextCardFiring.SubscriptionLike / PolicyLike / LoanLike 对齐
// ============================================================================

/**
 * 订阅入参形态（v2）—— 字段命名与 FinanceSubscription 的 camelCase DTO 对齐。
 *
 * @property billingCycle monthly | quarterly | yearly | custom_days
 * @property customDays billingCycle=custom_days 时的周期天数；其余周期为 null
 * @property reminders 提醒分钟偏移列表（0 = 续费当日；1440 = 提前一天）
 */
export interface SubscriptionLike {
  id: string
  active: boolean
  nextRenewalTs: number
  billingCycle: string
  customDays: number | null
  reminders: number[]
}

/**
 * 保单入参形态（v2）。
 *
 * @property expiryTs 保单到期时刻（一次性基准，不滚动）
 * @property reminders 提醒分钟偏移列表（0 = 到期当日）
 */
export interface PolicyLike {
  id: string
  active: boolean
  expiryTs: number
  reminders: number[]
}

/**
 * 借款入参形态（v2）—— 到期提醒专用最小字段集。
 *
 * 注意：本接口与 aggregator.ts 导出的同名 LoanLike 字段集不同
 * （本接口服务到期提醒：status / dueTs；aggregator 那个服务净资产聚合：
 * 金额 / 方向 / 是否计入），与 Android 端两个同名 data class 的拆分一致；
 * 同文件消费二者时用 import 别名消歧。
 *
 * @property status active | partially_paid | paid | overdue；仅 paid 不提醒
 * @property dueTs 借款到期时刻（一次性基准，不滚动）
 * @property reminders 提醒分钟偏移列表（0 = 到期当日）
 */
export interface LoanLike {
  id: string
  status: string
  dueTs: number
  reminders: number[]
}

// -----------------------------------------------------------------------------
// 常量区 —— 触发时刻边界（与 spec FR-5 / docs/finance.md §5 一致）
// -----------------------------------------------------------------------------

/** 触发时刻小时 —— 09:00 CST（本地时区上午 9 点整；spec FR-5）。 */
const TRIGGER_HOUR = 9

/** 触发时刻分钟 —— 0 分整（与 spec FR-5 一致）。 */
const TRIGGER_MINUTE = 0

/**
 * 还款日提前天数 —— T-1（spec FR-5；tasks.md TR-5.2 一致）。
 *
 * 注：本常量作为语义锚点保留 —— 实际跨月跨年减一天逻辑封装在
 * `previousDay` 工具方法中；导出常量便于文档阅读与未来扩展（如改为 T-3）。
 */
const PAYMENT_DUE_DAYS_BEFORE = 1

/**
 * v2 订阅续费向前滚动的防御性周期上限（stage5-finance-v2 / TR-4.1）。
 *
 * 业务语义：当 nextRenewalTs 因数据异常停留在远古时刻（如 1970 年）时，
 * 纯函数不能无限循环滚动；最多向前滚动 24 个计费周期（monthly 约 2 年，
 * custom_days=1 为 24 天）仍不超过 nowMs 即放弃，返回 null。
 * 与 Android NextCardFiring.MAX_CYCLE_LOOKAHEAD 同值。
 */
const MAX_CYCLE_LOOKAHEAD = 24

/** 一分钟对应的毫秒数（v2 reminders 偏移单位换算；与 Android MINUTE_MS 一致）。 */
const MINUTE_MS = 60_000

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
// 公开 API —— 两个纯函数（与 Android NextCardFiring 签名一致）
// -----------------------------------------------------------------------------

/**
 * 计算单张卡的最近一次未来触发时刻（账单日 / 还款日 T-1，取最近未来）。
 *
 * 算法骨架（与 Android NextCardFiring.kt `nextTrigger` 完全对齐）：
 *   1. 早退 —— `archived=true` 或 `billingDay=null` → 返回 null；
 *   2. 计算当月账单日 T+0 09:00 CST 对应 ms；若 ≤ nowMs → 改用下月账单日；
 *   3. 计算当月还款日 T-1 09:00 CST 对应 ms；若 ≤ nowMs → 改用下月还款日；
 *   4. `dueDay=null` → 跳过还款日候选；
 *   5. 在所有未来候选中取最小值（即"最近一次未来触发"）。
 *
 * 边界场景：
 *   - `card.archived=true` → 返回 null（不触发）；
 *   - `card.billingDay=null` → 返回 null（未配置账单日）；
 *   - `card.dueDay=null` → 仅返回账单日触发；
 *   - 当月 / 下月账单日均已过 且 dueDay=null → 返回 null；
 *
 * @param card 单张卡（含 billingDay / dueDay / archived 字段）
 * @param nowMs 当前时刻（Unix 毫秒；调用方提供，便于测试锚定）
 * @return 最近一次未来触发的 Unix 毫秒；无候选时返回 null
 */
export function nextTrigger(card: CardLike, nowMs: number): number | null {
  // ========== 1. 早退守卫 ==========
  // 归档卡 / 未配置账单日一律不触发 —— 业务语义：不打扰。
  if (card.archived) return null
  const billingDay = card.billingDay
  if (billingDay == null) return null

  // ========== 2. 拆 nowMs 本地日历分量 ==========
  // 与 4b Recurrence.ts 同口径：ms + tz_offset_ms 当 UTC ms 读，y/m/d 拆出来。
  const [nowY, nowM] = localPartsOfAsUtc(nowMs)

  // ========== 3. 计算账单日候选（当月 + 下月，选 ≥ nowMs） ==========
  const statementCandidates: number[] = []
  // 当月账单日 T+0 09:00 CST
  const currentMonth = tryLocalDateToMs(nowY, nowM, billingDay, TRIGGER_HOUR, TRIGGER_MINUTE)
  if (currentMonth !== null) statementCandidates.push(currentMonth)
  // 下月账单日 T+0 09:00 CST（兜底：当月已过时使用）
  const nextMonthDate = nextMonthOf(nowY, nowM)
  const nextMonth = tryLocalDateToMs(
    nextMonthDate.year,
    nextMonthDate.month,
    billingDay,
    TRIGGER_HOUR,
    TRIGGER_MINUTE,
  )
  if (nextMonth !== null) statementCandidates.push(nextMonth)

  const futureStatement = statementCandidates.filter((ms) => ms > nowMs)

  // ========== 4. 计算还款日 T-1 候选（仅在 dueDay 非空时） ==========
  let futurePayment: number[] = []
  if (card.dueDay != null) {
    const dueDayOffset = card.dueDay
    // 当月还款日 T-1
    const currentMonthDue = computePaymentDueDayMs(nowY, nowM, billingDay, dueDayOffset)
    if (currentMonthDue !== null) futurePayment.push(currentMonthDue)
    // 下月还款日 T-1
    const nextMonthDue = computePaymentDueDayMs(
      nextMonthDate.year,
      nextMonthDate.month,
      billingDay,
      dueDayOffset,
    )
    if (nextMonthDue !== null) futurePayment.push(nextMonthDue)
    futurePayment = futurePayment.filter((ms) => ms > nowMs)
  }

  // ========== 5. 取两类候选的最小值（最近一次未来触发） ==========
  const allCandidates = [...futureStatement, ...futurePayment]
  if (allCandidates.length === 0) return null
  return Math.min(...allCandidates)
}

/**
 * 多卡批量计算下一次触发时刻（取全局最小 nextTrigger，按 ms 升序截取前 N 个）。
 *
 * 算法骨架（与 Android NextCardFiring.kt `upcomingTriggers` 对齐）：
 *   1. 遍历 cards，对每张卡调 `nextTrigger(card, nowMs)`；
 *   2. 收集所有非 null 候选，按 ms 升序排序；
 *   3. 返回前 N 个（默认 N=5，与 ReminderScheduler 一次性取全局最小一致）。
 *
 * @param cards 卡列表（全集，函数内部按 archived / billingDay 过滤）
 * @param nowMs 当前时刻（Unix 毫秒）
 * @param limit 返回上限（默认 5；≤ 0 时返回空列表）
 * @return 升序排列的未来触发时刻列表（最长 limit 条；全空时返回空列表）
 */
export function upcomingTriggers(
  cards: CardLike[],
  nowMs: number,
  limit: number = 5,
): number[] {
  if (limit <= 0) return []
  // nextTrigger 内部已处理 archived / billingDay=null 守卫。
  const triggers = cards
    .map((c) => nextTrigger(c, nowMs))
    .filter((v): v is number => v !== null)
    .sort((a, b) => a - b)
  return triggers.slice(0, limit)
}

// ============================================================================
// v2 公开 API —— 订阅 / 保单 / 借款下一提醒（stage5-finance-v2 / TR-4.1，
// 与 Android NextCardFiring.kt 三个同名纯函数逐条对齐）
// ============================================================================

/**
 * 计算订阅条目的下一次续费提醒时刻（取最近未来候选）。
 *
 * 算法骨架（与 Android NextCardFiring.nextSubscriptionRenewal 完全对齐）：
 *   1. 早退 —— active=false 或 reminders 为空 → 返回 null；
 *   2. 基准实例 = sub.nextRenewalTs；若已 ≤ nowMs，按 billingCycle 用 UTC 日历
 *      习语（与现有 tryLocalDateToMs 同口径，先加时区偏移当 UTC 读）做日历加法
 *      向前滚动，保留原 ts 的时分秒毫秒：
 *        - monthly     → +1 个日历月；
 *        - quarterly   → +3 个日历月；
 *        - yearly      → +1 个日历年；
 *        - custom_days → +customDays 天；customDays 为 null 或 ≤0 → null；
 *      月末日期滚动由日历自然裁剪（如 1/31 + 1 月 = 2/28，与 java.time 一致）；
 *   3. 防御性上限 —— 连续滚动 MAX_CYCLE_LOOKAHEAD（24）个周期仍 ≤ nowMs → null；
 *      未知 cycle 字符串在基准过时时无法滚动 → null；
 *   4. 对最终续费实例计算候选 { renewal - r*60000 | r in reminders, r>=0 }，
 *      仅保留严格 > nowMs 的候选，返回最小值；无候选返回 null。
 *
 * @param sub 订阅入参 DTO
 * @param nowMs 当前时刻（Unix 毫秒；由调用方提供，便于测试锚定）
 * @return 最近一次未来提醒的 Unix 毫秒；无候选时返回 null
 */
export function nextSubscriptionRenewal(
  sub: SubscriptionLike,
  nowMs: number,
): number | null {
  // ========== 1. 早退守卫 ==========
  if (!sub.active) return null
  if (sub.reminders.length === 0) return null

  // ========== 2. 基准实例过期则按周期向前滚动 ==========
  let renewal = sub.nextRenewalTs
  let cycles = 0
  while (renewal <= nowMs) {
    // 达到防御性上限仍落过去 → 视为异常数据，放弃提醒。
    if (cycles >= MAX_CYCLE_LOOKAHEAD) return null
    const rolled = rollRenewal(renewal, sub.billingCycle, sub.customDays)
    if (rolled === null) return null
    renewal = rolled
    cycles++
  }

  // ========== 3. 在未来提醒候选中取最小值 ==========
  return earliestFutureReminder(renewal, sub.reminders, nowMs)
}

/**
 * 计算保单条目的下一次到期提醒时刻（一次性基准，不滚动）。
 *
 * 算法骨架（与 Android nextPolicyExpiry 完全对齐）：
 *   1. 早退 —— active=false 或 reminders 为空 → 返回 null；
 *   2. 基准实例固定 = policy.expiryTs（保单到期是一次性事件，不做周期滚动）；
 *   3. 候选 { expiryTs - r*60000 | r in reminders, r>=0 } 过滤严格 > nowMs 取最小；
 *      全部已过期 → null。
 *
 * @param policy 保单入参 DTO
 * @param nowMs 当前时刻（Unix 毫秒）
 * @return 最近一次未来提醒的 Unix 毫秒；无候选时返回 null
 */
export function nextPolicyExpiry(policy: PolicyLike, nowMs: number): number | null {
  if (!policy.active) return null
  if (policy.reminders.length === 0) return null
  return earliestFutureReminder(policy.expiryTs, policy.reminders, nowMs)
}

/**
 * 计算借款条目的下一次到期提醒时刻（一次性基准，不滚动）。
 *
 * 算法骨架（与 Android nextLoanDue 完全对齐）：
 *   1. 早退 —— status 恰为 "paid"（已结清）或 reminders 为空 → 返回 null；
 *      active / partially_paid / overdue 以及任何未知 status 均继续提醒
 *      （逾期未还更应提醒，未知状态按防御性"照常提醒"处理）；
 *   2. 基准实例固定 = loan.dueTs（借款到期是一次性事件）；
 *   3. 候选 { dueTs - r*60000 | r in reminders, r>=0 } 过滤严格 > nowMs 取最小；
 *      全部已过期 → null。
 *
 * @param loan 借款入参 DTO
 * @param nowMs 当前时刻（Unix 毫秒）
 * @return 最近一次未来提醒的 Unix 毫秒；无候选时返回 null
 */
export function nextLoanDue(loan: LoanLike, nowMs: number): number | null {
  // 仅已结清借款不再提醒；其余状态（含 overdue 逾期与未知状态）一律继续提醒。
  if (loan.status === 'paid') return null
  if (loan.reminders.length === 0) return null
  return earliestFutureReminder(loan.dueTs, loan.reminders, nowMs)
}

// -----------------------------------------------------------------------------
// FinanceCard 适配器 —— store 内缓存条目转 CardLike
// -----------------------------------------------------------------------------

/**
 * FinanceCard -> CardLike 适配器（与 Android 同款）。
 *
 * store 内 CachedFinanceRecord.data 为 FinanceCard 时可直接调用此函数转换，
 * 避免在 store / UI 层重复解构字段。
 *
 * @param card 完整 FinanceCard 条目（明文 payload）
 * @returns 仅含触发计算所需字段的 CardLike
 */
export function toCardLike(card: FinanceCard): CardLike {
  return {
    id: card.id,
    kind: card.kind,
    billingDay: card.billing_day ?? null,
    dueDay: card.due_day ?? null,
    archived: card.archived,
  }
}

// ============================================================================
// v2 适配器 —— store 内明文 snake_case 条目转提醒 DTO（TR-4.1）
// ============================================================================

/**
 * FinanceSubscription -> SubscriptionLike 适配器（snake_case → camelCase DTO）。
 *
 * @param p store 内缓存的明文凭据订阅条目
 * @returns 仅含续费提醒计算所需字段的 SubscriptionLike
 */
export function toSubscriptionLike(p: FinanceSubscription): SubscriptionLike {
  return {
    id: p.id,
    active: p.active,
    nextRenewalTs: p.next_renewal_ts,
    billingCycle: p.billing_cycle,
    customDays: p.custom_days ?? null,
    reminders: p.reminders,
  }
}

/**
 * FinancePolicy -> PolicyLike 适配器（snake_case → camelCase DTO）。
 */
export function toPolicyLike(p: FinancePolicy): PolicyLike {
  return {
    id: p.id,
    active: p.active,
    expiryTs: p.expiry_ts,
    reminders: p.reminders,
  }
}

/**
 * FinanceLoan -> 到期提醒 LoanLike 适配器（注意字段 due_ts / status）。
 *
 * 提醒场景只承载 status / dueTs / reminders；金额 / 方向字段请改用
 * aggregator.ts 的 toLoanLike（净资产聚合专用，字段集不同）。
 */
export function toLoanLike(p: FinanceLoan): LoanLike {
  return {
    id: p.id,
    status: p.status,
    dueTs: p.due_ts,
    reminders: p.reminders,
  }
}

// -----------------------------------------------------------------------------
// 私有工具方法 —— 本地日历推理（与 4b Recurrence.ts 同款算法骨架）
// -----------------------------------------------------------------------------

/**
 * "无时区 ts + offset" 拆成本地日历分量（与 4b Recurrence.ts 完全等价）。
 *
 * 算法：先把 ms 视为 UTC 时刻，用 UTC 字段读 y/m —— 这是 JS Date 的"无时区
 * Date"语义。例：CST 2026-01-01 09:00 = 1767229200000（UTC 01:00），
 * ts + offset = 1767258000000 → 当 UTC ms → 2026-01-01 09:00 ✅
 *
 * @returns [year, month] 二元组（month = 1..12）
 */
function localPartsOfAsUtc(ts: number): [number, number] {
  const shifted = ts + TZ_OFFSET_MIN * 60_000
  const d = new Date(shifted)
  return [d.getUTCFullYear(), d.getUTCMonth() + 1]
}

/**
 * 由本地日历分量 (y, m, d, h, mi) 重构 Unix 毫秒（与 4b Recurrence.ts 同口径）。
 *
 * @returns Unix 毫秒（UTC ms）；该月无该日（如 2/30）时返回 null
 */
function tryLocalDateToMs(
  y: number,
  m: number,
  d: number,
  h: number,
  mi: number,
): number | null {
  // 月份进位（m=13 → 次年 1 月）。
  const [normY, normM] = normalizeMonth(y, m)
  // 取当月最大日（处理 2/30 等越界）。
  const daysInMonth = daysInMonthOf(normY, normM)
  if (d < 1 || d > daysInMonth) return null
  // JS Date month 是 0-11；按 UTC 构造再减偏移回本地 ms。
  const utcMs = Date.UTC(normY, normM - 1, d, h, mi, 0, 0)
  return utcMs - TZ_OFFSET_MIN * 60_000
}

/**
 * 计算还款日 T-1 触发时刻 ms。
 *
 * 算法：
 *   1. 还款日 = billingDay + dueDayOffset（offset 模式，spec schema 注释）；
 *   2. 若超过当月最大天数 → 钳位到当月最大日（如 1+30=31 在 2 月按 28/29 日）；
 *   3. T-1 = 还款日 - 1 天；还款日为月初 1 日时，T-1 退到上月最后一天；
 *   4. 触发时刻固定 09:00 CST。
 *
 * @return 还款日 T-1 09:00 CST 对应的 Unix 毫秒；该月无账单日时返回 null
 */
function computePaymentDueDayMs(
  y: number,
  m: number,
  billingDay: number,
  dueDayOffset: number,
): number | null {
  // ========== 1. 还款日 = billingDay + offset ==========
  // billingDay 为 1-31；offset 为 1-31；二者相加结果 2-62，钳位到当月最大日。
  const [normY, normM] = normalizeMonth(y, m)
  const rawDueDay = billingDay + dueDayOffset
  const daysInMonth = daysInMonthOf(normY, normM)
  const dueDay = Math.min(rawDueDay, daysInMonth)

  // ========== 2. T-1 = 还款日 - 1 天 ==========
  // 当 dueDay = 1 时，T-1 退到上月最后一天（用 Date 安全跨月）。
  // 当 dueDay > 1 时，T-1 直接在当月减一天。
  const triggerDate = previousDay(normY, normM, dueDay)

  return tryLocalDateToMs(
    triggerDate.year,
    triggerDate.month,
    triggerDate.day,
    TRIGGER_HOUR,
    TRIGGER_MINUTE,
  )
}

// ============================================================================
// v2 私有工具 —— 订阅日历滚动与提醒候选（TR-4.1，与 Android 同名私方法对齐）
// ============================================================================

/** 本地日历分量（CST 口径；Unix ms 加偏移当 UTC 读出的 y/m/d/h/mi/s/ms）。 */
interface LocalDateTimeParts {
  year: number
  /** month = 1..12。 */
  month: number
  day: number
  hour: number
  minute: number
  second: number
  millisecond: number
}

/**
 * Unix ms（CST 口径）→ 本地日历分量（保留时分秒毫秒）。
 *
 * 与 Android NextCardFiring.toLocalDateTime 同一手法：ts 先加时区偏移再当
 * UTC ms 读，得到的字段即 CST 本地日历分量（CST 无夏令时，拆法无歧义）。
 */
function toLocalDateTimeParts(ts: number): LocalDateTimeParts {
  const shifted = ts + TZ_OFFSET_MIN * MINUTE_MS
  const d = new Date(shifted)
  return {
    year: d.getUTCFullYear(),
    month: d.getUTCMonth() + 1,
    day: d.getUTCDate(),
    hour: d.getUTCHours(),
    minute: d.getUTCMinutes(),
    second: d.getUTCSeconds(),
    millisecond: d.getUTCMilliseconds(),
  }
}

/**
 * v2 订阅续费实例按计费周期向前滚动一个周期。
 *
 * 走 UTC 日历习语（与 tryLocalDateToMs 同一 CST 偏移口径），保留原 ts 的
 * 时分秒毫秒；月 / 季 / 年加法对目标月不存在的日做月末钳位（与 java.time
 * LocalDateTime.plusMonths / plusYears 一致，如 1/31 + 1 月 = 2/28，
 * 2/29 + 1 年在平年 = 2/28）；custom_days 走 Date.UTC 日溢出自然进位。
 *
 * @returns 滚动后的 Unix 毫秒；周期非法或 customDays 缺失 / 非正时返回 null
 */
function rollRenewal(
  ts: number,
  billingCycle: string,
  customDays: number | null,
): number | null {
  const p = toLocalDateTimeParts(ts)

  if (
    billingCycle === 'monthly'
    || billingCycle === 'quarterly'
    || billingCycle === 'yearly'
  ) {
    let targetYear = p.year
    let targetMonth = p.month
    if (billingCycle === 'monthly') {
      targetMonth += 1
    } else if (billingCycle === 'quarterly') {
      targetMonth += 3
    } else {
      targetYear += 1
    }
    const [normY, normM] = normalizeMonth(targetYear, targetMonth)
    // 月末钳位：目标月无该日（如 2/30、平年 2/29）时取目标月最大日。
    const targetDay = Math.min(p.day, daysInMonthOf(normY, normM))
    const utcMs = Date.UTC(
      normY,
      normM - 1,
      targetDay,
      p.hour,
      p.minute,
      p.second,
      p.millisecond,
    )
    return utcMs - TZ_OFFSET_MIN * MINUTE_MS
  }

  if (billingCycle === 'custom_days') {
    // custom_days 必须显式给出正整数天数；否则数据非法，不提醒。
    if (customDays === null || customDays <= 0) return null
    // Date.UTC 的日参数溢出时自动跨月进位（与 java.time plusDays 等价）。
    const utcMs = Date.UTC(
      p.year,
      p.month - 1,
      p.day + customDays,
      p.hour,
      p.minute,
      p.second,
      p.millisecond,
    )
    return utcMs - TZ_OFFSET_MIN * MINUTE_MS
  }

  // 未知周期字符串防御性处理（基准过期需滚动时无法继续）。
  return null
}

/**
 * 计算基准时刻的未来提醒候选最小值（v2 三函数共享口径）。
 *
 * 候选 = baseTs - r*60000（r 为提前分钟数；r=0 即基准时刻本身）；
 * 负偏移（r<0，语义为"之后提醒"）按防御性策略忽略；
 * 仅保留严格 > nowMs 的候选，返回其中最小值。
 *
 * @returns 最近一次未来提醒 ms；无未来候选时返回 null
 */
function earliestFutureReminder(
  baseTs: number,
  reminders: number[],
  nowMs: number,
): number | null {
  let best: number | null = null
  for (const r of reminders) {
    if (r < 0) continue
    const candidate = baseTs - r * MINUTE_MS
    // 严格大于 nowMs，与 v1 nextTrigger 的 filter { it > nowMs } 口径一致：
    // 整点恰好等于 nowMs 视为已过，不再触发。
    if (candidate > nowMs && (best === null || candidate < best)) {
      best = candidate
    }
  }
  return best
}

// -----------------------------------------------------------------------------
// 内部日历工具（Date 替代：避免依赖 Intl / Temporal）
// -----------------------------------------------------------------------------

/**
 * 月份进位 —— m=13 → 次年 1 月；m=0 → 上年 12 月。
 *
 * @returns [normY, normM] 规范化后的 (年, 月)（month = 1..12）
 */
function normalizeMonth(y: number, m: number): [number, number] {
  let normY = y
  let normM = m
  while (normM > 12) {
    normY += 1
    normM -= 12
  }
  while (normM < 1) {
    normY -= 1
    normM += 12
  }
  return [normY, normM]
}

/**
 * 计算下个月 —— 当前 (y, m) → (y+1, 1) 或 (y, m+1)。
 */
function nextMonthOf(y: number, m: number): { year: number; month: number } {
  const [normY, normM] = normalizeMonth(y, m + 1)
  return { year: normY, month: normM }
}

/**
 * 当月最大日（公历平年 2 月 28 日，闰年 2 月 29 日）。
 *
 * 复用 Date(y, m, 0) 习语：第 0 天 = 上月最后一天 = 当月最大日。
 */
function daysInMonthOf(y: number, m: number): number {
  // 注意：JS Date 月份 0-11，故传 m（1-12）时用 m 即可，第 0 天 = 上月最后一天 = 当月最大日。
  return new Date(y, m, 0).getDate()
}

/**
 * 计算前一天的 (年, 月, 日)（含跨月、跨年）。
 *
 * 跨月减天数（PAYMENT_DUE_DAYS_BEFORE = 1）由调用方决定；本工具只做"减 1 天"
 * 单步操作 —— Date(y, m-1, d-1) 在 d=1 时自动退到上月最后一天。
 */
function previousDay(y: number, m: number, d: number): { year: number; month: number; day: number } {
  // 利用 Date(y, m-1, d-1) 自动规范化：d-1=0 时退到上月最后一天。
  // 这里要的是"本地日历"，但因为跨月跨年逻辑不涉及时区，故直接用 UTC 习语无歧义。
  // PAYMENT_DUE_DAYS_BEFORE 是语义锚点 —— 当前实现 T-1；如改为 T-3，
  // 仅需在此处将 1 改为 PAYMENT_DUE_DAYS_BEFORE 即可。
  const dt = new Date(Date.UTC(y, m - 1, d - PAYMENT_DUE_DAYS_BEFORE))
  return {
    year: dt.getUTCFullYear(),
    month: dt.getUTCMonth() + 1,
    day: dt.getUTCDate(),
  }
}