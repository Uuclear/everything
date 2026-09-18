// ============================================================================
// finance store B5 汇率状态单元测试（stage5-finance-v2 / B5 / FR-V2-C.1、C.2）
// ============================================================================
//
// 验证目标：
//   1. importRateTable 合法 JSON → rateTable 状态正确 + defaultCurrency 默认 CNY；
//   2. 非法 JSON / 语义非法 → ok:false 且既有状态保持不变；
//   3. setDefaultCurrency 合法接受 / 非法（小写等）拒绝；
//   4. hydrate 持久化往返（mock StorageChannel 写入再读出，B5 三字段保留）；
//   5. 旧本地数据（无 rateTable / defaultCurrency / rateRecordVersions）安全降级；
//   6. clearRateTable 移除汇率包但保留默认币种；
//   7. FR-V2-C.2 加密上行：records 通道收到 type='rate' 记录、确定性 id、
//      同生效时刻重复导入 version 严格递增；
//   8. 下行 ingest：pullAll 拉到 type='rate' 密文记录 → 解密校验 → 刷新汇率表。
//
// 测试基建照搬 store.spec.ts / store-v2.spec.ts：内存 StorageChannel +
// 可观测 CryptoChannel + beforeEach 独立 pinia。
//
// 关联:
//   - web/src/stores/finance.ts（被测目标）
//   - web/src/finance/rateTable.ts（汇率包格式真理源）
// ============================================================================

import { describe, it, expect, beforeEach } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import {
  useFinanceStore,
  type StorageChannel,
  type CryptoChannel,
  type PersistedFinanceState,
} from '../finance'
import type { RemoteRecord } from '../../api/client'

// -----------------------------------------------------------------------------
// 测试用工具 —— 内存 StorageChannel（与 store.spec.ts 同款）
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

/**
 * 占位 CryptoChannel —— 不触发真实加解密；注入仅为隔离默认通道
 * （defaultCryptoChannel 依赖 useAuthStore）。push 恒成功，list 恒空。
 */
function noopCryptoChannel(): CryptoChannel {
  return {
    seal: () => '',
    open: () => ({}) as never,
    push: async () => ({ applied: 1, skipped: 0, server_time: Date.now() }),
    list: async () => ({ records: [], has_more: false }),
  }
}

/**
 * 可观测 CryptoChannel —— 记录每次 push 的 RemoteRecord；open 返回预置明文，
 * list 返回预置远端页（供 pullAll 下行测试）。
 */
function recordingCryptoChannel(opts: {
  opens?: Record<string, unknown>
  remoteRecords?: RemoteRecord[]
}): CryptoChannel & { pushes: RemoteRecord[] } {
  const pushes: RemoteRecord[] = []
  return {
    pushes,
    // 密封不依赖 sodium：密文占位即可，下行时由 open 回调决定明文。
    seal: () => 'SEALED_RATE_CIPHERTEXT',
    open: (id) => (opts.opens?.[id] ?? {}) as never,
    push: async (record) => {
      pushes.push(record)
      return { applied: 1, skipped: 0, server_time: Date.now() }
    },
    list: async () => ({ records: opts.remoteRecords ?? [], has_more: false }),
  }
}

/** spec FR-V2-C.2 示例形态的合法汇率包 JSON。 */
const VALID_RATE_JSON = JSON.stringify({
  version: 1,
  effective_ts: 1735689600000,
  rates: {
    'EUR/CNY': 7.85,
    'USD/CNY': 7.25,
  },
})

/** 与 VALID_RATE_JSON 同生效时刻、不同汇率值（验证幂等覆盖 + version 递增）。 */
const UPDATED_RATE_JSON = JSON.stringify({
  version: 1,
  effective_ts: 1735689600000,
  rates: {
    'EUR/CNY': 7.90,
    'USD/CNY': 7.30,
  },
})

/** 汇率包确定性 records id（与 store.ratePackageId / Android 同键）。 */
const RATE_RECORD_ID = 'rate@1735689600000'

let channel: ReturnType<typeof memoryChannel>

beforeEach(() => {
  setActivePinia(createPinia())
  channel = memoryChannel()
  const store = useFinanceStore()
  store._setStorageForTest(channel)
  store._setChannelForTest(noopCryptoChannel())
})

