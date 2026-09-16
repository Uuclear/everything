// ============================================================================
// nextCardFiring 纯函数单元测试（stage5-finance / Task 8 / TR-8.5 部分）
// ============================================================================
//
// 验证目标（≥6 用例, 覆盖 TR-8.3 Pass Condition + 全部边界）：
//   1. fixture 数据驱动 —— 共享 nextCardFiring-cases.json（7 条用例, ≥4 硬性指标）；
//   2. 关键场景显式断言（账单日 T+0 09:00 / 还款日 T-1 09:00 / 跨月滚动）；
//   3. 边界与负例显式断言（归档卡 / 缺 billingDay / 缺 dueDay）；
//   4. upcomingTriggers 多卡批量取全局最小（与 4b rebuildChain 语义一致）；
//   5. 零知识纪律（pure 语义 + 幂等性 + 入参不被修改）。
//
// 共享 fixture（__fixtures__/nextCardFiring-cases.json）由三端共同加载, SHA-256
// 字节级一致；本测试仅读加载, 不修改 fixture 内容。
//
// 零知识纪律：
//   - 测试卡号均为业界公开示例 last4，非真实持卡人卡号；
//   - 不在断言失败消息中打印完整卡号（fixture 仅含 last4, 已是安全摘要）；
//   - 不向 localStorage / IndexedDB / 网络写入任何数据。
//
// 关联:
//   - web/src/finance/nextCardFiring.ts（被测目标）
//   - web/src/finance/__fixtures__/nextCardFiring-cases.json（共享 fixture）
//   - android/.../finance/NextCardFiring.kt（Android 镜像）
//   - android/.../finance/__fixtures__/nextCardFiring-cases.json（fixture 源）
// ============================================================================

import { describe, it, expect } from 'vitest'
import { nextTrigger, upcomingTriggers, type CardLike } from '../nextCardFiring'
import nextCardFiringCases from '../__fixtures__/nextCardFiring-cases.json'

// -----------------------------------------------------------------------------
// fixture 加载（JSON.parse + ts 静态导入, 直接复用 resolveJsonModule）
// -----------------------------------------------------------------------------

/**
 * fixture 单条用例结构（与 Android NextCardFiringCase 字段命名对齐）。
 */
interface NextCardFiringCase {
  name: string
  card: CardLike
  nowMs: number
  expectedTriggerMs: number | null
}

/**
 * 从 fixture JSON 中提取 cases 数组并转换 CardLike 字段。
 *
 * 极简解析 —— 仅消费 fixture 已知形态（避开 JSON.parse 类型推导歧义）。
 */
function loadCases(): NextCardFiringCase[] {
  const fixture = nextCardFiringCases as unknown as {
    cases: Array<{
      name: string
      input: { card: CardLike; nowMs: number }
      expected: { triggerMs: number | null }
    }>
  }
  return fixture.cases.map((c) => ({
    name: c.name,
    card: c.input.card,
    nowMs: c.input.nowMs,
    expectedTriggerMs: c.expected.triggerMs,
  }))
}

const cases: NextCardFiringCase[] = loadCases()

// -----------------------------------------------------------------------------
// 时区锁定 —— Vitest 单进程模式下由 Node 启动, 需在测试前确认 CST 时区。
// -----------------------------------------------------------------------------

// Vitest 默认环境 = node; Node TZ 受环境变量 TZ 与系统设置共同影响。
// 工程 vitest 配置未注入 TZ, 故运行时按系统时区（开发机多为 Asia/Shanghai）。
// 此处仅校验 TZ 偏移是否为 +480 分钟（CST = UTC+8），否则提示需设置 TZ=Asia/Shanghai。
const TZ_OFFSET_MIN_AT_STARTUP = -new Date(1780000000000).getTimezoneOffset()

// ============================================================================
// 1. fixture 加载驱动 —— 从 JSON 数据驱动全部用例（≥4 硬性指标）
// ============================================================================

