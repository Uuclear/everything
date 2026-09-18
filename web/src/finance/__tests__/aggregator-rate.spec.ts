// ============================================================================
// FinanceAggregator B5 多币种折算单元测试（stage5-finance-v2 / B5 / TR-5.2 / FR-V2-C.3）
// ============================================================================
//
// 验证目标（≥7 用例）：
//   1. fixture 字节级一致性 —— rate-table-cases.json 原始字节 SHA-256 必须等于
//      Android 真理源常量（node:crypto createHash 对 ?raw 原始 UTF-8 字节摘要）；
//   2. aggregatorCases 8 条全量数据驱动（快照 toEqual 严格比对，含
//      targetCurrency 键）；
//   3. monthlyReportCases 2 条全量驱动（带 currency 的 income / expense /
//      categoryBreakdown 逐笔折算；缺汇率面值降级）；
//   4. 零回归 —— 折算上下文未激活（无表且目标币默认 CNY）时快照与 v1 逐键一致，
//      不出现 targetCurrency 键；B4 面值口径金额不变；
//   5. targetCurrency 键的激活规则（显式目标币即便无表也标注，金额面值不折算）；
//   6. CardLike / TxLike 的 currency 缺省按 CNY（反向折算到 USD 实测）。
//
// 关联:
//   - web/src/finance/aggregator.ts（被测目标）
//   - web/src/finance/rateTable.ts（折算纯函数）
//   - web/src/finance/__fixtures__/rate-table-cases.json（双端共享 fixture）
//   - android/.../finance/FinanceAggregator.kt（Android 镜像真理源）
// ============================================================================

import { describe, it, expect } from 'vitest'
import { createHash } from 'node:crypto'
import {
  monthlyReport,
  netWorth,
  type AccountLike,
  type CardLike,
  type LoanLike,
  type TxLike,
} from '../aggregator'
import type { DashboardSnapshot, MonthlyReport } from '../types'
import { parseRateTable, type RateTable } from '../rateTable'
import rateTableRaw from '../__fixtures__/rate-table-cases.json?raw'
import rateTableJson from '../__fixtures__/rate-table-cases.json'

// ============================================================================
// 常量与 fixture 类型
// ============================================================================

/** fixture 真理源 SHA-256（与 Android 镜像必须逐字节一致）。 */
const EXPECTED_FIXTURE_SHA256 =
  '7a1c77af553595423a3d0a338421e00eb7c0f59ca7b906d9768faf3abb2aea12'

interface AggregatorRateCase {
  name: string
  targetCurrency: string
  table: string
  input: {
    accounts: AccountLike[]
    cards: CardLike[]
    txs: TxLike[]
    loans: LoanLike[]
  }
  expected: DashboardSnapshot
}

interface ReportRateCase {
  name: string
  yearMonth: string
  targetCurrency: string
  table: string
  input: { txs: TxLike[] }
  expected: MonthlyReport
}

interface RateTableFixture {
  tables: Record<string, { version?: number; effective_ts: number; rates: Record<string, number> }>
  aggregatorCases: AggregatorRateCase[]
  monthlyReportCases: ReportRateCase[]
}

const fixture = rateTableJson as unknown as RateTableFixture

/** fixture tables 段经 parseRateTable 入口解析。 */
const tables: Record<string, RateTable> = Object.fromEntries(
  Object.entries(fixture.tables).map(([name, pkg]) => [
    name,
    parseRateTable(JSON.stringify(pkg)),
  ]),
)

/** 2026-06 锚定时刻（fixture txs 使用, CST 口径与 Android 一致）。 */
const TS_2026_06 = 1782619200000

// ============================================================================
// 1. fixture 字节级一致性
// ============================================================================