// ============================================================================
// 1. importRateTable —— 合法 JSON 导入 + 初始默认币种
// ============================================================================

describe('finance store B5 / importRateTable', () => {
  it('合法汇率包导入成功：rateTable 状态正确，defaultCurrency 初始为 CNY', async () => {
    const store = useFinanceStore()
    // 导入前初始状态：无汇率包 + 默认 CNY。
    expect(store.rateTable).toBeNull()
    expect(store.defaultCurrency).toBe('CNY')

    const result = await store.importRateTable(VALID_RATE_JSON)

    expect(result.ok).toBe(true)
    if (!result.ok) throw new Error('前置断言：导入应当成功')
    expect(result.table.effectiveTs).toBe(1735689600000)
    expect(result.table.rates['USD/CNY']).toBe(7.25)
    // 占位通道 push 恒成功 → synced=true。
    expect(result.synced).toBe(true)
    // store 响应式状态与返回值一致。
    expect(store.rateTable).not.toBeNull()
    expect(store.rateTable?.effectiveTs).toBe(1735689600000)
    expect(store.rateTable?.rates['EUR/CNY']).toBe(7.85)
    // 导入汇率包不改变默认币种。
    expect(store.defaultCurrency).toBe('CNY')
    // 导入后即时持久化（StorageState 携带 B5 字段）。
    const snap = channel.snapshot()
    expect(snap?.rateTable).toEqual(result.table)
    expect(snap?.defaultCurrency).toBe('CNY')
    // 信封版本表同步落盘（同键 version=1）。
    expect(snap?.rateRecordVersions?.[RATE_RECORD_ID]).toBe(1)
  })
})

// ============================================================================
// 2. importRateTable —— 非法 JSON / 语义非法返回 ok:false，状态不变
// ============================================================================

describe('finance store B5 / importRateTable 失败路径', () => {
  it('JSON 语法错误 → ok:false 且中文错误，rateTable 保持 null', async () => {
    const store = useFinanceStore()
    const result = await store.importRateTable('{not-a-json')
    expect(result.ok).toBe(false)
    if (result.ok) throw new Error('前置断言：导入应当失败')
    expect(result.error).toContain('汇率包')
    expect(store.rateTable).toBeNull()
  })

  it('语义非法（effective_ts 为负）→ ok:false，既有汇率表不被覆盖', async () => {
    const store = useFinanceStore()
    // 先导入一份合法表。
    const first = await store.importRateTable(VALID_RATE_JSON)
    expect(first.ok).toBe(true)

    // 再导入语义非法包（语法合法但 effective_ts 非法）。
    const bad = JSON.stringify({
      version: 1,
      effective_ts: -1,
      rates: { 'USD/CNY': 7.25 },
    })
    const result = await store.importRateTable(bad)
    expect(result.ok).toBe(false)

    // 既有汇率表原样保留。
    expect(store.rateTable?.effectiveTs).toBe(1735689600000)
    expect(store.rateTable?.rates['USD/CNY']).toBe(7.25)
  })

  it('通道 push 抛异常 → 本地仍成功（synced=false），状态不回滚', async () => {
    const store = useFinanceStore()
    store._setChannelForTest({
      seal: () => '',
      open: () => ({}) as never,
      push: async () => {
        throw new Error('network offline')
      },
      list: async () => ({ records: [], has_more: false }),
    })

    const result = await store.importRateTable(VALID_RATE_JSON)

    expect(result.ok).toBe(true)
    if (!result.ok) throw new Error('前置断言：本地导入应当成功')
    expect(result.synced).toBe(false)
    // 本地表已落地可用于看板折算。
    expect(store.rateTable?.rates['USD/CNY']).toBe(7.25)
  })
})

// ============================================================================
// 3. setDefaultCurrency —— 合法 / 非法
// ============================================================================

