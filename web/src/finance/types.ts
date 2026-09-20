// ============================================================================
// 财务模块 TypeScript 类型定义（stage5-finance / Task 8 / TR-8.1）
// ============================================================================
//
// 任务: stage5-finance / Task 8 / TR-8.1
// 路径: web/src/finance/types.ts
// 作用: 定义 FinanceAccount / FinanceCard / FinanceTx / DashboardSnapshot 等
//       TypeScript 接口 + FinanceType 常量；与 docs/schemas/finance.schema.json
//       字段逐字段一致（三端契约）。
//
// 设计要点:
//   1. 明文 payload 类型 —— 仅在 store / aggregator / 视图层使用，不落盘；
//   2. decimal-as-string —— 所有金额字段以 string 承载，避免 JS Number 精度丢失；
//   3. id 一律 UUID v4 字符串；
//   4. 时间戳一律 Unix 毫秒 int64；
//   5. v2 子类型（policy / subscription / loan / contract）仅在 FinanceType
//      常量中占位，编辑器 / 列表 / 视图本期不实现（与 spec FR-1 一致）。
//
// 关联:
//   - tasks.md TR-8.1（Web 类型定义）
//   - tasks.md TR-1.1（finance JSON Schema 文档）
//   - docs/schemas/finance.schema.json（字段口径真理源）
//   - docs/module-schemas.md 第 9 章 finance 模块（文档交叉引用）
// ============================================================================

// -----------------------------------------------------------------------------
// FinanceType 子类型常量（含 v2 占位；与 schema 中 $defs.FinanceType 一致）
// -----------------------------------------------------------------------------

/**
 * finance 模块子类型集合（与 docs/schemas/finance.schema.json#/$defs/FinanceType
 * 字节级一致）。
 *
 * - v1 仅启用前三类（account / card / tx）；
 * - v2 启用后扩展后四类（policy / subscription / loan / contract）；
 * - B6 追加 budget（预算硬约束 + 超支拦截），复用 v2 records 通道。
 *
 * 向后兼容：budget 仅在数组末尾追加，不调整既有顺序，不升任何
 * schema_version（budget payload 自身 schema_version=2）。
 */
export const FINANCE_TYPES = [
  'account',
  'card',
  'tx',
  'policy',
  'subscription',
  'loan',
  'contract',
  'budget',
] as const

/** FinanceType 子类型联合。 */
export type FinanceType = (typeof FINANCE_TYPES)[number]

/**
 * 财务模块常量（module=finance；与 schema 顶层 module 字段一致）。
 *
 * 加密链路复用既有 envelope：AAD `eve:v1:record:{id}:finance:{BE(uint64 version)}`，
 * 不新造 envelope 参数。
 */
export const FINANCE_MODULE = 'finance' as const

// -----------------------------------------------------------------------------
// 枚举类型（与 schema $defs 对齐）
// -----------------------------------------------------------------------------

/** 账户类型枚举（v1 固定 5 项）。 */
export type AccountKind = 'cash' | 'deposit' | 'stock' | 'wallet' | 'other'

/** 卡类型枚举（v1 固定 2 项）。 */
export type CardKind = 'debit' | 'credit'

/** 流水类型枚举（3 项）。 */
export type TxKind = 'income' | 'expense' | 'transfer'

/** 色板枚举（与 schema ColorPalette 对齐）。 */
export type ColorPalette =
  | 'blue'
  | 'green'
  | 'red'
  | 'amber'
  | 'violet'
  | 'pink'
  | 'cyan'
  | 'slate'
  | 'emerald'
  | 'rose'
  | 'sky'
  | 'lime'
  | 'orange'

/** ISO 4217 三字母货币代码（v1 默认 CNY）。 */
export type CurrencyCode = string

// -----------------------------------------------------------------------------
// 明文 payload 类型 —— account / card / tx（v1 三类）
// -----------------------------------------------------------------------------

/**
 * 账户条目明文 payload（与 schema FinanceAccount 字段对齐）。
 *
 * 注意：v1 schema 中字段命名采用 snake_case（balance / created_at / updated_at），
 * 此处用 camelCase 在 store / UI 中友好使用；序列化时由 store 负责转换。
 */
export interface FinanceAccount {
  /** UUID v4 字符串。 */
  id: string
  /** schema 版本（v1 固定 1）。 */
  schema_version: 1
  /** 账户名称（1-40 字符）。 */
  name: string
  /** 账户类型枚举。 */
  kind: AccountKind
  /** 货币代码（ISO 4217，默认 CNY）。 */
  currency: CurrencyCode
  /** 余额（decimal-as-string，CNY = 元）。 */
  balance: string
  /** 备注（0-200 字符，可选）。 */
  note?: string | null
  /** lucide-icon 名称（可选）。 */
  icon?: string | null
  /** 色板（默认 blue）。 */
  color: ColorPalette
  /** 是否归档（软删除）；默认 false。 */
  archived: boolean
  /** 创建时刻，Unix 毫秒。 */
  created_at: number
  /** 最后更新时刻，Unix 毫秒。 */
  updated_at: number
}

