// 阶段 4b — 日程/日历 跨端共享 TypeScript 类型（tasks.md Task 7 / TR-7.1）。
//
// 设计目标：
//   1. **跨端契约**：与 docs/module-schemas.md § 8.2 字段表逐字段一致；与
//      web/src/events/expand.ts、Android EventEntity.kt 同源。任何字段变更
//      必须三端同改 + 同步更新 module-schemas.md。
//   2. **零知识边界**：本类型是加密前 JSON 形状（即密文内载荷），服务端仅
//      看到 records 表元数据（id/module/type/version/timestamps/ciphertext）；
//      任何明文字段（含 title/note/location_text）均不出本机。
//   3. **snake_case 保留**：与 expand.ts、EventEntity、Android 端 Kotlin
//      数据类保持同名字段名（snake_case），便于跨端 fixture 哈希对齐。
//   4. **类型层提示**：`reminders` ≤ 3 由表单层校验；本类型不强制数组上限
//      以避免运行时数组越界写法被类型推断拒绝（库内部仍按 ≤3 假设）。
//
// 注意：本文件是 4b Web 端**唯一**权威类型定义；编辑视图（T8 EventEditorDialog）
// 与 calendar 视图均依赖此处的形状，不要在各视图组件内重新声明。

// =============================================================================
// 色板（spec FR-1 / module-schemas.md § 8.2）
// =============================================================================

/**
 * 8 色板预设（事件→实例继承，同一事件所有展开实例同色）。
 * 色值映射在编辑器（T8 EventEditorDialog）与视图层完成，本类型只承载枚举。
 */
export type EventColor =
  | 'blue'
  | 'green'
  | 'red'
  | 'amber'
  | 'violet'
  | 'pink'
  | 'cyan'
  | 'slate'

// =============================================================================
// 提醒（spec FR-1 / module-schemas.md § 8.2）
// =============================================================================

/**
 * 单条提醒（UI 表单层结构；密文载荷见下方 EventRule.reminders 注释）。
 * 档位：0 / 5 / 15 / 30 / 60 / 1440（0=开始时刻本身；1440=1 天前）。
 * 元素个数 ≤ 3；非档位值由前端表单校验拒绝保存。
 *
 * 注：本接口**不**作为密文载荷字段类型；表单收集后提交前
 * editor.ruleFromForm 会把它拍平为 number（见 form.reminders.map(...)）。
 */
export interface ReminderItem {
  /** 提前分钟数；0=准时。 */
  offset_minutes: number
}

// =============================================================================
// RRULE B 档子集（spec FR-2 / module-schemas.md § 8.3）
// =============================================================================

/** RRULE 频率枚举（B 档 4 档；缺省 UI 默认 rrule=null 即单次）。 */
export type Frequency = 'DAILY' | 'WEEKLY' | 'MONTHLY' | 'YEARLY'

/** ISO 周内 weekday（周一开头，MO..SU）。 */
export type Weekday = 'MO' | 'TU' | 'WE' | 'TH' | 'FR' | 'SA' | 'SU'

/** RRULE 终止条件三选一（spec FR-2 / module-schemas.md § 8.3）。 */
export type RRuleEnd =
  | { kind: 'never' }
  /** until 为本地日历日 YYYY-MM-DD；实例本地日历日 > until 即停。 */
  | { kind: 'date'; until: string }
  /** count 累计展开次数（含 exdate 跳过的实例**不**计入）。 */
  | { kind: 'count'; count: number }

/**
 * 重复规则（spec FR-2 B 档简化子集）。
 *   - `freq`：四档之一；
 *   - `interval`：≥ 1（默认 1）；每隔 interval 个 freq 单位出现一次；
 *   - `byweekday`：仅 `freq ∈ {WEEKLY, MONTHLY}` 有效；WEEKLY 可多选
 *     （同一周内多天命中），MONTHLY 仅支持单 weekday；
 *   - `end`：never / until / count 三选一。
 */
export interface RRule {
  freq: Frequency
  interval: number
  byweekday?: Weekday[]
  end: RRuleEnd
}

