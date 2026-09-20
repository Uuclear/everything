// ============================================================================
// finance store 通知接线单元测试（stage5-finance-v2 / B7 / FR-V2-G）
// ============================================================================
//
// 验证目标（10 用例）：
//   1. 默认偏好位 false，且新 store 初次 persist 时落盘 notificationsEnabled=false；
//   2. 偏好位持久化往返：同一内存通道喂给第二个 store，hydrate 后恢复 true；
//   3. setNotificationsEnabled(true) 成功 → 返回 true、偏好位置位、reschedule
//      被调一次，且条目键集合严格等于 { id, kind, triggerMs }（零知识红线）；
//   4. enable 被权限拒绝 → 返回 false、偏好位保持 false、不触发 reschedule；
//   5. 环境不支持（getFinanceNotifier 返回 null）→ 返回 false 且绝不抛错；
//   6. setNotificationsEnabled(false) → 调 notifier.disable，偏好位回落 false；
//   7. CRUD（addSubscription）在启用态触发 reschedule、关闭态不触发；
//   8. hydrate 完成后触发一次 reschedule（偏好位开启时）；
//   9. reset() 调 notifier.dispose 但保留偏好位；_resetForTest 复位偏好位并
//      调 resetFinanceNotifierForTest（用例间隔离）；
//  10. replayDueNotifications 启用态调 fireDueOnOpen、关闭态短路；
//      stopNotifications 直通 dispose 且不抛错。
//
// 测试策略：
//   - vitest node 环境，不 mount 任何组件；
//   - vi.mock 整体替换通知基础层，提供可控假 notifier 与可观察的单例重置钩子；
//   - store 装配照 B6 BudgetList.spec.ts：内存 StorageChannel + noop CryptoChannel；
//   - store 对通知层全部是 void Promise 非阻塞接线，断言前用 setTimeout(0)
//     排空微任务队列。
//
// 关联:
//   - web/src/stores/finance.ts（被测目标）
//   - web/src/notifications/financeNotifications.ts（被 mock 的基础层，禁改）
// ============================================================================

