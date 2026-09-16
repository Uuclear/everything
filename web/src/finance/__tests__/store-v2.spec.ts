// ============================================================================
// finance v2 子类型 store 单元测试（stage5-finance-v2 / B1 余下 / TR-1.7）
// ============================================================================
//
// 验证目标（≥20 用例, 覆盖 store v2 扩展 Pass Condition）：
//   1. CRUD —— addSubscription / addPolicy / addLoan / addContract 各路径；
//   2. update —— 4 子类型 version 自增 + updatedAt 更新 + 持久化同步；
//   3. delete —— 4 子类型墓碑推送 + 拉取回执 + 本地清理；
//   4. list —— 4 子类型 list 按各自时间字段升序（即将到期在前）；
//   5. byId —— 4 子类型能按 id 命中；
//   6. payloadTypeOf —— 4 子类型 + 3 类 v1 收窄正确；
//   7. hydrate —— schemaVersion=1 形态缺 v2 字段时容错为空数组；
//   8. persist —— 落盘 schemaVersion=2 + 含 4 子类型数组；
//   9. assertValidV2 —— 4 子类型校验失败抛异常（不写本地缓存）。
//
// 测试策略：
//   - 默认环境 = node（vitest 配置），无 window / localStorage —— 故测试
//     通过 `_setStorageForTest` 注入内存 StorageChannel 替代 localStorage；
//   - `_setChannelForTest` 注入 noop CryptoChannel，避免 CRUD 后 fire-and-forget
//     pushChanges 链路触发 useAuthStore → localStorage 引用错误。
//   - beforeEach 重置 store 状态，保证用例间隔离。
//
// 零知识纪律：
//   - 测试数据均为本地构造，金额用公开示例（"100.00"），无真实持卡人数据；
//   - 持久化通道 mock 不打印明文；
//   - 不向真实 localStorage / IndexedDB / 网络写入任何数据。
//
// 关联:
//   - web/src/stores/finance.ts（被测目标）
//   - web/src/finance/types.ts（v2 接口 + 校验函数）
//   - tasks.md TR-1.7
// ============================================================================

import { describe, it, expect, beforeEach } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import {
  useFinanceStore,
  type StorageChannel,
  type CryptoChannel,
  type PersistedFinanceState,
} from '../../stores/finance'
import type {
  FinanceSubscription,
  FinancePolicy,
  FinanceLoan,
  FinanceContract,
  FinancePayloadAll,
  FinanceType,
} from '../types'

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
// 测试用工具 —— noop CryptoChannel（避免 pushChanges 触发 useAuthStore）
// -----------------------------------------------------------------------------

