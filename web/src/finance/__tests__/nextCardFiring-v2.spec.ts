// ============================================================================
// nextCardFiring v2 纯函数单元测试（stage5-finance-v2 / Task 4 / TR-4.1 + TR-4.6）
// ============================================================================
//
// 验证目标（≥6 用例）：
//   1. fixture 字节级一致性 —— 双端共享 next-v2-firing-cases.json 的 SHA-256
//      必须等于 Android 真理源常量（TR-4.6 三端一致）；
//   2. fixture 数据驱动 —— 15 条用例按 kind 分发到三个纯函数逐条断言；
//   3. 订阅滚动边界 —— customDays 缺失 / 非正、未知 cycle、月末钳位、
//      多周期滚动、MAX_CYCLE_LOOKAHEAD=24 临界；
//   4. 候选口径 —— 严格大于 nowMs、负偏移忽略；
//   5. 保单 / 借款早退 —— inactive / paid / 空 reminders / 未知 status；
//   6. 三个 snake_case → DTO 适配器字段对齐。
//
// 时区：vitest node 环境，fixture 全部期望按 CST（UTC+8）精算；本文件首组
// 用例锁定运行时时区偏移必须为 +480 分钟。
//
// 关联:
//   - web/src/finance/nextCardFiring.ts（被测目标）
//   - web/src/finance/__fixtures__/next-v2-firing-cases.json（双端共享 fixture）
//   - android/.../finance/NextCardFiring.kt（Android 镜像真理源）
// ============================================================================

import { describe, it, expect } from 'vitest'
import {
  nextLoanDue,
  nextPolicyExpiry,
  nextSubscriptionRenewal,
  toLoanLike,
  toPolicyLike,
  toSubscriptionLike,
  type LoanLike,
  type PolicyLike,
  type SubscriptionLike,
} from '../nextCardFiring'
import type { FinanceLoan, FinancePolicy, FinanceSubscription } from '../types'
// ?raw 导入拿 fixture 原始字节（vite/client 提供类型），用于 SHA-256 核验；
// JSON 同名导入用于 case 数据驱动。
import nextV2Raw from '../__fixtures__/next-v2-firing-cases.json?raw'
import nextV2Json from '../__fixtures__/next-v2-firing-cases.json'

// ============================================================================
// 常量与 fixture 加载
// ============================================================================

/** fixture 真理源 SHA-256（与 Android 镜像必须逐字节一致，TR-4.6）。 */
const EXPECTED_FIXTURE_SHA256 =
  'c4ea61f7cc17c177ec93fc8cafc4b0cb616d8267ca79eb77bc17440f26edcb76'

/** CST 相对 UTC 的偏移毫秒（UTC+8）。 */
const CST_OFFSET_MS = 8 * 60 * 60 * 1000

/** 一天的毫秒数（custom_days 滚动用）。 */
const DAY_MS = 24 * 60 * 60 * 1000

/**
 * 由 CST 墙钟分量构造 Unix 毫秒：CST 本地时刻 = 同字面值 UTC 时刻减 8 小时。
 *
 * @param year 公历年
 * @param month0 月（0 基，与 Date.UTC 一致）
 * @param day 日
 */
function cstMs(year: number, month0: number, day: number, hour = 0, minute = 0): number {
  return Date.UTC(year, month0, day, hour, minute) - CST_OFFSET_MS
}

/** Web Crypto 计算 UTF-8 文本的 SHA-256 hex（Node 18+ 全局 crypto 可用）。 */
async function sha256Hex(text: string): Promise<string> {
  const digest = await crypto.subtle.digest(
    'SHA-256',
    new TextEncoder().encode(text),
  )
  return Array.from(new Uint8Array(digest), (b) => b.toString(16).padStart(2, '0')).join('')
}

type FiringKind = 'subscription' | 'policy' | 'loan'

interface FiringCase {
  name: string
  kind: FiringKind
  input: SubscriptionLike | PolicyLike | LoanLike
  expected: number | null
}

interface FiringFixture {
  nowMs: number
  cases: FiringCase[]
}

const fixture = nextV2Json as unknown as FiringFixture
/** fixture 锚点 nowMs = 2026-06-28 12:00:00 CST。 */
const NOW = fixture.nowMs

/** 按 case.kind 分发到对应纯函数。 */
function dispatch(tc: FiringCase, nowMs: number): number | null {
  if (tc.kind === 'subscription') {
    return nextSubscriptionRenewal(tc.input as SubscriptionLike, nowMs)
  }
  if (tc.kind === 'policy') {
    return nextPolicyExpiry(tc.input as PolicyLike, nowMs)
  }
  return nextLoanDue(tc.input as LoanLike, nowMs)
}

// ============================================================================
// 1. fixture 字节级一致性（TR-4.6）与环境锚点
// ============================================================================

