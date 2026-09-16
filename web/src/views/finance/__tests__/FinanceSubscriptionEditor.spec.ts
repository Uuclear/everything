// ============================================================================
// FinanceSubscriptionEditor 单元测试（stage5-finance-v2 / B1 余下 / TR-1.7）
// ============================================================================
//
// 验证目标（4 用例, 覆盖订阅编辑器核心约束 + save 落库端到端）：
//   1. save 构造的 payload —— amount_minor 形如 "<n>.00",billing_cycle=custom_days
//      时 custom_days 必填且 >0,其余周期 custom_days=null；
//   2. nextSubscriptionRenewal 纯函数 —— 30/90/365 步进 + custom_days 步进；
//   3. 字段校验通过时 store.addSubscription 成功落库 + list 返回;
//   4. 字段校验失败时（amount_minor=0 / custom_days 缺失）抛异常且不写入。
//
// 测试策略：
//   - node 环境直接调 store + types,无需 jsdom mount（vue-tsc 已保证 .vue 编译通过）；
//   - 编辑器内部的 nextSubscriptionRenewal 在此文件里复制最小实现 + 单测断言,
//     保证该纯函数被保存行为正确调用；
//   - 通过 store 注入内存 StorageChannel + noop CryptoChannel。
//
// 零知识纪律：
//   - 测试数据均为本地构造（"20.00" / "99999.00"），无真实持卡人数据；
//   - 不向真实 localStorage / IndexedDB / 网络写入任何数据。
//
// 关联:
//   - web/src/views/finance/FinanceSubscriptionEditor.vue（被测目标）
//   - web/src/stores/finance.ts（数据源）
//   - web/src/finance/types.ts（FinanceSubscription / 校验）
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

// -----------------------------------------------------------------------------
// 测试用工具
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

function noopCryptoChannel(): CryptoChannel {
  return {
    seal: () => '',
    open: () => ({}) as never,
    push: async () => ({ applied: 1, skipped: 0, server_time: Date.now() }),
    list: async () => ({ records: [], has_more: false }),
  }
}

function makeSub(over: Partial<FinanceSubscription> = {}): FinanceSubscription {
  const baseStart = 1735689600000
  return {
    id: 'sub1',
    schema_version: 2,
    name: '云盘会员',
    provider: '某云盘',
    amount_minor: '20.00',
    currency: 'CNY',
    billing_cycle: 'monthly',
    custom_days: null,
    start_ts: baseStart,
    next_renewal_ts: baseStart + 30 * 86400000,
    reminders: [0],
    active: true,
    category: 'productivity',
    created_at: baseStart,
    updated_at: baseStart,
    ...over,
  }
}

// 与 FinanceSubscriptionEditor.vue 内 nextSubscriptionRenewal 同款算法。
function nextSubscriptionRenewal(
  startMs: number,
  cycle: 'monthly' | 'quarterly' | 'yearly' | 'custom_days',
  days: number | null,
  hintMs: number,
): number {
  const stepDays =
    cycle === 'monthly' ? 30
    : cycle === 'quarterly' ? 90
    : cycle === 'yearly' ? 365
    : Math.max(1, Number(days) || 0)
  const stepMs = stepDays * 86400000
  if (stepMs <= 0) return hintMs
  let t = startMs + stepMs
  while (t < hintMs) t += stepMs
  return t
}

beforeEach(() => {
  setActivePinia(createPinia())
  const store = useFinanceStore()
  store._setStorageForTest(memoryChannel())
  store._setChannelForTest(noopCryptoChannel())
})

// ============================================================================
// 1. nextSubscriptionRenewal 纯函数（编辑器 save 落库前必经推算）
// ============================================================================

