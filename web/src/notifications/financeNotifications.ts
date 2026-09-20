// ============================================================================
// 财务提醒 Web 通知基础层（stage5-finance-v2 / B7 批次 / Task 7 / FR-V2-G）
// ============================================================================
//
// 路径: web/src/notifications/financeNotifications.ts
//
// 职责：
//   - 把 stores/finance.ts 的纯计算出口 upcomingV2Reminders 产出的
//     「未来提醒条目」调度为浏览器本地通知（Service Worker 展示）；
//   - 用 IndexedDB 做条目持久化（页面重开后可补发已到期条目）；
//   - 提供页面打开时的到期补发入口 fireDueOnOpen。
//
// 设计原则：
//   1. 纯 TypeScript、零 Vue / 零 pinia 依赖，核心控制器全部通过依赖注入
//      拿到「存储、时钟、通知下发」三类外部能力，因此可在 node 测试环境
//      不挂任何浏览器 API 直接跑逻辑；
//   2. 浏览器默认实现（IndexedDB / setTimeout / Notification + SW）只在
//      工厂函数被调用时才访问 navigator、Notification、indexedDB 等
//      全局对象，模块顶层不产生任何副作用；
//   3. 零知识红线：通知标题、正文与 IndexedDB 条目里只允许出现抽象类型
//      文案，严禁渲染金额、日期、卡号、对手方、保单号、具体名称；
//      IndexedDB 每条仅存 { kind, id, triggerMs }；
//   4. 通知是增强能力：任何浏览器 API 失败都静默吞掉，不得阻断页面。
//
// 与 Service Worker 的消息协议见 web/public/sw.js：
//   { channel: 'finance', type: 'show' | 'cancel', payload: {...} }
// ============================================================================

// ————————————————————————————————————————————————————————————————————————————
// 一、基础类型
// ————————————————————————————————————————————————————————————————————————————

/** 财务提醒种类（与 stores/finance.ts 的 UpcomingV2Reminder['kind'] 对齐）。 */
export type FinanceReminderKind =
  | 'subscription_renewal'
  | 'policy_expiry'
  | 'loan_due'

/**
 * 待调度的提醒条目。
 *
 * 零知识纪律：只有三个字段，id 为记录主键，不携带任何人类可读的
 * 金额、名称、日期、卡号等信息。
 */
export interface FinanceScheduleEntry {
  kind: FinanceReminderKind
  id: string
  /** 触发时刻（Unix 毫秒时间戳；允许小于等于当前时刻，表示已到期待补发）。 */
  triggerMs: number
}

/** 浏览器通知权限状态（与 Notification.permission 取值一致）。 */
export type NotificationPermission = 'granted' | 'denied' | 'default'

// ————————————————————————————————————————————————————————————————————————————
// 二、依赖注入抽象
// ————————————————————————————————————————————————————————————————————————————

/**
 * 调度条目存储抽象（浏览器默认实现为 IndexedDB，测试用内存实现）。
 *
 * 存储内容仅限 { kind, id, triggerMs }，keyPath 为 id。
 */
export interface ScheduleStore {
  /** 读取全部条目。 */
  getAll(): Promise<FinanceScheduleEntry[]>
  /** 写入或覆盖一条（以 id 为主键）。 */
  put(entry: FinanceScheduleEntry): Promise<void>
  /** 按 id 删除一条。 */
  delete(id: string): Promise<void>
  /** 清空全部条目（关闭通知时调用）。 */
  clear(): Promise<void>
}

/**
 * 定时器时钟抽象（浏览器默认实现包 setTimeout，测试用可手动 tick 的假时钟）。
 */
export interface SchedulerClock {
  /** 排定 fn 在 ms 毫秒后执行，返回句柄。 */
  setTimeout(fn: () => void, ms: number): number
  /** 按句柄取消定时器。 */
  clearTimeout(handle: number): void
  /** 当前时刻（Unix 毫秒）。 */
  now(): number
}

/** onFire 回调或通知下发时使用的文案载体（仅含抽象类型文案）。 */
export interface NotificationText {
  title: string
  body: string
}

/**
 * 通知下发抽象（浏览器默认实现走 Service Worker，不可用时降级普通通知）。
 */
