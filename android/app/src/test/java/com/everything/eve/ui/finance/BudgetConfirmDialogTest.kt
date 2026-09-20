// ============================================================================
// BudgetGate / BudgetConfirmDialog 配套纯 JVM 单元测试（B6 预算硬约束）
// ============================================================================
//
// 路径：android/app/src/test/java/com/everything/eve/ui/finance/BudgetConfirmDialogTest.kt
//
// Compose 对话框本体只能在 androidTest（Compose UI Test）渲染；src/test 是
// 纯 JVM，不可渲染 @Composable。因此本测试把对话框的"是否该弹 / 文案是什么"
// 这条可独立测试的决策面收敛在纯函数 BudgetGate 上断言：
//   1) OK 不弹确认框、不 Toast；
//   2) WARNING 不弹确认框，但需要 Toast；
//   3) BLOCK 弹确认框、不 Toast；
//   4) OK_EMPTY（无任何命中预算）不弹框；
//   5) 零知识文案：仅含百分比；具体分类回显分类标签，"all" 降级"全部支出"；
//   6) confirmText 含百分比且不含分类（拦截正文刻意只给百分比）。
//
// 编码纪律：中文注释；严禁 ASCII 双连字符。
// ============================================================================

package com.everything.eve.ui.finance

import com.everything.eve.finance.BudgetCheckResult
import com.everything.eve.finance.BudgetLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BudgetConfirmDialogTest {

    /** 构造一个指定档位的判定结果（金额等字段与本测试断言无关，给固定值）。 */
    private fun result(
        level: BudgetLevel,
        usedPct: Int,
        category: String = "other",
    ): BudgetCheckResult = BudgetCheckResult(
        level = level,
        budgetId = "budget-1",
        category = category,
        currency = "CNY",
        spentMinor = 0L,
        incomingMinor = 0L,
        projectedMinor = 0L,
        limitMinor = 10_000L,
        usedPct = usedPct,
        thresholdPct = if (level == BudgetLevel.WARNING) 80 else 100,
    )

    // 用例 1：OK 不弹确认框，也不需要预警 Toast。
    @Test
    fun okLevel_doesNotNeedDialogOrToast() {
        val check = result(level = BudgetLevel.OK, usedPct = 50)
        assertFalse("OK 档不应弹超支确认框", BudgetGate.needsConfirmDialog(check))
        assertFalse("OK 档不应弹预警 Toast", BudgetGate.needsWarningToast(check))
    }

    // 用例 2：WARNING 不弹确认框（不拦截），但需要预警 Toast。
    @Test
    fun warningLevel_toastOnly_noDialog() {
        val check = result(level = BudgetLevel.WARNING, usedPct = 90, category = "food")
        assertFalse("WARNING 档不应弹模态确认框", BudgetGate.needsConfirmDialog(check))
        assertTrue("WARNING 档应触发预警 Toast", BudgetGate.needsWarningToast(check))
    }

    // 用例 3：BLOCK 必须弹确认框，且不走轻 Toast（模态优先）。
    @Test
    fun blockLevel_needsDialog_noToast() {
        val check = result(level = BudgetLevel.BLOCK, usedPct = 110, category = "food")
        assertTrue("BLOCK 档必须弹超支确认框", BudgetGate.needsConfirmDialog(check))
        assertFalse("BLOCK 档不应重复弹 Toast", BudgetGate.needsWarningToast(check))
    }

    // 用例 4：OK_EMPTY（无预算命中）不弹框。
    @Test
    fun okEmpty_doesNotNeedDialog() {
        assertFalse("无预算命中时不应弹框", BudgetGate.needsConfirmDialog(BudgetCheckResult.OK_EMPTY))
        assertEquals(BudgetLevel.OK, BudgetCheckResult.OK_EMPTY.level)
    }

    // 用例 5：预警文案零知识：只含百分比 + 分类标签；分类为 "all" 时降级文案。
    @Test
    fun warningText_zeroKnowledge_onlyPctAndCategory() {
        val food = result(level = BudgetLevel.WARNING, usedPct = 90, category = "餐饮")
        val foodText = BudgetGate.warningText(food)
        assertTrue("预警文案应含百分比，实际：$foodText", foodText.contains("90%"))
        assertTrue("具体分类应回显分类标签，实际：$foodText", foodText.contains("餐饮"))

        val all = result(level = BudgetLevel.WARNING, usedPct = 85, category = "all")
        val allText = BudgetGate.warningText(all)
        assertTrue("all 分类应显示全部支出，实际：$allText", allText.contains("全部支出"))
        assertTrue("all 文案应含百分比，实际：$allText", allText.contains("85%"))
    }

    // 用例 6：拦截正文含百分比，且不含分类名（只给百分比这一层信息）。
    @Test
    fun confirmText_containsPctOnly() {
        val check = result(level = BudgetLevel.BLOCK, usedPct = 110, category = "餐饮")
        val text = BudgetGate.confirmText(check)
        assertTrue("确认正文应含百分比，实际：$text", text.contains("110%"))
        assertFalse("确认正文不应回显分类名，实际：$text", text.contains("餐饮"))
    }
}
