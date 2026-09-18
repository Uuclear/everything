// ============================================================================
// FinanceAggregator v2（loan 接入）单元测试（stage5-finance-v2 / Task 4 /
// TR-4.2 + TR-4.6）
// ============================================================================
//
// 验证目标（≥6 用例）：
//   1. fixture 字节级一致性 —— aggregator-v2-cases.json 的 SHA-256 必须等于
//      Android 真理源常量（TR-4.6 三端一致）；
//   2. fixture 数据驱动 —— 9 条 loan 用例全跑（lent / borrowed / 排除 /
//      部分还款 / 结清 / 混合 / 超额钳位 / 未知 direction）；
//   3. DashboardSnapshot 结构不变 —— 仍是 v1 七字段，不新增 loanCount；
//   4. 默认参数零回归 —— 三参调用与四参调用结果一致；
//   5. monthlyReport 不消费 loan 金额（借款本金非收支，Task5 预留）；
//   6. toLoanLike 适配器字段对齐 + 小数分精度 / 多笔求和等边界。
//
// 关联:
//   - web/src/finance/aggregator.ts（被测目标）
//   - web/src/finance/__fixtures__/aggregator-v2-cases.json（双端共享 fixture）
//   - android/.../finance/FinanceAggregator.kt（Android 镜像真理源）
// ============================================================================

import { describe, it, expect } from 'vitest'
import {
  monthlyReport,
  netWorth,
  toLoanLike,
  type AccountLike,
  type CardLike,
  type LoanLike,
  type TxLike,
} from '../aggregator'
import type { FinanceLoan } from '../types'
// ?raw 导入拿 fixture 原始字节做 SHA-256 核验；JSON 同名导入做数据驱动。
import aggregatorV2Raw from '../__fixtures__/aggregator-v2-cases.json?raw'
import aggregatorV2Json from '../__fixtures__/aggregator-v2-cases.json'

// ============================================================================
// 常量与 fixture 加载
// ============================================================================

/** fixture 真理源 SHA-256（与 Android 镜像必须逐字节一致，TR-4.6）。 */
const EXPECTED_FIXTURE_SHA256 =
  '9e5fd8761458df070b7cd6b9e362cdd4b9869cdfb4cba6b4fa6ab9a5b291db88'

/** CST 相对 UTC 的偏移毫秒（UTC+8）。 */
const CST_OFFSET_MS = 8 * 60 * 60 * 1000

/** 由 CST 墙钟分量构造 Unix 毫秒（month0 为 0 基月）。 */
function cstMs(year: number, month0: number, day: number, hour = 0, minute = 0): number {
  return Date.UTC(year, month0, day, hour, minute) - CST_OFFSET_MS
}

/** Web Crypto 计算 UTF-8 文本的 SHA-256 hex（Node 18+ 全局 crypto 可用）。 */
async function sha256Hex(text: string): Promise<string> {
  const digest = await crypto.subtle.digest(
    'SHA-256',
    new TextEncoder().encode(text),
  )
  return Array.from(new Uint8Array(digest), (b) => b.toString(16).padStart(2, '0')).join('')
}

interface AggregatorV2Case {
  name: string
  input: {
    accounts: AccountLike[]
    cards: CardLike[]
    loans: LoanLike[]
  }
  expected: {
    totalAssets: string
    totalAssetValue: string
    totalLiability: string
    accountCount: number
    cardCount: number
    txCount: number
    currency: string
  }
}

interface AggregatorV2Fixture {
  cases: AggregatorV2Case[]
}

const fixture = aggregatorV2Json as unknown as AggregatorV2Fixture

/** 构造借款 DTO 的极简工厂（字段顺序与 fixture 对齐）。 */
function loan(
  partial: Partial<LoanLike> & Pick<LoanLike, 'id' | 'direction'>,
): LoanLike {
  return {
    principalMinor: '0.00',
    paidMinor: '0.00',
    includeInNetAssets: true,
    currency: 'CNY',
    status: 'active',
    ...partial,
  }
}

// ============================================================================
// 1. fixture 字节级一致性（TR-4.6）
// ============================================================================

describe('aggregator v2 / fixture 一致性', () => {
  it('fixture SHA-256 与 Android 真理源常量逐字节一致', async () => {
    const actual = await sha256Hex(aggregatorV2Raw)
    expect(
      actual,
      'fixture 被改写或行尾变化（必须 LF、无 BOM）；请与 Android 镜像重新对齐',
    ).toBe(EXPECTED_FIXTURE_SHA256)
  })

  it('fixture 含 9 条 loan 用例', () => {
    expect(fixture.cases).toHaveLength(9)
  })
})

// ============================================================================
// 2. fixture 全量数据驱动
// ============================================================================

describe('netWorth v2 / fixture 数据驱动', () => {
  it('9 条用例逐条匹配 DashboardSnapshot 七字段', () => {
    for (const tc of fixture.cases) {
      const actual = netWorth(tc.input.accounts, tc.input.cards, [], tc.input.loans)
      expect(actual, `case[${tc.name}] 快照整体不一致`).toEqual(tc.expected)
    }
  })
})

// ============================================================================
// 3. DashboardSnapshot 结构不变（v1 七字段，无 loanCount）
// ============================================================================

