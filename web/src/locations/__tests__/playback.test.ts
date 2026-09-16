// playback 纯函数单测（tasks.md Task 10 / TR-10.1）。
//
// 覆盖三类契约场景：
//   1. 插值：t 落段内中点/任意比例处，坐标按相邻点线性插值；
//   2. 端点钳制：t 早于首点 / 晚于末点（含恰等于端点）取端点坐标；
//   3. 空态与退化：空点流返回 null，单点流任意 t 取该点。
// 纯函数无 DOM/网络依赖，node 环境直接跑。

import { describe, expect, it } from 'vitest'
import { SPEED_LEVELS, positionAt } from '../playback'
import type { TrackPoint } from '../core/types'

/** 构造最小轨迹点（回放只关心 ts/lat/lon，其余字段补常量）。 */
function pt(ts: number, lat: number, lon: number): TrackPoint {
  return { ts, lat, lon, acc: 10 }
}

describe('SPEED_LEVELS 契约', () => {
  it('倍速档位为 [1, 4, 16, 60]（与 tasks.md 一致，顺序固定）', () => {
    expect(SPEED_LEVELS).toEqual([1, 4, 16, 60])
  })
})

describe('positionAt 插值', () => {
  const points = [pt(0, 0, 0), pt(10_000, 1, 3), pt(20_000, 3, 3)]

  it('段内中点：坐标取两端点的线性中点', () => {
    const pos = positionAt(points, 5_000)!
    expect(pos.lat).toBeCloseTo(0.5, 10)
    expect(pos.lon).toBeCloseTo(1.5, 10)
    expect(pos.index).toBe(0)
  })

  it('段内任意比例：k=(t-a)/(b-a) 线性插值', () => {
    const pos = positionAt(points, 2_500)!
    expect(pos.lat).toBeCloseTo(0.25, 10)
    expect(pos.lon).toBeCloseTo(0.75, 10)
  })

  it('后一段插值：index 指向段起始点', () => {
    const pos = positionAt(points, 15_000)!
    expect(pos.lat).toBeCloseTo(2, 10)
    expect(pos.lon).toBeCloseTo(3, 10)
    expect(pos.index).toBe(1)
  })

  it('t 恰好等于中间点 ts：取该点坐标（归其右侧段）', () => {
    const pos = positionAt(points, 10_000)!
    expect(pos.lat).toBe(1)
    expect(pos.lon).toBe(3)
  })

  it('大量点流二分正确：定位到目标段', () => {
    // 1000 个点，间隔 1 秒；t 落在第 500 段中点。
    const many: TrackPoint[] = []
    for (let i = 0; i < 1000; i++) many.push(pt(i * 1_000, i, -i))
    const pos = positionAt(many, 500_500)!
    expect(pos.index).toBe(500)
    expect(pos.lat).toBeCloseTo(500.5, 10)
    expect(pos.lon).toBeCloseTo(-500.5, 10)
  })
})

describe('positionAt 端点钳制', () => {
  const points = [pt(1_000, 10, 20), pt(2_000, 30, 40)]

  it('t 早于首点 ts：钳制到首点', () => {
    expect(positionAt(points, 0)).toEqual({ lat: 10, lon: 20, index: 0 })
  })

  it('t 恰等于首点 ts：取首点', () => {
    expect(positionAt(points, 1_000)).toEqual({ lat: 10, lon: 20, index: 0 })
  })

  it('t 晚于末点 ts：钳制到末点', () => {
    expect(positionAt(points, 999_999)).toEqual({ lat: 30, lon: 40, index: 1 })
  })

  it('t 恰等于末点 ts：取末点', () => {
    expect(positionAt(points, 2_000)).toEqual({ lat: 30, lon: 40, index: 1 })
  })
})

describe('positionAt 空态与退化', () => {
  it('空点流返回 null', () => {
    expect(positionAt([], 1_000)).toBeNull()
  })

  it('单点流任意 t 都取该点', () => {
    const one = [pt(5_000, 7, 8)]
    expect(positionAt(one, 0)).toEqual({ lat: 7, lon: 8, index: 0 })
    expect(positionAt(one, 5_000)).toEqual({ lat: 7, lon: 8, index: 0 })
    expect(positionAt(one, 9_999_999)).toEqual({ lat: 7, lon: 8, index: 0 })
  })

  it('相邻点同 ts 防御：不除零，取段末点', () => {
    const dup = [pt(1_000, 1, 1), pt(1_000, 2, 2), pt(3_000, 3, 3)]
    // t=2_000 落在 [1_000, 3_000] 段（二分会跳过同 ts 的首段或落其上，均不 NaN）。
    const pos = positionAt(dup, 2_000)!
    expect(Number.isFinite(pos.lat)).toBe(true)
    expect(Number.isFinite(pos.lon)).toBe(true)
  })
})
