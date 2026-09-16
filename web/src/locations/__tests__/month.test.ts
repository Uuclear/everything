// 轨迹页月度装载/切日/高亮纯函数单测（tasks.md Task 9 / TR-9.1）。
//
// 覆盖 month.ts 全部导出：月份 UTC 范围换算、年月平移、日历网格、
// 点流合并、月度分析管线、解密装载管线（真密文）、有数据日集合、
// 选日规则、时间线混排与展示格式化。
// 时区一律显式传入 tzOffsetMin（node 环境可测，与 core/days.ts 同口径）。
// 明文坐标仅存在本测试内存，绝不持久化（TR-9.2）。

import { beforeAll, describe, expect, it } from 'vitest'
import {
  loadSodium,
  locationBlockAAD,
  toBase64,
  type Sodium,
} from '../../crypto/envelope'
import { blockId, type ApiLocationBlock } from '../core/decode'
import type { DayTimeline, LocationBlockJson, TrackPoint } from '../core/types'
import {
  analyzeMonth,
  calendarCells,
  daysWithDataOf,
  decryptAndAnalyze,
  formatDurationMs,
  formatHHmm,
  formatKm,
  mergeBlockPoints,
  monthRangeUtc,
  pickInitialDay,
  shiftYearMonth,
  timelineItems,
  todayDayKey,
} from '../month'

let sodium: Sodium

beforeAll(async () => {
  sodium = await loadSodium()
})

/** 轨迹点快捷构造（仅 ts/lat/lon/acc 四必填字段，与 TrackPoint 契约一致）。 */
function pt(ts: number, lat = 39.9042, lon = 116.4074): TrackPoint {
  return { ts, lat, lon, acc: 10 }
}

/** 块明文 DTO 快捷构造。 */
function blockJson(deviceId: string, points: TrackPoint[]): LocationBlockJson {
  return {
    device_id: deviceId,
    start_ts: points[0].ts,
    end_ts: points[points.length - 1].ts,
    points,
  }
}

/**
 * 测试内密封助手：模拟 Android sealLocationBlock（envelope.ts 的 seal 为私有，
 * 位置块只有 open 导出），AAD/信封布局（nonce||ciphertext）与线上逐字节一致。
 */
function sealLocationBlockForTest(
  mk: Uint8Array,
  plaintextJson: string,
  id: string,
): Uint8Array {
  const nonce = sodium.randombytes_buf(sodium.crypto_aead_xchacha20poly1305_ietf_NPUBBYTES)
  const ciphertext = sodium.crypto_aead_xchacha20poly1305_ietf_encrypt(
    new TextEncoder().encode(plaintextJson),
    locationBlockAAD(id),
    null, // secret_nonce 固定 null（与 envelope.seal 一致）
    nonce,
    mk,
  )
  const out = new Uint8Array(nonce.length + ciphertext.length)
  out.set(nonce, 0)
  out.set(ciphertext, nonce.length)
  return out
}

/** 组装 API 密文块（cipher 为 base64，与 GET /api/v1/locations 线上形态一致）。 */
function rawBlockOf(mk: Uint8Array, block: LocationBlockJson): ApiLocationBlock {
  const id = blockId(block.device_id, block.start_ts, block.end_ts)
  const sealed = sealLocationBlockForTest(mk, JSON.stringify(block), id)
  return {
    id,
    device_id: block.device_id,
    start_ts: block.start_ts,
    end_ts: block.end_ts,
    point_count: block.points.length,
    cipher: toBase64(sealed),
    created_at: block.end_ts + 1000,
  }
}

/** 手工拼一个 DayTimeline（时间线混排/选日等纯函数的输入）。 */
function timelineOf(
  dayKey: string,
  visits: DayTimeline['visits'],
  trips: DayTimeline['trips'],
): DayTimeline {
  return {
    dayKey,
    visits,
    trips,
    pointCount: 0,
    stats: { distanceM: 0, movingMs: 0, visitCount: visits.length, pointCount: 0 },
  }
}

