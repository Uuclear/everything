// 轨迹页月度装载/切日/高亮纯函数（阶段 4a / tasks.md Task 9 / TR-9.1）。
//
// locations store（pinia）的全部可测逻辑都抽在本模块：不依赖 vue/pinia/DOM，
// 时区一律以显式 tzOffsetMin 传入（与 core/days.ts 同一约定，node 环境可单测）。
//
// 红线（TR-9.2）：本模块产出的明文坐标只驻留内存，调用方不得持久化
// （不写 localStorage/IndexedDB、不进日志）。

import type { Sodium } from '../crypto/envelope'
import { decryptBlock, type ApiLocationBlock } from './core/decode'
import { localDayKey, splitByLocalDay } from './core/days'
import { detectStays } from './core/stays'
import type {
  DayTimeline,
  LocationBlockJson,
  TrackPoint,
  Trip,
  Visit,
} from './core/types'

/** 当前运行环境的本地时区偏移（分钟，+08:00 为 480）。仅在 store/视图层取一次。 */
export function tzOffsetMinNow(now: Date = new Date()): number {
  // getTimezoneOffset 返回"本地比 UTC 慢多少分钟"，取负即 core 约定的偏移方向。
  return -now.getTimezoneOffset()
}

/**
 * 本地月 [year, month(1-12)] 的 UTC 毫秒查询范围：本地月初 00:00 → 次月月初 00:00。
 *
 * 固定偏移换算（与 localDayKey 同口径）；本地月最长 31 天，叠加偏移平移
 * 也远小于服务端 62 天跨度上限（契约约束内）。
 */
export function monthRangeUtc(
  year: number,
  month: number,
  tzOffsetMin: number,
): { from: number; to: number } {
  // Date.UTC 的 month 参数为 0 基且允许越界进位（month=12 → 次年 1 月）。
  const from = Date.UTC(year, month - 1, 1) - tzOffsetMin * 60_000
  const to = Date.UTC(year, month, 1) - tzOffsetMin * 60_000
  return { from, to }
}

/** 年月平移（月份切换按钮用）：delta 可为任意整数，跨年自动进位。 */
export function shiftYearMonth(
  year: number,
  month: number,
  delta: number,
): { year: number; month: number } {
  // 统一到 0 基总月数再平移，避免手工处理跨年借位。
  const total = year * 12 + (month - 1) + delta
  return { year: Math.floor(total / 12), month: ((total % 12) + 12) % 12 + 1 }
}

/** 月历格子：dayKey 定位数据高亮，dayOfMonth/inMonth 供渲染。 */
export interface CalendarCell {
  /** 本地日键 "YYYY-MM-DD"（与 DayTimeline.dayKey 同规则）。 */
  dayKey: string
  /** 日（1-31，直接取自 dayKey，纯函数无环境依赖）。 */
  dayOfMonth: number
  /** 是否属于当前展示月（前后补位格为 false，渲染置灰）。 */
  inMonth: boolean
}

/**
 * 月历网格（周一开头，整周补齐）：覆盖当前本地月的完整周序列。
 *
 * 实现口径：以"本地月初 00:00 对应的 UTC 毫秒"为锚，逐格 +1 天，
 * 每格 dayKey 由 localDayKey 推出——与 splitByLocalDay 的分日结果严格一致，
 * 保证高亮点标与分析产物对得上。
 */
export function calendarCells(
  year: number,
  month: number,
  tzOffsetMin: number,
): CalendarCell[] {
  const monthStartUtc = Date.UTC(year, month - 1, 1) - tzOffsetMin * 60_000
  const nextMonthStartUtc = Date.UTC(year, month, 1) - tzOffsetMin * 60_000
  // 当月天数：相邻两本地月初相差的整天数（UTC 月差天然是整天）。
  const daysInMonth = Math.round((nextMonthStartUtc - monthStartUtc) / 86_400_000)
  // 月初是本地周几（0=周日）：对偏移后的时刻读 UTC 星期即本地星期。
  const firstWeekday = new Date(monthStartUtc + tzOffsetMin * 60_000).getUTCDay()
  // 周一开头：月初前需补的格子数（周一→0，周日→6）。
  const leading = (firstWeekday + 6) % 7
  const totalCells = Math.ceil((leading + daysInMonth) / 7) * 7

  const cells: CalendarCell[] = []
  for (let i = 0; i < totalCells; i++) {
    // 第 i 格对应"月初 - leading + i"天后的本地日（取该日 00:00 的时刻求 dayKey）。
    const cellUtc = monthStartUtc + (i - leading) * 86_400_000
    const dayKey = localDayKey(cellUtc, tzOffsetMin)
    cells.push({
      dayKey,
      dayOfMonth: Number(dayKey.slice(8, 10)),
      inMonth: i >= leading && i < leading + daysInMonth,
    })
  }
  return cells
}

