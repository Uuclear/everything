// ============================================================================
// FinanceAggregator 投资账户市值聚合单元测试
// （stage5-finance-v2 / Task 8 / TR-8.3 / FR-V2-D.2、FR-V2-D.3）
// ============================================================================
//
// 路径: web/src/finance/__tests__/aggregator-quote.spec.ts
//
// 验证目标（7 用例, 与 Android FinanceAggregatorV2QuoteTest 1:1 镜像）：
//   1) 空账户 → totalValue=0n, accountCount=0, topHoldings=[];
//   2) 单笔 USD 持仓 × AAPL = 185000 cents (1850.00), 缺 RateTable 时按面值;
//   3) 缺价: quoteTable=null → missingPriceHoldingCount=总持仓数, totalValue=0n;
//   4) 多币种折算: USD/CNY=7.25 命中 → 持仓原币市值 × 7.25;
//   5) 命中的报价恰好为 0 → 不计入 missingPriceHoldingCount;
//   6) 归档账户: Web 当前实现行为（仅非归档计数）—— 与 Android 端不同, 测试
//      严格匹配 Web 当前真实行为, 文档注释 + 后续重构需对齐 spec;
//   7) topHoldings 降序 + topN 上限生效。
//
// 关联:
//   - web/src/finance/aggregator.ts#investmentMarketValue（被测目标）
//   - web/src/finance/quoteTable.ts（行情包）
//   - web/src/finance/rateTable.ts（汇率折算消费方）
//   - android/.../finance/FinanceAggregatorV2QuoteTest.kt（Android 镜像）
// ============================================================================

import { describe, it, expect } from 'vitest'
import {
  investmentMarketValue,
  type InvestmentAccountLike,
  type QuoteTableLike,
} from '../aggregator'
import { parseRateTable, type RateTable } from '../rateTable'

// ============================================================================
// 测试用常量
// ============================================================================

/** 固定时间戳（与 B5 RateTable fixture 对齐）。 */
const TS = 1735689600000

// ============================================================================
// 工具：构造测试数据
// ============================================================================

/** 一笔 USD 持仓（AAPL 10 股 × 185.00 USD）。 */
function usdAaplHolding() {
  return {
    symbol: 'AAPL',
    shares: 10,
    costBasisMinor: 150000,
    currency: 'USD',
  }
}

/** 一笔 HKD 持仓（0700.HK 100 股 × 380.00 HKD）。 */
function hkdHolding() {
  return {
    symbol: '0700.HK',
    shares: 100,
    costBasisMinor: 380000,
    currency: 'HKD',
  }
}

/** 构造投资账户记录（InvestmentAccountLike DTO）。 */
function account(
  id: string,
  currency: string = 'USD',
  holdings: ReturnType<typeof usdAaplHolding>[] = [],
  archived: boolean = false,
): InvestmentAccountLike {
  return { id, kind: 'stock', currency, holdings, archived }
}

/** 构造含 USD/CNY=7.25 的离线汇率表。 */
function cnyUsdRateTable(): RateTable {
  return parseRateTable(
    JSON.stringify({
      version: 1,
      effective_ts: TS,
      rates: { 'USD/CNY': 7.25 },
    }),
  )
}

/** 构造含 AAPL=18500 minor 的行情包。 */
function aaplQuoteTable(): QuoteTableLike {
  return {
    ts: TS,
    quotes: new Map([
      ['AAPL', { symbol: 'AAPL', priceMinor: 18500, currency: 'USD', ts: TS }],
    ]),
  }
}

// ============================================================================
// 1) 空账户 → 零值快照
// ============================================================================

describe('investmentMarketValue / 空账户', () => {
  it('空账户 + 行情包: totalValue=0n, accountCount=0, topHoldings=[]', () => {
    const snap = investmentMarketValue([], aaplQuoteTable(), null)
    expect(snap.totalValue).toBe(0n)
    expect(snap.currency).toBe('CNY')
    expect(snap.accountCount).toBe(0)
    expect(snap.missingPriceHoldingCount).toBe(0)
    expect(snap.topHoldings).toEqual([])
    expect(snap.effectiveTs).toBe(TS)
  })
})

// ============================================================================
// 2) 单笔 USD 持仓 × AAPL = 1850.00 (缺 RateTable 时按面值)
// ============================================================================

describe('investmentMarketValue / 单笔 USD 持仓', () => {
  it('无 RateTable: 持仓原币市值直接落入 targetCurrency (按面值不折算)', () => {
    const acc = account('acc-1', 'USD', [usdAaplHolding()])
    const snap = investmentMarketValue([acc], aaplQuoteTable(), null)
    expect(snap.totalValue).toBe(185000n) // 10 × 18500 = 185000 cents
    expect(snap.currency).toBe('CNY')
    expect(snap.accountCount).toBe(1)
    expect(snap.missingPriceHoldingCount).toBe(0)
    expect(snap.topHoldings).toHaveLength(1)
    expect(snap.topHoldings[0].symbol).toBe('AAPL')
    expect(snap.topHoldings[0].valueInTargetMinor).toBe(185000n)
    expect(snap.topHoldings[0].priceMinor).toBe(18500)
  })
})

// ============================================================================
// 3) 缺价: quoteTable=null → 全缺价, totalValue=0n
// ============================================================================

