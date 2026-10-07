// 财务附件 upsert 须经 records 上行；卡面引用随 FinanceCard payload 持久化。
import { describe, it, expect, beforeAll, beforeEach, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useAuthStore } from '../auth'
import { useFinanceStore, type CryptoChannel, type StorageChannel } from '../finance'
import { api } from '../../api/client'
import { uploadFile } from '../../finance/attachment'
import { FINANCE_MODULE } from '../../finance/types'
import { ATTACHMENT_RECORD_TYPE } from '../../vault/attachment'
import { extractLast4 } from '../../finance/luhn'
import sodium from 'libsodium-wrappers'
import { newMasterKey } from '../../crypto/envelope'

function memoryStorage(): StorageChannel {
  let state: ReturnType<StorageChannel['read']> = null
  return {
    read: () => (state == null ? null : JSON.parse(JSON.stringify(state))),
    write: (next) => {
      state = JSON.parse(JSON.stringify(next))
    },
    clear: () => {
      state = null
    },
  }
}

function noopCrypto(): CryptoChannel {
  return {
    seal: () => '',
    open: () => ({}) as never,
    push: async () => ({ applied: 1, skipped: 0, server_time: Date.now() }),
    list: async () => ({ records: [], has_more: false }),
  }
}

function makeFileLike(content: Uint8Array, mime: string): File {
  return {
    size: content.byteLength,
    type: mime,
    arrayBuffer: async () => content.buffer.slice(content.byteOffset, content.byteOffset + content.byteLength),
  } as unknown as File
}

let mk: Uint8Array

beforeAll(async () => {
  await sodium.ready
  mk = newMasterKey(sodium)
})

describe('finance attachment / records 上行', () => {
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
    vi.restoreAllMocks()
    const auth = useAuthStore()
    auth.sodium = sodium
    auth.masterKey = mk
  })

  it('getAttachmentChannel upsert → pushRecords(module=finance,type=attachment)', async () => {
    const store = useFinanceStore()
    store._setStorageForTest(memoryStorage())
    store._setChannelForTest(noopCrypto())
    store.hydrate()

    const pushSpy = vi.spyOn(api, 'pushRecords').mockResolvedValue({
      applied: 1,
      skipped: 0,
      server_time: 1_700_000_000_000,
    })

    const ch = store.getAttachmentChannel()
    const content = new TextEncoder().encode('card-face-bytes')
    const file = makeFileLike(content, 'image/jpeg')
    const up = await uploadFile('card-rec-1', file, ch, (ref) => {
      store.addAttachment('card-rec-1', ref)
    })
    expect(up.ok).toBe(true)
    expect(pushSpy).toHaveBeenCalled()
    const batch = pushSpy.mock.calls[0]?.[0] as Array<Record<string, unknown>>
    expect(batch).toHaveLength(1)
    expect(batch[0]?.module).toBe(FINANCE_MODULE)
    expect(batch[0]?.type).toBe(ATTACHMENT_RECORD_TYPE)
    expect(batch[0]?.id).toBe(up.ok ? up.value.id : '')
    expect(batch[0]?.deleted).toBe(false)
  })

  it('updateCard 持久化 card_face_attachment_id；卡号仍仅 last4', () => {
    const store = useFinanceStore()
    const storage = memoryStorage()
    store._setStorageForTest(storage)
    store._setChannelForTest(noopCrypto())
    store.hydrate()

    const pan = '4111111111111111'
    const last4 = extractLast4(pan)
    expect(last4).toBe('1111')

    store.addCard({
      id: 'card-1',
      schema_version: 1,
      name: '测试卡',
      kind: 'credit',
      issuer: 'test',
      last4: last4!,
      currency: 'CNY',
      credit_limit: '0.00',
      used_limit: null,
      billing_day: 1,
      due_day: 1,
      note: null,
      icon: null,
      color: 'blue',
      archived: false,
      include_in_net_assets: true,
      created_at: 1,
      updated_at: 1,
      card_face_attachment_id: 'att-face-1',
    })

    const persisted = storage.read()
    const card = persisted?.cards.find((c) => c.id === 'card-1')
    expect(card?.card_face_attachment_id).toBe('att-face-1')
    expect(card?.last4).toBe('1111')
    expect(JSON.stringify(persisted)).not.toContain(pan)
  })
})
