// 阶段 5 — itemsStore 单测（tasks.md Task 7 / TR-7.3）

import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { MS_PER_DAY } from '../warranty'
import type { Item } from '../types'
import type { RemoteRecord } from '../../api/client'
import { useItemsStore, type ItemsCryptoChannel } from '../../stores/items'

const IID_1 = '00000000-0000-4000-8000-000000000101'
const IID_2 = '00000000-0000-4000-8000-000000000102'

function makeItem(overrides: Partial<Item> = {}): Item {
  const now = 1_700_000_000_000
  return {
    id: IID_1,
    name: 'n-1',
    category: 'electronics',
    tags: ['tag-a'],
    purchase_date: now,
    purchase_price_cents: 100,
    currency: 'CNY',
    warranty_duration_days: 30,
    warranty_until_ts: now + 30 * MS_PER_DAY,
    created_ts: now,
    updated_ts: now,
    ...overrides,
  }
}

function makeChannel(initial: RemoteRecord[] = []) {
  const sealCalls: Array<{ item: Item; id: string; version: number }> = []
  const pushCalls: Array<RemoteRecord> = []
  const serverStore = new Map<string, RemoteRecord>()
  initial.forEach((r) => serverStore.set(r.id, r))

  const channel: ItemsCryptoChannel = {
    seal(item, id, version) {
      sealCalls.push({ item: structuredClone(item), id, version })
      return `CIPHERTEXT:${id}:${version}:${JSON.stringify(item)}`
    },
    open(id, _module, ciphertextB64, version) {
      const prefix = `CIPHERTEXT:${id}:${version}:`
      if (!ciphertextB64.startsWith(prefix)) {
        throw new Error(`mock decrypt failure: ${id}`)
      }
      return JSON.parse(ciphertextB64.slice(prefix.length)) as Item
    },
    async push(record) {
      pushCalls.push(structuredClone(record))
      serverStore.set(record.id, record)
      return { applied: 1, skipped: 0, server_time: Date.now() }
    },
    async list(since) {
      const records = Array.from(serverStore.values()).filter(
        (r) => r.updated_at > since && r.module === 'item',
      )
      return { records, has_more: false }
    },
  }

  return { channel, sealCalls, pushCalls, serverStore }
}

beforeEach(() => {
  const storage = new Map<string, string>()
  globalThis.localStorage = {
    get length() {
      return storage.size
    },
    clear: () => storage.clear(),
    getItem: (k: string) => storage.get(k) ?? null,
    key: () => null,
    removeItem: (k: string) => storage.delete(k),
    setItem: (k: string, v: string) => storage.set(k, v),
  } as unknown as Storage
  setActivePinia(createPinia())
})

describe('itemsStore — 加密链路 + CRUD（TR-7.3）', () => {
  it('upsert：module=item / type=item / version=1', async () => {
    const { channel, pushCalls } = makeChannel()
    const store = useItemsStore()
    store._setChannelForTest(channel)

    await store.upsert(makeItem())

    expect(pushCalls).toHaveLength(1)
    expect(pushCalls[0].module).toBe('item')
    expect(pushCalls[0].type).toBe('item')
    expect(pushCalls[0].version).toBe(1)
    expect(store.byId(IID_1)?.data.name).toBe('n-1')
  })

  it('upsert 同 id：version 递增', async () => {
    const { channel, pushCalls } = makeChannel()
    const store = useItemsStore()
    store._setChannelForTest(channel)

    await store.upsert(makeItem())
    await store.upsert(makeItem({ name: 'n-2' }))

    expect(pushCalls[1].version).toBe(2)
    expect(store.byId(IID_1)!.data.name).toBe('n-2')
  })

  it('remove：墓碑 deleted=true', async () => {
    const { channel, pushCalls } = makeChannel()
    const store = useItemsStore()
    store._setChannelForTest(channel)

    await store.upsert(makeItem())
    await store.remove(IID_1)

    expect(pushCalls[1].deleted).toBe(true)
    expect(pushCalls[1].ciphertext).toBe('')
    expect(store.items.has(IID_1)).toBe(false)
  })

  it('pull：解密还原 Item', async () => {
    const item = makeItem({ id: IID_2, name: 'n-x' })
    const { channel: prep } = makeChannel()
    const cipher = prep.seal(item, item.id, 1)
    const remote: RemoteRecord = {
      id: IID_2,
      module: 'item',
      type: 'item',
      ciphertext: cipher,
      version: 1,
      device_id: 'android',
      created_at: item.created_ts,
      updated_at: item.updated_ts,
      deleted: false,
    }
    const { channel } = makeChannel([remote])
    const store = useItemsStore()
    store._setChannelForTest(channel)

    await store.pull(true)

    expect(store.list).toHaveLength(1)
    expect(store.list[0].id).toBe(IID_2)
    expect(store.list[0].name).toBe('n-x')
  })

  it('解密失败抛异常不静默', async () => {
    const remote: RemoteRecord = {
      id: IID_1,
      module: 'item',
      type: 'item',
      ciphertext: 'BAD',
      version: 1,
      device_id: 'web',
      created_at: 1,
      updated_at: 2,
      deleted: false,
    }
    const { channel } = makeChannel([remote])
    const store = useItemsStore()
    store._setChannelForTest(channel)

    await expect(store.pull(true)).rejects.toThrow(/mock decrypt/)
  })

  it('byCategory / searchByTag 客户端筛选', async () => {
    const { channel } = makeChannel()
    const store = useItemsStore()
    store._setChannelForTest(channel)

    await store.upsert(makeItem({ id: IID_1, category: 'electronics', tags: ['卧室'] }))
    await store.upsert(
      makeItem({ id: IID_2, category: 'furniture', tags: ['客厅'], name: 'n-2' }),
    )

    expect(store.byCategory('electronics')).toHaveLength(1)
    expect(store.searchByTag('客厅')).toHaveLength(1)
  })

  it('nextTriggers 与 warranty.ts 一致', async () => {
    const { channel } = makeChannel()
    const store = useItemsStore()
    store._setChannelForTest(channel)

    const purchase = 1_700_000_000_000
    const until = purchase + 30 * MS_PER_DAY
    await store.upsert(
      makeItem({
        purchase_date: purchase,
        warranty_duration_days: 30,
        warranty_until_ts: until,
      }),
    )

    const now = until - 10 * MS_PER_DAY
    const triggers = store.nextTriggers(now)
    expect(triggers.get(IID_1)).toBe(until - 7 * MS_PER_DAY)
  })

  it('不写 localStorage', async () => {
    const setSpy = vi.spyOn(globalThis.localStorage, 'setItem')
    const { channel } = makeChannel()
    const store = useItemsStore()
    store._setChannelForTest(channel)

    await store.upsert(makeItem())

    expect(setSpy).not.toHaveBeenCalled()
    setSpy.mockRestore()
  })
})
