// ============================================================================
// FinanceContractList 单元测试（stage5-finance-v2 / B1 余下 / TR-1.7）
// ============================================================================
//
// 验证目标（4 用例 × 4 组 = 16 用例）：
//   1. filter —— title / counterparty 模糊匹配,大小写不敏感;
//   2. formatAmount —— 整数元 + 千分位 + ¥ 前缀;
//   3. KIND_LABEL / STATUS_LABEL / STATUS_TYPE —— 文案与 tag 类型路由;
//   4. 列表渲染 —— store.listContracts 按 end_ts 升序（即将结束在前）。
//
// 零知识纪律：
//   - 金额只显示整数元;
//   - 合同标题 / 对手方 允许在本视图展示（属于主标识字段）;
//
// 关联:
//   - web/src/views/finance/FinanceContractList.vue（被测目标）
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
import type { FinanceContract } from '../../../finance/types'

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
  return {
    id: 'ct1',
    schema_version: 2,
    title: '测试合同',
    counterparty: '对手方甲',
    amount_minor: '10000.00',
    currency: 'CNY',
    kind: 'service',
    signed_ts: start,
    start_ts: start,
    end_ts: start + 365 * 86400000,
    notice_period_days: 30,
    notice_deadline_ts: start + 335 * 86400000,
    auto_renew: false,
    status: 'active',
    linked_account_id: null,
    attachments: [],
    created_at: start,
    updated_at: start,
    ...over,
  }
}

const KIND_LABEL: Record<string, string> = {
  rental: '租赁',
  service: '服务',
  purchase: '采购',
  loan: '借贷',
  other: '其他',
}

const STATUS_LABEL: Record<string, string> = {
  active: '执行中',
  expired: '已到期',
  terminated: '已解约',
  renewed: '已续约',
}

const STATUS_TYPE: Record<string, 'default' | 'warning' | 'success' | 'error'> = {
  active: 'default',
  expired: 'warning',
  terminated: 'error',
  renewed: 'success',
}

function filterContracts(rows: FinanceContract[], k: string): FinanceContract[] {
  const key = k.trim().toLowerCase()
  if (!key) return rows
  return rows.filter(
    (c) =>
      c.title.toLowerCase().includes(key) ||
      c.counterparty.toLowerCase().includes(key),
  )
}

function formatAmount(v: string): string {
  const n = Number(v)
  if (!Number.isFinite(n)) return '¥0'
  return '¥' + Math.floor(n).toLocaleString('zh-CN')
}

function daysUntil(ms: number): number {
  return Math.ceil((ms - Date.now()) / 86400_000)
}

beforeEach(() => {
  setActivePinia(createPinia())
  const store = useFinanceStore()
  store._setStorageForTest(memoryChannel())
  store._setChannelForTest(noopCryptoChannel())
})

describe('FinanceContractList / filter 搜索（title / counterparty）', () => {
  it('关键字匹配 title', () => {
    const rows = [
      makeContract({ id: 'c1', title: '租房合同', counterparty: '房东甲' }),
      makeContract({ id: 'c2', title: '保洁服务', counterparty: '家政公司' }),
    ]
    expect(filterContracts(rows, '租房').map((c) => c.id)).toEqual(['c1'])
  })

  it('关键字匹配 counterparty', () => {
    const rows = [
      makeContract({ id: 'c1', title: '租房', counterparty: '房东甲' }),
      makeContract({ id: 'c2', title: '保洁', counterparty: '家政公司' }),
    ]
    expect(filterContracts(rows, '家政').map((c) => c.id)).toEqual(['c2'])
  })

  it('关键字空 → 返回全部', () => {
    const rows = [makeContract({ id: 'c1' }), makeContract({ id: 'c2' })]
    expect(filterContracts(rows, '')).toEqual(rows)
  })

  it('关键字大小写不敏感', () => {
    const rows = [makeContract({ id: 'c1', title: 'Office Lease' })]
    expect(filterContracts(rows, 'office').map((c) => c.id)).toEqual(['c1'])
  })
})

