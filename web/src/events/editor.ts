// 阶段 4b — EventEditorDialog 纯函数层（tasks.md Task 8 / TR-8.1）。
//
// 设计目标（关键纪律：不引入新依赖；零知识；类型严格）：
//   1. **测试可达**：UI 组件 EventEditorDialog.vue 仅做表层绑定；表单状态机、
//      校验、生成 EventRule 全部抽到本文件纯函数。Vitest + node 环境即可单跑，
//      无需 @vue/test-utils/jsdom 依赖。
//   2. **与既有 types 一致**：本文件引用的字段名 / 枚举与 web/src/events/types.ts
//      逐字段对齐；编辑器即类型映射器。
//   3. **零知识纪律**：校验报错/生成 EventRule 不写日志；测试断言使用脱敏数据。
//
// 暴露：
//   - EditorForm 草稿类型
//   - REMINDER_OPTIONS 提醒档位常量
//   - COLOR_OPTIONS 8 色板
//   - FREQ_OPTIONS / WEEKDAY_OPTIONS / END_KIND_OPTIONS UI 选项
//   - createBlankForm() 新建草稿
//   - formFromRule(rule) 编辑模式回填
//   - validateForm(form) → string[] 校验错误列表
//   - ruleFromForm(form, id) → EventRule 生成明文 rule

import type {
  EventRule,
  Frequency,
  Weekday,
  EventColor,
  RRule,
  RRuleEnd,
} from './types'

/** 转发 Frequency / Weekday 类型别名（types.ts 内导出；编辑器重导出方便组件 import 一处）。 */
export type { Frequency, Weekday } from './types'

// =============================================================================
// UI 选项常量（与 spec FR-1 / FR-2 字段表一一对应）
// =============================================================================

/** 提醒档位（分钟；0=准时；1440=1 天前）。 */
export const REMINDER_OPTIONS: { label: string; value: number }[] = [
  { label: '准时（0 分钟）', value: 0 },
  { label: '提前 5 分钟', value: 5 },
  { label: '提前 15 分钟', value: 15 },
  { label: '提前 30 分钟', value: 30 },
  { label: '提前 1 小时', value: 60 },
  { label: '提前 1 天', value: 1440 },
]

/** 8 色板（spec FR-1）。 */
export const COLOR_OPTIONS: { label: string; value: EventColor; hex: string }[] = [
  { label: '蓝', value: 'blue', hex: '#1a66ff' },
  { label: '绿', value: 'green', hex: '#18a058' },
  { label: '红', value: 'red', hex: '#d03050' },
  { label: '琥珀', value: 'amber', hex: '#f0a020' },
  { label: '紫罗兰', value: 'violet', hex: '#7c3aed' },
  { label: '粉', value: 'pink', hex: '#ec4899' },
  { label: '青', value: 'cyan', hex: '#0ea5e9' },
  { label: '石板', value: 'slate', hex: '#64748b' },
]

/** RRULE 频率选项（spec FR-2 B 档 4 档）。 */
export const FREQ_OPTIONS: { label: string; value: Frequency }[] = [
  { label: '每日', value: 'DAILY' },
  { label: '每周', value: 'WEEKLY' },
  { label: '每月', value: 'MONTHLY' },
  { label: '每年', value: 'YEARLY' },
]

/** ISO weekday（周一开头）。 */
export const WEEKDAY_OPTIONS: { label: string; value: Weekday }[] = [
  { label: '周一', value: 'MO' },
  { label: '周二', value: 'TU' },
  { label: '周三', value: 'WE' },
  { label: '周四', value: 'TH' },
  { label: '周五', value: 'FR' },
  { label: '周六', value: 'SA' },
  { label: '周日', value: 'SU' },
]

/** 结束三选一（spec FR-2）。 */
export type EndKind = 'never' | 'date' | 'count'
export const END_KIND_OPTIONS: { label: string; value: EndKind }[] = [
  { label: '永不结束', value: 'never' },
  { label: '截止日期', value: 'date' },
  { label: '限定次数', value: 'count' },
]

// =============================================================================
// 草稿形态（与 EventRule 解耦，便于编辑期 partial 状态）
// =============================================================================

