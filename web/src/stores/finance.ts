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
  AttachmentRef,
  CachedFinanceRecord,
  FinanceAccount,
  FinanceCard,
  FinanceContract,
  FinanceLoan,
  FinancePayload,
  FinancePayloadAll,
  FinancePolicy,
  FinanceSubscription,
  FinanceTx,
  FinanceType,
  FinanceV2Payload,
} from '../finance/types'
import {
  DEFAULT_CURRENCY,
  FINANCE_MODULE,
  validateV2Payload,
  type ValidationResult,
} from '../finance/types'
import { parseRateTable, type RateTable } from '../finance/rateTable'
import {
  defaultAttachmentChannel,
  uploadFile,
  type AttachmentChannel,
  type AttachmentRecord,
} from '../finance/attachment'
import {
  nextLoanDue,
  nextPolicyExpiry,
  nextSubscriptionRenewal,
  toLoanLike as toFiringLoanLike,
  toPolicyLike,
  toSubscriptionLike,
} from '../finance/nextCardFiring'

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
  schemaVersion: 1 | 2
  accounts: FinanceAccount[]
  cards: FinanceCard[]
  txs: FinanceTx[]
  /** v2 子类型（v1 阶段固定空数组；v2 B1 启用 4 子类型编辑）。 */
  subscriptions: FinanceSubscription[]
  policies: FinancePolicy[]
  loans: FinanceLoan[]
  contracts: FinanceContract[]
  /**
   * B5 离线汇率表（stage5-finance-v2 / FR-V2-C.2、FR-V2-C.3）。
   *
   * 可选字段：旧本地数据（schemaVersion=1/2 早期形态）无此字段，hydrate
   * 时降级为 null（不升 schemaVersion，保持 2）；null 表示未导入汇率包，
   * aggregator 一律按面值 1:1 口径计入。
   */
  rateTable?: RateTable | null
  /**
   * B5 默认 / 折算目标币种（ISO 4217 三字母代码；缺省即 DEFAULT_CURRENCY
   * = "CNY"）。旧本地数据无此字段时 hydrate 降级 "CNY"。
   */
  defaultCurrency?: string
  /**
   * B5 汇率包 records 通道信封版本表（FR-V2-C.2）。
   *
   * key = 汇率包确定性记录 id（`rate@${effective_ts}`，与 Android
   * RateTableRepository 同键），value = 最近一次成功上行 / 下行的信封 version。
   * 同一生效时刻重复导入时 version 必须严格递增，否则服务端 LWW 判 skipped。
   * 可选字段：旧本地数据无此字段时降级空表。
   */
  rateRecordVersions?: Record<string, number>
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
 *
 * v2 扩展：seal/open 同步支持 FinanceV2Payload（subscription / policy /
 * loan / contract）；AAD `type` 标识区分 v2 子类型，records 通道复用。
 */
export interface CryptoChannel {
  /**
   * 密封一条明文 payload → 密文 Base64。
   *
   * 入参含业务 payload（FinancePayloadAll）与非业务记录（B5 汇率包
   * type='rate'：{version,effective_ts,rates} 普通对象）；默认实现只做
   * JSON.stringify，两类对象同一路径密封，AAD 均为 module='finance'。
   */
  seal(payload: FinancePayloadAll | Record<string, unknown>, id: string, version: number): string
  /** 解密一条密文（Base64）→ 明文 payload。失败抛异常。 */
  open(id: string, module: string, ciphertextB64: string, version: number): FinancePayloadAll
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

// ============================================================================
// v2 提醒纯计算出口类型（stage5-finance-v2 / Task 4 / TR-4.3）
// ============================================================================

/**
 * v2 未来提醒条目（upcomingV2Reminders 的数组元素）。
 *
 * - kind=subscription_renewal：订阅续费提醒（nextSubscriptionRenewal）；
 * - kind=policy_expiry：保单到期提醒（nextPolicyExpiry）；
 * - kind=loan_due：借款到期提醒（nextLoanDue）。
 */
export interface UpcomingV2Reminder {
  kind: 'subscription_renewal' | 'policy_expiry' | 'loan_due'
  id: string
  /** 触发时刻（Unix 毫秒，严格晚于传入的 nowMs）。 */
  triggerMs: number
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
  /** v2 子类型（v2 B1 启用）。 */
  const subscriptions = reactive(new Map<string, CachedFinanceRecord>())
  const policies = reactive(new Map<string, CachedFinanceRecord>())
  const loans = reactive(new Map<string, CachedFinanceRecord>())
  const contracts = reactive(new Map<string, CachedFinanceRecord>())
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
  // ========== 附件子状态（TR-3.3 + TR-3.4） ==========
  /** 附件元数据缓存（id → AttachmentRef）。 */
  const attachments = reactive(new Map<string, AttachmentRef>())
  /** 二级索引：recordId → 附件 id 集合（用于 UI 列表按 recordId 拉取）。 */
  const attachmentsByRecordId = reactive(new Map<string, Set<string>>())
  /** 附件密文缓存（id → AttachmentRecord；含 ciphertext + plaintextJson 元数据）。 */
  const attachmentCipherCache = reactive(new Map<string, AttachmentRecord>())
  /**
   * 附件加密通道（默认直连 envelope + 内存 remote；测试可注入）。
   *
   * 注意：channel.upsert 写入 attachmentCipherCache（pinia 内存）；
   *       channel.listByRecord 从 cache 过滤 recordId；二者共用同一份缓存。
   */
  const attachmentRemote = new Map<string, AttachmentRecord>()
  let _attachmentChannel: AttachmentChannel | null = null
  /**
   * 懒构造附件 channel（首次调用才走 defaultAttachmentChannel，避免
   * pinia 未激活场景触发 useAuthStore 引用错误；测试注入后即可绕过）。
   */
  function getAttachmentChannel(): AttachmentChannel {
    if (_attachmentChannel) return _attachmentChannel
    const auth = useAuthStore()
    if (!auth.sodium || !auth.masterKey) {
      throw new Error('附件通道未初始化：请先解锁（auth.masterKey 不存在）')
    }
    _attachmentChannel = defaultAttachmentChannel(auth.sodium, auth.masterKey, attachmentRemote)
    return _attachmentChannel
  }
  /** schema 版本（v1=1；v2 B1 启用时升 2；schemaVersion>2 由迁移接管）。 */
  const schemaVersion = ref<1 | 2>(1)
  /** startup hydration 是否完成（首屏 UI 据此决定是否展示骨架）。 */
  const hydrated = ref(false)
  /** 同步状态：syncing / lastSyncAt。 */
  const syncing = ref(false)
  const lastSyncAt = ref(0)
  /** 增量游标：最近一次成功推送/拉取的最大 updated_at（毫秒）。 */
  let since = 0

