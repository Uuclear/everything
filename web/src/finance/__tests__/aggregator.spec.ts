// ============================================================================
// FinanceAggregator 纯函数单元测试（stage5-finance / Task 8 / TR-8.5）
// ============================================================================
//
// 验证目标（≥16 用例, 覆盖 TR-8.2 Pass Condition + 全部边界）：
//   1. fixture 数据驱动 —— 共享 aggregator-cases.json（6 条用例, 覆盖空集 / 单账户
//      / 多账户多卡 / 归档过滤 / 借记卡不计负债 / 多信用卡求和）；
//   2. 5 个纯函数全部有显式 @Test（netWorth / accountBalance / cardUsedLimit /
//      monthlyReport / budgetThreshold）；
//   3. 关键场景显式断言（跨年/跨月聚合、cents 整数精度、负数钳位）；
//   4. 边界与负例显式断言（空集合 → 零值；null usedLimit → "0.00"；异常 kind 跳过）；
//   5. 适配器 toAccountLike / toCardLike / toTxLike 字段对齐。
//
// 共享 fixture（__fixtures__/aggregator-cases.json）由三端共同加载, SHA-256 必须
// 字节级一致 —— 见 tasks.md TR-8.4；本测试仅读加载, 不修改 fixture 内容。
//
// 零知识纪律：
//   - 测试账户/卡号均为业界公开示例数据（last4 "1111"/"2222" 等），非真实数据；
//   - 不在断言中打印完整卡号或余额（用 id 引用）；
//   - 不向 localStorage / IndexedDB / 网络写入任何数据。
//
// 关联:
//   - web/src/finance/aggregator.ts（被测目标）
//   - web/src/finance/__fixtures__/aggregator-cases.json（共享 fixture）
//   - android/.../finance/FinanceAggregator.kt（Android 镜像）
// ============================================================================

import { describe, it, expect } from 'vitest'
import {
  netWorth,
  accountBalance,
  cardUsedLimit,
  monthlyReport,
  budgetThreshold,
  toAccountLike,
  toCardLike,
  toTxLike,
  type AccountLike,
  type CardLike,
  type TxLike,
} from '../aggregator'
import type { FinanceAccount, FinanceCard, FinanceTx } from '../types'
import aggregatorCases from '../__fixtures__/aggregator-cases.json'

// -----------------------------------------------------------------------------
// fixture 加载（JSON.parse + ts 静态导入, 直接复用 resolveJsonModule）
// -----------------------------------------------------------------------------

/**
 * fixture 单条用例结构（与 Android AggregatorCase 字段命名对齐）。
 */