/**
 * 银行卡 / 信用卡条目明文 payload（与 schema FinanceCard 字段对齐）。
 *
 * 零知识纪律：完整卡号不入 schema，仅 last4 入库；creditLimit / usedLimit
 * 按 decimal-as-string 承载。
 */
export interface FinanceCard {
  /** UUID v4 字符串。 */
  id: string
  /** schema 版本（v1 固定 1）。 */
  schema_version: 1
  /** 卡名（1-40 字符）。 */
  name: string
  /** 卡类型枚举。 */
  kind: CardKind
  /** 银行 / 发卡机构名（1-40 字符）。 */
  issuer: string
  /** 卡号后四位（4 位数字字符串）。 */
  last4: string
  /** 货币代码（默认 CNY）。 */
  currency: CurrencyCode
  /** 信用额度（信用卡必填，借记卡选填）。 */
  credit_limit: string
  /** 已用额度（可选；null 或 0 时按未用标注）。 */
  used_limit?: string | null
  /** 账单日（1-31，每月 day_of_month）。 */
  billing_day?: number | null
  /** 还款日距账单日天数 offset（1-31）。 */
  due_day?: number | null
  /** 备注（0-200 字符）。 */
  note?: string | null
  /** lucide-icon 名称。 */
  icon?: string | null
  /** 色板。 */
  color: ColorPalette
  /** 是否归档；默认 false。 */
  archived: boolean
  /** 是否计入净资产看板；默认 true；归档自动视为 false。 */
  include_in_net_assets: boolean
  /** 创建时刻。 */
  created_at: number
  /** 最后更新时刻。 */
  updated_at: number
}

/**
 * 流水条目明文 payload（与 schema FinanceTx 字段对齐）。
 *
 * amount 一律正数；kind 决定方向（expense 减余额 / income 增余额 /
 * transfer 双向调整）。
 */
export interface FinanceTx {
  /** UUID v4 字符串。 */
  id: string
  /** schema 版本（v1 固定 1）。 */
  schema_version: 1
  /** 出账方账户 id；expense / income 必填；transfer 必填。 */
  account_id: string | null
  /** 出账方卡 id；信用卡交易时填（与 account_id 二选一）。 */
  card_id?: string | null
  /** 流水类型。 */
  kind: TxKind
  /** 金额（正 decimal-as-string；kind 决定方向）。 */
  amount: string
  /** 分类 ID 或自由文本（1-20 字符）。 */
  category: string
  /** 流水发生时刻，Unix 毫秒；可改回历史日期补录。 */
  occurred_at: number
  /** 备注（0-200 字符）。 */
  note?: string | null
  /** 转账入账方账户 id；kind=transfer 必填，且不能等于 account_id。 */
  transfer_to_account_id?: string | null
  /** lucide-icon 名称。 */
  icon?: string | null
  /** 色板。 */
  color: ColorPalette
  /**
   * 超支硬拦截确认标记（B6 / FR-V2-F，可选；仅本地审计语义）。
   *
   * 用户在预算阻断确认弹窗中选择“仍保存”后置 true：表示本笔支出保存时
   * 已预计超过预算拦截阈值，且经用户显式确认。旧数据无此键时按
   * undefined / false 处理；未触发拦截的新流水也不挂该键，保持旧数据
   * 形态干净。流水自身 v1，schema_version 仍为 1，不随本字段升级。
   */
  overspend_acknowledged?: boolean
  /** 创建时刻。 */
  created_at: number
  /** 最后更新时刻。 */
  updated_at: number
}

/**
 * 财务条目明文 payload 联合（按 type 子类型区分）。
 *
 * store / aggregator / 视图层按 `type` 字段做类型收窄；序列化由 envelope 链路
 * 统一处理（明文 → sealRecord → pushRecords）。
 */
export type FinancePayload = FinanceAccount | FinanceCard | FinanceTx

// -----------------------------------------------------------------------------
// v2 子类型接口 —— Subscription / Policy / Loan / Contract（stage5-finance-v2 / TR-1.1）
// -----------------------------------------------------------------------------

/**
 * 订阅条目（type='subscription'）明文 payload。
 *
 * v2 子类型：复用 v1 records 通道 + 同款 envelope（AAD `module="finance"` +
 * `type="subscription"` 子标识），不新造 envelope。
 *
 * 提醒配置走 v1 events 单闹钟链式调度 + `kind="subscription_renewal"` 分支，
 * 链路扩展点在 Reminders 通道（见 web/src/stores/finance.ts Task 4 TR-4.3）。
 */
