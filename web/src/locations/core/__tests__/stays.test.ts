// 停留点检测纯函数单测（tasks.md Task 8 / FR-11 / AC-10）。
// 覆盖 spec 指定构造点流：居家过夜跨零点、3 分钟短时驻足、精度漂移簇、
// 两段通勤夹一 visit；外加 haversine 已知对照与空/单点边界。

import { describe, expect, it } from 'vitest'
import { detectStays, haversineM } from '../stays'
import type { TrackPoint } from '../types'

/** 构造轨迹点（acc 固定 10，其余可空字段缺省）。 */
function pt(ts: number, lat: number, lon: number): TrackPoint {
  return { ts, lat, lon, acc: 10 }
}

/** 纬度 0.001° 的 haversine 距离（同经度时精确等于 R·Δφ，手算锚点）。 */
const DEG_0_001_M = 6371000 * (0.001 * Math.PI / 180) // ≈ 111.19492664455873

describe('haversineM（与 Android GeoMath 同公式互证）', () => {
  it('赤道 1 经度 ≈ 111194.927m（2R·asin(sin(Δλ/2)) 手算对照）', () => {
    expect(haversineM(0, 0, 0, 1)).toBeCloseTo(111194.927, 2)
  })

  it('赤道 1 纬度 ≈ 111194.927m；同点距离为 0', () => {
    expect(haversineM(0, 0, 1, 0)).toBeCloseTo(111194.927, 2)
    expect(haversineM(39.9042, 116.4074, 39.9042, 116.4074)).toBe(0)
  })
})