describe('nextCardFiring v2 / fixture 一致性', () => {
  it('fixture SHA-256 与 Android 真理源常量逐字节一致', async () => {
    const actual = await sha256Hex(nextV2Raw)
    expect(
      actual,
      'fixture 被改写或行尾变化（必须 LF、无 BOM）；请与 Android 镜像重新对齐',
    ).toBe(EXPECTED_FIXTURE_SHA256)
  })

  it('运行时时区偏移必须为 +480 分钟（CST = UTC+8）', () => {
    const offsetMin = -new Date(1780000000000).getTimezoneOffset()
    expect(
      offsetMin,
      `fixture 期望 UTC+8（+480 分钟），实际 = ${offsetMin}；请设置 TZ=Asia/Shanghai`,
    ).toBe(480)
  })

  it('fixture 含 15 条用例（8 订阅 + 4 保单 + 3 借款）', () => {
    expect(fixture.cases).toHaveLength(15)
    expect(fixture.cases.filter((c) => c.kind === 'subscription')).toHaveLength(8)
    expect(fixture.cases.filter((c) => c.kind === 'policy')).toHaveLength(4)
    expect(fixture.cases.filter((c) => c.kind === 'loan')).toHaveLength(3)
  })
})

// ============================================================================
// 2. fixture 全量数据驱动
// ============================================================================

describe('nextCardFiring v2 / fixture 数据驱动', () => {
  it('15 条用例逐条例证三纯函数输出', () => {
    for (const tc of fixture.cases) {
      const actual = dispatch(tc, NOW)
      if (tc.expected === null) {
        expect(actual, `case[${tc.name}] 期望 null，实际 = ${actual}`).toBeNull()
      } else {
        expect(actual, `case[${tc.name}] 期望非 null，实际 = null`).not.toBeNull()
        expect(actual, `case[${tc.name}] 触发毫秒不一致`).toBe(tc.expected)
      }
    }
  })
})

// ============================================================================
// 3. 订阅滚动边界（fixture 之外的具名补充）
// ============================================================================

describe('nextSubscriptionRenewal 滚动边界', () => {
  it('custom_days 且 customDays 为 null / 0 / 负数 → null（基准已过时）', () => {
    const base = {
      id: 'sub-edge',
      active: true,
      nextRenewalTs: cstMs(2026, 5, 1),
      billingCycle: 'custom_days',
      reminders: [0],
    }
    expect(
      nextSubscriptionRenewal({ ...base, customDays: null }, NOW),
    ).toBeNull()
    expect(
      nextSubscriptionRenewal({ ...base, customDays: 0 }, NOW),
    ).toBeNull()
    expect(
      nextSubscriptionRenewal({ ...base, customDays: -7 }, NOW),
    ).toBeNull()
  })

  it('未知 billingCycle：基准过期 → null；基准在未来 → 不滚动直接取候选', () => {
    const past = nextSubscriptionRenewal(
      {
        id: 'sub-unknown-past',
        active: true,
        nextRenewalTs: cstMs(2026, 5, 1),
        billingCycle: 'weekly',
        customDays: null,
        reminders: [0],
      },
      NOW,
    )
    expect(past).toBeNull()

    const futureTs = cstMs(2026, 6, 5, 9)
    const future = nextSubscriptionRenewal(
      {
        id: 'sub-unknown-future',
        active: true,
        nextRenewalTs: futureTs,
        billingCycle: 'weekly',
        customDays: null,
        reminders: [0, 60],
      },
      NOW,
    )
    // r=60 候选 = 07-05 08:00；取最小未来候选。
    expect(future).toBe(cstMs(2026, 6, 5, 8))
  })

  it('monthly 多周期滚动：4 月基准连滚 3 次到 7 月（保留 08:30 时分）', () => {
    // 2026-04-15 08:30 CST → 5/15（过）→ 6/15（过）→ 7/15 08:30（未来）。
    const actual = nextSubscriptionRenewal(
      {
        id: 'sub-multi',
        active: true,
        nextRenewalTs: cstMs(2026, 3, 15, 8, 30),
        billingCycle: 'monthly',
        customDays: null,
        reminders: [0],
      },
      NOW,
    )
    expect(actual).toBe(cstMs(2026, 6, 15, 8, 30))
  })

  it('monthly 月末钳位：1/31 滚到平年 2/28（与 java.time plusMonths 同裁剪）', () => {
    const nowFeb = cstMs(2026, 1, 15, 12)
    const actual = nextSubscriptionRenewal(
      {
        id: 'sub-eom',
        active: true,
        nextRenewalTs: cstMs(2026, 0, 31, 9),
        billingCycle: 'monthly',
        customDays: null,
        reminders: [0],
      },
      nowFeb,
    )
    // 2026 为平年，2 月最大日 28。
    expect(actual).toBe(cstMs(2026, 1, 28, 9))
  })

  it('防御上限临界：需 24 次滚动成功取未来；需 25 次（基准再早一天）→ null', () => {
    // 基准 = now 减 23 天，customDays=1：连滚 24 次到 now 加 1 天，成功。
    const withinCap = nextSubscriptionRenewal(
      {
        id: 'sub-cap-ok',
        active: true,
        nextRenewalTs: NOW - 23 * DAY_MS,
        billingCycle: 'custom_days',
        customDays: 1,
        reminders: [0],
      },
      NOW,
    )
    expect(withinCap).toBe(NOW + DAY_MS)

    // 基准 = now 减 24 天：滚满 24 次仅到 now（不严格大于 now），触顶 → null。
    const exceedsCap = nextSubscriptionRenewal(
      {
        id: 'sub-cap-null',
        active: true,
        nextRenewalTs: NOW - 24 * DAY_MS,
        billingCycle: 'custom_days',
        customDays: 1,
        reminders: [0],
      },
      NOW,
    )
    expect(exceedsCap).toBeNull()
  })
})

