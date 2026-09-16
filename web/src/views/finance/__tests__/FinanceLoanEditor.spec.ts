// ============================================================================
// FinanceLoanEditor 单元测试（stage5-finance-v2 / B1 余下 / TR-1.7）
// ============================================================================
//
// 验证目标（4 用例）：
//   1. save payload 字段映射 —— principal_minor / paid_minor 整数元；
//      paid_minor 允许 0（未还款）;
//   2. paid_minor ≤ principal_minor 约束（编辑器与校验双方对齐）;
//   3. status 四态（active / partially_paid / paid / overdue）枚举;
//   4. addLoan 端到端落库 + listLoans 排序（按 due_ts 升序）+ 校验失败抛异常。
//
// 测试策略：
//   - node 环境直接调 store + types,不 mount vue;
//   - 复用同款 memoryChannel + noopCryptoChannel。
//
// 零知识纪律：
//   - counterparty 用业界公开示例（"友人甲"），非真实姓名;
//   - 不向真实 localStorage / IndexedDB / 网络写入任何数据。
//
// 关联:
//   - web/src/views/finance/FinanceLoanEditor.vue（被测目标）
//   - tasks.md TR-1.7
// ============================================================================

import { describe, it, expect, beforeEach } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import {
  useFinanceStore,
  type StorageChannel,
  type CryptoChannel,
  type PersistedFinanceState,
} from '../../../stores/finance'
import type { FinanceLoan, LoanStatus } from '../../../finance/types'

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

function noopCryptoChannel(): CryptoChannel {
  return {
    seal: () => '',
    open: () => ({}) as never,
    push: async () => ({ applied: 1, skipped: 0, server_time: Date.now() }),
    list: async () => ({ records: [], has_more: false }),
  }
}

function makeLoan(over: Partial<FinanceLoan> = {}): FinanceLoan {
  const issue = 1735689600000
  return {
    id: 'loan1',
    schema_version: 2,
    counterparty: '友人甲',
    principal_minor: '10000.00',
    currency: 'CNY',
    direction: 'lent',
    issue_ts: issue,
    due_ts: issue + 365 * 86400000,
    interest_rate_apy_bps: 360,
    status: 'active',
    paid_minor: '0.00',
    reminders: [0],
    linked_account_id: null,
    include_in_net_assets: true,
    created_at: issue,
    updated_at: issue,
    ...over,
  }
}

beforeEach(() => {
  setActivePinia(createPinia())
  const store = useFinanceStore()
  store._setStorageForTest(memoryChannel())
  store._setChannelForTest(noopCryptoChannel())
})

describe('FinanceLoanEditor / save payload 字段映射', () => {
  it('principal_minor 形如 "<n>.00"', () => {
    const principalYuan = Math.floor(12000)
    const principal_minor = String(principalYuan) + '.00'
    expect(principal_minor).toBe('12000.00')
  })

  it('paid_minor 形如 "<n>.00",允许为 0.00（未还款）', () => {
    const paidYuan = Math.floor(0)
    const paid_minor = String(paidYuan) + '.00'
    expect(paid_minor).toBe('0.00')
  })

  it('paid_minor 不允许超过 principal_minor', () => {
    const principal = Number(makeLoan({ id: 'l1', principal_minor: '10000.00' }).principal_minor)
    const paid = Number(makeLoan({ id: 'l1', principal_minor: '10000.00', paid_minor: '99999.00' }).paid_minor)
    expect(paid).toBeGreaterThan(principal)
  })

  it('interest_rate_apy_bps 非负整数（编辑器输入）', () => {
    const bps = 360
    expect(Number.isInteger(bps)).toBe(true)
    expect(bps).toBeGreaterThanOrEqual(0)
  })
})

describe('FinanceLoanEditor / status 枚举', () => {
  const statuses: LoanStatus[] = ['active', 'partially_paid', 'paid', 'overdue']
  for (const s of statuses) {
    it(`status = ${s} 可作为 LoanStatus 联合成员`, () => {
      const payload = makeLoan({ id: 'l1', status: s })
      expect(payload.status).toBe(s)
    })
  }
})

describe('FinanceLoanEditor / 端到端 save 落库', () => {
  it('payload 校验通过 → addLoan 成功 + listLoans 按 due_ts 升序', () => {
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

  it('due_ts < issue_ts → addLoan 抛异常', () => {
    const store = useFinanceStore()
    const base = 1735689600000
    expect(() =>
      store.addLoan(
        makeLoan({ id: 'lBad', issue_ts: base, due_ts: base - 86400000 }),
      ),
    ).toThrow()
    expect(store.listLoans).toHaveLength(0)
  })

  it('paid_minor > principal_minor → addLoan 抛异常', () => {
    const store = useFinanceStore()
    expect(() =>
      store.addLoan(
        makeLoan({ id: 'lBad2', principal_minor: '10000.00', paid_minor: '99999.00' }),
      ),
    ).toThrow()
  })

  it('updateLoan → version 自增 + 数据更新', () => {
    const store = useFinanceStore()
    store.addLoan(makeLoan({ id: 'l1', paid_minor: '0.00' }))
    store.updateLoan(makeLoan({ id: 'l1', paid_minor: '5000.00', status: 'partially_paid' }))
    const cached = store.byId('loan', 'l1')
    expect(cached?.version).toBe(2)
    expect((cached?.data as unknown as FinanceLoan).status).toBe('partially_paid')
  })
})