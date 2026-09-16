// ============================================================================
// 财务视图层 —— 纯函数辅助模块（stage5-finance / Task 9 / TR-9.8）
// ============================================================================
//
// 任务: stage5-finance / Task 9 / TR-9.8
// 路径: web/src/views/finance/__internal__/format.ts
// 作用: 把视图组件 setup 内的纯函数（金额格式化 / 日分组 / Luhn 校验包装 /
//       校验等）提取为可独立测试的模块，便于 vitest 在 node 环境下单测覆盖。
//
// 设计要点:
//   1. 仅承载"无副作用 / 无 DOM / 无 Vue 响应式"的纯函数；
//   2. 不依赖 router / store / message —— 这些在 setup 内通过参数注入；
//   3. 测试入口（__tests__/*.spec.ts）直接 import 此模块做断言；
//   4. 与 FinanceAccountList / FinanceCardList / FinanceTxList 渲染层共享。
//
// 关联:
//   - tasks.md TR-9.8（Web 视图 vitest 单测）
// ============================================================================

import type { FinanceAccount, FinanceCard, FinanceTx } from '../../../finance/types'

/**
 * decimal-as-string → "¥ + 千分位整数"（零知识口径，不显示小数点精度）。
 *
 * @param cents 入参 decimal-as-string （如 "1234.56"）
 * @returns 格式化字符串（如 "¥1,234"）
 */
export function formatYuan(cents: string): string {
  const num = Number(cents)
  if (!Number.isFinite(num)) return '¥0'
  const yuan = Math.floor(num)
  const formatted = yuan.toLocaleString('zh-CN')
  return '¥' + formatted
}

/**
 * 余额格式化（账户列表 / 卡额度共用）。与 formatYuan 等价，命名上区分用途。
 *
 * @param balance decimal-as-string
 */
export function formatBalance(balance: string): string {
  return formatYuan(balance)
}

/**
 * 流水金额格式化（带 +/- 前缀）：
 *   - income  → "+¥1,234"
 *   - expense → "-¥1,234"
 *   - transfer → "¥1,234"
 *
 * @param tx 单条流水（含 kind + amount）
 */
export function formatTxAmount(tx: FinanceTx): string {
  const n = Number(tx.amount)
  if (!Number.isFinite(n)) return '¥0'
  const yuan = Math.floor(n).toLocaleString('zh-CN')
  if (tx.kind === 'income') return '+¥' + yuan
  if (tx.kind === 'expense') return '-¥' + yuan
  return '¥' + yuan
}

/**
 * Unix 毫秒 → 本地日历日键 "YYYY-MM-DD"（CST 本地日历分量）。
 *
 * 复用 aggregator 同口径：ms + tz_offset_ms 当 UTC ms 读，y/m/d 拆出来。
 *
 * @param ts Unix 毫秒
 * @param tzOffsetMin 本地时区相对 UTC 的偏移（分钟；CST = +480）
 */
export function dayKey(ts: number, tzOffsetMin: number): string {
  const shifted = ts + tzOffsetMin * 60_000
  const d = new Date(shifted)
  const y = d.getUTCFullYear()
  const m = String(d.getUTCMonth() + 1).padStart(2, '0')
  const dd = String(d.getUTCDate()).padStart(2, '0')
  return `${y}-${m}-${dd}`
}

/**
 * Unix 毫秒 → 本地年月键 "YYYY-MM"（与 aggregator.yearMonthOf 同口径）。
 */
export function yearMonthKey(ts: number, tzOffsetMin: number): string {
  const shifted = ts + tzOffsetMin * 60_000
  const d = new Date(shifted)
  const y = d.getUTCFullYear()
  const m = String(d.getUTCMonth() + 1).padStart(2, '0')
  return `${y}-${m}`
}

/**
 * 流水按本地日历日分组（最新在前）。
 *
 * @param txs 流水列表（明文 FinanceTx）
 * @param tzOffsetMin 本地时区偏移（分钟）
 */