export interface FinanceSubscription {
  /** UUID v4 字符串。 */
  id: string
  /** schema 版本（v2 固定 2）。 */
  schema_version: 2
  /** 订阅名称（≤200 字符）。 */
  name: string
  /** 服务商（≤200 字符）。 */
  provider: string
  /** 金额（decimal-as-string；与 currency 配对）。 */
  amount_minor: string
  /** 货币代码（ISO 4217，默认 CNY）。 */
  currency: CurrencyCode
  /** 计费周期。 */
  billing_cycle: 'monthly' | 'quarterly' | 'yearly' | 'custom_days'
  /** 自定义周期天数（billing_cycle='custom_days' 时必填，其它为 null）。 */
  custom_days: number | null
  /** 起始时刻，Unix 毫秒。 */
  start_ts: number
  /** 下次扣费时刻，Unix 毫秒；由 nextSubscriptionRenewal 纯函数计算。 */
  next_renewal_ts: number
  /** 续费提醒偏移（分钟；如 [0, 1440] 表示"当日 + 前一天"）。 */
  reminders: number[]
  /** 是否启用；false 表示已停用但保留历史。 */
  active: boolean
  /** 分类。 */
  category: 'entertainment' | 'productivity' | 'utility' | 'other'
  /** 创建时刻。 */
  created_at: number
  /** 最后更新时刻。 */
  updated_at: number
}

/**
 * 保单条目（type='policy'）明文 payload。
 *
 * 保单号 policy_number 默认加密（policy_number_encrypted=true），
 * 仅显示末四位或不显示（避免日志泄漏）。
 *
 * 附件走 v2 启用的 records 通道 type='attachment' 子标识（见 Task 3）。
 */
export interface FinancePolicy {
  /** UUID v4 字符串。 */
  id: string
  /** schema 版本（v2 固定 2）。 */
  schema_version: 2
  /** 保单名称（≤200 字符）。 */
  name: string
  /** 保单号（≤100 字符；显示时按需截取末 4 位）。 */
  policy_number: string
  /** 保单号是否加密存储（默认 true；false 表示明文存档）。 */
  policy_number_encrypted: boolean
  /** 保险公司（≤200 字符）。 */
  provider: string
  /** 保费（decimal-as-string）。 */
  premium_minor: string
  /** 货币代码。 */
  currency: CurrencyCode
  /** 计费周期。 */
  billing_cycle: 'monthly' | 'quarterly' | 'yearly' | 'single'
  /** 起始时刻。 */
  start_ts: number
  /** 到期时刻。 */
  expiry_ts: number
  /** 到期提醒偏移（分钟；如 [0, 10080, 43200] 表示"当日 + 7天 + 30天"）。 */
  reminders: number[]
  /** 保额（decimal-as-string）。 */
  coverage_minor: string
  /** 是否启用。 */
  active: boolean
  /** 关联账户 id（如保费自动从某账户扣款）。 */
  linked_account_id: string | null
  /** 附件引用列表（v2 启用；走 records 通道 type='attachment'）。 */
  attachments: AttachmentRef[]
  /** 创建时刻。 */
  created_at: number
  /** 最后更新时刻。 */
  updated_at: number
}

/** 应收借款方向。 */
export type LoanDirection = 'lent' | 'borrowed'

/** 应收借款状态。 */
export type LoanStatus = 'active' | 'partially_paid' | 'paid' | 'overdue'

/**
 * 应收借款条目（type='loan'）明文 payload。
 *
 * v2 启用时纳入 v1 aggregator 的资产看板（net_assets += lent - borrowed）；
 * aggregator 入参已留 `loans?: Loan[]`（见 web/src/finance/aggregator.ts T5 修订）。
 */
export interface FinanceLoan {
  /** UUID v4 字符串。 */
  id: string
  /** schema 版本（v2 固定 2）。 */
  schema_version: 2
  /** 对手方（≤200 字符；不渲染到通知文案）。 */
  counterparty: string
  /** 本金（decimal-as-string）。 */
  principal_minor: string
  /** 货币代码。 */
  currency: CurrencyCode
  /** 借款方向：lent=我借出 / borrowed=我借入。 */
  direction: LoanDirection
  /** 放款时刻。 */
  issue_ts: number
  /** 到期时刻。 */
  due_ts: number
  /** 年化利率（基点 bps；10000 bps = 100%）。 */
  interest_rate_apy_bps: number
  /** 状态。 */
  status: LoanStatus
  /** 已还金额（decimal-as-string）。 */
  paid_minor: string
  /** 到期提醒偏移（分钟）。 */
  reminders: number[]
  /** 关联账户 id（可选）。 */
  linked_account_id: string | null
  /** 是否计入净资产（默认 true）；借入 false 时仅显示不计入。 */
  include_in_net_assets: boolean
  /** 创建时刻。 */
  created_at: number
  /** 最后更新时刻。 */
  updated_at: number
}

/** 合同 / 发票类型。 */
export type ContractKind = 'rental' | 'service' | 'purchase' | 'loan' | 'other'

