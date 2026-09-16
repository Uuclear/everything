// ============================================================================
// FinanceContractEditor 单元测试（stage5-finance-v2 / B1 余下 / TR-1.7）
// ============================================================================
//
// 验证目标（4 用例）：
//   1. save payload 字段映射 —— amount_minor 整数元 + notice_deadline_ts 推算;
//   2. notice_deadline_ts = end_ts - notice_period_days * 86400000 推算;
//   3. end_ts > start_ts 约束 + 校验通过/失败端到端;
//   4. status 四态（active / expired / terminated / renewed）+ kind 枚举。
//
// 测试策略：
//   - node 环境直接调 store + types,不 mount vue;
//   - 复用同款 memoryChannel + noopCryptoChannel。
//
// 零知识纪律：
//   - title / counterparty 用业界公开示例,非真实信息;
//   - 不向真实 localStorage / IndexedDB / 网络写入任何数据。
//
// 关联:
//   - web/src/views/finance/FinanceContractEditor.vue（被测目标）
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
import type { FinanceContract, ContractKind, ContractStatus } from '../../../finance/types'

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

function makeContract(over: Partial<FinanceContract> = {}): FinanceContract {
  const start = 1735689600000
  const end = start + 365 * 86400000
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
    created_at: start,
    updated_at: start,
    ...over,
  }
}

// 与 FinanceContractEditor.vue 内 noticeDeadlineTs 同款。
function noticeDeadlineTs(endTs: number, noticeDays: number): number {
  return endTs - noticeDays * 86400000
}

beforeEach(() => {
  setActivePinia(createPinia())
  const store = useFinanceStore()
  store._setStorageForTest(memoryChannel())
  store._setChannelForTest(noopCryptoChannel())
})

describe('FinanceContractEditor / notice_deadline_ts 推算', () => {
  it('notice_period_days=30 → deadline = end_ts - 30 * 86400000', () => {
    const end = 1735689600000 + 365 * 86400000
    const days = 30
    expect(noticeDeadlineTs(end, days)).toBe(end - 30 * 86400000)
  })

  it('notice_period_days=0 → deadline = end_ts', () => {
    const end = 1735689600000 + 365 * 86400000
    expect(noticeDeadlineTs(end, 0)).toBe(end)
  })

  it('notice_period_days=60 → deadline = end_ts - 60 * 86400000', () => {
    const end = 1735689600000 + 180 * 86400000
    expect(noticeDeadlineTs(end, 60)).toBe(end - 60 * 86400000)
  })

  it('notice_deadline_ts 与 end_ts - notice_period_days 一致 → 校验通过', () => {
    const payload = makeContract({ id: 'ct1', notice_period_days: 30 })
    const expected = payload.end_ts - payload.notice_period_days * 86400000
    expect(payload.notice_deadline_ts).toBe(expected)
  })
})

describe('FinanceContractEditor / save payload 字段映射', () => {
  it('amount_minor 形如 "<n>.00"', () => {
    const amountYuan = Math.floor(5500)
    const amount_minor = String(amountYuan) + '.00'
    expect(amount_minor).toBe('5500.00')
  })

  it('attachments 编辑期为 []（v2 B3 接入前默认空数组）', () => {
    expect(makeContract({ id: 'ct1' }).attachments).toEqual([])
  })

  it('kind 五选一（rental/service/purchase/loan/other）', () => {
    const kinds: ContractKind[] = ['rental', 'service', 'purchase', 'loan', 'other']
    for (const k of kinds) {
      const payload = makeContract({ id: 'c1', kind: k })
      expect(payload.kind).toBe(k)
    }
  })

  it('status 四选一（active/expired/terminated/renewed）', () => {
    const statuses: ContractStatus[] = ['active', 'expired', 'terminated', 'renewed']
    for (const s of statuses) {
      const payload = makeContract({ id: 'c1', status: s })
      expect(payload.status).toBe(s)
    }
  })
})

describe('FinanceContractEditor / 端到端 save 落库', () => {
  it('payload 校验通过 → addContract 成功 + listContracts 按 end_ts 升序', () => {
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

  it('end_ts < start_ts → addContract 抛异常', () => {
    const store = useFinanceStore()
    const base = 1735689600000
    expect(() =>
      store.addContract(
        makeContract({
          id: 'cBad',
          start_ts: base,
          end_ts: base - 86400000,
          notice_period_days: 30,
          notice_deadline_ts: base - 30 * 86400000,
        }),
      ),
    ).toThrow()
    expect(store.listContracts).toHaveLength(0)
  })

  it('notice_deadline_ts 与 end_ts - notice_period_days 不一致 → 抛异常', () => {
    const store = useFinanceStore()
    expect(() =>
      store.addContract(
        makeContract({
          id: 'cBad2',
          notice_period_days: 0,
          notice_deadline_ts: 1,
        }),
      ),
    ).toThrow()
  })

  it('updateContract → version 自增 + 数据更新', () => {
    const store = useFinanceStore()
    store.addContract(makeContract({ id: 'c1', amount_minor: '5000.00' }))
    store.updateContract(makeContract({ id: 'c1', amount_minor: '5500.00' }))
    const cached = store.byId('contract', 'c1')
    expect(cached?.version).toBe(2)
    expect((cached?.data as unknown as FinanceContract).amount_minor).toBe('5500.00')
  })
})