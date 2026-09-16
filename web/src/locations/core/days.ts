// 本地日切分纯函数（阶段 4a，FR-10 / FR-11 / AC-10）。
//
// 切分规则：
//   - 本地日键 dayKey = "YYYY-MM-DD"，由 ts + tzOffsetMin 偏移后按 UTC 日历日读取
//     （纯函数换算，不依赖运行环境时区，可单测）；
//   - Visit 归其【开始时刻】所在本地日；跨夜停留不截断，仅置 overnight 标注
//     （startTs 与 endTs 不属同一本地日即跨夜），供时间线展示"跨夜"；
//   - Trip 与 Visit 同规则：归开始时刻所在日，不截断（跨零点行程整日计入开始日）；
//   - 轨迹点按本地日分组计数（pointCount）。

import { dayStats } from './stats'
import type { DayTimeline, TrackPoint, Trip, Visit } from './types'

/** 把 UTC 毫秒换算为本地日键 "YYYY-MM-DD"。 */
export function localDayKey(ts: number, tzOffsetMin: number): string {
  // 本地时刻 = UTC + 偏移；对偏移后的时刻取 UTC 日历日即本地日历日。
  return new Date(ts + tzOffsetMin * 60_000).toISOString().slice(0, 10)
}

/**
 * 按月点流与停留检测结果切分本地日时间线。
 *
 * @param points      合并后的全量轨迹点（用于按日计数）
 * @param visits      detectStays 产出的停留（全局 ts 升序）
 * @param trips       detectStays 产出的移动段（全局 ts 升序）
 * @param tzOffsetMin 本地时区相对 UTC 的偏移分钟（如 +08:00 为 480，-05:00 为 -300）
 * @returns dayKey 升序的 Map（仅含有轨迹点的日子；无数据日不产生条目）
 */
export function splitByLocalDay(
  points: TrackPoint[],
  visits: Visit[],
  trips: Trip[],
  tzOffsetMin: number,
): Map<string, DayTimeline> {
  // 先按点流聚合各日骨架（dayKey → 当日点数），保证仅"有数据日"才有条目。
  const timelines = new Map<string, DayTimeline>()
  const ensure = (key: string): DayTimeline => {
    let t = timelines.get(key)
    if (t === undefined) {
      t = { dayKey: key, visits: [], trips: [], pointCount: 0, stats: dayStats({ visits: [], trips: [], pointCount: 0 }) }
      timelines.set(key, t)
    }
    return t
  }

  for (const p of points) {
    ensure(localDayKey(p.ts, tzOffsetMin)).pointCount++
  }

  // Visit 归开始时刻所在日；跨夜不截断，起止不属同一本地日时标注 overnight。
  for (const v of visits) {
    const startDay = localDayKey(v.startTs, tzOffsetMin)
    const overnight = localDayKey(v.endTs, tzOffsetMin) !== startDay
    ensure(startDay).visits.push(overnight ? { ...v, overnight: true } : v)
  }

  // Trip 同规则归开始时刻所在日（跨零点行程不截断，整日计入开始日）。
  for (const tr of trips) {
    ensure(localDayKey(tr.startTs, tzOffsetMin)).trips.push(tr)
  }

  // 各日统计回填；输出按 dayKey 升序（字典序即日期序）。
  const sorted = new Map<string, DayTimeline>(
    [...timelines.entries()].sort(([a], [b]) => (a < b ? -1 : a > b ? 1 : 0)),
  )
  for (const t of sorted.values()) {
    t.stats = dayStats(t)
  }
  return sorted
}
