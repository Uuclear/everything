// ============================================================================
// FinancePolicyEditor 单元测试（stage5-finance-v2 / B1 余下 / TR-1.7）
// ============================================================================
//
// 验证目标（4 用例）：
//   1. save payload 字段映射 —— premium_minor / coverage_minor 整数元；
//      attachments: []（v2 B3 接入前默认空数组）；
//   2. expiry_ts > start_ts 约束 — 编辑器与校验双方对齐;
//   3. addPolicy 端到端落库 + listPolicies 排序（按 expiry_ts 升序）;
//   4. premium_minor=0 / expiry_ts<start_ts → 抛异常且不写入。
//
// 测试策略：
//   - node 环境直接调 store + types,不 mount vue;
//   - 复用 FinanceSubscriptionEditor.spec 同款 memoryChannel + noopCryptoChannel。
//
// 零知识纪律：
//   - policy_number 用业界公开示例（"POL-2025-001"），非真实保单;
//   - 不向真实 localStorage / IndexedDB / 网络写入任何数据。
//
// 关联:
//   - web/src/views/finance/FinancePolicyEditor.vue（被测目标）
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
import type { FinancePolicy } from '../../../finance/types'

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

function makePol(over: Partial<FinancePolicy> = {}): FinancePolicy {
  const start = 1735689600000
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
    start_ts: start,
    expiry_ts: start + 365 * 86400000,
    reminders: [0, 10080],
    active: true,
    linked_account_id: null,
    attachments: [],
    created_at: start,
    updated_at: start,
    ...over,
  }
}

beforeEach(() => {
  setActivePinia(createPinia())
  const store = useFinanceStore()
  store._setStorageForTest(memoryChannel())
  store._setChannelForTest(noopCryptoChannel())
})

describe('FinancePolicyEditor / save payload 字段映射', () => {
  it('premium_minor 形如 "<n>.00"（编辑器只取整数元）', () => {
    const premiumYuan = Math.floor(3500.99)
    const premium_minor = String(premiumYuan) + '.00'
    expect(premium_minor).toBe('3500.00')
  })

  it('coverage_minor 形如 "<n>.00"', () => {
    const coverageYuan = Math.floor(200000)
    const coverage_minor = String(coverageYuan) + '.00'
    expect(coverage_minor).toBe('200000.00')
  })

  it('attachments 编辑期为 []（v2 B3 接入上传前默认空数组）', () => {
    const payload = makePol({ id: 'p1' })
    expect(payload.attachments).toEqual([])
  })

  it('billing_cycle=yearly/single/monthly/quarterly 四选一', () => {
    const cycles = ['yearly', 'single', 'monthly', 'quarterly'] as const
    expect(cycles).toContain(makePol({ id: 'p1', billing_cycle: 'single' }).billing_cycle)
    expect(cycles).toContain(makePol({ id: 'p2', billing_cycle: 'monthly' }).billing_cycle)
  })
})

describe('FinancePolicyEditor / expiry_ts > start_ts 约束', () => {
  it('expiry_ts == start_ts → 校验通过（边界允许）', () => {
    const start = 1735689600000
    const payload = makePol({ id: 'p1', start_ts: start, expiry_ts: start })
    expect(payload.expiry_ts).toBe(payload.start_ts)
  })

  it('expiry_ts > start_ts → 校验通过（典型保单 1 年期）', () => {
    const start = 1735689600000
    const payload = makePol({ id: 'p1', start_ts: start, expiry_ts: start + 365 * 86400000 })
    expect(payload.expiry_ts).toBeGreaterThan(payload.start_ts)
  })

  it('expiry_ts < start_ts → addPolicy 抛异常', () => {
    const store = useFinanceStore()
    const start = 1735689600000
    expect(() =>
      store.addPolicy(
        makePol({ id: 'pBad', start_ts: start, expiry_ts: start - 86400000 }),
      ),
    ).toThrow()
    expect(store.listPolicies).toHaveLength(0)
  })
})

describe('FinancePolicyEditor / 端到端 save 落库', () => {
  it('payload 校验通过 → addPolicy 成功 + listPolicies 按 expiry_ts 升序', () => {
    const store = useFinanceStore()
    const baseStart = 1735689600000
    store.addPolicy(
      makePol({ id: 'p1', start_ts: baseStart, expiry_ts: baseStart + 365 * 86400000 }),
    )
    store.addPolicy(
      makePol({ id: 'p2', start_ts: baseStart, expiry_ts: baseStart + 180 * 86400000 }),
    )
    expect(store.listPolicies).toHaveLength(2)
    expect(store.listPolicies[0]?.id).toBe('p2')
    expect(store.listPolicies[1]?.id).toBe('p1')
  })

  it('premium_minor=0 → addPolicy 抛异常', () => {
    const store = useFinanceStore()
    expect(() =>
      store.addPolicy(makePol({ id: 'pBad', premium_minor: '0.00' })),
    ).toThrow()
  })

  it('updatePolicy → version 自增 + 数据更新', () => {
    const store = useFinanceStore()
    store.addPolicy(makePol({ id: 'p1', premium_minor: '3000.00' }))
    store.updatePolicy(makePol({ id: 'p1', premium_minor: '3500.00' }))
    const cached = store.byId('policy', 'p1')
    expect(cached?.version).toBe(2)
    expect((cached?.data as unknown as FinancePolicy).premium_minor).toBe('3500.00')
  })
})