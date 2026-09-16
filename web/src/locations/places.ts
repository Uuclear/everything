// 命名地点（module=place）索引纯函数（阶段 4a / tasks.md Task 9 / TR-9.1）。
//
// place 记录由 vault store 的 ingest 路径解密进独立 placeRecords 缓存；
// 本模块把它建成"记录 id → 名称/version"索引，供时间线/地图按
// geohash7 查名（命名覆盖时 version 取自该索引 +1，写入属 Task 10）。
// 不依赖 vue/pinia/DOM，可单测。

import { encodeGeohash } from './core/geohash'
import type { Visit } from './core/types'
import type { PlaceData } from '../types/vault'

/** place 记录 id 规则（三端契约）：`place:{geohash7}`。 */
export function placeIdFor(geohash7: string): string {
  return `place:${geohash7}`
}

/** 索引条目：时间线查名只用 name；version 供 Task 10 命名覆盖时递增。 */
export interface PlaceIndexEntry {
  /** 记录 id（`place:{geohash7}`）。 */
  id: string
  /** 地点名称。 */
  name: string
  /** 分类（可选透传）。 */
  category?: string
  /** 当前版本号（取自 placeRecords 缓存；覆盖命名时 +1）。 */
  version: number
  /** 中心纬度（地图渲染备用）。 */
  centerLat: number
  /** 中心经度（地图渲染备用）。 */
  centerLon: number
  /** 覆盖半径（米）。 */
  radiusM: number
}

/** 可被索引的最小记录形状（vault 的 DecryptedPlaceRecord 满足之）。 */
export interface PlaceRecordLike {
  id: string
  version: number
  data: PlaceData
}

/**
 * 把 placeRecords 缓存建成 id → PlaceIndexEntry 的 Map。
 *
 * 键即记录 id（`place:{geohash7}`）：时间线由 visit 质心 encodeGeohash(_, 7)
 * 推出同形 id 后直查；同 id 重复出现时保留高 version（同步乱序防御）。
 */
export function indexPlacesById(records: Iterable<PlaceRecordLike>): Map<string, PlaceIndexEntry> {
  const index = new Map<string, PlaceIndexEntry>()
  for (const r of records) {
    const existed = index.get(r.id)
    if (existed && existed.version >= r.version) continue // 版本不回退
    index.set(r.id, {
      id: r.id,
      name: r.data.name,
      category: r.data.category,
      version: r.version,
      centerLat: r.data.center_lat,
      centerLon: r.data.center_lon,
      radiusM: r.data.radius_m,
    })
  }
  return index
}

/**
 * 查 visit 的地点名：质心 → geohash7 → `place:{geohash7}` → 索引。
 *
 * @returns 命中返回名称，未命名（无记录）返回 null（视图层显示"未命名地点"）
 */
export function visitPlaceName(
  index: Map<string, PlaceIndexEntry>,
  visit: Pick<Visit, 'centerLat' | 'centerLon'>,
): string | null {
  const id = placeIdFor(encodeGeohash(visit.centerLat, visit.centerLon, 7))
  return index.get(id)?.name ?? null
}
