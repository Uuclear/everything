import { describe, expect, it } from 'vitest'
import { parseIdentityBackText, parseIdentityFrontText } from '../parsers/identity'

const sampleId = '110101199003077432'

describe('parseIdentityFrontText', () => {
  it('typical layout', () => {
    const hint = parseIdentityFrontText(`姓名张三\n性别男\n住址北京市东城区某某街道1号\n${sampleId}`)
    expect(hint?.name).toBe('张三')
    expect(hint?.number).toBe(sampleId)
    expect(hint?.address).toContain('东城')
  })

  it('spaced id', () => {
    const hint = parseIdentityFrontText('姓名李四\n110101 19900307 7432')
    expect(hint?.number).toBe(sampleId)
    expect(hint?.name).toBe('李四')
  })

  it('invalid checksum', () => {
    expect(parseIdentityFrontText('110101199003077430')).toBeNull()
  })

  it('name before gender', () => {
    expect(parseIdentityFrontText(`王五 性别女\n${sampleId}`)?.name).toBe('王五')
  })

  it('address label', () => {
    const hint = parseIdentityFrontText(`住址广东省深圳市南山区路88号\n${sampleId}`)
    expect(hint?.address).toContain('深圳')
  })
})

describe('parseIdentityBackText', () => {
  it('issuer and range', () => {
    const hint = parseIdentityBackText('签发机关北京市公安局东城分局\n有效期限 2015.03.08-2025.03.08')
    expect(hint?.issuer).toContain('东城分局')
    expect(hint?.validFrom).toBe('2015-03-08')
    expect(hint?.expiresOn).toBe('2025-03-08')
  })

  it('long term', () => {
    const hint = parseIdentityBackText('签发机关上海市公安局\n有效期限 2020.01.01-长期')
    expect(hint?.expiresOn).toBe('长期')
  })

  it('issuer only', () => {
    expect(parseIdentityBackText('签发机关广州市公安局天河分局')?.issuer).toContain('广州')
  })

  it('blank', () => {
    expect(parseIdentityBackText('')).toBeNull()
  })
})
