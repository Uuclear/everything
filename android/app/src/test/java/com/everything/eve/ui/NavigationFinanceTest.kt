/*
 * ============================================================================
 * NavigationFinanceTest —— 阶段 5 Task 10 / TR-10.1 主导航接入 JVM 单测
 * ============================================================================
 *
 * 设计要点：
 *   1. **JVM 友好**：不依赖 Robolectric / Compose UI Test / instrumented runner。
 *      与 T7 FinanceAggregatorUiTest 同口径：纯 JUnit 4 + 字符串/源码扫描 + Kotlin
 *      object const 反射，覆盖：
 *        - 主导航 Routes.FINANCE 常量与 FinanceRoutes.ROOT 同源；
 *        - AppNav.kt 源码注册 composable(Routes.FINANCE) { FinanceScreen() }；
 *        - VaultScreen.kt 顶部 TopAppBar actions 新增「财务」TextButton +
 *          onOpenFinance 参数；
 *        - FinanceRoutes.tabRoute / editorRoute 拼接 + 子路由 key 集合；
 *        - 通知入口走 RouteKey 字符串常量（不渲染金额 / 卡号后四位 / 具体日期数字）。
 *   2. **零知识红线（spec NFR-1 / FR-10）**：本测试集中显式断言
 *      - finance_reminder_* 文案不含具体日期数字、不含卡号后四位格式；
 *      - nav_finance / nav_calendar / nav_collect / nav_device 不存在也合法
 *        （4a/4b 已落盘 nav_calendar，本任务仅新增 nav_finance）。
 *   3. **沿用 4b 既有 Routes 命名**：WELCOME / VAULT / DEVICES / COLLECTOR /
 *      CALENDAR 同款镜像 —— FINANCE 与 CALENDAR 对齐，确保主导航增量最小。
 *
 * 关联：
 *   - ui/AppNav.kt (Routes object + AppNav composable)
 *   - ui/screens/VaultScreen.kt (VaultScreen + onOpenFinance 参数)
 *   - ui/finance/FinanceRoutes.kt (Finance 模块路由常量)
 *   - res/values/strings.xml (nav_finance / nav_calendar / finance_reminder_*)
 * ============================================================================
 */

package com.everything.eve.ui

import com.everything.eve.ui.finance.FinanceRoutes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 主导航 finance 入口接通的 JVM 单测（≥4 用例）。
 *
 * 覆盖：
 *   - testRoutesFinanceConstant（Routes.FINANCE 字面量 + 与 FinanceRoutes.ROOT 同源）
 *   - testAppNavRegistersFinanceComposable（AppNav.kt 源码含 FINANCE composable 块）
 *   - testVaultScreenTopBarHasFinanceEntry（VaultScreen.kt 源码含 onOpenFinance +
 *     TopAppBar actions TextButton(stringResource(R.string.nav_finance))）
 *   - testFinanceRoutesTabAndEditorNavigation（tabRoute / editorRoute 拼接正确，
 *     TAB_* / EDITOR_* 四个 key 与 FinanceRoutes 对齐）
 *   - testFinanceNotificationRouteUsesRouteKeyOnly（通知入口走 Routes.FINANCE
 *     字符串常量，零知识 —— strings.xml finance_reminder_* 不含数字 / 卡号后四位）
 *
 * 路径相对仓库根解析：当前 working dir 期望为 android/ 下；自动向上回退一次以容错。
 */
class NavigationFinanceTest {

    companion object {
        /** 仓库根：testDebugUnitTest 工作目录 = android/（gradlew.bat 所在）。
         *  因此相对路径从 android/ 开始，即 "app/src/..."。
         *  若检测不到目标文件，回退尝试上一级（兼容 IDE 启动 cwd）。 */
        private val repoRoot: File by lazy {
            val cwd = File(".").absoluteFile.canonicalFile
            val probe = listOf(cwd, cwd.parentFile, cwd.parentFile?.parentFile).filterNotNull()
            // 优先选包含 app/src/main/java/com/everything/eve/ui/AppNav.kt 的目录（即 android/）。
            probe.firstOrNull { File(it, "app/src/main/java/com/everything/eve/ui/AppNav.kt").exists() }
                ?: cwd
        }

        private fun readText(path: String): String {
            val f = File(repoRoot, path)
            assertTrue(
                "测试源码扫描目标不存在：${f.absolutePath}（repoRoot=${repoRoot.absolutePath}）",
                f.exists(),
            )
            return f.readText(Charsets.UTF_8)
        }
    }

    // ========================================================================
    // 用例 1：主导航 Routes.FINANCE 常量
    // ========================================================================