describe('nextCardFiring / fixture 加载', () => {
  it('fixture 至少 4 条用例（TR-8.3 硬性指标）', () => {
    expect(
      cases.length,
      `fixture 用例数应不少于 4 条, 实际 = ${cases.length}`,
    ).toBeGreaterThanOrEqual(4)
  })

  it('fixture 数据驱动 —— 每条用例期望 nextTrigger 一致', () => {
    for (const tc of cases) {
      const actual = nextTrigger(tc.card, tc.nowMs)
      if (tc.expectedTriggerMs == null) {
        expect(actual, `case[${tc.name}] 期望返回 null, 实际 = ${actual}`).toBeNull()
      } else {
        expect(
          actual,
          `case[${tc.name}] 期望非 null, 实际 = null`,
        ).not.toBeNull()
        expect(actual, `case[${tc.name}] nextTrigger 不一致`).toBe(tc.expectedTriggerMs)
      }
    }
  })

  it('fixture 数据驱动 —— TZ 偏移必须为 +480 分钟（CST = UTC+8）', () => {
    expect(
      TZ_OFFSET_MIN_AT_STARTUP,
      `fixture 期望本地时区 = UTC+8（CST = +480 min）, 实际 = ${TZ_OFFSET_MIN_AT_STARTUP};`
        + '请在启动 vitest 前设置环境变量 TZ=Asia/Shanghai',
    ).toBe(480)
  })
})

// ============================================================================
// 2. 关键场景显式断言（覆盖账单日 / 还款日 / 跨月滚动）
// ============================================================================

describe('nextTrigger 关键场景', () => {
  it('账单日 T+0 09:00 CST —— 关键锚点 CST 2026-01-01 09:00 = 1767229200000', () => {
    const card: CardLike = {
      id: 'c0',
      kind: 'credit',
      billingDay: 15,
      dueDay: 25,
      archived: false,
    }
    // 账单日 15 日未过 → 返回当月 15 日 09:00 CST = 1768438800000。
    expect(nextTrigger(card, 1767229200000)).toBe(1768438800000)
  })

  it('还款日 T-1 09:00 CST —— 账单日已过但还款日未过', () => {
    // billingDay=5, dueDay=25 → 还款日 = 30；T-1 = 29。
    // nowMs = CST 2026-01-09 17:00 = 1768000800000（账单日已过, 还款日未过）。
    const card: CardLike = {
      id: 'c2',
      kind: 'credit',
      billingDay: 5,
      dueDay: 25,
      archived: false,
    }
    // 预期 CST 2026-01-29 09:00 = 1769648400000。
    expect(nextTrigger(card, 1768000800000)).toBe(1769648400000)
  })

  it('跨月滚动 —— 当月账单日 + 还款日 T-1 均已过, 返回下月账单日', () => {
    // billingDay=3, dueDay=20 → 还款日 = 23；T-1 = 22。
    // nowMs = CST 2026-01-21 09:00 = 1769302800000（账单日/还款日均已过）。
    const card: CardLike = {
      id: 'c4',
      kind: 'credit',
      billingDay: 3,
      dueDay: 20,
      archived: false,
    }
    // 预期下月账单日 CST 2026-02-03 09:00 = 1770080400000。
    expect(nextTrigger(card, 1769302800000)).toBe(1770080400000)
  })

  it('还款日 T-1 跨月钳位 —— billingDay=1 + dueDay=20, T-1 = 20 日', () => {
    // billingDay=1, dueDay=20 → 还款日 = 21；T-1 = 20。
    // nowMs = CST 2026-01-01 09:00（账单日已过 nowMs 在 1 日 09:00 之后, 还款日未过）。
    const card: CardLike = {
      id: 'c3',
      kind: 'credit',
      billingDay: 1,
      dueDay: 20,
      archived: false,
    }
    // 预期 CST 2026-01-20 09:00 = 1768870800000。
    expect(nextTrigger(card, 1767229200000)).toBe(1768870800000)
  })
})

// ============================================================================
// 3. 边界 / 负例显式断言（归档 / 缺字段 → null）
// ============================================================================

describe('nextTrigger 边界与负例', () => {
  it('归档卡 → 返回 null（不触发）', () => {
    const card: CardLike = {
      id: 'c5',
      kind: 'credit',
      billingDay: 15,
      dueDay: 25,
      archived: true,
    }
    expect(nextTrigger(card, 1767229200000)).toBeNull()
  })

  it('缺 billingDay → 返回 null（未配置账单日）', () => {
    const card: CardLike = {
      id: 'c6',
      kind: 'credit',
      billingDay: null,
      dueDay: 25,
      archived: false,
    }
    expect(nextTrigger(card, 1767229200000)).toBeNull()
  })

  it('缺 dueDay → 仅返回账单日触发, 还款日跳过', () => {
    const card: CardLike = {
      id: 'c7',
      kind: 'credit',
      billingDay: 15,
      dueDay: null,
      archived: false,
    }
    // 仍返回账单日触发 = 1768438800000。
    expect(nextTrigger(card, 1767229200000)).toBe(1768438800000)
  })
})

