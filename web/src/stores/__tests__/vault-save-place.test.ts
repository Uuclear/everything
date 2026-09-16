// vault store savePlace 单测（tasks.md Task 10 / TR-10.3）。
//
// 验证 savePlace 复用 sealRecord → api.pushRecords 同链路：
//   - 推送记录 id 形如 `place:{geohash7}`（geohash 锚点与 geohash.test.ts 一致）；
//   - module/type = place/place；密文可用 openRecord 同 AAD 解出原明文；
//   - 幂等覆盖：同 visit 重复命名不产生新记录，version 严格递增（1 → 2），
//     placeRecords 缓存即时更新（时间线/地图立即显示新名）；
//   - skipped>0（版本竞争）时触发全量补同步，不写本地缓存。
// node 环境无 localStorage，测试内补最小 stub（同 vault-place.test.ts）。

import { beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { api } from '../../api/client'
import { loadSodium, newMasterKey, openRecord, fromBase64, type Sodium } from '../../crypto/envelope'
import { encodeGeohash } from '../../locations/core/geohash'
import { placeIdFor } from '../../locations/places'
import type { PlaceData } from '../../types/vault'
import { useAuthStore } from '../auth'
import { useVaultStore } from '../vault'

let sodium: Sodium
let mk: Uint8Array

/** 测试 visit 锚点（geohash7 = "u4pruyd"，与 geohash.test.ts 的 11 位锚点前缀一致）。 */
const VISIT = { centerLat: 57.64911, centerLon: 10.40744 }
const EXPECTED_ID = placeIdFor(encodeGeohash(VISIT.centerLat, VISIT.centerLon, 7))

/** 捕获 pushRecords 的推送体（未知形状，逐字段断言）。 */
function mockPush(result = { applied: 1, skipped: 0, server_time: 1_800_000_000_000 }) {
  const spy = vi.spyOn(api, 'pushRecords').mockResolvedValue(result)
  return spy
}

beforeAll(async () => {
  sodium = await loadSodium()
  mk = newMasterKey(sodium)
})

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
  const auth = useAuthStore()
  auth.sodium = sodium
  auth.masterKey = mk
  vi.restoreAllMocks()
})

describe('savePlace 写入链路（TR-10.3）', () => {
  it('id 形如 place:{geohash7}，module/type=place/place，version 从 1 起', async () => {
    expect(EXPECTED_ID).toBe('place:u4pruyd') // geohash 契约锚点
    const push = mockPush()
    const vault = useVaultStore()

    await vault.savePlace('家', 'home', VISIT)

    expect(push).toHaveBeenCalledTimes(1)
    const records = push.mock.calls[0][0] as Array<Record<string, unknown>>
    expect(records).toHaveLength(1)
    expect(records[0].id).toBe('place:u4pruyd')
    expect(records[0].module).toBe('place')
    expect(records[0].type).toBe('place')
    expect(records[0].version).toBe(1)
    expect(records[0].deleted).toBe(false)
  })

  it('密文可由 openRecord 同 AAD 解出原明文（sealRecord 同链路自证）', async () => {
    const push = mockPush()
    const vault = useVaultStore()

    await vault.savePlace('公司', 'work', VISIT)

    const records = push.mock.calls[0][0] as Array<Record<string, unknown>>
    const plain = openRecord(
      sodium,
      mk,
      fromBase64(records[0].ciphertext as string),
      'place:u4pruyd',
      'place',
      1,
    )
    const data = JSON.parse(new TextDecoder().decode(plain)) as PlaceData
    expect(data).toEqual({
      name: '公司',
      category: 'work',
      center_lat: VISIT.centerLat,
      center_lon: VISIT.centerLon,
      radius_m: 100,
    })
  })

  it('成功后即时更新 placeRecords 缓存（时间线/地图立即显示）', async () => {
    mockPush()
    const vault = useVaultStore()
    await vault.savePlace('家', 'home', VISIT)

    const cached = vault.placeRecords.get('place:u4pruyd')!
    expect(cached.version).toBe(1)
    expect(cached.data.name).toBe('家')
    // 独立缓存红线：不进 records 列表。
    expect(vault.records.has('place:u4pruyd')).toBe(false)
  })

  it('幂等覆盖：重复命名同 visit 不建新记录，version 递增', async () => {
    const push = mockPush()
    const vault = useVaultStore()

    await vault.savePlace('家', 'home', VISIT)
    await vault.savePlace('老家', 'custom', VISIT)

    // 两次推送同 id，version 1 → 2；服务端只存在这一条记录的两个版本。
    expect(push).toHaveBeenCalledTimes(2)
    const first = (push.mock.calls[0][0] as Array<Record<string, unknown>>)[0]
    const second = (push.mock.calls[1][0] as Array<Record<string, unknown>>)[0]
    expect(first.id).toBe('place:u4pruyd')
    expect(second.id).toBe('place:u4pruyd')
    expect(first.version).toBe(1)
    expect(second.version).toBe(2)

    // 缓存只保留最新版本与名称。
    expect(vault.placeRecords.size).toBe(1)
    const cached = vault.placeRecords.get('place:u4pruyd')!
    expect(cached.version).toBe(2)
    expect(cached.data.name).toBe('老家')
  })

  it('skipped>0 版本竞争：触发全量补同步，不写本地缓存', async () => {
    mockPush({ applied: 0, skipped: 1, server_time: 1_800_000_000_000 })
    // 补同步以服务端为准：远端已有 version 5 的记录。
    const listSpy = vi
      .spyOn(api, 'listRecords')
      .mockResolvedValue({ records: [], has_more: false })
    const vault = useVaultStore()

    await vault.savePlace('家', 'home', VISIT)

    expect(listSpy).toHaveBeenCalled() // sync(true) 被触发
    expect(vault.placeRecords.has('place:u4pruyd')).toBe(false) // 本地未写假状态
  })
})
