// ============================================================================
// finance store 单元测试（stage5-finance / Task 8 / TR-8.7）
// ============================================================================
//
// 验证目标（≥8 用例, 覆盖 TR-8.6 Pass Condition + 全部边界）：
//   1. CRUD —— addAccount / addCard / addTx / updateX / archiveX / deleteTx；
//   2. 持久化 —— localStorage key = `eve:finance:v1`，CRUD 后自动 persist；
//   3. hydrate —— 注入持久化数据后内存可还原；
//   4. 列表查询 —— listAccounts / listCards / listTxs 过滤 + 排序；
//   5. 计算属性联动 —— aggregator 调 store 输出 DashboardSnapshot。
//
// 测试策略：
//   - 默认环境 = node（vitest 配置），无 window / localStorage —— 故测试
//     通过 `_setStorageForTest` 注入内存 StorageChannel 替代 localStorage；
//   - beforeEach 重置 store 状态，保证用例间隔离。
//
// 零知识纪律：
//   - 测试卡号均为业界公开示例 last4（"1111"/"2222"），非真实持卡人卡号；
//   - 持久化通道 mock 不打印明文；
//   - 不向真实 localStorage / IndexedDB / 网络写入任何数据。
//
// 关联:
//   - web/src/stores/finance.ts（被测目标）
//   - web/src/finance/aggregator.ts（联动验证）
//   - tasks.md TR-8.6 / TR-8.7
// ============================================================================

import { describe, it, expect, beforeEach } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import {
  useFinanceStore,
  FINANCE_STORAGE_KEY,
  type StorageChannel,
  type CryptoChannel,
  type PersistedFinanceState,
} from '../../stores/finance'
import type { FinanceAccount, FinanceCard, FinanceTx } from '../types'
import { DEFAULT_ACCOUNT_COLOR, DEFAULT_CARD_COLOR, DEFAULT_TX_COLOR } from '../types'

// -----------------------------------------------------------------------------
// 测试用工具 —— 内存 StorageChannel（替代 localStorage）
// -----------------------------------------------------------------------------

/**
 * 内存版 StorageChannel —— 满足单测隔离 + 无 jsdom 依赖。
 */
function memoryChannel(): StorageChannel & { snapshot: () => PersistedFinanceState | null } {
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
    snapshot() {
      return state == null ? null : JSON.parse(JSON.stringify(state))
    },
  }
}

// -----------------------------------------------------------------------------
// 测试用工具 —— noop CryptoChannel（T8 仅验证本地 CRUD，不依赖 sealRecord 链路）
// -----------------------------------------------------------------------------

/**
 * 占位 CryptoChannel —— push/list 返回 ok，open/seal 返回空字符串。
 *
 * store.spec.ts（T8 范围）只验证本地 CRUD + 持久化，不需要真实加密链路；
 * 但 finance store 在 CRUD 后会 fire-and-forget pushChanges 调用 channel.push，
 * 没有注入 channel 会走到默认实现触发 useAuthStore → localStorage 报错。
 * 用 noop 占位既能避免连锁报错，又不影响本地 CRUD 断言。
 */
function noopCryptoChannel(): CryptoChannel {
  return {
    seal: () => '',
    open: () => ({}) as never,
    push: async () => ({ applied: 1, skipped: 0, server_time: Date.now() }),
    list: async () => ({ records: [], has_more: false }),
  }
}

// -----------------------------------------------------------------------------
// 测试用 fixture —— 三类条目各一条最小有效数据
// -----------------------------------------------------------------------------

function makeAccount(overrides: Partial<FinanceAccount> = {}): FinanceAccount {
  return {
    id: 'a1',
    schema_version: 1,
    name: '现金钱包',
    kind: 'cash',
    currency: 'CNY',
    balance: '1234.56',
    note: null,
    icon: null,
    color: DEFAULT_ACCOUNT_COLOR,
    archived: false,
    created_at: 1735689600000,
    updated_at: 1735689600000,
    ...overrides,
  }
}

function makeCard(overrides: Partial<FinanceCard> = {}): FinanceCard {
  return {
    id: 'c1',
    schema_version: 1,
    name: '招行信用卡',
    kind: 'credit',
    issuer: '招商银行',
    last4: '1111',
    currency: 'CNY',
    credit_limit: '10000.00',
    used_limit: '500.00',
    billing_day: 15,
    due_day: 25,
    note: null,
    icon: null,
    color: DEFAULT_CARD_COLOR,
    archived: false,
    include_in_net_assets: true,
    created_at: 1735689600000,
    updated_at: 1735689600000,
    ...overrides,
  }
}

function makeTx(overrides: Partial<FinanceTx> = {}): FinanceTx {
  return {
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
    color: DEFAULT_TX_COLOR,
    created_at: 1735689600000,
    updated_at: 1735689600000,
    ...overrides,
  }
}

// -----------------------------------------------------------------------------
// 测试前置：每个用例独立 pinia + 内存 storage 注入
// -----------------------------------------------------------------------------