describe('netWorth v2 / 快照结构', () => {
  it('仅有 v1 约定的 7 个键，不新增 loanCount，且计数不受 loan 影响', () => {
    const snap = netWorth(
      [],
      [],
      [],
      [
        loan({ id: 'L1', direction: 'lent', principalMinor: '100.00' }),
        loan({ id: 'L2', direction: 'borrowed', principalMinor: '200.00' }),
      ],
    )
    expect(Object.keys(snap).sort()).toEqual(
      [
        'accountCount',
        'cardCount',
        'currency',
        'totalAssetValue',
        'totalAssets',
        'totalLiability',
        'txCount',
      ].sort(),
    )
    expect(snap).not.toHaveProperty('loanCount')
    expect(snap.accountCount).toBe(0)
    expect(snap.cardCount).toBe(0)
    expect(snap.txCount).toBe(0)
  })
})

// ============================================================================
// 4. 默认参数零回归（v1 三参调用与 v2 四参调用等价）
// ============================================================================

describe('netWorth v2 / 默认参数兼容', () => {
  it('不传 loans 与传空 loans 结果完全一致', () => {
    const accounts: AccountLike[] = [
      { id: 'a1', balance: '200.00', currency: 'CNY', archived: false },
    ]
    const cards: CardLike[] = [
      { id: 'c1', kind: 'credit', usedLimit: '50.00', archived: false },
    ]
    // txs 在 netWorth 中本就不消费，仅占签名位。
    const txs: TxLike[] = [
      {
        id: 't1',
        accountId: 'a1',
        cardId: null,
        kind: 'expense',
        amount: '9.99',
        category: 'food',
        occurredAt: cstMs(2026, 5, 1),
        transferToAccountId: null,
      },
    ]
    const v1Style = netWorth(accounts, cards, txs)
    const v2Style = netWorth(accounts, cards, txs, [])
    expect(v2Style).toEqual(v1Style)
    expect(v1Style).toEqual({
      totalAssets: '150.00',
      totalAssetValue: '200.00',
      totalLiability: '50.00',
      accountCount: 1,
      cardCount: 1,
      txCount: 1,
      currency: 'CNY',
    })
  })
})

// ============================================================================
// 5. monthlyReport：借款本金非收支（TR-4.2，Task5 预留）
// ============================================================================

describe('monthlyReport v2 / loans 预留不参与收支', () => {
  it('传入任意 loans 不改变月报；纯 loans 空流水月报为全零', () => {
    const tx: TxLike = {
      id: 't1',
      accountId: 'a1',
      cardId: null,
      kind: 'expense',
      amount: '10.00',
      category: 'food',
      occurredAt: cstMs(2026, 5, 10, 10),
      transferToAccountId: null,
    }
    const loans: LoanLike[] = [
      loan({ id: 'L1', direction: 'lent', principalMinor: '999.00' }),
      loan({ id: 'L2', direction: 'borrowed', principalMinor: '888.00' }),
    ]

    const withoutLoans = monthlyReport('2026-06', [tx])
    const withLoans = monthlyReport('2026-06', [tx], [], loans)
    expect(withLoans).toEqual(withoutLoans)
    expect(withLoans).toEqual({
      yearMonth: '2026-06',
      income: '0.00',
      expense: '10.00',
      net: '-10.00',
      txCount: 1,
      categoryBreakdown: { food: '10.00' },
    })

    // 只有 loans、没有流水 —— 月报仍为全零，loan 金额不渗入任何字段。
    const loansOnly = monthlyReport('2026-06', [], [], loans)
    expect(loansOnly).toEqual({
      yearMonth: '2026-06',
      income: '0.00',
      expense: '0.00',
      net: '0.00',
      txCount: 0,
      categoryBreakdown: {},
    })
  })
})

// ============================================================================
// 6. toLoanLike 适配器
// ============================================================================

describe('aggregator toLoanLike 适配器', () => {
  it('snake_case 明文 → camelCase 聚合 DTO 七字段', () => {
    const raw = {
      id: 'L1',
      direction: 'lent',
      principal_minor: '1000.00',
      paid_minor: '300.00',
      include_in_net_assets: true,
      currency: 'CNY',
      status: 'partially_paid',
    } as unknown as FinanceLoan
    expect(toLoanLike(raw)).toEqual({
      id: 'L1',
      direction: 'lent',
      principalMinor: '1000.00',
      paidMinor: '300.00',
      includeInNetAssets: true,
      currency: 'CNY',
      status: 'partially_paid',
    })
  })
})

// ============================================================================
// 7. fixture 之外的金额边界
// ============================================================================

describe('netWorth v2 / 金额边界补充', () => {
  it('小数分精度：1000.50 减 0.25 剩余 1000.25；借入 0.25 计负债', () => {
    const snap = netWorth(
      [],
      [],
      [],
      [
        loan({ id: 'A', direction: 'lent', principalMinor: '1000.50', paidMinor: '0.25' }),
        loan({ id: 'B', direction: 'borrowed', principalMinor: '0.25' }),
      ],
    )
    expect(snap.totalAssetValue).toBe('1000.25')
    expect(snap.totalLiability).toBe('0.25')
    expect(snap.totalAssets).toBe('1000.00')
  })

  it('多笔同向求和；排除项与未知 direction 一律不影响金额', () => {
    const snap = netWorth(
      [],
      [],
      [],
      [
        loan({ id: 'A', direction: 'lent', principalMinor: '100.00' }),
        loan({ id: 'B', direction: 'lent', principalMinor: '200.00' }),
        loan({ id: 'C', direction: 'borrowed', principalMinor: '50.00' }),
        loan({
          id: 'D',
          direction: 'lent',
          principalMinor: '999.00',
          includeInNetAssets: false,
        }),
        loan({ id: 'E', direction: 'weird', principalMinor: '777.00' }),
      ],
    )
    expect(snap.totalAssetValue).toBe('300.00')
    expect(snap.totalLiability).toBe('50.00')
    expect(snap.totalAssets).toBe('250.00')
  })
})
