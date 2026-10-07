// 阶段 5 — 物品 Pinia store（tasks.md Task 7 / TR-7.2）。
//
// CRUD + 拉取走 sealRecord / openRecord + api.pushRecords / listRecords，
// 与 4b event-rules store 同模式；module=item / type=item。
// 明文仅驻内存，刷新即清空。

import { defineStore } from 'pinia'
import { computed, reactive, ref } from 'vue'
import { api, type RemoteRecord } from '../api/client'
import { fromBase64, openRecord, sealRecord, toBase64 } from '../crypto/envelope'
import { nextItemTrigger } from '../items/warranty'
import type { Item } from '../items/types'
import { ITEM_MODULE, ITEM_TYPE } from '../items/types'
import { useAuthStore } from './auth'

/** store 内缓存：明文 Item + 信封元数据 */
export interface CachedItem {
  id: string
  module: typeof ITEM_MODULE
  type: typeof ITEM_TYPE
  version: number
  createdAt: number
  updatedAt: number
  deleted: boolean
  data: Item
}

/** 加密链路契约（测试可注入 mock） */
export interface ItemsCryptoChannel {
  seal(item: Item, id: string, version: number): string
  open(id: string, module: string, ciphertextB64: string, version: number): Item
  push(record: RemoteRecord): Promise<{ applied: number; skipped: number; server_time: number }>
  list(since: number): Promise<{ records: RemoteRecord[]; has_more: boolean }>
}

function defaultChannel(): ItemsCryptoChannel {
  const auth = useAuthStore()
  return {
    seal(item, id, version) {
      const plaintext = new TextEncoder().encode(JSON.stringify(item))
      const sealed = sealRecord(auth.sodium!, auth.masterKey!, plaintext, id, ITEM_MODULE, version)
      return toBase64(sealed)
    },
    open(id, module, ciphertextB64, version) {
      const plaintext = openRecord(
        auth.sodium!,
        auth.masterKey!,
        fromBase64(ciphertextB64),
        id,
        module,
        version,
      )
      return JSON.parse(new TextDecoder().decode(plaintext)) as Item
    },
    push(record) {
      return api.pushRecords([record])
    },
    list(since) {
      return api.listRecords(since, 500)
    },
  }
}

export const useItemsStore = defineStore('items', () => {
  const items = reactive(new Map<string, CachedItem>())
  const syncing = ref(false)
  const lastSyncAt = ref(0)
  let since = 0
  let channel: ItemsCryptoChannel = defaultChannel()

  const list = computed<Item[]>(() =>
    Array.from(items.values())
      .filter((r) => !r.deleted)
      .sort((a, b) => b.updatedAt - a.updatedAt)
      .map((r) => r.data),
  )

  function byId(id: string): CachedItem | undefined {
    return items.get(id)
  }

  /** 一级分类筛选（客户端） */
  function byCategory(cat: Item['category']): Item[] {
    return list.value.filter((i) => i.category === cat)
  }

  /** 标签子串搜索（大小写不敏感） */
  function searchByTag(q: string): Item[] {
    const needle = q.trim().toLowerCase()
    if (!needle) return list.value
    return list.value.filter((i) => i.tags.some((t) => t.toLowerCase().includes(needle)))
  }

  /** 各物品下一次保修提醒触发时刻（id → ts | null） */
  function nextTriggers(now: number): Map<string, number | null> {
    const out = new Map<string, number | null>()
    for (const cached of items.values()) {
      if (cached.deleted) continue
      out.set(cached.id, nextItemTrigger(cached.data, now))
    }
    return out
  }

  function ingest(remote: RemoteRecord) {
    if (remote.deleted) {
      items.delete(remote.id)
      return
    }
    const existed = items.get(remote.id)
    if (existed && existed.version >= remote.version) return
    const data = channel.open(remote.id, remote.module, remote.ciphertext, remote.version)
    items.set(remote.id, {
      id: remote.id,
      module: ITEM_MODULE,
      type: ITEM_TYPE,
      version: remote.version,
      createdAt: remote.created_at,
      updatedAt: remote.updated_at,
      deleted: false,
      data,
    })
  }

  async function pull(full = false): Promise<void> {
    if (syncing.value) return
    syncing.value = true
    try {
      let cursor = full ? 0 : since
      for (;;) {
        const page = await channel.list(cursor)
        page.records.filter((r) => r.module === ITEM_MODULE).forEach(ingest)
        const moduleRows = page.records.filter((r) => r.module === ITEM_MODULE)
        if (moduleRows.length) {
          cursor = Math.max(cursor, ...moduleRows.map((r) => r.updated_at))
        }
        if (!page.has_more) break
      }
      since = cursor
      lastSyncAt.value = Date.now()
    } finally {
      syncing.value = false
    }
  }

  async function pullAll(sinceMs: number): Promise<void> {
    if (sinceMs <= 0) {
      await pull(true)
      return
    }
    since = Math.max(since, sinceMs)
    await pull(false)
  }

  async function upsert(item: Item): Promise<void> {
    const existing = items.get(item.id)
    const version = (existing?.version ?? 0) + 1
    const now = Date.now()
    const ciphertext = channel.seal(item, item.id, version)
    const res = await channel.push({
      id: item.id,
      module: ITEM_MODULE,
      type: ITEM_TYPE,
      ciphertext,
      version,
      device_id: 'web',
      created_at: existing?.createdAt ?? now,
      updated_at: now,
      deleted: false,
    })
    if (res.skipped > 0) {
      await pull(true)
      return
    }
    items.set(item.id, {
      id: item.id,
      module: ITEM_MODULE,
      type: ITEM_TYPE,
      version,
      createdAt: existing?.createdAt ?? now,
      updatedAt: res.server_time,
      deleted: false,
      data: item,
    })
    since = Math.max(since, res.server_time)
  }

  async function remove(id: string): Promise<void> {
    const existing = items.get(id)
    const version = (existing?.version ?? 0) + 1
    const now = Date.now()
    const res = await channel.push({
      id,
      module: ITEM_MODULE,
      type: ITEM_TYPE,
      ciphertext: '',
      version,
      device_id: 'web',
      created_at: existing?.createdAt ?? now,
      updated_at: now,
      deleted: true,
    })
    if (res.skipped === 0) items.delete(id)
    since = Math.max(since, res.server_time)
    await pull()
  }

  async function pushChanges(itemsToPush: Item[]): Promise<void> {
    for (const item of itemsToPush) {
      await upsert(item)
    }
  }

  function reset() {
    items.clear()
    since = 0
    lastSyncAt.value = 0
  }

  function _setChannelForTest(c: ItemsCryptoChannel) {
    channel = c
  }

  return {
    items,
    syncing,
    lastSyncAt,
    list,
    byId,
    byCategory,
    searchByTag,
    nextTriggers,
    pull,
    pullAll,
    upsert,
    remove,
    pushChanges,
    reset,
    _setChannelForTest,
  }
})