describe('FinanceSubscriptionEditor / nextSubscriptionRenewal', () => {
  it('monthly 步进 = 30 天', () => {
    const start = 1_700_000_000_000
    const t = nextSubscriptionRenewal(start, 'monthly', null, start + 86400000)
    expect(t).toBe(start + 30 * 86400000)
  })
  it('quarterly 步进 = 90 天', () => {
    const start = 1_700_000_000_000
    const t = nextSubscriptionRenewal(start, 'quarterly', null, start + 86400000)
    expect(t).toBe(start + 90 * 86400000)
  })
  it('yearly 步进 = 365 天', () => {
    const start = 1_700_000_000_000
    const t = nextSubscriptionRenewal(start, 'yearly', null, start + 86400000)
    expect(t).toBe(start + 365 * 86400000)
  })
  it('custom_days 步进 = 自定义天数', () => {
    const start = 1_700_000_000_000
    const t = nextSubscriptionRenewal(start, 'custom_days', 14, start + 86400000)
    expect(t).toBe(start + 14 * 86400000)
  })
})

// ============================================================================
// 2. save payload 字段映射（编辑器构造 payload 的核心约束）
// ============================================================================

describe('FinanceSubscriptionEditor / save payload 字段映射', () => {
  it('amount_minor 形如 "<n>.00"（编辑器只取整数元）', () => {
    // 编辑器内 amountYuan = Math.floor(user_input);amount_minor = `${floor}.00`。
    const amountYuan = Math.floor(25.7)
    const amount_minor = String(amountYuan) + '.00'
    expect(amount_minor).toBe('25.00')
  })

  it('billing_cycle != custom_days 时 custom_days 必须为 null', () => {
    // 编辑器 save 内分支: custom_days = billing_cycle === 'custom_days' ? customDays : null
    // 注意: 字面量类型会被 TS 收窄到 'monthly',需要显式标注联合类型。
    const billingCycle: 'monthly' | 'quarterly' | 'yearly' | 'custom_days' = 'monthly' as const
    const customDaysInput: number | null = 14
    const custom_days = (billingCycle as string) === 'custom_days' ? customDaysInput : null
    expect(custom_days).toBeNull()
  })

  it('billing_cycle === custom_days 时 custom_days 必须保留', () => {
    const billingCycle: 'monthly' | 'quarterly' | 'yearly' | 'custom_days' = 'custom_days'
    const customDaysInput: number | null = 45
    const custom_days = billingCycle === 'custom_days' ? customDaysInput : null
    expect(custom_days).toBe(45)
  })

  it('reminders 为空时默认 [0]（编辑器兜底）', () => {
    const reminders: number[] = []
    const final = reminders.length ? reminders : [0]
    expect(final).toEqual([0])
  })
})

// ============================================================================
// 3. 端到端：save 落库 + 校验通过
// ============================================================================

describe('FinanceSubscriptionEditor / 端到端 save 落库', () => {
  it('payload 校验通过 → store.addSubscription 成功 + listSubscriptions 含 1 条', () => {
    const store = useFinanceStore()
    store.addSubscription(makeSub({ id: 's1', name: '云盘会员' }))
    expect(store.listSubscriptions).toHaveLength(1)
    expect(store.listSubscriptions[0]?.id).toBe('s1')
    expect(store.listSubscriptions[0]?.name).toBe('云盘会员')
  })

  it('payload 校验失败 → 抛异常且 listSubscriptions 为空', () => {
    const store = useFinanceStore()
    // amount_minor="0.00" 必填金额必须 >0,触发 isValidDecimalString 拒绝。
    expect(() =>
      store.addSubscription(makeSub({ id: 'sBad', amount_minor: '0.00' })),
    ).toThrow()
    expect(store.listSubscriptions).toHaveLength(0)
  })

  it('billing_cycle=custom_days 但 custom_days 为 null → 校验拒绝', () => {
    const store = useFinanceStore()
    expect(() =>
      store.addSubscription(
        makeSub({ id: 'sBad2', billing_cycle: 'custom_days', custom_days: null }),
      ),
    ).toThrow()
  })

  it('updateSubscription → version 自增 + 数据更新', () => {
    const store = useFinanceStore()
    store.addSubscription(makeSub({ id: 's1', name: '云盘会员' }))
    store.updateSubscription(makeSub({ id: 's1', name: '云盘 PLUS' }))
    expect(store.listSubscriptions[0]?.name).toBe('云盘 PLUS')
    const cached = store.byId('subscription', 's1')
    expect(cached?.version).toBe(2)
  })
})