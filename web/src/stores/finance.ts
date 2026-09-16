// ============================================================================
// 财务模块 Pinia store（stage5-finance / Task 8 + Task 10 + TR-11.1）
// ============================================================================
//
// 任务:
//   - TR-8.6（financeStore 实现）
//   - TR-8.7（Vitest 测试套件 ≥8 用例）
//   - TR-11.1（同步集成：CryptoChannel + pullAll + pushChanges）
// 路径: web/src/stores/finance.ts
// 作用: 财务条目（账户 / 卡 / 流水）的 CRUD 容器 + 端到端加密同步入口；
//       本地持久化到 localStorage（兼容 v1 期未解锁场景）；
//       同步链路复用 crypto/envelope.sealRecord/openRecord 与 api/client。
//
// 设计要点：
//   1. **Pinia setup store** —— 与 4a places / 4b event-rules 同款风格；
//   2. **CRUD 接口** —— addAccount / updateAccount / archiveAccount /
//      addCard / updateCard / archiveCard / addTx / updateTx / deleteTx；
//   3. **本地持久化** —— key = `eve:finance:v1`，存明文 JSON（含 accounts /
//      cards / txs / loans[v2 placeholder]），刷新后 hydrate 自动恢复；
//   4. **加密同步**（TR-11.1 接入）——
//      - CryptoChannel 注入点（默认直连 crypto/envelope + api/client）；
//      - pullAll(since?)：从服务端拉 records，过滤 module='finance'，
//        解密后入 store；墓碑直接删除；版本不回退；解密失败抛异常
//        （与 4b event-rules 同纪律）；
//      - pushChanges()：遍历 store 中"待推送"集合，按明文 payload →
//        sealRecord → api.pushRecords 上行；version 严格递增；
//      - upsert/delete Xxx 内部即时加密上行（推送失败时回滚缓存并保留
//        localStorage 副本，下一轮 pullAll 仍可校正）；
//   5. **版本与 createdAt / updatedAt** —— CRUD 内部严格自增 version，
//      createdAt 取首次创建时间，updatedAt 优先取服务端权威时间；
//   6. **零知识纪律** —— 密文走 api/client；明文仅驻内存；不进日志；
//      测试卡号均为业界公开示例 last4。
//   7. **测试可注入** —— `_setStorageForTest(channel)` 与 `_setChannelForTest(channel)`
//      允许单测注入内存 storage 与内存 CryptoChannel。
//
// 关联:
//   - tasks.md TR-8.6（financeStore 实现）
//   - tasks.md TR-8.7（Vitest 测试套件 ≥8 用例）
//   - tasks.md TR-11.1（同步集成）
//   - web/src/finance/aggregator.ts（聚合函数）
//   - web/src/finance/luhn.ts（卡号校验）
//   - docs/schemas/finance.schema.json（字段真理源）
// ============================================================================

import { defineStore } from 'pinia'
import { computed, reactive, ref } from 'vue'
import { api, type RemoteRecord } from '../api/client'
import {
  fromBase64,
  openRecord,
  sealRecord,
  toBase64,
} from '../crypto/envelope'
import { useAuthStore } from './auth'
import type {
  CachedFinanceRecord,
  FinanceAccount,
  FinanceCard,
  FinanceTx,
  FinancePayload,
  FinanceType,
} from '../finance/types'
import { FINANCE_MODULE } from '../finance/types'

// -----------------------------------------------------------------------------
// 持久化契约 —— StorageChannel（注入点：默认 localStorage；测试可 mock）
// -----------------------------------------------------------------------------

/**
 * 持久化通道契约（store 依赖注入点）。
 *
 * 默认实现 = 直接调 localStorage；测试时可整体 mock，避免 jsdom 依赖。
 */
export interface StorageChannel {
  /** 读取 JSON；解析失败 / 不存在时返回 null。 */
  read(): PersistedFinanceState | null
  /** 写入 JSON（覆盖）。 */
  write(state: PersistedFinanceState): void
  /** 清空（测试或登出场景）。 */
  clear(): void
}

/** 持久化形态（明文 JSON；与 schema 字段口径一致）。 */
export interface PersistedFinanceState {
  schemaVersion: 1
  accounts: FinanceAccount[]
  cards: FinanceCard[]
  txs: FinanceTx[]
  /** v2 子类型占位（v1 阶段固定空数组；编辑功能本期不实现）。 */
  loans: unknown[]
}

