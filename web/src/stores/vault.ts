// 密码库 store：端到端加密的记录同步、内存索引、搜索与 CRUD。
// 服务端只存密文与版本号；所有明文仅存在于本 store（页面刷新即清空，需重新解锁）。
import { defineStore } from 'pinia'
import { computed, reactive, ref } from 'vue'
import { api, type RemoteRecord } from '../api/client'
import { fromBase64, openRecord, sealRecord, toBase64 } from '../crypto/envelope'
import { encodeGeohash } from '../locations/core/geohash'
import { placeIdFor } from '../locations/places'
import { useAuthStore } from './auth'
import {
  kindOf,
  moduleTypeFor,
  type DecryptedRecord,
  type IdentityData,
  type PlaceData,
  type RecordKind,
  type VaultData,
} from '../types/vault'
// 阶段 4b / Task 10（TR-10.1）挂载点：events 模块独立 Pinia store
// （web/src/stores/event-rules.ts）。不在 vault 内复造 envelope / place 链路，
// 仅在 vault.sync 完成事件分支后调一次 eventsStore.pullAll，消化 module=event
// 远端记录（解密后入 eventsStore 内存）。try-catch 包裹不破坏既有同步。
// 文件名说明：eventsStore 实际导出名为 `useEventRulesStore`，因其文件名
// `event-rules.ts`（不与 4a SSE channel `events.ts` 冲突）。
import { useEventRulesStore } from './event-rules'
// 阶段 5 / Task 10（TR-11.1）挂载点：finance 模块独立 Pinia store
// （web/src/stores/finance.ts）。同样在 vault.sync 末尾追加 financeStore.pullAll
// try-catch 块；财务模块与事件模块使用各自的 CryptoChannel 与 since 游标，
// 互不干扰；任一模块解密失败不破坏 4a vault 闭环。
import { useFinanceStore } from './finance'

/**
 * 命名地点（module=place）解密缓存记录（阶段 4a / tasks.md Task 9）。
 *
 * 与密码库记录同信封（recordAAD），但语义独立：id 规则 `place:{geohash7}`、
 * 幂等覆盖；单独建 placeRecords 缓存，绝不混入 pass/identity 列表。
 */
export interface DecryptedPlaceRecord {
  id: string
  version: number
  createdAt: number
  updatedAt: number
  data: PlaceData
}

const PAGE_SIZE = 500

/** 证件到期分级（含当天边界：到期日当天 days=0，归入红色/橙色临界见实现）。 */
export type ExpiryLevel = 'expired' | 'soon' | 'upcoming'
export function expiryDays(expiresOn: string, now = new Date()): number {
  // 以本地日历天为单位做差，消除时分秒与 UTC 偏移干扰。
  const today = new Date(now.getFullYear(), now.getMonth(), now.getDate())
  const d = new Date(`${expiresOn}T00:00:00`)
  return Math.round((d.getTime() - today.getTime()) / 86_400_000)
}
export function expiryLevel(expiresOn: string): ExpiryLevel | null {
  if (!expiresOn) return null
  const days = expiryDays(expiresOn)
  if (days < 0) return 'expired' // 已过期：红
  if (days <= 30) return 'soon' // 30 天内（含到期当天）：橙
  if (days <= 90) return 'upcoming' // 31–90 天：黄
  return null
}

