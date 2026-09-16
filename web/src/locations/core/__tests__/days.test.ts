// 本地日切分与当日统计单测（tasks.md Task 8 / FR-10 / FR-11 / AC-10）。
// 覆盖：+08:00 零点前后分日、负时区、visit 归开始日且跨夜标注不截断、
// 跨零点行程归开始日、stats 已知折线距离累加、visit 内位移不计入。

import { describe, expect, it } from 'vitest'
import { localDayKey, splitByLocalDay } from '../days'
import { dayStats } from '../stats'
import { detectStays } from '../stays'
import type { TrackPoint, Trip, Visit } from '../types'

/** 构造轨迹点（acc 固定 10，其余可空字段缺省）。 */
function pt(ts: number, lat: number, lon: number): TrackPoint {
  return { ts, lat, lon, acc: 10 }
}

const TZ_PLUS_8 = 480 // +08:00 偏移分钟

describe('localDayKey', () => {
  it('+08:00 零点前后分日：UTC 16:00 为本地日界', () => {
    // UTC 2023-11-14 15:59:59.999 → 本地 23:59:59.999，仍归 11-14
    expect(localDayKey(Date.UTC(2023, 10, 14, 15, 59, 59, 999), TZ_PLUS_8)).toBe('2023-11-14')
    // UTC 2023-11-14 16:00:00.000 → 本地 00:00:00.000，归 11-15
    expect(localDayKey(Date.UTC(2023, 10, 14, 16, 0, 0, 0), TZ_PLUS_8)).toBe('2023-11-15')
  })

  it('负偏移时区（-05:00）：UTC 04:59:59 本地仍前一日', () => {
    expect(localDayKey(Date.UTC(2023, 10, 15, 4, 59, 59), -300)).toBe('2023-11-14')
    expect(localDayKey(Date.UTC(2023, 10, 15, 5, 0, 0), -300)).toBe('2023-11-15')
  })
})