/** 合同状态。 */
export type ContractStatus = 'active' | 'expired' | 'terminated' | 'renewed'

/**
 * 合同 / 发票条目（type='contract'）明文 payload。
 *
 * contract 不接入 v1 Reminders 通道主流程；notice_deadline_ts 提醒 v3 评估。
 */
export interface FinanceContract {
  /** UUID v4 字符串。 */
  id: string
  /** schema 版本（v2 固定 2）。 */
  schema_version: 2
  /** 合同标题（≤200 字符）。 */
  title: string
  /** 对手方（≤200 字符）。 */
  counterparty: string
  /** 合同种类。 */
  kind: ContractKind
  /** 金额（decimal-as-string）。 */
  amount_minor: string
  /** 货币代码。 */
  currency: CurrencyCode
  /** 签约时刻。 */
  signed_ts: number
  /** 起始时刻。 */
  start_ts: number
  /** 结束时刻。 */
  end_ts: number
  /** 是否自动续约。 */
  auto_renew: boolean
  /** 提前通知期（天）。 */
  notice_period_days: number
  /** 通知截止时刻（Unix 毫秒；由 end_ts - notice_period_days 推算）。 */
  notice_deadline_ts: number
  /** 状态。 */
  status: ContractStatus
  /** 关联账户 id。 */
  linked_account_id: string | null
  /** 附件引用列表（v2 启用；走 records 通道 type='attachment'）。 */
  attachments: AttachmentRef[]
  /** 创建时刻。 */
  created_at: number
  /** 最后更新时刻。 */
  updated_at: number
}

/**
 * 预算条目（type='budget'）明文 payload（B6 / FR-V2-F 预算硬约束）。
 *
 * B6 Web 集成层接线后：已并入 FINANCE_TYPES 常量、FinanceV2Payload 联合
 * 与 validateV2Payload 分发，store / UI 全链路复用 v2 records 通道。
 *
 * 字段与 Android `BudgetRecord` 一一对应；金额 amount_minor 为
 * decimal-as-string，纯函数计算时统一转成 minor 分整数 bigint。
 *
 * 周期口径（与 Android BudgetEnforcer.periodBucket 同口径）：
 *   - start_ts / end_ts 为预算有效期（双闭区间，均为正整数毫秒，end 大于
 *     等于 start），流水发生时刻不在有效期内则该预算完全不参与判定；
 *   - monthly / yearly 在有效期内按 CST（UTC+8）自然月 / 自然年滚动分桶；
 *   - weekly 以 start_ts 所在 CST 日期零点为 epoch，每 7 天一个滚动桶；
 *   - custom 在有效期内只有一个桶 [start_ts, end_ts + 1)（末点开区间）。
 */
export interface FinanceBudget {
  /** UUID v4 字符串。 */
  id: string
  /** schema 版本（v2 固定 2）。 */
  schema_version: 2
  /** 周期口径：monthly | weekly | yearly | custom。 */
  scope: 'monthly' | 'weekly' | 'yearly' | 'custom'
  /** 分类匹配值；'all' 为特殊值表示覆盖全部分类，其余为具体分类名（1-20 字符）。 */
  category: string
  /** 预算额度（decimal-as-string；正数，最多两位小数，如 "1000.00"）。 */
  amount_minor: string
  /** 预算币种（ISO 4217 三字母代码；异币流水按离线汇率表折算）。 */
  currency: CurrencyCode
  /** 预算有效期起点（Unix 毫秒，含；必须为正整数）。 */
  start_ts: number
  /** 预算有效期终点（Unix 毫秒，含；必须大于等于 start_ts）。 */
  end_ts: number
  /** 预警阈值百分数（80 表示 80%；合法区间 1..10000 且小于等于 block）。 */
  warning_threshold_pct: number
  /** 阻断确认阈值百分数（100 表示 100%，150 表示 150%）。 */
  block_threshold_pct: number
  /** 是否启用；false 表示停用且不参与判定，但保留历史。 */
  active: boolean
  /** 创建时刻，Unix 毫秒。 */
  created_at: number
  /** 最后更新时刻，Unix 毫秒。 */
  updated_at: number
}

/**
 * 附件引用（policy / contract 等 v2 记录挂的附件列表项）。
 *
 * 实际二进制走 records 通道 type='attachment'；这里只存元数据。
 */
export interface AttachmentRef {
  /** 附件 UUID。 */
  id: string
  /** MIME 类型（如 application/pdf / image/jpeg）。 */
  mime: string
  /** 文件大小（字节；端侧校验 ≤ 50MB）。 */
  size: number
  /** 附件二进制 sha-256（hex 字符串）。 */
  sha256: string
}

/**
 * v2 财务条目明文 payload 联合。
 *
 * store / aggregator / 视图层按 `type` 字段做类型收窄；序列化由 envelope 链路
 * 统一处理（明文 → sealRecord → pushRecords）。
 */
