// ============================================================================
// 财务通知基础层单测（stage5-finance-v2 / B7 / Task 7 / FR-V2-G）
// ============================================================================
//
// 路径: web/src/notifications/__tests__/financeNotifications.spec.ts
//
// 测试环境：vitest 默认 node 环境（无 jsdom、无 happy-dom、无
// @vue/test-utils、无 @types/node）。浏览器 API 一律通过手工假实现
// 注入或挂到 globalThis：
//   - ScheduleStore：内存 Map 实现；
//   - SchedulerClock：ManualClock，可用 tick(ms) 手动推进虚拟时间；
//   - NotificationSink：FakeSink 记录所有下发调用；
//   - IndexedDB：极小内存假实现（open/onsuccess/onupgradeneeded/
//     transaction/objectStore/put/getAll/delete/clear）。
//
// 零知识纪律：断言通知文案只有抽象类型提示，不含日期形态与金额符号。
// ============================================================================

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import {
  FINANCE_NOTIFICATION_BODY,
  FINANCE_NOTIFICATION_TITLE,
  createFinanceNotifier,
  createIndexedDbScheduleStore,
  getFinanceNotifier,
  registerFinanceServiceWorker,
  resetFinanceNotifierForTest,
  type FinanceNotifier,
  type FinanceReminderKind,
  type FinanceScheduleEntry,
  type NotificationPermission,
  type NotificationSink,
  type ScheduleStore,
  type SchedulerClock,
} from '../financeNotifications'

// ============================================================================
// 通用小工具
// ============================================================================

/** 推进一个宏任务，排空其中全部微任务（Promise 链）。 */
function flush(): Promise<void> {
  return new Promise(function (resolve) {
    setTimeout(resolve, 0)
  })
}

/** 以偏移毫秒构造条目（kind 缺省为订阅续费）。 */
function makeEntry(
  id: string,
  offsetMs: number,
  kind: FinanceReminderKind = 'subscription_renewal',
  baseMs = CLOCK_START,
): FinanceScheduleEntry {
  return { kind, id, triggerMs: baseMs + offsetMs }
}

/** 时钟与测试的统一虚拟起点。 */
const CLOCK_START = 1_000_000

// ============================================================================
// 手工假依赖：内存存储
// ============================================================================

class MemoryScheduleStore implements ScheduleStore {
  readonly rows = new Map<string, FinanceScheduleEntry>()

  getAll(): Promise<FinanceScheduleEntry[]> {
    return Promise.resolve(Array.from(this.rows.values()))
  }

  put(entry: FinanceScheduleEntry): Promise<void> {
    // 复制一份，模拟 IndexedDB 结构化克隆，避免别名干扰断言。
    this.rows.set(entry.id, { ...entry })
    return Promise.resolve()
  }

  delete(id: string): Promise<void> {
    this.rows.delete(id)
    return Promise.resolve()
  }

  clear(): Promise<void> {
    this.rows.clear()
    return Promise.resolve()
  }
}

// ============================================================================
// 手工假依赖：可手动 tick 的时钟
// ============================================================================

interface PendingTimer {
  fn: () => void
  fireAt: number
}

class ManualClock implements SchedulerClock {
  private current = CLOCK_START
  private seq = 1
  readonly timers = new Map<number, PendingTimer>()

  setTimeout(fn: () => void, ms: number): number {
    // 与浏览器默认实现一致做上限 clamp。
    const safeMs = Math.min(Math.max(0, ms), 2147483647)
    const handle = this.seq
    this.seq += 1
    this.timers.set(handle, { fn, fireAt: this.current + safeMs })
    return handle
  }

  clearTimeout(handle: number): void {
    this.timers.delete(handle)
  }

  now(): number {
    return this.current
  }

  /** 推进虚拟时间并同步触发所有到期回调（按到期时刻升序）。 */
  tick(ms: number): void {
    this.current += ms
    while (true) {
      let dueHandle: number | null = null
      let dueAt = Number.POSITIVE_INFINITY
      this.timers.forEach((timer, handle) => {
        if (timer.fireAt <= this.current && timer.fireAt < dueAt) {
          dueAt = timer.fireAt
          dueHandle = handle
        }
      })
      if (dueHandle === null) {
        return
      }
      const handle = dueHandle
      const timer = this.timers.get(handle)
      this.timers.delete(handle)
      if (timer) {
        timer.fn()
      }
    }
  }
}

// ============================================================================
// 手工假依赖：通知下发记录器
// ============================================================================