  // ========== B5 多币种汇率状态（stage5-finance-v2 / FR-V2-C.1 ~ C.3） ==========
  /**
   * 已导入的离线汇率表（null = 未导入；aggregator 按面值 1:1 口径计入）。
   * 仅手动 JSON 导入产生（parseRateTable 校验通过后赋值），本批次不接网络。
   */
  const rateTable = ref<RateTable | null>(null)
  /** 默认币种（看板 / 月报折算目标币；ISO 4217 三字母代码，缺省 CNY）。 */
  const defaultCurrency = ref<string>(DEFAULT_CURRENCY)
  /**
   * 已上行 / 下行汇率包的信封版本（key=`rate@${effectiveTs}`）。
   * 同生效时刻重复导入时 version 在此基础上 +1，避免服务端 LWW 判 skipped；
   * 随持久化落盘，跨刷新保留。
   */
  const rateRecordVersions = ref<Record<string, number>>({})

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

  /** 所有订阅（按 next_renewal_ts 升序；即将到期在前）。 */
  const listSubscriptions = computed<FinanceSubscription[]>(() =>
    Array.from(subscriptions.values())
      .filter((r) => !r.deleted)
      .sort((a, b) => {
        const aS = a.data as unknown as FinanceSubscription
        const bS = b.data as unknown as FinanceSubscription
        return aS.next_renewal_ts - bS.next_renewal_ts
      })
      .map((r) => r.data as unknown as FinanceSubscription),
  )

  /** 所有保单（按 expiry_ts 升序；即将到期在前）。 */
  const listPolicies = computed<FinancePolicy[]>(() =>
    Array.from(policies.values())
      .filter((r) => !r.deleted)
      .sort((a, b) => {
        const aP = a.data as unknown as FinancePolicy
        const bP = b.data as unknown as FinancePolicy
        return aP.expiry_ts - bP.expiry_ts
      })
      .map((r) => r.data as unknown as FinancePolicy),
  )

  /** 所有应收借款（按 due_ts 升序）。 */
  const listLoans = computed<FinanceLoan[]>(() =>
    Array.from(loans.values())
      .filter((r) => !r.deleted)
      .sort((a, b) => {
        const aL = a.data as unknown as FinanceLoan
        const bL = b.data as unknown as FinanceLoan
        return aL.due_ts - bL.due_ts
      })
      .map((r) => r.data as unknown as FinanceLoan),
  )

  /** 所有合同（按 end_ts 升序）。 */
  const listContracts = computed<FinanceContract[]>(() =>
    Array.from(contracts.values())
      .filter((r) => !r.deleted)
      .sort((a, b) => {
        const aC = a.data as unknown as FinanceContract
        const bC = b.data as unknown as FinanceContract
        return aC.end_ts - bC.end_ts
      })
      .map((r) => r.data as unknown as FinanceContract),
  )

  // ========== v2 提醒纯计算出口（TR-4.3） ==========

  /**
   * 同 triggerMs 时的 kind 固定先后：subscription_renewal > policy_expiry >
   * loan_due（rank 越小越靠前）。
   */
  const REMINDER_KIND_RANK: Record<UpcomingV2Reminder['kind'], number> = {
    subscription_renewal: 0,
    policy_expiry: 1,
    loan_due: 2,
  }