describe('finance store B5 / setDefaultCurrency', () => {
  it('合法三字母大写代码 → 更新 + 持久化', () => {
    const store = useFinanceStore()
    expect(store.setDefaultCurrency('USD')).toBe(true)
    expect(store.defaultCurrency).toBe('USD')
    expect(channel.snapshot()?.defaultCurrency).toBe('USD')
  })

  it('小写 / 长度不符 / 空串 → 拒绝且状态不变', () => {
    const store = useFinanceStore()
    expect(store.setDefaultCurrency('usd')).toBe(false)
    expect(store.setDefaultCurrency('CN')).toBe(false)
    expect(store.setDefaultCurrency('CNYY')).toBe(false)
    expect(store.setDefaultCurrency('')).toBe(false)
    // 全部拒绝后仍为初始 CNY，且未产生持久化写入（channel 为空）。
    expect(store.defaultCurrency).toBe('CNY')
    expect(channel.snapshot()).toBeNull()
  })
})

// ============================================================================
// 4. hydrate 持久化往返 —— B5 三字段保留
// ============================================================================

describe('finance store B5 / hydrate 持久化往返', () => {
  it('导入汇率包 + 改默认币种后，新 store hydrate 完整还原三字段', async () => {
    const store = useFinanceStore()
    const imported = await store.importRateTable(VALID_RATE_JSON)
    expect(imported.ok).toBe(true)
    expect(store.setDefaultCurrency('EUR')).toBe(true)

    // 模拟刷新：新 pinia + 新 store 实例复用同一内存通道。
    setActivePinia(createPinia())
    const restored = useFinanceStore()
    restored._setStorageForTest(channel)
    restored._setChannelForTest(noopCryptoChannel())
    restored.hydrate()

    expect(restored.defaultCurrency).toBe('EUR')
    expect(restored.rateTable).not.toBeNull()
    expect(restored.rateTable?.effectiveTs).toBe(1735689600000)
    expect(restored.rateTable?.rates['USD/CNY']).toBe(7.25)
    expect(restored.rateTable?.rates['EUR/CNY']).toBe(7.85)
    // 信封版本表也还原（重复导入时 version 从 2 起算）。
    expect(restored.rateRecordVersions[RATE_RECORD_ID]).toBe(1)
  })
})

// ============================================================================
// 5. 旧本地数据兼容 —— 无 B5 字段时安全降级
// ============================================================================

describe('finance store B5 / 旧数据降级', () => {
  it('schemaVersion=2 但缺 B5 三字段 → null + CNY + 空版本表', () => {
    channel.write({
      schemaVersion: 2,
      accounts: [],
      cards: [],
      txs: [],
      subscriptions: [],
      policies: [],
      loans: [],
      contracts: [],
    })

    const store = useFinanceStore()
    store.hydrate()

    expect(store.rateTable).toBeNull()
    expect(store.defaultCurrency).toBe('CNY')
    expect(store.rateRecordVersions).toEqual({})
  })

  it('持久化的 defaultCurrency 非法（小写）→ 降级 CNY', () => {
    channel.write({
      schemaVersion: 2,
      accounts: [],
      cards: [],
      txs: [],
      subscriptions: [],
      policies: [],
      loans: [],
      contracts: [],
      rateTable: null,
      defaultCurrency: 'jpy',
    })

    const store = useFinanceStore()
    store.hydrate()

    expect(store.defaultCurrency).toBe('CNY')
    expect(store.rateTable).toBeNull()
  })

  it('rateRecordVersions 被篡改（坏 key / 负数 / 字符串）→ 非法条目丢弃', () => {
    channel.write({
      schemaVersion: 2,
      accounts: [],
      cards: [],
      txs: [],
      subscriptions: [],
      policies: [],
      loans: [],
      contracts: [],
      rateRecordVersions: {
        [RATE_RECORD_ID]: 3,
        'not-a-rate-key': 1,
        'rate@123': -2,
        'rate@456': 'x',
      } as unknown as Record<string, number>,
    })

    const store = useFinanceStore()
    store.hydrate()

    expect(store.rateRecordVersions).toEqual({ [RATE_RECORD_ID]: 3 })
  })
})

// ============================================================================
// 6. clearRateTable —— 移除汇率包，保留默认币种
// ============================================================================

describe('finance store B5 / clearRateTable', () => {
  it('移除后 rateTable=null 并持久化；defaultCurrency 保留', async () => {
    const store = useFinanceStore()
    expect((await store.importRateTable(VALID_RATE_JSON)).ok).toBe(true)
    expect(store.setDefaultCurrency('HKD')).toBe(true)

    store.clearRateTable()

    expect(store.rateTable).toBeNull()
    expect(store.defaultCurrency).toBe('HKD')
    const snap = channel.snapshot()
    expect(snap?.rateTable).toBeNull()
    expect(snap?.defaultCurrency).toBe('HKD')
  })
})

