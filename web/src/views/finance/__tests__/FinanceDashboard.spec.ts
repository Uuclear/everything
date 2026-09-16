// ============================================================================
// FinanceDashboard 单元测试（stage5-finance / Task 9 / TR-9.8a）
// ============================================================================
//
// 验证目标（≥4 用例，覆盖 Dashboard 核心业务）：
//   1. 净资产聚合 —— 总资产 - 总负债 = 净资产；
//   2. 月支出 —— 当月 expense 流水合计；
//   3. 预算进度 —— budgetThreshold 三档判定（OK / WARNING / EXCEEDED）；
//   4. 空态 —— 全空数据时不抛错，返回零值 DashboardSnapshot。
//
// 测试策略：
//   - node 环境直接调 store + aggregator（无需 jsdom）；
//   - 通过 store 注入内存 StorageChannel（与 store.spec.ts 同款），保证
//     hydrate 与持久化测试可控；
//   - 调 aggregator 纯函数做断言（净资产 / 月支出 / 预算阈值）。
//
// 关联:
//   - web/src/views/finance/FinanceDashboard.vue（被测目标）
//   - web/src/stores/finance.ts（数据源）
//   - web/src/finance/aggregator.ts（聚合纯函数）
// ============================================================================

import { describe, it, expect, beforeEach } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useFinanceStore, type StorageChannel, type PersistedFinanceState } from '../../../stores/finance'
import {
  netWorth,
  monthlyReport,
  budgetThreshold,
  toAccountLike,
  toCardLike,
  toTxLike,
} from '../../../finance/aggregator'
import type {
  FinanceAccount,
  FinanceCard,
  FinanceTx,
} from '../../../finance/types'
import {
  DEFAULT_ACCOUNT_COLOR,
  DEFAULT_CARD_COLOR,
  DEFAULT_TX_COLOR,
} from '../../../finance/types'

// -----------------------------------------------------------------------------
// 测试工具 —— 内存 StorageChannel + fixture
// -----------------------------------------------------------------------------

function memoryChannel(): StorageChannel {
  let state: PersistedFinanceState | null = null
  return {
    read() {
      return state == null ? null : JSON.parse(JSON.stringify(state))
    },
    write(next) {
      state = JSON.parse(JSON.stringify(next))
    },
    clear() {
      state = null
    },
  }
}

function makeAccount(over: Partial<FinanceAccount> = {}): FinanceAccount {
  return {
    id: 'a' + Math.random().toString(36).slice(2, 8),
    schema_version: 1,
    name: '现金钱包',
    kind: 'cash',
    currency: 'CNY',
    balance: '0.00',
    note: null,
    icon: null,
    color: DEFAULT_ACCOUNT_COLOR,
    archived: false,
    created_at: 1735689600000,
    updated_at: 1735689600000,
    ...over,
  }
}
function makeCard(over: Partial<FinanceCard> = {}): FinanceCard {
  return {
    id: 'c' + Math.random().toString(36).slice(2, 8),
    schema_version: 1,
    name: '招行信用卡',
    kind: 'credit',
    issuer: '招商银行',
    last4: '1111',
    currency: 'CNY',
    credit_limit: '10000.00',
    used_limit: '0.00',
    billing_day: 15,
    due_day: 25,
    note: null,
    icon: null,
    color: DEFAULT_CARD_COLOR,
    archived: false,
    include_in_net_assets: true,
    created_at: 1735689600000,
    updated_at: 1735689600000,
    ...over,
  }
}
function makeTx(over: Partial<FinanceTx> = {}): FinanceTx {
  return {
    id: 't' + Math.random().toString(36).slice(2, 8),
    schema_version: 1,
    account_id: null,
    card_id: null,
    kind: 'expense',
    amount: '0.00',
    category: '餐饮',
    occurred_at: 1735689600000,
    note: null,
    transfer_to_account_id: null,
    icon: null,
    color: DEFAULT_TX_COLOR,
    created_at: 1735689600000,
    updated_at: 1735689600000,
    ...over,
  }
}