  /**
   * v2 三类条目的"下一提醒"纯计算出口。
   *
   * 这是 Android 端 v1 单闹钟链式调度（AlarmManager + ReminderScheduler）在
   * Web 端的等价纯计算：Web 没有 AlarmManager / 后台 scheduler，store 本身也
   * 不持有调度器，因此这里只产出数据（供 Dashboard 倒计时卡片与未来 Web 通知
   * 通道复用）；本函数不做浏览器 Notification（通知权限 / 弹出属于 G-7 范围）。
   *
   * 行为契约：
   *   1. 消费已缓存的 subscriptions / policies / loans（store 内明文
   *      snake_case 形态），经 toSubscriptionLike / toPolicyLike /
   *      toFiringLoanLike 适配器后交给 nextCardFiring 三个纯函数；
   *   2. null 结果（停用 / 已结清 / 无 reminders / 全部候选过期等）一律过滤；
   *   3. 返回数组按 triggerMs 升序；同一 triggerMs 时按 kind 固定顺序
   *      subscription_renewal → policy_expiry → loan_due 排列；同 kind 的
   *      并列保持列表顺序（Array.prototype.sort 稳定排序）；
   *   4. 纯函数语义：不修改 store 状态、不写存储、不发起网络请求。
   *
   * @param nowMs 当前时刻 Unix 毫秒；缺省 Date.now()，测试可显式锚定
   * @returns 升序的未来提醒条目数组；无任何未来提醒时返回空数组
   */
  function upcomingV2Reminders(nowMs: number = Date.now()): UpcomingV2Reminder[] {
    const items: UpcomingV2Reminder[] = []

    for (const sub of listSubscriptions.value) {
      const triggerMs = nextSubscriptionRenewal(toSubscriptionLike(sub), nowMs)
      if (triggerMs !== null) {
        items.push({ kind: 'subscription_renewal', id: sub.id, triggerMs })
      }
    }
    for (const policy of listPolicies.value) {
      const triggerMs = nextPolicyExpiry(toPolicyLike(policy), nowMs)
      if (triggerMs !== null) {
        items.push({ kind: 'policy_expiry', id: policy.id, triggerMs })
      }
    }
    for (const loan of listLoans.value) {
      const triggerMs = nextLoanDue(toFiringLoanLike(loan), nowMs)
      if (triggerMs !== null) {
        items.push({ kind: 'loan_due', id: loan.id, triggerMs })
      }
    }

    // triggerMs 升序；同 ts 按 kind 固定顺序（subscription → policy → loan）。
    items.sort(
      (a, b) =>
        a.triggerMs - b.triggerMs
        || REMINDER_KIND_RANK[a.kind] - REMINDER_KIND_RANK[b.kind],
    )
    return items
  }

  // ========== 附件 getters ==========

  /** 全部附件元数据（按 sha256 前 8 位分组无关；仅按 id 顺序输出）。 */
  const listAttachments = computed<AttachmentRef[]>(() =>
    Array.from(attachments.values()).filter((r) => {
      const cipher = attachmentCipherCache.get(r.id)
      return cipher == null || !cipher.deleted
    }),
  )

  /**
   * 按 recordId 拉附件列表（供 UI 编辑器调用）。
   *
   * 返回 reactive 数组——响应式订阅自动触发 UI 更新。
   *
   * 行为契约：
   *   - cipher 缺失时仍可返回（addAttachment 直接元数据路径；密文通过
   *     channel.upsert 单独上传，cipher 缓存可能稍后才到位）；
   *   - cipher 存在但 deleted=true 时过滤（墓碑不可见）。
   */
  function getAttachmentsForRecord(recordId: string): AttachmentRef[] {
    const ids = attachmentsByRecordId.get(recordId)
    if (!ids) return []
    const out: AttachmentRef[] = []
    for (const id of ids) {
      const ref = attachments.get(id)
      if (!ref) continue
      const cipher = attachmentCipherCache.get(id)
      if (cipher && cipher.deleted) continue
      out.push(ref)
    }
    return out
  }

  /** 取单个附件元数据（无 → undefined）。 */
  function getAttachmentMeta(id: string): AttachmentRef | undefined {
    return attachments.get(id)
  }

  // ========== 查询接口 ==========

  function byId(type: FinanceType, id: string): CachedFinanceRecord | undefined {
    switch (type) {
      case 'account':
        return accounts.get(id)
      case 'card':
        return cards.get(id)
      case 'tx':
        return txs.get(id)
      case 'subscription':
        return subscriptions.get(id)
      case 'policy':
        return policies.get(id)
      case 'loan':
        return loans.get(id)
      case 'contract':
        return contracts.get(id)
    }
  }

  // ========== 内部工具 ==========

