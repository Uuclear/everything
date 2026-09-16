// 轨迹回放定位纯函数（阶段 4a / tasks.md Task 10 / TR-10.1）。
//
// 输入为按 ts 升序的轨迹点流（当日各 trip 段拼接），给定回放虚拟时刻 t，
// 用二分查找定位 t 所在段，再在相邻两点间做线性插值得到播放头坐标。
// 零知识红线：只读内存中的明文点流，不持久化、不日志、不依赖 DOM/vue。

import type { TrackPoint } from './core/types'

/**
 * 回放倍速档位（与 tasks.md Task 10 契约一致，顺序固定，视图层循环切换）。
 * 虚拟时间推进 = 真实流逝 × 倍速。
 */
export const SPEED_LEVELS: readonly number[] = [1, 4, 16, 60]

/** positionAt 的输出：插值坐标 + 所在段信息。 */
export interface PlaybackPosition {
  /** 插值纬度（WGS84 度）。 */
  lat: number
  /** 插值经度（WGS84 度）。 */
  lon: number
  /**
   * t 所在段的起始点下标（points[index].ts ≤ t < points[index+1].ts）；
   * t 越界钳制到端点时为该端点下标。
   */
  index: number
}

/**
 * 求回放时刻 t 在点流上的插值位置。
 *
 * 语义：
 *   - 空点流返回 null（视图层隐藏播放头）；
 *   - t ≤ 首点 ts 钳制到首点，t ≥ 末点 ts 钳制到末点（越界取端点）；
 *   - 其余二分定位段 [i, i+1]，按 (t - a.ts) / (b.ts - a.ts) 线性插值；
 *   - 相邻点同 ts（理论不发生，防御性）时直接取段末点，避免除零。
 *
 * @param points 按 ts 升序的轨迹点（调用方保证有序，本函数不重复校验）
 * @param t 回放虚拟时刻（UTC 毫秒）
 */
export function positionAt(points: readonly TrackPoint[], t: number): PlaybackPosition | null {
  if (points.length === 0) return null

  const first = points[0]
  const lastIdx = points.length - 1
  const last = points[lastIdx]

  // 端点钳制：越界直接取端点（含单点流，此时 first === last）。
  if (t <= first.ts) return { lat: first.lat, lon: first.lon, index: 0 }
  if (t >= last.ts) return { lat: last.lat, lon: last.lon, index: lastIdx }

  // 二分查找最大的 i，使 points[i].ts ≤ t（此时必有 0 ≤ i < lastIdx，
  // 且 t < points[lastIdx].ts，故 lo 收敛后 lo+1 必为段右端）。
  let lo = 0
  let hi = lastIdx
  while (lo + 1 < hi) {
    const mid = (lo + hi) >> 1
    if (points[mid].ts <= t) lo = mid
    else hi = mid
  }

  const a = points[lo]
  const b = points[lo + 1]
  const span = b.ts - a.ts
  // 同 ts 重复点防御：无法插值，取段末点（仍保证坐标落在点流上）。
  if (span <= 0) return { lat: b.lat, lon: b.lon, index: lo + 1 }

  const k = (t - a.ts) / span // ∈ (0, 1)
  return {
    lat: a.lat + (b.lat - a.lat) * k,
    lon: a.lon + (b.lon - a.lon) * k,
    index: lo,
  }
}
