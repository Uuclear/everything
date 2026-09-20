// ============================================================================
// BudgetGate —— 财务 v2 B6 预算超支拦截 UI 门控纯函数（Web）
// ============================================================================
//
// 任务: stage5-finance-v2 / Task 6（预算硬约束 + 超支拦截）Web 集成层
// 路径: web/src/finance/budgetGate.ts
// 作用: 把 budgetEnforcer 的三档判定结果（OK / WARNING / BLOCK）映射为
//       UI 门控动作与零知识文案：
//         - BLOCK   → 弹“超支确认”模态，用户显式确认后方可保存；
//         - WARNING → 保存后以 toast 提示接近预算上限；
//         - OK      → 无额外门控。
//
// 铁律口径（与 Android 镜像语义一致）:
//   1. 纯函数模块：不依赖 store / 路由 / DOM / Network，输入结果对象，
//      输出布尔判定与中文文案，便于单测双端锁定；
//   2. 零知识文案：文案中只允许出现“百分比 + 分类名”，禁止金额、日期、
//      卡号、对手方等任何敏感数值；分类为 'all' 时用“全部支出”统称；
//   3. 百分比直接取 BudgetCheckResult.usedPct（bigint 整除向下取整后的
//      整数），本模块不做任何数值运算。
//
// 关联:
//   - web/src/finance/budgetEnforcer.ts（BudgetCheckResult / BudgetLevel）
//   - web/src/views/finance/BudgetConfirmDialog.vue（BLOCK 模态）
//   - web/src/views/finance/FinanceTxEditor.vue（门控调用方）
// ============================================================================

import type { BudgetCheckResult } from './budgetEnforcer'

/**
 * 是否需要弹“超支确认”模态。
 *
 * 仅 BLOCK 档需要阻断式确认；WARNING / OK 均不打断保存流程。
 */
export function needsConfirmDialog(r: BudgetCheckResult): boolean {
  return r.level === 'BLOCK'
}

/**
 * 是否需要在保存成功后给 WARNING toast。
 *
 * 仅 WARNING 档提示；BLOCK 已由确认模态承接，OK 不提示。
 */
export function needsWarningToast(r: BudgetCheckResult): boolean {
  return r.level === 'WARNING'
}

/**
 * BLOCK 模态正文文案（零知识：仅百分比，无金额 / 日期）。
 */
export function confirmText(r: BudgetCheckResult): string {
  return `预计已用 ${r.usedPct}%，超过预算阈值，是否仍保存？`
}

/**
 * WARNING toast 文案（零知识：仅百分比 + 分类名）。
 *
 * 预算分类为特殊值 'all' 时统称“全部支出”，否则展示具体分类名。
 */
export function warningText(r: BudgetCheckResult): string {
  return r.category === 'all'
    ? `本月全部支出已用 ${r.usedPct}%，接近预算上限`
    : `本月${r.category}已用 ${r.usedPct}%，接近预算上限`
}
