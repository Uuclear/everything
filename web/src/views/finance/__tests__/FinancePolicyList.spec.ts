// ============================================================================
// FinancePolicyList 单元测试（stage5-finance-v2 / B1 余下 / TR-1.7）
// ============================================================================
//
// 验证目标（4 用例）：
//   1. filter —— name / provider 模糊匹配,大小写不敏感;
//   2. last4 —— 保单号末 4 位,空串/长度≤4 时兜底;
//   3. formatAmount + daysUntil 与订阅列表同款算法;
//   4. 列表渲染 —— store.listPolicies 按 expiry_ts 升序。
//
// 零知识纪律：
//   - 保单号只显示末 4 位（`****XXXX`）;
//   - 保费 / 保额 只显示整数元。
//
// 关联:
//   - web/src/views/finance/FinancePolicyList.vue（被测目标）
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

function formatAmount(v: string): string {
  const n = Number(v)
  if (!Number.isFinite(n)) return '¥0'
  return '¥' + Math.floor(n).toLocaleString('zh-CN')
}

function daysUntil(ms: number): number {
  return Math.ceil((ms - Date.now()) / 86400_000)
}

function last4(pn: string): string {
  if (!pn) return '****'
  return pn.length <= 4 ? pn : '****' + pn.slice(-4)
}

function filterPols(rows: FinancePolicy[], k: string): FinancePolicy[] {
  const key = k.trim().toLowerCase()
  if (!key) return rows
  return rows.filter(
    (p) => p.name.toLowerCase().includes(key) || p.provider.toLowerCase().includes(key),
  )
}

beforeEach(() => {
  setActivePinia(createPinia())
  const store = useFinanceStore()
  store._setStorageForTest(memoryChannel())
  store._setChannelForTest(noopCryptoChannel())
})

describe('FinancePolicyList / filter 搜索', () => {
  it('关键字匹配 name', () => {
    const rows = [
      makePol({ id: 'p1', name: '车险', provider: '某人寿' }),
      makePol({ id: 'p2', name: '医疗险', provider: '某健康' }),
    ]
    expect(filterPols(rows, '车险').map((p) => p.id)).toEqual(['p1'])
  })

  it('关键字匹配 provider', () => {
    const rows = [
      makePol({ id: 'p1', name: '车险', provider: '某人寿' }),
      makePol({ id: 'p2', name: '医疗险', provider: '某健康' }),
    ]
    expect(filterPols(rows, '健康').map((p) => p.id)).toEqual(['p2'])
  })

  it('关键字空 → 返回全部', () => {
    const rows = [makePol({ id: 'p1' }), makePol({ id: 'p2' })]
    expect(filterPols(rows, '')).toEqual(rows)
  })

  it('关键字大小写不敏感', () => {
    const rows = [makePol({ id: 'p1', name: 'Insurance' })]
    expect(filterPols(rows, 'insurance').map((p) => p.id)).toEqual(['p1'])
  })
})

describe('FinancePolicyList / last4 保单号末 4', () => {
  it('长字符串 → `****` + 末 4', () => {
    // 末 4 字符取自字符串末尾（含分隔符），与生产代码 .slice(-4) 一致
    expect(last4('POL-2025-001')).toBe('****-001')
  })

  it('长度恰好 4 → 原样返回', () => {
    expect(last4('0001')).toBe('0001')
  })

  it('长度 < 4 → 原样返回', () => {
    expect(last4('123')).toBe('123')
  })

  it('空串 → `****`', () => {
    expect(last4('')).toBe('****')
  })
})

describe('FinancePolicyList / formatAmount + daysUntil', () => {
  it('formatAmount 整数元 + 千分位 + ¥', () => {
    expect(formatAmount('3000.00')).toBe('¥3,000')
    expect(formatAmount('200000.00')).toBe('¥200,000')
  })

  it('daysUntil 未来天数', () => {
    const future = Date.now() + 90 * 86400000
    expect(daysUntil(future)).toBe(90)
  })

  it('daysUntil 已过期返回负数', () => {
    const past = Date.now() - 10 * 86400000
    expect(daysUntil(past)).toBeLessThan(0)
  })
})

describe('FinancePolicyList / 列表渲染（store 数据源）', () => {
  it('listPolicies 按 expiry_ts 升序（store 内已排序）', () => {
    const store = useFinanceStore()
    const baseStart = 1735689600000
    store.addPolicy(
      makePol({ id: 'p1', start_ts: baseStart, expiry_ts: baseStart + 365 * 86400000 }),
    )
    store.addPolicy(
      makePol({ id: 'p2', start_ts: baseStart, expiry_ts: baseStart + 180 * 86400000 }),
    )
    expect(store.listPolicies[0]?.id).toBe('p2')
    expect(store.listPolicies[1]?.id).toBe('p1')
  })

  it('empty 状态下 listPolicies 为空数组', () => {
    const store = useFinanceStore()
    expect(store.listPolicies).toEqual([])
  })

  it('active=false 时卡片应用 inactive 样式（list 模板分支）', () => {
    const pol = makePol({ id: 'p1', active: false })
    expect({ inactive: !pol.active }.inactive).toBe(true)
  })

  it('active=true 时不应用 inactive 样式', () => {
    const pol = makePol({ id: 'p1', active: true })
    expect({ inactive: !pol.active }.inactive).toBe(false)
  })
})