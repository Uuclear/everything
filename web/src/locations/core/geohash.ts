// geohash 编码纯函数（阶段 4a，FR-13：visit 命名 id = place:{geohash7(中心点)}）。
//
// 标准 geohash 算法：经纬度区间交替二分（经度先），每 5 bit 查 base32 表。
// base32 字符表与 geohash.org 约定一致（去掉 a/i/l/o 防混淆）。
// 已知对照（单测锚点）：lat=57.64911, lon=10.40744, precision=11 → "u4pruydqqvj"。

/** geohash 标准 base32 字符表（三端契约，不得改动顺序）。 */
const BASE32 = '0123456789bcdefghjkmnpqrstuvwxyz'

/**
 * 把 WGS84 坐标编码为 geohash 字符串。
 *
 * @param lat 纬度（度，[-90, 90]）
 * @param lon 经度（度，[-180, 180]）
 * @param precision 输出字符数（≥1）；place 记录用 7（约 153m×153m 网格）
 * @returns geohash 字符串（长度 = precision）
 */
export function encodeGeohash(lat: number, lon: number, precision: number): string {
  if (!Number.isInteger(precision) || precision < 1) {
    throw new Error(`geohash precision 必须为正整数，实际 ${precision}`)
  }
  if (lat < -90 || lat > 90 || lon < -180 || lon > 180) {
    throw new Error(`坐标越界：lat=${lat}, lon=${lon}`)
  }

  // 纬/经度初始取值区间。
  let latMin = -90
  let latMax = 90
  let lonMin = -180
  let lonMax = 180

  let hash = ''
  // bit 交替标志：true 表示本轮二分经度（geohash 约定经度先行）。
  let evenBit = true
  // 当前字符累积的 bit 数（0..4），满 5 位查表出一个字符。
  let bit = 0
  // 当前字符的 bit 累积值。
  let ch = 0

  while (hash.length < precision) {
    if (evenBit) {
      // 二分经度：坐标落右半区间则该 bit 置 1，并收缩区间。
      const mid = (lonMin + lonMax) / 2
      if (lon >= mid) {
        ch = (ch << 1) | 1
        lonMin = mid
      } else {
        ch = ch << 1
        lonMax = mid
      }
    } else {
      // 二分纬度：同理。
      const mid = (latMin + latMax) / 2
      if (lat >= mid) {
        ch = (ch << 1) | 1
        latMin = mid
      } else {
        ch = ch << 1
        latMax = mid
      }
    }
    evenBit = !evenBit
    bit++
    if (bit === 5) {
      hash += BASE32[ch]
      bit = 0
      ch = 0
    }
  }
  return hash
}