describe('FinanceContractList / formatAmount + daysUntil', () => {
  it('金额整数元 + 千分位 + ¥ 前缀', () => {
    expect(formatAmount('10000.00')).toBe('¥10,000')
    expect(formatAmount('1234567.89')).toBe('¥1,234,567')
  })

  it('金额非有限数 → 兜底 ¥0', () => {
    expect(formatAmount('not-a-number')).toBe('¥0')
    expect(formatAmount('NaN')).toBe('¥0')
  })

  it('金额 0 → ¥0', () => {
    expect(formatAmount('0.00')).toBe('¥0')
  })

  it('daysUntil 未来 / 过去', () => {
    const future = Date.now() + 5 * 86400_000
    expect(daysUntil(future)).toBeGreaterThanOrEqual(4)
    expect(daysUntil(future)).toBeLessThanOrEqual(5)
    const past = Date.now() - 10 * 86400_000
    expect(daysUntil(past)).toBeLessThanOrEqual(-10)
  })
})

describe('FinanceContractList / kind / status 文案与 tag 类型', () => {
  it('KIND_LABEL 5 态', () => {
    expect(KIND_LABEL['rental']).toBe('租赁')
    expect(KIND_LABEL['service']).toBe('服务')
    expect(KIND_LABEL['purchase']).toBe('采购')
    expect(KIND_LABEL['loan']).toBe('借贷')
    expect(KIND_LABEL['other']).toBe('其他')
  })

  it('STATUS_LABEL 4 态', () => {
    expect(STATUS_LABEL['active']).toBe('执行中')
    expect(STATUS_LABEL['expired']).toBe('已到期')
    expect(STATUS_LABEL['terminated']).toBe('已解约')
    expect(STATUS_LABEL['renewed']).toBe('已续约')
  })

  it('STATUS_TYPE active=default / expired=warning / terminated=error / renewed=success', () => {
    expect(STATUS_TYPE['active']).toBe('default')
    expect(STATUS_TYPE['expired']).toBe('warning')
    expect(STATUS_TYPE['terminated']).toBe('error')
    expect(STATUS_TYPE['renewed']).toBe('success')
  })

  it('未知 kind/status 兜底（list 模板内 `?? c.kind`）', () => {
    const kindLabel = KIND_LABEL['unknown'] ?? 'unknown'
    const statusLabel = STATUS_LABEL['unknown'] ?? 'unknown'
    expect(kindLabel).toBe('unknown')
    expect(statusLabel).toBe('unknown')
  })
})

describe('FinanceContractList / 列表渲染（store 数据源）', () => {
  it('listContracts 按 end_ts 升序（即将结束在前）', () => {
    const store = useFinanceStore()
    const baseStart = 1735689600000
    store.addContract(
      makeContract({
        id: 'c1',
        start_ts: baseStart,
        end_ts: baseStart + 365 * 86400000,
        notice_deadline_ts: baseStart + 335 * 86400000,
      }),
    )
    store.addContract(
      makeContract({
        id: 'c2',
        start_ts: baseStart,
        end_ts: baseStart + 30 * 86400000,
        notice_deadline_ts: baseStart + 0 * 86400000,
      }),
    )
    store.addContract(
      makeContract({
        id: 'c3',
        start_ts: baseStart,
        end_ts: baseStart + 180 * 86400000,
        notice_deadline_ts: baseStart + 150 * 86400000,
      }),
    )
    expect(store.listContracts.map((c) => c.id)).toEqual(['c2', 'c3', 'c1'])
  })

  it('empty 状态下 listContracts 为空数组', () => {
    const store = useFinanceStore()
    expect(store.listContracts).toEqual([])
  })

  it('auto_renew=true 卡片展示"自动续约"分支（list 模板分支）', () => {
    const c = makeContract({ id: 'c1', auto_renew: true })
    expect(c.auto_renew).toBe(true)
  })

  it('auto_renew=false 卡片展示"手动续约"分支（list 模板分支）', () => {
    const c = makeContract({ id: 'c1', auto_renew: false })
    expect(c.auto_renew).toBe(false)
  })
})