/**
 * 编辑器草稿；字段语义：
 *   - title 必填；trim 后非空
 *   - start_ts / end_ts Unix 毫秒
 *   - all_day 全天（end_ts 可等于 start_ts；含结束日 00:00 但 UI 取 23:59）
 *   - location_text / note 可空字符串；空字符串落规则前归为 null
 *   - color / reminders / exdates 与 EventRule 一致
 *   - rrule 部分为 null 时表单的 freq='NONE'（扩展 Frequency；ruleFromForm
 *     时 'NONE' 映射回 rrule=null）
 *   - byweekday 多选项启用条件：freq==='WEEKLY'
 *   - endUntil 字符串 'YYYY-MM-DD' 仅 endKind==='date' 生效
 *   - endCount 数字 ≥ 1 仅 endKind==='count' 生效
 *
 * 频率在 UI 层加 'NONE' 选项；spec 仅 4 档，但编辑器需要"不重复"入口。
 */
export interface EditorForm {
  title: string
  start_ts: number
  end_ts: number
  all_day: boolean
  location_text: string
  note: string
  color: EventColor
  reminders: number[]
  freq: 'NONE' | Frequency
  interval: number
  byweekday: Weekday[]
  endKind: EndKind
  endUntil: string // 'YYYY-MM-DD'
  endCount: number
  exdates: string[] // 'YYYY-MM-DD'
}

// =============================================================================
// 工厂 / 转换
// =============================================================================

/** 新建空白草稿（默认 1 小时事件、当前时刻起 1 小时、提醒 0、单一蓝色）。 */
export function createBlankForm(now: number = Date.now()): EditorForm {
  const start = now
  return {
    title: '',
    start_ts: start,
    end_ts: start + 3_600_000, // 默认 1 小时
    all_day: false,
    location_text: '',
    note: '',
    color: 'blue',
    reminders: [0],
    freq: 'NONE',
    interval: 1,
    byweekday: [],
    endKind: 'never',
    endUntil: '',
    endCount: 10,
    exdates: [],
  }
}

/**
 * 由 EventRule 回填草稿（编辑模式入口）。
 * 不修改传入 rule；返回新对象。
 */
export function formFromRule(rule: EventRule): EditorForm {
  const form: EditorForm = {
    title: rule.title,
    start_ts: rule.start_ts,
    end_ts: rule.end_ts,
    all_day: rule.all_day,
    location_text: rule.location_text ?? '',
    note: rule.note ?? '',
    color: rule.color,
    // rule.reminders 现为 number[]（与 EventRule.reminders 同型）；旧 {offset_minutes}
// 拍平逻辑已不需要（types.ts 调整）。
    reminders: [...rule.reminders],
    freq: rule.rrule ? rule.rrule.freq : 'NONE',
    interval: rule.rrule?.interval ?? 1,
    byweekday: rule.rrule?.byweekday ? [...rule.rrule.byweekday] : [],
    endKind: 'never',
    endUntil: '',
    endCount: 10,
    exdates: [...rule.exdates],
  }
  if (rule.rrule?.end.kind === 'date') {
    form.endKind = 'date'
    form.endUntil = rule.rrule.end.until
  } else if (rule.rrule?.end.kind === 'count') {
    form.endKind = 'count'
    form.endCount = rule.rrule.end.count
  } else if (rule.rrule?.end.kind === 'never') {
    form.endKind = 'never'
  }
  return form
}

// =============================================================================
// 校验
// =============================================================================

/**
 * 校验草稿：返回错误列表（空 = 通过）。
 * 规则：
 *   1) title 非空（trim 后）
 *   2) start_ts > 0 / end_ts > 0
 *   3) start_ts ≤ end_ts
 *   4) reminders ≤ 3 个
 *   5) reminders 元素 ∈ REMINDER_OPTIONS.value 集合
 *   6) interval ≥ 1（仅 freq≠'NONE'）
 *   7) MONTHLY 时 byweekday 至多 1 元素（spec FR-2 MONTHLY 仅支持单 weekday）
 *   8) endKind='date' 时 endUntil 形如 YYYY-MM-DD 非空
 *   9) endKind='count' 时 endCount ≥ 1
 */
export function validateForm(form: EditorForm): string[] {
  const errors: string[] = []
  if (!form.title.trim()) errors.push('标题不能为空')
  if (!form.start_ts || form.start_ts <= 0) errors.push('开始时刻不合法')
  if (!form.end_ts || form.end_ts <= 0) errors.push('结束时刻不合法')
  if (form.start_ts > form.end_ts) errors.push('结束必须 ≥ 开始')
  if (form.reminders.length > 3) errors.push('提醒最多 3 个')
  const allowedReminders = new Set(REMINDER_OPTIONS.map((o) => o.value))
  for (const r of form.reminders) {
    if (!allowedReminders.has(r)) errors.push(`提醒档位非法: ${r}`)
  }
  if (form.freq !== 'NONE') {
    if (form.interval < 1) errors.push('间隔 ≥ 1')
    if (form.freq === 'MONTHLY' && form.byweekday.length > 1) {
      errors.push('MONTHLY 仅支持单 weekday')
    }
  }
  if (form.endKind === 'date') {
    if (!/^\d{4}-\d{2}-\d{2}$/.test(form.endUntil)) {
      errors.push('截止日期需为 YYYY-MM-DD')
    }
  } else if (form.endKind === 'count') {
    if (!Number.isFinite(form.endCount) || form.endCount < 1) {
      errors.push('次数 ≥ 1')
    }
  }
  // exdates 不在校验范围内（编辑器按添加按钮逐条添加；schema 兜底）
  return errors
}

