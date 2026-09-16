// geohash 纯函数单测（tasks.md Task 8 / AC-12）。
// 已知对照锚点取自 geohash.org 经典示例坐标，锁定编码表与二分顺序。

import { describe, expect, it } from 'vitest'
import { encodeGeohash } from '../geohash'

describe('encodeGeohash', () => {
  it('已知对照：lat=57.64911, lon=10.40744, precision=11 → u4pruydqqvj', () => {
    expect(encodeGeohash(57.64911, 10.40744, 11)).toBe('u4pruydqqvj')
  })

  it('已知对照：precision=7 与 11 位结果前缀一致（u4pruyd）', () => {
    const short = encodeGeohash(57.64911, 10.40744, 7)
    expect(short).toBe('u4pruyd')
    // 同坐标不同精度：长码必然以短码为前缀（网格逐级细分）。
    expect(encodeGeohash(57.64911, 10.40744, 11).startsWith(short)).toBe(true)
  })

  it('编码确定性：同坐标同精度重复编码结果逐字一致（place id 幂等前提）', () => {
    expect(encodeGeohash(39.9042, 116.4074, 7)).toBe(encodeGeohash(39.9042, 116.4074, 7))
  })

  it('输出长度恰为 precision，且字符全部落在 base32 表内', () => {
    const hash = encodeGeohash(-33.8688, 151.2093, 9)
    expect(hash).toHaveLength(9)
    expect(hash).toMatch(/^[0-9b-hjkmnpqrstv-z]+$/)
  })

  it('非法输入拒绝：precision 非正整数 / 坐标越界均抛错', () => {
    expect(() => encodeGeohash(39.9, 116.4, 0)).toThrow()
    expect(() => encodeGeohash(39.9, 116.4, 2.5)).toThrow()
    expect(() => encodeGeohash(91, 116.4, 7)).toThrow()
    expect(() => encodeGeohash(39.9, 181, 7)).toThrow()
  })
})
