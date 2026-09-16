// 当日统计纯函数（阶段 4a，FR-11 / AC-10）。
//
// 统计口径：
//   总距离   = 当日全部 trip 的 distanceM 求和（haversine 累加已在 detectStays 完成；
//              visit 内位移不进入任何 trip，天然不计入）；
//   移动时长 = 当日全部 trip 的 (endTs - startTs) 求和；
//   停留数   = 当日 visit 个数；
//   轨迹点数 = 当日采样点总数（由 splitByLocalDay 按本地日分组点数得到）。

import type { DayStats, DayTimeline } from './types'

/**
 * 计算当日统计。
 *
 * 入参为结构类型（DayTimeline 满足该形状），便于在 splitByLocalDay 组装
 * DayTimeline 时先算 stats 再回填，也便于 Task 9 UI 对选中日直接复用。
 *
 * @param timeline 含 visits / trips / pointCount 的时间线（或子结构）
 * @returns 当日统计值（全新对象，不修改入参）
 */
export function dayStats(
  timeline: Pick<DayTimeline, 'visits' | 'trips' | 'pointCount'>,
): DayStats {
  let distanceM = 0
  let movingMs = 0
  for (const trip of timeline.trips) {
    distanceM += trip.distanceM
    movingMs += trip.endTs - trip.startTs
  }
  return {
    distanceM,
    movingMs,
    visitCount: timeline.visits.length,
    pointCount: timeline.pointCount,
  }
}