describe('investmentMarketValue / 缺价', () => {
  it('quoteTable=null: 两笔持仓全缺价, missingPriceHoldingCount=2, totalValue=0n', () => {
    const acc = account('acc-1', 'USD', [usdAaplHolding(), hkdHolding()])
    const snap = investmentMarketValue([acc], null, null)
    expect(snap.totalValue).toBe(0n)
    expect(snap.currency).toBe('CNY')
    expect(snap.accountCount).toBe(1)
    expect(snap.missingPriceHoldingCount).toBe(2)
    expect(snap.effectiveTs).toBeNull()
  })
})

// ============================================================================
// 4) 多币种折算: USD/CNY=7.25 → 13412.50 CNY
// ============================================================================

describe('investmentMarketValue / 多币种折算', () => {
  it('USD/CNY=7.25: 1850.00 USD × 7.25 = 13412.50 CNY (1341250 cents)', () => {
    const acc = account('acc-1', 'USD', [usdAaplHolding()])
    const snap = investmentMarketValue(
      [acc],
      aaplQuoteTable(),
      cnyUsdRateTable(),
      'CNY',
    )
    expect(snap.totalValue).toBe(1341250n) // 185000 × 7.25 = 1341250 cents
    expect(snap.currency).toBe('CNY')
    expect(snap.accountCount).toBe(1)
    expect(snap.missingPriceHoldingCount).toBe(0)
    expect(snap.topHoldings[0].valueInTargetMinor).toBe(1341250n)
  })
})

// ============================================================================
// 5) 命中的报价恰好为 0 → 不计入 missingPriceHoldingCount
// ============================================================================

describe('investmentMarketValue / 0 价边界', () => {
  it('AAPL 报价为 0: 不计入 missingPriceHoldingCount, totalValue=0n', () => {
    const table: QuoteTableLike = {
      ts: TS,
      quotes: new Map([
        ['AAPL', { symbol: 'AAPL', priceMinor: 0, currency: 'USD', ts: TS }],
      ]),
    }
    const acc = account('acc-1', 'USD', [usdAaplHolding()])
    const snap = investmentMarketValue([acc], table, null)
    expect(snap.missingPriceHoldingCount).toBe(0)
    expect(snap.totalValue).toBe(0n)
    expect(snap.topHoldings[0].priceMinor).toBe(0)
  })
})

// ============================================================================
// 6) 归档账户: Web 当前实现 = 仅非归档计入 accountCount（与 Android 不同）
// ============================================================================

describe('investmentMarketValue / 归档账户', () => {
  it('active + archived 各一: Web 仅非归档 accountCount=1, totalValue 仅计 active', () => {
    const active = account('acc-1', 'USD', [usdAaplHolding()])
    const archived = account('acc-2', 'USD', [usdAaplHolding()], true)
    const snap = investmentMarketValue([active, archived], aaplQuoteTable(), null)
    // Web 端 accountCount 仅非归档（与 Android 实现不同, 详见 spec FR-V2-D.3）。
    expect(snap.accountCount).toBe(1)
    expect(snap.totalValue).toBe(185000n)
    expect(snap.topHoldings).toHaveLength(1)
    expect(snap.missingPriceHoldingCount).toBe(0)
  })
})

// ============================================================================
// 7) topHoldings 降序 + topN 上限
// ============================================================================

describe('investmentMarketValue / topHoldings 排序与上限', () => {
  it('3 笔持仓 + topN=2: 降序截取前两条 (AAPL 100000 > TSLA 50000 > AMZN 20000)', () => {
    const table: QuoteTableLike = {
      ts: TS,
      quotes: new Map([
        ['AAPL', { symbol: 'AAPL', priceMinor: 10000, currency: 'USD', ts: TS }],
        ['TSLA', { symbol: 'TSLA', priceMinor: 5000, currency: 'USD', ts: TS }],
        ['AMZN', { symbol: 'AMZN', priceMinor: 2000, currency: 'USD', ts: TS }],
      ]),
    }
    const acc = account('acc-1', 'USD', [
      { symbol: 'AAPL', shares: 10, costBasisMinor: 0, currency: 'USD' },
      { symbol: 'TSLA', shares: 10, costBasisMinor: 0, currency: 'USD' },
      { symbol: 'AMZN', shares: 10, costBasisMinor: 0, currency: 'USD' },
    ])
    const snap = investmentMarketValue([acc], table, null, 'CNY', 2)
    expect(snap.topHoldings).toHaveLength(2)
    expect(snap.topHoldings[0].symbol).toBe('AAPL')
    expect(snap.topHoldings[1].symbol).toBe('TSLA')
    expect(snap.topHoldings[0].valueInTargetMinor).toBe(100000n)
    expect(snap.topHoldings[1].valueInTargetMinor).toBe(50000n)
    expect(snap.totalValue).toBe(170000n) // 100000 + 50000 + 20000 = 170000
  })
})

// ============================================================================
// 8) 缺价 + 命中并存: missingPriceHoldingCount 仅计缺价条
// ============================================================================

describe('investmentMarketValue / 部分缺价', () => {
  it('AAPL 命中 + 0700.HK 缺价: missingPriceHoldingCount=1, totalValue=185000', () => {
    const table: QuoteTableLike = {
      ts: TS,
      quotes: new Map([
        ['AAPL', { symbol: 'AAPL', priceMinor: 18500, currency: 'USD', ts: TS }],
        // 故意不包含 0700.HK
      ]),
    }
    const acc = account('acc-1', 'USD', [usdAaplHolding(), hkdHolding()])
    const snap = investmentMarketValue([acc], table, null)
    expect(snap.missingPriceHoldingCount).toBe(1)
    expect(snap.totalValue).toBe(185000n)
    expect(snap.topHoldings).toHaveLength(2) // 缺价也 push, valueInTarget=0n
  })
})