// =============================================================================
// 事件规则（spec FR-1 / module-schemas.md § 8.2 字段表）
// =============================================================================

/**
 * 事件规则（明文 JSON 形态；密文内载荷即此结构 JSON 化）。
 *
 * 字段表逐字段含义见 module-schemas.md § 8.2；此处不重复注释。
 *
 * 关键约束：
 *   - `id`：UUID v4；与 records 表主键一一对应；同 id 重复命名走
 *     records LWW 覆盖；
 *   - `start_ts` / `end_ts`：Unix **毫秒**（int64），本地时区语义；
 *   - `tz_mode`：本期固定 `'local'`，字段保留以便未来扩展；
 *   - `reminders`：≤ 3 个；元素为 `ReminderItem` 对象（与 spec 描述
 *     一致；表单与校验层负责 ≤ 3 与档位约束）；
 *   - `rrule = null` 表示单次事件；
 *   - `exdates`：本地日历日 `YYYY-MM-DD` 数组；展开时命中该列表即跳
 *     过对应实例（不递增 n，也不计入 count 配额）。
 */
export interface EventRule {
  id: string
  title: string
  /** Unix 毫秒（int64）；本地时区语义（tz_mode=local）。 */
  start_ts: number
  /** Unix 毫秒；≥ start_ts。 */
  end_ts: number
  all_day: boolean
  /** 本期固定 'local'；字段保留以便未来扩展。 */
  tz_mode: 'local'
  /** 纯文本地点描述；不关联 place 模块（保持模块解耦）。 */
  location_text?: string | null
  /** 纯文本备注。 */
  note?: string | null
  color: EventColor
  /** 提前分钟数组；≤ 3 个；0=准时。密文载荷直接用 number（与 expand.ts 同型）。 */
  reminders: number[]
  /** 重复规则；null = 单次。 */
  rrule: RRule | null
  /** 本地日历日 YYYY-MM-DD；命中即跳过对应实例。 */
  exdates: string[]
}

// =============================================================================
// 实例（spec FR-11 / module-schemas.md § 8.3.1）
// =============================================================================

/**
 * 事件展开实例（客户端按本地时区动态展开；不持久化、不物化）。
 * `instance_id` 形如 `<rule.id>:<rule.start_ts>#<n>`；n 从 0 起跳过 exdate 后递增。
 */
export interface Occurrence {
  instance_id: string
  rule_id: string
  /** 实例起始 Unix 毫秒（UTC ms）。 */
  start_ts: number
  /** 实例结束 Unix 毫秒（= start_ts + 规则时长）。 */
  end_ts: number
  all_day: boolean
  color: EventColor
  title: string
  /** 原始（未漂移）起始 ts；与 RFC 5545 recurrence-id 对齐。 */
  original_start_ts: number
}

/** 时间窗口（毫秒；from 含、to 不含）。 */
export interface TimeWindow {
  from: number
  to: number
}

// =============================================================================
// 模块挂载点常量（spec § 8.1 / module-schemas.md）
// =============================================================================

/**
 * event 模块在 records 表的双键约定（与 4a place 同款 module/type 模式）。
 *   - `module`：records 投递索引（明文可见）；
 *   - `type`：随密文写入；端侧按此识别载荷形态；
 * AAD 沿用既有 `eve:v1:record:{id}`，不新造 envelope 参数（spec § 8.1）。
 */
export const EVENT_MODULE = 'event' as const
export const EVENT_TYPE = 'event' as const

/**
 * 把 EventRule 包装成 records 投递的明文 payload（即加密前的对象）。
 * 仅为占位接口签名；具体序列化在 store/saveRecord 内完成。
 */
export interface EventPayload extends EventRule {}

/**
 * 在事件模块中可能用作 `RecordEnvelope` 字段的对象（与 4a vault.save 同链路）。
 * 这里仅承载 event 模块新增的字段约定；实际密封走 crypto/envelope.sealRecord。
 */
export interface RecordEnvelope {
  module: typeof EVENT_MODULE
  type: typeof EVENT_TYPE
  payload: string // JSON.stringify(EventRule)
}