export type FinanceV2Payload =
  | FinanceSubscription
  | FinancePolicy
  | FinanceLoan
  | FinanceContract
  | FinanceBudget

/** 全部财务条目明文 payload 联合（v1 三类 + v2 四类）。 */
export type FinancePayloadAll = FinancePayload | FinanceV2Payload

// -----------------------------------------------------------------------------
// v2 子类型校验函数（stage5-finance-v2 / TR-1.1）
// -----------------------------------------------------------------------------

/** v2 子类型 schema_version 常量。 */
export const FINANCE_V2_SCHEMA_VERSION = 2 as const

/**
 * v2 校验结果。
 *
 * - ok=true → 通过；
 * - ok=false → 校验失败，`reason` 给出人类可读原因（不含敏感数据）。
 */
export type ValidationResult =
  | { ok: true }
  | { ok: false; reason: string }

/**
 * decimal-as-string 校验（金额字段：非负 + 最多 2 位小数 + > 0）。
 *
 * 用于 `amount_minor` / `principal_minor` / `premium_minor` / `coverage_minor`
 * 等"必填金额"字段（值必须 > 0）。`paid_minor`（已还）允许 0，用
 * `isValidDecimalNonNegative` 替代。
 */
export function isValidDecimalString(s: string): boolean {
  if (typeof s !== 'string' || s.length === 0) return false
  if (!/^\d+(\.\d{1,2})?$/.test(s)) return false
  // 禁止全 0（必填金额必须 > 0）
  return s !== '0' && s !== '0.0' && s !== '0.00'
}

/**
 * decimal-as-string 非负校验（非负 + 最多 2 位小数，允许 0）。
 *
 * 用于 `paid_minor` / `remaining_minor` 等"累计 / 余量"字段。
 */
export function isValidDecimalNonNegative(s: string): boolean {
  if (typeof s !== 'string' || s.length === 0) return false
  return /^\d+(\.\d{1,2})?$/.test(s)
}

/** ISO 4217 三字母代码（粗校验：3 个大写字母）。 */
export function isValidCurrencyCode(c: string): boolean {
  return typeof c === 'string' && /^[A-Z]{3}$/.test(c)
}

/** sha-256 hex 字符串校验（64 个十六进制字符）。 */
export function isValidSha256Hex(s: string): boolean {
  return typeof s === 'string' && /^[0-9a-f]{64}$/.test(s)
}

/** 单文件 ≤ 50MB 限制（端侧校验；超限直接拒收）。 */
export const ATTACHMENT_MAX_SIZE_BYTES = 50 * 1024 * 1024

/**
 * 校验订阅条目。
 *
 * @returns 校验结果；不抛异常（spec 零知识纪律：日志 / 提示不渲染金额 / 日期数字）。
 */
export function validateSubscription(p: FinanceSubscription): ValidationResult {
  if (p.schema_version !== FINANCE_V2_SCHEMA_VERSION) {
    return { ok: false, reason: 'schema_version 必须是 2' }
  }
  if (!p.id || typeof p.id !== 'string') return { ok: false, reason: 'id 缺失' }
  if (!p.name || p.name.length === 0 || p.name.length > 200) {
    return { ok: false, reason: 'name 长度需在 1-200 字符' }
  }
  if (!p.provider || p.provider.length === 0 || p.provider.length > 200) {
    return { ok: false, reason: 'provider 长度需在 1-200 字符' }
  }
  if (!isValidDecimalString(p.amount_minor)) {
    return { ok: false, reason: 'amount_minor 非法' }
  }
  if (!isValidCurrencyCode(p.currency)) {
    return { ok: false, reason: 'currency 必须为 ISO 4217 三字母大写代码' }
  }
  const cycles = ['monthly', 'quarterly', 'yearly', 'custom_days'] as const
  if (!cycles.includes(p.billing_cycle)) {
    return { ok: false, reason: 'billing_cycle 非法' }
  }
  if (p.billing_cycle === 'custom_days') {
    if (p.custom_days == null || p.custom_days <= 0 || !Number.isInteger(p.custom_days)) {
      return { ok: false, reason: 'custom_days 在 billing_cycle=custom_days 时必须为正整数' }
    }
  } else if (p.custom_days !== null) {
    return { ok: false, reason: 'custom_days 在非 custom_days 周期时必须为 null' }
  }
  if (p.next_renewal_ts < p.start_ts) {
    return { ok: false, reason: 'next_renewal_ts 必须 >= start_ts' }
  }
  if (!Array.isArray(p.reminders) || p.reminders.some((r) => r < 0 || !Number.isInteger(r))) {
    return { ok: false, reason: 'reminders 必须为非负整数数组（分钟偏移）' }
  }
  return { ok: true }
}

/**
 * 校验保单条目。
 */