// =============================================================================
// 生成 EventRule
// =============================================================================

/**
 * 由草稿生成完整 EventRule（保存前最后一步）。
 *   - id 由调用方提供（UUID v4；store 内 upsert 强约束）
 *   - 调用方应先调 validateForm（错误列表非空时应拒绝保存）
 *   - location_text/note 空字符串归为 null（节省密文体积；与 4a save 同口径）
 *   - tz_mode 本期固定 'local'（spec FR-1 / FR-2 字段保留）
 *
 * 注意：interval/endCount 类型为 number（number|null 在生成规则前应用 ?? 兜回），
 * 由 naive-ui n-input-number 输入可能导致 null 值——调用方需保证。
 */
export function ruleFromForm(form: EditorForm, id: string): EventRule {
  const interval = Math.max(1, Math.floor(form.interval || 1))
  const endCount = Math.max(1, Math.floor(form.endCount || 1))
  const location = form.location_text.trim() ? form.location_text.trim() : null
  const note = form.note.trim() ? form.note.trim() : null
  const rrule: RRule | null =
    form.freq === 'NONE'
      ? null
      : {
          freq: form.freq,
          interval,
          byweekday:
            form.freq === 'WEEKLY' || form.freq === 'MONTHLY'
              ? form.byweekday
              : undefined,
          end: buildRRuleEnd(form, endCount),
        }
  return {
    id,
    title: form.title.trim(),
    start_ts: form.start_ts,
    end_ts: form.end_ts,
    all_day: form.all_day,
    tz_mode: 'local',
    location_text: location,
    note,
    color: form.color,
    // form.reminders 与 EventRule.reminders 同型（number[]）；表单收 {offset_minutes}
// 对象拍平为 number 在表单层完成（EditorForm.reminders 即 number[]）。
    reminders: [...form.reminders],
    rrule,
    exdates: [...form.exdates],
  }
}

/** 由 form.endKind 构造 RRuleEnd。 */
function buildRRuleEnd(form: EditorForm, endCount: number): RRuleEnd {
  if (form.endKind === 'never') return { kind: 'never' }
  if (form.endKind === 'date') {
    return { kind: 'date', until: form.endUntil }
  }
  return { kind: 'count', count: endCount }
}

// =============================================================================
// 简易 weekday 工具（编辑器按 start_ts 自动为 MONTHLY 单 weekday 设值时用）
// =============================================================================

/**
 * 由本地时刻推断 weekday（ISO：0=Mon..6=Sun → 'MO'..'SU'）。
 * 算法与 expand.ts isoWeekdayOf 一致；此处独立实现避免引入跨层依赖，便于
 * 测试纯函数直接调（不需挂 Date 全局 mock）。
 */
export function weekdayOfTs(ts: number, tzOffsetMin: number): Weekday {
  const arr: Weekday[] = ['MO', 'TU', 'WE', 'TH', 'FR', 'SA', 'SU']
  const iso = ((new Date(ts + tzOffsetMin * 60_000).getUTCDay() + 6) % 7)
  return arr[iso]
}

/** 来自表单的"display 模式"构造显示选项。MONTHLY 单 weekday 仅取数组首项。 */
export function effectiveByweekday(form: EditorForm): Weekday[] {
  if (form.freq === 'WEEKLY') return form.byweekday
  if (form.freq === 'MONTHLY' && form.byweekday.length > 0) {
    return [form.byweekday[0]] // spec FR-2 单 weekday
  }
  return []
}

// =============================================================================
// 占位：当前时间（避免测试中 Date.now() 抖动）
// =============================================================================

/** 工具方法：把 Unix ms 转 'YYYY-MM-DD' 本地日历日（编辑器 endUntil 输入）。 */
export function tsToLocalDayKey(ts: number, tzOffsetMin: number): string {
  return new Date(ts + tzOffsetMin * 60_000).toISOString().slice(0, 10)
}
