// ============================================================================
// QuoteTable 纯函数单元测试
// （stage5-finance-v2 / Task 8 / FR-V2-D.2）
// ============================================================================
//
// 路径: web/src/finance/__tests__/quoteTable.spec.ts
//
// 验证目标（5 用例, 与 Android QuoteTableTest 1:1 镜像）：
//   1) parse 合法行情包: 3 条 quote 字段完整解析;
//   2) parse 同 symbol 多次出现: 取最后一条覆盖（last-write-wins）;
//   3) parse 字段非法（price_minor 负 / currency 小写 / ts 小数 / quotes 空 /
//      version=2 异值 / ts 缺失）: 抛 Error;
//   4) priceMinorOf 命中 + 缺价 + 非法长度边界;
//   5) encodeQuoteTable ↔ parseQuoteTable 往返一致。
//
// 关联:
//   - web/src/finance/quoteTable.ts（被测目标）
//   - android/.../finance/QuoteTableTest.kt（Android 镜像）
//   - web/src/finance/aggregator.ts#investmentMarketValue（聚合消费方）
// ============================================================================

import { describe, it, expect } from 'vitest'
import {
  encodeQuoteTable,
  parseQuoteTable,
  priceMinorOf,
  type Quote,
  type QuoteTable,
} from '../quoteTable'

// ============================================================================
// 1) parse 合法行情包
// ============================================================================

describe('quoteTable / parse 合法入参', () => {
  it('完整字段: ts / base / 3 条 quote 全部解析', () => {
    const ts = 1735689600000
    const table = parseQuoteTable(
      JSON.stringify({
        version: 1,
        ts,
        base: 'CNY',
        quotes: [
          { symbol: 'AAPL', price_minor: 18500, currency: 'USD', ts },
          { symbol: '0700.HK', price_minor: 38000, currency: 'HKD', ts },
          { symbol: '600519.SH', price_minor: 170000, currency: 'CNY', ts },
        ],
      }),
    )
    expect(table.ts).toBe(ts)
    expect(table.base).toBe('CNY')
    expect(table.quotes.size).toBe(3)
    expect(table.quotes.get('AAPL')).toMatchObject({
      symbol: 'AAPL',
      priceMinor: 18500,
      currency: 'USD',
      ts,
    })
    expect(table.quotes.get('0700.HK')?.priceMinor).toBe(38000)
    expect(table.quotes.get('600519.SH')?.currency).toBe('CNY')
  })

  it('version 缺失按 1 接受', () => {
    const table = parseQuoteTable(
      JSON.stringify({
        ts: 1,
        quotes: [{ symbol: 'A', price_minor: 1, currency: 'USD', ts: 1 }],
      }),
    )
    expect(table.ts).toBe(1)
    expect(table.quotes.size).toBe(1)
  })
})

// ============================================================================
// 2) 同 symbol 多次出现 → last-write-wins
// ============================================================================

describe('quoteTable / last-write-wins', () => {
  it('同 symbol 多次出现: 取数组最后一条覆盖', () => {
    const ts = 1735689600000
    const table = parseQuoteTable(
      JSON.stringify({
        version: 1,
        ts,
        quotes: [
          { symbol: 'AAPL', price_minor: 18000, currency: 'USD', ts },
          { symbol: 'AAPL', price_minor: 18500, currency: 'USD', ts },
        ],
      }),
    )
    expect(table.quotes.size).toBe(1)
    expect(table.quotes.get('AAPL')?.priceMinor).toBe(18500)
  })
})

// ============================================================================
// 3) parse 字段非法: 抛 Error
// ============================================================================

