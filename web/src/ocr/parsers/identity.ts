// 身份证正反面 OCR 文本解析（与 Android IdentityOcrParser.kt 对齐）

export interface IdentityFrontOcrHint {
  name: string | null
  number: string | null
  address: string | null
}

export interface IdentityBackOcrHint {
  issuer: string | null
  validFrom: string | null
  expiresOn: string | null
}

const ID_NUMBER_REGEX = /\d{6}\s*\d{8}\s*\d{3}[\dXx]/
const ID_NUMBER_COMPACT = /\d{17}[\dXx]/
const ID_CHECK_WEIGHTS = [7, 9, 10, 5, 8, 4, 2, 1, 6, 3, 7, 9, 10, 5, 8, 4, 2]
const ID_CHECK_CHARS = '10X98765432'

export function parseIdentityFrontText(text: string): IdentityFrontOcrHint | null {
  if (!text.trim()) return null
  const lines = text.split(/\r?\n/).map((l) => l.trim()).filter(Boolean)
  const number = extractIdNumber(text)
  const name = extractName(lines)
  const address = extractAddress(text, lines)
  if (!number && !name && !address) return null
  return { name, number, address }
}

export function parseIdentityBackText(text: string): IdentityBackOcrHint | null {
  if (!text.trim()) return null
  const lines = text.split(/\r?\n/).map((l) => l.trim()).filter(Boolean)
  const issuer = extractIssuer(lines)
  const { from, to } = extractValidPeriod(text)
  if (!issuer && !from && !to) return null
  return { issuer, validFrom: from, expiresOn: to }
}

function isValidChineseId(id: string): boolean {
  if (id.length !== 18) return false
  if (!/^\d{17}[\dX]$/.test(id)) return false
  let sum = 0
  for (let i = 0; i < 17; i++) sum += Number(id[i]) * ID_CHECK_WEIGHTS[i]
  return id[17] === ID_CHECK_CHARS[sum % 11]
}

function extractIdNumber(text: string): string | null {
  const spaced = text.match(ID_NUMBER_REGEX)?.[0]?.replace(/\s+/g, '')
  const candidate = (spaced ?? text.match(ID_NUMBER_COMPACT)?.[0])?.toUpperCase()
  return candidate && isValidChineseId(candidate) ? candidate : null
}

function extractName(lines: string[]): string | null {
  for (const line of lines) {
    const idx = line.indexOf('姓名')
    if (idx >= 0) {
      const rest = line.slice(idx + 2).trim()
      const name = rest.split('性别')[0].trim().split(/\s+/)[0] ?? ''
      if (isPlausibleChineseName(name)) return name
    }
  }
  for (const line of lines) {
    if (line.includes('性别')) {
      const before = line.split('性别')[0].trim()
      if (isPlausibleChineseName(before)) return before
    }
  }
  return null
}

function isPlausibleChineseName(s: string): boolean {
  if (s.length < 2 || s.length > 8) return false
  const cjk = [...s].filter((c) => /\p{Script=Han}/u.test(c)).length
  return cjk >= 2 && cjk * 2 >= s.length
}

function extractAddress(text: string, lines: string[]): string | null {
  const label = text.match(/住址/)
  if (label && label.index != null) {
    const after = text.slice(label.index + 2).split(/\r?\n/)[0].trim()
    if (after.length >= 4) return after.slice(0, 80)
  }
  const fallback = lines.find(
    (l) => l.length >= 8 && (l.match(/\d/g)?.length ?? 0) <= 4 && !ID_NUMBER_COMPACT.test(l),
  )
  return fallback ? fallback.slice(0, 80) : null
}

function extractIssuer(lines: string[]): string | null {
  for (const line of lines) {
    const idx = line.indexOf('签发机关')
    if (idx >= 0) {
      const rest = line.slice(idx + 4).trim()
      if (rest.length >= 4 && rest.length <= 40) return rest
    }
  }
  return null
}

const DATE_RANGE_REGEX =
  /(\d{4})\s*[.\-/年]\s*(\d{1,2})\s*[.\-/月]\s*(\d{1,2})\s*日?\s*[-—~至]\s*(\d{4})\s*[.\-/年]\s*(\d{1,2})\s*[.\-/月]\s*(\d{1,2})\s*日?/

function formatYmd(year: number, month: number, day: number): string {
  return `${String(year).padStart(4, '0')}-${String(month).padStart(2, '0')}-${String(day).padStart(2, '0')}`
}

function extractValidPeriod(text: string): { from: string | null; to: string | null } {
  if (text.includes('长期')) {
    const range = text.match(DATE_RANGE_REGEX)
    if (range) {
      return {
        from: formatYmd(Number(range[1]), Number(range[2]), Number(range[3])),
        to: '长期',
      }
    }
  }
  const match = text.match(DATE_RANGE_REGEX)
  if (match) {
    return {
      from: formatYmd(Number(match[1]), Number(match[2]), Number(match[3])),
      to: formatYmd(Number(match[4]), Number(match[5]), Number(match[6])),
    }
  }
  for (const line of text.split(/\r?\n/)) {
    if (!/有效期限|有效期/.test(line)) continue
    const dates = [...line.matchAll(/\d{4}[.\-/年]\d{1,2}[.\-/月]\d{1,2}/g)].map((m) =>
      normalizeDateToken(m[0]),
    )
    if (dates.length >= 2) return { from: dates[0], to: dates[1] }
    if (dates.length === 1 && line.includes('长期')) return { from: dates[0], to: '长期' }
  }
  return { from: null, to: null }
}

function normalizeDateToken(raw: string): string {
  const parts = [...raw.matchAll(/\d+/g)].map((m) => Number(m[0]))
  if (parts.length < 3) return raw
  return formatYmd(parts[0], parts[1], parts[2])
}