describe('monthRangeUtc（本地月初→次月月初的 UTC 毫秒范围）', () => {
  it('tz=0：范围即 UTC 月初到次月月初', () => {
    const { from, to } = monthRangeUtc(2026, 3, 0)
    expect(from).toBe(Date.UTC(2026, 2, 1))
    expect(to).toBe(Date.UTC(2026, 3, 1))
  })

  it('tz=+08:00（480）：范围整体前移 8 小时（本地月初 00:00 = UTC 前日 16:00）', () => {
    const { from, to } = monthRangeUtc(2026, 3, 480)
    expect(from).toBe(Date.UTC(2026, 2, 1) - 480 * 60_000)
    expect(to).toBe(Date.UTC(2026, 3, 1) - 480 * 60_000)
  })

  it('跨年：12 月的 to 为次年 1 月月初（Date.UTC 越界进位）', () => {
    const { to } = monthRangeUtc(2026, 12, 0)
    expect(to).toBe(Date.UTC(2027, 0, 1))
  })

  it('本地月跨度上限 31 天 + 偏移，远小于服务端 62 天限制（契约内）', () => {
    const { from, to } = monthRangeUtc(2026, 1, -840) // 极端负偏移（UTC-14）
    expect(to - from).toBeLessThanOrEqual(32 * 86_400_000)
  })
})

describe('shiftYearMonth（月份切换平移）', () => {
  it('上一月跨年：2026-01 -1 → 2025-12', () => {
    expect(shiftYearMonth(2026, 1, -1)).toEqual({ year: 2025, month: 12 })
  })

  it('下一月跨年：2025-12 +1 → 2026-01', () => {
    expect(shiftYearMonth(2025, 12, 1)).toEqual({ year: 2026, month: 1 })
  })

  it('同年内平移与 delta=0 恒等', () => {
    expect(shiftYearMonth(2026, 3, -1)).toEqual({ year: 2026, month: 2 })
    expect(shiftYearMonth(2026, 6, 0)).toEqual({ year: 2026, month: 6 })
  })

  it('大步长平移：+13 个月跨两年', () => {
    expect(shiftYearMonth(2026, 6, 13)).toEqual({ year: 2027, month: 7 })
  })
})

describe('calendarCells（月历网格，周一开头整周补齐）', () => {
  it('2026-03（月初为周日，31 天）：6 格前补 + 31 格当月 + 5 格后补 = 42 格', () => {
    const cells = calendarCells(2026, 3, 0)
    expect(cells).toHaveLength(42)
    // 前补格：3/1（周日）前 6 天，首格为 2/23（周一）。
    expect(cells[0]).toEqual({ dayKey: '2026-02-23', dayOfMonth: 23, inMonth: false })
    // 当月首格。
    expect(cells[6]).toEqual({ dayKey: '2026-03-01', dayOfMonth: 1, inMonth: true })
    // 当月末格与后补格。
    expect(cells[36]).toEqual({ dayKey: '2026-03-31', dayOfMonth: 31, inMonth: true })
    expect(cells[37]).toEqual({ dayKey: '2026-04-01', dayOfMonth: 1, inMonth: false })
    expect(cells[41]).toEqual({ dayKey: '2026-04-05', dayOfMonth: 5, inMonth: false })
    // 当月格数恰为 31，dayKey 全部唯一（高亮集合不会撞键）。
    expect(cells.filter((c) => c.inMonth)).toHaveLength(31)
    expect(new Set(cells.map((c) => c.dayKey)).size).toBe(42)
  })

  it('2024-02（闰年，月初为周四）：3 格前补 + 29 格 = 35 格（5 行）', () => {
    const cells = calendarCells(2024, 2, 0)
    expect(cells).toHaveLength(35)
    expect(cells[0]).toEqual({ dayKey: '2024-01-29', dayOfMonth: 29, inMonth: false })
    expect(cells[3]).toEqual({ dayKey: '2024-02-01', dayOfMonth: 1, inMonth: true })
    expect(cells[31]).toEqual({ dayKey: '2024-02-29', dayOfMonth: 29, inMonth: true })
    expect(cells.filter((c) => c.inMonth)).toHaveLength(29)
  })

  it('tz=+08:00：分日口径与 splitByLocalDay 一致（本地月初仍归 03-01）', () => {
    const cells = calendarCells(2026, 3, 480)
    const firstInMonth = cells.find((c) => c.inMonth)!
    expect(firstInMonth.dayKey).toBe('2026-03-01')
    // 同一天（3/10）用 localDayKey 同口径核验：高亮键与分析产物键严格同形。
    const sample = cells.find((c) => c.dayOfMonth === 10 && c.inMonth)!
    expect(sample.dayKey).toBe('2026-03-10')
  })
})