beforeEach(() => {
  setActivePinia(createPinia())
  const store = useFinanceStore()
  store._setStorageForTest(memoryChannel())
  // T11 集成后 CRUD 内部 fire-and-forget pushChanges 会触发 defaultCryptoChannel
  // （依赖 useAuthStore → localStorage），注入 noop channel 隔离加密链路。
  store._setChannelForTest({
    seal: () => '',
    open: () => ({}) as never,
    push: async () => ({ applied: 1, skipped: 0, server_time: Date.now() }),
    list: async () => ({ records: [], has_more: false }),
  })
})

// -----------------------------------------------------------------------------
// 1. 净资产聚合
// -----------------------------------------------------------------------------

describe('FinanceDashboard / 净资产聚合', () => {
  it('总资产 = 非归档账户余额之和', () => {
    const store = useFinanceStore()
    store.addAccount(makeAccount({ id: 'a1', balance: '5000.00' }))
    store.addAccount(makeAccount({ id: 'a2', balance: '3000.00' }))
    store.addAccount(makeAccount({ id: 'a3', balance: '1000.00', archived: true }))

    // Dashboard 数据源 = store.listAccounts（非归档 + 未删除）, 故 accountCount
    // 反映"看板可见账户数", 不含已归档条目 —— 与 listAccounts 输出口径一致。
    const acc = store.listAccounts.map(toAccountLike)
    const cards = store.listCards.map(toCardLike)
    const txs = store.listTxs.map(toTxLike)
    const snap = netWorth(acc, cards, txs)

    // 归档账户 a3 不计入 → 5000 + 3000 = 8000。
    expect(snap.totalAssetValue).toBe('8000.00')
    expect(snap.totalLiability).toBe('0.00')
    expect(snap.totalAssets).toBe('8000.00')
    // a3 已归档, 不在 listAccounts 中 → accountCount = 2。
    expect(snap.accountCount).toBe(2)
  })

  it('净资产 = 总资产 - 总负债(信用卡已用额度)', () => {
    const store = useFinanceStore()
    store.addAccount(makeAccount({ id: 'a1', balance: '10000.00' }))
    store.addCard(makeCard({ id: 'c1', kind: 'credit', used_limit: '3000.00' }))
    store.addCard(makeCard({ id: 'c2', kind: 'credit', used_limit: '1500.00' }))

    const acc = store.listAccounts.map(toAccountLike)
    const cards = store.listCards.map(toCardLike)
    const txs = store.listTxs.map(toTxLike)
    const snap = netWorth(acc, cards, txs)

    // 总资产 10000;总负债 3000 + 1500 = 4500;净资产 = 5500。
    expect(snap.totalAssetValue).toBe('10000.00')
    expect(snap.totalLiability).toBe('4500.00')
    expect(snap.totalAssets).toBe('5500.00')
    expect(snap.accountCount).toBe(1) // 仅 a1。
    expect(snap.cardCount).toBe(2)
  })
})

// -----------------------------------------------------------------------------
// 2. 月支出 —— 当月 expense 流水合计
// -----------------------------------------------------------------------------

