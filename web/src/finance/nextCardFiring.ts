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

import type { FinanceCard } from './types'

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