// ============================================================================
// 4. 候选口径：严格大于 nowMs、负偏移忽略
// ============================================================================

describe('earliestFutureReminder 共享候选口径', () => {
  it('候选恰好等于 nowMs 视为已过；增加 r=0 候选后取基准时刻', () => {
    const expiry = NOW + 60_000
    // reminders=[1]：唯一候选 = expiry 减 1 分钟 = now，严格大于不成立 → null。
    expect(
      nextPolicyExpiry(
        { id: 'pol-eq', active: true, expiryTs: expiry, reminders: [1] },
        NOW,
      ),
    ).toBeNull()
    // reminders=[0,1]：r=0 候选在未来，返回基准本身。
    expect(
      nextPolicyExpiry(
        { id: 'pol-eq2', active: true, expiryTs: expiry, reminders: [0, 1] },
        NOW,
      ),
    ).toBe(expiry)
  })

  it('负偏移（语义为之后提醒）防御性忽略，r=0 候选照常生效', () => {
    const expiry = NOW + 1000
    const actual = nextPolicyExpiry(
      { id: 'pol-neg', active: true, expiryTs: expiry, reminders: [-100, 0] },
      NOW,
    )
    expect(actual).toBe(expiry)
  })
})

// ============================================================================
// 5. 保单 / 借款早退与未知 status 补充
// ============================================================================

describe('nextPolicyExpiry / nextLoanDue 边界补充', () => {
  it('loan 未知 status 与 overdue（到期日在未来）均照常提醒；空 reminders → null', () => {
    const due = cstMs(2026, 7, 5, 10)
    expect(
      nextLoanDue({ id: 'loan-unknown', status: 'paused', dueTs: due, reminders: [0] }, NOW),
    ).toBe(due)
    expect(
      nextLoanDue({ id: 'loan-overdue', status: 'overdue', dueTs: due, reminders: [0] }, NOW),
    ).toBe(due)
    expect(
      nextLoanDue({ id: 'loan-empty', status: 'active', dueTs: due, reminders: [] }, NOW),
    ).toBeNull()
  })

  it('policy 基准在未来但所有候选已过期（超大提前量）→ null', () => {
    // 到期在 1 小时后，但唯一提醒要求提前 1 天 → 候选已过 → null。
    const actual = nextPolicyExpiry(
      { id: 'pol-far-reminder', active: true, expiryTs: NOW + 3600_000, reminders: [1440] },
      NOW,
    )
    expect(actual).toBeNull()
  })
})

// ============================================================================
// 6. 适配器：store 明文 snake_case → 提醒 DTO
// ============================================================================

describe('v2 提醒适配器', () => {
  it('toSubscriptionLike 映射 next_renewal_ts / billing_cycle / custom_days', () => {
    const raw = {
      id: 's1',
      active: true,
      next_renewal_ts: NOW + 123,
      billing_cycle: 'monthly',
      custom_days: null,
      reminders: [0, 1440],
    } as unknown as FinanceSubscription
    expect(toSubscriptionLike(raw)).toEqual({
      id: 's1',
      active: true,
      nextRenewalTs: NOW + 123,
      billingCycle: 'monthly',
      customDays: null,
      reminders: [0, 1440],
    })
  })

  it('toPolicyLike 映射 expiry_ts', () => {
    const raw = {
      id: 'p1',
      active: false,
      expiry_ts: NOW + 5,
      reminders: [0, 1],
    } as unknown as FinancePolicy
    expect(toPolicyLike(raw)).toEqual({
      id: 'p1',
      active: false,
      expiryTs: NOW + 5,
      reminders: [0, 1],
    })
  })

  it('toLoanLike 映射 due_ts / status（提醒专用 DTO，无金额字段）', () => {
    const raw = {
      id: 'l1',
      status: 'active',
      due_ts: NOW + 9,
      reminders: [0],
    } as unknown as FinanceLoan
    const dto = toLoanLike(raw)
    expect(dto).toEqual({
      id: 'l1',
      status: 'active',
      dueTs: NOW + 9,
      reminders: [0],
    })
    // 适配器产物可直接喂给纯函数并得到一致结果。
    expect(nextLoanDue(dto, NOW)).toBe(NOW + 9)
  })
})