describe('FinanceDashboard / 月支出', () => {
  it('当月 expense 流水合计正确', () => {
    const store = useFinanceStore()
    const now = Date.now()
    // 取当月 1 日 12:00 本地。
    const TZ_OFFSET_MIN = -new Date(1780000000000).getTimezoneOffset()
    const localYmd = (() => {
      const shifted = now + TZ_OFFSET_MIN * 60_000
      const d = new Date(shifted)
      return {
        y: d.getUTCFullYear(),
        m: d.getUTCMonth() + 1,
        d: d.getUTCDate(),
      }
    })()
    const localMs =
      Date.UTC(localYmd.y, localYmd.m - 1, localYmd.d, 12, 0, 0, 0) -
      TZ_OFFSET_MIN * 60_000

    store.addTx(makeTx({ id: 't1', kind: 'expense', amount: '120.50', occurred_at: localMs }))
    store.addTx(makeTx({ id: 't2', kind: 'expense', amount: '80.00', occurred_at: localMs }))
    store.addTx(makeTx({ id: 't3', kind: 'income', amount: '1000.00', occurred_at: localMs }))

    const ym = (() => {
      const s = localMs + TZ_OFFSET_MIN * 60_000
      const d = new Date(s)
      return `${d.getUTCFullYear()}-${String(d.getUTCMonth() + 1).padStart(2, '0')}`
    })()
    const report = monthlyReport(
      ym,
      store.listTxs.map(toTxLike),
      store.listAccounts.map(toAccountLike),
    )

    expect(report.expense).toBe('200.50') // 120.50 + 80.00
    expect(report.income).toBe('1000.00')
    expect(report.txCount).toBe(3)
  })

  it('跨月流水不计入当月', () => {
    const store = useFinanceStore()
    // 取 2025-06-15 12:00 本地 → ym = 2025-06
    const TZ_OFFSET_MIN = -new Date(1780000000000).getTimezoneOffset()
    const junMs =
      Date.UTC(2025, 5, 15, 12, 0, 0, 0) - TZ_OFFSET_MIN * 60_000
    const mayMs =
      Date.UTC(2025, 4, 15, 12, 0, 0, 0) - TZ_OFFSET_MIN * 60_000

    store.addTx(makeTx({ id: 't1', kind: 'expense', amount: '50.00', occurred_at: junMs }))
    store.addTx(makeTx({ id: 't2', kind: 'expense', amount: '999.00', occurred_at: mayMs }))

    const report = monthlyReport(
      '2025-06',
      store.listTxs.map(toTxLike),
      store.listAccounts.map(toAccountLike),
    )

    expect(report.expense).toBe('50.00') // 仅 6 月
    expect(report.txCount).toBe(1)
  })
})

// -----------------------------------------------------------------------------
// 3. 预算进度 —— 三档判定
// -----------------------------------------------------------------------------

describe('FinanceDashboard / 预算阈值', () => {
  it('OK —— 支出 < 收入', () => {
    expect(budgetThreshold('10000.00', '5000.00', 1.0)).toBe('OK')
  })
  it('WARNING —— 支出 ≥ 1.0 倍收入但 < 1.5 倍', () => {
    expect(budgetThreshold('10000.00', '12000.00', 1.0)).toBe('WARNING')
  })
  it('EXCEEDED —— 支出 ≥ 1.5 倍收入', () => {
    expect(budgetThreshold('10000.00', '20000.00', 1.0)).toBe('EXCEEDED')
  })
  it('零收入 → OK（短路）', () => {
    expect(budgetThreshold('0', '5000', 1.0)).toBe('OK')
  })
})

// -----------------------------------------------------------------------------
// 4. 空态 —— 全空数据安全返回零值
// -----------------------------------------------------------------------------

describe('FinanceDashboard / 空态', () => {
  it('全空数据不抛错, 返回全零 DashboardSnapshot', () => {
    const store = useFinanceStore()
    const acc = store.listAccounts.map(toAccountLike)
    const cards = store.listCards.map(toCardLike)
    const txs = store.listTxs.map(toTxLike)
    const snap = netWorth(acc, cards, txs)

    expect(snap.totalAssets).toBe('0.00')
    expect(snap.totalAssetValue).toBe('0.00')
    expect(snap.totalLiability).toBe('0.00')
    expect(snap.accountCount).toBe(0)
    expect(snap.cardCount).toBe(0)
    expect(snap.txCount).toBe(0)
    expect(snap.currency).toBe('CNY') // 默认货币
  })

  it('空流水月报 → 全零 MonthlyReport', () => {
    const report = monthlyReport('2025-06', [], [])
    expect(report.expense).toBe('0.00')
    expect(report.income).toBe('0.00')
    expect(report.net).toBe('0.00')
    expect(report.txCount).toBe(0)
    expect(report.categoryBreakdown).toEqual({})
  })
})