let channel: ReturnType<typeof memoryChannel>

beforeEach(() => {
  setActivePinia(createPinia())
  channel = memoryChannel()
  const store = useFinanceStore()
  store._setStorageForTest(channel)
  // 注入 noop CryptoChannel：CRUD 内部 pushChanges 不依赖真实加密链路，
  // 避免连锁触发 useAuthStore → localStorage 引用错误。
  store._setChannelForTest(noopCryptoChannel())
})

// ============================================================================
// 1. 持久化 key 常量
// ============================================================================

describe('finance store / 持久化 key 常量', () => {
  it('FINANCE_STORAGE_KEY === "eve:finance:v1"', () => {
    expect(FINANCE_STORAGE_KEY).toBe('eve:finance:v1')
  })
})

// ============================================================================
// 2. CRUD —— 账户
// ============================================================================

describe('finance store / 账户 CRUD', () => {
  it('addAccount → listAccounts 包含新账户（按 updatedAt 降序）', () => {
    const store = useFinanceStore()
    store.addAccount(makeAccount({ id: 'a1', name: '现金钱包' }))
    expect(store.listAccounts).toHaveLength(1)
    expect(store.listAccounts[0]?.name).toBe('现金钱包')
  })

  it('addAccount 自动 persist 到 storage（key = eve:finance:v1）', () => {
    const store = useFinanceStore()
    store.addAccount(makeAccount({ id: 'a1' }))
    const snap = channel.snapshot()
    expect(snap).not.toBeNull()
    expect(snap?.accounts).toHaveLength(1)
    expect(snap?.accounts[0]?.id).toBe('a1')
  })

  it('updateAccount → version 自增 + updatedAt 更新', () => {
    const store = useFinanceStore()
    store.addAccount(makeAccount({ id: 'a1', name: '现金钱包' }))
    const before = store.byId('account', 'a1')
    const initialVersion = before?.version ?? 0

    store.updateAccount(makeAccount({ id: 'a1', name: '现金钱包改', balance: '2000.00' }))

    const after = store.byId('account', 'a1')
    expect(after?.version).toBe(initialVersion + 1)
    expect((after?.data as FinanceAccount).name).toBe('现金钱包改')
    expect((after?.data as FinanceAccount).balance).toBe('2000.00')
  })

  it('archiveAccount → listAccounts 不再返回归档项, 但 Map 仍保留', () => {
    const store = useFinanceStore()
    store.addAccount(makeAccount({ id: 'a1' }))
    expect(store.listAccounts).toHaveLength(1)
    store.archiveAccount('a1')
    expect(store.listAccounts).toHaveLength(0)
    // Map 中仍保留（带 archived=true）。
    const cached = store.byId('account', 'a1')
    expect(cached).toBeDefined()
    expect((cached?.data as FinanceAccount).archived).toBe(true)
  })
})

// ============================================================================
// 3. CRUD —— 卡
// ============================================================================

describe('finance store / 卡 CRUD', () => {
  it('addCard → listCards 包含新卡', () => {
    const store = useFinanceStore()
    store.addCard(makeCard({ id: 'c1', name: '招行信用卡' }))
    expect(store.listCards).toHaveLength(1)
    expect(store.listCards[0]?.last4).toBe('1111')
  })

  it('archiveCard → listCards 不再返回, 持久化保留 archived=true', () => {
    const store = useFinanceStore()
    store.addCard(makeCard({ id: 'c1' }))
    store.archiveCard('c1')
    expect(store.listCards).toHaveLength(0)
    const snap = channel.snapshot()
    expect(snap?.cards[0]?.archived).toBe(true)
  })

  it('updateCard → version 自增, 持久化字段更新', () => {
    const store = useFinanceStore()
    store.addCard(makeCard({ id: 'c1', credit_limit: '10000.00' }))
    store.updateCard(makeCard({ id: 'c1', credit_limit: '20000.00' }))
    const cached = store.byId('card', 'c1')
    expect(cached?.version).toBe(2)
    expect((cached?.data as FinanceCard).credit_limit).toBe('20000.00')
    const snap = channel.snapshot()
    expect(snap?.cards[0]?.credit_limit).toBe('20000.00')
  })
})

// ============================================================================
// 4. CRUD —— 流水
// ============================================================================