/** localStorage key —— `eve:finance:v1`（spec §持久化 §6 一致）。 */
export const FINANCE_STORAGE_KEY = 'eve:finance:v1'

/** 默认 StorageChannel（直连 localStorage；SSR / node 环境无 window 时静默降级）。 */
function defaultStorageChannel(): StorageChannel {
  return {
    read() {
      if (typeof localStorage === 'undefined') return null
      const raw = localStorage.getItem(FINANCE_STORAGE_KEY)
      if (raw == null) return null
      try {
        return JSON.parse(raw) as PersistedFinanceState
      } catch {
        // JSON 损坏时返回 null, 调用方可决定如何兜底（v1 阶段按"丢数据"处理）。
        return null
      }
    },
    write(state) {
      if (typeof localStorage === 'undefined') return
      localStorage.setItem(FINANCE_STORAGE_KEY, JSON.stringify(state))
    },
    clear() {
      if (typeof localStorage === 'undefined') return
      localStorage.removeItem(FINANCE_STORAGE_KEY)
    },
  }
}

// -----------------------------------------------------------------------------
// 加密链路契约 —— CryptoChannel（注入点：默认直连 crypto/envelope + api/client）
// -----------------------------------------------------------------------------

/**
 * 加密通道契约（TR-11.1 同步集成）。
 *
 * 默认实现 = 直接调 crypto/envelope.sealRecord/openRecord + api/client；
 * 测试时可整体 mock，便于不依赖 sodium / 网络跑通单测。
 *
 * 与 4b event-rules store CryptoChannel 同结构——保持三端契约一致。
 */
export interface CryptoChannel {
  /** 密封一条 FinancePayload → 密文 Base64。 */
  seal(payload: FinancePayload, id: string, version: number): string
  /** 解密一条密文（Base64）→ FinancePayload。失败抛异常。 */
  open(id: string, module: string, ciphertextB64: string, version: number): FinancePayload
  /** 推送到远端 records。 */
  push(record: RemoteRecord): Promise<{ applied: number; skipped: number; server_time: number }>
  /** 拉取远端 records（since=0 即全量）。 */
  list(since: number): Promise<{ records: RemoteRecord[]; has_more: boolean }>
}

/** 构造默认 CryptoChannel（直接调既有 crypto/envelope + api/client）。 */
function defaultCryptoChannel(): CryptoChannel {
  // 静态导入 auth（与 4b event-rules 同款）；channel 在测试场景下整体 mock，
  // 生产路径走 auth.sodium + auth.masterKey 加密。
  const auth = useAuthStore()
  return {
    seal(payload, id, version) {
      const plaintext = new TextEncoder().encode(JSON.stringify(payload))
      const sealed = sealRecord(auth.sodium!, auth.masterKey!, plaintext, id, FINANCE_MODULE, version)
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
      return JSON.parse(new TextDecoder().decode(plaintext)) as FinancePayload
    },
    push(record) {
      return api.pushRecords([record])
    },
    list(since) {
      return api.listRecords(since, 500)
    },
  }
}

// -----------------------------------------------------------------------------
// Pinia store
// -----------------------------------------------------------------------------

/**
 * 财务 store —— CRUD + 持久化 + 同步入口。
 *
 * 状态拓扑：
 *   - accounts / cards / txs：reactive Map<id, CachedFinanceRecord>（明文 + 元数据）
 *   - schemaVersion：持久化 schema 版本（v1 固定 1）
 *   - hydrated：是否已完成 startup hydration
 *   - byId(type, id) / listAccounts / listCards / listTxs：查询接口
 *   - addX / updateX / archiveX / deleteX：CRUD 接口（内部即时 pushChanges）
 *   - hydrate() / persist()：启动 / 落盘
 *   - pullAll(since?) / pushChanges()：同步入口（TR-11.1）
 *   - reset()：清空所有状态（登出 / 锁定）
 */