/** 合并多块解密明文的点流（detectStays 内部会按 ts 排序，这里保持简单拼接）。 */
export function mergeBlockPoints(blocks: LocationBlockJson[]): TrackPoint[] {
  const points: TrackPoint[] = []
  for (const b of blocks) points.push(...b.points)
  return points
}

/**
 * 月度分析管线（纯函数）：合并点流 → detectStays → splitByLocalDay。
 *
 * @param blocks      已全部解密成功的块明文（逐块解密在 decryptAndAnalyze 完成）
 * @param tzOffsetMin 本地时区偏移分钟
 * @returns dayKey 升序的 DayTimeline 缓存（仅含有数据日）
 */
export function analyzeMonth(
  blocks: LocationBlockJson[],
  tzOffsetMin: number,
): Map<string, DayTimeline> {
  const points = mergeBlockPoints(blocks)
  const { visits, trips } = detectStays(points)
  return splitByLocalDay(points, visits, trips, tzOffsetMin)
}

/**
 * 装载管线（纯函数，TR-9.1）：逐块 decryptBlock → analyzeMonth。
 *
 * 任一块解密失败（密钥错/AAD 不符/密文损坏）立即抛异常——调用方（store）
 * 整体置"解密失败"错误态，不做半拉子展示、不泄露任何坐标。
 */
export function decryptAndAnalyze(
  sodium: Sodium,
  mk: Uint8Array,
  rawBlocks: ApiLocationBlock[],
  tzOffsetMin: number,
): Map<string, DayTimeline> {
  const blocks = rawBlocks.map((raw) => decryptBlock(sodium, mk, raw))
  return analyzeMonth(blocks, tzOffsetMin)
}

/** 日历高亮集合：有数据日的 dayKey 列表（字典序升序即日期序；视图层转 Set 做 O(1) 查询）。 */
export function daysWithDataOf(timelines: Map<string, DayTimeline>): string[] {
  return [...timelines.keys()].sort()
}

/**
 * 选日规则（切日逻辑）：
 *   1. 优先保留调用方指定的 preferred（月份切换后原选中日仍有数据时不动）；
 *   2. 其次今天（在本月且有数据）；
 *   3. 否则最近一个有数据日；
 *   4. 全月无数据返回 null（视图展示空态）。
 */
export function pickInitialDay(
  timelines: Map<string, DayTimeline>,
  todayKey: string,
  preferred: string | null = null,
): string | null {
  if (preferred !== null && timelines.has(preferred)) return preferred
  if (timelines.has(todayKey)) return todayKey
  const keys = [...timelines.keys()].sort()
  return keys.length > 0 ? keys[keys.length - 1] : null
}

/** 今天的本地日键（视图层初次装载用）。 */
export function todayDayKey(tzOffsetMin: number, now: number = Date.now()): string {
  return localDayKey(now, tzOffsetMin)
}

/** 时间线条目：visit 卡与 trip 段按开始时刻混排（视图层直接遍历渲染）。 */
export type TimelineItem =
  | { kind: 'visit'; startTs: number; visit: Visit }
  | { kind: 'trip'; startTs: number; trip: Trip }

/** 合并当日 visits/trips 为按开始时刻升序的时间线（同时刻 visit 在前，语义上先到达后离开）。 */
export function timelineItems(timeline: DayTimeline): TimelineItem[] {
  const items: TimelineItem[] = [
    ...timeline.visits.map((visit) => ({ kind: 'visit' as const, startTs: visit.startTs, visit })),
    ...timeline.trips.map((trip) => ({ kind: 'trip' as const, startTs: trip.startTs, trip })),
  ]
  items.sort((a, b) => a.startTs - b.startTs || (a.kind === 'visit' ? -1 : 1))
  return items
}

// ---- 展示格式化（纯函数，时区显式传入）----

/** 距离（米）→ km 字符串（一位小数，AC-10 口径）。 */
export function formatKm(distanceM: number): string {
  return (distanceM / 1000).toFixed(1)
}

/** 时长（毫秒）→ 中文短格式："3小时25分" / "45分钟" / "0分钟"。 */
export function formatDurationMs(ms: number): string {
  const totalMin = Math.round(ms / 60_000)
  const hours = Math.floor(totalMin / 60)
  const mins = totalMin % 60
  if (hours > 0) return mins > 0 ? `${hours}小时${mins}分` : `${hours}小时`
  return `${mins}分钟`
}

/** UTC 毫秒 → 本地 "HH:mm"（对偏移后时刻读 UTC 时分，与 localDayKey 同口径）。 */
export function formatHHmm(ts: number, tzOffsetMin: number): string {
  return new Date(ts + tzOffsetMin * 60_000).toISOString().slice(11, 16)
}