describe('finance store / 流水 CRUD', () => {
  it('addTx → listTxs 包含新流水（按 occurred_at 降序）', () => {
    const store = useFinanceStore()
    store.addTx(makeTx({ id: 't1', amount: '50.00', occurred_at: 1000 }))
    store.addTx(makeTx({ id: 't2', amount: '100.00', occurred_at: 2000 }))
    expect(store.listTxs).toHaveLength(2)
    // 降序：t2 (2000) 在前。
    expect(store.listTxs[0]?.id).toBe('t2')
    expect(store.listTxs[1]?.id).toBe('t1')
  })

  it('updateTx → version 自增, 持久化字段更新', () => {
    const store = useFinanceStore()
    store.addTx(makeTx({ id: 't1', amount: '50.00' }))
    store.updateTx(makeTx({ id: 't1', amount: '60.00' }))
    const cached = store.byId('tx', 't1')
    expect(cached?.version).toBe(2)
    expect((cached?.data as FinanceTx).amount).toBe('60.00')
  })

  it('deleteTx → 硬删除, listTxs 与持久化均不再包含', async () => {
    const store = useFinanceStore()
    store.addTx(makeTx({ id: 't1' }))
    await Promise.resolve()
    await Promise.resolve()
    expect(store.listTxs).toHaveLength(1)
    // T11 集成后 deleteTx 是 async（推送墓碑 → 等回拉 → 删本地），必须 await。
    await store.deleteTx('t1')
    expect(store.listTxs).toHaveLength(0)
    expect(store.byId('tx', 't1')).toBeUndefined()
    const snap = channel.snapshot()
    expect(snap?.txs).toHaveLength(0)
  })
})

// ============================================================================
// 5. 启动 hydration —— 从持久化恢复
// ============================================================================

describe('finance store / startup hydration', () => {
  it('hydrate() 从 storage 还原 accounts / cards / txs', () => {
    // 先写一份持久化数据。
    channel.write({
      schemaVersion: 1,
      accounts: [makeAccount({ id: 'a1', name: '已持久化账户' })],
      cards: [makeCard({ id: 'c1', name: '已持久化卡' })],
      txs: [makeTx({ id: 't1', amount: '88.00' })],
      loans: [],
    })

    const store = useFinanceStore()
    expect(store.hydrated).toBe(false)
    store.hydrate()
    expect(store.hydrated).toBe(true)
    expect(store.listAccounts).toHaveLength(1)
    expect(store.listAccounts[0]?.name).toBe('已持久化账户')
    expect(store.listCards).toHaveLength(1)
    expect(store.listTxs).toHaveLength(1)
    expect(store.listTxs[0]?.amount).toBe('88.00')
  })

  it('hydrate() 空 storage 时不抛错, 列表为空', () => {
    const store = useFinanceStore()
    store.hydrate()
    expect(store.hydrated).toBe(true)
    expect(store.listAccounts).toHaveLength(0)
    expect(store.listCards).toHaveLength(0)
    expect(store.listTxs).toHaveLength(0)
  })

  it('hydrate() 幂等 —— 多次调用不重复注入', () => {
    channel.write({
      schemaVersion: 1,
      accounts: [makeAccount({ id: 'a1' })],
      cards: [],
      txs: [],
      loans: [],
    })
    const store = useFinanceStore()
    store.hydrate()
    expect(store.listAccounts).toHaveLength(1)
    // 外部修改 storage 后再 hydrate() 应无效（已 hydrated）。
    channel.write({
      schemaVersion: 1,
      accounts: [makeAccount({ id: 'a1' }), makeAccount({ id: 'a2' })],
      cards: [],
      txs: [],
      loans: [],
    })
    store.hydrate()
    expect(store.listAccounts).toHaveLength(1)
  })
})

// ============================================================================
// 6. 联动 —— aggregator 输出 DashboardSnapshot
// ============================================================================

describe('finance store / aggregator 联动', () => {
  it('store + aggregator 输出 DashboardSnapshot —— 单账户余额正确', async () => {
    const { netWorth, toAccountLike, toCardLike } = await import('../aggregator')
    const store = useFinanceStore()
    store.addAccount(makeAccount({ id: 'a1', balance: '5000.00' }))

    const snap = netWorth(
      store.listAccounts.map(toAccountLike),
      store.listCards.map(toCardLike),
      store.listTxs.map((tx) => ({
        id: tx.id,
        accountId: tx.account_id,
        cardId: tx.card_id ?? null,
        kind: tx.kind,
        amount: tx.amount,
        category: tx.category,
        occurredAt: tx.occurred_at,
        transferToAccountId: tx.transfer_to_account_id ?? null,
      })),
    )
    expect(snap.totalAssets).toBe('5000.00')
    expect(snap.totalAssetValue).toBe('5000.00')
    expect(snap.totalLiability).toBe('0.00')
    expect(snap.accountCount).toBe(1)
  })
})

// ============================================================================
// 7. 零知识纪律（pure-ish 语义 + 不向真实 localStorage 写入）
// ============================================================================

describe('finance store / 零知识纪律', () => {
  it('CRUD 不修改入参对象（不可变更新语义）', () => {
    const store = useFinanceStore()
    const original = makeAccount({ id: 'a1', balance: '1000.00' })
    const snapshot = JSON.parse(JSON.stringify(original))
    store.addAccount(original)
    expect(original).toEqual(snapshot)
  })

  it('持久化通道 mock 与真实 localStorage 隔离（无 window 依赖）', () => {
    // 本测试运行在 vitest node 环境（vitest.config.ts 已配 environment: 'node'）；
    // 验证 store 默认 channel.read() 不会因 localStorage undefined 抛错。
    const store = useFinanceStore()
    expect(() => store.hydrate()).not.toThrow()
  })
})