export interface TxDayGroup {
  dayKey: string
  items: FinanceTx[]
}
export function groupTxsByDay(
  txs: FinanceTx[],
  tzOffsetMin: number,
): TxDayGroup[] {
  const map = new Map<string, FinanceTx[]>()
  for (const tx of txs) {
    const k = dayKey(tx.occurred_at, tzOffsetMin)
    if (!map.has(k)) map.set(k, [])
    map.get(k)!.push(tx)
  }
  return Array.from(map.entries())
    .sort((a, b) => (a[0] < b[0] ? 1 : -1))
    .map(([k, v]) => ({ dayKey: k, items: v }))
}

/**
 * 账户搜索过滤 —— name 模糊匹配（不含大小写）。
 */
export function filterAccountsByName(
  accounts: FinanceAccount[],
  keyword: string,
): FinanceAccount[] {
  const k = keyword.trim().toLowerCase()
  if (!k) return accounts
  return accounts.filter((a) => a.name.toLowerCase().includes(k))
}

/**
 * 卡尾号标签 —— 零知识口径下仅显示最后 4 位数字。
 */
export function cardTailLabel(last4: string): string {
  if (!last4) return '••••'
  return '•••• ' + last4.slice(-4)
}

/**
 * 账单日 / 还款日标签 —— 不显示具体日期数字, 仅显示"账单日:每月 X 日"。
 */
export function statementLabel(card: FinanceCard): string {
  if (card.billing_day == null) return '账单日:未配置'
  return `账单日:每月 ${card.billing_day} 日`
}
export function dueLabel(card: FinanceCard): string {
  if (card.due_day == null) return '还款日:未配置'
  return `还款日:账单后 ${card.due_day} 天`
}

/**
 * 编辑器校验 —— 转账双方账户不同（流水编辑器使用）。
 *
 * @returns errors 字典 —— 空字典表示校验通过
 */
export function validateTxInputs(input: {
  amountYuan: number
  category: string
  kind: 'income' | 'expense' | 'transfer'
  accountId: string | null
  toAccountId: string | null
}): Record<string, string> {
  const e: Record<string, string> = {}
  if (!Number.isFinite(input.amountYuan) || input.amountYuan <= 0)
    e.amount = '金额 > 0'
  const c = input.category.trim()
  if (c.length === 0) e.category = '分类必填'
  else if (c.length > 20) e.category = '分类 ≤20 字符'
  if (input.accountId == null) e.account = '出账方必填'
  if (input.kind === 'transfer') {
    if (input.toAccountId == null) e.toAccount = '入账方必填'
    else if (input.toAccountId === input.accountId)
      e.toAccount = '入账方不能等于出账方'
  }
  return e
}

/**
 * 编辑器校验 —— 卡编辑器 Luhn 通过性 + billingDay / dueDay 区间。
 *
 * 注意：Luhn 校验在调用前完成（luhnValidate），本函数只校验其他字段。
 *   - masked_pan 留空时: 不报错(编辑模式下保留原 last4);
 *   - masked_pan 非空但 Luhn 失败: 报错。
 *
 * @returns errors 字典 —— 空字典表示校验通过
 */
export function validateCardInputs(input: {
  name: string
  billingDay: number | null
  dueDay: number | null
  pan: string
  panLuhnValid: boolean
}): Record<string, string> {
  const e: Record<string, string> = {}
  const n = input.name.trim()
  if (n.length === 0) e.name = '名称必填'
  else if (n.length > 40) e.name = '名称 ≤40 字符'
  if (input.billingDay != null && (input.billingDay < 1 || input.billingDay > 31))
    e.billingDay = '账单日 1-31'
  if (input.dueDay != null && (input.dueDay < 1 || input.dueDay > 31))
    e.dueDay = '还款日偏移 1-31'
  if (input.pan.length > 0 && !input.panLuhnValid)
    e.pan = '卡号未通过 Luhn 校验'
  return e
}