// ============================================================================
// 7. FR-V2-C.2 加密上行 —— records 通道 type='rate' + 版本递增
// ============================================================================

describe('finance store B5 / 汇率包 records 通道加密上行', () => {
  it('导入 → push 一条 module=finance/type=rate 记录，确定性 id + version=1', async () => {
    const crypto = recordingCryptoChannel({})
    const store = useFinanceStore()
    store._setChannelForTest(crypto)

    const result = await store.importRateTable(VALID_RATE_JSON)
    expect(result.ok).toBe(true)

    expect(crypto.pushes).toHaveLength(1)
    const rec = crypto.pushes[0]
    expect(rec.id).toBe(RATE_RECORD_ID)
    expect(rec.module).toBe('finance')
    expect(rec.type).toBe('rate')
    expect(rec.version).toBe(1)
    expect(rec.deleted).toBe(false)
    expect(rec.ciphertext).toBe('SEALED_RATE_CIPHERTEXT')
  })

  it('同生效时刻重复导入 → version 递增到 2 且仍只两条 push（幂等覆盖语义）', async () => {
    const crypto = recordingCryptoChannel({})
    const store = useFinanceStore()
    store._setChannelForTest(crypto)

    await store.importRateTable(VALID_RATE_JSON)
    const updated = await store.importRateTable(UPDATED_RATE_JSON)
    expect(updated.ok).toBe(true)

    expect(crypto.pushes).toHaveLength(2)
    expect(crypto.pushes[0].version).toBe(1)
    expect(crypto.pushes[1].version).toBe(2)
    expect(crypto.pushes[1].id).toBe(RATE_RECORD_ID)
    // 本地表取新值。
    expect(store.rateTable?.rates['USD/CNY']).toBe(7.3)
    // 版本表持久化为最新 2。
    expect(channel.snapshot()?.rateRecordVersions?.[RATE_RECORD_ID]).toBe(2)
  })
})

// ============================================================================
// 8. 下行 ingest —— pullAll 拉到 type='rate' 密文记录刷新汇率表
// ============================================================================

describe('finance store B5 / 汇率包下行 ingest', () => {
  it('pullAll 收到 type=rate 记录 → open 解密校验 → rateTable 更新 + 版本表记录', async () => {
    // 远端密文记录；open 按 id 返回合法汇率包明文（模拟解密成功）。
    const remote: RemoteRecord = {
      id: RATE_RECORD_ID,
      module: 'finance',
      type: 'rate',
      ciphertext: 'REMOTE_CIPHER',
      version: 4,
      device_id: 'android',
      created_at: 1735600000000,
      updated_at: 1735689600000,
      deleted: false,
    }
    const crypto = recordingCryptoChannel({
      opens: { [RATE_RECORD_ID]: JSON.parse(VALID_RATE_JSON) },
      remoteRecords: [remote],
    })
    const store = useFinanceStore()
    store._setChannelForTest(crypto)

    await store.pullAll(0)

    expect(store.rateTable).not.toBeNull()
    expect(store.rateTable?.effectiveTs).toBe(1735689600000)
    expect(store.rateTable?.rates['EUR/CNY']).toBe(7.85)
    expect(store.rateRecordVersions[RATE_RECORD_ID]).toBe(4)
    // 下行后落盘。
    expect(channel.snapshot()?.rateTable?.effectiveTs).toBe(1735689600000)
  })

  it('pullAll 收到损坏的 type=rate 记录（open 抛错）→ 静默跳过不影响其他记录', async () => {
    const remote: RemoteRecord = {
      id: RATE_RECORD_ID,
      module: 'finance',
      type: 'rate',
      ciphertext: 'BROKEN',
      version: 1,
      device_id: 'android',
      created_at: 1,
      updated_at: 2,
      deleted: false,
    }
    const crypto = recordingCryptoChannel({
      opens: {}, // 未预置 → open 返回 {} → parseRateTable 必拒（rates 缺失）
      remoteRecords: [remote],
    })
    const store = useFinanceStore()
    store._setChannelForTest(crypto)

    await store.pullAll(0)

    expect(store.rateTable).toBeNull()
  })
})