describe('quoteTable / parse 拒绝', () => {
  it('price_minor 负数: 抛 Error 含 price_minor 关键词', () => {
    expect(() =>
      parseQuoteTable(
        '{"version":1,"ts":1,"quotes":[{"symbol":"A","price_minor":-1,"currency":"USD","ts":1}]}',
      ),
    ).toThrowError(/price_minor/)
  })

  it('currency 小写: 抛 Error 含 currency 关键词', () => {
    expect(() =>
      parseQuoteTable(
        '{"version":1,"ts":1,"quotes":[{"symbol":"A","price_minor":1,"currency":"usd","ts":1}]}',
      ),
    ).toThrowError(/currency/)
  })

  it('ts 小数: 抛 Error 含 ts 关键词', () => {
    expect(() =>
      parseQuoteTable(
        '{"version":1,"ts":1.5,"quotes":[{"symbol":"A","price_minor":1,"currency":"USD","ts":1}]}',
      ),
    ).toThrowError(/ts/)
  })

  it('quotes 空数组: 抛 Error 含 quotes 关键词', () => {
    expect(() =>
      parseQuoteTable('{"version":1,"ts":1,"quotes":[]}'),
    ).toThrowError(/quotes/)
  })

  it('version=2 异值: 抛 Error 含 version 关键词', () => {
    expect(() =>
      parseQuoteTable(
        '{"version":2,"ts":1,"quotes":[{"symbol":"A","price_minor":1,"currency":"USD","ts":1}]}',
      ),
    ).toThrowError(/version/)
  })

  it('ts 缺失: 抛 Error 含 ts 关键词', () => {
    expect(() =>
      parseQuoteTable(
        '{"version":1,"quotes":[{"symbol":"A","price_minor":1,"currency":"USD","ts":1}]}',
      ),
    ).toThrowError(/ts/)
  })

  it('根节点不是 JSON 对象: 抛 Error', () => {
    expect(() => parseQuoteTable('"oops"')).toThrowError(/JSON 对象/)
    expect(() => parseQuoteTable('[1]')).toThrowError(/JSON 对象/)
  })

  it('base 非 ISO 三字母大写: 抛 Error 含 base 关键词', () => {
    expect(() =>
      parseQuoteTable(
        '{"version":1,"ts":1,"base":"cny","quotes":[{"symbol":"A","price_minor":1,"currency":"USD","ts":1}]}',
      ),
    ).toThrowError(/base/)
  })
})

// ============================================================================
// 4) priceMinorOf 命中 / 缺价
// ============================================================================

describe('quoteTable / priceMinorOf', () => {
  const ts = 1
  const table = parseQuoteTable(
    JSON.stringify({
      version: 1,
      ts,
      quotes: [{ symbol: 'AAPL', price_minor: 18500, currency: 'USD', ts }],
    }),
  )

  it('命中 symbol: 返回 priceMinor (分)', () => {
    expect(priceMinorOf('AAPL', table)).toBe(18500)
  })

  it('缺价 symbol: 返回 null', () => {
    expect(priceMinorOf('OTHER', table)).toBeNull()
  })

  it('非法 symbol 长度（空 / 超 32）: 返回 null', () => {
    expect(priceMinorOf('', table)).toBeNull()
    expect(priceMinorOf('A'.repeat(33), table)).toBeNull()
  })
})

// ============================================================================
// 5) encodeQuoteTable ↔ parseQuoteTable 往返
// ============================================================================

describe('quoteTable / encodeQuoteTable', () => {
  it('encode → parse 往返一致: 字段逐个相等', () => {
    const original: QuoteTable = {
      ts: 1735689600000,
      quotes: new Map<string, Quote>([
        ['AAPL', { symbol: 'AAPL', priceMinor: 18500, currency: 'USD', ts: 1735689600000 }],
        ['0700.HK', { symbol: '0700.HK', priceMinor: 38000, currency: 'HKD', ts: 1735689600000 }],
      ]),
      base: 'CNY',
    }
    const encoded = encodeQuoteTable(original)
    expect(encoded).toContain('"version":1')
    expect(encoded).toContain('"base":"CNY"')
    const decoded = parseQuoteTable(encoded)
    expect(decoded.ts).toBe(original.ts)
    expect(decoded.base).toBe(original.base)
    expect(decoded.quotes.size).toBe(original.quotes.size)
    for (const [symbol, q] of original.quotes) {
      expect(decoded.quotes.get(symbol)).toEqual(q)
    }
  })

  it('base 为 null 时 encode 不输出 base 字段', () => {
    const encoded = encodeQuoteTable({
      ts: 1,
      quotes: new Map([['A', { symbol: 'A', priceMinor: 1, currency: 'USD', ts: 1 }]]),
    })
    expect(encoded).not.toContain('"base"')
  })
})