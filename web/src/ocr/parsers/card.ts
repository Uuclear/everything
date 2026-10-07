// 银行卡 OCR 文本解析（与 Android CardOcrParser.kt 行为对齐）
import { extractLast4, luhnValidate } from '../../finance/luhn'

export interface CardOcrHint {
  pan: string | null
  last4: string | null
  expiryMonth: number | null
  expiryYear: number | null
  holder: string | null
}

const EXPIRY_LABEL_REGEX = /(?:VALID\s*THRU|GOOD\s*THRU|有效期|有效期至|EXP(?:IRY)?)/i
const EXPIRY_DATE_REGEX = /(\d{1,2})\s*[/.年-]\s*(\d{2,4})/
const HOLDER_LABEL_REGEX = /(?:持卡人|CARD\s*HOLDER)/i

/** 从 OCR 全文解析银行卡要素。 */
export function parseCardText(text: string): CardOcrHint | null {
  if (!text.trim()) return null
  const pan = extractLuhnPan(text)
  const last4 = pan ? extractLast4(pan) : null
  const { month, year } = extractExpiry(text)
  const holder = extractHolder(
    text
      .split(/\r?\n/)
      .map((l) => l.trim())
      .filter(Boolean),
  )
  if (!pan && !holder) return null
  return { pan, last4, expiryMonth: month, expiryYear: year, holder }
}

const PAN_GROUPED_REGEX = /(?:\d{4}[\s-]){2,4}\d{1,4}/g
const PAN_RUN_REGEX = /\d{13,19}/g

function extractLuhnPan(text: string): string | null {
  let best: string | null = null
  for (const line of text.split(/\r?\n/)) {
    const trimmed = line.trim()
    for (const match of trimmed.matchAll(PAN_GROUPED_REGEX)) {
      best = considerPanCandidate(match[0], best)
    }
    for (const match of trimmed.matchAll(PAN_RUN_REGEX)) {
      best = considerPanCandidate(match[0], best)
    }
  }
  return best
}

function considerPanCandidate(raw: string, currentBest: string | null): string | null {
  const normalized = raw.replace(/[\s-]/g, '')
  if (normalized.length < 13 || normalized.length > 19 || !luhnValidate(normalized)) return currentBest
  if (!currentBest || normalized.length > currentBest.length) return normalized
  return currentBest
}

function extractExpiry(text: string): { month: number | null; year: number | null } {
  const lines = text.split(/\r?\n/).map((l) => l.trim()).filter(Boolean)
  const prioritized = [...lines.filter((l) => EXPIRY_LABEL_REGEX.test(l)), ...lines]
  for (const line of prioritized) {
    const match = line.match(EXPIRY_DATE_REGEX)
    if (!match) continue
    const month = Number(match[1])
    if (month < 1 || month > 12) continue
    const rawYear = match[2]
    const year = rawYear.length === 2 ? 2000 + Number(rawYear) : Number(rawYear)
    if (year >= 2000 && year <= 2099) return { month, year }
  }
  return { month: null, year: null }
}

function extractHolder(lines: string[]): string | null {
  for (const line of lines) {
    const label = line.match(HOLDER_LABEL_REGEX)
    if (label && label.index != null) {
      const rest = line.slice(label.index + label[0].length).trim()
      if (rest.length >= 2 && rest.length <= 30 && (rest.match(/\d/g)?.length ?? 0) <= 2) return rest
    }
  }
  return null
}