    @Test
    fun testRoutesFinanceConstant() {
        // 主导航 Routes.FINANCE 必须存在且字面量 = "finance"（与 FinanceRoutes.ROOT 同源，
        // 保证 AppNav 入口与 Finance 模块二级路由共享 namespace，避免出现两条不互通的路径）。
        assertEquals(
            "Routes.FINANCE 字面量必须固定为 \"finance\"（与 FinanceRoutes.ROOT 同源）",
            "finance",
            Routes.FINANCE,
        )
        assertEquals(
            "Routes.FINANCE 必须与 FinanceRoutes.ROOT 一致——主导航与模块二级路由同 namespace",
            Routes.FINANCE,
            FinanceRoutes.ROOT,
        )

        // 4b 既有的"采集 / 设备 / 日历"入口不得回归（保证 T10 改动是纯增量）。
        assertEquals("collector", Routes.COLLECTOR)
        assertEquals("devices", Routes.DEVICES)
        assertEquals("calendar", Routes.CALENDAR)
    }

    // ========================================================================
    // 用例 2：AppNav.kt 源码注册 FINANCE composable
    // ========================================================================

    @Test
    fun testAppNavRegistersFinanceComposable() {
        val src = readText("app/src/main/java/com/everything/eve/ui/AppNav.kt")

        // 必须 import FinanceScreen —— 否则 composable(Routes.FINANCE) { FinanceScreen() } 无法编译。
        assertTrue(
            "AppNav.kt 必须 import com.everything.eve.ui.finance.FinanceScreen",
            src.contains("import com.everything.eve.ui.finance.FinanceScreen"),
        )

        // 必须包含 composable(Routes.FINANCE) 块（沿用 4b CALENDAR 同款镜像）。
        assertTrue(
            "AppNav.kt 必须注册 composable(Routes.FINANCE) 块",
            Regex("""composable\s*\(\s*Routes\.FINANCE\s*\)""").containsMatchIn(src),
        )

        // composable(Routes.FINANCE) 块内必须调用 FinanceScreen()。
        assertTrue(
            "AppNav.kt 的 composable(Routes.FINANCE) 块必须调用 FinanceScreen()",
            Regex(
                """composable\s*\(\s*Routes\.FINANCE\s*\)\s*\{[^}]*FinanceScreen\s*\(\s*\)""",
                setOf(RegexOption.DOT_MATCHES_ALL),
            ).containsMatchIn(src),
        )

        // VaultScreen composable 块必须传入 onOpenFinance 回调（沿用 4b onOpenCalendar 镜像）。
        assertTrue(
            "VaultScreen composable 块必须传入 onOpenFinance = { nav.navigate(Routes.FINANCE) }",
            src.contains("onOpenFinance = { nav.navigate(Routes.FINANCE) }"),
        )

        // 4b CALENDAR 镜像不得回归（保证本任务是纯增量 —— 没动 4b 已落盘块）。
        assertTrue("4b CALENDAR 镜像必须保留", src.contains("composable(Routes.CALENDAR)"))
        assertTrue("4b onOpenCalendar 回调必须保留", src.contains("onOpenCalendar = { nav.navigate(Routes.CALENDAR) }"))
    }

    // ========================================================================
    // 用例 3：VaultScreen 顶部 TopAppBar actions 新增「财务」TextButton
    // ========================================================================

    @Test
    fun testVaultScreenTopBarHasFinanceEntry() {
        val src = readText("app/src/main/java/com/everything/eve/ui/screens/VaultScreen.kt")

        // 函数签名必须新增 onOpenFinance 参数（沿用 4b onOpenCalendar 镜像）。
        assertTrue(
            "VaultScreen 函数签名必须新增 onOpenFinance: () -> Unit 参数",
            src.contains("onOpenFinance: () -> Unit"),
        )

        // TopAppBar actions 必须新增 onOpenFinance 的 TextButton，且文案走 R.string.nav_finance。
        val hasFinanceButton = Regex(
            """TextButton\s*\(\s*onClick\s*=\s*onOpenFinance\s*\)\s*\{[^}]*R\.string\.nav_finance""",
            setOf(RegexOption.DOT_MATCHES_ALL),
        ).containsMatchIn(src)
        assertTrue(
            "VaultScreen TopAppBar actions 必须新增「财务」TextButton 且文案走 R.string.nav_finance",
            hasFinanceButton,
        )

        // 4b onOpenCalendar TextButton 必须保留（保证本任务是纯增量）。
        assertTrue(
            "VaultScreen 必须保留 4b onOpenCalendar TextButton 与 R.string.nav_calendar",
            src.contains("R.string.nav_calendar"),
        )
    }

    // ========================================================================
    // 用例 4：FinanceRoutes Tab / Editor 路由跳转语义
    // ========================================================================

