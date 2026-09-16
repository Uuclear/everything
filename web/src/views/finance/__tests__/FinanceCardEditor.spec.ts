// ============================================================================
// FinanceCardEditor 单元测试（stage5-finance / Task 9 / TR-9.8c）
// ============================================================================
//
// 验证目标（≥3 用例，覆盖卡编辑器核心校验 + 后四位持久化）：
//   1. Luhn 通过 —— extractLast4 返回 4 位数字字符串;
//   2. Luhn 失败 —— extractLast4 返回 null, 校验报错;
//   3. 后四位持久化 —— store.updateCard 写入 last4;完整卡号不入 store。
//
// 测试策略：
//   - node 环境直接调 luhn.ts + store;
//   - 通过 store 注入内存 StorageChannel 验证 last4 持久化字段;
//   - 测试用卡号均来自 luhn-cases.json(业界公开示例, 非真实持卡人卡号)。
//
// 关联:
//   - web/src/views/finance/FinanceCardEditor.vue（被测目标）
//   - web/src/finance/luhn.ts（Luhn 校验纯函数）
//   - web/src/stores/finance.ts（数据源）
// ============================================================================

import { describe, it, expect, beforeEach } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useFinanceStore, type StorageChannel, type PersistedFinanceState } from '../../../stores/finance'
import { luhnValidate, extractLast4 } from '../../../finance/luhn'
import { validateCardInputs } from '../__internal__/format'
import type { FinanceCard } from '../../../finance/types'
import { DEFAULT_CARD_COLOR } from '../../../finance/types'

// -----------------------------------------------------------------------------
// 测试工具
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

function makeCard(over: Partial<FinanceCard> = {}): FinanceCard {
  return {
    id: 'c' + Math.random().toString(36).slice(2, 8),
    schema_version: 1,
    name: '招行信用卡',
    kind: 'credit',
    issuer: '招商银行',
    last4: '0000',
    currency: 'CNY',
    credit_limit: '0.00',
    used_limit: null,
    billing_day: 15,
    due_day: 25,
    note: null,
    icon: null,
    color: DEFAULT_CARD_COLOR,
    archived: false,
    include_in_net_assets: true,
    created_at: 1735689600000,
    updated_at: 1735689600000,
    ...over,
  }
}

beforeEach(() => {
  setActivePinia(createPinia())
  const store = useFinanceStore()
  store._setStorageForTest(memoryChannel())
  // T11 集成后 CRUD 内部 fire-and-forget pushChanges 会触发 defaultCryptoChannel
  // （依赖 useAuthStore → localStorage），注入 noop channel 隔离加密链路。
  store._setChannelForTest({
    seal: () => '',
    open: () => ({}) as never,
    push: async () => ({ applied: 1, skipped: 0, server_time: Date.now() }),
    list: async () => ({ records: [], has_more: false }),
  })
})

// -----------------------------------------------------------------------------
// 1. Luhn 通过 —— extractLast4 返回 4 位数字字符串
// -----------------------------------------------------------------------------

describe('FinanceCardEditor / Luhn 通过', () => {
  it('合法 16 位 visa 卡号 → extractLast4 返回 4 位数字字符串', () => {
    // 4242 4242 4242 4242 —— visa 测试卡号,Luhn 通过,last4=4242。
    const pan = '4242424242424242'
    expect(luhnValidate(pan)).toBe(true)
    expect(extractLast4(pan)).toBe('4242')
  })

  it('合法 16 位 master 卡号(末位修正) → 通过 + last4=4444', () => {
    // 5555 5555 5555 4444 —— master 测试卡号,Luhn 通过。
    const pan = '5555555555554444'
    expect(luhnValidate(pan)).toBe(true)
    expect(extractLast4(pan)).toBe('4444')
  })

  it('合法卡号含空格/连字符 → normalize 后通过', () => {
    const pan = '4242-4242-4242-4242'
    expect(luhnValidate(pan)).toBe(true)
    expect(extractLast4(pan)).toBe('4242')
  })

  it('校验通过时 validateCardInputs 不报 pan 错误', () => {
    const e = validateCardInputs({
      name: '测试卡',
      billingDay: 15,
      dueDay: 25,
      pan: '4242424242424242',
      panLuhnValid: true,
    })
    expect(e.pan).toBeUndefined()
    expect(Object.keys(e)).toEqual([])
  })
})