import { describe, it, expect, beforeEach, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import type {
  FinanceNotifier,
  FinanceScheduleEntry,
  ScheduledSummary,
} from '../../notifications/financeNotifications'
import type { FinanceSubscription } from '../../finance/types'
import {
  useFinanceStore,
  type StorageChannel,
  type CryptoChannel,
  type PersistedFinanceState,
} from '../finance'

// ——————————————————————————————————————————————————————————————————————————
// mock：通知基础层（SA-1 交付的模块整体替换）
// ——————————————————————————————————————————————————————————————————————————
//
// vi.hoisted 保证工厂与状态在 vi.mock 提升后仍可被本文件用例代码引用。
// 控制面（nullNotifier / grantEnable）决定假工厂 / 假 enable 的行为；
// 观察面（lastResetNotifier 等）用于断言接线动作确实发生。

const notifierState = vi.hoisted(() => ({
  /** 假通知器当前启用位（enable/disable/dispose 翻转它）。 */
  active: false,
  /** getFinanceNotifier 是否返回 null（模拟 node / 不支持环境）。 */
  nullNotifier: false,
  /** enable() 是否成功（false 模拟权限被拒）。 */
  grantEnable: true,
  /** resetFinanceNotifierForTest 被调次数。 */
  resetCalls: 0,
}))

/** 构造一个行为可控、调用可观察的假通知器（结构满足 FinanceNotifier）。 */
function makeFakeNotifier() {
  return {
    // 真实 notifier 的 enabled 是只读 getter，这里以 getter 对齐形态。
    get enabled(): boolean {
      return notifierState.active
    },
    enable: vi.fn(async (): Promise<boolean> => {
      if (notifierState.grantEnable) {
        notifierState.active = true
        return true
      }
      return false
    }),
    disable: vi.fn(async (): Promise<void> => {
      notifierState.active = false
    }),
    reschedule: vi.fn(
      async (_entries: FinanceScheduleEntry[]): Promise<ScheduledSummary> => ({
        scheduled: 0,
        expired: 0,
      }),
    ),
    fireDueOnOpen: vi.fn(
      async (): Promise<FinanceScheduleEntry[]> => [],
    ),
    dispose: vi.fn((): void => {
      notifierState.active = false
    }),
  } satisfies FinanceNotifier
}

// 单例引用放在 hoisted 区之外、模块工厂闭包内：每次 resetFinanceNotifierForTest
// 或 beforeEach 都重建一个全新假对象，避免历史 mock 调用计数污染。
let fakeNotifier: ReturnType<typeof makeFakeNotifier>

vi.mock('../../notifications/financeNotifications', () => ({
  // store 顶部 re-export 该符号，mock 中必须提供（空实现即可）。
  registerFinanceServiceWorker: vi.fn(async () => undefined),
  getFinanceNotifier: vi.fn((): FinanceNotifier | null =>
    notifierState.nullNotifier ? null : (fakeNotifier as FinanceNotifier),
  ),
  resetFinanceNotifierForTest: vi.fn((): void => {
    notifierState.resetCalls += 1
  }),
}))

// ——————————————————————————————————————————————————————————————————————————
// store 装配（内存持久化 + noop 加密通道，照 B6 BudgetList.spec.ts）
// ——————————————————————————————————————————————————————————————————————————

/** 可外置内部状态的内存通道：同一 state 可喂给多个 store 实例（测持久化往返）。 */
function memoryChannel(seed?: { state: PersistedFinanceState | null }): StorageChannel {
  const box = seed ?? { state: null as PersistedFinanceState | null }
  return {
    read() {
      return box.state == null ? null : (JSON.parse(JSON.stringify(box.state)) as PersistedFinanceState)
    },
    write(next) {
      box.state = JSON.parse(JSON.stringify(next)) as PersistedFinanceState
    },
    clear() {
      box.state = null
    },
  }
}

function noopCryptoChannel(): CryptoChannel {
  return {
    seal: () => '',
    open: () => ({}) as never,
    push: async () => ({ applied: 1, skipped: 0, server_time: Date.now() }),
    list: async () => ({ records: [], has_more: false }),
  }
}

/** 合法订阅 fixture（满足 validateV2Payload；触发时刻在未来 7 天）。 */
function makeSubscription(over: Partial<FinanceSubscription> = {}): FinanceSubscription {
  const now = Date.now()
  const base = now - 30 * 86400000
  return {
    id: 'sub-notify-1',
    schema_version: 2,
    name: '云盘会员',
    provider: '某云盘',
    amount_minor: '20.00',
    currency: 'CNY',
    billing_cycle: 'monthly',
    custom_days: null,
    start_ts: base,
    // 下次续费在 7 天后；reminders=[0] 时触发时刻严格等于该值。
    next_renewal_ts: now + 7 * 86400000,
    reminders: [0],
    active: true,
    category: 'productivity',
    created_at: base,
    updated_at: base,
    ...over,
  }
}

/**
 * 排空 store 内所有 void Promise 接线产生的微任务。
 * store 的通知调用链全为 async（reschedule 等 await 链），连续两个
 * macrotask 足以让其落定。
 */
async function flushVoid(): Promise<void> {
  await new Promise((resolve) => setTimeout(resolve, 0))
  await new Promise((resolve) => setTimeout(resolve, 0))
}

beforeEach(() => {
  // 控制面与观察面复位。
  notifierState.active = false
  notifierState.nullNotifier = false
  notifierState.grantEnable = true
  notifierState.resetCalls = 0
  fakeNotifier = makeFakeNotifier()

  setActivePinia(createPinia())
  const store = useFinanceStore()
  store._setStorageForTest(memoryChannel())
  store._setChannelForTest(noopCryptoChannel())
})

describe('finance store / 通知偏好位默认值与持久化', () => {
  it('默认 false；首次写库后落盘 notificationsEnabled=false', () => {
    const box = { state: null as PersistedFinanceState | null }
    const store = useFinanceStore()
    expect(store.notificationsEnabled).toBe(false)
    // hydrate 本身不写盘；用一次 CRUD 触发 persist 后检查落盘形态。
    store._setStorageForTest(memoryChannel(box))
    store.hydrate()
    store.addSubscription(makeSubscription({ id: 'sub-persist-1' }))
    expect(box.state?.notificationsEnabled).toBe(false)
  })

  it('偏好位持久化往返：第二个 store hydrate 后恢复 true', async () => {
    const box = { state: null as PersistedFinanceState | null }
    const storeA = useFinanceStore()
    storeA._setStorageForTest(memoryChannel(box))
    storeA._setChannelForTest(noopCryptoChannel())
    storeA.hydrate()
    const ok = await storeA.setNotificationsEnabled(true)
    expect(ok).toBe(true)
    // 落盘形态断言：仍是 schemaVersion=2，偏好位与业务字段同级。
    expect(box.state?.schemaVersion).toBe(2)
    expect(box.state?.notificationsEnabled).toBe(true)

    // 第二个 pinia / store 复用同一通道：hydrate 应还原偏好位。
    setActivePinia(createPinia())
    const storeB = useFinanceStore()
    storeB._setStorageForTest(memoryChannel(box))
    storeB._setChannelForTest(noopCryptoChannel())
    storeB.hydrate()
    await flushVoid()
    expect(storeB.notificationsEnabled).toBe(true)
  })
})

describe('finance store / setNotificationsEnabled 启用路径', () => {
  it('启用成功 → true、reschedule 一次，条目键集合严格三白名单', async () => {
    const store = useFinanceStore()
    store.hydrate()
    store.addSubscription(makeSubscription())
    const ok = await store.setNotificationsEnabled(true)
    expect(ok).toBe(true)
    expect(store.notificationsEnabled).toBe(true)
    expect(fakeNotifier.enable).toHaveBeenCalledTimes(1)
    expect(fakeNotifier.reschedule).toHaveBeenCalledTimes(1)

    // 零知识红线：逐条检查键集合严格等于 ['id','kind','triggerMs']，
    // 多一个键（金额 / 名称 / 日期等）即失败。
    const entries = fakeNotifier.reschedule.mock.calls[0]?.[0] ?? []
    expect(entries.length).toBeGreaterThan(0)
    for (const entry of entries) {
      const keys = Object.keys(entry).sort()
      expect(keys).toEqual(['id', 'kind', 'triggerMs'])
      // kind 取值也锁定在三类抽象类型内。
      expect(['subscription_renewal', 'policy_expiry', 'loan_due']).toContain(entry.kind)
      expect(typeof entry.triggerMs).toBe('number')
    }
    // fixture 只有一条订阅且 reminders=[0]，触发时刻即下次续费时刻。
    const first = entries[0] as FinanceScheduleEntry
    expect(first.id).toBe('sub-notify-1')
    expect(first.kind).toBe('subscription_renewal')
  })

  it('权限被拒 → false、偏好位保持 false、不触发 reschedule', async () => {
    notifierState.grantEnable = false
    const store = useFinanceStore()
    store.hydrate()
    store.addSubscription(makeSubscription())
    const ok = await store.setNotificationsEnabled(true)
    expect(ok).toBe(false)
    expect(store.notificationsEnabled).toBe(false)
    expect(fakeNotifier.reschedule).not.toHaveBeenCalled()
  })

  it('环境不支持（notifier=null）→ false 且不抛错', async () => {
    notifierState.nullNotifier = true
    const store = useFinanceStore()
    store.hydrate()
    let threw = false
    let ok = true
    try {
      ok = await store.setNotificationsEnabled(true)
    } catch {
      threw = true
    }
    expect(threw).toBe(false)
    expect(ok).toBe(false)
    expect(store.notificationsEnabled).toBe(false)
  })
})

describe('finance store / setNotificationsEnabled 关闭路径', () => {
  it('关闭 → 调 disable，偏好位回落 false', async () => {
    const store = useFinanceStore()
    store.hydrate()
    await store.setNotificationsEnabled(true)
    expect(store.notificationsEnabled).toBe(true)
    const result = await store.setNotificationsEnabled(false)
    expect(result).toBe(false)
    expect(store.notificationsEnabled).toBe(false)
    expect(fakeNotifier.disable).toHaveBeenCalledTimes(1)
    // 关闭后再来的 CRUD 不得触发调度（前置短路）。
    fakeNotifier.reschedule.mockClear()
    store.addSubscription(makeSubscription({ id: 'sub-after-off' }))
    await flushVoid()
    expect(fakeNotifier.reschedule).not.toHaveBeenCalled()
  })
})

describe('finance store / CRUD 与 hydrate 触发调度', () => {
  it('启用态 addSubscription 触发 reschedule；void 非阻塞但可被 flush 观察', async () => {
    const store = useFinanceStore()
    store.hydrate()
    await store.setNotificationsEnabled(true)
    fakeNotifier.reschedule.mockClear()
    // addSubscription 内部以 void Promise 触发调度。注意：syncNotificationSchedules
    // 虽是 async，但其函数体在第一个 await 之前会同步执行到 notifier.reschedule(entries)
    // 这一句，vi.fn 的调用记录在同一 tick 即写入，因此此处不能断言“尚未调用”；
    // flushVoid 仅保证被 void 掉的 Promise 完整落定，调用次数恰为 1 即证明接线成功。
    store.addSubscription(makeSubscription({ id: 'sub-crud-1' }))
    await flushVoid()
    expect(fakeNotifier.reschedule).toHaveBeenCalledTimes(1)
  })

  it('hydrate 完成后在启用态触发一次全量对账', async () => {
    // 先造一份偏好位为 true 的本地状态。
    const box = { state: null as PersistedFinanceState | null }
    const storeA = useFinanceStore()
    storeA._setStorageForTest(memoryChannel(box))
    storeA._setChannelForTest(noopCryptoChannel())
    storeA.hydrate()
    await storeA.setNotificationsEnabled(true)
    expect(box.state?.notificationsEnabled).toBe(true)

    // 新 store 从同一通道 hydrate：还原偏好位后应触发 reschedule。
    setActivePinia(createPinia())
    const storeB = useFinanceStore()
    storeB._setStorageForTest(memoryChannel(box))
    storeB._setChannelForTest(noopCryptoChannel())
    // 通知器单例仍为 enabled（假 notifier 的 active 由 enable 翻转且未 dispose）。
    expect(notifierState.active).toBe(true)
    storeB.hydrate()
    await flushVoid()
    expect(fakeNotifier.reschedule).toHaveBeenCalled()
  })
})

describe('finance store / 补发、停止与重置隔离', () => {
  it('replayDueNotifications 启用态调 fireDueOnOpen；关闭态短路', async () => {
    const store = useFinanceStore()
    store.hydrate()
    // 关闭态：直接短路，不触达通知器。
    await store.replayDueNotifications()
    expect(fakeNotifier.fireDueOnOpen).not.toHaveBeenCalled()

    await store.setNotificationsEnabled(true)
    await store.replayDueNotifications()
    expect(fakeNotifier.fireDueOnOpen).toHaveBeenCalledTimes(1)
  })

  it('reset() 调 dispose 但保留偏好位；_resetForTest 复位偏好位并重置单例', async () => {
    const store = useFinanceStore()
    store.hydrate()
    await store.setNotificationsEnabled(true)
    expect(store.notificationsEnabled).toBe(true)

    // 生产锁定口径：释放通知器资源，但偏好位刻意保留（解锁后无感恢复）。
    store.reset()
    expect(fakeNotifier.dispose).toHaveBeenCalledTimes(1)
    expect(store.notificationsEnabled).toBe(true)

    // 测试钩子口径：偏好位复位 + 通知器单例清空（保证用例间隔离）。
    store._resetForTest()
    expect(store.notificationsEnabled).toBe(false)
    expect(notifierState.resetCalls).toBe(1)
  })

  it('stopNotifications 直通 dispose 且自身不抛错（即便通知层异常）', () => {
    const store = useFinanceStore()
    store.hydrate()
    fakeNotifier.dispose.mockImplementationOnce(() => {
      throw new Error('notifier boom')
    })
    expect(() => store.stopNotifications()).not.toThrow()
  })
})