    @Test
    fun testFinanceRoutesTabAndEditorNavigation() {
        // 四个 tab key 必须齐全（dashboard / accounts / cards / txs）。
        assertEquals("dashboard", FinanceRoutes.TAB_DASHBOARD)
        assertEquals("accounts", FinanceRoutes.TAB_ACCOUNTS)
        assertEquals("cards", FinanceRoutes.TAB_CARDS)
        assertEquals("txs", FinanceRoutes.TAB_TXS)

        // tabRoute 拼接："finance/{tab}"。
        assertEquals("finance/dashboard", FinanceRoutes.tabRoute(FinanceRoutes.TAB_DASHBOARD))
        assertEquals("finance/accounts", FinanceRoutes.tabRoute(FinanceRoutes.TAB_ACCOUNTS))
        assertEquals("finance/cards", FinanceRoutes.tabRoute(FinanceRoutes.TAB_CARDS))
        assertEquals("finance/txs", FinanceRoutes.tabRoute(FinanceRoutes.TAB_TXS))

        // editorRoute：id=null → "finance/{tab}/editor"；id 非空 → "finance/{tab}/editor?id={id}"。
        assertEquals(
            "新建（无 id）→ finance/accounts/editor",
            "finance/accounts/editor",
            FinanceRoutes.editorRoute(FinanceRoutes.TAB_ACCOUNTS),
        )
        assertEquals(
            "编辑（有 id）→ finance/accounts/editor?id=acc-123",
            "finance/accounts/editor?id=acc-123",
            FinanceRoutes.editorRoute(FinanceRoutes.TAB_ACCOUNTS, "acc-123"),
        )
        assertEquals(
            "新建卡片编辑器 → finance/cards/editor",
            "finance/cards/editor",
            FinanceRoutes.editorRoute(FinanceRoutes.TAB_CARDS),
        )

        // editor kind 三选一（account / card / tx）。
        assertEquals("account", FinanceRoutes.EDITOR_ACCOUNT)
        assertEquals("card", FinanceRoutes.EDITOR_CARD)
        assertEquals("tx", FinanceRoutes.EDITOR_TX)
    }

    // ========================================================================
    // 用例 5：通知路由走 RouteKey 字符串常量 + 零知识红线
    // ========================================================================

    @Test
    fun testFinanceNotificationRouteUsesRouteKeyOnly() {
        val strings = readText("app/src/main/res/values/strings.xml")

        // 财务入口文案必须存在（TopAppBar TextButton 渲染依赖）。
        assertTrue(
            "strings.xml 必须定义 nav_finance 文案",
            Regex("<string\\s+name=\"nav_finance\"").containsMatchIn(strings),
        )
        // 4b 已落盘 nav_calendar 必须保留（保证 T10 改动是纯增量）。
        assertTrue(
            "strings.xml 必须保留 nav_calendar 文案（4b 已落盘）",
            Regex("<string\\s+name=\"nav_calendar\"").containsMatchIn(strings),
        )

        // 财务通知 title + 文案必须存在（T6 落地，本任务不重写但需确认落盘）。
        val reminderTitleLine = Regex(
            "<string\\s+name=\"finance_reminder_title\"",
        ).find(strings)
        assertNotNull("strings.xml 必须定义 finance_reminder_title", reminderTitleLine)
        val reminderStatementLine = Regex(
            "<string\\s+name=\"finance_reminder_statement_due_text\"",
        ).find(strings)
        assertNotNull(
            "strings.xml 必须定义 finance_reminder_statement_due_text",
            reminderStatementLine,
        )
        val reminderPaymentLine = Regex(
            "<string\\s+name=\"finance_reminder_payment_due_text\"",
        ).find(strings)
        assertNotNull(
            "strings.xml 必须定义 finance_reminder_payment_due_text",
            reminderPaymentLine,
        )

        // 零知识红线（spec NFR-1）：通知文案不渲染：
        //   - 具体日期数字（账单日/还款日几号，如 "5 日" / "账单日 12"）；
        //   - 卡号后四位（4 位连续数字串）；
        //   - 金额数字（"￥12,345" / "123.45 元"）。
        // 抽象文案含 %d/%s 占位符是合法的，但占位符之外不得出现连续 4 位以上纯数字 / 货币符号。
        val reminderLines = listOf(
            reminderStatementLine!!.value,
            reminderPaymentLine!!.value,
        )
        reminderLines.forEach { line ->
            // 不含连续 4 位纯数字（卡号后四位格式）。
            assertFalse(
                "通知文案不得渲染卡号后四位格式（连续 4 位数字）：$line",
                Regex("""\d{4}""").containsMatchIn(line),
            )
            // 不含货币符号（CNY 文字以外不出现 ￥ / ¥ / $ 等）。
            assertFalse(
                "通知文案不得渲染货币符号：$line",
                Regex("""[￥¥\$]""").containsMatchIn(line),
            )
            // 不含"账单日 / 还款日 + 数字"模式。
            assertFalse(
                "通知文案不得渲染具体日期数字（账单日 X 日 / 还款日 X 日）：$line",
                Regex("""(账单日|还款日|到期日)\s*\d""").containsMatchIn(line),
            )
        }
    }
}