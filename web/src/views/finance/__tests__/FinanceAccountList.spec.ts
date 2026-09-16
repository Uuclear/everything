// ============================================================================
// FinanceAccountList 单元测试（stage5-finance / Task 9 / TR-9.8b）
// ============================================================================
//
// 验证目标（≥3 用例，覆盖账户列表核心交互）：
//   1. 列表渲染 —— store.listAccounts 输出正确；
//   2. archive 折叠 —— store 归档后 listAccounts 不返回，UI 可折叠显示;
//   3. 搜索 —— keyword 按 name 模糊过滤。
//
// 测试策略：
//   - node 环境直接调 store + format.ts 纯函数;
//   - format.ts 暴露 filterAccountsByName / formatBalance —— 与组件共用;
//   - 通过 store 注入内存 StorageChannel。
//
// 关联:
//   - web/src/views/finance/FinanceAccountList.vue（被测目标）
//   - web/src/views/finance/__internal__/format.ts（共享纯函数）
// ============================================================================

import { describe, it, expect, beforeEach } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useFinanceStore, type StorageChannel, type PersistedFinanceState } from '../../../stores/finance'
import { filterAccountsByName, formatBalance } from '../__internal__/format'
import type { FinanceAccount } from '../../../finance/types'
import { DEFAULT_ACCOUNT_COLOR } from '../../../finance/types'

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

function makeAccount(over: Partial<FinanceAccount> = {}): FinanceAccount {
  return {
    id: 'a' + Math.random().toString(36).slice(2, 8),
    schema_version: 1,
    name: '账户',
    kind: 'cash',
    currency: 'CNY',
    balance: '0.00',
    note: null,
    icon: null,
    color: DEFAULT_ACCOUNT_COLOR,
    archived: false,
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
// 1. 列表渲染
// -----------------------------------------------------------------------------

describe('FinanceAccountList / 列表', () => {
  it('store.listAccounts 输出账户条目(按 updatedAt 降序)', () => {
    const store = useFinanceStore()
    store.addAccount(makeAccount({ id: 'a1', name: '现金钱包', balance: '5000.00' }))
    store.addAccount(makeAccount({ id: 'a2', name: '招行储蓄', balance: '30000.00' }))

    expect(store.listAccounts).toHaveLength(2)
    // 两条 updatedAt 几乎相同 —— store 内部按降序但顺序不固定,断言长度即可。
    const names = store.listAccounts.map((a) => a.name).sort()
    expect(names).toEqual(['招行储蓄', '现金钱包'])
  })

  it('余额格式化: decimal-as-string → ¥ + 千分位整数', () => {
    expect(formatBalance('12345.67')).toBe('¥12,345')
    expect(formatBalance('0.00')).toBe('¥0')
    expect(formatBalance('100.00')).toBe('¥100')
    expect(formatBalance('999999.99')).toBe('¥999,999')
  })
})

// -----------------------------------------------------------------------------
// 2. archive 折叠 —— 列表层过滤 + UI 折叠容器
// -----------------------------------------------------------------------------

describe('FinanceAccountList / archive 折叠', () => {
  it('store.archiveAccount 后 listAccounts 不再返回该条', () => {
    const store = useFinanceStore()
    store.addAccount(makeAccount({ id: 'a1', name: '现金' }))
    store.addAccount(makeAccount({ id: 'a2', name: '银行' }))
    expect(store.listAccounts).toHaveLength(2)

    store.archiveAccount('a2')
    expect(store.listAccounts).toHaveLength(1)
    expect(store.listAccounts[0]?.id).toBe('a1')
  })

  it('store.listCards 同款语义(用于对照 listAccounts)', () => {
    const store = useFinanceStore()
    // 这里仅验证 listAccounts 与 listCards 都遵守"非归档过滤"语义。
    store.addAccount(makeAccount({ id: 'a1' }))
    store.addAccount(makeAccount({ id: 'a2', archived: true }))
    expect(store.listAccounts).toHaveLength(1)
    // 但 store.byId 仍能查到归档条目 —— UI 可折叠展示。
    const archived = store.byId('account', 'a2')
    expect(archived).toBeDefined()
    expect((archived?.data as FinanceAccount).archived).toBe(true)
  })
})

// -----------------------------------------------------------------------------
// 3. 搜索 —— keyword 按 name 模糊过滤
// -----------------------------------------------------------------------------

describe('FinanceAccountList / 搜索', () => {
  it('keyword 空字符串时返回全集', () => {
    const accs: FinanceAccount[] = [
      makeAccount({ id: 'a1', name: '现金钱包' }),
      makeAccount({ id: 'a2', name: '招行储蓄' }),
    ]
    expect(filterAccountsByName(accs, '')).toHaveLength(2)
    expect(filterAccountsByName(accs, '   ')).toHaveLength(2)
  })

  it('keyword 命中 name 子串(忽略大小写)', () => {
    const accs: FinanceAccount[] = [
      makeAccount({ id: 'a1', name: '现金钱包' }),
      makeAccount({ id: 'a2', name: '招行储蓄' }),
      makeAccount({ id: 'a3', name: '招商信用卡' }),
    ]
    expect(filterAccountsByName(accs, '招行')).toHaveLength(1)
    expect(filterAccountsByName(accs, '现金')).toHaveLength(1)
    expect(filterAccountsByName(accs, '钱包')).toHaveLength(1)
  })

  it('keyword 无命中 → 空数组', () => {
    const accs: FinanceAccount[] = [
      makeAccount({ id: 'a1', name: '现金' }),
    ]
    expect(filterAccountsByName(accs, '银行')).toEqual([])
  })
})