// ============================================================================
// 4. upcomingTriggers 多卡批量（与 4b rebuildChain 取全局最小语义一致）
// ============================================================================

describe('upcomingTriggers 多卡批量', () => {
  it('取全局最小未来触发 + 升序排列', () => {
    const cards: CardLike[] = [
      // 卡 1: 账单日 5 日, T+0 09:00 CST = 2026-01-05 09:00 = 1767574800000。
      { id: 'c1', kind: 'credit', billingDay: 5, dueDay: 25, archived: false },
      // 卡 2: 账单日 15 日, T+0 09:00 CST = 2026-01-15 09:00 = 1768438800000。
      { id: 'c2', kind: 'credit', billingDay: 15, dueDay: 25, archived: false },
      // 卡 3: 归档 → 跳过。
      { id: 'c3', kind: 'credit', billingDay: 1, dueDay: 20, archived: true },
      // 卡 4: 缺 billingDay → 跳过。
      { id: 'c4', kind: 'credit', billingDay: null, dueDay: 25, archived: false },
    ]
    const triggers = upcomingTriggers(cards, 1767229200000)
    // 期望升序: [c1 账单 5 日 = 1767574800000, c2 账单 15 日 = 1768438800000]。
    expect(triggers).toEqual([1767574800000, 1768438800000])
  })

  it('limit 截断（默认 limit=5）', () => {
    const cards: CardLike[] = [
      { id: 'c1', kind: 'credit', billingDay: 1, dueDay: 20, archived: false },
      { id: 'c2', kind: 'credit', billingDay: 5, dueDay: 25, archived: false },
      { id: 'c3', kind: 'credit', billingDay: 15, dueDay: 25, archived: false },
      { id: 'c4', kind: 'credit', billingDay: 20, dueDay: 25, archived: false },
      { id: 'c5', kind: 'credit', billingDay: 25, dueDay: 25, archived: false },
    ]
    const triggers = upcomingTriggers(cards, 1767229200000, 3)
    expect(triggers.length, 'limit=3 应截断到 3 条').toBeLessThanOrEqual(3)
    // 升序排列断言。
    for (let i = 1; i < triggers.length; i++) {
      expect(
        triggers[i]! >= triggers[i - 1]!,
        `upcomingTriggers 未按升序: triggers[${i}]=${triggers[i]} < triggers[${i - 1}]=${triggers[i - 1]}`,
      ).toBe(true)
    }
  })

  it('空输入 → 返回空列表, 不抛错', () => {
    const triggers = upcomingTriggers([], 1767229200000)
    expect(triggers).toEqual([])
  })

  it('limit ≤ 0 → 返回空列表（防御性兜底）', () => {
    const cards: CardLike[] = [
      { id: 'c1', kind: 'credit', billingDay: 15, dueDay: 25, archived: false },
    ]
    expect(upcomingTriggers(cards, 1767229200000, 0)).toEqual([])
    expect(upcomingTriggers(cards, 1767229200000, -1)).toEqual([])
  })
})

// ============================================================================
// 5. 零知识纪律（pure 语义 + 幂等性）
// ============================================================================

describe('nextTrigger 零知识纪律', () => {
  it('不修改入参 CardLike（pure 语义）', () => {
    const card: CardLike = {
      id: 'c1',
      kind: 'credit',
      billingDay: 15,
      dueDay: 25,
      archived: false,
    }
    // 浅拷贝快照。
    const snapshot = JSON.parse(JSON.stringify(card))
    nextTrigger(card, 1767229200000)
    expect(card).toEqual(snapshot)
  })

  it('纯函数无副作用 —— 相同输入多次调用结果一致（幂等性）', () => {
    const card: CardLike = {
      id: 'c1',
      kind: 'credit',
      billingDay: 15,
      dueDay: 25,
      archived: false,
    }
    const a = nextTrigger(card, 1767229200000)
    const b = nextTrigger(card, 1767229200000)
    const c = nextTrigger(card, 1767229200000)
    expect(a).toBe(b)
    expect(b).toBe(c)
  })
})