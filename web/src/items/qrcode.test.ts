// ============================================================================
// 二维码 payload 纯函数 —— Vitest（stage5-items / T2 / TR-2.4）
// ============================================================================

import { describe, it, expect } from 'vitest'
import fixture from './__fixtures__/cases.json'
import { qrPayloadForItem } from './qrcode'

interface QrFixtureCase {
  name: string
  input: { itemId: string }
  expected: string
}

const qrCases = (fixture as { qrCases: QrFixtureCase[] }).qrCases

describe('qrPayloadForItem (fixture)', () => {
  it('qrCases 至少 6 条', () => {
    expect(qrCases.length).toBeGreaterThanOrEqual(6)
  })

  for (const c of qrCases) {
    it(c.name, () => {
      const got = qrPayloadForItem(c.input.itemId)
      expect(got).toBe(c.expected)
      expect(got).not.toMatch(/name|serial|密文/)
    })
  }
})

describe('qrPayloadForItem — 零知识形态', () => {
  it('返回值与入参同一引用语义（字符串相等、无拼接）', () => {
    const id = 'f47ac10b-58cc-4372-a567-0e02b2c3d479'
    expect(qrPayloadForItem(id)).toEqual(id)
  })
})
