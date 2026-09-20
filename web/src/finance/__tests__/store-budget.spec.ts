// ============================================================================
// finance B6 预算 store 单元测试（stage5-finance-v2 / B6 / FR-V2-F）
// ============================================================================
//
// 验证目标（11 用例）：
//   1. addBudget 校验失败抛错且不 seal / 不入 Map；
//   2. addBudget 成功 → seal payload type='budget' + 入 Map + persist；
//   3. updateBudget version 递增；
//   4. deleteBudget 推墓碑（deleted=true, type='budget'）+ 本地清理；
//   5. hydrate 持久化 budgets 还原；
//   6. 旧 state 无 budgets 字段不报错（按空数组）；
//   7. precheckTx 无预算 → OK_EMPTY；非 expense 同样放行；
//   8. precheckTx 本月已花 + 本笔超 100% → BLOCK 且 usedPct 正确；
//   9. addTx BLOCK 未 ack → false 且未 seal / push / persist；
//  10. addTx BLOCK ack=true → true 且落库 tx 带 overspend_acknowledged；
//  11. WARNING 档 addTx(未 ack) → true 正常落库。
//
// 时间锚定：1782619200000（2026-06-28 12:00 CST），预算与流水均放 2026-06。
//
// 测试策略（同 store-v2.spec.ts）：node 环境注入内存 StorageChannel 与
// 带 spy 的 CryptoChannel；零知识测试数据均本地构造，无真实敏感信息。
// ============================================================================

