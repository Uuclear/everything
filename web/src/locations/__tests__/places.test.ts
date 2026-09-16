// 命名地点（module=place）索引纯函数单测（tasks.md Task 9 / TR-9.1）。
//
// 锁定三端契约：place 记录 id = `place:{geohash7}`；时间线由 visit 质心
// encodeGeohash(_, 7) 推出同形 id 查名；索引版本不回退（Task 10 覆盖命名
// 时 version +1 的前提）。geohash 锚点复用 core geohash.test.ts 的已知对照。

import { describe, expect, it } from 'vitest'
import {
  indexPlacesById,
  placeIdFor,
  visitPlaceName,
  type PlaceRecordLike,
} from '../places'

/** geohash.org 经典对照坐标：encodeGeohash(57.64911, 10.40744, 7) === 'u4pruyd'。 */
const ANCHOR_LAT = 57.64911
const ANCHOR_LON = 10.40744
const ANCHOR_GEOHASH7 = 'u4pruyd'

/** place 记录快捷构造（满足 PlaceRecordLike 最小形状）。 */
function placeRecord(id: string, version: number, name: string): PlaceRecordLike {
  return {
    id,
    version,
    data: {
      name,
      center_lat: ANCHOR_LAT,
      center_lon: ANCHOR_LON,
      radius_m: 100,
    },
  }
}

describe('placeIdFor（记录 id 规则）', () => {
  it('id 恰为 "place:{geohash7}"（三端契约，命名覆盖幂等键）', () => {
    expect(placeIdFor(ANCHOR_GEOHASH7)).toBe('place:u4pruyd')
  })
})

describe('indexPlacesById（placeRecords → id 索引）', () => {
  it('键即记录 id；字段透传 name/category/version/中心/半径', () => {
    const index = indexPlacesById([
      {
        id: 'place:u4pruyd',
        version: 2,
        data: {
          name: '家',
          category: 'home',
          center_lat: ANCHOR_LAT,
          center_lon: ANCHOR_LON,
          radius_m: 80,
        },
      },
    ])
    const entry = index.get('place:u4pruyd')!
    expect(entry).toEqual({
      id: 'place:u4pruyd',
      name: '家',
      category: 'home',
      version: 2,
      centerLat: ANCHOR_LAT,
      centerLon: ANCHOR_LON,
      radiusM: 80,
    })
  })

  it('多条不同 id 全部入索引', () => {
    const index = indexPlacesById([
      placeRecord('place:u4pruyd', 1, '家'),
      placeRecord('place:wx4g0ec', 3, '公司'),
    ])
    expect(index.size).toBe(2)
    expect(index.get('place:wx4g0ec')!.name).toBe('公司')
  })

  it('同 id 乱序出现时保留高 version（同步乱序防御，版本不回退）', () => {
    const asc = indexPlacesById([
      placeRecord('place:u4pruyd', 1, '旧名'),
      placeRecord('place:u4pruyd', 2, '新名'),
    ])
    expect(asc.get('place:u4pruyd')!.name).toBe('新名')
    expect(asc.get('place:u4pruyd')!.version).toBe(2)

    // 逆序到达（高版本先到）：低版本不得覆盖。
    const desc = indexPlacesById([
      placeRecord('place:u4pruyd', 2, '新名'),
      placeRecord('place:u4pruyd', 1, '旧名'),
    ])
    expect(desc.get('place:u4pruyd')!.name).toBe('新名')
    expect(desc.get('place:u4pruyd')!.version).toBe(2)
  })

  it('空输入返回空索引', () => {
    expect(indexPlacesById([]).size).toBe(0)
  })
})

describe('visitPlaceName（visit 质心 → geohash7 → 查名）', () => {
  it('命中命名地点返回名称（锚点坐标 u4pruyd）', () => {
    const index = indexPlacesById([placeRecord('place:u4pruyd', 1, '家')])
    expect(
      visitPlaceName(index, { centerLat: ANCHOR_LAT, centerLon: ANCHOR_LON }),
    ).toBe('家')
  })

  it('未命名（无记录）返回 null，视图层显示"未命名地点"', () => {
    const index = indexPlacesById([placeRecord('place:u4pruyd', 1, '家')])
    // 原点 geohash7 = s000000，不在索引内。
    expect(visitPlaceName(index, { centerLat: 0, centerLon: 0 })).toBeNull()
  })

  it('空索引一律返回 null（place 尚未同步时时间线正常降级）', () => {
    expect(
      visitPlaceName(new Map(), { centerLat: ANCHOR_LAT, centerLon: ANCHOR_LON }),
    ).toBeNull()
  })
})
