// ============================================================================
// RateTable 纯函数单元测试（stage5-finance-v2 / B5 / TR-5.2 / FR-V2-C.2、C.3）
// ============================================================================
//
// 验证目标（≥8 用例）：
//   1. fixture 字节级一致性 —— rate-table-cases.json 原始字节 SHA-256 必须等于
//      Android 真理源常量（node:crypto createHash 对 ?raw 原始 UTF-8 字节摘要）；
//   2. fixture convertCases 11 条全量数据驱动（正向 / 反向 / 自交叉 / 缺失
//      null / 负数符号 / 半分边界）；
//   3. parseRateTable 容错：version 缺失接受、异值拒绝、坏 key、自交叉 key、
//      rate<=0、rate 非数字、rate 无限、无 effective_ts、rates 空、根非对象；
//   4. 负数符号双端锁定（fixture ⑨⑩⑪）+ 空表自交叉 + 非法代码 null + 大数。
//
// 关联:
//   - web/src/finance/rateTable.ts（被测目标）
//   - web/src/finance/__fixtures__/rate-table-cases.json（双端共享 fixture）
//   - android/.../finance/RateTable.kt（Android 镜像真理源）
// ============================================================================

import { describe, it, expect } from 'vitest'
import { createHash } from 'node:crypto'
import {
  convertMinor,
  convertMinorOrIdentity,
  parseRateTable,
  type RateTable,
} from '../rateTable'
// ?raw 拿原始文本，再经 TextEncoder 还原为原始 UTF-8 字节做摘要
//（fixture 为 LF、无 BOM 的合法 UTF-8，编码结果与磁盘字节逐字节一致）。
import rateTableRaw from '../__fixtures__/rate-table-cases.json?raw'
import rateTableJson from '../__fixtures__/rate-table-cases.json'

// ============================================================================
// 常量与 fixture 类型
// ============================================================================

/** fixture 真理源 SHA-256（与 Android 镜像必须逐字节一致）。 */
const EXPECTED_FIXTURE_SHA256 =
  '7a1c77af553595423a3d0a338421e00eb7c0f59ca7b906d9768faf3abb2aea12'

interface ConvertCase {
  name: string
  table: string
  amountMinor: number
  from: string
  to: string
  expectedMinor: number | null
}

interface RateTablePackage {
  version?: number
  effective_ts: number
  rates: Record<string, number>
}

interface RateTableFixture {
  tables: Record<string, RateTablePackage>
  convertCases: ConvertCase[]
}

const fixture = rateTableJson as unknown as RateTableFixture

/** fixture tables 段：重新 JSON.stringify 后走 parseRateTable 入口（顺带验证解析）。 */
const tables: Record<string, RateTable> = Object.fromEntries(
  Object.entries(fixture.tables).map(([name, pkg]) => [
    name,
    parseRateTable(JSON.stringify(pkg)),
  ]),
)

/** 断言 parseRateTable 必抛中文 message。 */
function expectRejected(json: string, clue: string): void {
  expect(() => parseRateTable(json), `非法包未被拒绝：${clue}`).toThrowError(/.+/)
}

// ============================================================================
// 1. fixture 字节级一致性
// ============================================================================

describe('rateTable / fixture 一致性', () => {
  it('fixture 原始字节 SHA-256 与 Android 真理源常量一致（node:crypto）', () => {
    const rawBytes = new TextEncoder().encode(rateTableRaw)
    const actual = createHash('sha256').update(rawBytes).digest('hex')
    expect(
      actual,
      'fixture 被改写或行尾变化（必须 LF、无 BOM）；请与 Android 镜像重新对齐',
    ).toBe(EXPECTED_FIXTURE_SHA256)
  })

  it('fixture 含不少于 8 条 convertCases（实际 11 条）', () => {
    expect(fixture.convertCases.length).toBeGreaterThanOrEqual(8)
  })

  it('fixture main 表解析后 effectiveTs 与 4 条汇率正确', () => {
    const main = tables.main
    expect(main.effectiveTs).toBe(1735689600000)
    expect(Object.keys(main.rates).sort()).toEqual(
      ['EUR/CNY', 'HKD/CNY', 'JPY/CNY', 'USD/CNY'].sort(),
    )
    expect(main.rates['USD/CNY']).toBe(7.25)
  })
})

// ============================================================================
// 2. fixture 全量数据驱动 —— convert / convertMinorOrIdentity
// ============================================================================