export interface NotificationSink {
  /** 读取当前权限状态。 */
  permission(): NotificationPermission
  /** 向用户申请权限，返回申请后的状态。 */
  request(): Promise<NotificationPermission>
  /** 展示一条提醒；title 与 body 由调用方按抽象文案传入。 */
  show(entry: FinanceScheduleEntry, text: NotificationText): Promise<void>
  /** 可选：按条目 id 撤销已展示的通知（对应 SW cancel 消息）。 */
  cancel?(id: string): Promise<void>
}

// ————————————————————————————————————————————————————————————————————————————
// 三、零知识文案常量（与 Android strings.xml 逐字一致）
// ————————————————————————————————————————————————————————————————————————————

/** 通知标题：对应 Android finance_reminder_title。 */
export const FINANCE_NOTIFICATION_TITLE = '财务提醒'

/**
 * 三类提醒正文：逐字对齐 Android strings.xml：
 *   - finance_reminder_subscription_renewal_due_text
 *   - finance_reminder_policy_expiry_due_text
 *   - finance_reminder_loan_due_due_text
 *
 * 只有抽象类型提示，不含任何金额、日期、卡号、名称等敏感信息。
 */
export const FINANCE_NOTIFICATION_BODY: Record<FinanceReminderKind, string> = {
  subscription_renewal: '订阅续费临近，点击查看',
  policy_expiry: '保单即将到期，点击查看',
  loan_due: '借款到期临近，点击查看',
}

/**
 * 浏览器 setTimeout 延时上限（32 位有符号整数最大值）。
 * 超过该上限的延时会被 clamp；到点后由页面重新 reschedule 续排，
 * 本模块的 reschedule 本身是幂等全量替换，可安全重复调用。
 */
export const MAX_SETTIMEOUT_MS = 2147483647

// ————————————————————————————————————————————————————————————————————————————
// 四、浏览器默认实现
// ————————————————————————————————————————————————————————————————————————————

/** IndexedDB 库名。 */
const IDB_NAME = 'eve-finance-notifications'
/** IndexedDB 对象仓库名。 */
const IDB_STORE = 'schedules'
/** IndexedDB schema 版本。 */
const IDB_VERSION = 1

/**
 * 用原生 IndexedDB 实现 ScheduleStore。
 *
 * 每条记录形状固定为 { kind, id, triggerMs }，keyPath 为 id；
 * 不使用任何第三方库（无 idb、无 fake-indexeddb），手写极小 Promise 包装。
 *
 * 注意：本函数只在被显式调用时才访问全局 indexedDB，模块导入无副作用。
 *
 * @returns 调度存储实例
 * @throws 当运行环境不存在 indexedDB 时抛出中文 Error
 */
export function createIndexedDbScheduleStore(): ScheduleStore {
  if (typeof indexedDB === 'undefined' || !indexedDB) {
    throw new Error('当前环境不支持 IndexedDB，无法创建财务提醒调度存储')
  }

  /**
   * 打开（必要时升级）数据库，返回 Promise<IDBDatabase>。
   * onupgradeneeded 中创建 schedules 仓库并以 id 为主键。
   */
  function openDb(): Promise<IDBDatabase> {
    return new Promise(function (resolve, reject) {
      const request = indexedDB.open(IDB_NAME, IDB_VERSION)
      request.onupgradeneeded = function () {
        const db = request.result
        // 旧版本可能已存在同名仓库，先判断避免重复创建抛错。
        if (!db.objectStoreNames.contains(IDB_STORE)) {
          db.createObjectStore(IDB_STORE, { keyPath: 'id' })
        }
      }
      request.onsuccess = function () {
        resolve(request.result)
      }
      request.onerror = function () {
        reject(request.error || new Error('打开财务提醒 IndexedDB 失败'))
      }
    })
  }

  /**
   * 在 readwrite 或 readonly 事务里执行一次仓库操作。
   *
   * 以事务 oncomplete 作为成功信号（写入必须等事务提交，不能只看请求
   * onsuccess，否则极端情况下数据可能未落盘）；事务出错或中止则 reject
   * 中文错误。连接由浏览器在事务结束后自行管理，这里不提前 close
   * （提前关闭会中止尚未提交的事务）。
   */
  function withStore<T>(
    mode: IDBTransactionMode,
    run: (store: IDBObjectStore) => IDBRequest<T>,
  ): Promise<T> {
    return openDb().then(
      (db) =>
        new Promise<T>(function (resolve, reject) {
          const tx = db.transaction(IDB_STORE, mode)
          const request = run(tx.objectStore(IDB_STORE))
          let result: T
          request.onsuccess = function () {
            // 请求成功只拿到结果值，真正 resolve 等事务提交。
            result = request.result
          }
          request.onerror = function () {
            reject(request.error || new Error('财务提醒 IndexedDB 操作失败'))
          }
          tx.oncomplete = function () {
            resolve(result)
          }
          tx.onerror = function () {
            reject(request.error || new Error('财务提醒 IndexedDB 事务失败'))
          }
          tx.onabort = function () {
            reject(request.error || new Error('财务提醒 IndexedDB 事务中止'))
          }
        }),
    )
  }

  return {
    getAll(): Promise<FinanceScheduleEntry[]> {
      return withStore<FinanceScheduleEntry[]>('readonly', (store) =>
        // getAll 返回的记录形状受写入约束，恒为 { kind, id, triggerMs }。
        store.getAll() as IDBRequest<FinanceScheduleEntry[]>,
      )
    },
    put(entry: FinanceScheduleEntry): Promise<void> {
      return withStore<IDBValidKey>('readwrite', (store) =>
        // 仅持久化三个白名单字段，杜绝任何敏感字段混入。
        store.put({
          kind: entry.kind,
          id: entry.id,
          triggerMs: entry.triggerMs,
        }),
      ).then(() => undefined)
    },
    delete(id: string): Promise<void> {
      return withStore<undefined>('readwrite', (store) =>
        store.delete(id) as IDBRequest<undefined>,
      ).then(() => undefined)
    },
    clear(): Promise<void> {
      return withStore<undefined>('readwrite', (store) =>
        store.clear() as IDBRequest<undefined>,
      ).then(() => undefined)
    },
  }
}

