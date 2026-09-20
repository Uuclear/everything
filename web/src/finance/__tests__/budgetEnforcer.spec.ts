// ============================================================================
// BudgetEnforcer 纯函数单元测试（stage5-finance-v2 / Task 6 / B6 第一批）
// ============================================================================
//
// 验证目标：
//   1. fixture SHA-256 硬编码守护（双端字节级一致，与 Android
//      BudgetEnforcerTest 同 SHA；?raw 原始 UTF-8 字节经 node:crypto 摘要）；
//   2. fixture cases 段全部 20 条数据驱动，逐条比对 checkTx 结果的
//      level / budgetId / category / currency / spent / incoming /
//      projected / limit / usedPct / thresholdPct 全字段；
//   3. periodBucket 月 / 周 / 年 / custom / 有效期外 / 相邻周桶 6 条边界；
//   4. checkTx 对非 expense 流水直接返回 OK_EMPTY；
//   5. evaluateBudget 三档边界直测（79 OK / 80 WARNING / 100 BLOCK）；
//   6. validateBudget 7 条（合法 / scope / 金额 / 阈值倒挂 / end<start /
//      缺 id / startTs 非正与 category 超长）。
//
// 关联:
//   - web/src/finance/budgetEnforcer.ts（被测目标）
//   - web/src/finance/__fixtures__/budget-enforcer-cases.json（共享 fixture）
// ============================================================================

import { describe, it, expect } from 'vitest'
import { createHash } from 'node:crypto'
import {
  OK_EMPTY,
  checkTx,
  evaluateBudget,
  periodBucket,
  type BudgetCheckResult,
  type BudgetTxLike,
} from '../budgetEnforcer'
import { parseRateTable, type RateTable } from '../rateTable'
import { validateBudget, type FinanceBudget } from '../types'
// ?raw 拿原始文本，再经 TextEncoder 还原为原始 UTF-8 字节做摘要
//（fixture 为 LF、无 BOM 的合法 UTF-8，编码结果与磁盘字节逐字节一致）。
import budgetEnforcerRaw from '../__fixtures__/budget-enforcer-cases.json?raw'
import budgetEnforcerJson from '../__fixtures__/budget-enforcer-cases.json'

// =============================================================================
// 常量与 fixture 类型
// =============================================================================

/** fixture 文件 SHA-256（小写 hex）—— 与 Android 测试常量必须逐字节一致。 */
const EXPECTED_FIXTURE_SHA256 =
  '7e568077870c608172bd6a0b643aaf50fdbf15c8a571512871db113900342e44'

/** 锚点时刻：2026-06-28 12:00:00 CST（周日）。 */
const ANCHOR_NOW_MS = 1_782_619_200_000

/** fixture 中的流水最小形态（snake_case，金额为 decimal 元字符串）。 */
interface FixtureTx {
  id?: string
  kind: string
  amount_minor: string
  category: string
  currency: string
  occurred_at: number
}

/** fixture rate_table 段为 snake_case 明文汇率包。 */
interface FixtureRateTablePkg {
  effective_ts: number
  rates: Record<string, number>
}

/** 单条用例 expect 段（契约外 threshold_pct 为本 fixture 额外锁定字段）。 */
interface Expect {
  level: string
  used_pct: number
  category: string
  currency: string
  budget_id: string | null
  spent_minor: number
  incoming_minor: number
  projected_minor: number
  limit_minor: number
  threshold_pct: number
}

/** fixture 单条用例。 */
interface FixtureCase {
  name: string
  budgets: FinanceBudget[]
  rate_table: FixtureRateTablePkg | null
  existing: FixtureTx[]
  incoming: FixtureTx
  expect: Expect
}

interface BudgetEnforcerFixture {
  _schema_ref_: string
  anchor_now_ms: number
  cases: FixtureCase[]
}

const fixture = budgetEnforcerJson as unknown as BudgetEnforcerFixture

/** JSON 对象 → BudgetTxLike；id 缺失按空串容错（incoming 允许省略 id）。 */
function toTx(o: FixtureTx): BudgetTxLike {
  return {
    id: o.id ?? '',
    kind: o.kind,
    amountMinor: o.amount_minor,
    category: o.category,
    currency: o.currency,
    occurredAt: o.occurred_at,
  }
}

/** rate_table 段非 null 时重新序列化后走 parseRateTable 入口（顺带验证 parse）。 */
function toRateTable(c: FixtureCase): RateTable | null {
  return c.rate_table === null || c.rate_table === undefined
    ? null
    : parseRateTable(JSON.stringify(c.rate_table))
}