describe('aggregator B5 / fixture 一致性', () => {
  it('fixture 原始字节 SHA-256 与 Android 真理源常量一致（node:crypto）', () => {
    const rawBytes = new TextEncoder().encode(rateTableRaw)
    const actual = createHash('sha256').update(rawBytes).digest('hex')
    expect(
      actual,
      'fixture 被改写或行尾变化（必须 LF、无 BOM）；请与 Android 镜像重新对齐',
    ).toBe(EXPECTED_FIXTURE_SHA256)
  })

  it('fixture 含不少于 7 条看板用例（实际 8 条）与至少 1 条月报用例', () => {
    expect(fixture.aggregatorCases.length).toBeGreaterThanOrEqual(7)
    expect(fixture.monthlyReportCases.length).toBeGreaterThanOrEqual(1)
  })
})

// ============================================================================
// 2. 看板折算 fixture 全量数据驱动（严格 toEqual，含 targetCurrency 键）
// ============================================================================

describe('netWorth B5 / fixture 数据驱动', () => {
  it('8 条用例快照整体匹配（v1 七字段 + 激活时 targetCurrency）', () => {
    for (const tc of fixture.aggregatorCases) {
      const actual = netWorth(
        tc.input.accounts,
        tc.input.cards,
        tc.input.txs,
        tc.input.loans,
        tc.targetCurrency,
        tables[tc.table],
      )
      expect(actual, `case[${tc.name}] 快照整体不一致`).toEqual(tc.expected)
    }
  })
})

// ============================================================================
// 3. 月报折算 fixture 全量数据驱动
// ============================================================================

describe('monthlyReport B5 / fixture 数据驱动', () => {
  it('2 条用例月报整体匹配（折算后 income / expense / net / 分类）', () => {
    for (const tc of fixture.monthlyReportCases) {
      const actual = monthlyReport(
        tc.yearMonth,
        tc.input.txs,
        [],
        [],
        tc.targetCurrency,
        tables[tc.table],
      )
      expect(actual, `case[${tc.name}] 月报整体不一致`).toEqual(tc.expected)
    }
  })
})

// ============================================================================
// 4. 关键场景具名断言
// ============================================================================

describe('netWorth B5 / 关键场景', () => {
  it('①USD 账户 100.00 ×7.25 → 725.00；currency 仍为 USD', () => {
    const tc = fixture.aggregatorCases.find(
      (c) => c.name === 'usd_singleAccount_100_convertsTo725Cny',
    )!
    const snap = netWorth(
      tc.input.accounts,
      [],
      [],
      [],
      tc.targetCurrency,
      tables[tc.table],
    )
    expect(snap.totalAssetValue).toBe('725.00')
    expect(snap.currency).toBe('USD')
    expect(snap.targetCurrency).toBe('CNY')
  })

  it('④GBP 缺汇率面值 1:1 降级，条目不丢失', () => {
    const tc = fixture.aggregatorCases.find(
      (c) => c.name === 'gbp_missingRate_faceValueFallback',
    )!
    const snap = netWorth(
      tc.input.accounts,
      [],
      [],
      [],
      tc.targetCurrency,
      tables[tc.table],
    )
    expect(snap.totalAssetValue).toBe('100.00')
    expect(snap.targetCurrency).toBe('CNY')
  })

  it('⑦USD 信用卡负债 + EUR lent 资产综合折算：资产 1285 / 负债 725 / 净 560', () => {
    const tc = fixture.aggregatorCases.find(
      (c) => c.name === 'usdCard_liability_and_eurLoan_asset_composite',
    )!
    const snap = netWorth(
      tc.input.accounts,
      tc.input.cards,
      tc.input.txs,
      tc.input.loans,
      tc.targetCurrency,
      tables[tc.table],
    )
    expect(snap).toEqual(tc.expected)
    expect(Object.keys(snap).sort()).toEqual(
      [
        'accountCount',
        'cardCount',
        'currency',
        'targetCurrency',
        'totalAssetValue',
        'totalAssets',
        'totalLiability',
        'txCount',
      ].sort(),
    )
  })
})

