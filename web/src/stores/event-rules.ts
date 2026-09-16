// 阶段 4b — 日程事件 Pinia store（tasks.md Task 7 / TR-7.2）。
//
// 设计目标：
//   1. **CRUD + 展开**：增改/删除/拉取事件走加密链路；选中窗口展开复用
//      expand.ts（Task 2）；明文仅驻内存，不持久化到 localStorage/IndexedDB。
//   2. **加密链路复用**：与 4a vault.savePlace 同源码路径——
//        plaintext = JSON.stringify(EventRule)
//        sealed = sealRecord(sodium, mk, plaintext, id, 'event', version)
//        pushRecords([{id, module:'event', type:'event', ciphertext, ...}])
//      严格复用 crypto/envelope.sealRecord/openRecord + api.pushRecords/
//      listRecords；不新造 envelope 参数；AAD 沿用 `eve:v1:record:{id}`
//      （spec § 8.1 / module-schemas.md）。
//   3. **零知识红线**：store 内部明文 Map 仅存活于内存；任何调试 console
//      严禁打印 title/note/location_text 原文；测试断言使用脱敏标题。
//   4. **风格统一**：Pinia setup store（与 4a places/vault 同款）——
//      `defineStore('eventRules', () => { ... })` + `import { defineStore }
//      from 'pinia'` + `ref` / `computed` / `reactive`。
//
// 路径说明：本 store 文件名 `event-rules.ts` 而非 `events.ts`——后者已被
// 4a SSE channel（web/src/stores/events.ts）占用，为避免破坏 4a 既有契约，
// 选择新文件名；外部 `useEventRulesStore()` 调用接口与任务要求一致。
//
// 与 4a place 模块边界：本 store 不复用 vault.savePlace（其内部用 geohash7
// 幂等）；事件走 records LWW（version 严格递增）；与 place 同模块隔离。
//
// 模块挂载点：module='event' / type='event'（spec § 8.1 双键约定）。

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
import { expand, type TimeWindow, type Occurrence } from '../events/expand'
import type { EventRule, EventColor } from '../events/types'
import { EVENT_MODULE, EVENT_TYPE } from '../events/types'

// =============================================================================
// 内部缓存形态：明文 EventRule + 信封元数据（不落盘）
// =============================================================================

/**
 * store 内缓存的事件条目（明文 + 信封元数据）。
 * 与 4a vault.DecryptedPlaceRecord 同模式：明文 + version/createdAt/updatedAt。
 *
 * 注意：本结构仅存内存，刷新即清空；不参与 records 同步流程（同步由
 * vault.sync → ingest 还原密文 + 明文后再写入本 Map）。
 */
export interface CachedEventRule {
  id: string
  module: typeof EVENT_MODULE
  type: typeof EVENT_TYPE
  version: number
  createdAt: number
  updatedAt: number
  deleted: boolean
  data: EventRule
}

// =============================================================================
// 与 api/crypto 的契约：store 通过此对象访问加密 + 网络原语，便于测试 mock
// =============================================================================

/**
 * 加密链路契约接口（store 依赖注入点）。
 * 默认实现 = 直接调 crypto/envelope + api/client；测试时可整体 mock。
 */
export interface CryptoChannel {
  /** 密封一条 EventRule → 密文 Base64。 */
  seal(rule: EventRule, id: string, version: number): string
  /** 解密一条密文（Base64）→ EventRule。失败抛异常。 */
  open(id: string, module: string, ciphertextB64: string, version: number): EventRule
  /** 推送到远端 records。 */
  push(record: RemoteRecord): Promise<{ applied: number; skipped: number; server_time: number }>
  /** 拉取远端 records（since=0 即全量）。 */
  list(since: number): Promise<{ records: RemoteRecord[]; has_more: boolean }>
}