describe('splitByLocalDay', () => {
  it('本地日边界：零点前后的点分别计数，仅产出有数据日且按日升序', () => {
    const p1 = pt(Date.UTC(2023, 10, 14, 15, 59, 59, 999), 39.9, 116.4)
    const p2 = pt(Date.UTC(2023, 10, 14, 16, 0, 0, 0), 39.901, 116.4)
    const map = splitByLocalDay([p1, p2], [], [], TZ_PLUS_8)

    expect([...map.keys()]).toEqual(['2023-11-14', '2023-11-15'])
    expect(map.get('2023-11-14')!.pointCount).toBe(1)
    expect(map.get('2023-11-15')!.pointCount).toBe(1)
  })

  it('居家过夜 visit：归开始时刻所在日、标注跨夜、不截断（整合 detectStays）', () => {
    // 与 stays.test.ts 同点流：本地 23:50 → 次日 07:00，87 点。
    const t0 = Date.UTC(2023, 10, 14, 15, 50) // 本地 2023-11-14 23:50
    const points: TrackPoint[] = []
    for (let i = 0; i <= 86; i++) {
      const lon = 116.4074 + (i % 2 === 0 ? -0.0003 : 0.0003)
      points.push(pt(t0 + i * 5 * 60_000, 39.9042, lon))
    }
    const { visits, trips } = detectStays(points)
    const map = splitByLocalDay(points, visits, trips, TZ_PLUS_8)

    const day1 = map.get('2023-11-14')!
    const day2 = map.get('2023-11-15')!
    // 23:50/23:55 两点归前一日，其余 85 点归次日。
    expect(day1.pointCount).toBe(2)
    expect(day2.pointCount).toBe(85)

    // visit 归开始时刻所在日，次日不再重复出现。
    expect(day1.visits).toHaveLength(1)
    expect(day2.visits).toHaveLength(0)
    // 跨夜标注置位，且 endTs 保持次日 07:00 不截断。
    expect(day1.visits[0].overnight).toBe(true)
    expect(day1.visits[0].startTs).toBe(t0)
    expect(day1.visits[0].endTs).toBe(t0 + 430 * 60_000)

    // visit 内位移不计入距离：两日总距离与移动时长均为 0。
    expect(day1.stats.distanceM).toBe(0)
    expect(day1.stats.movingMs).toBe(0)
    expect(day1.stats.visitCount).toBe(1)
    expect(day2.stats.distanceM).toBe(0)
  })

  it('同日内 visit 不标跨夜', () => {
    const t0 = Date.UTC(2023, 10, 15, 1, 0) // 本地 09:00
    const points: TrackPoint[] = []
    for (let i = 0; i < 30; i++) {
      points.push(pt(t0 + i * 60_000, 39.9042, 116.4074))
    }
    const { visits, trips } = detectStays(points)
    const map = splitByLocalDay(points, visits, trips, TZ_PLUS_8)

    const day = map.get('2023-11-15')!
    expect(day.visits).toHaveLength(1)
    // 同日起止：overnight 字段缺省（不置 true）。
    expect(day.visits[0].overnight).toBeUndefined()
  })

  it('跨零点行程：归开始日、不截断，整日距离计入开始日统计', () => {
    // 本地 23:30 出发，每分钟 +0.001° 纬度，61 点至本地次日 00:30。
    const t0 = Date.UTC(2023, 10, 14, 15, 30) // 本地 2023-11-14 23:30
    const points: TrackPoint[] = []
    for (let i = 0; i <= 60; i++) {
      points.push(pt(t0 + i * 60_000, 39.9 + i * 0.001, 116.4))
    }
    const { visits, trips } = detectStays(points)
    expect(visits).toHaveLength(0)
    expect(trips).toHaveLength(1)

    const map = splitByLocalDay(points, visits, trips, TZ_PLUS_8)
    const day1 = map.get('2023-11-14')!
    const day2 = map.get('2023-11-15')!

    // trip 归开始时刻所在日，endTs 跨零点不截断。
    expect(day1.trips).toHaveLength(1)
    expect(day1.trips[0].endTs).toBe(t0 + 60 * 60_000)
    expect(day2.trips).toHaveLength(0)

    // 点数仍按本地日实切（23:30-23:59 共 30 点，00:00-00:30 共 31 点）。
    expect(day1.pointCount).toBe(30)
    expect(day2.pointCount).toBe(31)
    // 整日距离计入开始日：60 段 × 0.001° 纬度。
    expect(day1.stats.distanceM).toBeCloseTo(60 * 6371000 * (0.001 * Math.PI / 180), 0)
    expect(day1.stats.movingMs).toBe(60 * 60_000)
    expect(day2.stats.distanceM).toBe(0)
  })
})

describe('dayStats', () => {
  it('已知折线距离累加：多段 trip 求和，移动时长/停留数/点数正确', () => {
    const trips: Trip[] = [
      { startTs: 0, endTs: 600_000, points: [], distanceM: 2223.9 },
      { startTs: 3_600_000, endTs: 4_800_000, points: [], distanceM: 1779.1 },
    ]
    const visits: Visit[] = [
      { startTs: 600_000, endTs: 3_600_000, centerLat: 39.9, centerLon: 116.4, pointCount: 30 },
    ]
    const stats = dayStats({ visits, trips, pointCount: 42 })

    expect(stats.distanceM).toBeCloseTo(4003, 0)
    expect(stats.movingMs).toBe(600_000 + 1_200_000)
    expect(stats.visitCount).toBe(1)
    expect(stats.pointCount).toBe(42)
  })

  it('visit 内位移不计入：仅含 visit 的时间线距离与移动时长为 0', () => {
    const visits: Visit[] = [
      { startTs: 0, endTs: 3_600_000, centerLat: 39.9, centerLon: 116.4, pointCount: 37 },
    ]
    const stats = dayStats({ visits, trips: [], pointCount: 37 })
    expect(stats.distanceM).toBe(0)
    expect(stats.movingMs).toBe(0)
    expect(stats.visitCount).toBe(1)
  })
})