interface ShownRecord {
  entry: FinanceScheduleEntry
  text: { title: string; body: string }
}

class FakeSink implements NotificationSink {
  permissionState: NotificationPermission = 'granted'
  requestResult: NotificationPermission = 'granted'
  requestCalls = 0
  readonly shown: ShownRecord[] = []
  readonly cancelled: string[] = []
  /** 置 true 时 show 以 reject 失败，用于验证失败静默。 */
  rejectShow = false

  permission(): NotificationPermission {
    return this.permissionState
  }

  request(): Promise<NotificationPermission> {
    this.requestCalls += 1
    return Promise.resolve(this.requestResult)
  }

  show(
    entry: FinanceScheduleEntry,
    text: { title: string; body: string },
  ): Promise<void> {
    if (this.rejectShow) {
      return Promise.reject(new Error('模拟通知下发失败'))
    }
    this.shown.push({ entry, text: { ...text } })
    return Promise.resolve()
  }

  cancel(id: string): Promise<void> {
    this.cancelled.push(id)
    return Promise.resolve()
  }
}

/** 一组装配好的假依赖。 */
interface Harness {
  store: MemoryScheduleStore
  clock: ManualClock
  sink: FakeSink
  notifier: FinanceNotifier
  onFire: ReturnType<typeof vi.fn>
}

/** 装配一个可测通知器（onFire 为可选注入的展示决策回调）。 */
function createHarness(useOnFire: boolean): Harness {
  const store = new MemoryScheduleStore()
  const clock = new ManualClock()
  const sink = new FakeSink()
  const onFire = vi.fn()
  const notifier = createFinanceNotifier({
    store,
    clock,
    sink,
    onFire: useOnFire ? onFire : undefined,
  })
  return { store, clock, sink, notifier, onFire }
}

// ============================================================================
// 极小内存假 IndexedDB（只实现本模块用到的 API 形状）
// ============================================================================

type FakeReqHandler = ((event: unknown) => void) | null

/**
 * 最小 IDBRequest 形状：success/error 事件 + result/error 数据。
 *
 * 说明：triggerSuccess 通过微任务异步派发 success，与真实 IndexedDB 的
 * 事件节奏一致，保证生产代码先绑定 onsuccess/oncomplete 处理器、后收到事件。
 * success 与事务 complete 在同一个微任务内顺序执行，因此顺序仍为
 * request success 在前、transaction complete 在后。
 */
class FakeRequest {
  onsuccess: FakeReqHandler = null
  onerror: FakeReqHandler = null
  onupgradeneeded: FakeReqHandler = null
  result: unknown = undefined
  error: Error | null = null
  /** 请求成功后再触发的钩子（用于驱动事务 complete）。 */
  afterSuccess: (() => void) | null = null

  /** 微任务异步派发 success，随后驱动事务 complete。 */
  triggerSuccess(result: unknown): void {
    this.result = result
    Promise.resolve().then(() => {
      if (this.onsuccess) {
        this.onsuccess({})
      }
      if (this.afterSuccess) {
        this.afterSuccess()
      }
    })
  }
}

/** 假对象仓库：内存 Map 存储，keyPath 固定为 id。 */
class FakeObjectStore {
  /** 由所属事务在 objectStore() 时注入：success 后驱动事务 complete。 */
  complete: (() => void) | null = null

  constructor(private readonly map: Map<string, unknown>) {}

  /** 构造一个会在 success 后驱动事务 complete 的请求。 */
  private requestWith(result: unknown): FakeRequest {
    const req = new FakeRequest()
    req.afterSuccess = () => {
      if (this.complete) {
        this.complete()
      }
    }
    req.triggerSuccess(result)
    return req
  }

  put(value: { id: string }): FakeRequest {
    // 结构化克隆语义：存入副本，只保留写入字段（零知识白名单由生产代码保证）。
    this.map.set(value.id, { ...value })
    return this.requestWith(value.id)
  }

  getAll(): FakeRequest {
    return this.requestWith(Array.from(this.map.values()))
  }

  delete(key: string): FakeRequest {
    this.map.delete(key)
    return this.requestWith(undefined)
  }

  clear(): FakeRequest {
    this.map.clear()
    return this.requestWith(undefined)
  }
}

/** 假 IDBDatabase：固定持有 schedules 仓库（map 由工厂共享）。 */
class FakeDb {
  readonly objectStoreNames = {
    contains: (name: string): boolean => name === 'schedules',
  }
  private readonly store: FakeObjectStore

