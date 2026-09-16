// 停留点检测纯函数（阶段 4a，FR-11 / AC-10）。
//
// 算法（与 Android GeoMath 同公式互证，单测锚点锁定行为）：
//   1. 点流按 ts 升序遍历（输入乱序时先排序，输出只依赖集合内容）；
//   2. 维护当前停留簇：质心 = 簇内点经纬均值（逐点增量更新）；
//   3. 点距簇质心 ≤100m 并入簇，否则结算当前簇并以该点开新簇；
//   4. 结算规则：簇驻留（末点ts - 首点ts）≥10min → Visit；
//      否则簇内点按序并入当前 Trip 段；
//   5. Trip 跨簇连续拼接：未达 visit 阈值的簇（等红灯、排队等短暂驻足）
//      不打断行程，其点并入同一 Trip；只有 Visit 出现才结算当前 Trip；
//   6. Trip.distanceM = 段内相邻点 haversine 距离累加（visit 内位移不计入）。

import type { TrackPoint, Trip, Visit } from './types'

/** WGS84 地球平均半径（米）。与 Android GeoMath.EARTH_RADIUS_M 一致。 */
export const EARTH_RADIUS_M = 6_371_000

/** 停留簇并入半径（米）：点距簇质心不超过该值即视为仍在停留。 */
export const STAY_RADIUS_M = 100

/** 停留判定时长阈值（毫秒）：簇驻留达到该值才判定为 Visit。 */
export const STAY_MIN_DURATION_MS = 10 * 60_000

/**
 * haversine 大圆距离（米）。
 *
 * 公式必须与 Android GeoMath.haversineM 逐行一致：
 *   a = sin²(Δφ/2) + cos φ1 · cos φ2 · sin²(Δλ/2)
 *   d = 2R · asin(√a)
 * 数值上 a 可能因浮点误差略超 1，clamp 处理防 NaN。
 */
export function haversineM(
  lat1: number,
  lon1: number,
  lat2: number,
  lon2: number,
): number {
  const toRad = (deg: number) => (deg * Math.PI) / 180
  const phi1 = toRad(lat1)
  const phi2 = toRad(lat2)
  const dPhi = toRad(lat2 - lat1)
  const dLambda = toRad(lon2 - lon1)
  const sinDPhi = Math.sin(dPhi / 2)
  const sinDLambda = Math.sin(dLambda / 2)
  const a = sinDPhi * sinDPhi + Math.cos(phi1) * Math.cos(phi2) * sinDLambda * sinDLambda
  const clamped = Math.min(1, Math.max(0, a))
  return 2 * EARTH_RADIUS_M * Math.asin(Math.sqrt(clamped))
}

/** 停留簇内部态：质心用增量均值维护（centroid = Σpoints / count）。 */
interface Cluster {
  /** 簇质心纬度（簇内点均值，逐点更新）。 */
  centroidLat: number
  /** 簇质心经度（簇内点均值，逐点更新）。 */
  centroidLon: number
  /** 簇首点 ts（UTC 毫秒）。 */
  startTs: number
  /** 簇末点 ts（UTC 毫秒）。 */
  endTs: number
  /** 簇内点（ts 升序），结算时整体并入 trip 或计入 visit.pointCount。 */
  points: TrackPoint[]
}

/** detectStays 输出：全局 ts 升序的 visits 与 trips。 */
export interface StaysResult {
  visits: Visit[]
  trips: Trip[]
}

/**
 * 停留点检测。
 *
 * @param points 解密合并后的轨迹点流（任意顺序，内部按 ts 升序排序）
 * @returns visits 与 trips（均按开始时刻升序；同一 visit 前的 trip 先于 visit 出现）
 */
export function detectStays(points: TrackPoint[]): StaysResult {
  const visits: Visit[] = []
  const trips: Trip[] = []
  if (points.length === 0) return { visits, trips }

  const sorted = [...points].sort((a, b) => a.ts - b.ts)
  let cluster: Cluster | null = null
  let trip: Trip | null = null

  /** 把单点并入当前 trip（无则开新段），并累加 haversine 距离。 */
  const appendToTrip = (p: TrackPoint) => {
    if (trip === null) {
      trip = { startTs: p.ts, endTs: p.ts, points: [p], distanceM: 0 }
      return
    }
    const last = trip.points[trip.points.length - 1]
    trip.distanceM += haversineM(last.lat, last.lon, p.lat, p.lon)
    trip.points.push(p)
    trip.endTs = p.ts
  }

  /** 结算当前 trip 并推入结果（visit 出现时调用，保证"两段通勤夹一 visit"分段）。 */
  const settleTrip = () => {
    if (trip !== null) {
      trips.push(trip)
      trip = null
    }
  }

  /**
   * 结算当前簇：驻留 ≥10min → 先结算 trip 再产出 Visit；
   * 否则簇内点按序并入当前 trip（跨簇连续拼接，行程不被短暂驻足打断）。
   */
  const settleCluster = (c: Cluster) => {
    const durationMs = c.endTs - c.startTs
    if (durationMs >= STAY_MIN_DURATION_MS) {
      settleTrip()
      visits.push({
        startTs: c.startTs,
        endTs: c.endTs,
        centerLat: c.centroidLat,
        centerLon: c.centroidLon,
        pointCount: c.points.length,
      })
    } else {
      for (const p of c.points) appendToTrip(p)
    }
  }

  for (const p of sorted) {
    if (cluster === null) {
      cluster = {
        centroidLat: p.lat,
        centroidLon: p.lon,
        startTs: p.ts,
        endTs: p.ts,
        points: [p],
      }
      continue
    }
    if (haversineM(p.lat, p.lon, cluster.centroidLat, cluster.centroidLon) <= STAY_RADIUS_M) {
      // 并入簇：质心增量均值更新（centroid' = (centroid·n + p) / (n+1)）。
      const n = cluster.points.length
      cluster.centroidLat = (cluster.centroidLat * n + p.lat) / (n + 1)
      cluster.centroidLon = (cluster.centroidLon * n + p.lon) / (n + 1)
      cluster.endTs = p.ts
      cluster.points.push(p)
    } else {
      // 距质心超半径：结算旧簇，以该点开新簇。
      settleCluster(cluster)
      cluster = {
        centroidLat: p.lat,
        centroidLon: p.lon,
        startTs: p.ts,
        endTs: p.ts,
        points: [p],
      }
    }
  }
  // 收尾：结算末尾簇与未闭合的 trip（全程无停留时整条轨迹为一个 trip）。
  // 空输入时 cluster 为 null，跳过即可（settleTrip 对空 trip 同样无操作）。
  if (cluster !== null) settleCluster(cluster)
  settleTrip()

  return { visits, trips }
}
