/*
 * ============================================================================
 * BudgetConfirmDialog —— B6 预算硬约束"超支确认"对话框
 * ============================================================================
 *
 * 仅当 [BudgetGate.needsConfirmDialog] 为 true（BLOCK 拦截态）时由
 * FinanceEditor 挂载。两条出路：
 *   - "仍保存"：携带 overspendAcknowledged=true 二次提交 saveBuffer，
 *     实体落库时把审计位一并持久化；
 *   - "返回修改"：放弃本次提交，留在编辑器。
 *
 * 零知识铁律：正文只走 [BudgetGate.confirmText]，仅含已用百分比，
 * 不出现金额、日期、卡号、对手方等任何敏感信息。
 * ============================================================================
 */

package com.everything.eve.ui.finance

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import com.everything.eve.R
import com.everything.eve.finance.BudgetCheckResult

/**
 * 超支确认对话框。
 *
 * @param result 闸门判定结果（调用方已确保 level == BLOCK）。
 * @param onConfirm 用户选"仍保存"（二次提交，带审计确认位）。
 * @param onDismiss 用户选"返回修改"或点遮罩取消（本次不落库）。
 */
@Composable
fun BudgetConfirmDialog(
    result: BudgetCheckResult,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.semantics { testTag = "budget_confirm_dialog" },
        title = { Text(stringResource(R.string.finance_budget_confirm_title)) },
        text = {
            // 零知识：正文只含百分比，由纯函数 BudgetGate 统一产出。
            Text(BudgetGate.confirmText(result))
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                modifier = Modifier.semantics { testTag = "budget_confirm_save_btn" },
            ) { Text(stringResource(R.string.finance_budget_confirm_save)) }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.semantics { testTag = "budget_confirm_cancel_btn" },
            ) { Text(stringResource(R.string.finance_budget_confirm_cancel)) }
        },
    )
}