/** 全字段比对期望与实际，失败信息带 case 名与字段名（bigint 需同型比较）。 */
function assertResultMatches(clue: string, e: Expect, a: BudgetCheckResult): void {
  expect(a.level, `${clue} level 不一致`).toBe(e.level)
  expect(a.budgetId, `${clue} budgetId 不一致`).toBe(e.budget_id)
  expect(a.category, `${clue} category 不一致`).toBe(e.category)
  expect(a.currency, `${clue} currency 不一致`).toBe(e.currency)
  expect(a.spentMinor, `${clue} spentMinor 不一致`).toBe(BigInt(e.spent_minor))
  expect(a.incomingMinor, `${clue} incomingMinor 不一致`).toBe(BigInt(e.incoming_minor))
  expect(a.projectedMinor, `${clue} projectedMinor 不一致`).toBe(BigInt(e.projected_minor))
  expect(a.limitMinor, `${clue} limitMinor 不一致`).toBe(BigInt(e.limit_minor))
  expect(a.usedPct, `${clue} usedPct 不一致`).toBe(e.used_pct)
  expect(a.thresholdPct, `${clue} thresholdPct 不一致`).toBe(e.threshold_pct)
}

// =============================================================================
// 1. fixture 完整性守护
// =============================================================================

describe('budgetEnforcer / fixture 一致性', () => {
  it('原始字节 SHA-256 与硬编码常量一致（双端镜像）', () => {
    const bytes = new TextEncoder().encode(budgetEnforcerRaw)
    const actual = createHash('sha256').update(bytes).digest('hex')
    expect(actual).toBe(EXPECTED_FIXTURE_SHA256)
  })

  it('锚点与用例数量符合契约（20 条，不少于 18）', () => {
    expect(fixture.anchor_now_ms).toBe(ANCHOR_NOW_MS)
    expect(fixture.cases.length).toBeGreaterThanOrEqual(18)
  })
})

// =============================================================================
// 2. fixture 全量数据驱动（20 条）
// =============================================================================

describe('budgetEnforcer / checkTx 数据驱动', () => {
  it.each(fixture.cases.map((c) => [c.name, c] as [string, FixtureCase]))(
    'case[%s] 全字段匹配',
    (_name, c) => {
      const actual = checkTx(
        toTx(c.incoming),
        c.budgets,
        c.existing.map(toTx),
        toRateTable(c),
        ANCHOR_NOW_MS,
      )
      assertResultMatches(`case[${c.name}]`, c.expect, actual)
    },
  )
})

// =============================================================================
// 3. periodBucket 边界（月 / 周 / 年 / custom / 有效期外 / 相邻周桶）
// =============================================================================

describe('budgetEnforcer / periodBucket 分桶', () => {
  /** 2026 年预算有效期：2026-01-01 00:00 CST 至 2026-12-31 23:59:59.999 CST。 */
  const yearStart = 1_767_196_800_000
  const yearEnd = 1_798_732_799_999

  it('monthly：锚点 6 月 → [06-01, 07-01)', () => {
    expect(periodBucket('monthly', yearStart, yearEnd, ANCHOR_NOW_MS)).toEqual([
      1_780_243_200_000, 1_782_835_200_000,
    ])
  })

  it('yearly：锚点 2026 年 → [2026-01-01, 2027-01-01)', () => {
    expect(periodBucket('yearly', yearStart, yearEnd, ANCHOR_NOW_MS)).toEqual([
      1_767_196_800_000, 1_798_732_800_000,
    ])
  })

  it('weekly：epoch 取 startTs 所在 CST 日期零点（06-22 周一起算，周日仍在第 0 桶）', () => {
    const weeklyStart = 1_782_057_600_000 // 2026-06-22 00:00 CST
    expect(periodBucket('weekly', weeklyStart, yearEnd, ANCHOR_NOW_MS)).toEqual([
      1_782_057_600_000, 1_782_662_400_000,
    ])
  })

  it('weekly：下周一零点（上桶半开右端点）落入下一桶', () => {
    const weeklyStart = 1_782_057_600_000
    const nextMonday = 1_782_662_400_000 // 2026-06-29 00:00 CST
    expect(periodBucket('weekly', weeklyStart, yearEnd, nextMonday)).toEqual([
      1_782_662_400_000, 1_783_267_200_000,
    ])
  })

  it('custom：桶为 [startTs, endTs + 1)，endTs 当刻包含、之后一毫秒排除', () => {
    const start = 1_780_243_200_000 // 2026-06-01 00:00 CST
    const end = 1_781_020_800_000 // 2026-06-10 00:00 CST（含）
    expect(periodBucket('custom', start, end, end)).toEqual([start, end + 1])
    expect(periodBucket('custom', start, end, end + 1)).toBeNull()
  })

  it('有效期外 / 未知 scope 返回 null', () => {
    // monthly 预算有效期自 2026-06-01 起；5 月 31 日的流水不命中。
    expect(periodBucket('monthly', 1_780_243_200_000, yearEnd, 1_780_156_800_000)).toBeNull()
    expect(periodBucket('quarterly', yearStart, yearEnd, ANCHOR_NOW_MS)).toBeNull()
  })
})

// =============================================================================
// 4 / 5. 门面非支出短路与 evaluateBudget 三档边界
// =============================================================================

