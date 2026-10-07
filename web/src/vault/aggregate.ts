// 阶段 P3 — 解锁后本地聚合与全局搜索（纯函数；明文不出机、不上行）。
import type { Occurrence } from '../events/expand'
import { localDayKey } from '../events/expand'
import { nextTrigger } from '../finance/nextCardFiring'
import type { FinanceCard, FinanceTx } from '../finance/types'
import { nextItemTrigger, MS_PER_DAY } from '../items/warranty'
import type { Item } from '../items/types'
import { expiryDays } from '../stores/vault'
import type { DecryptedRecord, IdentityData, LoginData, NoteData } from '../types/vault'

export { MS_PER_DAY }
export const SEARCH_DEBOUNCE_MS = 300
export const HOME_HORIZON_DAYS = 7

export type GlobalSearchModule = 'identity' | 'item' | 'finance_tx' | 'pass'

export interface GlobalSearchHit {
  module: GlobalSearchModule
  id: string
  title: string
  subtitle?: string
}

export interface GlobalSearchInput {
  identities: DecryptedRecord[]
  items: Item[]
  financeTxs: FinanceTx[]
  passRecords: DecryptedRecord[]
}

/** 全局搜索：仅匹配解密后的内存字段，不含完整卡号/PAN。 */
export function globalSearch(query: string, input: GlobalSearchInput): GlobalSearchHit[] {
  const q = query.trim().toLowerCase()
  if (!q) return []
  const hits: GlobalSearchHit[] = []

  for (const r of input.identities) {
    const d = r.data as IdentityData
    const hay = `${d.title}\n${d.name ?? ''}`.toLowerCase()
    if (hay.includes(q)) {
      hits.push({
        module: 'identity',
        id: r.id,
        title: d.title,
        subtitle: d.name || undefined,
      })
    }
  }

  for (const item of input.items) {
    const hay = `${item.name}\n${item.tags.join(' ')}`.toLowerCase()
    if (hay.includes(q)) {
      hits.push({ module: 'item', id: item.id, title: item.name })
    }
  }

  for (const tx of input.financeTxs) {
    const note = (tx.note ?? '').trim()
    if (!note) continue
    if (note.toLowerCase().includes(q)) {
      hits.push({
        module: 'finance_tx',
        id: tx.id,
        title: note.length > 48 ? `${note.slice(0, 48)}…` : note,
      })
    }
  }

  for (const r of input.passRecords) {
    const d = r.data as LoginData | NoteData
    const title = d.title ?? ''
    const extra =
      'username' in d && d.username
        ? d.username
        : 'body' in d && d.body
          ? d.body.slice(0, 40)
          : ''
    const hay = `${title}\n${extra}`.toLowerCase()
    if (hay.includes(q)) {
      hits.push({
        module: 'pass',
        id: r.id,
        title,
        subtitle: extra || undefined,
      })
    }
  }

  return hits
}

export interface IdentityExpiryRow {
  record: DecryptedRecord
  days: number
}

/** 未来 withinDays 天内到期的证件（含到期当天；不含已过期）。 */
export function identitiesExpiringWithinDays(
  identities: DecryptedRecord[],
  withinDays: number,
  now = new Date(),
): IdentityExpiryRow[] {
  return identities
    .map((r) => ({
      record: r,
      days: expiryDays((r.data as IdentityData).expires_on ?? '', now),
    }))
    .filter(
      (x) =>
        Boolean((x.record.data as IdentityData).expires_on)
        && x.days >= 0
        && x.days <= withinDays,
    )
    .sort((a, b) => a.days - b.days)
}

export interface ItemReminderRow {
  id: string
  name: string
  triggerMs: number
}

export function itemRemindersWithinDays(
  items: Item[],
  withinDays: number,
  nowMs: number,
): ItemReminderRow[] {
  const horizon = nowMs + withinDays * MS_PER_DAY
  const out: ItemReminderRow[] = []
  for (const item of items) {
    const triggerMs = nextItemTrigger(item, nowMs)
    if (triggerMs !== null && triggerMs <= horizon) {
      out.push({ id: item.id, name: item.name, triggerMs })
    }
  }
  out.sort((a, b) => a.triggerMs - b.triggerMs)
  return out
}

export interface FinanceCardReminderRow {
  id: string
  name: string
  triggerMs: number
}

export function financeCardRemindersWithinDays(
  cards: FinanceCard[],
  withinDays: number,
  nowMs: number,
): FinanceCardReminderRow[] {
  const horizon = nowMs + withinDays * MS_PER_DAY
  const out: FinanceCardReminderRow[] = []
  for (const c of cards) {
    const triggerMs = nextTrigger(
      {
        id: c.id,
        kind: c.kind,
        billingDay: c.billing_day ?? null,
        dueDay: c.due_day ?? null,
        archived: c.archived,
      },
      nowMs,
    )
    if (triggerMs !== null && triggerMs <= horizon) {
      out.push({ id: c.id, name: c.name, triggerMs })
    }
  }
  out.sort((a, b) => a.triggerMs - b.triggerMs)
  return out
}

export interface V2ReminderLike {
  kind: string
  id: string
  triggerMs: number
}

export function financeV2RemindersWithinDays(
  reminders: V2ReminderLike[],
  withinDays: number,
  nowMs: number,
): V2ReminderLike[] {
  const horizon = nowMs + withinDays * MS_PER_DAY
  return reminders
    .filter((r) => r.triggerMs >= nowMs && r.triggerMs <= horizon)
    .sort((a, b) => a.triggerMs - b.triggerMs)
}

/** 筛选落在指定本地日的日程实例。 */
export function occurrencesOnLocalDay(
  occurrences: Occurrence[],
  dayKey: string,
): Occurrence[] {
  return occurrences
    .filter((o) => localDayKey(o.start_ts) === dayKey)
    .sort((a, b) => a.start_ts - b.start_ts)
}