describe('detectStays', () => {
  it('居家过夜跨零点：23:50→07:00 单 visit 不截断、无 trip', () => {
    // 本地 +08:00 23:50 起，每 5 分钟 1 点共 87 点，至本地次日 07:00；
    // 点在 ±0.0003°（约 26m）内交替抖动，远小于 100m 并入半径。
    const t0 = Date.UTC(2023, 10, 14, 15, 50) // 本地 2023-11-14 23:50
    const points: TrackPoint[] = []
    for (let i = 0; i <= 86; i++) {
      const lon = 116.4074 + (i % 2 === 0 ? -0.0003 : 0.0003)
      points.push(pt(t0 + i * 5 * 60_000, 39.9042, lon))
    }

    const { visits, trips } = detectStays(points)

    expect(trips).toHaveLength(0)
    expect(visits).toHaveLength(1)
    // 跨本地零点不截断：起止恰为首末点 ts（驻留 7h10m 完整保留）。
    expect(visits[0].startTs).toBe(t0)
    expect(visits[0].endTs).toBe(t0 + 430 * 60_000)
    expect(visits[0].pointCount).toBe(87)
    // 质心为簇内点增量均值：87 点（奇数）交替 ±0.0003，负向多 1 次，
    // 理论均值 116.4074 - 0.0003/87 ≈ 116.40739655，据此锁定精度。
    expect(visits[0].centerLon).toBeCloseTo(116.4074, 5)
    expect(visits[0].centerLat).toBeCloseTo(39.9042, 10)
  })

  it('4 分钟短时驻足：归入 trip 不误判 visit，且不打断行程拼接', () => {
    // 本地 08:00 起通勤，每分钟 1 点、每点纬度 +0.001°（≈111m > 100m，逐点开新簇）；
    // 08:10-08:14 五点同位置（簇驻留 4min < 10min）模拟等灯驻足，随后继续通勤。
    const t0 = Date.UTC(2023, 10, 15, 0, 0) // 本地 2023-11-15 08:00
    const points: TrackPoint[] = []
    for (let i = 0; i <= 10; i++) {
      points.push(pt(t0 + i * 60_000, 39.9 + i * 0.001, 116.4))
    }
    // 驻足点延续 08:10 点纬度（39.910），与前后移动段无缝衔接。
    for (let i = 11; i <= 14; i++) {
      points.push(pt(t0 + i * 60_000, 39.9 + 10 * 0.001, 116.4))
    }
    for (let i = 15; i <= 19; i++) {
      points.push(pt(t0 + i * 60_000, 39.9 + (i - 4) * 0.001, 116.4))
    }

    const { visits, trips } = detectStays(points)

    expect(visits).toHaveLength(0)
    // 驻足不产出 visit，且其前后移动点拼入同一 trip（跨簇连续拼接）。
    expect(trips).toHaveLength(1)
    expect(trips[0].points).toHaveLength(20)
    expect(trips[0].startTs).toBe(t0)
    expect(trips[0].endTs).toBe(t0 + 19 * 60_000)
    // 距离 = 15 段非零位移 × 0.001°（驻足簇内 4 段同位置贡献 0）。
    expect(trips[0].distanceM).toBeCloseTo(15 * DEG_0_001_M, 1)
  })

  it('精度漂移簇：半径内长时间抖动不炸簇，判为单 visit', () => {
    // 30 点、间隔 60s（驻留 29min ≥ 10min），位置在质心 ±31m 内伪随机抖动。
    const t0 = Date.UTC(2023, 10, 15, 12, 0)
    const points: TrackPoint[] = []
    for (let i = 0; i < 30; i++) {
      points.push(
        pt(
          t0 + i * 60_000,
          39.9042 + 0.0002 * Math.sin(i * 0.7),
          116.4074 + 0.0002 * Math.cos(i * 0.7),
        ),
      )
    }

    const { visits, trips } = detectStays(points)

    expect(trips).toHaveLength(0)
    expect(visits).toHaveLength(1)
    expect(visits[0].pointCount).toBe(30)
    expect(visits[0].endTs - visits[0].startTs).toBe(29 * 60_000)
    // 质心均值不应被抖动带偏（偏离基准远小于抖动幅度）。
    expect(visits[0].centerLat).toBeCloseTo(39.9042, 3)
    expect(visits[0].centerLon).toBeCloseTo(116.4074, 3)
  })

  it('两段通勤夹一 visit：trip/visit/trip 次序与起止正确，距离分段累计', () => {
    // 通勤1 08:00-08:20：每分钟 +0.001° 纬度（21 点，末点 39.920）；
    // 公司 08:30-17:30：中心 39.9212、±0.00015° 抖动（55 点，驻留 9h）；
    // 通勤2 18:00-18:20：自 39.920 每分钟 -0.001° 返程（21 点）。
    // 公司簇首点距通勤1末点 ≈117m > 100m，通勤2首点距公司质心 ≈133m > 100m，
    // 两次簇切换位置精心设计，保证 visit 边界判定无歧义。
    const t0 = Date.UTC(2023, 10, 15, 0, 0) // 本地 08:00
    const points: TrackPoint[] = []
    for (let i = 0; i <= 20; i++) {
      points.push(pt(t0 + i * 60_000, 39.9 + i * 0.001, 116.4))
    }
    for (let i = 0; i < 55; i++) {
      const d = (i % 2 === 0 ? -1 : 1) * 0.00015
      points.push(pt(t0 + 30 * 60_000 + i * 10 * 60_000, 39.9212 + d, 116.4 + d))
    }
    for (let i = 0; i <= 20; i++) {
      points.push(pt(t0 + 10 * 3_600_000 + i * 60_000, 39.92 - i * 0.001, 116.4))
    }

    const { visits, trips } = detectStays(points)

    // visit 恰一个：起止为公司簇首末点，点数 55。
    expect(visits).toHaveLength(1)
    expect(visits[0].startTs).toBe(t0 + 30 * 60_000)
    expect(visits[0].endTs).toBe(t0 + 30 * 60_000 + 54 * 10 * 60_000)
    expect(visits[0].pointCount).toBe(55)
    expect(visits[0].centerLat).toBeCloseTo(39.9212, 5)

    // visit 前后各一段 trip，互不合并。
    expect(trips).toHaveLength(2)
    expect(trips[0].startTs).toBe(t0)
    expect(trips[0].endTs).toBe(t0 + 20 * 60_000)
    expect(trips[0].points).toHaveLength(21)
    expect(trips[0].distanceM).toBeCloseTo(20 * DEG_0_001_M, 1)
    expect(trips[1].startTs).toBe(t0 + 10 * 3_600_000)
    expect(trips[1].endTs).toBe(t0 + 10 * 3_600_000 + 20 * 60_000)
    expect(trips[1].points).toHaveLength(21)
    expect(trips[1].distanceM).toBeCloseTo(20 * DEG_0_001_M, 1)

    // 时间线次序：trip1 结束早于 visit 开始，visit 结束早于 trip2 开始。
    expect(trips[0].endTs).toBeLessThan(visits[0].startTs)
    expect(visits[0].endTs).toBeLessThan(trips[1].startTs)
  })

  it('输入乱序时内部按 ts 排序，输出与有序输入一致', () => {
    const t0 = Date.UTC(2023, 10, 15, 0, 0)
    const ordered: TrackPoint[] = []
    for (let i = 0; i <= 10; i++) {
      ordered.push(pt(t0 + i * 60_000, 39.9 + i * 0.001, 116.4))
    }
    const shuffled = [...ordered].reverse()

    const a = detectStays(ordered)
    const b = detectStays(shuffled)
    expect(b.trips[0].distanceM).toBeCloseTo(a.trips[0].distanceM, 10)
    expect(b.trips[0].points.map((p) => p.ts)).toEqual(a.trips[0].points.map((p) => p.ts))
  })

  it('边界：空点流产出空结果；单点归入单点 trip（距离 0）', () => {
    expect(detectStays([])).toEqual({ visits: [], trips: [] })

    const t0 = Date.UTC(2023, 10, 15, 0, 0)
    const { visits, trips } = detectStays([pt(t0, 39.9, 116.4)])
    expect(visits).toHaveLength(0)
    expect(trips).toHaveLength(1)
    expect(trips[0].points).toHaveLength(1)
    expect(trips[0].distanceM).toBe(0)
    expect(trips[0].startTs).toBe(t0)
    expect(trips[0].endTs).toBe(t0)
  })
})
