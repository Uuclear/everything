// 位置轨迹（阶段 4a）Web 端数据模型。
//
// 纯类型定义，无任何运行时依赖（vue/pinia/DOM 一律禁止，TR-8.2）。
// TrackPoint / LocationBlockJson 的字段名（snake_case）与 Android
// collector/location/core/TrackPoint.kt 的 moshi DTO 逐字段一致，
// 是 Android / docs/module-schemas.md / Web 三方契约，任何改动必须三方同步。

/** 单个轨迹点（明文仅存浏览器内存，绝不持久化）。 */
export interface TrackPoint {
  /** 采样时刻（UTC 毫秒，取设备系统时间）。 */
  ts: number
  /** 纬度（WGS84 度）。 */
  lat: number
  /** 经度（WGS84 度）。 */
  lon: number
  /** 定位精度半径（米）；Android 入库前已过滤（>100m 丢弃）。 */
  acc: number
  /** 瞬时速度（米/秒），系统未提供时缺省/null。 */
  speed?: number | null
  /** 航向角（度，正北为 0 顺时针），系统未提供时缺省/null。 */
  bearing?: number | null
  /** 海拔（米），系统未提供时缺省/null。 */
  altitude?: number | null
  /** 定位来源（"gps"/"network" 等 provider 名），未知时缺省/null。 */
  provider?: string | null
}

/**
 * 轨迹块明文 DTO（decryptBlock 解密 JSON.parse 的产物）。
 *
 * 块 id 不在 JSON 内冗余存储，由 decode.ts 的 blockId() 纯函数从
 * (device_id, start_ts, end_ts) 派生（同 Android BlockPacker.blockId 规则）。
 */
export interface LocationBlockJson {
  /** 采集设备 id（服务端配对设备 id）。 */
  device_id: string
  /** 块首点时刻（UTC 毫秒）。 */
  start_ts: number
  /** 块末点时刻（UTC 毫秒）。 */
  end_ts: number
  /** 块内轨迹点（ts 升序）。 */
  points: TrackPoint[]
}

/** 停留点（驻留 ≥10 分钟且半径 ≤100m 的点簇）。 */
export interface Visit {
  /** 停留开始时刻（簇首点 ts，UTC 毫秒）。 */
  startTs: number
  /** 停留结束时刻（簇末点 ts，UTC 毫秒）。 */
  endTs: number
  /** 簇质心纬度（簇内点均值）。 */
  centerLat: number
  /** 簇质心经度（簇内点均值）。 */
  centerLon: number
  /** 簇内轨迹点数。 */
  pointCount: number
  /** 用户命名（"家"/"公司"/自定义），未命名时缺省（Task 10 填充）。 */
  name?: string
  /**
   * 跨夜标注：splitByLocalDay 判定 startTs 与 endTs 不属同一本地日时置 true。
   * Visit 归开始时刻所在日、跨夜不截断（FR-11），该标注供时间线展示"跨夜"。
   */
  overnight?: boolean
}

/** 移动段（两个 Visit 之间连续的行程）。 */
export interface Trip {
  /** 行程开始时刻（段内首点 ts，UTC 毫秒）。 */
  startTs: number
  /** 行程结束时刻（段内末点 ts，UTC 毫秒）。 */
  endTs: number
  /** 段内轨迹点（ts 升序，跨短暂停留簇连续拼接，供地图折线渲染）。 */
  points: TrackPoint[]
  /** 段内相邻点 haversine 距离累加（米）。 */
  distanceM: number
}

/** 当日统计（dayStats 产物）。 */
export interface DayStats {
  /** 总距离（米）：当日全部 trip 的 distanceM 求和（visit 内位移不计入）。 */
  distanceM: number
  /** 移动时长（毫秒）：当日全部 trip 的 (endTs - startTs) 求和。 */
  movingMs: number
  /** 停留数：当日 visit 个数。 */
  visitCount: number
  /** 轨迹点数：当日采样点总数。 */
  pointCount: number
}

/** 本地日时间线（splitByLocalDay 产物，key 见 dayKey）。 */
export interface DayTimeline {
  /** 本地日键，格式 "YYYY-MM-DD"（按传入时区偏移换算）。 */
  dayKey: string
  /** 归本日的停留（visit 归其开始时刻所在日，跨夜不截断、overnight 标注）。 */
  visits: Visit[]
  /** 归本日的移动段（与 visit 同规则：归开始时刻所在日，不截断）。 */
  trips: Trip[]
  /** 当日轨迹点数。 */
  pointCount: number
  /** 当日统计（splitByLocalDay 内调 dayStats 填充）。 */
  stats: DayStats
}
