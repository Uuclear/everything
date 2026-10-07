// 小票 OCR 文本解析（与 Android finance/OcrParser.kt 口径一致）

export interface ReceiptHint {
  amountMinor: number | null
  ts: number | null
  merchant: string | null
}

const AMOUNT_KEYWORDS = ['合计', '总计', '金额', '应付', '实付', 'total', 'amount']
const CURRENCY_NUMBER_REGEX =
  /(?:[¥￥]|RMB)\s*(\d[\d,]*(?:\.\d{1,2})?)|(\d[\d,]*(?:\.\d{1,2})?)\s*[元块]/gi
const DECIMAL_REGEX = /(?<![\d,.])(\d{1,3}(?:,\d{3})+(?:\.\d{1,2})?|\d+\.\d{1,2})(?![\d,.])/g
const PLAIN_INTEGER_REGEX = /(?<!\d)(\d{1,6})(?!\d)/g
const DATE_YEAR_FIRST_REGEX = /(\d{4})\s*[-/.年]\s*(\d{1,2})\s*[-/.月]\s*(\d{1,2})\s*日?/g
const DATE_YEAR_LAST_REGEX = /(\d{1,2})\s*[/.]\s*(\d{1,2})\s*[/.]\s*(\d{4})/g
const MERCHANT_NOISE = ['欢迎光临', '谢谢惠顾', '欢迎', '惠顾', '小票', '收银', '凭条']

export function parseReceiptText(text: string): ReceiptHint | null {
  if (!text.trim()) return null
  const lines = text.split(/\r?\n/).map((l) => l.trim()).filter(Boolean)
  const amountMinor = extractAmount(lines)
  const ts = extractDate(text)
  const merchant = extractMerchant(lines)
  if (amountMinor == null && ts == null && !merchant) return null
  return { amountMinor, ts, merchant }
}

function extractAmount(lines: string[]): number | null {
  for (const line of lines) {
    const lower = line.toLowerCase()
    if (AMOUNT_KEYWORDS.some((k) => lower.includes(k))) {
      const v = parseKeywordLineAmount(line)
      if (v != null) return v
    }
  }
  let max: number | null = null
  for (const line of lines) {
    if (isQuantityUnitLine(line)) continue
    for (const m of line.matchAll(DECIMAL_REGEX)) {
      const cents = tokenToCents(m[1])
      if (cents != null && (max == null || cents > max)) max = cents
    }
  }
  return max
}

function isQuantityUnitLine(line: string): boolean {
  if (/[×xX*]/.test(line) && /\d/.test(line)) return true
  return [...line.matchAll(DECIMAL_REGEX)].length >= 2
}

function parseKeywordLineAmount(line: string): number | null {
  const symbolAmounts: number[] = []
  for (const m of line.matchAll(CURRENCY_NUMBER_REGEX)) {
    const token = m[1] || m[2]
    const cents = tokenToCents(token)
    if (cents != null) symbolAmounts.push(cents)
  }
  if (symbolAmounts.length) return symbolAmounts[symbolAmounts.length - 1]
  const decimals = [...line.matchAll(DECIMAL_REGEX)]
    .map((m) => tokenToCents(m[1]))
    .filter((v): v is number => v != null)
  if (decimals.length) return Math.max(...decimals)
  const withoutDates = stripDateFragments(line)
  const ints = [...withoutDates.matchAll(PLAIN_INTEGER_REGEX)]
    .map((m) => tokenToCents(m[1]))
    .filter((v): v is number => v != null)
  return ints.length ? Math.max(...ints) : null
}

function tokenToCents(token: string): number | null {
  if (!token) return null
  if (token.includes(',') && !/^\d{1,3}(,\d{3})+(\.\d{1,2})?$/.test(token)) return null
  const normalized = token.replace(/,/g, '')
  if (!/^\d+(\.\d{1,2})?$/.test(normalized)) return null
  const [whole, frac = ''] = normalized.split('.')
  if (whole === '0' && !frac) return null
  const cents = Number(whole) * 100 + Number((frac + '00').slice(0, 2))
  if (!Number.isFinite(cents) || cents <= 0) return null
  return cents
}

function stripDateFragments(s: string): string {
  return s
    .replace(DATE_YEAR_FIRST_REGEX, (m) => ' '.repeat(m.length))
    .replace(DATE_YEAR_LAST_REGEX, (m) => ' '.repeat(m.length))
}

function extractDate(text: string): number | null {
  for (const m of text.matchAll(DATE_YEAR_FIRST_REGEX)) {
    const ts = buildMidnightTs(Number(m[1]), Number(m[2]), Number(m[3]))
    if (ts != null) return ts
  }
  for (const m of text.matchAll(DATE_YEAR_LAST_REGEX)) {
    const first = Number(m[1])
    const second = Number(m[2])
    const year = Number(m[3])
    let month: number
    let day: number
    if (first > 12) {
      day = first
      month = second
    } else if (second > 12) {
      month = first
      day = second
    } else {
      day = first
      month = second
    }
    const ts = buildMidnightTs(year, month, day)
    if (ts != null) return ts
  }
  return null
}

function buildMidnightTs(year: number, month: number, day: number): number | null {
  const currentYear = new Date().getFullYear()
  if (year < 2000 || year > currentYear + 1) return null
  const d = new Date(year, month - 1, day)
  if (d.getFullYear() !== year || d.getMonth() !== month - 1 || d.getDate() !== day) return null
  d.setHours(0, 0, 0, 0)
  return d.getTime()
}

function extractMerchant(lines: string[]): string | null {
  for (const line of lines.slice(0, 10)) {
    if (line.length < 2 || line.length > 20) continue
    if (MERCHANT_NOISE.some((w) => line.includes(w))) continue
    if ((line.match(/\d/g)?.length ?? 0) > 2) continue
    const cjk = [...line].filter((c) => /\p{Script=Han}/u.test(c)).length
    if (cjk >= 2) return line
    const letters = [...line].filter((c) => /[A-Za-z]/.test(c)).length
    if (letters >= 2 && letters * 2 >= line.length) return line
  }
  return null
}