import { describe, it, expect, beforeEach, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import {
  useFinanceStore,
  type StorageChannel,
  type CryptoChannel,
  type PersistedFinanceState,
} from '../../stores/finance'
import type { FinanceAccount, FinanceBudget, FinanceTx } from '../types'
import type { RemoteRecord } from '../../api/client'
import { OK_EMPTY } from '../budgetEnforcer'

// ▌时间锚点（CST，固定 +8h 口径）

/** 2026-06-28 12:00 CST（业务时间锚）。 */
const ANCHOR = 1782619200000
/** 2026-06-10 12:00 CST（既有支出时间）。 */
const JUN_10 = ANCHOR - 18 * 86400000
/** 2026-06-01 00:00 CST（预算有效期起）。 */
const JUN_START = Date.UTC(2026, 5, 1) - 8 * 3600000
/** 2026-12-31 23:59:59.999 CST（预算有效期止）。 */
const YEAR_END = Date.UTC(2026, 11, 31, 23, 59, 59, 999) - 8 * 3600000

// ▌内存 StorageChannel（同 store-v2.spec.ts）

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

/**
 * 带 spy 的 CryptoChannel：seal / push 均记录调用，供断言“未密封 /
 * 推墓碑”等行为；list 返回空页避免 pullAll 链路噪音。
 */
function spyCryptoChannel(): CryptoChannel & {
  seal: ReturnType<typeof vi.fn>
  push: ReturnType<typeof vi.fn>
  pushed: RemoteRecord[]
} {
  const pushed: RemoteRecord[] = []
  return {
    seal: vi.fn(() => 'cipher-b6'),
    open: vi.fn(() => ({}) as never),
    push: vi.fn(async (record: RemoteRecord) => {
      pushed.push(record)
      return { applied: 1, skipped: 0, server_time: Date.now() }
    }),
    list: vi.fn(async () => ({ records: [], has_more: false })),
    pushed,
  }
}

/** 等待 fire-and-forget pushChanges 微任务完成。 */
async function flushPush(): Promise<void> {
  await new Promise((resolve) => setTimeout(resolve, 0))
}

// ▌fixture

function makeBudget(overrides: Partial<FinanceBudget> = {}): FinanceBudget {
  return {
    id: 'bud1',
    schema_version: 2,
    scope: 'monthly',
    category: '餐饮',
    amount_minor: '1000.00',
    currency: 'CNY',
    start_ts: JUN_START,
    end_ts: YEAR_END,
    warning_threshold_pct: 80,
    block_threshold_pct: 100,
    active: true,
    created_at: JUN_START,
    updated_at: JUN_START,
    ...overrides,
  }
}

function makeAccount(overrides: Partial<FinanceAccount> = {}): FinanceAccount {
  return {
    id: 'acc1',
    schema_version: 1,
    name: '现金账户',
    kind: 'cash',
    currency: 'CNY',
    balance: '5000.00',
    note: null,
    icon: null,
    color: 'blue',
    archived: false,
    created_at: JUN_START,
    updated_at: JUN_START,
    ...overrides,
  }
}

function makeTx(overrides: Partial<FinanceTx> = {}): FinanceTx {
  return {
    id: 'tx1',
    schema_version: 1,
    account_id: 'acc1',
    card_id: null,
    kind: 'expense',
    amount: '500.00',
    category: '餐饮',
    occurred_at: ANCHOR,
    note: null,
    transfer_to_account_id: null,
    icon: null,
    color: 'slate',
    created_at: ANCHOR,
    updated_at: ANCHOR,
    ...overrides,
  }
}

// ▌前置

let storage: ReturnType<typeof memoryChannel>
let crypto: ReturnType<typeof spyCryptoChannel>

beforeEach(() => {
  setActivePinia(createPinia())
  storage = memoryChannel()
  crypto = spyCryptoChannel()
  const store = useFinanceStore()
  store._setStorageForTest(storage)
  store._setChannelForTest(crypto)
})

// ============================================================================
// 1. Budget CRUD
// ============================================================================

describe('finance B6 store / budget CRUD', () => {
  it('addBudget 校验失败抛错，且不 seal / 不入 Map', () => {
    const store = useFinanceStore()
    // warning > block 违反 1 <= warning <= block <= 10000。
    const bad = makeBudget({ warning_threshold_pct: 120, block_threshold_pct: 100 })
    expect(() => store.addBudget(bad)).toThrow()
    expect(store.listBudgets).toHaveLength(0)
    expect(store.byId('budget', 'bud1')).toBeUndefined()
    expect(crypto.seal).not.toHaveBeenCalled()
  })

  it('addBudget 成功 → seal 的上行记录 type=budget，入 Map 且 persist 落盘', async () => {
    const store = useFinanceStore()
    store.addBudget(makeBudget())
    await flushPush()
    expect(store.byId('budget', 'bud1')?.type).toBe('budget')
    expect(store.listBudgets).toHaveLength(1)
    expect(crypto.seal).toHaveBeenCalled()
    expect(crypto.pushed[0]?.type).toBe('budget')
    const snap = storage.snapshot()
    expect(snap?.schemaVersion).toBe(2)
    expect(snap?.budgets).toHaveLength(1)
    expect(snap?.budgets?.[0]?.id).toBe('bud1')
  })

  it('updateBudget → 本地 version 递增且更新持久化', () => {
    const store = useFinanceStore()
    // 与 store-v2.spec.ts 同款：add / update 连续调用后做同步断言，不
    // flush 异步 pushChanges（其续跑中的二次 wrap 属既有上行链路行为）。
    store.addBudget(makeBudget({ warning_threshold_pct: 80 }))
    store.updateBudget(makeBudget({ warning_threshold_pct: 70 }))
    const cached = store.byId('budget', 'bud1')
    expect(cached?.version).toBe(2)
    expect((cached?.data as FinanceBudget).warning_threshold_pct).toBe(70)
    expect(storage.snapshot()?.budgets?.[0]?.warning_threshold_pct).toBe(70)
  })

  it('deleteBudget → 推 type=budget 的墓碑（deleted=true）并本地清理', async () => {
    const store = useFinanceStore()
    store.addBudget(makeBudget())
    await flushPush()
    await store.deleteBudget('bud1')
    const tombstone = crypto.pushed.find((r) => r.deleted)
    expect(tombstone).toBeDefined()
    expect(tombstone?.type).toBe('budget')
    expect(tombstone?.id).toBe('bud1')
    expect(store.byId('budget', 'bud1')).toBeUndefined()
    expect(store.listBudgets).toHaveLength(0)
  })
})

// ============================================================================
// 2. hydrate / 旧形态兼容
// ============================================================================

describe('finance B6 store / hydrate budgets', () => {
  it('hydrate → 持久化 budgets 数组还原入 Map', () => {
    storage.write({
      schemaVersion: 2,
      accounts: [],
      cards: [],
      txs: [],
      subscriptions: [],
      policies: [],
      loans: [],
      contracts: [],
      budgets: [makeBudget({ id: 'b-h' })],
    })
    const store = useFinanceStore()
    store.hydrate()
    expect(store.listBudgets).toHaveLength(1)
    expect(store.listBudgets[0]?.id).toBe('b-h')
    expect(store.byId('budget', 'b-h')?.type).toBe('budget')
  })

  it('旧 state 无 budgets 字段 → hydrate 不报错且 listBudgets 为空', () => {
    storage.write({
      schemaVersion: 2,
      accounts: [],
      cards: [],
      txs: [],
      subscriptions: [],
      policies: [],
      loans: [],
      contracts: [],
    } as unknown as PersistedFinanceState)
    const store = useFinanceStore()
    expect(() => store.hydrate()).not.toThrow()
    expect(store.listBudgets).toEqual([])
  })
})

// ============================================================================
// 3. precheckTx 预算硬约束门面
// ============================================================================

describe('finance B6 store / precheckTx', () => {
  it('无预算 → 返回 OK_EMPTY', () => {
    const store = useFinanceStore()
    store.addAccount(makeAccount())
    const r = store.precheckTx(makeTx())
    expect(r).toBe(OK_EMPTY)
  })

  it('非 expense（收入 / 转账）→ 直接 OK_EMPTY，即使预算存在', () => {
    const store = useFinanceStore()
    store.addBudget(makeBudget())
    const income = makeTx({ kind: 'income' as FinanceTx['kind'] })
    expect(store.precheckTx(income)).toBe(OK_EMPTY)
  })

  it('本月已花 600 + 本笔 500（额度 1000）→ BLOCK，usedPct=110', () => {
    const store = useFinanceStore()
    store.addAccount(makeAccount())
    store.addBudget(makeBudget())
    // 既有 600 元餐饮支出（本月桶内）。
    store.addTx(makeTx({ id: 'old1', amount: '600.00', occurred_at: JUN_10 }))
    // 本笔 500：预计累计 1100，占 110%，达到 block=100 → BLOCK。
    const r = store.precheckTx(makeTx({ id: 'new1', amount: '500.00' }))
    expect(r.level).toBe('BLOCK')
    expect(r.usedPct).toBe(110)
    expect(r.budgetId).toBe('bud1')
  })

  it('编辑场景：同 id 旧额自动排除，改金额后按新额判定不重复累计', () => {
    const store = useFinanceStore()
    store.addAccount(makeAccount())
    store.addBudget(makeBudget())
    // 先落一笔 600（同 id 即“正在编辑”的旧记录）。
    store.addTx(makeTx({ id: 'edit1', amount: '600.00', occurred_at: JUN_10 }))
    // 编辑后 550：existing 中 edit1 被排除，预计仅 550/1000 = 55% → OK。
    const r = store.precheckTx(makeTx({ id: 'edit1', amount: '550.00' }))
    expect(r.level).toBe('OK')
    expect(r.usedPct).toBe(55)
  })
})

// ============================================================================
// 4. addTx 拦截 / 确认 / WARNING
// ============================================================================

describe('finance B6 store / addTx 预算拦截', () => {
  it('BLOCK 未 ack → addTx 返回 false，未 seal / push / 落库', () => {
    const store = useFinanceStore()
    store.addAccount(makeAccount())
    store.addBudget(makeBudget())
    store.addTx(makeTx({ id: 'old1', amount: '600.00', occurred_at: JUN_10 }))
    crypto.seal.mockClear()
    crypto.push.mockClear()
    crypto.pushed.length = 0
    const saved = store.addTx(makeTx({ id: 'block1', amount: '500.00' }))
    expect(saved).toBe(false)
    expect(store.byId('tx', 'block1')).toBeUndefined()
    expect(crypto.seal).not.toHaveBeenCalled()
    expect(crypto.push).not.toHaveBeenCalled()
    expect(storage.snapshot()?.txs).toHaveLength(1)
  })

  it('BLOCK ack=true → addTx 返回 true，落库 tx 带 overspend_acknowledged=true', async () => {
    const store = useFinanceStore()
    store.addAccount(makeAccount())
    store.addBudget(makeBudget())
    store.addTx(makeTx({ id: 'old1', amount: '600.00', occurred_at: JUN_10 }))
    const payload = makeTx({ id: 'ack1', amount: '500.00' })
    const saved = store.addTx(payload, true)
    expect(saved).toBe(true)
    const cached = store.byId('tx', 'ack1')
    expect(cached).toBeDefined()
    const tx = cached?.data as FinanceTx
    expect(tx.overspend_acknowledged).toBe(true)
    expect(storage.snapshot()?.txs?.find((t) => t.id === 'ack1')?.overspend_acknowledged).toBe(true)
    await flushPush()
    expect(crypto.pushed.some((r) => r.type === 'tx' && r.id === 'ack1')).toBe(true)
  })

  it('WARNING 档（85%）未 ack → addTx 返回 true 正常落库，且不挂确认标记', async () => {
    const store = useFinanceStore()
    store.addAccount(makeAccount())
    store.addBudget(makeBudget())
    // 既有 300 + 本笔 550 = 850 → 85%：达预警 80、未达拦截 100。
    store.addTx(makeTx({ id: 'old1', amount: '300.00', occurred_at: JUN_10 }))
    const payload = makeTx({ id: 'warn1', amount: '550.00' })
    const pre = store.precheckTx(payload)
    expect(pre.level).toBe('WARNING')
    expect(pre.usedPct).toBe(85)
    const saved = store.addTx(payload)
    expect(saved).toBe(true)
    const tx = store.byId('tx', 'warn1')?.data as FinanceTx
    expect(tx.overspend_acknowledged).toBeUndefined()
    await flushPush()
  })
})