  constructor(map: Map<string, unknown>) {
    // 多个连接共享同一份工厂级 Map，模拟同源同库数据持久。
    this.store = new FakeObjectStore(map)
  }

  createObjectStore(): FakeObjectStore {
    // 测试中 upgrade 恒发生，直接返回同一内存仓库。
    return this.store
  }

  transaction(): { objectStore: () => FakeObjectStore } & {
    oncomplete: FakeReqHandler
    onerror: FakeReqHandler
    onabort: FakeReqHandler
  } {
    const tx = {
      objectStore: (): FakeObjectStore => {
        // 仓库内每个操作请求在 success 之后驱动本事务 complete，
        // 保证 request onsuccess 先于 tx oncomplete（与真实顺序一致）。
        this.store.complete = () => {
          if (tx.oncomplete) {
            tx.oncomplete({})
          }
        }
        return this.store
      },
      oncomplete: null as FakeReqHandler,
      onerror: null as FakeReqHandler,
      onabort: null as FakeReqHandler,
    }
    return tx
  }

  close(): void {
    // 内存实现无需关闭。
  }
}

/** 假 IDBFactory：仅实现 open。 */
function createFakeIndexedDbFactory(): IDBFactory {
  const map = new Map<string, unknown>()
  const factory = {
    open(): FakeRequest {
      const req = new FakeRequest()
      const db = new FakeDb(map)
      // open 本身延迟一微任务派发，等生产代码绑好 onupgradeneeded 与
      // onsuccess；upgrade 与 success 顺序仍与真实 IndexedDB 一致。
      Promise.resolve().then(() => {
        req.result = db
        if (req.onupgradeneeded) {
          req.onupgradeneeded({})
        }
        // open 请求没有事务，不挂 afterSuccess，直接调 success 部分。
        if (req.onsuccess) {
          req.onsuccess({})
        }
      })
      return req
    },
  }
  return factory as unknown as IDBFactory
}

// ============================================================================
// 全局浏览器对象隔离：测试前后清理 / 还原 navigator、Notification、indexedDB
// ============================================================================

interface GlobalShape {
  navigator?: unknown
  Notification?: unknown
  indexedDB?: unknown
}

let savedGlobals: GlobalShape = {}

beforeEach(() => {
  resetFinanceNotifierForTest()
  const g = globalThis as unknown as GlobalShape
  savedGlobals = {
    navigator: g.navigator,
    Notification: g.Notification,
    indexedDB: g.indexedDB,
  }
  delete g.navigator
  delete g.Notification
  delete g.indexedDB
})

afterEach(() => {
  const g = globalThis as unknown as GlobalShape
  if (savedGlobals.navigator === undefined) {
    delete g.navigator
  } else {
    g.navigator = savedGlobals.navigator
  }
  if (savedGlobals.Notification === undefined) {
    delete g.Notification
  } else {
    g.Notification = savedGlobals.Notification
  }
  if (savedGlobals.indexedDB === undefined) {
    delete g.indexedDB
  } else {
    g.indexedDB = savedGlobals.indexedDB
  }
})

// ============================================================================
// 用例
// ============================================================================

describe('createFinanceNotifier：enable 权限语义', () => {
  it('用例1：已 granted 直接启用且不再申请；denied 与 default 均保持禁用', async () => {
    // 场景一：已经授权。
    const granted = createHarness(false)
    granted.sink.permissionState = 'granted'
    await expect(granted.notifier.enable()).resolves.toBe(true)
    expect(granted.notifier.enabled).toBe(true)
    expect(granted.sink.requestCalls).toBe(0)

    // 场景二：申请被拒。
    const denied = createHarness(false)
    denied.sink.permissionState = 'default'
    denied.sink.requestResult = 'denied'
    await expect(denied.notifier.enable()).resolves.toBe(false)
    expect(denied.notifier.enabled).toBe(false)
    expect(denied.sink.requestCalls).toBe(1)

    // 场景三：申请结果仍是 default（未决定）。
    const pending = createHarness(false)
    pending.sink.permissionState = 'default'
    pending.sink.requestResult = 'default'
    await expect(pending.notifier.enable()).resolves.toBe(false)
    expect(pending.notifier.enabled).toBe(false)
  })
})

