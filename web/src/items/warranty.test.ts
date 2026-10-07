// ============================================================================
// 物品保修纯函数 —— Vitest（stage5-items / T2 / TR-2.4）
// ============================================================================
//
// 从 __fixtures__/cases.json 加载 warrantyCases，逐用例断言与 expected 一致。
// ============================================================================

import { describe, it, expect } from 'vitest'
import fixture from './__fixtures__/cases.json'
import {
  warrantyUntilTs,
  nextItemTrigger,
  normalizeTags,
  isValidReceiptUrl,
} from './warranty'
import type { ItemTriggerLike } from './types'

type WarrantyFn = 'warrantyUntilTs' | 'nextItemTrigger' | 'normalizeTags' | 'isValidReceiptUrl'

interface WarrantyFixtureCase {
  name: string
  fn: WarrantyFn
  input: Record<string, unknown>
  expected: unknown
}

const warrantyCases = (fixture as { warrantyCases: WarrantyFixtureCase[] }).warrantyCases

function runWarrantyCase(c: WarrantyFixtureCase): unknown {
  switch (c.fn) {
    case 'warrantyUntilTs': {
      const { purchaseDateTs, durationDays } = c.input as {
        purchaseDateTs: number
        durationDays: number
      }
      return warrantyUntilTs(purchaseDateTs, durationDays)
    }
    case 'nextItemTrigger': {
      const { item, now } = c.input as { item: ItemTriggerLike; now: number }
      return nextItemTrigger(item, now)
    }
    case 'normalizeTags': {
      const { rawTags } = c.input as { rawTags: string[] }
      return normalizeTags(rawTags)
    }
    case 'isValidReceiptUrl': {
      const { url } = c.input as { url: string }
      return isValidReceiptUrl(url)
    }
    default:
      throw new Error(`unknown fn: ${c.fn}`)
  }
}

describe('warranty pure functions (fixture)', () => {
  it('warrantyCases 至少 12 条', () => {
    expect(warrantyCases.length).toBeGreaterThanOrEqual(12)
  })

  for (const c of warrantyCases) {
    it(c.name, () => {
      expect(runWarrantyCase(c)).toEqual(c.expected)
    })
  }
})

describe('isValidReceiptUrl — 补充边界', () => {
  it('空串拒绝', () => {
    expect(isValidReceiptUrl('')).toBe(false)
  })

  it('非 URL 形态拒绝', () => {
    expect(isValidReceiptUrl('https://')).toBe(false)
  })
})