/**
 * 浏览器默认时钟：包 window 的 setTimeout / clearTimeout 与 Date.now。
 *
 * 延时做浏览器上限保护：超过 MAX_SETTIMEOUT_MS 的值会被截到上限，
 * 避免超长延时被浏览器立即触发；超长等待的准确续排依赖上层周期性
 * 调用幂等的 reschedule 完成（本期不内置自唤醒循环）。
 */
export function createBrowserClock(): SchedulerClock {
  return {
    setTimeout(fn: () => void, ms: number): number {
      const safeMs = Math.min(Math.max(0, ms), MAX_SETTIMEOUT_MS)
      return setTimeout(fn, safeMs) as unknown as number
    },
    clearTimeout(handle: number): void {
      clearTimeout(handle as unknown as ReturnType<typeof setTimeout>)
    },
    now(): number {
      return Date.now()
    },
  }
}

/** 组装某条提醒的稳定 tag（与 sw.js 默认 tag 规则一致）。 */
function financeTag(entry: FinanceScheduleEntry): string {
  return 'finance-' + entry.kind + '-' + entry.id
}

/**
 * 浏览器默认通知下发：优先通过已激活的 Service Worker 展示通知，
 * SW 尚未激活时降级 new Notification(title, { body })。
 *
 * 本函数同样只在被调用时访问 navigator / Notification。
 */
export function createSwNotificationSink(): NotificationSink {
  return {
    permission(): NotificationPermission {
      return Notification.permission as NotificationPermission
    },
    request(): Promise<NotificationPermission> {
      // 现代浏览器返回 Promise；老回调式 API 在当前目标浏览器上不存在，
      // 这里直接按 Promise 形式 await。
      return Promise.resolve(Notification.requestPermission()).then(
        (state) => state as NotificationPermission,
      )
    },
    show(entry: FinanceScheduleEntry, text: NotificationText): Promise<void> {
      const tag = financeTag(entry)
      // navigator.serviceWorker 不存在（非安全上下文等）时降级普通通知。
      if (
        typeof navigator === 'undefined' ||
        !navigator ||
        !navigator.serviceWorker
      ) {
        return fallbackNotification(text)
      }
      return navigator.serviceWorker.ready.then(
        (registration) => {
          if (registration && registration.active) {
            // 文案由页面端提供，SW 只透传；data 由 SW 侧补通道与定位字段。
            registration.active.postMessage({
              channel: 'finance',
              type: 'show',
              payload: {
                kind: entry.kind,
                id: entry.id,
                title: text.title,
                body: text.body,
                tag,
              },
            })
            return
          }
          return fallbackNotification(text)
        },
        // serviceWorker.ready 被拒绝时降级普通通知；再失败则静默。
        () => fallbackNotification(text),
      )
    },
    cancel(id: string): Promise<void> {
      if (
        typeof navigator === 'undefined' ||
        !navigator ||
        !navigator.serviceWorker
      ) {
        return Promise.resolve()
      }
      return navigator.serviceWorker.ready.then(
        (registration) => {
          if (registration && registration.active) {
            // SW cancel 协议按 tag 精确撤销，此处 tag 与展示时一致。
            registration.active.postMessage({
              channel: 'finance',
              type: 'cancel',
              payload: { tag: id },
            })
          }
        },
        // 撤销失败静默，不影响主流程。
        () => undefined,
      )
    },
  }
}

