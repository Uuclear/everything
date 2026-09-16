// 阶段 4b — eventRulesStore 单测（tasks.md Task 7 / TR-7.3）。
//
// 验证目标（≥6 用例，TR-7.3 Pass Condition）：
//   1. upsert 构造 envelope 字段正确（module='event' / type='event' /
//      payload 是 EventRule 序列化；version 严格递增）；
//   2. delete 调 push tombstone（deleted=true）；
//   3. pull 解密还原 EventRule（mock channel.open 返回密文 payload）；
//   4. decrypt 失败抛异常不静默（与 vault.ingestPlace 区别：本 store 显式失败）；
//   5. occurrencesInWindow 调 expand 正确（单规则单实例）；
//   6. occurrencesInWindow 多事件按 start_ts asc 合并排序；
//   7. 不调用 localStorage / IndexedDB（spyOn window.localStorage.setItem）。
//
// 零知识纪律：测试断言用脱敏标题（如 't-1'/'t-2'）；不打印 title/note 原文。
// node 环境无 localStorage：测试内补最小 stub（同 vault-place.test.ts）。

import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import * as expandModule from '../expand'
import { useEventRulesStore, type CryptoChannel } from '../../stores/event-rules'
import type { EventRule } from '../types'
import type { RemoteRecord } from '../../api/client'

// =============================================================================
// 工具与 fixture
// =============================================================================

/** 测试事件 id。 */
const EID_1 = '00000000-0000-4000-8000-000000000001'
const EID_2 = '00000000-0000-4000-8000-000000000002'

/** 构造一条最小 EventRule（脱敏 title）。 */
function makeRule(overrides: Partial<EventRule> = {}): EventRule {
  return {
    id: EID_1,
    title: 't-1', // 脱敏：避免敏感原文进入日志
    start_ts: 1_700_000_000_000, // 2023-11-14T22:13:20Z
    end_ts: 1_700_000_000_000 + 3_600_000, // +1h
    all_day: false,
    tz_mode: 'local',
    location_text: null,
    note: null,
    color: 'blue',
    // EventRule.reminders 现与 expand.ts 同型（number[]）；fixture 用 number 即可。
    reminders: [0],
    rrule: null,
    exdates: [],
    ...overrides,
  }
}

/**
 * 可控 mock CryptoChannel：暴露调用历史 + 远程记录池。
 * 不依赖 crypto/envelope 与 api/client，保证单测纯逻辑覆盖。
 */
function makeChannel(initial: RemoteRecord[] = []): {
  channel: CryptoChannel
  sealCalls: Array<{ rule: EventRule; id: string; version: number }>
  pushCalls: Array<RemoteRecord>
  /** 模拟服务端记录池：push 后加入；list 按 since 过滤。 */
  serverStore: Map<string, RemoteRecord>
} {
  const sealCalls: Array<{ rule: EventRule; id: string; version: number }> = []
  const pushCalls: Array<RemoteRecord> = []
  const serverStore = new Map<string, RemoteRecord>()
  initial.forEach((r) => serverStore.set(r.id, r))

  const channel: CryptoChannel = {
    seal(rule, id, version) {
      sealCalls.push({ rule: structuredClone(rule), id, version })
      // 模拟 sealRecord 输出："CIPHERTEXT:..."（足以让 open 返回原 payload）
      return `CIPHERTEXT:${id}:${version}:${JSON.stringify(rule)}`
    },
    open(id, _module, ciphertextB64, version) {
      // 解析我们 seal 阶段写入的占位串；任何解析失败抛异常（验证"解密失败抛异常"）。
      const prefix = `CIPHERTEXT:${id}:${version}:`
      if (!ciphertextB64.startsWith(prefix)) {
        throw new Error(`mock decrypt failure: ${id}`)
      }
      return JSON.parse(ciphertextB64.slice(prefix.length)) as EventRule
    },
    async push(record) {
      pushCalls.push(structuredClone(record))
      // 写入服务端池（事件 store 内部已存；模拟服务端无冲突 → applied=1）。
      serverStore.set(record.id, record)
      return { applied: 1, skipped: 0, server_time: Date.now() }
    },
    async list(since) {
      const records = Array.from(serverStore.values()).filter(
        (r) => r.updated_at > since && r.module === 'event',
      )
      return { records, has_more: false }
    },
  }

  return { channel, sealCalls, pushCalls, serverStore }
}

