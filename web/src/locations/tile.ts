// 地图瓦片源配置纯函数（阶段 4a / tasks.md Task 10 / TR-10.2）。
//
// 零知识边界（tasks.md 明文纪律的唯一例外）：
//   瓦片 URL 属"非轨迹数据"，允许持久化到 localStorage；
//   明文坐标/轨迹数据仍然绝不落盘，本模块不接触任何坐标。
// 校验函数 isValidTileUrl 抽出为纯函数以便单测（TR-10.2 证据）。

/** 瓦片 URL 的 localStorage 键（契约：`eve.locations.tileUrl`）。 */
export const TILE_URL_STORAGE_KEY = 'eve.locations.tileUrl'

/** 缺省瓦片源：OSM 标准瓦片（附署名，满足 OSM 使用条款）。 */
export const DEFAULT_TILE_URL = 'https://tile.openstreetmap.org/{z}/{x}/{y}.png'

/** 缺省源的署名 HTML（leaflet attribution）。 */
export const OSM_ATTRIBUTION =
  '&copy; <a href="https://www.openstreetmap.org/copyright" target="_blank" rel="noopener">OpenStreetMap</a> 贡献者'

/**
 * 瓦片 URL 合法性校验（TR-10.2）：
 *   1. 必须以 http:// 或 https:// 开头（file: 等一律拒绝）；
 *   2. 必须包含 {z}、{x}、{y} 三个占位符（leaflet 渲染必需）。
 * 输入先 trim；返回 true 表示可保存生效。
 */
export function isValidTileUrl(url: string): boolean {
  const u = url.trim()
  if (!/^https?:\/\//.test(u)) return false
  return u.includes('{z}') && u.includes('{x}') && u.includes('{y}')
}

/**
 * 读取当前瓦片 URL：localStorage 缺失或值非法（脏数据防御）时回退缺省 OSM。
 * localStorage 不可用（隐私模式等）同样回退缺省，不抛错。
 */
export function loadTileUrl(): string {
  try {
    const v = localStorage.getItem(TILE_URL_STORAGE_KEY)
    return v !== null && isValidTileUrl(v) ? v.trim() : DEFAULT_TILE_URL
  } catch {
    return DEFAULT_TILE_URL
  }
}

/**
 * 保存自定义瓦片 URL：仅当 isValidTileUrl 通过才写入并返回 true；
 * 非法值拒绝保存（返回 false，由视图层提示），不覆盖现有配置。
 */
export function saveTileUrl(url: string): boolean {
  if (!isValidTileUrl(url)) return false
  try {
    localStorage.setItem(TILE_URL_STORAGE_KEY, url.trim())
    return true
  } catch {
    return false
  }
}