export function validatePolicy(p: FinancePolicy): ValidationResult {
  if (p.schema_version !== FINANCE_V2_SCHEMA_VERSION) {
    return { ok: false, reason: 'schema_version 必须是 2' }
  }
  if (!p.id || typeof p.id !== 'string') return { ok: false, reason: 'id 缺失' }
  if (!p.name || p.name.length === 0 || p.name.length > 200) {
    return { ok: false, reason: 'name 长度需在 1-200 字符' }
  }
  if (!p.policy_number || p.policy_number.length > 100) {
    return { ok: false, reason: 'policy_number 长度需在 1-100 字符' }
  }
  if (!p.provider || p.provider.length === 0 || p.provider.length > 200) {
    return { ok: false, reason: 'provider 长度需在 1-200 字符' }
  }
  if (!isValidDecimalString(p.premium_minor)) {
    return { ok: false, reason: 'premium_minor 非法' }
  }
  if (!isValidCurrencyCode(p.currency)) {
    return { ok: false, reason: 'currency 必须为 ISO 4217 三字母大写代码' }
  }
  const cycles = ['monthly', 'quarterly', 'yearly', 'single'] as const
  if (!cycles.includes(p.billing_cycle)) {
    return { ok: false, reason: 'billing_cycle 非法' }
  }
  if (p.expiry_ts < p.start_ts) {
    return { ok: false, reason: 'expiry_ts 必须 >= start_ts' }
  }
  if (!isValidDecimalString(p.coverage_minor)) {
    return { ok: false, reason: 'coverage_minor 非法' }
  }
  // 附件元数据校验
  for (const a of p.attachments ?? []) {
    if (!isValidSha256Hex(a.sha256)) return { ok: false, reason: 'attachment.sha256 非法' }
    if (a.size <= 0 || a.size > ATTACHMENT_MAX_SIZE_BYTES) {
      return { ok: false, reason: 'attachment.size 超 50MB 或非正' }
    }
    if (!a.mime || typeof a.mime !== 'string') return { ok: false, reason: 'attachment.mime 缺失' }
  }
  if (!Array.isArray(p.reminders) || p.reminders.some((r) => r < 0 || !Number.isInteger(r))) {
    return { ok: false, reason: 'reminders 必须为非负整数数组（分钟偏移）' }
  }
  return { ok: true }
}

/**
 * 校验应收借款条目。
 */
export function validateLoan(p: FinanceLoan): ValidationResult {
  if (p.schema_version !== FINANCE_V2_SCHEMA_VERSION) {
    return { ok: false, reason: 'schema_version 必须是 2' }
  }
  if (!p.id || typeof p.id !== 'string') return { ok: false, reason: 'id 缺失' }
  if (!p.counterparty || p.counterparty.length === 0 || p.counterparty.length > 200) {
    return { ok: false, reason: 'counterparty 长度需在 1-200 字符' }
  }
  if (!isValidDecimalString(p.principal_minor)) {
    return { ok: false, reason: 'principal_minor 非法' }
  }
  if (!isValidCurrencyCode(p.currency)) {
    return { ok: false, reason: 'currency 必须为 ISO 4217 三字母大写代码' }
  }
  if (p.direction !== 'lent' && p.direction !== 'borrowed') {
    return { ok: false, reason: 'direction 非法' }
  }
  if (p.due_ts < p.issue_ts) {
    return { ok: false, reason: 'due_ts 必须 >= issue_ts' }
  }
  if (!Number.isInteger(p.interest_rate_apy_bps) || p.interest_rate_apy_bps < 0) {
    return { ok: false, reason: 'interest_rate_apy_bps 必须为非负整数' }
  }
  const statuses = ['active', 'partially_paid', 'paid', 'overdue'] as const
  if (!statuses.includes(p.status)) return { ok: false, reason: 'status 非法' }
  // paid_minor 允许 0（未还款），用 isValidDecimalNonNegative
  if (!isValidDecimalNonNegative(p.paid_minor)) {
    return { ok: false, reason: 'paid_minor 非法' }
  }
  // 已还本金不能超过本金
  if (Number(p.paid_minor) > Number(p.principal_minor)) {
    return { ok: false, reason: 'paid_minor 不能超过 principal_minor' }
  }
  if (!Array.isArray(p.reminders) || p.reminders.some((r) => r < 0 || !Number.isInteger(r))) {
    return { ok: false, reason: 'reminders 必须为非负整数数组（分钟偏移）' }
  }
  return { ok: true }
}

/**
 * 校验合同 / 发票条目。
 */