export const useVaultStore = defineStore('vault', () => {
  const auth = useAuthStore()
  /** id → 解密记录（不含已删除墓碑）。reactive Map 的 set/delete 可被 Vue 追踪。 */
  const records = reactive(new Map<string, DecryptedRecord>())
  /** id → 命名地点（module=place）独立缓存：轨迹页查名专用，不进 records 列表。 */
  const placeRecords = reactive(new Map<string, DecryptedPlaceRecord>())
  const syncing = ref(false)
  const lastSyncAt = ref(0)
  /** 增量游标：本设备已拉取到的最大 updated_at（毫秒）。 */
  let since = 0

  const list = computed(() => Array.from(records.values()))

  function byKind(kind: RecordKind): DecryptedRecord[] {
    return list.value
      .filter((r) => kindOf(r.module, r.type) === kind)
      .sort((a, b) => b.updatedAt - a.updatedAt)
  }

  /** 当前类型内的内存全文检索（标题/用户名/URI/卡号尾号/备注/证号等）。 */
  function search(kind: RecordKind, query: string): DecryptedRecord[] {
    const q = query.trim().toLowerCase()
    const items = byKind(kind)
    if (!q) return items
    return items.filter((r) => haystack(r).includes(q))
  }

  function haystack(r: DecryptedRecord): string {
    // 仅用于客户端内存检索的宽松字段遍历（明文不出本机）。
    const d = r.data as unknown as Record<string, unknown>
    const parts: string[] = [r.type]
    Object.entries(d).forEach(([k, v]) => {
      if (k === 'totp' || v == null) return
      if (Array.isArray(v)) parts.push(v.join(' '))
      else parts.push(String(v))
    })
    // 卡号额外提供尾号命中（完整号在上面已包含；尾号搜索更常用）。
    const card = d as { number?: string }
    if (card.number) parts.push(card.number.replace(/\s/g, '').slice(-4))
    return parts.join('\n').toLowerCase()
  }

  /** 到期提醒：仅证件、有到期日；90 天内 + 已过期，按到期日升序。 */
  const expiring = computed(() => {
    const today = new Date()
    return list.value
      .filter((r) => kindOf(r.module, r.type) === 'identity')
      .map((r) => ({ r, days: expiryDays((r.data as IdentityData).expires_on ?? '', today) }))
      .filter((x) => Boolean((x.r.data as IdentityData).expires_on) && x.days <= 90)
      .sort((a, b) => a.days - b.days)
      .map((x) => x.r)
  })

  /** 解密并合并一条远端记录；墓碑直接移除。 */
  function ingest(remote: RemoteRecord) {
    // place 模块走独立缓存分支（不混入 pass/identity 列表）。
    if (remote.module === 'place') {
      ingestPlace(remote)
      return
    }
    if (remote.deleted) {
      records.delete(remote.id)
      return
    }
    const kind = kindOf(remote.module, remote.type)
    if (!kind) return // 未知模块/类型：保留在服务端，不在 UI 暴露（天然前向兼容）
    const existed = records.get(remote.id)
    if (existed && existed.version >= remote.version) return // 版本不回退
    try {
      const sodium = auth.sodium!
      const mk = auth.masterKey!
      const plain = openRecord(
        sodium,
        mk,
        fromBase64(remote.ciphertext),
        remote.id,
        remote.module,
        remote.version,
      )
      const data = JSON.parse(new TextDecoder().decode(plain)) as VaultData
      records.set(remote.id, {
        id: remote.id,
        module: remote.module,
        type: remote.type,
        version: remote.version,
        createdAt: remote.created_at,
        updatedAt: remote.updated_at,
        deleted: false,
        data,
      })
    } catch {
      // 单条解密失败（密钥版本不匹配等）不影响其余记录可见性。
    }
  }

  /**
   * place 模块 ingest 分支：墓碑移除；版本不回退；解密进 placeRecords。
   * AAD 与普通记录同构（recordAAD(id, "place", version)），与 Task 10 的
   * savePlace sealRecord 链路互为读写两端。
   */
  function ingestPlace(remote: RemoteRecord) {
    if (remote.deleted) {
      placeRecords.delete(remote.id)
      return
    }
    const existed = placeRecords.get(remote.id)
    if (existed && existed.version >= remote.version) return // 版本不回退
    try {
      const sodium = auth.sodium!
      const mk = auth.masterKey!
      const plain = openRecord(
        sodium,
        mk,
        fromBase64(remote.ciphertext),
        remote.id,
        'place',
        remote.version,
      )
      const data = JSON.parse(new TextDecoder().decode(plain)) as PlaceData
      placeRecords.set(remote.id, {
        id: remote.id,
        version: remote.version,
        createdAt: remote.created_at,
        updatedAt: remote.updated_at,
        data,
      })
    } catch {
      // 单条解密失败不影响其余 place 记录可见性（与普通记录同策略）。
    }
  }

  /** 全量（since=0）或增量同步；分页直到 has_more=false。 */
  async function sync(full = false): Promise<void> {
    if (!auth.unlocked || syncing.value) return
    syncing.value = true
    try {
      let cursor = full ? 0 : since
      for (;;) {
        const page = await api.listRecords(cursor, PAGE_SIZE)
        page.records.forEach(ingest)
        if (page.records.length) {
          cursor = Math.max(cursor, ...page.records.map((r) => r.updated_at))
        }
        if (!page.has_more) break
      }
      since = cursor
      lastSyncAt.value = Date.now()
    } finally {
      syncing.value = false
    }
    // ---- 阶段 4b / TR-10.1 挂载点：事件模块拉取 ----
    // vault.sync 自身已"消化过全部 records"——本调用再次触发 listRecords
    // 在 event 模块维度看似冗余，但事件模块走独立 CryptoChannel（避免
    // 解密失败时拖垮 vault.ingest 的容错），由 eventsStore 自己管 since
    // 游标；用 `since`（毫秒游标）传入，eventsStore.pullAll 在 sinceMs<=0
    // 时走全量。try-catch 包裹——事件模块解密失败不应阻塞 vault 闭环。
    try {
      const eventsStore = useEventRulesStore()
      await eventsStore.pullAll(full ? 0 : since)
    } catch (e) {
      // 单模块同步失败不破坏 4a 闭环（与 ingest 容错纪律一致）
    }
    // ---- 阶段 5 / TR-11.1 挂载点：财务模块拉取 ----
    // 与 eventsStore 同款 try-catch：finance 模块走独立 CryptoChannel；
    // 调用 financeStore.pullAll 时 sinceMs 复用 vault 已累计水位；
    // full=true 走 sinceMs=0 全量。解密失败抛异常由本 try-catch 吞掉，
    // 4a vault 闭环不因此中断。
    try {
      const financeStore = useFinanceStore()
      await financeStore.pullAll(full ? 0 : since)
    } catch (e) {
      // 单模块同步失败不破坏 4a 闭环（与事件模块同策略）
    }
  }

  /** 新建或编辑保存：version 严格递增，加密后单条 push；skipped 表示远端更新，触发补同步。 */
  async function save(
    kind: RecordKind,
    data: VaultData,
    existing?: DecryptedRecord,
  ): Promise<void> {
    const sodium = auth.sodium!
    const mk = auth.masterKey!
    const id = existing?.id ?? crypto.randomUUID()
    const version = (existing?.version ?? 0) + 1
    const identityKind = kind === 'identity' ? (data as IdentityData).kind : undefined
    const { module, type } = moduleTypeFor(kind, identityKind)
    const now = Date.now()
    const plaintext = new TextEncoder().encode(JSON.stringify(data))
    const sealed = sealRecord(sodium, mk, plaintext, id, module, version)
    const res = await api.pushRecords([
      {
        id,
        module,
        type,
        ciphertext: toBase64(sealed),
        version,
        created_at: existing?.createdAt ?? now,
        updated_at: now,
        deleted: false,
      },
    ])
    if (res.skipped > 0) {
      // 与其他设备发生版本竞争：以服务端为准补拉，避免本地假状态。
      await sync(true)
      return
    }
    records.set(id, {
      id,
      module,
      type,
      version,
      createdAt: existing?.createdAt ?? now,
      // updatedAt 与游标一律采用服务端权威时间，避免本地时钟偏差污染增量同步（FU-1）。
      updatedAt: res.server_time,
      deleted: false,
      data,
    })
    since = Math.max(since, res.server_time)
  }

  /**
   * 命名地点保存（阶段 4a / tasks.md Task 10 / TR-10.3）。
   *
   * 与 save 同链路（明文 JSON → sealRecord → api.pushRecords → skipped 补同步），
   * 差异仅在 id/模块语义：
   *   - id = `place:{geohash7(visit 质心)}`（三端契约，placeIdFor 生成）；
   *   - 幂等覆盖：version 取 placeRecords 缓存现存值 +1（无则 1），同 id 重复
   *     命名不会产生新记录，只递增版本覆盖；
   *   - 成功后立即更新 placeRecords 缓存，轨迹页 placesByGeohash 派生即时刷新，
   *     时间线/地图马上显示新名称（无需等待下一轮同步）。
   *
   * 明文坐标纪律：坐标仅在本函数调用帧与密文推送内出现，不落盘、不日志。
   *
   * @param name 地点名称（"家"/"公司"/自定义文本，调用方保证非空）
   * @param category 分类（home/work/custom）
   * @param visit 目标 visit（取其质心；仅读 centerLat/centerLon）
   */
  async function savePlace(
    name: string,
    category: string,
    visit: { centerLat: number; centerLon: number },
  ): Promise<void> {
    const sodium = auth.sodium!
    const mk = auth.masterKey!
    const id = placeIdFor(encodeGeohash(visit.centerLat, visit.centerLon, 7))
    const existing = placeRecords.get(id)
    const version = (existing?.version ?? 0) + 1 // 幂等覆盖：版本严格递增
    const data: PlaceData = {
      name,
      category,
      center_lat: visit.centerLat,
      center_lon: visit.centerLon,
      radius_m: 100,
    }
    const now = Date.now()
    const plaintext = new TextEncoder().encode(JSON.stringify(data))
    const sealed = sealRecord(sodium, mk, plaintext, id, 'place', version)
    const res = await api.pushRecords([
      {
        id,
        module: 'place',
        type: 'place',
        ciphertext: toBase64(sealed),
        version,
        created_at: existing?.createdAt ?? now,
        updated_at: now,
        deleted: false,
      },
    ])
    if (res.skipped > 0) {
      // 与其他设备发生版本竞争：以服务端为准补拉（同 vault.save 做法）。
      await sync(true)
      return
    }
    placeRecords.set(id, {
      id,
      version,
      createdAt: existing?.createdAt ?? now,
      // updatedAt 与游标一律采用服务端权威时间（FU-1，同 vault.save）。
      updatedAt: res.server_time,
      data,
    })
    since = Math.max(since, res.server_time)
  }

  /** 删除：推送高版本墓碑（密文置空），本地移除。 */
  async function remove(record: DecryptedRecord): Promise<void> {
    const version = record.version + 1
    const now = Date.now()
    const res = await api.pushRecords([
      {
        id: record.id,
        module: record.module,
        type: record.type,
        ciphertext: '',
        version,
        created_at: record.createdAt,
        updated_at: now,
        deleted: true,
      },
    ])
    if (res.skipped === 0) records.delete(record.id)
    // 无论 applied/skipped，响应都带服务端权威时间；游标以它推进（FU-1）。
    since = Math.max(since, res.server_time)
    await sync() // 增量回拉墓碑/竞争状态（无需全量）
  }

  /** 退出/重新锁定：清空全部明文内存与游标。 */
  function reset() {
    records.clear()
    placeRecords.clear() // place 明文同样只驻内存
    since = 0
    lastSyncAt.value = 0
  }

  return {
    records,
    placeRecords,
    syncing,
    lastSyncAt,
    list,
    expiring,
    byKind,
    search,
    sync,
    save,
    savePlace,
    remove,
    reset,
  }
})
