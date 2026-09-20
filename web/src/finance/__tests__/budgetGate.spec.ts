// ============================================================================
// BudgetGate 纯函数单元测试（stage5-finance-v2 / B6 / FR-V2-F）
// ============================================================================
//
// 验证目标（7 用例）：
//   1. needsConfirmDialog：仅 BLOCK 为 true；
//   2. needsWarningToast：仅 WARNING 为 true；
//   3. confirmText 含百分比与 usedPct，不含小数点金额；
//   4. warningText 分类 'all' → “全部支出”统称；
//   5. warningText 具体分类 → 带分类名与百分比；
//   6. 全部文案零知识：不出现金额小数 / 日期 / 卡号等数字串；
//   7. OK 档文案函数仍可用且不报错（弹窗兜底 OK_EMPTY 场景）。
//
// 关联: web/src/finance/budgetGate.ts
// ============================================================================

import { describe, it, expect } from 'vitest'
import {
  needsConfirmDialog,
  needsWarningToast,
  confirmText,
  warningText,
} from '../budgetGate'
import type { BudgetCheckResult, BudgetLevel } from '../budgetEnforcer'

/** 构造判定结果夹具（金额 bigint 字段与文案无关，统一填 0n）。 */
function makeResult(
  level: BudgetLevel,
  usedPct: number,
  category = '餐饮',
): BudgetCheckResult {
  return {
    level,
    budgetId: level === 'OK' ? null : 'bud1',
    category,
    currency: level === 'OK' ? '' : 'CNY',
    spentMinor: 0n,
    incomingMinor: 0n,
    projectedMinor: 0n,
    limitMinor: 0n,
    usedPct,
    thresholdPct: level === 'BLOCK' ? 100 : 80,
  }
}

describe('budgetGate / needsConfirmDialog', () => {
  it('BLOCK → true，WARNING / OK → false', () => {
    expect(needsConfirmDialog(makeResult('BLOCK', 120))).toBe(true)
    expect(needsConfirmDialog(makeResult('WARNING', 85))).toBe(false)
    expect(needsConfirmDialog(makeResult('OK', 30))).toBe(false)
  })
})

describe('budgetGate / needsWarningToast', () => {
  it('WARNING → true，BLOCK / OK → false', () => {
    expect(needsWarningToast(makeResult('WARNING', 85))).toBe(true)
    expect(needsWarningToast(makeResult('BLOCK', 120))).toBe(false)
    expect(needsWarningToast(makeResult('OK', 30))).toBe(false)
  })
})

describe('budgetGate / confirmText', () => {
  it('含百分号与 usedPct 数字，不含小数点金额', () => {
    const text = confirmText(makeResult('BLOCK', 110))
    expect(text).toContain('%')
    expect(text).toContain('110')
    // 零知识：禁止金额形态（小数点数字）、日期戳等。
    expect(/\d+\.\d+/.test(text)).toBe(false)
  })
})

describe('budgetGate / warningText', () => {
  it("category='all' → 文案含“全部支出”与百分比，不含分类原值", () => {
    const text = warningText(makeResult('WARNING', 88, 'all'))
    expect(text).toContain('全部支出')
    expect(text).toContain('88%')
    expect(text).not.toContain('all')
  })

  it('具体分类 → 文案带分类名与百分比', () => {
    const text = warningText(makeResult('WARNING', 91, '交通'))
    expect(text).toContain('交通')
    expect(text).toContain('91%')
  })

  it('零知识：两类文案均不含小数点金额 / 长日期数字', () => {
    for (const r of [
      makeResult('WARNING', 85, '餐饮'),
      makeResult('WARNING', 85, 'all'),
      makeResult('BLOCK', 130, '餐饮'),
    ]) {
      for (const text of [confirmText(r), warningText(r)]) {
        expect(/\d+\.\d+/.test(text)).toBe(false)
        expect(/\d{5,}/.test(text)).toBe(false)
      }
    }
  })

  it('OK 档（空结果兜底）下文案函数不抛错', () => {
    const r = makeResult('OK', 0, 'all')
    expect(() => confirmText(r)).not.toThrow()
    expect(() => warningText(r)).not.toThrow()
    expect(warningText(r)).toContain('0%')
  })
})