describe('mergeBlockPoints / analyzeMonth（月度分析管线）', () => {
  it('多块点流拼接为单一序列（逐块顺序保持）', () => {
    const b1 = blockJson('dev-1', [pt(1000), pt(2000)])
    const b2 = blockJson('dev-1', [pt(3000)])
    expect(mergeBlockPoints([b1, b2]).map((p) => p.ts)).toEqual([1000, 2000, 3000])
  })

  it('跨天点流按本地日分桶（tz=0）：dayKey 与 pointCount 正确', () => {
    // 3/10 三点（间隔 30s，快速移动不形成停留簇）、3/11 两点。
    const day1 = Date.UTC(2026, 2, 10, 10, 0, 0)
    const day2 = Date.UTC(2026, 2, 11, 12, 0, 0)
    const blocks = [
      blockJson('dev-1', [
        pt(day1, 39.9042, 116.4074),
        pt(day1 + 30_000, 39.9142, 116.4174),
        pt(day1 + 60_000, 39.9242, 116.4274),
      ]),
      blockJson('dev-1', [pt(day2, 39.9342), pt(day2 + 30_000, 39.9442)]),
    ]
    const timelines = analyzeMonth(blocks, 0)
    expect([...timelines.keys()].sort()).toEqual(['2026-03-10', '2026-03-11'])
    expect(timelines.get('2026-03-10')!.pointCount).toBe(3)
    expect(timelines.get('2026-03-11')!.pointCount).toBe(2)
  })

  it('时区分日：UTC 3/10 17:00 在 +08:00 口径下归 3/11', () => {
    const ts = Date.UTC(2026, 2, 10, 17, 0, 0) // 本地 3/11 01:00
    const timelines = analyzeMonth([blockJson('dev-1', [pt(ts), pt(ts + 30_000, 39.9142)])], 480)
    expect([...timelines.keys()]).toEqual(['2026-03-11'])
  })
})

describe('decryptAndAnalyze（装载管线：逐块解密 → 分析）', () => {
  const mk = new Uint8Array(32).map((_, i) => i) // 00..1f 固定测试 MK

  it('真密文块解密后产出正确分日结果', () => {
    const day1 = Date.UTC(2026, 5, 15, 9, 0, 0)
    const block = blockJson('dev-1', [pt(day1), pt(day1 + 30_000, 39.9142)])
    const timelines = decryptAndAnalyze(sodium, mk, [rawBlockOf(mk, block)], 0)
    expect([...timelines.keys()]).toEqual(['2026-06-15'])
    expect(timelines.get('2026-06-15')!.pointCount).toBe(2)
  })

  it('多块装载：不同设备块均可解密合并', () => {
    const t1 = Date.UTC(2026, 5, 15, 9, 0, 0)
    const t2 = Date.UTC(2026, 5, 16, 9, 0, 0)
    const raw = [
      rawBlockOf(mk, blockJson('phone', [pt(t1), pt(t1 + 30_000, 39.9142)])),
      rawBlockOf(mk, blockJson('watch', [pt(t2)])),
    ]
    const timelines = decryptAndAnalyze(sodium, mk, raw, 0)
    expect([...timelines.keys()].sort()).toEqual(['2026-06-15', '2026-06-16'])
  })

  it('任一块 MK 不符即整体抛异常（零知识：错钥不产出任何部分明文）', () => {
    const good = rawBlockOf(mk, blockJson('dev-1', [pt(Date.UTC(2026, 5, 15, 9)), pt(Date.UTC(2026, 5, 15, 9, 0, 30), 39.9142)]))
    const wrongMk = new Uint8Array(32).map((_, i) => i ^ 0xff)
    const bad = rawBlockOf(wrongMk, blockJson('dev-2', [pt(Date.UTC(2026, 5, 16, 9))]))
    expect(() => decryptAndAnalyze(sodium, mk, [good, bad], 0)).toThrow()
  })
})