describe('createFinanceNotifier：reschedule 全量替换', () => {
  it('用例2：第二次调度移除消失条目，旧定时器被清空，到点不再 fire', async () => {
    const h = createHarness(false)
    await h.notifier.enable()

    // 第一次：A 未来 1000ms，B 未来 2000ms。
    await h.notifier.reschedule([
      makeEntry('a', 1000),
      makeEntry('b', 2000, 'policy_expiry'),
    ])

    // 第二次：只保留 B，新增 C；A 应从存储与定时器中同时消失。
    await h.notifier.reschedule([
      makeEntry('b', 2000, 'policy_expiry'),
      makeEntry('c', 3000, 'loan_due'),
    ])

    const rows = await h.store.getAll()
    expect(rows.map((r) => r.id).sort()).toEqual(['b', 'c'])

    // 越过 A 原本的触发时刻 1000ms：A 不应再触发。
    h.clock.tick(1500)
    await flush()
    expect(h.sink.shown.map((s) => s.entry.id)).toEqual([])
  })

  it('用例2补充：reschedule 返回 scheduled/expired 计数', async () => {
    const h = createHarness(false)
    const summary = await h.notifier.reschedule([
      makeEntry('future', 1000),
      makeEntry('past', 0),
      makeEntry('past2', -50, 'loan_due'),
    ])
    expect(summary).toEqual({ scheduled: 1, expired: 2 })
  })
})

describe('createFinanceNotifier：未来条目到点 fire', () => {
  it('用例3：三种 kind 到点各下发一次，标题/正文逐字正确，条目随后从 store 删除', async () => {
    const kinds: FinanceReminderKind[] = [
      'subscription_renewal',
      'policy_expiry',
      'loan_due',
    ]
    for (const kind of kinds) {
      const h = createHarness(false)
      await h.notifier.enable()
      const entry = makeEntry('id-' + kind, 1000, kind)
      await h.notifier.reschedule([entry])

      h.clock.tick(999)
      await flush()
      expect(h.sink.shown).toHaveLength(0)

      h.clock.tick(1)
      await flush()
      expect(h.sink.shown).toHaveLength(1)
      expect(h.sink.shown[0].text.title).toBe(FINANCE_NOTIFICATION_TITLE)
      expect(h.sink.shown[0].text.title).toBe('财务提醒')
      expect(h.sink.shown[0].text.body).toBe(FINANCE_NOTIFICATION_BODY[kind])
      // 条目已从持久层移除。
      const rows = await h.store.getAll()
      expect(rows.map((r) => r.id)).not.toContain(entry.id)
    }

    // 三种正文逐字断言（与 Android strings.xml 对齐）。
    expect(FINANCE_NOTIFICATION_BODY.subscription_renewal).toBe(
      '订阅续费临近，点击查看',
    )
    expect(FINANCE_NOTIFICATION_BODY.policy_expiry).toBe(
      '保单即将到期，点击查看',
    )
    expect(FINANCE_NOTIFICATION_BODY.loan_due).toBe('借款到期临近，点击查看')
  })
})

describe('createFinanceNotifier：过期条目补发', () => {
  it('用例4：过期条目标记 expired，fireDueOnOpen 经 onFire 回调返回，默认不下发 sink', async () => {
    const h = createHarness(true)
    await h.notifier.enable()
    const expired = makeEntry('past', -10, 'policy_expiry')
    const summary = await h.notifier.reschedule([expired])
    expect(summary).toEqual({ scheduled: 0, expired: 1 })

    // 未打开发放前不应有通知。
    h.clock.tick(5000)
    await flush()
    expect(h.sink.shown).toHaveLength(0)

    const due = await h.notifier.fireDueOnOpen()
    expect(due.map((e) => e.id)).toEqual(['past'])
    expect(h.onFire).toHaveBeenCalledTimes(1)
    expect(h.onFire).toHaveBeenCalledWith(expired, {
      title: '财务提醒',
      body: '保单即将到期，点击查看',
    })
    // 提供 onFire 时默认展示路径被接管，sink 不直接弹通知。
    expect(h.sink.shown).toHaveLength(0)
    // 补发后条目从 store 移除。
    expect((await h.store.getAll()).map((r) => r.id)).toEqual([])
  })

  it('用例4补充：不传 onFire 时 fireDueOnOpen 默认走 sink.show', async () => {
    const h = createHarness(false)
    await h.notifier.enable()
    await h.notifier.reschedule([makeEntry('past2', -1, 'loan_due')])

    const due = await h.notifier.fireDueOnOpen()
    expect(due.map((e) => e.id)).toEqual(['past2'])
    expect(h.sink.shown).toHaveLength(1)
    expect(h.sink.shown[0].text.body).toBe('借款到期临近，点击查看')
    expect((await h.store.getAll()).map((r) => r.id)).toEqual([])
  })
})