// ============================================================================
// 5. 零回归 —— 折算上下文未激活时 v1 七字段形态与面值口径
// ============================================================================

describe('netWorth B5 / 默认参数零回归', () => {
  it('不传 rateTable：外币面值累加、快照无 targetCurrency 键，与 B4 逐键一致', () => {
    const accounts: AccountLike[] = [
      { id: 'a1', balance: '100.00', currency: 'USD', archived: false },
    ]
    const cards: CardLike[] = [
      { id: 'c1', kind: 'credit', usedLimit: '100.00', archived: false },
    ]
    const loans: LoanLike[] = [
      {
        id: 'L1',
        direction: 'lent',
        principalMinor: '50.00',
        paidMinor: '0.00',
        includeInNetAssets: true,
        currency: 'USD',
        status: 'active',
      },
    ]

    const b4Style = netWorth(accounts, cards, [], loans)
    // 严格七字段，不允许出现 targetCurrency。
    expect(b4Style).toEqual({
      totalAssets: '50.00',
      totalAssetValue: '150.00',
      totalLiability: '100.00',
      accountCount: 1,
      cardCount: 1,
      txCount: 0,
      currency: 'USD',
    })
    expect(b4Style).not.toHaveProperty('targetCurrency')

    // 显式默认（CNY + null）与默认调用 deep equal。
    const explicitDefaults = netWorth(accounts, cards, [], loans, 'CNY', null)
    expect(explicitDefaults).toEqual(b4Style)

    // 两参 v1 形态。
    const twoArgs = netWorth(accounts, cards)
    expect(twoArgs.totalLiability).toBe('100.00')
    expect(twoArgs).not.toHaveProperty('targetCurrency')
  })

  it('显式目标币但无汇率表：标注 targetCurrency，金额仍按面值不折算', () => {
    const accounts: AccountLike[] = [
      { id: 'a1', balance: '100.00', currency: 'USD', archived: false },
    ]
    const snap = netWorth(accounts, [], [], [], 'USD', null)
    expect(snap.totalAssetValue).toBe('100.00')
    expect(snap.targetCurrency).toBe('USD')
    expect(Object.keys(snap)).toContain('targetCurrency')
  })
})

// ============================================================================
// 6. CardLike / TxLike currency 缺省按 CNY
// ============================================================================

describe('B5 / Like DTO currency 缺省口径', () => {
  it('CardLike 不带 currency 时按 CNY 反向折算到 USD：100.00 → 13.79', () => {
    const cards: CardLike[] = [
      { id: 'c1', kind: 'credit', usedLimit: '100.00', archived: false },
    ]
    const snap = netWorth([], cards, [], [], 'USD', tables.main)
    expect(snap.totalLiability).toBe('13.79')
    expect(snap.targetCurrency).toBe('USD')
  })

  it('TxLike 不带 currency 时月报按 CNY 反向折算到 USD：支出 100.00 → 13.79', () => {
    const txs: TxLike[] = [
      {
        id: 't1',
        accountId: 'a1',
        cardId: null,
        kind: 'expense',
        amount: '100.00',
        category: 'food',
        occurredAt: TS_2026_06,
        transferToAccountId: null,
      },
    ]
    const report = monthlyReport('2026-06', txs, [], [], 'USD', tables.main)
    expect(report.expense).toBe('13.79')
    expect(report.net).toBe('-13.79')
    expect(report.categoryBreakdown).toEqual({ food: '13.79' })
  })

  it('折算口径幂等：同一折算入参多次调用结果一致', () => {
    const tc = fixture.aggregatorCases.find(
      (c) => c.name === 'multiCurrency_cnyUsdEur_preciseSum2510',
    )!
    const run = () => netWorth(
      tc.input.accounts,
      tc.input.cards,
      tc.input.txs,
      tc.input.loans,
      tc.targetCurrency,
      tables[tc.table],
    )
    expect(run()).toEqual(run())
    expect(run().totalAssetValue).toBe('2510.00')
  })
})