describe('daysWithDataOf（日历高亮集合）', () => {
  it('返回 dayKey 字典序升序（与插入顺序无关，保证确定性）', () => {
    const timelines = new Map<string, DayTimeline>([
      ['2026-03-12', timelineOf('2026-03-12', [], [])],
      ['2026-03-05', timelineOf('2026-03-05', [], [])],
    ])
    expect(daysWithDataOf(timelines)).toEqual(['2026-03-05', '2026-03-12'])
  })

  it('空缓存返回空数组（全月无数据 → 日历无高亮）', () => {
    expect(daysWithDataOf(new Map())).toEqual([])
  })
})

describe('pickInitialDay（切日规则四分支）', () => {
  const timelines = new Map<string, DayTimeline>([
    ['2026-03-05', timelineOf('2026-03-05', [], [])],
    ['2026-03-12', timelineOf('2026-03-12', [], [])],
  ])

  it('preferred 有数据时保留（切月后原选中日不动）', () => {
    expect(pickInitialDay(timelines, '2026-03-15', '2026-03-05')).toBe('2026-03-05')
  })

  it('preferred 无数据时优先今天（今天有数据）', () => {
    expect(pickInitialDay(timelines, '2026-03-12', '2026-03-09')).toBe('2026-03-12')
  })

  it('preferred 与今天都无数据时取最近有数据日（最大 dayKey）', () => {
    expect(pickInitialDay(timelines, '2026-03-15')).toBe('2026-03-12')
  })

  it('全月无数据返回 null（视图空态）', () => {
    expect(pickInitialDay(new Map(), '2026-03-15')).toBeNull()
  })
})

describe('todayDayKey（今天的本地日键）', () => {
  it('tz=0 与 +08:00 对同一 UTC 时刻给出不同本地日', () => {
    const ts = Date.UTC(2026, 2, 10, 17, 0, 0) // UTC 3/10 17:00 = 北京 3/11 01:00
    expect(todayDayKey(0, ts)).toBe('2026-03-10')
    expect(todayDayKey(480, ts)).toBe('2026-03-11')
  })
})

describe('timelineItems（visit/trip 按开始时刻混排）', () => {
  const visit = {
    startTs: 200,
    endTs: 300,
    centerLat: 39.9,
    centerLon: 116.4,
    pointCount: 3,
  }
  const trip = { startTs: 100, endTs: 200, points: [], distanceM: 500 }

  it('按 startTs 升序：早开始的 trip 排在 visit 前', () => {
    const items = timelineItems(timelineOf('2026-03-10', [visit], [trip]))
    expect(items.map((i) => i.kind)).toEqual(['trip', 'visit'])
    expect(items[0]).toMatchObject({ kind: 'trip', startTs: 100 })
    expect(items[1]).toMatchObject({ kind: 'visit', startTs: 200 })
  })

  it('同时刻 visit 在前（先到达后离开的语义）', () => {
    const sameStartTrip = { ...trip, startTs: 200 }
    const items = timelineItems(timelineOf('2026-03-10', [visit], [sameStartTrip]))
    expect(items.map((i) => i.kind)).toEqual(['visit', 'trip'])
  })

  it('空时间线返回空数组（当日无数据空态）', () => {
    expect(timelineItems(timelineOf('2026-03-10', [], []))).toEqual([])
  })
})

describe('展示格式化（formatKm / formatDurationMs / formatHHmm）', () => {
  it('formatKm：米 → km 一位小数', () => {
    expect(formatKm(12_345)).toBe('12.3')
    expect(formatKm(999)).toBe('1.0')
    expect(formatKm(0)).toBe('0.0')
  })

  it('formatDurationMs：中文短格式（小时+分 / 仅分钟）', () => {
    expect(formatDurationMs(45 * 60_000)).toBe('45分钟')
    expect(formatDurationMs((3 * 60 + 25) * 60_000)).toBe('3小时25分')
    expect(formatDurationMs(2 * 3_600_000)).toBe('2小时')
    expect(formatDurationMs(0)).toBe('0分钟')
  })

  it('formatHHmm：UTC 毫秒 → 本地 HH:mm（显式时区）', () => {
    expect(formatHHmm(0, 0)).toBe('00:00')
    expect(formatHHmm(0, 480)).toBe('08:00')
    expect(formatHHmm(Date.UTC(2026, 2, 10, 13, 5), 0)).toBe('13:05')
  })
})