describe('createFinanceNotifier：禁用与失败静默', () => {
  it('用例5：未启用（权限被拒）时即使定时器到点也不弹通知，条目仍保留', async () => {
    const h = createHarness(false)
    h.sink.permissionState = 'default'
    h.sink.requestResult = 'denied'
    const enabled = await h.notifier.enable()
    expect(enabled).toBe(false)

    // reschedule 不要求启用态（数据层照常对账），但到点 fire 必须丢弃。
    await h.notifier.reschedule([makeEntry('a', 1000)])
    h.clock.tick(5000)
    await flush()
    expect(h.sink.shown).toHaveLength(0)
    // 未调用 disable，存储不受影响。
    expect((await h.store.getAll()).map((r) => r.id)).toEqual(['a'])
  })

  it('用例6：disable 清空 store 与全部定时器，再 tick 无 fire', async () => {
    const h = createHarness(false)
    await h.notifier.enable()
    await h.notifier.reschedule([
      makeEntry('a', 1000),
      makeEntry('b', 2000, 'loan_due'),
    ])
    await h.notifier.disable()
    expect(h.notifier.enabled).toBe(false)
    expect((await h.store.getAll())).toEqual([])
    expect(h.clock.timers.size).toBe(0)

    h.clock.tick(10_000)
    await flush()
    expect(h.sink.shown).toHaveLength(0)
  })

  it('用例7：sink.show reject 不冒泡，条目仍被删除，reschedule/fire 链不抛错', async () => {
    const h = createHarness(false)
    await h.notifier.enable()
    h.sink.rejectShow = true
    await h.notifier.reschedule([makeEntry('a', 1000)])

    h.clock.tick(1000)
    await expect(flush()).resolves.toBeUndefined()
    expect((await h.store.getAll()).map((r) => r.id)).toEqual([])
  })

  it('用例8：onFire 回调同步抛错或异步 reject 都被吞掉', async () => {
    const store = new MemoryScheduleStore()
    const clock = new ManualClock()
    const sink = new FakeSink()
    const syncThrow = vi.fn(() => {
      throw new Error('回调同步爆炸')
    })
    const notifier = createFinanceNotifier({
      store,
      clock,
      sink,
      onFire: syncThrow,
    })
    await notifier.enable()
    await notifier.reschedule([makeEntry('a', 1000)])
    clock.tick(1000)
    await expect(flush()).resolves.toBeUndefined()
    expect(syncThrow).toHaveBeenCalledTimes(1)
    expect((await store.getAll()).map((r) => r.id)).toEqual([])

    // 异步 reject 场景。
    const asyncThrow = vi.fn(() => Promise.reject(new Error('回调异步爆炸')))
    const h2 = createHarness(false)
    const notifier2 = createFinanceNotifier({
      store: h2.store,
      clock: h2.clock,
      sink: h2.sink,
      onFire: asyncThrow,
    })
    await notifier2.enable()
    await notifier2.reschedule([makeEntry('b', 1000)])
    h2.clock.tick(1000)
    await expect(flush()).resolves.toBeUndefined()
    expect(asyncThrow).toHaveBeenCalledTimes(1)
  })
})

describe('createFinanceNotifier：定时器幂等', () => {
  it('用例9：同 id 重复 reschedule 不产生重复定时器，tick 后只 fire 一次', async () => {
    const h = createHarness(false)
    await h.notifier.enable()
    // 第一次排在 1000ms 后。
    await h.notifier.reschedule([makeEntry('a', 1000)])
    // 第二次把同 id 改排到 2000ms 后。
    await h.notifier.reschedule([makeEntry('a', 2000)])
    expect(h.clock.timers.size).toBe(1)

    h.clock.tick(1000)
    await flush()
    expect(h.sink.shown).toHaveLength(0)

    h.clock.tick(1001)
    await flush()
    expect(h.sink.shown).toHaveLength(1)
    expect(h.sink.shown[0].entry.id).toBe('a')
  })
})