export function validateContract(p: FinanceContract): ValidationResult {
  if (p.schema_version !== FINANCE_V2_SCHEMA_VERSION) {
    return { ok: false, reason: 'schema_version 必须是 2' }
  }
  if (!p.id || typeof p.id !== 'string') return { ok: false, reason: 'id 缺失' }
  if (!p.title || p.title.length === 0 || p.title.length > 200) {
    return { ok: false, reason: 'title 长度需在 1-200 字符' }
  }
  if (!p.counterparty || p.counterparty.length === 0 || p.counterparty.length > 200) {
    return { ok: false, reason: 'counterparty 长度需在 1-200 字符' }
  }
  const kinds = ['rental', 'service', 'purchase', 'loan', 'other'] as const
  if (!kinds.includes(p.kind)) return { ok: false, reason: 'kind 非法' }
  if (!isValidDecimalString(p.amount_minor)) {
    return { ok: false, reason: 'amount_minor 非法' }
  }
  if (!isValidCurrencyCode(p.currency)) {
    return { ok: false, reason: 'currency 必须为 ISO 4217 三字母大写代码' }
  }
  if (p.end_ts < p.start_ts) {
    return { ok: false, reason: 'end_ts 必须 >= start_ts' }
  }
  if (!Number.isInteger(p.notice_period_days) || p.notice_period_days < 0) {
    return { ok: false, reason: 'notice_period_days 必须为非负整数' }
  }
  // notice_deadline_ts 应 = end_ts - notice_period_days * 86400000
  const expected = p.end_ts - p.notice_period_days * 86400000
  if (p.notice_deadline_ts !== expected) {
    return { ok: false, reason: 'notice_deadline_ts 必须等于 end_ts - notice_period_days * 86400000' }
  }
  const statuses = ['active', 'expired', 'terminated', 'renewed'] as const
  if (!statuses.includes(p.status)) return { ok: false, reason: 'status 非法' }
  // 附件元数据校验（同 policy）
  for (const a of p.attachments ?? []) {
    if (!isValidSha256Hex(a.sha256)) return { ok: false, reason: 'attachment.sha256 非法' }
    if (a.size <= 0 || a.size > ATTACHMENT_MAX_SIZE_BYTES) {
      return { ok: false, reason: 'attachment.size 超 50MB 或非正' }
    }
    if (!a.mime || typeof a.mime !== 'string') return { ok: false, reason: 'attachment.mime 缺失' }
  }
  return { ok: true }
}

/**
 * 校验预算条目（B6 / FR-V2-F，经 validateV2Payload 按 type='budget' 分发）。
 *
 * 规则与 Android `FinanceRecords.validateBudget` 逐分支一致：
 *   1. schema_version 必须为 2；id 非空；
 *   2. scope 必须为 monthly / weekly / yearly / custom 四值之一；
 *   3. category 非空且长度 1..20；'all' 是允许的特殊值（覆盖全部分类）；
 *   4. amount_minor 走必填金额校验（正数，最多两位小数）；
 *   5. currency 走 ISO 4217 三字母大写代码校验；
 *   6. start_ts 必须为正整数毫秒，end_ts 必须为整数且大于等于 start_ts
 *      （四种 scope 同口径：二者表达预算有效期双闭区间）；
 *   7. 阈值为整数且满足 1 <= warning <= block <= 10000。
 *
 * reason 文案为中文字段级提示，不含金额 / 日期等敏感数值。
 */
export function validateBudget(p: FinanceBudget): ValidationResult {
  if (p.schema_version !== FINANCE_V2_SCHEMA_VERSION) {
    return { ok: false, reason: 'schema_version 必须是 2' }
  }
  if (!p.id || typeof p.id !== 'string') return { ok: false, reason: 'id 缺失' }
  const scopes = ['monthly', 'weekly', 'yearly', 'custom'] as const
  if (!scopes.includes(p.scope)) return { ok: false, reason: 'scope 非法' }
  if (!p.category || typeof p.category !== 'string' || p.category.length === 0 || p.category.length > 20) {
    return { ok: false, reason: 'category 长度需在 1-20 字符' }
  }
  if (!isValidDecimalString(p.amount_minor)) {
    return { ok: false, reason: 'amountMinor 非法' }
  }
  if (!isValidCurrencyCode(p.currency)) {
    return { ok: false, reason: 'currency 必须为 ISO 4217 三字母大写代码' }
  }
  if (!Number.isInteger(p.start_ts) || p.start_ts <= 0) {
    return { ok: false, reason: 'startTs 必须为正整数毫秒' }
  }
  if (!Number.isInteger(p.end_ts) || p.end_ts < p.start_ts) {
    return { ok: false, reason: 'endTs 必须为整数且大于等于 startTs' }
  }
  if (
    !Number.isInteger(p.warning_threshold_pct) ||
    !Number.isInteger(p.block_threshold_pct) ||
    p.warning_threshold_pct < 1 ||
    p.block_threshold_pct < p.warning_threshold_pct ||
    p.block_threshold_pct > 10000
  ) {
    return {
      ok: false,
      reason: '阈值需满足 1 <= warningThresholdPct <= blockThresholdPct <= 10000',
    }
  }
  return { ok: true }
}

/**
 * v2 子类型校验统一入口（按 type 路由）。
 */