describe('convertMinor / fixture 数据驱动', () => {
  it('11 条用例逐条匹配（含 null 缺失与三条负数舍入锁定例）', () => {
    for (const tc of fixture.convertCases) {
      const table = tables[tc.table]
      const actual = convertMinor(BigInt(tc.amountMinor), tc.from, tc.to, table)
      const expected = tc.expectedMinor === null ? null : BigInt(tc.expectedMinor)
      expect(actual, `case[${tc.name}] convert 结果不一致`).toEqual(expected)
      // orIdentity：null 退回原值，非 null 与 convert 一致。
      const fallback = convertMinorOrIdentity(BigInt(tc.amountMinor), tc.from, tc.to, table)
      expect(fallback, `case[${tc.name}] orIdentity 结果不一致`).toEqual(
        expected ?? BigInt(tc.amountMinor),
      )
    }
  })
})

// ============================================================================
// 3. parseRateTable 容错与非法包
// ============================================================================

describe('parseRateTable / 容错与拒绝', () => {
  it('version 缺失按 1 接受', () => {
    const table = parseRateTable('{"effective_ts":1,"rates":{"USD/CNY":7.25}}')
    expect(table.effectiveTs).toBe(1)
    expect(table.rates['USD/CNY']).toBe(7.25)
  })

  it('version 显式不为 1 拒绝', () => {
    expectRejected('{"version":2,"effective_ts":1,"rates":{"USD/CNY":7.25}}', 'version=2')
  })

  it('effective_ts 缺失 / 为负 / 非整数 拒绝', () => {
    expectRejected('{"version":1,"rates":{"USD/CNY":7.25}}', '缺 effective_ts')
    expectRejected('{"version":1,"effective_ts":-1,"rates":{"USD/CNY":7.25}}', '负 effective_ts')
    expectRejected('{"version":1,"effective_ts":1.5,"rates":{"USD/CNY":7.25}}', '小数 effective_ts')
  })

  it('rates 为空对象 / 非对象 拒绝', () => {
    expectRejected('{"version":1,"effective_ts":1,"rates":{}}', 'rates 空')
    expectRejected('{"version":1,"effective_ts":1,"rates":[]}', 'rates 为数组')
  })

  it('坏键 / 自交叉键 拒绝', () => {
    expectRejected('{"version":1,"effective_ts":1,"rates":{"USD-CNY":7.25}}', '坏键')
    expectRejected('{"version":1,"effective_ts":1,"rates":{"usd/cny":7.25}}', '小写键')
    expectRejected('{"version":1,"effective_ts":1,"rates":{"USD/USD":1}}', '自交叉键')
  })

  it('rate 为 0 / 负数 / 字符串 / null 拒绝', () => {
    expectRejected('{"version":1,"effective_ts":1,"rates":{"USD/CNY":0}}', '0 汇率')
    expectRejected('{"version":1,"effective_ts":1,"rates":{"USD/CNY":-7.25}}', '负汇率')
    expectRejected('{"version":1,"effective_ts":1,"rates":{"USD/CNY":"7.25"}}', '字符串汇率')
    expectRejected('{"version":1,"effective_ts":1,"rates":{"USD/CNY":null}}', 'null 汇率')
  })

  it('rate 为 Infinity（1e999）拒绝', () => {
    expectRejected('{"version":1,"effective_ts":1,"rates":{"USD/CNY":1e999}}', '无限汇率')
  })

  it('根节点不是 JSON 对象 拒绝', () => {
    expectRejected('not-a-json', '裸字符串')
    expectRejected('[]', '数组根')
    expectRejected('null', 'null 根')
  })
})

// ============================================================================
// 4. convert 边界 —— 空表自交叉 / 非法代码 / 大数
// ============================================================================

describe('convertMinor / 边界', () => {
  it('自交叉不读表，空内存表也恒等返回', () => {
    const emptyTable: RateTable = { effectiveTs: 0, rates: {} }
    expect(convertMinor(12345n, 'USD', 'USD', emptyTable)).toBe(12345n)
  })

  it('非法币种代码一律 null（含非法代码自交叉）', () => {
    const main = tables.main
    expect(convertMinor(100n, 'US', 'CNY', main)).toBeNull()
    expect(convertMinor(100n, 'USD', 'cny', main)).toBeNull()
    expect(convertMinor(100n, 'usd', 'usd', main)).toBeNull()
  })

  it('业务大数 1e12 分（100 亿元）×7.25 = 7.25e12 分，不溢出不丢整', () => {
    const main = tables.main
    expect(convertMinor(1_000_000_000_000n, 'USD', 'CNY', main)).toBe(7_250_000_000_000n)
  })

  it('负数半分边界双端锁定：−2×7.25 必须为 −15（朴素 Math.round 会误得 −14）', () => {
    const main = tables.main
    // 该断言在 JS 侧直接证明"绝对值取整再恢复符号"规则的必要性。
    expect(Math.round(-14.5)).toBe(-14)
    expect(convertMinor(-2n, 'USD', 'CNY', main)).toBe(-15n)
    expect(convertMinor(-101n, 'EUR', 'USD', tables.usdEur)).toBe(-126n)
  })
})