/**
 * 降级路径：SW 不可用时直接构造页面内 Notification。
 * 构造本身可能抛错（权限不足等），统一包成 reject 交由上层吞掉。
 */
function fallbackNotification(text: NotificationText): Promise<void> {
  try {
    // eslint 不参与本仓库校验；new Notification 是标准浏览器 API。
    new Notification(text.title, { body: text.body })
    return Promise.resolve()
  } catch (err) {
    return Promise.reject(err)
  }
}

// ————————————————————————————————————————————————————————————————————————————
// 五、核心可测控制器
// ————————————————————————————————————————————————————————————————————————————

/** reschedule 的统计结果。 */
export interface ScheduledSummary {
  /** 已排定未来定时器的条目数。 */
  scheduled: number
  /** 已到期（triggerMs 小于等于当前时刻）、保留在 store 待补发的条目数。 */
  expired: number
}

/** 财务提醒通知器：页面侧唯一需要持有的控制器。 */
export interface FinanceNotifier {
  /** 是否处于启用状态（内存布尔，初始 false）。 */
  readonly enabled: boolean
  /** 申请权限并启用；被拒绝时返回 false 且保持禁用，不做任何 toast 降级。 */
  enable(): Promise<boolean>
  /** 禁用：清定时器、清空持久化条目。 */
  disable(): Promise<void>
  /**
   * 幂等全量替换调度：清空旧定时器，与 store 全量对账（增删），
   * 未来条目排定时器，已到期条目保留待 fireDueOnOpen 补发。
   */
  reschedule(
    entries: FinanceScheduleEntry[],
    nowMs?: number,
  ): Promise<ScheduledSummary>
  /**
   * 页面打开时补发已到期条目：从 store 找出 triggerMs 小于等于当前时刻
   * 的条目，移除存储与对应定时器，逐条走「展示决策」回调，最后返回这些
   * 条目（供 UI 在权限被拒时改走页内 toast 降级）。
   */
  fireDueOnOpen(nowMs?: number): Promise<FinanceScheduleEntry[]>
  /** 释放：等同 disable，并移除全部内存定时器。 */
  dispose(): void
}

/** createFinanceNotifier 的依赖包。 */
export interface FinanceNotifierDeps {
  store: ScheduleStore
  clock: SchedulerClock
  sink: NotificationSink
  /**
   * 可选展示决策回调：
   *   - 定时器到期、页面打开补发时都会调用；
   *   - 提供时由上层决定走 sink.show（系统通知）还是 toast（页内降级）；
   *   - 不提供时默认直接调用 sink.show。
   * 回调无论是同步抛错还是返回 reject 的 Promise 都会被吞掉。
   */
  onFire?: (entry: FinanceScheduleEntry, text: NotificationText) => void
}

/**
 * 创建一个财务提醒通知器。全部外部能力均由 deps 注入，纯逻辑可在 node 测试。
 */
