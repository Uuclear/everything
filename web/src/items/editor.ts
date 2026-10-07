// 物品表单纯函数：校验与 Item 构造（与 Android ItemEditorScreen 字段对齐）

import { isValidReceiptUrl, normalizeTags, warrantyUntilTs } from './warranty'
import type { Item, ItemCategory } from './types'

export const CATEGORY_OPTIONS: { label: string; value: ItemCategory }[] = [
  { label: '电子设备', value: 'electronics' },
  { label: '家具', value: 'furniture' },
  { label: '服饰', value: 'apparel' },
  { label: '工具', value: 'tools' },
  { label: '书籍', value: 'books' },
  { label: '其他', value: 'other' },
]

export interface ItemFormDraft {
  id: string
  name: string
  category: ItemCategory
  tagsInput: string
  brand: string
  model: string
  serial_no: string
  purchase_date: number
  purchase_price_yuan: string
  currency: string
  warranty_duration_days: number
  receipt_url: string
  note: string
  location_text: string
}

/** 新建空白草稿 */
export function createBlankItemForm(now = Date.now()): ItemFormDraft {
  return {
    id: crypto.randomUUID(),
    name: '',
    category: 'other',
    tagsInput: '',
    brand: '',
    model: '',
    serial_no: '',
    purchase_date: now,
    purchase_price_yuan: '0',
    currency: 'CNY',
    warranty_duration_days: 0,
    receipt_url: '',
    note: '',
    location_text: '',
  }
}

/** 从已有 Item 还原草稿 */
export function formFromItem(item: Item): ItemFormDraft {
  return {
    id: item.id,
    name: item.name,
    category: item.category,
    tagsInput: item.tags.join(', '),
    brand: item.brand ?? '',
    model: item.model ?? '',
    serial_no: item.serial_no ?? '',
    purchase_date: item.purchase_date,
    purchase_price_yuan: String(item.purchase_price_cents / 100),
    currency: item.currency || 'CNY',
    warranty_duration_days: item.warranty_duration_days,
    receipt_url: item.receipt_url ?? '',
    note: item.note ?? '',
    location_text: item.location_text ?? '',
  }
}

export function validateItemForm(form: ItemFormDraft): string | null {
  if (!form.name.trim()) return '请填写名称'
  if (!form.category) return '请选择分类'
  const url = form.receipt_url.trim()
  if (url && !isValidReceiptUrl(url)) return '发票链接须为 https:// 开头'
  const cents = parsePriceCents(form.purchase_price_yuan)
  if (cents < 0) return '价格无效'
  if (form.warranty_duration_days < 0) return '保修天数不能为负'
  return null
}

function parsePriceCents(yuan: string): number {
  const n = Number.parseFloat(yuan.trim())
  if (!Number.isFinite(n) || n < 0) return -1
  return Math.round(n * 100)
}

/** 草稿 → Item（含 warranty_until_ts 自动计算） */
export function itemFromForm(form: ItemFormDraft, existing?: Item): Item {
  const now = Date.now()
  const tags = normalizeTags(
    form.tagsInput
      .split(/[,，]/)
      .map((s) => s.trim())
      .filter(Boolean),
  )
  const purchase_price_cents = parsePriceCents(form.purchase_price_yuan)
  const warranty_duration_days = Math.max(0, Math.trunc(form.warranty_duration_days))
  const purchase_date = form.purchase_date
  const warranty_until_ts = warrantyUntilTs(purchase_date, warranty_duration_days)

  return {
    id: form.id,
    name: form.name.trim(),
    category: form.category,
    tags,
    brand: form.brand.trim() || undefined,
    model: form.model.trim() || undefined,
    serial_no: form.serial_no.trim() || undefined,
    purchase_date,
    purchase_price_cents,
    currency: form.currency.trim() || 'CNY',
    warranty_duration_days,
    warranty_until_ts,
    receipt_url: form.receipt_url.trim() || undefined,
    note: form.note.trim() || undefined,
    location_text: form.location_text.trim() || undefined,
    created_ts: existing?.created_ts ?? now,
    updated_ts: now,
  }
}

/** 只读展示：保修截止 */
export function warrantyUntilLabel(ts: number): string {
  if (!Number.isFinite(ts)) return '—'
  return new Date(ts).toLocaleDateString()
}
