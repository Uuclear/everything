// ============================================================================
// 物品模块 —— 保修到期与提醒触发纯函数（stage5-items / T2 / TR-2.1）
// ============================================================================
//
// 与 Android Warranty.kt 字节级语义对齐；不依赖 DOM / localStorage / 网络。
// 时间戳均为 Unix 毫秒；天数 × MS_PER_DAY 推算（与 4b event 同款漂移接受度）。
// ============================================================================

import type { ItemTriggerLike } from './types'

/** 一天的毫秒数（与 spec FR-1 / Android Duration.ofDays 乘数一致） */
export const MS_PER_DAY = 86_400_000

/** 保修到期前提醒档位（天）—— FR-6 / ReminderScheduler 共用 */
export const WARRANTY_REMINDER_DAYS = [30, 7, 1] as const

const MAX_TAGS = 8
const MAX_TAG_LEN = 24

/**
 * 计算保修截止时刻：purchase_date + duration_days × 一天毫秒。
 * duration_days=0 时返回 purchaseDateTs（表示无保修期，仅基准日）。
 */
export function warrantyUntilTs(purchaseDateTs: number, durationDays: number): number {
  if (!Number.isFinite(purchaseDateTs) || !Number.isFinite(durationDays)) {
    return purchaseDateTs
  }
  const days = Math.max(0, Math.trunc(durationDays))
  return purchaseDateTs + days * MS_PER_DAY
}

/**
 * 计算物品下一次本地闹钟触发时刻。
 * 取 warranty_until_ts 前 30 / 7 / 1 天三档中仍大于 now 的最小值；
 * warranty_duration_days=0 或全部档位已过则返回 null。
 */
export function nextItemTrigger(item: ItemTriggerLike, now: number): number | null {
  if (!Number.isFinite(now)) return null
  if (item.warranty_duration_days <= 0) return null
  const until = item.warranty_until_ts
  if (!Number.isFinite(until) || until <= now) return null

  let best: number | null = null
  for (const d of WARRANTY_REMINDER_DAYS) {
    const trigger = until - d * MS_PER_DAY
    if (trigger > now && (best === null || trigger < best)) {
      best = trigger
    }
  }
  return best
}

/**
 * 二级标签归一化：去重（大小写不敏感）、转小写、单项截断 24 字符、最多 8 个。
 */
export function normalizeTags(rawTags: string[]): string[] {
  const seen = new Set<string>()
  const out: string[] = []
  for (const raw of rawTags) {
    const trimmed = raw.trim()
    if (!trimmed) continue
    const clipped = trimmed.length > MAX_TAG_LEN ? trimmed.slice(0, MAX_TAG_LEN) : trimmed
    const key = clipped.toLowerCase()
    if (seen.has(key)) continue
    seen.add(key)
    out.push(key)
    if (out.length >= MAX_TAGS) break
  }
  return out
}

/**
 * 发票外部链接校验：必须 https:// 前缀且为可解析 URL。
 */
export function isValidReceiptUrl(url: string): boolean {
  const s = url.trim()
  if (!s.startsWith('https://')) return false
  try {
    const u = new URL(s)
    return u.protocol === 'https:' && u.hostname.length > 0
  } catch {
    return false
  }
}
