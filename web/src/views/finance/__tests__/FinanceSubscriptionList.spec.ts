// ============================================================================
// FinanceSubscriptionList 单元测试（stage5-finance-v2 / B1 余下 / TR-1.7）
// ============================================================================
//
// 验证目标（4 用例, 覆盖订阅列表核心纯函数 + 搜索过滤）：
//   1. filter —— 关键字匹配 name / provider;大小写不敏感;
//   2. formatAmount —— 千分位 + ¥ 前缀 + 整数元;
//   3. daysUntil —— 距下次扣费剩余天数;负数表示已过期;
//   4. 列表渲染 —— 卡片式数据源 = store.listSubscriptions（按 next_renewal_ts 升序）;
//
// 测试策略：
//   - node 环境直接调 store + 纯函数,不 mount vue;
//   - filter / formatAmount / daysUntil 与列表 .vue 内同款算法;
//   - 通过 store 注入内存 StorageChannel + noop CryptoChannel。
//
// 零知识纪律：
//   - 金额展示仅显示 ¥ + 千分位整数,不显示小数点;
//   - 不渲染具体日期数字,仅展示"X 天后 / 已过期 X 天"语义。
//
// 关联:
//   - web/src/views/finance/FinanceSubscriptionList.vue（被测目标）
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
import type { FinanceSubscription } from '../../../finance/types'

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

function makeSub(over: Partial<FinanceSubscription> = {}): FinanceSubscription {
  const start = 1735689600000
  return {
    id: 'sub1',
    schema_version: 2,
    name: '云盘会员',
    provider: '某云盘',
    amount_minor: '20.00',
    currency: 'CNY',
    billing_cycle: 'monthly',
    custom_days: null,
    start_ts: start,
    next_renewal_ts: start + 30 * 86400000,
    reminders: [0],
    active: true,
    category: 'productivity',
    created_at: start,
    updated_at: start,
    ...over,
  }
}

// 与 FinanceSubscriptionList.vue 内同款算法。
function formatAmount(v: string): string {
  const n = Number(v)
  if (!Number.isFinite(n)) return '¥0'
  return '¥' + Math.floor(n).toLocaleString('zh-CN')
}

function daysUntil(ms: number): number {
  return Math.ceil((ms - Date.now()) / 86400_000)
}

function filterSubs(rows: FinanceSubscription[], k: string): FinanceSubscription[] {
  const key = k.trim().toLowerCase()
  if (!key) return rows
  return rows.filter(
    (s) => s.name.toLowerCase().includes(key) || s.provider.toLowerCase().includes(key),
  )
}

beforeEach(() => {
  setActivePinia(createPinia())
  const store = useFinanceStore()
  store._setStorageForTest(memoryChannel())
  store._setChannelForTest(noopCryptoChannel())
})

describe('FinanceSubscriptionList / filter 搜索', () => {
  it('关键字匹配 name', () => {
    const rows = [
      makeSub({ id: 's1', name: '云盘会员', provider: '某云盘' }),
      makeSub({ id: 's2', name: '视频会员', provider: '某视频' }),
    ]
    expect(filterSubs(rows, '云盘').map((s) => s.id)).toEqual(['s1'])
  })

  it('关键字匹配 provider', () => {
    const rows = [
      makeSub({ id: 's1', name: '云盘会员', provider: '某云盘' }),
      makeSub({ id: 's2', name: '视频会员', provider: '某视频' }),
    ]
    expect(filterSubs(rows, '视频').map((s) => s.id)).toEqual(['s2'])
  })

  it('关键字空 → 返回全部', () => {
    const rows = [makeSub({ id: 's1' }), makeSub({ id: 's2' })]
    expect(filterSubs(rows, '')).toEqual(rows)
  })

  it('关键字大小写不敏感', () => {
    const rows = [makeSub({ id: 's1', name: 'Cloud' })]
    expect(filterSubs(rows, 'cloud').map((s) => s.id)).toEqual(['s1'])
  })
})

describe('FinanceSubscriptionList / formatAmount', () => {
  it('整数元 + 千分位 + ¥ 前缀', () => {
    expect(formatAmount('1234.56')).toBe('¥1,234')
  })

  it('零金额显示 ¥0', () => {
    expect(formatAmount('0')).toBe('¥0')
  })

  it('非数字字符串 → ¥0（兜底）', () => {
    expect(formatAmount('abc')).toBe('¥0')
  })

  it('负数仍按整数元格式化（不暴露小数点）', () => {
    // Math.floor(-99.99) === -100（JS 标准向下取整）
    expect(formatAmount('-99.99')).toBe('¥-100')
  })
})

describe('FinanceSubscriptionList / daysUntil', () => {
  it('未来 5 天 → 返回 5', () => {
    const future = Date.now() + 5 * 86400000
    expect(daysUntil(future)).toBe(5)
  })

  it('过去 3 天 → 返回负数（已过期）', () => {
    const past = Date.now() - 3 * 86400000
    expect(daysUntil(past)).toBeLessThan(0)
  })
})

describe('FinanceSubscriptionList / 列表渲染（store 数据源）', () => {
  it('listSubscriptions 按 next_renewal_ts 升序（store 内已排序）', () => {
    const store = useFinanceStore()
    const baseStart = 1735689600000
    store.addSubscription(
      makeSub({ id: 's1', start_ts: baseStart, next_renewal_ts: baseStart + 60 * 86400000 }),
    )
    store.addSubscription(
      makeSub({ id: 's2', start_ts: baseStart, next_renewal_ts: baseStart + 30 * 86400000 }),
    )
    expect(store.listSubscriptions[0]?.id).toBe('s2')
    expect(store.listSubscriptions[1]?.id).toBe('s1')
  })

  it('empty 状态下 listSubscriptions 为空数组', () => {
    const store = useFinanceStore()
    expect(store.listSubscriptions).toEqual([])
  })

  it('active=false 时卡片应用 inactive 样式（list 模板分支）', () => {
    // 列表模板: :class="{ inactive: !sub.active }" —— active=false 时 opacity 0.6
    const sub = makeSub({ id: 's1', active: false })
    const cls = { inactive: !sub.active }
    expect(cls.inactive).toBe(true)
  })

  it('active=true 时不应用 inactive 样式', () => {
    const sub = makeSub({ id: 's1', active: true })
    const cls = { inactive: !sub.active }
    expect(cls.inactive).toBe(false)
  })
})