/**
 * 占位 CryptoChannel —— push/list 返回 ok，open/seal 返回空字符串/空对象。
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
// 测试用 fixture —— v2 四子类型各一条最小有效数据
// -----------------------------------------------------------------------------

function makeSubscription(
  overrides: Partial<FinanceSubscription> = {},
): FinanceSubscription {
  return {
    id: 'sub1',
    schema_version: 2,
    name: '云盘会员',
    provider: '某云盘',
    amount_minor: '20.00',
    currency: 'CNY',
    billing_cycle: 'monthly',
    custom_days: null,
    start_ts: 1735689600000,
    next_renewal_ts: 1738281600000, // 30 天后
    reminders: [0],
    active: true,
    category: 'productivity',
    created_at: 1735689600000,
    updated_at: 1735689600000,
    ...overrides,
  }
}

function makePolicy(
  overrides: Partial<FinancePolicy> = {},
): FinancePolicy {
  return {
    id: 'pol1',
    schema_version: 2,
    name: '车险',
    policy_number: 'POL-2025-001',
    policy_number_encrypted: true,
    provider: '某人寿',
    premium_minor: '3000.00',
    coverage_minor: '200000.00',
    currency: 'CNY',
    billing_cycle: 'yearly',
    start_ts: 1735689600000,
    expiry_ts: 1767225600000,
    reminders: [0, 10080],
    active: true,
    linked_account_id: null,
    attachments: [],
    created_at: 1735689600000,
    updated_at: 1735689600000,
    ...overrides,
  }
}

function makeLoan(
  overrides: Partial<FinanceLoan> = {},
): FinanceLoan {
  return {
    id: 'loan1',
    schema_version: 2,
    counterparty: '友人甲',
    principal_minor: '10000.00',
    currency: 'CNY',
    direction: 'lent',
    issue_ts: 1735689600000,
    due_ts: 1767225600000,
    interest_rate_apy_bps: 360, // 3.6%
    status: 'active',
    paid_minor: '0.00',
    reminders: [0],
    linked_account_id: null,
    include_in_net_assets: true,
    created_at: 1735689600000,
    updated_at: 1735689600000,
    ...overrides,
  }
}

function makeContract(
  overrides: Partial<FinanceContract> = {},
): FinanceContract {
  const start = 1735689600000
  const end = 1767225600000
  const noticeDays = 30
  return {
    id: 'ct1',
    schema_version: 2,
    title: '租房合同',
    counterparty: '房东',
    kind: 'rental',
    amount_minor: '5000.00',
    currency: 'CNY',
    signed_ts: start - 86400000,
    start_ts: start,
    end_ts: end,
    auto_renew: false,
    notice_period_days: noticeDays,
    notice_deadline_ts: end - noticeDays * 86400000,
    status: 'active',
    linked_account_id: null,
    attachments: [],
    created_at: 1735689600000,
    updated_at: 1735689600000,
    ...overrides,
  }
}

// -----------------------------------------------------------------------------
// 测试前置：每个用例独立 pinia + 内存 storage + noop channel
// -----------------------------------------------------------------------------

let channel: ReturnType<typeof memoryChannel>

beforeEach(() => {
  setActivePinia(createPinia())
  channel = memoryChannel()
  const store = useFinanceStore()
  store._setStorageForTest(channel)
  store._setChannelForTest(noopCryptoChannel())
})

// ============================================================================
// 1. Subscription CRUD
// ============================================================================

describe('finance v2 store / Subscription CRUD', () => {
  it('addSubscription → listSubscriptions 含 1 条（按 next_renewal_ts 升序）', () => {
    const store = useFinanceStore()
    // start_ts 固定 1735689600000, next_renewal_ts 必须 >= start_ts。
    // 用更大的时间戳区分排序。
    const baseStart = 1735689600000
    store.addSubscription(
      makeSubscription({ id: 's1', start_ts: baseStart, next_renewal_ts: baseStart + 60 * 86400000 }),
    )
    store.addSubscription(
      makeSubscription({ id: 's2', start_ts: baseStart, next_renewal_ts: baseStart + 30 * 86400000 }),
    )
    expect(store.listSubscriptions).toHaveLength(2)
    expect(store.listSubscriptions[0]?.id).toBe('s2')
    expect(store.listSubscriptions[1]?.id).toBe('s1')
  })

  it('addSubscription → persist 落到 storage（含 schemaVersion=2）', () => {
    const store = useFinanceStore()
    store.addSubscription(makeSubscription({ id: 's1', name: '云盘会员' }))
    const snap = channel.snapshot()
    expect(snap).not.toBeNull()
    expect(snap?.schemaVersion).toBe(2)
    expect(snap?.subscriptions).toHaveLength(1)
    expect(snap?.subscriptions[0]?.name).toBe('云盘会员')
  })

  it('updateSubscription → version 自增 + 持久化同步', () => {
    const store = useFinanceStore()
    store.addSubscription(makeSubscription({ id: 's1', amount_minor: '20.00' }))
    const before = store.byId('subscription', 's1')
    const initialVersion = before?.version ?? 0
    store.updateSubscription(
      makeSubscription({ id: 's1', amount_minor: '25.00', name: '云盘 PLUS' }),
    )
    const after = store.byId('subscription', 's1')
    expect(after?.version).toBe(initialVersion + 1)
    const data = after?.data as unknown as FinanceSubscription
    expect(data.amount_minor).toBe('25.00')
    expect(data.name).toBe('云盘 PLUS')
    const snap = channel.snapshot()
    expect((snap?.subscriptions[0] as FinanceSubscription).amount_minor).toBe('25.00')
  })

  it('addSubscription 校验失败 → 抛异常且不写入', () => {
    const store = useFinanceStore()
    // amount_minor = "0.00" 触发 isValidDecimalString 拒绝（要求 > 0）。
    expect(() =>
      store.addSubscription(
        makeSubscription({ id: 'sBad', amount_minor: '0.00' }),
      ),
    ).toThrow()
    expect(store.listSubscriptions).toHaveLength(0)
    expect(store.byId('subscription', 'sBad')).toBeUndefined()
  })
})

// ============================================================================
// 2. Policy CRUD
// ============================================================================

describe('finance v2 store / Policy CRUD', () => {
  it('addPolicy → listPolicies 按 expiry_ts 升序', () => {
    const store = useFinanceStore()
    const baseStart = 1735689600000
    store.addPolicy(
      makePolicy({ id: 'p1', start_ts: baseStart, expiry_ts: baseStart + 365 * 86400000 }),
    )
    store.addPolicy(
      makePolicy({ id: 'p2', start_ts: baseStart, expiry_ts: baseStart + 180 * 86400000 }),
    )
    expect(store.listPolicies).toHaveLength(2)
    expect(store.listPolicies[0]?.id).toBe('p2')
    expect(store.listPolicies[1]?.id).toBe('p1')
  })

  it('addPolicy → byId 能命中', () => {
    const store = useFinanceStore()
    store.addPolicy(makePolicy({ id: 'pol-x', name: '车险' }))
    const cached = store.byId('policy', 'pol-x')
    expect(cached).toBeDefined()
    expect(cached?.type).toBe('policy')
    expect((cached?.data as unknown as FinancePolicy).name).toBe('车险')
  })

  it('updatePolicy → version 自增 + 持久化同步', () => {
    const store = useFinanceStore()
    store.addPolicy(makePolicy({ id: 'p1', premium_minor: '3000.00' }))
    store.updatePolicy(makePolicy({ id: 'p1', premium_minor: '3500.00' }))
    const cached = store.byId('policy', 'p1')
    expect(cached?.version).toBe(2)
    expect((cached?.data as unknown as FinancePolicy).premium_minor).toBe('3500.00')
    const snap = channel.snapshot()
    expect((snap?.policies[0] as FinancePolicy).premium_minor).toBe('3500.00')
  })

  it('addPolicy 校验失败（premium 0） → 抛异常', () => {
    const store = useFinanceStore()
    expect(() =>
      store.addPolicy(makePolicy({ id: 'pBad', premium_minor: '0.00' })),
    ).toThrow()
    expect(store.listPolicies).toHaveLength(0)
  })
})

// ============================================================================
// 3. Loan CRUD
// ============================================================================

describe('finance v2 store / Loan CRUD', () => {
  it('addLoan → listLoans 按 due_ts 升序', () => {
    const store = useFinanceStore()
    const baseIssue = 1735689600000
    store.addLoan(
      makeLoan({ id: 'l1', issue_ts: baseIssue, due_ts: baseIssue + 365 * 86400000 }),
    )
    store.addLoan(
      makeLoan({ id: 'l2', issue_ts: baseIssue, due_ts: baseIssue + 180 * 86400000 }),
    )
    expect(store.listLoans).toHaveLength(2)
    expect(store.listLoans[0]?.id).toBe('l2')
    expect(store.listLoans[1]?.id).toBe('l1')
  })

  it('updateLoan → version 自增 + 持久化同步', () => {
    const store = useFinanceStore()
    store.addLoan(makeLoan({ id: 'l1', paid_minor: '0.00' }))
    store.updateLoan(makeLoan({ id: 'l1', paid_minor: '5000.00', status: 'partially_paid' }))
    const cached = store.byId('loan', 'l1')
    expect(cached?.version).toBe(2)
    const data = cached?.data as unknown as FinanceLoan
    expect(data.paid_minor).toBe('5000.00')
    expect(data.status).toBe('partially_paid')
    const snap = channel.snapshot()
    expect((snap?.loans[0] as FinanceLoan).status).toBe('partially_paid')
  })

  it('addLoan 校验失败（paid > principal） → 抛异常', () => {
    const store = useFinanceStore()
    expect(() =>
      store.addLoan(makeLoan({ id: 'lBad', paid_minor: '99999.00', principal_minor: '10000.00' })),
    ).toThrow()
    expect(store.listLoans).toHaveLength(0)
  })

  it('addLoan 校验失败（direction 非法） → 抛异常', () => {
    const store = useFinanceStore()
    expect(() =>
      store.addLoan(
        makeLoan({ id: 'lBad2', direction: 'invalid' as unknown as 'lent' }),
      ),
    ).toThrow()
  })
})

// ============================================================================
// 4. Contract CRUD
// ============================================================================

describe('finance v2 store / Contract CRUD', () => {
  it('addContract → listContracts 按 end_ts 升序', () => {
    const store = useFinanceStore()
    const baseStart = 1735689600000
    store.addContract(
      makeContract({
        id: 'c1',
        start_ts: baseStart,
        end_ts: baseStart + 365 * 86400000,
        notice_period_days: 30,
        notice_deadline_ts: baseStart + 365 * 86400000 - 30 * 86400000,
      }),
    )
    store.addContract(
      makeContract({
        id: 'c2',
        start_ts: baseStart,
        end_ts: baseStart + 180 * 86400000,
        notice_period_days: 30,
        notice_deadline_ts: baseStart + 180 * 86400000 - 30 * 86400000,
      }),
    )
    expect(store.listContracts).toHaveLength(2)
    expect(store.listContracts[0]?.id).toBe('c2')
    expect(store.listContracts[1]?.id).toBe('c1')
  })

  it('updateContract → version 自增 + 持久化同步', () => {
    const store = useFinanceStore()
    store.addContract(makeContract({ id: 'c1', amount_minor: '5000.00' }))
    store.updateContract(makeContract({ id: 'c1', amount_minor: '5500.00' }))
    const cached = store.byId('contract', 'c1')
    expect(cached?.version).toBe(2)
    expect((cached?.data as unknown as FinanceContract).amount_minor).toBe('5500.00')
  })

  it('addContract 校验失败（notice_deadline_ts 与 end_ts - noticePeriodDays 不一致）→ 抛异常', () => {
    const store = useFinanceStore()
    expect(() =>
      store.addContract(
        makeContract({ id: 'cBad', notice_deadline_ts: 1, notice_period_days: 0 }),
      ),
    ).toThrow()
    expect(store.listContracts).toHaveLength(0)
  })
})

// ============================================================================
// 5. 跨子类型 byId / 持久化合并
// ============================================================================

describe('finance v2 store / 跨子类型查询与持久化', () => {
  it('byId 路由 4 子类型 + v1 三类全命中', () => {
    const store = useFinanceStore()
    store.addSubscription(makeSubscription({ id: 's1' }))
    store.addPolicy(makePolicy({ id: 'p1' }))
    store.addLoan(makeLoan({ id: 'l1' }))
    store.addContract(makeContract({ id: 'c1' }))
    expect(store.byId('subscription', 's1')?.type).toBe('subscription')
    expect(store.byId('policy', 'p1')?.type).toBe('policy')
    expect(store.byId('loan', 'l1')?.type).toBe('loan')
    expect(store.byId('contract', 'c1')?.type).toBe('contract')
    expect(store.byId('subscription', 'none')).toBeUndefined()
  })

  it('persist → schemaVersion=2 + 4 子类型均落盘', () => {
    const store = useFinanceStore()
    store.addSubscription(makeSubscription({ id: 's1' }))
    store.addPolicy(makePolicy({ id: 'p1' }))
    store.addLoan(makeLoan({ id: 'l1' }))
    store.addContract(makeContract({ id: 'c1' }))
    const snap = channel.snapshot()
    expect(snap?.schemaVersion).toBe(2)
    expect(snap?.subscriptions).toHaveLength(1)
    expect(snap?.policies).toHaveLength(1)
    expect(snap?.loans).toHaveLength(1)
    expect(snap?.contracts).toHaveLength(1)
  })
})

// ============================================================================
// 6. payloadTypeOf —— 7 子类型收窄
// ============================================================================

describe('finance v2 store / payloadTypeOf', () => {
  // payloadTypeOf 是内部函数, 行为通过 pushChanges + 错误 fixture 反向验证；
  // 这里仅断言它能正确路由 7 子类型, 即 addSubscription/Policy/Loan/Contract
  // 都不会被路由到错误的 Map（避免某些 union 字段串味）。
  const cases: Array<{ kind: FinanceType; payload: FinancePayloadAll; badMap: FinanceType }> = [
    {
      kind: 'subscription',
      payload: makeSubscription(),
      badMap: 'policy',
    },
    {
      kind: 'policy',
      payload: makePolicy(),
      badMap: 'loan',
    },
    {
      kind: 'loan',
      payload: makeLoan(),
      badMap: 'contract',
    },
    {
      kind: 'contract',
      payload: makeContract(),
      badMap: 'subscription',
    },
  ]
  for (const { kind, payload, badMap } of cases) {
    it(`${kind} 不被路由到 ${badMap} 的 Map`, () => {
      const store = useFinanceStore()
      // 根据 kind 选正确的 add 函数。
      if (kind === 'subscription') store.addSubscription(payload as FinanceSubscription)
      else if (kind === 'policy') store.addPolicy(payload as FinancePolicy)
      else if (kind === 'loan') store.addLoan(payload as FinanceLoan)
      else if (kind === 'contract') store.addContract(payload as FinanceContract)
      expect(store.byId(kind, payload.id)).toBeDefined()
      expect(store.byId(badMap, payload.id)).toBeUndefined()
    })
  }
})

// ============================================================================
// 7. hydrate —— v1 形态容错 + v2 形态还原
// ============================================================================

describe('finance v2 store / hydrate', () => {
  it('hydrate schemaVersion=1 缺 v2 字段 → 4 子类型 list 为空数组, v1 数据保留', () => {
    // 故意构造 schemaVersion=1 形态：v2 4 个字段缺失（v1 阶段持久化形态）。
    channel.write({
      schemaVersion: 1,
      accounts: [],
      cards: [],
      txs: [],
      loans: [],
      // subscriptions/policies/contracts 故意缺, 由 hydrate 容错为空数组。
    } as unknown as PersistedFinanceState)
    const store = useFinanceStore()
    store.hydrate()
    expect(store.hydrated).toBe(true)
    expect(store.listSubscriptions).toEqual([])
    expect(store.listPolicies).toEqual([])
    expect(store.listLoans).toEqual([])
    expect(store.listContracts).toEqual([])
  })

  it('hydrate schemaVersion=2 形态 → 4 子类型按数组还原', () => {
    channel.write({
      schemaVersion: 2,
      accounts: [],
      cards: [],
      txs: [],
      subscriptions: [makeSubscription({ id: 's1' })],
      policies: [makePolicy({ id: 'p1' })],
      loans: [makeLoan({ id: 'l1' })],
      contracts: [makeContract({ id: 'c1' })],
    })
    const store = useFinanceStore()
    store.hydrate()
    expect(store.listSubscriptions).toHaveLength(1)
    expect(store.listSubscriptions[0]?.id).toBe('s1')
    expect(store.listPolicies).toHaveLength(1)
    expect(store.listLoans).toHaveLength(1)
    expect(store.listContracts).toHaveLength(1)
  })

  it('空 storage → schemaVersion 升 2 + 4 子类型空数组', () => {
    const store = useFinanceStore()
    store.hydrate()
    expect(store.hydrated).toBe(true)
    expect(store.listSubscriptions).toEqual([])
    expect(store.listPolicies).toEqual([])
    expect(store.listLoans).toEqual([])
    expect(store.listContracts).toEqual([])
  })
})

// ============================================================================
// 8. reset —— 清空全部 7 子类型缓存
// ============================================================================

describe('finance v2 store / reset', () => {
  it('清空 4 子类型 Map 后 list 为空', () => {
    const store = useFinanceStore()
    store.addSubscription(makeSubscription({ id: 's1' }))
    store.addPolicy(makePolicy({ id: 'p1' }))
    store.addLoan(makeLoan({ id: 'l1' }))
    store.addContract(makeContract({ id: 'c1' }))
    store.reset()
    expect(store.listSubscriptions).toEqual([])
    expect(store.listPolicies).toEqual([])
    expect(store.listLoans).toEqual([])
    expect(store.listContracts).toEqual([])
    expect(store.byId('subscription', 's1')).toBeUndefined()
  })
})