  /**
 * 把明文 payload 包装为 CachedFinanceRecord，version 自增。
   *
   * 注意：createdAt 优先取 existing.createdAt（保持首次创建时间稳定），
   * updatedAt 在推送成功后由 pushChanges 覆写为服务端权威时间。
   *
   * v2 扩展：type ∈ {subscription, policy, loan, contract} 时同样适用，
   * 通过校验函数 validateV2Payload 兜底。
   */
  function wrap(
    type: FinanceType,
    data: FinancePayloadAll,
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

  /**
   * v2 子类型校验入口（store 写入前的硬闸门）。
   *
   * 校验失败抛 Error，调用方捕获后按"丢数据兜底"处理（v1 阶段策略）。
   */
  function assertValidV2(type: FinanceType, data: unknown): asserts data is FinanceV2Payload {
    if (type !== 'subscription' && type !== 'policy' && type !== 'loan' && type !== 'contract') {
      return
    }
    const r: ValidationResult = validateV2Payload(type, data)
    if (!r.ok) throw new Error(`v2 ${type} 校验失败: ${r.reason}`)
  }

  /** 把明文 payload 序列化为 JSON（沿用 schema snake_case 字段名）。 */
  function toJson(data: FinancePayloadAll): string {
    return JSON.stringify(data)
  }

  /**
   * 把远端 RemoteRecord（module=finance）解密后入 Map；墓碑删除；
   * 版本不回退；解密失败抛异常（与 4b event-rules.ingest 同纪律）。
   *
   * v2 扩展：type ∈ {subscription, policy, loan, contract} 时路由到对应 Map；
   * 解密后立即调 validateV2Payload 兜底（保证入栈前数据合规）。
   */
  function ingest(remote: RemoteRecord): void {
    // ========== B5 汇率包分支（type='rate'；FR-V2-C.2） ==========
    // 汇率包不是业务 payload（无账户/卡/四子类型缓存 Map），在此提前路由，
    // 不走下方业务墓碑删除与 v2 校验逻辑。
    if (remote.type === 'rate') {
      // 汇率包以"新生效时刻覆盖"为语义，无行级删除；墓碑忽略。
      if (remote.deleted) return
      try {
        // channel.open 运行时就是 JSON.parse；汇率包按普通对象解密后再交给
        // parseRateTable 严格校验（坏包 / 非预期明文直接丢弃，不污染看板）。
        const obj = channel.open(
          remote.id,
          remote.module,
          remote.ciphertext,
          remote.version,
        ) as unknown as Record<string, unknown>
        const table = parseRateTable(JSON.stringify(obj))
        // 记录信封版本（供同生效时刻再次导入时 version 递增）。
        rateRecordVersions.value = {
          ...rateRecordVersions.value,
          [remote.id]: remote.version,
        }
        // 多包并存时取 effectiveTs 最大者（与 Android latest() 口径一致）。
        if (rateTable.value == null || table.effectiveTs >= rateTable.value.effectiveTs) {
          rateTable.value = table
        }
        persist()
      } catch {
        // MK 未就绪 / 密文损坏 / 包格式非法 → 跳过该条，不阻塞其他记录 ingest。
      }
      return
    }

    // 墓碑：按 type 路由删除本地缓存。
    if (remote.deleted) {
      accounts.delete(remote.id)
      cards.delete(remote.id)
      txs.delete(remote.id)
      subscriptions.delete(remote.id)
      policies.delete(remote.id)
      loans.delete(remote.id)
      contracts.delete(remote.id)
      // 附件墓碑：从密文缓存 + 二级索引移除（attachmentCipherCache 保留墓碑用于审计）。
      if (remote.type === 'attachment') {
        const cipher = attachmentCipherCache.get(remote.id)
        if (cipher) {
          cipher.deleted = true
          cipher.updated_at = remote.updated_at
          cipher.version = Math.max(cipher.version, remote.version)
          const set = attachmentsByRecordId.get(cipher.recordId)
          if (set) {
            set.delete(remote.id)
            if (set.size === 0) attachmentsByRecordId.delete(cipher.recordId)
          }
        }
      }
      return
    }
    // ========== 附件 ingest（type='attachment'） ==========
    // 附件密文走独立通道：解密 plaintextJson → 还原 AttachmentRef 元数据；
    // ciphertext 存 attachmentCipherCache（不存普通 Map）。
    if (remote.type === 'attachment') {
      let data: unknown
      try {
        data = channel.open(remote.id, remote.module, remote.ciphertext, remote.version)
      } catch {
        throw new Error('附件解密失败')
      }
      // 解密产物 = plaintextJson 字节流（Uint8Array → TextDecoder → JSON.parse）。
      let meta: { mime: string; size: number; sha256: string; recordId?: string }
      try {
        // crypto/envelope.openRecord 已规范返回 Uint8Array；某些 mock 通道
        // 可能返回 Buffer 等 BufferSource 兼容类型，统一按 Uint8Array 处理。
        const bytes: Uint8Array =
          data instanceof Uint8Array
            ? data
            : ArrayBuffer.isView(data)
              ? new Uint8Array(data.buffer.slice(data.byteOffset, data.byteOffset + data.byteLength))
              : new Uint8Array(data as ArrayBufferLike)
        meta = JSON.parse(new TextDecoder().decode(bytes)) as {
          mime: string
          size: number
          sha256: string
          recordId?: string
        }
      } catch {
        throw new Error('附件明文 JSON 解析失败')
      }
      // 端侧 recordId 索引（plaintextJson.recordId）—— 优先级 > AAD。
      const recordId = meta.recordId ?? ''
      const ref: AttachmentRef = {
        id: remote.id,
        mime: meta.mime,
        size: meta.size,
        sha256: meta.sha256,
      }
      attachments.set(remote.id, ref)
      attachmentCipherCache.set(remote.id, {
        id: remote.id,
        ciphertext: remote.ciphertext,
        version: remote.version,
        recordId,
        plaintextJson: JSON.stringify(meta),
        deleted: false,
        device_id: 'web',
        created_at: remote.created_at,
        updated_at: remote.updated_at,
      })
      // 二级索引维护。
      const set = attachmentsByRecordId.get(recordId)
      if (set) {
        set.add(remote.id)
      } else {
        attachmentsByRecordId.set(recordId, new Set([remote.id]))
      }
      since = Math.max(since, remote.updated_at)
      return
    }
    const type = remote.type as FinanceType
    const target = mapForType(type)
    if (target == null) return // 未知子类型：保留在服务端，不在 UI 暴露
    const existed = target.get(remote.id)
    // 版本不回退：低版本重复到达不覆盖缓存。
    if (existed && existed.version >= remote.version) return
    // 解密（失败抛异常，让上层观测）。
    const data = channel.open(remote.id, remote.module, remote.ciphertext, remote.version)
    // v2 校验（v1 类型跳过）：失败抛异常（与 4b 同纪律）。
    if (type === 'subscription' || type === 'policy' || type === 'loan' || type === 'contract') {
      const r: ValidationResult = validateV2Payload(type, data)
      if (!r.ok) throw new Error(`远端 v2 ${type} 校验失败: ${r.reason}`)
    }
    target.set(remote.id, {
      id: remote.id,
      module: FINANCE_MODULE,
      type,
      version: remote.version,
      createdAt: remote.created_at,
      updatedAt: remote.updated_at,
      deleted: false,
      data: data as FinancePayloadAll,
    })
    since = Math.max(since, remote.updated_at)
  }

  /**
   * 按 type 路由到对应 Map；v1+v2 共 7 子类型。
   */
  function mapForType(type: FinanceType): Map<string, CachedFinanceRecord> | null {
    switch (type) {
      case 'account': return accounts
      case 'card': return cards
      case 'tx': return txs
      case 'subscription': return subscriptions
      case 'policy': return policies
      case 'loan': return loans
      case 'contract': return contracts
    }
  }

  /**
   * 推送单条 CachedFinanceRecord 上行（v1+v2 共 7 子类型）。
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

  // ========== B5 离线汇率包 / 默认币种（FR-V2-C.1、FR-V2-C.2） ==========

  /**
   * 导入 spec FR-V2-C.2 离线汇率包明文 JSON。
   *
   * 流程：
   *  1. parseRateTable 全量校验（version / effective_ts / rates 键值）；
   *     失败 → 状态保持不变，返回 ok:false 与中文错误原因（UI 层 NMessage）；
   *  2. 成功 → 覆写 rateTable + persist（本地即时可用，未解锁 / 离线也生效）；
   *  3. **加密上行（FR-V2-C.2）**：确定性记录 id=`rate@${effectiveTs}`
   *     （与 Android RateTableRepository 同键），version 按 rateRecordVersions
   *     严格递增，经 channel.seal 密封后 channel.push 推 records 通道
   *     type='rate'；skipped（版本竞争）→ 触发全量补拉对账；
   *  4. 上行异常（MK 未就绪 / 网络）不回滚本地状态，synced=false 告知调用方；
   *     对端可由下一次同生效时刻导入补推（汇率包幂等覆盖）。
   *
   * @param json 汇率包明文 JSON 字符串（FileReader.readAsText 产物）
   */
  async function importRateTable(
    json: string,
  ): Promise<{ ok: true; table: RateTable; synced: boolean } | { ok: false; error: string }> {
    let table: RateTable
    try {
      table = parseRateTable(json)
    } catch (e) {
      // parseRateTable 抛出的 message 已是中文人类可读说明；非 Error 兜底。
      const error = e instanceof Error ? e.message : '汇率包导入失败：未知错误'
      return { ok: false, error }
    }

    // 本地先落地：解锁 / 离线状态下导入也立即可用于看板折算。
    rateTable.value = table
    persist()

    // records 通道加密上行（best-effort；与 Android upsertFinanceV2 同语义）。
    let synced = false
    try {
      const id = ratePackageId(table.effectiveTs)
      const version = (rateRecordVersions.value[id] ?? 0) + 1
      const now = Date.now()
      const ciphertext = channel.seal(JSON.parse(json) as Record<string, unknown>, id, version)
      const res = await channel.push({
        id,
        module: FINANCE_MODULE,
        type: 'rate',
        ciphertext,
        version,
        device_id: 'web',
        created_at: now,
        updated_at: now,
        deleted: false,
      })
      if (res.skipped > 0) {
        // 版本竞争：以服务端为准全量补拉（ingest 内会刷新表与版本号）。
        await pullAll(0)
      } else {
        rateRecordVersions.value = { ...rateRecordVersions.value, [id]: version }
        persist()
        synced = true
      }
    } catch {
      // 未解锁 / 离线 / 推送失败：本地表已落地，synced=false 由 UI 提示，不回滚。
    }
    return { ok: true, table, synced }
  }

  /** 汇率包 records 通道确定性 id（与 Android RateTableRepository 同键）。 */
  function ratePackageId(effectiveTs: number): string {
    return `rate@${effectiveTs}`
  }

  /**
   * 设置默认币种（看板 / 月报折算目标币）。
   *
   * 仅接受 ISO 4217 粗校验（/^[A-Z]{3}$/，3 位大写字母）；小写 / 长度不符
   * 一律拒绝且状态不变。预设 CNY/USD/EUR/JPY/HKD 由 UI 层提供，store 不内置。
   *
   * @returns true 表示已更新并持久化；false 表示校验拒绝
   */
  function setDefaultCurrency(code: string): boolean {
    if (typeof code !== 'string' || !/^[A-Z]{3}$/.test(code)) return false
    defaultCurrency.value = code
    persist()
    return true
  }

  /**
   * 移除当前汇率包（设置页"移除当前汇率包"按钮；二次确认在 UI 层完成）。
   * 移除后 rateTable=null，看板回到 v1 面值 1:1 口径；默认币种设置保留。
   */
  function clearRateTable(): void {
    rateTable.value = null
    persist()
  }

  /**
   * hydrate 辅助：从持久化形态还原 RateTable。
   *
   * 旧本地数据无该字段（undefined）→ null；字段存在但形态被破坏（本地
   * JSON 损坏 / 手工篡改）时同样安全降级 null，不阻断其余条目 hydrate。
   */
  function restoreRateTable(raw: unknown): RateTable | null {
    if (raw === null || typeof raw !== 'object' || Array.isArray(raw)) return null
    const obj = raw as Record<string, unknown>
    if (
      typeof obj.effectiveTs !== 'number'
      || !Number.isFinite(obj.effectiveTs)
      || obj.effectiveTs < 0
    ) {
      return null
    }
    const rates = obj.rates
    if (rates === null || typeof rates !== 'object' || Array.isArray(rates)) return null
    return { effectiveTs: obj.effectiveTs, rates: rates as Record<string, number> }
  }

  /**
   * hydrate 辅助：从持久化形态还原汇率包信封版本表。
   *
   * 仅接受 key 为字符串、value 为正整数的条目；null / 非对象 / 被篡改字段
   * 一律安全降级为空表（最坏后果是下次导入从 version=1 重新对账）。
   */
  function restoreRateVersions(raw: unknown): Record<string, number> {
    if (raw === null || typeof raw !== 'object' || Array.isArray(raw)) return {}
    const out: Record<string, number> = {}
    for (const [k, v] of Object.entries(raw as Record<string, unknown>)) {
      if (typeof k === 'string' && k.startsWith('rate@') && Number.isInteger(v) && (v as number) > 0) {
        out[k] = v as number
      }
    }
    return out
  }

  // ========== 启动 hydration ==========

  /**
   * 从持久化通道恢复 entries；首屏 UI 渲染前调用。
   *
   * 幂等 —— 多次调用不会重复注入；hydrated=true 后再次调用直接返回。
   *
   * v2 扩展：hydrate 时按子类型还原；schemaVersion=1 时 v2 字段缺失（容错为
   * 空数组）；schemaVersion=2 时按完整形态还原。
   */
  function hydrate(): void {
    if (hydrated.value) return
    const state = storage.read()
    if (state == null) {
      schemaVersion.value = 2
      hydrated.value = true
      return
    }
    // schema 版本不匹配 → 按"丢数据"处理（v1 → v2 由 T-migration 接管）。
    if (state.schemaVersion !== 1 && state.schemaVersion !== 2) {
      hydrated.value = true
      return
    }
    schemaVersion.value = state.schemaVersion
    // B5：还原汇率表与默认币种；旧本地数据缺这两个字段时安全降级
    // （null / DEFAULT_CURRENCY），不升 schemaVersion（保持 2）。
    rateTable.value = restoreRateTable(state.rateTable)
    defaultCurrency.value =
      typeof state.defaultCurrency === 'string'
      && /^[A-Z]{3}$/.test(state.defaultCurrency)
        ? state.defaultCurrency
        : DEFAULT_CURRENCY
    // 还原汇率包信封版本表；仅接受 value 为正整数的键，其余丢弃。
    rateRecordVersions.value = restoreRateVersions(state.rateRecordVersions)
    // 还原 v1 三类条目。
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
    // 还原 v2 四子类型（schemaVersion=1 时缺失, 自动视作空数组）。
    for (const s of state.subscriptions ?? []) {
      subscriptions.set(s.id, {
        id: s.id,
        module: FINANCE_MODULE,
        type: 'subscription',
        version: 1,
        createdAt: s.created_at,
        updatedAt: s.updated_at,
        deleted: false,
        data: s,
      })
    }
    for (const p of state.policies ?? []) {
      policies.set(p.id, {
        id: p.id,
        module: FINANCE_MODULE,
        type: 'policy',
        version: 1,
        createdAt: p.created_at,
        updatedAt: p.updated_at,
        deleted: false,
        data: p,
      })
    }
    for (const l of state.loans ?? []) {
      loans.set(l.id, {
        id: l.id,
        module: FINANCE_MODULE,
        type: 'loan',
        version: 1,
        createdAt: l.created_at,
        updatedAt: l.updated_at,
        deleted: false,
        data: l,
      })
    }
    for (const c of state.contracts ?? []) {
      contracts.set(c.id, {
        id: c.id,
        module: FINANCE_MODULE,
        type: 'contract',
        version: 1,
        createdAt: c.created_at,
        updatedAt: c.updated_at,
        deleted: false,
        data: c,
      })
    }
    hydrated.value = true
  }

  /**
   * 把当前内存状态写入持久化通道（CRUD 后内部自动调用）。
   *
   * 失败不抛错（v1 阶段 localStorage 配额耗尽时静默降级，UI 层不感知）。
   *
   * v2 扩展：persist 同步落盘 4 子类型；schemaVersion 升 2。
   */
  function persist(): void {
    const state: PersistedFinanceState = {
      schemaVersion: 2,
      accounts: Array.from(accounts.values())
        .filter((r) => !r.deleted)
        .map((r) => r.data as FinanceAccount),
      cards: Array.from(cards.values())
        .filter((r) => !r.deleted)
        .map((r) => r.data as FinanceCard),
      txs: Array.from(txs.values())
        .filter((r) => !r.deleted)
        .map((r) => r.data as FinanceTx),
      subscriptions: Array.from(subscriptions.values())
        .filter((r) => !r.deleted)
        .map((r) => r.data as unknown as FinanceSubscription),
      policies: Array.from(policies.values())
        .filter((r) => !r.deleted)
        .map((r) => r.data as unknown as FinancePolicy),
      loans: Array.from(loans.values())
        .filter((r) => !r.deleted)
        .map((r) => r.data as unknown as FinanceLoan),
      contracts: Array.from(contracts.values())
        .filter((r) => !r.deleted)
        .map((r) => r.data as unknown as FinanceContract),
      // B5：汇率表与默认币种随同一 StorageState 明文落盘（与既有字段同级）。
      // 汇率包密文另走 records 通道 type='rate' 加密上行（见 importRateTable）；
      // rateRecordVersions 记录本地上行 / 下行信封版本，支撑同键重复导入递增。
      rateTable: rateTable.value,
      defaultCurrency: defaultCurrency.value,
      rateRecordVersions: { ...rateRecordVersions.value },
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
   * 批量推送一组明文 payload（v1+v2 共 7 子类型）。
   *
   * 流程：对每个 payload 走 wrap → pushOne → 写回缓存；skipped>0 触发
   * 全量补同步；最后落盘持久化。
   *
   * 与 4b event-rules.pushChanges 同结构：串行推送，避免版本竞争。
   *
   * @param payloads 明文 payload 数组（id 已就位；调用方负责生成）
   */
  async function pushChanges(payloads: FinancePayloadAll[]): Promise<void> {
    for (const payload of payloads) {
      const type = payloadTypeOf(payload)
      if (type == null) continue
      // v2 子类型校验（兜底）：失败抛异常, 不写本地缓存。
      if (type === 'subscription' || type === 'policy' || type === 'loan' || type === 'contract') {
        assertValidV2(type, payload)
      }
      const target = mapForType(type)
      if (target == null) continue
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
   * 由明文 payload 推导出 FinanceType 子类型（运行时类型守卫）。
   * 判别策略：按必备字段做收窄——
   *   - 含 `last4` → card；
   *   - 含 `account_id` + `amount` + `occurred_at` → tx；
   *   - 含 `balance` + `currency` + `archived` → account；
   *   - 含 `next_renewal_ts` + `billing_cycle` + `provider` → subscription；
   *   - 含 `policy_number` + `premium_minor` + `expiry_ts` → policy；
   *   - 含 `counterparty` + `principal_minor` + `due_ts` + `direction` → loan；
   *   - 含 `signed_ts` + `end_ts` + `auto_renew` → contract。
   */
  function payloadTypeOf(payload: FinancePayloadAll): FinanceType | null {
    const p = payload as unknown as Record<string, unknown>
    if ('last4' in p && typeof p.last4 === 'string') return 'card'
    if ('account_id' in p && 'amount' in p && 'occurred_at' in p) return 'tx'
    if ('balance' in p && 'currency' in p && 'archived' in p) return 'account'
    if ('next_renewal_ts' in p && 'billing_cycle' in p && 'provider' in p) return 'subscription'
    if ('policy_number' in p && 'premium_minor' in p && 'expiry_ts' in p) return 'policy'
    if ('counterparty' in p && 'principal_minor' in p && 'due_ts' in p && 'direction' in p) return 'loan'
    if ('signed_ts' in p && 'end_ts' in p && 'auto_renew' in p) return 'contract'
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

  /**
   * 新建订阅（v2 子类型）。
   * 校验通过后再写入；推送失败保留本地副本。
   */
  function addSubscription(data: FinanceSubscription): void {
    assertValidV2('subscription', data)
    const record = wrap('subscription', data)
    subscriptions.set(record.id, record)
    persist()
    void pushChanges([data])
  }

  /** 更新订阅。 */
  function updateSubscription(data: FinanceSubscription): void {
    assertValidV2('subscription', data)
    const existing = subscriptions.get(data.id)
    const record = wrap('subscription', data, existing)
    subscriptions.set(record.id, record)
    persist()
    void pushChanges([data])
  }

  /**
   * 硬删除订阅（v2 子类型无归档语义 —— 订阅为事件性, 误录后可彻底删除）。
   * 推送墓碑上行 + 增量回拉。
   */
  async function deleteSubscription(id: string): Promise<void> {
    const existing = subscriptions.get(id)
    if (!existing) return
    const version = existing.version + 1
    const now = Date.now()
    const res = await channel.push({
      id,
      module: FINANCE_MODULE,
      type: 'subscription',
      ciphertext: '',
      version,
      device_id: 'web',
      created_at: existing.createdAt,
      updated_at: now,
      deleted: true,
    })
    if (res.skipped === 0) {
      subscriptions.delete(id)
      persist()
    }
    since = Math.max(since, res.server_time)
    await pullAll(since)
  }

  // ========== CRUD —— 保单（policy, v2） ==========

  function addPolicy(data: FinancePolicy): void {
    assertValidV2('policy', data)
    const record = wrap('policy', data)
    policies.set(record.id, record)
    persist()
    void pushChanges([data])
  }

  function updatePolicy(data: FinancePolicy): void {
    assertValidV2('policy', data)
    const existing = policies.get(data.id)
    const record = wrap('policy', data, existing)
    policies.set(record.id, record)
    persist()
    void pushChanges([data])
  }

  async function deletePolicy(id: string): Promise<void> {
    const existing = policies.get(id)
    if (!existing) return
    const version = existing.version + 1
    const now = Date.now()
    const res = await channel.push({
      id,
      module: FINANCE_MODULE,
      type: 'policy',
      ciphertext: '',
      version,
      device_id: 'web',
      created_at: existing.createdAt,
      updated_at: now,
      deleted: true,
    })
    if (res.skipped === 0) {
      policies.delete(id)
      persist()
    }
    since = Math.max(since, res.server_time)
    await pullAll(since)
  }

  // ========== CRUD —— 应收借款（loan, v2） ==========

  function addLoan(data: FinanceLoan): void {
    assertValidV2('loan', data)
    const record = wrap('loan', data)
    loans.set(record.id, record)
    persist()
    void pushChanges([data])
  }

  function updateLoan(data: FinanceLoan): void {
    assertValidV2('loan', data)
    const existing = loans.get(data.id)
    const record = wrap('loan', data, existing)
    loans.set(record.id, record)
    persist()
    void pushChanges([data])
  }

  async function deleteLoan(id: string): Promise<void> {
    const existing = loans.get(id)
    if (!existing) return
    const version = existing.version + 1
    const now = Date.now()
    const res = await channel.push({
      id,
      module: FINANCE_MODULE,
      type: 'loan',
      ciphertext: '',
      version,
      device_id: 'web',
      created_at: existing.createdAt,
      updated_at: now,
      deleted: true,
    })
    if (res.skipped === 0) {
      loans.delete(id)
      persist()
    }
    since = Math.max(since, res.server_time)
    await pullAll(since)
  }

  // ========== CRUD —— 合同（contract, v2） ==========

  function addContract(data: FinanceContract): void {
    assertValidV2('contract', data)
    const record = wrap('contract', data)
    contracts.set(record.id, record)
    persist()
    void pushChanges([data])
  }

  function updateContract(data: FinanceContract): void {
    assertValidV2('contract', data)
    const existing = contracts.get(data.id)
    const record = wrap('contract', data, existing)
    contracts.set(record.id, record)
    persist()
    void pushChanges([data])
  }

  async function deleteContract(id: string): Promise<void> {
    const existing = contracts.get(id)
    if (!existing) return
    const version = existing.version + 1
    const now = Date.now()
    const res = await channel.push({
      id,
      module: FINANCE_MODULE,
      type: 'contract',
      ciphertext: '',
      version,
      device_id: 'web',
      created_at: existing.createdAt,
      updated_at: now,
      deleted: true,
    })
    if (res.skipped === 0) {
      contracts.delete(id)
      persist()
    }
    since = Math.max(since, res.server_time)
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
    subscriptions.clear()
    policies.clear()
    loans.clear()
    contracts.clear()
    // 附件子状态一并清空（与 v1/v2 子类型同节奏）。
    attachments.clear()
    attachmentsByRecordId.clear()
    attachmentCipherCache.clear()
    attachmentRemote.clear()
    since = 0
    lastSyncAt.value = 0
    hydrated.value = false
    // B5：登出 / 重新锁定时汇率状态一并清空（用户本地偏好，不跨账号残留）。
    rateTable.value = null
    defaultCurrency.value = DEFAULT_CURRENCY
    rateRecordVersions.value = {}
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
    subscriptions.clear()
    policies.clear()
    loans.clear()
    contracts.clear()
    attachments.clear()
    attachmentsByRecordId.clear()
    attachmentCipherCache.clear()
    attachmentRemote.clear()
    _attachmentChannel = null
    hydrated.value = false
    since = 0
    // B5：测试间隔离，汇率状态恢复默认。
    rateTable.value = null
    defaultCurrency.value = DEFAULT_CURRENCY
    rateRecordVersions.value = {}
  }

  // ========== 附件 CRUD（TR-3.3） ==========

  /**
   * 添加附件（编辑器上传 → 写 store）。
   *
   * 由 attachment.ts.uploadFile 调 channel.upsert 后再调本函数入 store 元数据。
   * 二级索引同步维护。
   */
  function addAttachment(recordId: string, ref: AttachmentRef): void {
    attachments.set(ref.id, ref)
    const set = attachmentsByRecordId.get(recordId)
    if (set) {
      set.add(ref.id)
    } else {
      attachmentsByRecordId.set(recordId, new Set([ref.id]))
    }
  }

  /**
   * 删除附件（store 元数据 + 二级索引；密文缓存由 attachment.ts.deleteAttachment 标记墓碑）。
   */
  function removeAttachment(recordId: string, attachmentId: string): void {
    attachments.delete(attachmentId)
    const set = attachmentsByRecordId.get(recordId)
    if (set) {
      set.delete(attachmentId)
      if (set.size === 0) attachmentsByRecordId.delete(recordId)
    }
  }

  /**
   * 高级包装：上传附件 + 入 store 元数据 + 维护二级索引。
   *
   * UI 编辑器建议走本方法，不要直接调 attachment.uploadFile——后者只负责
   * 加密上行，不写 store 元数据。
   *
   * @param recordId 关联财务记录 id
   * @param file File 鸭子对象（真实浏览器 File 或测试桩）
   * @returns AttachmentResult（成功 → AttachmentRef，失败 → 中文错误文案）
   */
  async function uploadAttachment(recordId: string, file: File) {
    const ch = getAttachmentChannel()
    return uploadFile(recordId, file, ch, (ref) => {
      addAttachment(recordId, ref)
    })
  }

  /**
   * 注入自定义附件加密通道（仅供单元测试使用）。
   */
  function _setAttachmentChannelForTest(ch: AttachmentChannel): void {
    _attachmentChannel = ch
  }

  return {
    // 状态
    schemaVersion,
    hydrated,
    syncing,
    lastSyncAt,
    // B5 多币种汇率状态（FR-V2-C.1 ~ C.3）
    rateTable,
    defaultCurrency,
    /** 汇率包信封版本表（只读暴露；测试与未来 push 对账消费，写入仅经 import/ingest）。 */
    rateRecordVersions,
    importRateTable,
    setDefaultCurrency,
    clearRateTable,
    // 计算属性
    listAccounts,
    listCards,
    listTxs,
    listSubscriptions,
    listPolicies,
    listLoans,
    listContracts,
    // v2 提醒纯计算出口（TR-4.3；Web 端无 AlarmManager，仅产出数据）
    upcomingV2Reminders,
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
    // CRUD 订阅（v2）
    addSubscription,
    updateSubscription,
    deleteSubscription,
    // CRUD 保单（v2）
    addPolicy,
    updatePolicy,
    deletePolicy,
    // CRUD 应收借款（v2）
    addLoan,
    updateLoan,
    deleteLoan,
    // CRUD 合同（v2）
    addContract,
    updateContract,
    deleteContract,
    // 附件 getters / CRUD（TR-3.3 + TR-3.4）
    attachments,
    attachmentsByRecordId,
    listAttachments,
    getAttachmentsForRecord,
    getAttachmentMeta,
    getAttachmentChannel,
    addAttachment,
    removeAttachment,
    uploadAttachment,
    // 重置
    reset,
    // 测试钩子
    _setStorageForTest,
    _setChannelForTest,
    _setAttachmentChannelForTest,
    _resetForTest,
    // 内部工具（导出便于测试 toJson 链路）
    toJson,
  }
})