export function createFinanceNotifier(deps: FinanceNotifierDeps): FinanceNotifier {
  const { store, clock, sink, onFire } = deps

  /** 内存启用位：初始禁用。 */
  let active = false
  /** 已排定定时器：id 到句柄，保证同 id reschedule 不泄漏、不重复。 */
  const timers = new Map<string, number>()

  /** 取某类提醒的抽象文案（仅标题与固定正文，零敏感信息）。 */
  function textOf(entry: FinanceScheduleEntry): NotificationText {
    return {
      title: FINANCE_NOTIFICATION_TITLE,
      body: FINANCE_NOTIFICATION_BODY[entry.kind],
    }
  }

  /** 安全调用 onFire：同步抛错与异步 reject 都吞掉，不影响主流程。 */
  function notifyCallback(
    entry: FinanceScheduleEntry,
    text: NotificationText,
  ): void {
    if (!onFire) {
      return
    }
    try {
      // 规格约定 onFire 返回 void，但容错允许其返回 Promise（异步展示），
      // 因此先收窄到 unknown 再判断是否为 thenable，统一吞掉拒绝。
      const result: unknown = onFire(entry, text)
      if (
        result &&
        typeof (result as { then?: unknown }).then === 'function'
      ) {
        ;(result as Promise<void>).then(undefined, () => undefined)
      }
    } catch (err) {
      // onFire 同步抛错被忽略。
    }
  }

  /**
   * 定时器到期触发（仅未来条目使用此路径）。
   *
   * 语义：
   *   - 已禁用则直接丢弃（不展示、不清理 store，禁用时 store 已整体清空）；
   *   - 从内存定时器表移除自身；
   *   - 尝试 sink.show 系统通知，成功或失败都把条目从 store 删除；
   *   - sink.show 与 store.delete 的 reject 都被吞掉；
   *   - 最后调用 onFire 回调（若提供），回调抛错同样吞掉。
   */
  function fireByTimer(entry: FinanceScheduleEntry): void {
    if (!active) {
      return
    }
    timers.delete(entry.id)
    const text = textOf(entry)
    Promise.resolve()
      .then(() => sink.show(entry, text))
      // 通知下发失败不冒泡：通知是增强能力，不影响页面。
      .catch(() => undefined)
      .then(() => store.delete(entry.id))
      // 存储清理失败同样忽略，下次 reschedule 会再次对账。
      .catch(() => undefined)
      .then(() => notifyCallback(entry, text))
  }

  /** 取消并遗忘某 id 的定时器（若存在）。 */
  function clearTimer(id: string): void {
    const handle = timers.get(id)
    if (handle !== undefined) {
      clock.clearTimeout(handle)
      timers.delete(id)
    }
  }

  /** 取消并遗忘全部定时器。 */
  function clearAllTimers(): void {
    timers.forEach((handle) => clock.clearTimeout(handle))
    timers.clear()
  }

  return {
    get enabled(): boolean {
      return active
    },

    enable(): Promise<boolean> {
      // 已授权：直接启用，不重复弹窗。
      if (sink.permission() === 'granted') {
        active = true
        return Promise.resolve(true)
      }
      // 未授权：发起申请；仅 granted 才启用，denied/default 都保持禁用。
      // 是否 toast 降级交由 UI 决定，本层不做任何界面反馈。
      return Promise.resolve()
        .then(() => sink.request())
        .then((state) => {
          if (state === 'granted') {
            active = true
            return true
          }
          active = false
          return false
        })
    },

    disable(): Promise<void> {
      active = false
      clearAllTimers()
      // 清空持久化条目；失败静默，禁用动作本身不因存储问题抛错。
      return Promise.resolve()
        .then(() => store.clear())
        .catch(() => undefined)
    },

    reschedule(
      entries: FinanceScheduleEntry[],
      nowMs: number = clock.now(),
    ): Promise<ScheduledSummary> {
      // 第一步：无条件清空旧定时器，实现「全量替换」，保证同 id 不重复、
      // 已消失的 id 不再到点触发。
      clearAllTimers()

      // 第二步：与 store 全量对账。传入条目逐条 put，store 中多余的旧
      // 条目（id 不在本次集合内）逐条删除。
      const incomingIds = new Set<string>()
      entries.forEach((entry) => incomingIds.add(entry.id))

      return Promise.resolve()
        .then(() => store.getAll())
        .then((existing) => {
          const removable = existing.filter(
            (entry) => !incomingIds.has(entry.id),
          )
          return removable.reduce(
            (chain, entry) =>
              chain.then(() => store.delete(entry.id)).catch(() => undefined),
            Promise.resolve(),
          )
        })
        // 对账删除完成后再写入本次全量条目（put 以 id 覆盖）。
        .then(() =>
          entries.reduce(
            (chain, entry) =>
              chain.then(() => store.put(entry)).catch(() => undefined),
            Promise.resolve(),
          ),
        )
        .then(() => {
          // 第三步：对每条目决定排定时器还是留待补发。
          let scheduled = 0
          let expired = 0
          entries.forEach((entry) => {
            if (entry.triggerMs > nowMs) {
              // 未来条目：延时 clamp 到浏览器上限；超长延时由后续
              // reschedule 调用续排（reschedule 幂等可重复调用）。
              const delay = Math.min(entry.triggerMs - nowMs, MAX_SETTIMEOUT_MS)
              const handle = clock.setTimeout(() => fireByTimer(entry), delay)
              timers.set(entry.id, handle)
              scheduled += 1
            } else {
              // 已到期：不排未来定时器，条目保留在 store，
              // 等 fireDueOnOpen 在页面打开时补发。
              expired += 1
            }
          })
          return { scheduled, expired }
        })
    },

    fireDueOnOpen(
      nowMs: number = clock.now(),
    ): Promise<FinanceScheduleEntry[]> {
      return Promise.resolve()
        .then(() => store.getAll())
        .then((all) => {
          const due = all.filter((entry) => entry.triggerMs <= nowMs)
          // 先把到期条目从持久层移除、清掉可能存在的定时器，
          // 使「发现到期条目」与「如何展示」解耦。
          return due
            .reduce(
              (chain, entry) =>
                chain
                  .then(() => {
                    clearTimer(entry.id)
                    return store.delete(entry.id)
                  })
                  .catch(() => undefined),
              Promise.resolve(),
            )
            .then(() => due)
        })
        .then((due) => {
          // 展示决策（降级路径）：
          //   - 传了 onFire：由上层选择系统通知 sink.show 或页内 toast，
          //     权限被拒时 UI 可在此改走 toast；
          //   - 没传 onFire：默认直接尝试 sink.show，失败静默。
          due.forEach((entry) => {
            const text = textOf(entry)
            if (onFire) {
              notifyCallback(entry, text)
            } else {
              Promise.resolve()
                .then(() => sink.show(entry, text))
                .catch(() => undefined)
            }
          })
          // 无论是否成功展示，都把到期条目返回给上层，供 UI 自行补偿。
          return due
        })
    },

    dispose(): void {
      active = false
      clearAllTimers()
      // store.clear 是异步的，dispose 签名为同步；触发清理并吞掉结果。
      Promise.resolve()
        .then(() => store.clear())
        .catch(() => undefined)
    },
  }
}

