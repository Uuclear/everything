// 阶段 4b — expand.ts 跨端共享测试（tasks.md Task 2 / TR-2.3）。
//
// 验证策略：
//   1. 从 `__fixtures__/cases.json` 加载 24 个用例（CST/UTC+8 锚定）。
//   2. 每个用例逐字段断言 `expand(rule, window)` 输出与 `expected` 完全一致
//      （Occurrence 字段顺序无关—— `toEqual` 做深比较）。
//   3. 失败用例给出实例化信息便于排查（rule.id / window.from / window.to）。
//
// 注意：
//   - 本机必须为 UTC+8（CST）以与 fixture 锚定一致；其他时区会让断言失败，
//     这正是 spec "跨端应共享 fixture" 的硬约束——Android 端 Task 3 镜像亦
//     须保证测试入口 `user.timezone=Asia/Shanghai`。
//   - fixture 中 all_day 跨日事件用 `end_ts = start_ts + N * 86_400_000`
//     保持天数 × 一天的持续时长（spec FR-1）。

import { describe, it, expect } from 'vitest'

// 引入被测实现与共享类型。
import { expand, type EventRule, type TimeWindow, type Occurrence } from './expand'

// 引入 fixture（顶层包含 comment / tz_anchor / ts_anchor_comment 等元信息，
// 用例数组在 cases 字段）。
import fixture from './__fixtures__/cases.json'

// =============================================================================
// Fixture 类型（仅在本测试文件内使用，避免与实现类型相互污染）
// =============================================================================

/** 单个 fixture 用例的形状（与 expand.ts 的 EventRule / TimeWindow / Occurrence 对齐）。 */
interface FixtureCase {
  name: string
  rule: EventRule
  window: TimeWindow
  expected: Occurrence[]
}

// Vitest/TS 的静态分析：JSON 顶层我们只取 cases 字段并断言形状。
const cases = fixture.cases as FixtureCase[]

// =============================================================================
// 套件
// =============================================================================

describe('expand (cross-stage fixture)', () => {
  // 守住"≥24 用例"的硬性指标（任务 TR-2.3 Pass Condition）。
  it('fixture 至少 24 用例', () => {
    expect(cases.length).toBeGreaterThanOrEqual(24)
  })

  // 逐用例断言：每个用例一个独立 it，失败时能定位到具体 case.name。
  for (const c of cases) {
    it(c.name, () => {
      const got = expand(c.rule, c.window)
      // 深比较：Occurrence 字段顺序无关；多/少字段都会被捕获。
      expect(got).toEqual(c.expected)
    })
  }
})