/** 构造默认 CryptoChannel（直接调既有 crypto/envelope + api/client）。 */
function defaultChannel(): CryptoChannel {
  const auth = useAuthStore()
  return {
    seal(rule, id, version) {
      const plaintext = new TextEncoder().encode(JSON.stringify(rule))
      const sealed = sealRecord(auth.sodium!, auth.masterKey!, plaintext, id, EVENT_MODULE, version)
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
      return JSON.parse(new TextDecoder().decode(plaintext)) as EventRule
    },
    push(record) {
      return api.pushRecords([record])
    },
    list(since) {
      return api.listRecords(since, 500)
    },
  }
}

// =============================================================================
// Pinia store
// =============================================================================

/**
 * 事件模块 store：CRUD + 加密链路复用 + 窗口展开。
 *
 * 状态拓扑：
 *   - rules: reactive Map<id, CachedEventRule>（明文 + 信封元数据，仅内存）
 *   - syncing / lastSyncAt：同步状态
 *   - byId(id)：同步读
 *   - list()：解密后的 EventRule[]（与 expand 入参形态一致）
 *   - upsert / delete / pull：调 CryptoChannel
 *   - occurrencesInWindow({from, to})：拼所有 rules 的 Occurrence 并按 start_ts 升序
 */
export const useEventRulesStore = defineStore('eventRules', () => {
  // 阶段 4b — 事件 store 仅调自身 CryptoChannel，不直接读 auth；保留 import 仅为类型
  // 兼容其他模块习惯（移除会触发 lint unused-import）。如果未来要做"未解锁禁写入"
  // 闸门可重新启用 auth.isUnlocked。
  // const auth = useAuthStore()
  /** id → CachedEventRule（reactive Map；Vue 可追踪 set/delete）。 */
  const rules = reactive(new Map<string, CachedEventRule>())
  const syncing = ref(false)
  const lastSyncAt = ref(0)
  /** 增量游标：最近一次成功推送/拉取的最大 updated_at（毫秒）。 */
  let since = 0
  /** 加密通道（默认直连；测试可注入 mock）。 */
  let channel: CryptoChannel = defaultChannel()

  // ----- 计算属性 -----

  /** 当前所有明文 EventRule（按 updatedAt 降序；与 vault.list 同口径）。 */
  const list = computed<EventRule[]>(() =>
    Array.from(rules.values())
      .filter((r) => !r.deleted)
      .sort((a, b) => b.updatedAt - a.updatedAt)
      .map((r) => r.data),
  )

  /** 同步读一条缓存（不解密；用于视图层按 id 即时取元数据）。 */
  function byId(id: string): CachedEventRule | undefined {
    return rules.get(id)
  }

  // ----- 内部工具 -----

  /**
   * 把远端 RemoteRecord（密文 + 元数据）解密为 CachedEventRule 写入 Map。
   * 失败：抛异常让上层处理（与 vault.ingestPlace 不同的纪律——本 store
   * 显式失败以便测试用例验证"解密失败抛异常不静默"，FR-NFR-1）。
   */
  function ingest(remote: RemoteRecord) {
    // 墓碑：本地移除（不写解密失败记录）。
    if (remote.deleted) {
      rules.delete(remote.id)
      return
    }
    // 版本不回退：低版本重复到达不覆盖缓存（与 vault.ingest 同口径）。
    const existed = rules.get(remote.id)
    if (existed && existed.version >= remote.version) return
    // 解密（失败抛异常，调用方可观测）。
    const data = channel.open(remote.id, remote.module, remote.ciphertext, remote.version)
    rules.set(remote.id, {
      id: remote.id,
      module: remote.module as typeof EVENT_MODULE,
      type: remote.type as typeof EVENT_TYPE,
      version: remote.version,
      createdAt: remote.created_at,
      updatedAt: remote.updated_at,
      deleted: false,
      data,
    })
  }

  // ----- 同步 -----

  /**
   * 全量（full=true）或增量拉取；分页直到 has_more=false。
   * 仅处理 module==='event' 的记录（其它模块密文原样跳过，由 vault
   * 既有链路接管，不在本 store 介入）。
   *
   * 不检查 auth.unlocked：channel 是注入点；调用方（视图层/集成层）
   * 负责确保在解锁态下调用；测试时可注入 mock 通道绕开默认 channel
   * 的 auth.sodium/masterKey 依赖。这是与 vault.pull 的边界差异——
   * vault.pull 必须检查 unlocked（默认 channel 直连 auth）；本 store
   * 注入式设计允许单元测试独立跑通。
   */
  async function pull(full = false): Promise<void> {
    if (syncing.value) return
    syncing.value = true
    try {
      let cursor = full ? 0 : since
      for (;;) {
        const page = await channel.list(cursor)
        // 仅消化 event 模块的远端记录。
        page.records.filter((r) => r.module === EVENT_MODULE).forEach(ingest)
        if (page.records.length) {
          cursor = Math.max(
            cursor,
            ...page.records.filter((r) => r.module === EVENT_MODULE).map((r) => r.updated_at),
          )
        }
        if (!page.has_more) break
      }
      since = cursor
      lastSyncAt.value = Date.now()
    } finally {
      syncing.value = false
    }
  }

  /**
   * 阶段 4b / Task 10（TR-10.1）外部同步入口：
   * 集成层（vault 同步流程）调用本方法触发"拉取 + 解密 + 入 store"全流程。
   *
   * 与 `pull(full)` 的差异：
   *  - 显式接受 `sinceMs`（毫秒游标），便于上层把 vault 同步游标复用给
   *    本 store；sinceMs=0 即全量；与 `pull(full)` 行为一致。
   *  - 解密失败沿用 store 既有纪律：抛异常不静默（NFR-1 / AC-11 红线）。
   *
   * 注意：本方法不调 vault 已有 sync 流程——避免与 4a place 模块互相触发
   * 循环；调用方负责编排顺序（典型：vault.sync → eventsStore.pullAll）。
   */
  async function pullAll(sinceMs: number): Promise<void> {
    // sinceMs=0 时走全量；非零沿用现有 since 游标叠加（保留上次成功水位）。
    if (sinceMs <= 0) {
      await pull(true)
      return
    }
    // 增量：游标在 pull 内部已维护为 `since`，调用方传 sinceMs 时
    // 先把 store 内部游标提升，再走 pull(false)。
    since = Math.max(since, sinceMs)
    await pull(false)
  }

  // ----- CRUD -----

  /**
   * 新建或编辑保存：构造明文 EventRule → sealRecord → pushRecords。
   * version 严格递增；成功后即时写本地缓存（视图层立即可见，无需等待
   * 下一轮同步——与 vault.savePlace 同模式）。
   *
   * @param rule 完整 EventRule（含 id；调用方负责生成 UUID v4）
   */
  async function upsert(rule: EventRule): Promise<void> {
    const existing = rules.get(rule.id)
    const version = (existing?.version ?? 0) + 1
    const now = Date.now()
    const ciphertext = channel.seal(rule, rule.id, version)
    const res = await channel.push({
      id: rule.id,
      module: EVENT_MODULE,
      type: EVENT_TYPE,
      ciphertext,
      version,
      device_id: 'web',
      created_at: existing?.createdAt ?? now,
      updated_at: now,
      deleted: false,
    })
    // 推送失败（skipped>0 即远端版本领先）：触发全量补同步，不写假状态。
    if (res.skipped > 0) {
      await pull(true)
      return
    }
    // 推送成功：写入本地缓存（updatedAt 取服务端权威时间，FU-1 同款）。
    rules.set(rule.id, {
      id: rule.id,
      module: EVENT_MODULE,
      type: EVENT_TYPE,
      version,
      createdAt: existing?.createdAt ?? now,
      updatedAt: res.server_time,
      deleted: false,
      data: rule,
    })
    since = Math.max(since, res.server_time)
  }

  /**
   * 删除：推送高版本墓碑（密文置空），本地移除。
   * 与 vault.remove 同链路；事件模块额外拉一次增量以同步其它设备的墓碑。
   */
  async function remove(id: string): Promise<void> {
    const existing = rules.get(id)
    const version = (existing?.version ?? 0) + 1
    const now = Date.now()
    const res = await channel.push({
      id,
      module: EVENT_MODULE,
      type: EVENT_TYPE,
      ciphertext: '',
      version,
      device_id: 'web',
      created_at: existing?.createdAt ?? now,
      updated_at: now,
      deleted: true,
    })
    if (res.skipped === 0) rules.delete(id)
    since = Math.max(since, res.server_time)
    await pull() // 增量回拉墓碑（无需全量）
  }

  /**
   * 阶段 4b / Task 10（TR-10.1）外部上行入口：
   * 集成层在本地 dirty 标记 → 上行的场景下批量推送一组 EventRule。
   *
   * 当前 store 的 upsert 本身就是"密文 + pushRecords"链路；本方法仅作为
   * 任务规范要求的 pushChanges 命名暴露，便于集成层显式调用。
   *
   * 调用约束：
   *  - 调用前调用方需把 dirty 规则列表聚合出来（CRUD 后即时推送；
   *    重连场景下聚合"远端版本落后于本地 version 的"行）；
   *  - 不写 localStorage / IndexedDB / console 日志（NFR-1）。
   *
   * @param rulesToPush 明文 EventRule 数组（id 已就位）；按数组顺序串行推送
   */
  async function pushChanges(rulesToPush: EventRule[]): Promise<void> {
    for (const rule of rulesToPush) {
      // 沿用 upsert 的"单条 sealRecord + pushRecords"链路；
      // version 严格递增以遵循 records LWW（FR-3）。
      await upsert(rule)
    }
  }

  // ----- 展开（核心 API：被 T8 MonthView/WeekView 调用） -----

  /**
   * 计算指定窗口内的所有 Occurrence。
   * 算法：
   *   1. 遍历 rules（明文 EventRule 缓存）；
   *   2. 每个 rule 调 expand(rule, window) 得到 occurrence 子集；
   *   3. 全部 occurrence 合并，按 start_ts asc 排序返回。
   *
   * 复用 expand.ts（Task 2 / TR-2.1 已落，25 用例全绿）保证与 Android 端
   * Recurrence.kt 字节级一致行为（fixture SHA-256 已对齐，TR-3.3）。
   */
  function occurrencesInWindow(window: TimeWindow): Occurrence[] {
    const all: Occurrence[] = []
    for (const cached of rules.values()) {
      if (cached.deleted) continue
      const items = expand(cached.data, window)
      all.push(...items)
    }
    all.sort((a, b) => a.start_ts - b.start_ts)
    return all
  }

  // ----- 重置（锁定时清空明文） -----

  /** 退出/重新锁定：清空全部明文内存与游标。 */
  function reset() {
    rules.clear()
    since = 0
    lastSyncAt.value = 0
  }

  /** 测试钩子：注入加密通道（默认实现外可整体替换为 mock）。 */
  function _setChannelForTest(c: CryptoChannel) {
    channel = c
  }

  return {
    rules,
    syncing,
    lastSyncAt,
    list,
    byId,
    pull,
    pullAll,
    upsert,
    remove,
    pushChanges,
    occurrencesInWindow,
    reset,
    _setChannelForTest,
  }
})

// =============================================================================
// 色板兜底（视图层渲染未指定颜色时使用；与 spec § 8.2 一致）
// =============================================================================

/**
 * 8 色板兜底默认色（视图层新建空白事件时预填）。
 * 不参与 store 状态；纯常量。
 */
export const DEFAULT_EVENT_COLOR: EventColor = 'blue'