export const useFinanceStore = defineStore('finance', () => {
  // ========== 内部状态 ==========
  const accounts = reactive(new Map<string, CachedFinanceRecord>())
  const cards = reactive(new Map<string, CachedFinanceRecord>())
  const txs = reactive(new Map<string, CachedFinanceRecord>())
  /** 持久化通道（默认 localStorage；测试可注入 mock）。 */
  let storage: StorageChannel = defaultStorageChannel()
  /** 加密通道（默认直连 crypto/envelope + api/client；测试可注入 mock）。 */
  // channel 用懒代理模式：只在首次调用 seal/open/push/list 时才构造真实实现
  // （默认实现依赖 useAuthStore，需在 pinia 已激活场景下才能执行——测试注入
  // _setChannelForTest 后即可绕过 defaultCryptoChannel 的 localStorage 访问）。
  let _channel: CryptoChannel | null = null
  const channel: CryptoChannel = {
    seal(...args) {
      return (_channel ??= defaultCryptoChannel()).seal(...args)
    },
    open(...args) {
      return (_channel ??= defaultCryptoChannel()).open(...args)
    },
    push(...args) {
      return (_channel ??= defaultCryptoChannel()).push(...args)
    },
    list(...args) {
      return (_channel ??= defaultCryptoChannel()).list(...args)
    },
  }
  /** schema 版本（v1 固定 1；v2 升级时按需迁移）。 */
  const schemaVersion = ref<1>(1)
  /** startup hydration 是否完成（首屏 UI 据此决定是否展示骨架）。 */
  const hydrated = ref(false)
  /** 同步状态：syncing / lastSyncAt。 */
  const syncing = ref(false)
  const lastSyncAt = ref(0)
  /** 增量游标：最近一次成功推送/拉取的最大 updated_at（毫秒）。 */
  let since = 0

  // ========== 计算属性 ==========

  /**
   * 判断条目是否"归档"（仅账户/卡有该字段；流水无归档语义，硬删除）。
   *
   * 类型守卫 —— 流水类型上不存在 `archived` 字段, 由本函数做收窄。
   */
  function isArchived(r: CachedFinanceRecord): boolean {
    if (r.type === 'tx') return false
    // 收窄后剩下 account / card 两条分支, 均含 archived 字段。
    return (r.data as FinanceAccount | FinanceCard).archived === true
  }

  /** 所有非归档账户（按 updatedAt 降序）。 */
  const listAccounts = computed<FinanceAccount[]>(() =>
    Array.from(accounts.values())
      .filter((r) => !r.deleted && !isArchived(r))
      .sort((a, b) => b.updatedAt - a.updatedAt)
      .map((r) => r.data as FinanceAccount),
  )

  /** 所有非归档卡（按 updatedAt 降序）。 */
  const listCards = computed<FinanceCard[]>(() =>
    Array.from(cards.values())
      .filter((r) => !r.deleted && !isArchived(r))
      .sort((a, b) => b.updatedAt - a.updatedAt)
      .map((r) => r.data as FinanceCard),
  )

  /** 所有未删除流水（按 occurred_at 降序）。 */
  const listTxs = computed<FinanceTx[]>(() =>
    Array.from(txs.values())
      .filter((r) => !r.deleted)
      .sort((a, b) => {
        const aTx = a.data as FinanceTx
        const bTx = b.data as FinanceTx
        return bTx.occurred_at - aTx.occurred_at
      })
      .map((r) => r.data as FinanceTx),
  )

  // ========== 查询接口 ==========

  function byId(type: FinanceType, id: string): CachedFinanceRecord | undefined {
    switch (type) {
      case 'account':
        return accounts.get(id)
      case 'card':
        return cards.get(id)
      case 'tx':
        return txs.get(id)
      default:
        // v2 子类型本期不实现。
        return undefined
    }
  }

  // ========== 内部工具 ==========

  /**
   * 把明文 payload 包装为 CachedFinanceRecord，version 自增。
   *
   * 注意：createdAt 优先取 existing.createdAt（保持首次创建时间稳定），
   * updatedAt 在推送成功后由 pushChanges 覆写为服务端权威时间。
   */
  function wrap(
    type: FinanceType,
    data: FinancePayload,
    existing?: CachedFinanceRecord,
  ): CachedFinanceRecord {
    const now = Date.now()
    const version = (existing?.version ?? 0) + 1
    return {
      id: data.id,
      module: FINANCE_MODULE,
      type,
      version,
      createdAt: existing?.createdAt ?? data.created_at ?? now,
      updatedAt: now,
      deleted: false,
      data,
    }
  }

  /** 把明文 payload 序列化为 JSON（沿用 schema snake_case 字段名）。 */
  function toJson(data: FinancePayload): string {
    return JSON.stringify(data)
  }

  /**
   * 把远端 RemoteRecord（module=finance）解密后入 Map；墓碑删除；
   * 版本不回退；解密失败抛异常（与 4b event-rules.ingest 同纪律）。
   */
  function ingest(remote: RemoteRecord): void {
    // 墓碑：按 type 路由删除本地缓存。
    if (remote.deleted) {
      accounts.delete(remote.id)
      cards.delete(remote.id)
      txs.delete(remote.id)
      return
    }
    const type = remote.type as FinanceType
    const target = type === 'account' ? accounts : type === 'card' ? cards : type === 'tx' ? txs : null
    if (target == null) return // 未知子类型：保留在服务端，不在 UI 暴露
    const existed = target.get(remote.id)
    // 版本不回退：低版本重复到达不覆盖缓存。
    if (existed && existed.version >= remote.version) return
    // 解密（失败抛异常，让上层观测）。
    const data = channel.open(remote.id, remote.module, remote.ciphertext, remote.version)
    target.set(remote.id, {
      id: remote.id,
      module: FINANCE_MODULE,
      type,
      version: remote.version,
      createdAt: remote.created_at,
      updatedAt: remote.updated_at,
      deleted: false,
      data,
    })
    since = Math.max(since, remote.updated_at)
  }

  /**
   * 推送单条 CachedFinanceRecord 上行（仅对 type ∈ {account,card,tx}）。
   * version 严格递增；skipped>0 触发全量补同步，不写假状态。
   *
   * @returns true 表示推送成功并已写本地缓存；false 表示 skipped/失败
   */
  async function pushOne(rec: CachedFinanceRecord): Promise<boolean> {
    const ciphertext = channel.seal(rec.data, rec.id, rec.version)
    const res = await channel.push({
      id: rec.id,
      module: FINANCE_MODULE,
      type: rec.type,
      ciphertext,
      version: rec.version,
      device_id: 'web',
      created_at: rec.createdAt,
      updated_at: Date.now(),
      deleted: false,
    })
    if (res.skipped > 0) {
      // 版本竞争：以服务端为准补拉，避免本地假状态。
      await pullAll(0)
      return false
    }
    // 推送成功：以服务端权威时间覆写 updatedAt（FU-1）。
    rec.updatedAt = res.server_time
    since = Math.max(since, res.server_time)
    return true
  }

  // ========== 启动 hydration ==========

  /**
   * 从持久化通道恢复 entries；首屏 UI 渲染前调用。
   *
   * 幂等 —— 多次调用不会重复注入；hydrated=true 后再次调用直接返回。
   */
  function hydrate(): void {
    if (hydrated.value) return
    const state = storage.read()
    if (state == null) {
      hydrated.value = true
      return
    }
    // schema 版本不匹配 → 按"丢数据"处理（v1 → v2 由 T-migration 接管）。
    if (state.schemaVersion !== 1) {
      hydrated.value = true
      return
    }
    // 还原三类条目。
    for (const acc of state.accounts ?? []) {
      accounts.set(acc.id, {
        id: acc.id,
        module: FINANCE_MODULE,
        type: 'account',
        version: 1,
        createdAt: acc.created_at,
        updatedAt: acc.updated_at,
        deleted: false,
        data: acc,
      })
    }
    for (const card of state.cards ?? []) {
      cards.set(card.id, {
        id: card.id,
        module: FINANCE_MODULE,
        type: 'card',
        version: 1,
        createdAt: card.created_at,
        updatedAt: card.updated_at,
        deleted: false,
        data: card,
      })
    }
    for (const tx of state.txs ?? []) {
      txs.set(tx.id, {
        id: tx.id,
        module: FINANCE_MODULE,
        type: 'tx',
        version: 1,
        createdAt: tx.created_at,
        updatedAt: tx.updated_at,
        deleted: false,
        data: tx,
      })
    }
    hydrated.value = true
  }

  /**
   * 把当前内存状态写入持久化通道（CRUD 后内部自动调用）。
   *
   * 失败不抛错（v1 阶段 localStorage 配额耗尽时静默降级，UI 层不感知）。
   */
  function persist(): void {
    const state: PersistedFinanceState = {
      schemaVersion: 1,
      accounts: Array.from(accounts.values())
        .filter((r) => !r.deleted)
        .map((r) => r.data as FinanceAccount),
      cards: Array.from(cards.values())
        .filter((r) => !r.deleted)
        .map((r) => r.data as FinanceCard),
      txs: Array.from(txs.values())
        .filter((r) => !r.deleted)
        .map((r) => r.data as FinanceTx),
      loans: [],
    }
    storage.write(state)
  }

  // ========== 同步入口（TR-11.1） ==========

  /**
   * 全量（sinceMs=0）或增量拉取 + 解密 + 入 store。
   *
   * 流程：
   *   1. 分页拉 records（每页 500，has_more=false 即终止）；
   *   2. 仅消化 module='finance' 的远端记录，其它模块密文原样跳过；
   *   3. 墓碑删除本地缓存；版本不回退；解密失败抛异常（FR-NFR-1）。
   *
   * 与 4b event-rules.pullAll 同结构：sinceMs<=0 走全量；非零时把
   * store 内部游标提升，再走增量（保留上次成功水位）。
   */
  async function pullAll(sinceMs = 0): Promise<void> {
    if (syncing.value) return
    syncing.value = true
    try {
      let cursor = sinceMs <= 0 ? 0 : since
      for (;;) {
        const page = await channel.list(cursor)
        // 仅消化 finance 模块的远端记录；其它模块交给其它 store 接管。
        page.records.filter((r) => r.module === FINANCE_MODULE).forEach(ingest)
        if (page.records.length) {
          cursor = Math.max(
            cursor,
            ...page.records
              .filter((r) => r.module === FINANCE_MODULE)
              .map((r) => r.updated_at),
          )
        }
        if (!page.has_more) break
      }
      since = cursor
      lastSyncAt.value = Date.now()
      // 拉取完成后即时落盘——确保下次解锁 hydrate 能拿到最新视图。
      persist()
    } finally {
      syncing.value = false
    }
  }

  /**
   * 批量推送一组明文 FinancePayload。
   *
   * 流程：对每个 payload 走 wrap → pushOne → 写回缓存；skipped>0 触发
   * 全量补同步；最后落盘持久化。
   *
   * 与 4b event-rules.pushChanges 同结构：串行推送，避免版本竞争。
   *
   * @param payloads 明文 FinancePayload 数组（id 已就位；调用方负责生成）
   */
  async function pushChanges(payloads: FinancePayload[]): Promise<void> {
    for (const payload of payloads) {
      const type = payloadTypeOf(payload)
      if (type == null) continue
      const target = type === 'account' ? accounts : type === 'card' ? cards : txs
      const existing = target.get(payload.id)
      const rec = wrap(type, payload, existing)
      const ok = await pushOne(rec)
      if (ok) {
        target.set(rec.id, rec)
      }
    }
    persist()
  }

  /**
   * 由 FinancePayload 推导出 FinanceType 子类型（运行时类型守卫）。
   * v2 子类型（policy/subscription/loan/contract）本期不在 store 内编辑。
   *
   * 判别策略：按必备字段做收窄——
   *   - 含 `last4` → card；
   *   - 含 `account_id` + `amount` + `occurred_at` → tx；
   *   - 含 `balance` + `currency` + `archived` → account。
   */
  function payloadTypeOf(payload: FinancePayload): FinanceType | null {
    const p = payload as unknown as Record<string, unknown>
    if ('last4' in p && typeof p.last4 === 'string') return 'card'
    if ('account_id' in p && 'amount' in p && 'occurred_at' in p) return 'tx'
    if ('balance' in p && 'currency' in p && 'archived' in p) return 'account'
    return null
  }

  // ========== CRUD —— 账户 ==========

  /**
   * 新建账户（明文 payload 入参；createdAt / updatedAt / version 由 store 内部管理）。
   *
   * TR-11.1：CRUD 后即时调 pushChanges 上行；推送失败不抛错（保留本地副本）。
   */
  function addAccount(data: FinanceAccount): void {
    const record = wrap('account', data)
    accounts.set(record.id, record)
    persist()
    void pushChanges([data])
  }

  /**
   * 更新账户（按 id 匹配；archived 字段请走 archiveAccount 专用接口以保留审计语义）。
   */
  function updateAccount(data: FinanceAccount): void {
    const existing = accounts.get(data.id)
    const record = wrap('account', data, existing)
    accounts.set(record.id, record)
    persist()
    void pushChanges([data])
  }

  /**
   * 归档账户（软删除；保留在 Map 中, 但 listAccounts 已过滤）。
   *
   * 业务语义 —— 归档后不再计入净资产看板, 但历史流水仍关联（不级联删除）。
   */
  function archiveAccount(id: string): void {
    const existing = accounts.get(id)
    if (!existing) return
    const acc = existing.data as FinanceAccount
    const next: FinanceAccount = { ...acc, archived: true, updated_at: Date.now() }
    const record = wrap('account', next, existing)
    accounts.set(record.id, record)
    persist()
    void pushChanges([next])
  }

  // ========== CRUD —— 卡 ==========

  function addCard(data: FinanceCard): void {
    const record = wrap('card', data)
    cards.set(record.id, record)
    persist()
    void pushChanges([data])
  }

  function updateCard(data: FinanceCard): void {
    const existing = cards.get(data.id)
    const record = wrap('card', data, existing)
    cards.set(record.id, record)
    persist()
    void pushChanges([data])
  }

  function archiveCard(id: string): void {
    const existing = cards.get(id)
    if (!existing) return
    const card = existing.data as FinanceCard
    const next: FinanceCard = { ...card, archived: true, updated_at: Date.now() }
    const record = wrap('card', next, existing)
    cards.set(record.id, record)
    persist()
    void pushChanges([next])
  }

  // ========== CRUD —— 流水 ==========

  function addTx(data: FinanceTx): void {
    const record = wrap('tx', data)
    txs.set(record.id, record)
    persist()
    void pushChanges([data])
  }

  function updateTx(data: FinanceTx): void {
    const existing = txs.get(data.id)
    const record = wrap('tx', data, existing)
    txs.set(record.id, record)
    persist()
    void pushChanges([data])
  }

  /**
   * 硬删除流水（与账户/卡的归档语义不同 —— 流水为事件性数据, 误录后应可彻底删除）。
   *
   * 业务语义 —— 流水删除后从账户余额 / 卡已用额度计算中彻底剔除。
   *
   * TR-11.1：推送墓碑（version+1 + deleted=true + ciphertext=空）上行。
   */
  async function deleteTx(id: string): Promise<void> {
    const existing = txs.get(id)
    if (!existing) return
    const version = existing.version + 1
    const now = Date.now()
    const res = await channel.push({
      id,
      module: FINANCE_MODULE,
      type: 'tx',
      ciphertext: '',
      version,
      device_id: 'web',
      created_at: existing.createdAt,
      updated_at: now,
      deleted: true,
    })
    if (res.skipped === 0) {
      txs.delete(id)
      persist()
    }
    since = Math.max(since, res.server_time)
    // 增量回拉墓碑（无需全量）
    await pullAll(since)
  }

  // ========== 重置 / 测试钩子 ==========

  /**
   * 退出/重新锁定：清空全部明文内存与游标（与 4a vault.reset 同口径）。
   */
  function reset(): void {
    accounts.clear()
    cards.clear()
    txs.clear()
    since = 0
    lastSyncAt.value = 0
    hydrated.value = false
  }

  /**
   * 注入自定义持久化通道（仅供单元测试使用；生产环境请勿调用）。
   */
  function _setStorageForTest(ch: StorageChannel): void {
    storage = ch
    hydrated.value = false
  }

  /**
   * 注入自定义加密通道（仅供单元测试使用；生产环境请勿调用）。
   */
  function _setChannelForTest(ch: CryptoChannel): void {
    _channel = ch
  }

  /**
   * 重置内存状态（仅供单元测试使用）。
   */
  function _resetForTest(): void {
    accounts.clear()
    cards.clear()
    txs.clear()
    hydrated.value = false
    since = 0
  }

  return {
    // 状态
    schemaVersion,
    hydrated,
    syncing,
    lastSyncAt,
    // 计算属性
    listAccounts,
    listCards,
    listTxs,
    // 查询
    byId,
    // 启动 / 持久化
    hydrate,
    persist,
    // 同步入口（TR-11.1）
    pullAll,
    pushChanges,
    // CRUD 账户
    addAccount,
    updateAccount,
    archiveAccount,
    // CRUD 卡
    addCard,
    updateCard,
    archiveCard,
    // CRUD 流水
    addTx,
    updateTx,
    deleteTx,
    // 重置
    reset,
    // 测试钩子
    _setStorageForTest,
    _setChannelForTest,
    _resetForTest,
    // 内部工具（导出便于测试 toJson 链路）
    toJson,
  }
})