import { describe, expect, it } from 'vitest'
import { parseReceiptText } from '../parsers/receipt'

function midnight(year: number, month: number, day: number): number {
  const d = new Date(year, month - 1, day)
  d.setHours(0, 0, 0, 0)
  return d.getTime()
}

describe('parseReceiptText', () => {
  it('typical chinese receipt', () => {
    const hint = parseReceiptText(`好味道快餐店\n2026-09-24\n合计 35.00`)
    expect(hint?.amountMinor).toBe(3500)
    expect(hint?.ts).toBe(midnight(2026, 9, 24))
    expect(hint?.merchant).toBe('好味道快餐店')
  })

  it('english total', () => {
    expect(parseReceiptText('COFFEE SHOP\nTOTAL ¥42.50')?.amountMinor).toBe(4250)
  })

  it('grouped thousands', () => {
    expect(parseReceiptText('实付 ¥1,234.56')?.amountMinor).toBe(123456)
  })

  it('amount only', () => {
    const hint = parseReceiptText('合计 66.00')
    expect(hint?.amountMinor).toBe(6600)
    expect(hint?.ts).toBeNull()
  })

  it('quantity lines null', () => {
    expect(parseReceiptText('可乐 2 × 4.50\n汉堡 1 × 18.00')).toBeNull()
  })

  it('date formats', () => {
    expect(parseReceiptText('店\n2026年9月24日\n合计 10.00')?.ts).toBe(midnight(2026, 9, 24))
    expect(parseReceiptText('店\n24.09.2026\n合计 10.00')?.ts).toBe(midnight(2026, 9, 24))
  })

  it('keyword line wins', () => {
    expect(parseReceiptText('精品超市\n原价 999.00\n实付 70.00')?.amountMinor).toBe(7000)
  })

  it('unreasonable year ignored', () => {
    const hint = parseReceiptText('小吃店\n1999-09-24\n合计 20.00')
    expect(hint?.amountMinor).toBe(2000)
    expect(hint?.ts).toBeNull()
  })

  it('blank', () => {
    expect(parseReceiptText('')).toBeNull()
  })

  it('merchant english', () => {
    expect(parseReceiptText('BOOK STORE\nAMOUNT RMB 88.00')?.amountMinor).toBe(8800)
  })
})