describe('createIndexedDbScheduleStore：假 IndexedDB 往返', () => {
  it('用例10：put/getAll 覆盖往返、delete/clear、keyPath 为 id，且仅含白名单字段', async () => {
    const g = globalThis as unknown as { indexedDB?: unknown }
    g.indexedDB = createFakeIndexedDbFactory()

    const store = createIndexedDbScheduleStore()
    await store.put({ kind: 'subscription_renewal', id: 's1', triggerMs: 123 })
    await store.put({ kind: 'loan_due', id: 'l1', triggerMs: 456 })
    // 同 id 再写：覆盖而非新增（验证 keyPath id）。
    await store.put({ kind: 'policy_expiry', id: 's1', triggerMs: 789 })

    const all = await store.getAll()
    expect(all).toHaveLength(2)
    const s1 = all.find((r) => r.id === 's1')
    expect(s1).toEqual({ kind: 'policy_expiry', id: 's1', triggerMs: 789 })
    // 零知识：持久化对象只允许三个白名单键。
    expect(Object.keys(s1 as FinanceScheduleEntry).sort()).toEqual([
      'id',
      'kind',
      'triggerMs',
    ])

    await store.delete('l1')
    const afterDelete = await store.getAll()
    expect(afterDelete.map((r) => r.id)).toEqual(['s1'])

    await store.clear()
    expect(await store.getAll()).toEqual([])
  })

  it('用例10补充：indexedDB 缺失时工厂抛中文错误', () => {
    const g = globalThis as unknown as { indexedDB?: unknown }
    delete g.indexedDB
    expect(() => createIndexedDbScheduleStore()).toThrow(
      /不支持 IndexedDB/,
    )
  })
})

describe('浏览器环境探测', () => {
  it('用例11：纯 node 无 navigator/Notification 时 getFinanceNotifier 返回 null 且不抛错', () => {
    expect(() => {
      const result = getFinanceNotifier()
      expect(result).toBeNull()
    }).not.toThrow()
  })

  it('用例11补充：indexedDB 缺失但 navigator/Notification 存在时懒构造失败也返回 null', () => {
    const g = globalThis as unknown as {
      navigator?: unknown
      Notification?: unknown
    }
    g.navigator = {}
    g.Notification = function MockNotification() {}
    expect(getFinanceNotifier()).toBeNull()
  })
})

describe('registerFinanceServiceWorker', () => {
  it('用例12：navigator 缺失时静默 resolve，不抛错', async () => {
    await expect(registerFinanceServiceWorker()).resolves.toBeUndefined()
  })

  it('用例13：以 sw.js 为脚本地址调用 register（默认根作用域）', async () => {
    const register = vi.fn().mockResolvedValue(undefined)
    const fakeNav = { serviceWorker: { register } } as unknown as Navigator
    await registerFinanceServiceWorker(fakeNav)
    expect(register).toHaveBeenCalledTimes(1)
    expect(register).toHaveBeenCalledWith('sw.js')
  })

  it('用例14：register reject 时也静默 resolve，不抛错', async () => {
    const register = vi.fn().mockRejectedValue(new Error('注册被拒'))
    const fakeNav = { serviceWorker: { register } } as unknown as Navigator
    await expect(
      registerFinanceServiceWorker(fakeNav),
    ).resolves.toBeUndefined()
    expect(register).toHaveBeenCalledTimes(1)
  })
})

describe('零知识文案红线', () => {
  it('用例15：标题与三类正文逐字对齐 Android，且不含日期形态与金额符号', () => {
    const texts: Array<[string, string]> = [
      ['标题', FINANCE_NOTIFICATION_TITLE],
      ['订阅续费', FINANCE_NOTIFICATION_BODY.subscription_renewal],
      ['保单到期', FINANCE_NOTIFICATION_BODY.policy_expiry],
      ['借款到期', FINANCE_NOTIFICATION_BODY.loan_due],
    ]
    for (const [, text] of texts) {
      // 不得出现 2026-09 / 2026/9 这类年月日形态。
      expect(/\d{4}[-/]\d{1,2}/.test(text)).toBe(false)
      // 不得出现货币符号。
      expect(/[￥$€£]/.test(text)).toBe(false)
      // 不得出现具体卡号位段（连续 4 位以上数字）。
      expect(/\d{4}/.test(text)).toBe(false)
    }
    // 与 Android strings.xml 三条文案逐字一致（按 kind 固定顺序）。
    expect(
      (
        [
          'subscription_renewal',
          'policy_expiry',
          'loan_due',
        ] as FinanceReminderKind[]
      ).map((kind) => FINANCE_NOTIFICATION_BODY[kind]),
    ).toEqual([
      '订阅续费临近，点击查看',
      '保单即将到期，点击查看',
      '借款到期临近，点击查看',
    ])
    expect(FINANCE_NOTIFICATION_TITLE).toBe('财务提醒')
  })
})
