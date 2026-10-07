// ============================================================================
// 物品模块 —— 明文 DTO 类型（stage5-items / FR-1）
// ============================================================================
//
// 与 docs/module-schemas.md 第 9 章字段命名对齐；仅类型定义，无运行时逻辑。
// 明文仅存浏览器内存，不经 localStorage / IndexedDB 持久化。
// ============================================================================

/** 内置一级分类（6 选 1） */
export type ItemCategory =
  | 'electronics'
  | 'furniture'
  | 'apparel'
  | 'tools'
  | 'books'
  | 'other'

/** 物品 records 明文形态（module=item, type=item） */
export interface Item {
  id: string
  name: string
  category: ItemCategory
  tags: string[]
  brand?: string
  model?: string
  serial_no?: string
  purchase_date: number
  purchase_price_cents: number
  currency: string
  warranty_duration_days: number
  warranty_until_ts: number
  receipt_url?: string
  note?: string
  location_text?: string
  created_ts: number
  updated_ts: number
}

/** nextItemTrigger 所需最小字段集 */
export interface ItemTriggerLike {
  warranty_duration_days: number
  warranty_until_ts: number
}

/** records 表 module / type 双键（与 4b event 同型） */
export const ITEM_MODULE = 'item'
export const ITEM_TYPE = 'item'

/** 保修提醒日志（本地 UI；不上行 records） */
export interface ItemReminderLog {
  id: number
  item_id: string
  occurrence_ts: number
  kind: 'warranty_expiring' | 'alarm_killed' | 'notification_denied' | 'exact_denied'
  created_ts: number
}