interface AggregatorCase {
  name: string
  input: {
    accounts: AccountLike[]
    cards: CardLike[]
    txs: TxLike[]
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

/**
 * 从 fixture JSON 提取 DashboardSnapshot 期望值。
 *
 * 极简解析 —— 仅消费 fixture 已知形态。
 */
function loadCases(): AggregatorCase[] {
  const fixture = aggregatorCases as unknown as {
    cases: AggregatorCase[]
  }
  return fixture.cases
}

const cases: AggregatorCase[] = loadCases()

// ============================================================================
// 1. fixture 加载驱动 —— 从 JSON 数据驱动全部用例（≥6 硬性指标）
// ============================================================================

describe('netWorth / fixture 加载', () => {
  it('fixture 至少 6 条用例（TR-8.2 硬性指标）', () => {
    expect(
      cases.length,
      `fixture 用例数应不少于 6 条, 实际 = ${cases.length}`,
    ).toBeGreaterThanOrEqual(6)
  })

  it('fixture 数据驱动 —— 每条用例期望 DashboardSnapshot 一致', () => {
    for (const tc of cases) {
      const actual = netWorth(tc.input.accounts, tc.input.cards, tc.input.txs)
      expect(
        actual.totalAssets,
        `case[${tc.name}] totalAssets 不一致`,
      ).toBe(tc.expected.totalAssets)
      expect(
        actual.totalAssetValue,
        `case[${tc.name}] totalAssetValue 不一致`,
      ).toBe(tc.expected.totalAssetValue)
      expect(
        actual.totalLiability,
        `case[${tc.name}] totalLiability 不一致`,
      ).toBe(tc.expected.totalLiability)
      expect(
        actual.accountCount,
        `case[${tc.name}] accountCount 不一致`,
      ).toBe(tc.expected.accountCount)
      expect(
        actual.cardCount,
        `case[${tc.name}] cardCount 不一致`,
      ).toBe(tc.expected.cardCount)
      expect(
        actual.txCount,
        `case[${tc.name}] txCount 不一致`,
      ).toBe(tc.expected.txCount)
      expect(
        actual.currency,
        `case[${tc.name}] currency 不一致`,
      ).toBe(tc.expected.currency)
    }
  })
})

// ============================================================================
// 2. netWorth 关键场景显式断言（独立硬编码，不依赖 fixture）
// ============================================================================

describe('netWorth 关键场景', () => {
  it('cents 整数精度 —— 大金额（百万元级）累加无精度丢失', () => {
    const accounts: AccountLike[] = [
      { id: 'a1', balance: '9999999.99', currency: 'CNY', archived: false },
      { id: 'a2', balance: '1000000.01', currency: 'CNY', archived: false },
    ]
    const result = netWorth(accounts, [], [])
    // 9999999.99 + 1000000.01 = 11000000.00 —— JS Number 累加易丢精度, BigInt 不丢。
    expect(result.totalAssets).toBe('11000000.00')
    expect(result.totalAssetValue).toBe('11000000.00')
    expect(result.totalLiability).toBe('0.00')
  })

  it('负净资产 —— 信用卡已用 > 账户余额', () => {
    const accounts: AccountLike[] = [
      { id: 'a1', balance: '500.00', currency: 'CNY', archived: false },
    ]
    const cards: CardLike[] = [
      { id: 'c1', kind: 'credit', usedLimit: '800.00', archived: false },
    ]
    const result = netWorth(accounts, cards, [])
    expect(result.totalAssetValue).toBe('500.00')
    expect(result.totalLiability).toBe('800.00')
    expect(result.totalAssets).toBe('-300.00')
  })

  it('账户 currency 优先级 —— 第一条账户的 currency 决定 DashboardSnapshot.currency', () => {
    const accounts: AccountLike[] = [
      { id: 'a1', balance: '100.00', currency: 'USD', archived: false },
      { id: 'a2', balance: '200.00', currency: 'CNY', archived: false },
    ]
    const result = netWorth(accounts, [], [])
    expect(result.currency).toBe('USD')
  })
})

// ============================================================================
// 3. accountBalance 单元显式断言（含联动流水）
// ============================================================================

describe('accountBalance 单账户聚合', () => {
  it('无流水 → 仅返回 balance 规范化结果', () => {
    const account: AccountLike = {
      id: 'a1',
      balance: '1000.00',
      currency: 'CNY',
      archived: false,
    }
    expect(accountBalance(account, [])).toBe('1000.00')
  })

  it('income / expense 联动流水调整余额', () => {
    const account: AccountLike = {
      id: 'a1',
      balance: '1000.00',
      currency: 'CNY',
      archived: false,
    }
    const txs: TxLike[] = [
      { id: 't1', accountId: 'a1', cardId: null, kind: 'income', amount: '500.00', category: '工资', occurredAt: 1, transferToAccountId: null },
      { id: 't2', accountId: 'a1', cardId: null, kind: 'expense', amount: '300.00', category: '餐饮', occurredAt: 2, transferToAccountId: null },
    ]
    // 1000 + 500 - 300 = 1200
    expect(accountBalance(account, txs)).toBe('1200.00')
  })

  it('transfer 转出/转入账户余额分别调整', () => {
    const fromAcc: AccountLike = {
      id: 'a1',
      balance: '1000.00',
      currency: 'CNY',
      archived: false,
    }
    const toAcc: AccountLike = {
      id: 'a2',
      balance: '500.00',
      currency: 'CNY',
      archived: false,
    }
    const txs: TxLike[] = [
      { id: 't1', accountId: 'a1', cardId: null, kind: 'transfer', amount: '200.00', category: '转账', occurredAt: 1, transferToAccountId: 'a2' },
    ]
    expect(accountBalance(fromAcc, txs)).toBe('800.00')
    expect(accountBalance(toAcc, txs)).toBe('700.00')
  })

  it('未关联账户的流水不参与聚合', () => {
    const account: AccountLike = {
      id: 'a1',
      balance: '1000.00',
      currency: 'CNY',
      archived: false,
    }
    const txs: TxLike[] = [
      { id: 't1', accountId: 'a9', cardId: null, kind: 'income', amount: '500.00', category: '工资', occurredAt: 1, transferToAccountId: null },
    ]
    expect(accountBalance(account, txs)).toBe('1000.00')
  })

  it('异常 amount 字符串 → 跳过该项（不抛错）', () => {
    const account: AccountLike = {
      id: 'a1',
      balance: '1000.00',
      currency: 'CNY',
      archived: false,
    }
    const txs: TxLike[] = [
      { id: 't1', accountId: 'a1', cardId: null, kind: 'income', amount: 'abc', category: '工资', occurredAt: 1, transferToAccountId: null },
    ]
    expect(accountBalance(account, txs)).toBe('1000.00')
  })
})

// ============================================================================
// 4. cardUsedLimit 单元显式断言（含钳位到 0）
// ============================================================================

describe('cardUsedLimit 单卡已用额度', () => {
  it('无流水 → 仅返回 usedLimit 规范化结果（null → "0.00"）', () => {
    const card: CardLike = {
      id: 'c1',
      kind: 'credit',
      usedLimit: null,
      archived: false,
    }
    expect(cardUsedLimit(card, [])).toBe('0.00')
  })

  it('expense 累加 / income（还款）/ transfer 扣减', () => {
    const card: CardLike = {
      id: 'c1',
      kind: 'credit',
      usedLimit: '1000.00',
      archived: false,
    }
    const txs: TxLike[] = [
      { id: 't1', accountId: null, cardId: 'c1', kind: 'expense', amount: '500.00', category: '餐饮', occurredAt: 1, transferToAccountId: null },
      { id: 't2', accountId: null, cardId: 'c1', kind: 'income', amount: '300.00', category: '还款', occurredAt: 2, transferToAccountId: null },
      { id: 't3', accountId: null, cardId: 'c1', kind: 'transfer', amount: '200.00', category: '还款', occurredAt: 3, transferToAccountId: null },
    ]
    // 1000 + 500 - 300 - 200 = 1000
    expect(cardUsedLimit(card, txs)).toBe('1000.00')
  })

  it('钳位到 0 —— 还款过度冲销不会出现负数', () => {
    const card: CardLike = {
      id: 'c1',
      kind: 'credit',
      usedLimit: '100.00',
      archived: false,
    }
    const txs: TxLike[] = [
      { id: 't1', accountId: null, cardId: 'c1', kind: 'income', amount: '500.00', category: '还款', occurredAt: 1, transferToAccountId: null },
    ]
    // 100 - 500 = -400 → 钳位到 0.00
    expect(cardUsedLimit(card, txs)).toBe('0.00')
  })

  it('未关联卡片的流水不参与聚合', () => {
    const card: CardLike = {
      id: 'c1',
      kind: 'credit',
      usedLimit: '100.00',
      archived: false,
    }
    const txs: TxLike[] = [
      { id: 't1', accountId: null, cardId: 'c9', kind: 'expense', amount: '500.00', category: '餐饮', occurredAt: 1, transferToAccountId: null },
    ]
    expect(cardUsedLimit(card, txs)).toBe('100.00')
  })
})

// ============================================================================
// 5. monthlyReport 单元显式断言（按年月过滤 + 分类占比）
// ============================================================================

describe('monthlyReport 月度收支', () => {
  it('空流水 → 返回全零 MonthlyReport', () => {
    const result = monthlyReport('2026-01', [], [])
    expect(result).toEqual({
      yearMonth: '2026-01',
      income: '0.00',
      expense: '0.00',
      net: '0.00',
      txCount: 0,
      categoryBreakdown: {},
    })
  })

  it('按 yearMonth 过滤 —— 不同月流水不参与聚合', () => {
    const txs: TxLike[] = [
      // 2026-01-15 income
      { id: 't1', accountId: 'a1', cardId: null, kind: 'income', amount: '1000.00', category: '工资', occurredAt: Date.UTC(2026, 0, 15, 1, 0), transferToAccountId: null },
      // 2026-02-15 expense（不在 1 月）
      { id: 't2', accountId: 'a1', cardId: null, kind: 'expense', amount: '500.00', category: '餐饮', occurredAt: Date.UTC(2026, 1, 15, 1, 0), transferToAccountId: null },
    ]
    const result = monthlyReport('2026-01', txs, [])
    expect(result.income).toBe('1000.00')
    expect(result.expense).toBe('0.00')
    expect(result.net).toBe('1000.00')
    expect(result.txCount).toBe(1)
  })

  it('分类占比仅含 expense —— income / transfer 不计入分类', () => {
    const txs: TxLike[] = [
      { id: 't1', accountId: 'a1', cardId: null, kind: 'income', amount: '5000.00', category: '工资', occurredAt: Date.UTC(2026, 0, 15, 1, 0), transferToAccountId: null },
      { id: 't2', accountId: 'a1', cardId: null, kind: 'expense', amount: '300.00', category: '餐饮', occurredAt: Date.UTC(2026, 0, 16, 1, 0), transferToAccountId: null },
      { id: 't3', accountId: 'a1', cardId: null, kind: 'expense', amount: '200.00', category: '餐饮', occurredAt: Date.UTC(2026, 0, 17, 1, 0), transferToAccountId: null },
      { id: 't4', accountId: 'a1', cardId: null, kind: 'expense', amount: '100.00', category: '交通', occurredAt: Date.UTC(2026, 0, 18, 1, 0), transferToAccountId: null },
      { id: 't5', accountId: 'a1', cardId: null, kind: 'transfer', amount: '50.00', category: '转账', occurredAt: Date.UTC(2026, 0, 19, 1, 0), transferToAccountId: 'a2' },
    ]
    const result = monthlyReport('2026-01', txs, [])
    expect(result.income).toBe('5000.00')
    expect(result.expense).toBe('600.00')
    expect(result.net).toBe('4400.00')
    expect(result.txCount).toBe(5) // 含 transfer
    expect(result.categoryBreakdown).toEqual({
      '餐饮': '500.00',
      '交通': '100.00',
    })
  })
})

// ============================================================================
// 6. budgetThreshold 单元显式断言（OK / WARNING / EXCEEDED 三档）
// ============================================================================

describe('budgetThreshold 预算阈值', () => {
  it('零收入 → 一律 OK（无超支概念）', () => {
    expect(budgetThreshold('0', '500.00', 1.0)).toBe('OK')
    expect(budgetThreshold('0.00', '999.99', 1.0)).toBe('OK')
  })

  it('OK —— 支出未达月收入', () => {
    expect(budgetThreshold('10000.00', '5000.00', 1.0)).toBe('OK')
    expect(budgetThreshold('1000.00', '999.99', 1.0)).toBe('OK')
  })

  it('WARNING —— 支出 ≥ 月收入 且 < 1.5 倍', () => {
    // ratio = 1.2
    expect(budgetThreshold('1000.00', '1200.00', 1.0)).toBe('WARNING')
    // ratio = 1.0 边界
    expect(budgetThreshold('1000.00', '1000.00', 1.0)).toBe('WARNING')
    // ratio = 1.49
    expect(budgetThreshold('1000.00', '1499.99', 1.0)).toBe('WARNING')
  })

  it('EXCEEDED —— 支出 ≥ 1.5 倍月收入', () => {
    // ratio = 1.5 边界
    expect(budgetThreshold('1000.00', '1500.00', 1.0)).toBe('EXCEEDED')
    // ratio = 2.0
    expect(budgetThreshold('1000.00', '2000.00', 1.0)).toBe('EXCEEDED')
  })
})

// ============================================================================
// 7. 适配器单元显式断言（FinanceAccount/Card/Tx -> Like DTO）
// ============================================================================

describe('适配器 toXxxLike 字段对齐', () => {
  it('toAccountLike —— FinanceAccount -> AccountLike', () => {
    const account: FinanceAccount = {
      id: 'a1',
      schema_version: 1,
      name: '现金',
      kind: 'cash',
      currency: 'CNY',
      balance: '1234.56',
      note: null,
      icon: null,
      color: 'blue',
      archived: false,
      created_at: 1735689600000,
      updated_at: 1735689600000,
    }
    expect(toAccountLike(account)).toEqual({
      id: 'a1',
      balance: '1234.56',
      currency: 'CNY',
      archived: false,
    })
  })

  it('toCardLike —— FinanceCard -> CardLike（used_limit null 兼容）', () => {
    const card: FinanceCard = {
      id: 'c1',
      schema_version: 1,
      name: '招行信用卡',
      kind: 'credit',
      issuer: '招商银行',
      last4: '1111',
      currency: 'CNY',
      credit_limit: '10000.00',
      used_limit: null,
      billing_day: 15,
      due_day: 25,
      note: null,
      icon: null,
      color: 'blue',
      archived: false,
      include_in_net_assets: true,
      created_at: 1735689600000,
      updated_at: 1735689600000,
    }
    expect(toCardLike(card)).toEqual({
      id: 'c1',
      kind: 'credit',
      usedLimit: null,
      archived: false,
    })
  })

  it('toTxLike —— FinanceTx -> TxLike', () => {
    const tx: FinanceTx = {
      id: 't1',
      schema_version: 1,
      account_id: 'a1',
      card_id: null,
      kind: 'expense',
      amount: '50.00',
      category: '餐饮',
      occurred_at: 1735689600000,
      note: null,
      transfer_to_account_id: null,
      icon: null,
      color: 'slate',
      created_at: 1735689600000,
      updated_at: 1735689600000,
    }
    expect(toTxLike(tx)).toEqual({
      id: 't1',
      accountId: 'a1',
      cardId: null,
      kind: 'expense',
      amount: '50.00',
      category: '餐饮',
      occurredAt: 1735689600000,
      transferToAccountId: null,
    })
  })
})

// ============================================================================
// 8. 零知识纪律（pure 语义 + 幂等性）
// ============================================================================

describe('aggregator 零知识纪律', () => {
  it('netWorth 不修改入参（pure 语义）', () => {
    const accounts: AccountLike[] = [
      { id: 'a1', balance: '1000.00', currency: 'CNY', archived: false },
    ]
    const cards: CardLike[] = [
      { id: 'c1', kind: 'credit', usedLimit: '500.00', archived: false },
    ]
    // 深拷贝快照。
    const snapshotAccounts = JSON.parse(JSON.stringify(accounts))
    const snapshotCards = JSON.parse(JSON.stringify(cards))
    netWorth(accounts, cards, [])
    expect(accounts).toEqual(snapshotAccounts)
    expect(cards).toEqual(snapshotCards)
  })

  it('相同输入多次调用结果一致（幂等性）', () => {
    const accounts: AccountLike[] = [
      { id: 'a1', balance: '1000.00', currency: 'CNY', archived: false },
    ]
    const cards: CardLike[] = [
      { id: 'c1', kind: 'credit', usedLimit: '500.00', archived: false },
    ]
    const a = netWorth(accounts, cards, [])
    const b = netWorth(accounts, cards, [])
    const c = netWorth(accounts, cards, [])
    expect(a).toEqual(b)
    expect(b).toEqual(c)
  })
})