// vault store 的 place 模块分流单测（tasks.md Task 9）。
//
// 验证：module='place' 的远端记录经 sync → ingest 解密后进独立
// placeRecords 缓存（不混入 records 的 pass/identity 列表）；墓碑移除；
// 版本不回退；普通记录不受分流影响。
// 零知识边界：全部明文仅存内存；node 环境无 localStorage，测试内补最小 stub。

import { beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { api, type RemoteRecord } from '../../api/client'
import {
  loadSodium,
  newMasterKey,
  sealRecord,
  toBase64,
  type Sodium,
} from '../../crypto/envelope'
import type { PlaceData } from '../../types/vault'
import { useAuthStore } from '../auth'
import { useVaultStore } from '../vault'

let sodium: Sodium
let mk: Uint8Array

/** 测试用 place 明文 payload（锚点坐标与 places.test.ts 一致）。 */
const PLACE_DATA: PlaceData = {
  name: '家',
  category: 'home',
  center_lat: 57.64911,
  center_lon: 10.40744,
  radius_m: 100,
}

/** 用真实信封密封一条远端记录（与线上 push 路径同 AAD 同算法）。 */
function remoteRecordOf(
  id: string,
  module: string,
  type: string,
  data: unknown,
  version: number,
  overrides: Partial<RemoteRecord> = {},
): RemoteRecord {
  const plaintext = new TextEncoder().encode(JSON.stringify(data))
  const sealed = sealRecord(sodium, mk, plaintext, id, module, version)
  return {
    id,
    module,
    type,
    ciphertext: toBase64(sealed),
    version,
    device_id: 'dev-test-1',
    created_at: 1_700_000_000_000,
    updated_at: 1_700_000_000_000 + version,
    deleted: false,
    ...overrides,
  }
}

/** 设置本轮 sync 的远端返回（单页 has_more=false）。 */
function mockListRecords(records: RemoteRecord[]) {
  vi.spyOn(api, 'listRecords').mockResolvedValue({ records, has_more: false })
}

beforeAll(async () => {
  sodium = await loadSodium()
  mk = newMasterKey(sodium)
})

beforeEach(() => {
  // node 环境无 localStorage：auth state 工厂读用户名需要最小 stub（不落盘，仅内存 Map）。
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
  // options store 的 state 可直接赋值：模拟"已解锁"（sodium + MK 均在内存）。
  const auth = useAuthStore()
  auth.sodium = sodium
  auth.masterKey = mk
  vi.restoreAllMocks()
})

describe('vault store place 分流（module=place → placeRecords 独立缓存）', () => {
  it('place 记录解密后进 placeRecords，不混入 records 列表', async () => {
    mockListRecords([
      remoteRecordOf('place:u4pruyd', 'place', 'place', PLACE_DATA, 3),
    ])
    const vault = useVaultStore()
    await vault.sync(true)

    const cached = vault.placeRecords.get('place:u4pruyd')!
    expect(cached.version).toBe(3)
    expect(cached.data).toEqual(PLACE_DATA)
    // 独立缓存红线：绝不混入 pass/identity 的 records。
    expect(vault.records.has('place:u4pruyd')).toBe(false)
    expect(vault.list).toHaveLength(0)
  })

  it('版本不回退：低版本重复到达不覆盖缓存', async () => {
    const vault = useVaultStore()
    mockListRecords([remoteRecordOf('place:u4pruyd', 'place', 'place', PLACE_DATA, 3)])
    await vault.sync(true)

    mockListRecords([
      remoteRecordOf('place:u4pruyd', 'place', 'place', { ...PLACE_DATA, name: '旧名' }, 2),
    ])
    await vault.sync()
    expect(vault.placeRecords.get('place:u4pruyd')!.data.name).toBe('家')
    expect(vault.placeRecords.get('place:u4pruyd')!.version).toBe(3)
  })

  it('高版本覆盖：重命名后缓存更新（Task 10 savePlace 的读端）', async () => {
    const vault = useVaultStore()
    mockListRecords([remoteRecordOf('place:u4pruyd', 'place', 'place', PLACE_DATA, 3)])
    await vault.sync(true)

    mockListRecords([
      remoteRecordOf('place:u4pruyd', 'place', 'place', { ...PLACE_DATA, name: '新家' }, 4),
    ])
    await vault.sync()
    expect(vault.placeRecords.get('place:u4pruyd')!.data.name).toBe('新家')
    expect(vault.placeRecords.get('place:u4pruyd')!.version).toBe(4)
  })

  it('墓碑（deleted）从 placeRecords 移除', async () => {
    const vault = useVaultStore()
    mockListRecords([remoteRecordOf('place:u4pruyd', 'place', 'place', PLACE_DATA, 3)])
    await vault.sync(true)
    expect(vault.placeRecords.has('place:u4pruyd')).toBe(true)

    mockListRecords([
      remoteRecordOf('place:u4pruyd', 'place', 'place', PLACE_DATA, 4, {
        ciphertext: '',
        deleted: true,
      }),
    ])
    await vault.sync()
    expect(vault.placeRecords.has('place:u4pruyd')).toBe(false)
  })

  it('普通 pass 记录不受分流影响：仍进 records，不进 placeRecords', async () => {
    const vault = useVaultStore()
    mockListRecords([
      remoteRecordOf('rec-1', 'pass', 'login', { title: '邮箱', username: 'a@b.c' }, 1),
      remoteRecordOf('place:u4pruyd', 'place', 'place', PLACE_DATA, 1),
    ])
    await vault.sync(true)

    expect(vault.records.has('rec-1')).toBe(true)
    expect(vault.placeRecords.has('rec-1')).toBe(false)
    expect(vault.placeRecords.has('place:u4pruyd')).toBe(true)
    expect(vault.records.has('place:u4pruyd')).toBe(false)
  })

  it('reset 同时清空 placeRecords（锁定/退出不留明文）', async () => {
    const vault = useVaultStore()
    mockListRecords([remoteRecordOf('place:u4pruyd', 'place', 'place', PLACE_DATA, 1)])
    await vault.sync(true)
    expect(vault.placeRecords.size).toBe(1)

    vault.reset()
    expect(vault.placeRecords.size).toBe(0)
  })
})
