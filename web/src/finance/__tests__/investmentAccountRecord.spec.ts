// ============================================================================
// InvestmentAccountRecord 纯函数单元测试
// （stage5-finance-v2 / Task 8 / FR-V2-D.1）
// ============================================================================
//
// 路径: web/src/finance/__tests__/investmentAccountRecord.spec.ts
//
// 验证目标（5 用例, 与 Android InvestmentAccountRecordTest 1:1 镜像）：
//   1) parseHoldings 合法 JSON: 完整字段解析, 返回不可变列表;
//   2) parseHoldings 缺失 holdings 段: 返回空数组（合法空仓）;
//   3) parseHoldings 字段非法（symbol 空 / shares=0 / shares 负 / cost 负 /
//      currency 小写）: 抛 Error, message 含中文关键词;
//   4) encodeHoldings ↔ parseHoldings 往返一致;
//   5) build 工厂（kind 非法 / currency 非法 / id 空）: 抛 Error。
//
// 关联:
//   - web/src/finance/investmentAccountRecord.ts（被测目标）
//   - android/.../finance/InvestmentAccountRecordTest.kt（Android 镜像）
//   - web/src/finance/aggregator.ts#investmentMarketValue（聚合消费方）
// ============================================================================

import { describe, it, expect } from 'vitest'
import {
  build,
  encodeHoldings,
  KIND_STOCK,
  parseHoldings,
  type HoldingLike,
} from '../investmentAccountRecord'

// ============================================================================
// 1) parseHoldings 合法 JSON
// ============================================================================

describe('investmentAccountRecord / parseHoldings 合法入参', () => {
  it('完整字段: 多笔持仓按序解析为不可变 HoldingLike[]', () => {
    const json = JSON.stringify({
      holdings: [
        { symbol: 'AAPL', shares: 10, cost_basis_minor: 150000, currency: 'USD' },
        { symbol: '0700.HK', shares: 100, cost_basis_minor: 380000, currency: 'HKD' },
      ],
    })
    const holdings = parseHoldings(json)
    expect(holdings).toHaveLength(2)
    expect(holdings[0]).toEqual<HoldingLike>({
      symbol: 'AAPL',
      shares: 10,
      costBasisMinor: 150000,
      currency: 'USD',
    })
    expect(holdings[1]).toEqual<HoldingLike>({
      symbol: '0700.HK',
      shares: 100,
      costBasisMinor: 380000,
      currency: 'HKD',
    })
  })
})

// ============================================================================
// 2) parseHoldings 缺失 holdings 段: 空数组
// ============================================================================

describe('investmentAccountRecord / parseHoldings 边界', () => {
  it('holdings 字段缺失: 返回空数组（合法空仓）', () => {
    expect(parseHoldings('{}')).toEqual([])
    expect(parseHoldings('{"currency":"USD"}')).toEqual([])
  })

  it('holdings 为 null: 返回空数组（合法空仓）', () => {
    expect(parseHoldings('{"holdings":null}')).toEqual([])
  })
})

// ============================================================================
// 3) parseHoldings 字段非法: 抛 Error, message 含中文关键词
// ============================================================================

describe('investmentAccountRecord / parseHoldings 拒绝', () => {
  it('symbol 为空串: 抛 Error 含 symbol 关键词', () => {
    expect(() =>
      parseHoldings(
        '{"holdings":[{"symbol":"","shares":1,"cost_basis_minor":0,"currency":"USD"}]}',
      ),
    ).toThrowError(/symbol/)
  })

  it('shares 为 0: 抛 Error 含 shares 关键词', () => {
    expect(() =>
      parseHoldings(
        '{"holdings":[{"symbol":"A","shares":0,"cost_basis_minor":0,"currency":"USD"}]}',
      ),
    ).toThrowError(/shares/)
  })

  it('shares 为负数: 抛 Error 含 shares 关键词', () => {
    expect(() =>
      parseHoldings(
        '{"holdings":[{"symbol":"A","shares":-1,"cost_basis_minor":0,"currency":"USD"}]}',
      ),
    ).toThrowError(/shares/)
  })

  it('cost_basis_minor 为负数: 抛 Error 含 cost_basis_minor 关键词', () => {
    expect(() =>
      parseHoldings(
        '{"holdings":[{"symbol":"A","shares":1,"cost_basis_minor":-1,"currency":"USD"}]}',
      ),
    ).toThrowError(/cost_basis_minor/)
  })

  it('currency 小写: 抛 Error 含 currency 关键词', () => {
    expect(() =>
      parseHoldings(
        '{"holdings":[{"symbol":"A","shares":1,"cost_basis_minor":0,"currency":"usd"}]}',
      ),
    ).toThrowError(/currency/)
  })

  it('holdings 元素非对象: 抛 Error', () => {
    expect(() =>
      parseHoldings('{"holdings":[1]}'),
    ).toThrowError(/holdings\[0\]/)
  })

  it('holdings 段非数组: 抛 Error 含 holdings 关键词', () => {
    expect(() =>
      parseHoldings('{"holdings":"oops"}'),
    ).toThrowError(/holdings/)
  })
})

// ============================================================================
// 4) encodeHoldings ↔ parseHoldings 往返
// ============================================================================

describe('investmentAccountRecord / encodeHoldings', () => {
  it('encode → parse 往返一致: 字段逐个相等', () => {
    const original: HoldingLike[] = [
      { symbol: 'AAPL', shares: 10, costBasisMinor: 150000, currency: 'USD' },
      { symbol: '600519.SH', shares: 5, costBasisMinor: 850000, currency: 'CNY' },
    ]
    const encoded = encodeHoldings(original)
    // 编码 JSON 含 holdings 数组 + snake_case 字段。
    expect(encoded).toContain('"symbol":"AAPL"')
    expect(encoded).toContain('"cost_basis_minor":150000')
    const decoded = parseHoldings(encoded)
    expect(decoded).toEqual(original)
  })

  it('空仓: encode 仍输出 holdings=[] (不省略键)', () => {
    expect(encodeHoldings([])).toBe('{"holdings":[]}')
  })
})

// ============================================================================
// 5) build 工厂: 字段校验
// ============================================================================

describe('investmentAccountRecord / build 工厂', () => {
  it('合法入参构造 InvestmentAccountRecord (kind=currency=id 三检通过)', () => {
    const acc = build('acc-1', KIND_STOCK, 'USD', [], false)
    expect(acc).toEqual({
      id: 'acc-1',
      kind: 'stock',
      currency: 'USD',
      holdings: [],
      archived: false,
    })
  })

  it('kind 非 stock: 抛 Error 含 kind 关键词', () => {
    expect(() => build('acc-1', 'crypto', 'USD', [], false)).toThrowError(/kind/)
  })

  it('currency 非 ISO 三字母大写: 抛 Error 含 currency 关键词', () => {
    expect(() => build('acc-1', KIND_STOCK, 'us', [], false)).toThrowError(/currency/)
  })

  it('id 为空: 抛 Error 含 id 关键词', () => {
    expect(() => build('   ', KIND_STOCK, 'USD', [], false)).toThrowError(/id/)
  })
})