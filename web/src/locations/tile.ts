// 地图瓦片源配置纯函数（阶段 4a / tasks.md Task 10 / TR-10.2）。
//
// 零知识边界（tasks.md 明文纪律的唯一例外）：
//   瓦片 URL 属"非轨迹数据"，允许持久化到 localStorage；
//   明文坐标/轨迹数据仍然绝不落盘，本模块不接触任何坐标。
// 校验函数 isValidTileUrl 抽出为纯函数以便单测（TR-10.2 证据）。

/** 瓦片 URL 的 localStorage 键（契约：`eve.locations.tileUrl`）。 */
export const TILE_URL_STORAGE_KEY = 'eve.locations.tileUrl'

/** 缺省瓦片源：同源代理（服务端带合规 User-Agent 拉取上游，避免浏览器直连 OSM 403）。 */
export const DEFAULT_TILE_URL = '/api/v1/map/tiles/{z}/{x}/{y}.png'

/** 直连 OSM（自定义源示例；浏览器直连可能 403）。 */
export const OSM_DIRECT_TILE_URL = 'https://tile.openstreetmap.org/{z}/{x}/{y}.png'

/** 缺省源的署名 HTML（leaflet attribution）。 */
export const OSM_ATTRIBUTION =
  '&copy; <a href="https://www.openstreetmap.org/copyright" target="_blank" rel="noopener">OpenStreetMap</a> 贡献者'

/**
 * 是否为需携带 JWT 的同源 API 瓦片（img 标签无法带 Authorization，由地图组件 fetch 加载）。
 */
export function isProxiedTileUrl(url: string): boolean {
  const u = url.trim()
  return u.startsWith('/api/') && u.includes('{z}') && u.includes('{x}') && u.includes('{y}')
}

/**
 * 瓦片 URL 合法性校验（TR-10.2）：
 *   1. 同源相对路径 `/…` 或 http(s):// 绝对 URL；
 *   2. 必须包含 {z}、{x}、{y} 三个占位符（leaflet 渲染必需）。
 * 输入先 trim；返回 true 表示可保存生效。
 */
export function isValidTileUrl(url: string): boolean {
  const u = url.trim()
  if (u.startsWith('/')) {
    if (u.startsWith('//')) return false // 拒绝协议相对 URL
    return u.includes('{z}') && u.includes('{x}') && u.includes('{y}')
  }
  if (!/^https?:\/\//.test(u)) return false
  return u.includes('{z}') && u.includes('{x}') && u.includes('{y}')
}

/**
 * 读取当前瓦片 URL：localStorage 缺失或值非法（脏数据防御）时回退缺省同源代理。
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