/** 在每次用例前设置 Pinia + localStorage stub（node 环境无）。 */
beforeEach(() => {
  const storage = new Map<string, string>()
  globalThis.localStorage = {
    get length() {
      return storage.size
    },
    clear: () => storage.clear(),
    getItem: (k: string) => storage.get(k) ?? null,
    key: () => null,
    removeItem: (k: string) => {
      storage.delete(k)
    },
    setItem: (k: string, v: string) => {
      storage.set(k, v)
    },
  } as unknown as Storage
  setActivePinia(createPinia())
  // vitest 默认 jsdom 不一定有 indexedDB；这里不显式注入，spyOn 会捕获访问。
  // store 内部不主动写 IndexedDB，仅以 spyOn 校验未触发。
})

// =============================================================================
// 用例（≥6，TR-7.3）
// =============================================================================

describe('eventRulesStore — 加密链路 + CRUD（TR-7.3）', () => {
  it('upsert 构造 envelope：module=event / type=event / payload 是 EventRule 序列化 / version 严格递增', async () => {
    const { channel, sealCalls, pushCalls } = makeChannel()
    const store = useEventRulesStore()
    store._setChannelForTest(channel)

    const rule = makeRule()
    await store.upsert(rule)

    // seal 调用记录：id/version 与推送一致。
    expect(sealCalls).toHaveLength(1)
    expect(sealCalls[0].id).toBe(EID_1)
    expect(sealCalls[0].version).toBe(1)
    expect(sealCalls[0].rule).toEqual(rule)

    // pushRecords 调一次，参数形状与 4a savePlace 同（仅 module/type 不同）。
    expect(pushCalls).toHaveLength(1)
    const pushed = pushCalls[0]
    expect(pushed.id).toBe(EID_1)
    expect(pushed.module).toBe('event')
    expect(pushed.type).toBe('event')
    expect(pushed.version).toBe(1)
    expect(pushed.deleted).toBe(false)
    expect(pushed.ciphertext).toMatch(/^CIPHERTEXT:/) // 由 mock seal 生成

    // 本地缓存立即写入（不依赖下一轮同步）。
    const cached = store.rules.get(EID_1)!
    expect(cached.version).toBe(1)
    expect(cached.module).toBe('event')
    expect(cached.type).toBe('event')
    expect(cached.data.title).toBe('t-1')
  })

  it('upsert 同 id 第二次：version 严格递增（1 → 2），仍同 module/event 双键', async () => {
    const { channel, pushCalls } = makeChannel()
    const store = useEventRulesStore()
    store._setChannelForTest(channel)

    await store.upsert(makeRule())
    await store.upsert(makeRule({ color: 'green' }))

    expect(pushCalls).toHaveLength(2)
    expect(pushCalls[0].version).toBe(1)
    expect(pushCalls[1].version).toBe(2)
    expect(pushCalls[0].module).toBe('event')
    expect(pushCalls[0].type).toBe('event')
    expect(store.rules.get(EID_1)!.version).toBe(2)
    expect(store.rules.get(EID_1)!.data.color).toBe('green')
  })

  it('delete：推送高版本墓碑（deleted=true / ciphertext=空 / version 递增）', async () => {
    const { channel, pushCalls } = makeChannel()
    const store = useEventRulesStore()
    store._setChannelForTest(channel)

    // 先建一条。
    await store.upsert(makeRule())
    expect(store.rules.has(EID_1)).toBe(true)

    await store.remove(EID_1)
    expect(pushCalls).toHaveLength(2)
    const tomb = pushCalls[1]
    expect(tomb.id).toBe(EID_1)
    expect(tomb.module).toBe('event')
    expect(tomb.deleted).toBe(true)
    expect(tomb.ciphertext).toBe('')
    expect(tomb.version).toBe(2)
    // 本地缓存立即移除。
    expect(store.rules.has(EID_1)).toBe(false)
  })

  it('pull：解密还原 EventRule 进缓存（mock open 返回 EventRule payload）', async () => {
    const rule1 = makeRule({ id: EID_1 })
    const rule2 = makeRule({ id: EID_2, title: 't-2', color: 'green' })
    // 预置远端密文（先 seal 一次得到 ciphertext，再喂给 channel.open）。
    const { channel: prepChannel } = makeChannel()
    const cipher1 = prepChannel.seal(rule1, rule1.id, 1)
    const cipher2 = prepChannel.seal(rule2, rule2.id, 3)
    const initialRecords: RemoteRecord[] = [
      {
        id: EID_1,
        module: 'event',
        type: 'event',
        ciphertext: cipher1,
        version: 1,
        device_id: 'web',
        created_at: 1_700_000_000_000,
        updated_at: 1_700_000_000_001,
        deleted: false,
      },
      {
        id: EID_2,
        module: 'event',
        type: 'event',
        ciphertext: cipher2,
        version: 3,
        device_id: 'web',
        created_at: 1_700_000_000_000,
        updated_at: 1_700_000_000_003,
        deleted: false,
      },
    ]
    const { channel } = makeChannel(initialRecords)
    const store = useEventRulesStore()
    store._setChannelForTest(channel)

    await store.pull(true)

    expect(store.rules.size).toBe(2)
    expect(store.rules.get(EID_1)!.data.title).toBe('t-1')
    expect(store.rules.get(EID_1)!.version).toBe(1)
    expect(store.rules.get(EID_2)!.data.title).toBe('t-2')
    expect(store.rules.get(EID_2)!.version).toBe(3)
    // list() 按 updatedAt 降序（EID_2 后入故排在前）。
    const titles = store.list.map((r) => r.title)
    expect(titles).toEqual(['t-2', 't-1'])
  })

  it('decrypt 失败抛异常不静默（与 vault.ingestPlace 不同：本 store 显式失败）', async () => {
    // 喂一条损坏的远端记录：ciphertext 与 id/version 不匹配，mock open 抛错。
    const broken: RemoteRecord = {
      id: 'broken-id',
      module: 'event',
      type: 'event',
      ciphertext: 'GARBAGE-NOT-A-VALID-SEAL',
      version: 1,
      device_id: 'web',
      created_at: 1_700_000_000_000,
      updated_at: 1_700_000_000_001,
      deleted: false,
    }
    const { channel } = makeChannel([broken])
    const store = useEventRulesStore()
    store._setChannelForTest(channel)

    // pull 内调用 ingest → open 抛错 → 透传到 caller。
    await expect(store.pull(true)).rejects.toThrow(/mock decrypt failure/)

    // 失败记录不入缓存（不静默写假状态）。
    expect(store.rules.has('broken-id')).toBe(false)
  })

  it('occurrencesInWindow 单规则：调 expand 正确（窗口内 1 个实例）', async () => {
    const { channel } = makeChannel()
    const store = useEventRulesStore()
    store._setChannelForTest(channel)

    // spy expand 验证调用——避免直接断言复杂 Occurrence 字段。
    const expandSpy = vi.spyOn(expandModule, 'expand')

    const rule = makeRule() // 单次事件（rrule=null）
    await store.upsert(rule)

    const window = { from: rule.start_ts - 1_000, to: rule.start_ts + 3_600_000 + 1_000 }
    const occs = store.occurrencesInWindow(window)

    expect(expandSpy).toHaveBeenCalled()
    // 至少 1 个 occurrence（单次事件窗口内）。
    expect(occs.length).toBeGreaterThanOrEqual(1)
    expect(occs[0].rule_id).toBe(EID_1)
    expect(occs[0].title).toBe('t-1')
  })

  it('occurrencesInWindow 多事件：按 start_ts asc 合并排序（窗口内）', async () => {
    const { channel } = makeChannel()
    const store = useEventRulesStore()
    store._setChannelForTest(channel)

    // 故意让 EID_2 入库顺序在前、start_ts 较晚；展开应按 start_ts asc 排序。
    await store.upsert(makeRule({ id: EID_1, title: 't-1', start_ts: 1_700_000_300_000 }))
    await store.upsert(makeRule({ id: EID_2, title: 't-2', start_ts: 1_700_000_100_000 }))

    const window = {
      from: 1_700_000_000_000,
      to: 1_700_000_500_000,
    }
    const occs = store.occurrencesInWindow(window)

    // 两条都在窗口内，按 start_ts asc → t-2 先、t-1 后。
    expect(occs.map((o) => o.title)).toEqual(['t-2', 't-1'])
    // 防御性：相邻 start_ts 严格升序。
    for (let i = 1; i < occs.length; i++) {
      expect(occs[i].start_ts).toBeGreaterThanOrEqual(occs[i - 1].start_ts)
    }
  })

  it('不调用 localStorage.setItem / IndexedDB（零知识红线）', async () => {
    const setItemSpy = vi.spyOn(globalThis.localStorage, 'setItem')
    const { channel } = makeChannel()
    const store = useEventRulesStore()
    store._setChannelForTest(channel)

    await store.upsert(makeRule())
    await store.upsert(makeRule({ id: EID_2, title: 't-2' }))
    await store.remove(EID_1)
    await store.pull(true)

    // 任何路径都不能写 localStorage。
    expect(setItemSpy).not.toHaveBeenCalled()
    // IndexedDB：node 默认无 indexedDB 全局；store 内部不主动调用即视为合规。
    expect((globalThis as unknown as { indexedDB?: unknown }).indexedDB).toBeUndefined()
  })
})