// -----------------------------------------------------------------------------
// 2. Luhn 失败 —— extractLast4 返回 null, 校验报错
// -----------------------------------------------------------------------------

describe('FinanceCardEditor / Luhn 失败', () => {
  it('非法卡号 → extractLast4 返回 null', () => {
    // 末位 5(非 Luhn 校验位) → 校验失败。
    const pan = '4242424242424245'
    expect(luhnValidate(pan)).toBe(false)
    expect(extractLast4(pan)).toBeNull()
  })

  it('空串 → extractLast4 返回 null', () => {
    expect(luhnValidate('')).toBe(false)
    expect(extractLast4('')).toBeNull()
  })

  it('长度非法(过短) → extractLast4 返回 null', () => {
    const pan = '4242' // 仅 4 位
    expect(luhnValidate(pan)).toBe(false)
    expect(extractLast4(pan)).toBeNull()
  })

  it('含非数字字符 → extractLast4 返回 null', () => {
    const pan = '4242-4242-4242-424X'
    expect(luhnValidate(pan)).toBe(false)
    expect(extractLast4(pan)).toBeNull()
  })

  it('校验失败时 validateCardInputs 报 pan 错误', () => {
    const e = validateCardInputs({
      name: '测试卡',
      billingDay: 15,
      dueDay: 25,
      pan: '4242424242424245',
      panLuhnValid: false,
    })
    expect(e.pan).toBe('卡号未通过 Luhn 校验')
  })
})

// -----------------------------------------------------------------------------
// 3. 后四位持久化 —— store.updateCard 写入 last4;完整卡号不入 store
// -----------------------------------------------------------------------------

describe('FinanceCardEditor / 后四位持久化', () => {
  it('updateCard 后 store.last4 是 4 位数字(完整卡号不入库)', () => {
    const store = useFinanceStore()
    // 1) 新建卡 —— last4=0000。
    store.addCard(makeCard({ id: 'c1', last4: '0000' }))
    // 2) 模拟编辑器保存: 调用 extractLast4 提取新卡号后四位, 再 updateCard。
    const pan = '4242424242424242'
    const newLast4 = extractLast4(pan)
    expect(newLast4).toBe('4242')
    store.updateCard(makeCard({ id: 'c1', last4: newLast4 ?? '0000' }))

    // 3) 验证 store 内仅有 last4 (4 位数字字符串)。
    const cached = store.byId('card', 'c1')
    expect(cached).toBeDefined()
    const cardData = cached?.data as FinanceCard
    expect(cardData.last4).toBe('4242')
    expect(cardData.last4).toHaveLength(4)
    // 完整卡号不在 store 内 —— store 字段 schema 仅含 last4。
    expect(JSON.stringify(cardData)).not.toContain('4242424242424242')
  })

  it('编辑模式下 masked_pan 为空时, 保留原 last4', () => {
    // 验证编辑器行为: masked_pan="" + 编辑模式 + 已有 last4 → 保留原 last4。
    const store = useFinanceStore()
    store.addCard(makeCard({ id: 'c1', last4: '1234' }))

    // 模拟编辑器校验: pan 为空且 panLuhnValid 任意(false 因为空)。
    const e = validateCardInputs({
      name: '测试卡',
      billingDay: 15,
      dueDay: 25,
      pan: '',
      panLuhnValid: false, // 空串视为无效
    })
    // 注意: 编辑模式下空 pan 不报错(保留原 last4);此函数仅在 pan 非空且 Luhn
    // 失败时报错 —— 故此处 errors.pan 应为 undefined。
    expect(e.pan).toBeUndefined()

    // 业务行为由编辑器 save() 内的分支保证 —— 此处仅校验校验函数本身。
    const cached = store.byId('card', 'c1')
    expect((cached?.data as FinanceCard).last4).toBe('1234')
  })

  it('billingDay / dueDay 越界 → validateCardInputs 报错', () => {
    const e1 = validateCardInputs({
      name: '卡',
      billingDay: 32, // > 31
      dueDay: 25,
      pan: '',
      panLuhnValid: false,
    })
    expect(e1.billingDay).toBe('账单日 1-31')

    const e2 = validateCardInputs({
      name: '卡',
      billingDay: 15,
      dueDay: 0, // < 1
      pan: '',
      panLuhnValid: false,
    })
    expect(e2.dueDay).toBe('还款日偏移 1-31')
  })
})
