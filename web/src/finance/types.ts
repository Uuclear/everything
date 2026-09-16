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
 * - 编辑器 / 列表 / 视图本期不实现 v2（spec FR-1 边界）。
 */
export const FINANCE_TYPES = [
  'account',
  'card',
  'tx',
  'policy',
  'subscription',
  'loan',
  'contract',
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
 *   - currency：货币代码；空集合时 = "CNY"。
 */
export interface DashboardSnapshot {
  totalAssets: string
  totalAssetValue: string
  totalLiability: string
  accountCount: number
  cardCount: number
  txCount: number
  currency: CurrencyCode
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
  data: FinancePayload
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