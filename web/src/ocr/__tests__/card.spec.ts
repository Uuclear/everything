import { describe, expect, it } from 'vitest'
import { parseCardText } from '../parsers/card'

const visaPan = '4111111111111111'

describe('parseCardText', () => {
  it('spaced pan with VALID THRU', () => {
    const hint = parseCardText(`CHINA MERCHANTS BANK\n4111 1111 1111 1111\nVALID THRU 12/28\nCARD HOLDER ZHANG SAN`)
    expect(hint?.pan).toBe(visaPan)
    expect(hint?.last4).toBe('1111')
    expect(hint?.expiryMonth).toBe(12)
    expect(hint?.expiryYear).toBe(2028)
    expect(hint?.holder).toBe('ZHANG SAN')
  })

  it('hyphen pan', () => {
    const hint = parseCardText('4111-1111-1111-1111\nEXP 09/26')
    expect(hint?.pan).toBe(visaPan)
    expect(hint?.expiryMonth).toBe(9)
  })

  it('chinese expiry label', () => {
    const hint = parseCardText('4111111111111111\n有效期至 03/2027')
    expect(hint?.expiryMonth).toBe(3)
    expect(hint?.expiryYear).toBe(2027)
  })

  it('holder chinese label', () => {
    const hint = parseCardText('4111111111111111\n持卡人 李明')
    expect(hint?.holder).toBe('李明')
  })

  it('invalid luhn null', () => {
    expect(parseCardText('1234567890123456')).toBeNull()
  })

  it('expiry only null', () => {
    expect(parseCardText('EXP 11/29')).toBeNull()
  })

  it('pan only', () => {
    expect(parseCardText('4111111111111111')?.pan).toBe(visaPan)
  })

  it('glued digits', () => {
    expect(parseCardText('NO:4111111111111111END')?.pan).toBe(visaPan)
  })

  it('two digit year', () => {
    const hint = parseCardText('4111111111111111 GOOD THRU 05/31')
    expect(hint?.expiryYear).toBe(2031)
  })

  it('mastercard sample', () => {
    const pan = '5555555555554444'
    const hint = parseCardText(`MASTERCARD\n${pan}\nVALID THRU 07/27`)
    expect(hint?.pan).toBe(pan)
    expect(hint?.last4).toBe('4444')
  })

  it('blank', () => {
    expect(parseCardText('')).toBeNull()
  })

  it('phone not pan', () => {
    expect(parseCardText('客服 400-123-4567')).toBeNull()
  })
})