describe('budgetEnforcer / checkTx 与 evaluateBudget', () => {
  /** 2026 年餐饮月预算 1000.00 CNY，阈值 80 / 100。 */
  const budget: FinanceBudget = {
    id: 'b',
    schema_version: 2,
    scope: 'monthly',
    category: '餐饮',
    amount_minor: '1000.00',
    currency: 'CNY',
    start_ts: 1_767_196_800_000,
    end_ts: 1_798_732_799_999,
    warning_threshold_pct: 80,
    block_threshold_pct: 100,
    active: true,
    created_at: 1_767_196_800_000,
    updated_at: 1_767_196_800_000,
  }

  it('非 expense 流水直接返回 OK_EMPTY', () => {
    const income: BudgetTxLike = {
      id: 'tx-income',
      kind: 'income',
      amountMinor: '5000.00',
      category: '餐饮',
      currency: 'CNY',
      occurredAt: ANCHOR_NOW_MS,
    }
    expect(checkTx(income, [budget], [], null, ANCHOR_NOW_MS)).toBe(OK_EMPTY)
  })

  it('evaluateBudget 边界：79% OK / 恰好 80% WARNING / 恰好 100% BLOCK', () => {
    // 690.00 + 100.00 = 790.00 → 79% → OK，thresholdPct 填预警阈值 80。
    const ok = evaluateBudget(budget, 69000n, 10000n)
    expect(ok.level).toBe('OK')
    expect(ok.usedPct).toBe(79)
    expect(ok.thresholdPct).toBe(80)

    // 700.00 + 100.00 = 800.00 → 恰好 80% → WARNING。
    const warning = evaluateBudget(budget, 70000n, 10000n)
    expect(warning.level).toBe('WARNING')
    expect(warning.usedPct).toBe(80)
    expect(warning.thresholdPct).toBe(80)

    // 900.00 + 100.00 = 1000.00 → 恰好 100% → BLOCK，thresholdPct 填 100。
    const block = evaluateBudget(budget, 90000n, 10000n)
    expect(block.level).toBe('BLOCK')
    expect(block.usedPct).toBe(100)
    expect(block.thresholdPct).toBe(100)
  })
})

// =============================================================================
// 6. validateBudget（与 Android BudgetRecordTest 同口径 7 用例）
// =============================================================================

describe('types / validateBudget', () => {
  /** 构造一条合法预算，各用例按需改坏单个字段。 */
  function validBudget(): FinanceBudget {
    return {
      id: 'budget-0001',
      schema_version: 2,
      scope: 'monthly',
      category: '餐饮',
      amount_minor: '3000.00',
      currency: 'CNY',
      start_ts: 1_767_196_800_000,
      end_ts: 1_798_732_799_999,
      warning_threshold_pct: 80,
      block_threshold_pct: 100,
      active: true,
      created_at: 1_767_196_800_000,
      updated_at: 1_767_196_800_000,
    }
  }

  /** 取失败原因（用例前提：该入参必须校验失败）。 */
  function reason(p: FinanceBudget): string {
    const r = validateBudget(p)
    expect(r.ok).toBe(false)
    return (r as { ok: false; reason: string }).reason
  }

  it('合法记录通过（含 custom 且 endTs 等于 startTs 的等长一刻）', () => {
    expect(validateBudget(validBudget())).toEqual({ ok: true })
    expect(
      validateBudget({
        ...validBudget(),
        scope: 'custom',
        start_ts: 1_780_243_200_000,
        end_ts: 1_780_243_200_000,
      }),
    ).toEqual({ ok: true })
  })

  it('scope 非法被拒', () => {
    expect(reason({ ...validBudget(), scope: 'quarterly' as FinanceBudget['scope'] })).toBe(
      'scope 非法',
    )
  })

  it('金额非法被拒（0 / 三位小数 / 字母）', () => {
    expect(reason({ ...validBudget(), amount_minor: '0.00' })).toBe('amountMinor 非法')
    expect(reason({ ...validBudget(), amount_minor: '10.999' })).toBe('amountMinor 非法')
    expect(reason({ ...validBudget(), amount_minor: 'abc' })).toBe('amountMinor 非法')
  })

  it('预警阈值大于阻断阈值（倒挂）被拒', () => {
    expect(
      reason({ ...validBudget(), warning_threshold_pct: 120, block_threshold_pct: 100 }),
    ).toContain('阈值')
  })

  it('endTs 小于 startTs 被拒', () => {
    expect(
      reason({ ...validBudget(), start_ts: 1_780_243_200_000, end_ts: 1_780_156_800_000 }),
    ).toBe('endTs 必须为整数且大于等于 startTs')
  })

  it('id 缺失被拒', () => {
    expect(reason({ ...validBudget(), id: '' })).toBe('id 缺失')
  })

  it('startTs 非正与 category 超长被拒', () => {
    expect(reason({ ...validBudget(), start_ts: 0, end_ts: 0 })).toBe(
      'startTs 必须为正整数毫秒',
    )
    expect(reason({ ...validBudget(), category: '分'.repeat(21) })).toBe(
      'category 长度需在 1-20 字符',
    )
  })
})