// ————————————————————————————————————————————————————————————————————————————
// 六、便捷单例与 Service Worker 注册（供上层 SA-2/store 接线，本任务不接线）
// ————————————————————————————————————————————————————————————————————————————

/** 进程内单例；浏览器环境不可用时保持 null。 */
let _singleton: FinanceNotifier | null = null

/**
 * 获取财务提醒通知器单例。
 *
 * 仅当 navigator 与 Notification 都存在时才尝试懒构造（IndexedDB 工厂
 * 内部还会再检查一次 indexedDB）；任一依赖缺失或构造抛错，均返回 null，
 * 绝不向调用方抛异常。
 */
export function getFinanceNotifier(): FinanceNotifier | null {
  if (_singleton) {
    return _singleton
  }
  // 模块顶层不访问这些全局对象，仅在函数被调用时检查。
  if (typeof navigator === 'undefined' || !navigator) {
    return null
  }
  if (typeof Notification === 'undefined' || !Notification) {
    return null
  }
  try {
    _singleton = createFinanceNotifier({
      store: createIndexedDbScheduleStore(),
      clock: createBrowserClock(),
      sink: createSwNotificationSink(),
    })
    return _singleton
  } catch (err) {
    // IndexedDB 缺失等环境问题：降级为无通知能力，返回 null。
    _singleton = null
    return null
  }
}

/** 仅供单元测试重置单例用（生产代码不要调用）。 */
export function resetFinanceNotifierForTest(): void {
  _singleton = null
}

/**
 * 注册全站唯一 Service Worker（web/public/sw.js，根作用域）。
 *
 * - 环境不支持 Service Worker 时静默返回；
 * - 注册被拒绝也静默：通知是增强能力，绝不能因 SW 注册失败阻断应用。
 *
 * @param navOverride 可选注入的 navigator（测试用）；默认取全局 navigator。
 */
export function registerFinanceServiceWorker(
  navOverride?: Navigator,
): Promise<void> {
  const nav: Navigator | undefined =
    navOverride ??
    (typeof navigator !== 'undefined' ? navigator : undefined)
  if (!nav || !nav.serviceWorker || typeof nav.serviceWorker.register !== 'function') {
    return Promise.resolve()
  }
  return Promise.resolve()
    .then(() => nav.serviceWorker.register('sw.js'))
    // 作用域取默认根作用域（脚本位于站点根），失败静默不影响应用启动。
    .then(() => undefined)
    .catch(() => undefined)
}