export function validateV2Payload(
  type: FinanceType,
  payload: unknown,
): ValidationResult {
  switch (type) {
    case 'subscription':
      return validateSubscription(payload as FinanceSubscription)
    case 'policy':
      return validatePolicy(payload as FinancePolicy)
    case 'loan':
      return validateLoan(payload as FinanceLoan)
    case 'contract':
      return validateContract(payload as FinanceContract)
    // B6：预算子类型接入统一分发（FINANCE_TYPES / FinanceV2Payload 已含）。
    case 'budget':
      return validateBudget(payload as FinanceBudget)
    default:
      return { ok: false, reason: `不支持的 type=${String(type)}` }
  }
}

// -----------------------------------------------------------------------------
// 聚合视图类型 —— DashboardSnapshot / MonthlyReport / BudgetStatus
// -----------------------------------------------------------------------------

/**
 * 资产看板快照（FinanceDashboard 顶部三数字卡 + 计数）。
 *
 * 字段语义（与 Android FinanceAggregator.DashboardSnapshot 一致）：
 *   - totalAssets：净资产（decimal-as-string）= 总资产 - 总负债；
 *   - totalAssetValue：总资产（仅账户余额；不含信用卡信用额度）；
 *   - totalLiability：总负债（所有非归档信用卡的 usedLimit 之和）；
 *   - accountCount / cardCount / txCount：列表计数（含归档条目）；
 *   - currency：货币代码；空集合时 = "CNY"（首账户币的原始口径锚点）；
 *   - targetCurrency：B5 折算目标币（FR-V2-C.3，可选）。v1 面值口径调用
 *     （netWorth 不传 rateTable 且目标币为默认 CNY）时该键缺省，快照保持
 *     v1 七字段形态；折算上下文激活（rateTable 非空或目标币非 CNY）时必然存在。
 */
export interface DashboardSnapshot {
  totalAssets: string
  totalAssetValue: string
  totalLiability: string
  accountCount: number
  cardCount: number
  txCount: number
  currency: CurrencyCode
  /** B5 折算目标币；缺省语义即默认 CNY（v1 面值口径）。 */
  targetCurrency?: CurrencyCode
}

/**
 * 月度收支汇总（FinanceDashboard 月报表格 + 分类饼图）。
 *
 * 字段语义：
 *   - yearMonth：年月键 "YYYY-MM"；
 *   - income：当月收入合计；
 *   - expense：当月支出合计；
 *   - net：月度净流（= income - expense）；
 *   - txCount：当月流水条数（含 income / expense / transfer）；
 *   - categoryBreakdown：分类占比（仅 expense 分类）。
 */
export interface MonthlyReport {
  yearMonth: string
  income: string
  expense: string
  net: string
  txCount: number
  categoryBreakdown: Record<string, string>
}

/**
 * 预算阈值告警状态（BudgetCard 颜色 + 文案路由）。
 *
 * 三档：
 *   - OK：支出未达阈值，预算健康；
 *   - WARNING：支出达到或超过阈值，但未超过 1.5 倍阈值（黄色告警）；
 *   - EXCEEDED：支出超过 1.5 倍阈值（红色告警）。
 */
export type BudgetStatus = 'OK' | 'WARNING' | 'EXCEEDED'

// -----------------------------------------------------------------------------
// store 内部缓存形态 —— 与 event-rules store 同款（明文 + 信封元数据）
// -----------------------------------------------------------------------------

/**
 * store 内缓存的财务条目（明文 + 信封元数据，仅内存）。
 *
 * 与 event-rules store CachedEventRule 同模式：
 *   - 明文 payload + 信封元数据（id / version / createdAt / updatedAt）；
 *   - 仅存活于内存，刷新即清空；
 *   - sealed = true 表示密文已生成（v1 阶段暂留空，作为 T11 同步集成接入点）。
 */
export interface CachedFinanceRecord {
  id: string
  /** finance 模块（固定 'finance'）。 */
  module: typeof FINANCE_MODULE
  /** 子类型。 */
  type: FinanceType
  /** 信封版本（严格递增）。 */
  version: number
  /** 创建时刻（服务端权威时间）。 */
  createdAt: number
  /** 最后更新时刻。 */
  updatedAt: number
  /** 软删除墓碑。 */
  deleted: boolean
  /** 明文 payload。 */
  data: FinancePayloadAll
}

// -----------------------------------------------------------------------------
// 默认值常量（UI 渲染未指定字段时使用；与 spec § FR-9 / FR-10 一致）
// -----------------------------------------------------------------------------

/** 账户默认色（新建空白账户时预填）。 */
export const DEFAULT_ACCOUNT_COLOR: ColorPalette = 'blue'

/** 卡默认色（新建空白卡时预填）。 */
export const DEFAULT_CARD_COLOR: ColorPalette = 'blue'

/** 流水默认色。 */
export const DEFAULT_TX_COLOR: ColorPalette = 'slate'

/** 默认货币代码（v1 固定 CNY）。 */
export const DEFAULT_CURRENCY: CurrencyCode = 'CNY'