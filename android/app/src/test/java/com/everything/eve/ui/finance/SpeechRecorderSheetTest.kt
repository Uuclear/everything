// ============================================================================
// SpeechRecorderSheet 配套 Robolectric 组件测试（stage5-finance-v2 / Task 9 / B8）
// ============================================================================
//
// 路径：android/app/src/test/java/com/everything/eve/ui/finance/SpeechRecorderSheetTest.kt
//
// 测试目标（与 OcrScannerSheetTest 同款宿主规则模式）：
//   在不开 includeAndroidResources 的前提下，用 Robolectric 驱动
//   SpeechRecorderSheet 组合，验证：
//     1) 设备不支持离线识别：显示 speech_recorder_unsupported、隐藏
//        speech_recorder_toggle，点关闭触发 onDismiss；
//     2) 未授权：显示 speech_recorder_permission_blocked、隐藏 toggle，
//        点关闭触发 onDismiss；
//     3) 已授权且设备可用：显示 toggle「开始说话」；
//     4) 点开始 → Fake 引擎以 onFinal 回传带金额语句 → 渲染金额与分类预览；
//     5) 点「使用该结果」→ onHint 回调金额分与分类；
//     6) onFinal 回传无金额语句 → 显示 speech_recorder_no_result；
//     7) onError 回传错误码：普通错误显示错误码提示，权限错误（9）显示
//        权限引导文案；
//     8) 无结果 / 错误态点重试可重新开始（Fake 引擎 start 再次被调用）；
//     9) 离开组合：engine.destroy 被调用（资源释放）。
//
// 零知识：Fake 引擎只回传测试预置文本，文本仅在内存中流经 parseSpeechText；
// 测试不验证任何持久化、不打日志；结果节点只断言金额与分类限定要素。
//
// 编码纪律：中文注释；严禁 ASCII 双连字符。
// ============================================================================

package com.everything.eve.ui.finance

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.core.app.ApplicationProvider
import com.everything.eve.finance.SpeechHint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runners.model.Statement
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

// Robolectric 跑在 SDK 33；manifest=NONE：不合并清单，仅做组件级组合测试。
// application 指定下方空实现：本测试仍需经 test_config.properties 解析真实
// 资源（字符串格式断言），Robolectric 会同时按合并清单创建 Application；
// 真实 EveApplication.onCreate 会初始化 AndroidKeyStore（JVM 沙箱无该
// Provider，直接 KeyStoreException），故以不重写 onCreate 的空 Application
// 替换，纯组件测试不需要 ServiceLocator 等真实初始化。
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE, application = SpeechRecorderSheetTest.TestOnlyApplication::class)
class SpeechRecorderSheetTest {

    /**
     * 测试专用 Application：不重写 onCreate，因此不触发 ServiceLocator 初始化
     * 与 AndroidKeyStore 访问；其余系统行为（资源、Context）保持 Robolectric 默认。
     */
    class TestOnlyApplication : Application()

    // Compose 规则内部持有 ActivityScenarioRule，其 before() 会启动宿主
    // Activity；它本身不再标注 @Rule，而是放进下方 RuleChain 的内层。
    private val composeRule = createComposeRule()

    /**
     * 宿主注册规则：在执行链最外层，于 Compose 规则启动 Activity 之前，
     * 向 Robolectric 的影子包管理器注册 androidx.activity.ComponentActivity。
     *
     * 原因：manifest=Config.NONE 时没有合并清单，Compose 规则无法解析宿主
     * Activity（报 Unable to resolve activity）。JUnit 的执行时序是
     * 规则 before() 早于测试类 @Before，因此注册动作不能放在 @Before
     * （那时 Activity 已启动失败），必须以 RuleChain 外层规则提前注入。
     * 这里仅写影子包管理器，不改主清单、不开 includeAndroidResources。
     */
    private class ComposeHostActivityRule : TestRule {
        override fun apply(base: Statement, description: Description): Statement {
            return object : Statement() {
                override fun evaluate() {
                    val app = ApplicationProvider.getApplicationContext<android.app.Application>()
                    val packageManager = Shadows.shadowOf(app.packageManager)
                    // manifest=NONE 时 Robolectric 以固定占位包名
                    // org.robolectric.default 解析宿主组件
                    // （见报错 cmp=org.robolectric.default/...），
                    // 因此注册的 ComponentName 必须落在该占位包下。
                    val component = ComponentName(
                        "org.robolectric.default",
                        "androidx.activity.ComponentActivity",
                    )
                    packageManager.addActivityIfNotPresent(component)
                    // 规则以 ACTION_MAIN + CATEGORY_LAUNCHER 启动，
                    // 过滤器必须带该分类才可解析。
                    packageManager.addIntentFilterForActivity(
                        component,
                        IntentFilter(Intent.ACTION_MAIN).apply {
                            addCategory(Intent.CATEGORY_LAUNCHER)
                        },
                    )
                    base.evaluate()
                }
            }
        }
    }

    // 外层先注册宿主，内层再启动 Compose 与 Activity。
    @get:Rule
    val ruleChain: TestRule = RuleChain
        .outerRule(ComposeHostActivityRule())
        .around(composeRule)

    // ==================================================================
    // Fake 引擎：不触碰系统 SpeechRecognizer / 真机麦克风。
    // ==================================================================
    //
    // 回调时机方案（稳定性考虑）：
    //   start 内【直接同步回调】预置结果。Compose 的 onClick 与状态订阅都
    //   在同一主线程快照环境中执行：点击 → startSession() 先把状态置为
    //   Listening，随后引擎同步回调把状态推进为 Result / NoResult / Error，
    //   点击返回后重组即可读到终态；这与 OcrScannerSheetTest 中 Fake 引擎
    //   recognize 同步回调的风格一致。
    //   若个别场景需要「先 mount 再给结果」，可改预置字段后点重试，
    //   本文件的重试用例即按该方式编排。
    private class FakeSpeechRecorderEngine(
        // 设备是否具备离线识别能力（构造期一次求值，对应接口属性）。
        private val capable: Boolean = true,
        // 预置最终文本；非 null 时 start 经 onFinal 回调。
        var finalText: String? = null,
        // 预置错误码；非 null 且 finalText 为 null 时经 onError 回调。
        var errorCode: Int? = null,
    ) : SpeechRecorderEngine {
        // start 被调用次数：重试场景断言其再次被调用。
        var startCount = 0
        // stop / destroy 调用记录：验证资源释放路径。
        var stopCount = 0
        var destroyed = false

        override val isOnDeviceCapable: Boolean = capable

        override fun start(
            onPartial: (String) -> Unit,
            onFinal: (String?) -> Unit,
            onError: (Int) -> Unit,
        ) {
            startCount++
            when {
                // 优先回调预置最终文本（含无金额文本，由调用方纯函数判定）。
                finalText != null -> onFinal(finalText)
                // 无最终文本但有预置错误码：按错误路径回调。
                errorCode != null -> onError(errorCode!!)
                // 二者皆无：模拟仍在聆听（不回调，等待后续重试切换预置结果）。
                else -> Unit
            }
        }

        override fun stop() {
            stopCount++
        }

        override fun destroy() {
            destroyed = true
        }
    }

    /**
     * 触发节点点击的统一辅助方法。
     *
     * 背景：本工程 Compose 测试版本中的 performClick 走真实触摸注入路径
     * （performTouchInput），而 ModalBottomSheet 的内容位于独立 Dialog
     * 窗口；在 Robolectric 沙箱内，注入的触摸事件不会被分发到该 Dialog
     * 窗口，表现为节点已显示但 onClick 始终不触发。
     * 处理：改为直接触发 Compose 语义树上的 OnClick 动作，其最终调用的
     * 仍是组件真实注册的 onClick 回调（状态机、引擎调用、回调输出等
     * 生产路径完全一致），仅绕开窗口级触摸分发这一环境缺陷。
     * 每次点击后调用方仍需按测试纪律调用 waitForIdle 等待重组完成。
     */
    private fun SemanticsNodeInteraction.activate() {
        performSemanticsAction(SemanticsActions.OnClick)
    }

    /** 挂载 Sheet 并收集 hint、dismiss 回调。 */
    private class Harness {
        var hinted: SpeechHint? = null
        var dismissed = false
    }

    private fun mountSheet(
        engine: SpeechRecorderEngine,
        granted: Boolean?,
    ): Harness {
        val harness = Harness()
        composeRule.setContent {
            SpeechRecorderSheet(
                onHint = { harness.hinted = it },
                onDismiss = { harness.dismissed = true },
                engine = engine,
                initialPermissionGranted = granted,
            )
        }
        composeRule.waitForIdle()
        return harness
    }

    // 用例 1：设备不支持离线识别 → unsupported 说明可见、toggle 不存在，
    // 点关闭触发 onDismiss（不阻断记账）。
    @Test
    fun deviceUnsupported_showsUnsupported_hidesToggle_andDismiss() {
        val engine = FakeSpeechRecorderEngine(capable = false, finalText = "汉堡花了35元")
        val harness = mountSheet(engine, granted = null)

        composeRule.onNodeWithTag("speech_recorder_unsupported")
            .assertIsDisplayed()
        composeRule.onNodeWithTag("speech_recorder_toggle")
            .assertDoesNotExist()

        // 不支持页的关闭按钮可用，证明无识别能力不阻断界面操作。
        composeRule.onNodeWithText("关闭").activate()
        composeRule.waitForIdle()
        assertTrue("点关闭应触发 onDismiss", harness.dismissed)
    }

    // 用例 2：未授权 → permission_blocked 说明可见、toggle 不存在，
    // 点关闭触发 onDismiss。
    @Test
    fun permissionDenied_showsBlocked_hidesToggle_andDismiss() {
        val engine = FakeSpeechRecorderEngine(capable = true, finalText = "汉堡花了35元")
        val harness = mountSheet(engine, granted = false)

        composeRule.onNodeWithTag("speech_recorder_permission_blocked")
            .assertIsDisplayed()
        composeRule.onNodeWithTag("speech_recorder_toggle")
            .assertDoesNotExist()

        composeRule.onNodeWithText("关闭").activate()
        composeRule.waitForIdle()
        assertTrue("点关闭应触发 onDismiss", harness.dismissed)
    }

    // 用例 3：已授权且设备可用 → toggle 显示「开始说话」，无权限阻断。
    @Test
    fun permissionGranted_showsStartToggle() {
        val engine = FakeSpeechRecorderEngine(capable = true)
        mountSheet(engine, granted = true)

        composeRule.onNodeWithTag("speech_recorder_toggle")
            .assertIsDisplayed()
        composeRule.onNodeWithText("开始说话").assertIsDisplayed()
        composeRule.onNodeWithTag("speech_recorder_permission_blocked")
            .assertDoesNotExist()
    }

    // 用例 4：点开始 → onFinal 回传「汉堡花了35元」→ 结果节点渲染
    // 金额 35 与分类「餐饮」。
    @Test
    fun startAndFinal_showsAmountAndCategoryPreview() {
        val engine = FakeSpeechRecorderEngine(capable = true, finalText = "汉堡花了35元")
        mountSheet(engine, granted = true)

        // 点开始（语义动作触发，见 activate 注释），Fake 引擎同步回传最终文本。
        composeRule.onNodeWithTag("speech_recorder_toggle").activate()
        composeRule.waitForIdle()

        // 金额格式串 finance_ai_preview_amount：金额：%1$s 元；
        // 3500 分经 BigDecimal 去尾零展示为「35」。
        composeRule.onNodeWithText("金额：35 元").assertIsDisplayed()
        // 分类格式串 finance_ai_preview_category：分类：%1$s。
        composeRule.onNodeWithText("分类：餐饮").assertIsDisplayed()
        composeRule.onNodeWithTag("speech_recorder_use_hint")
            .assertIsDisplayed()
        assertEquals("应只启动一次识别", 1, engine.startCount)
    }

    // 用例 5：点「使用该结果」→ onHint 回调，金额为 3500 分、分类「餐饮」。
    @Test
    fun useHint_invokesOnHintWithCentsAndCategory() {
        val engine = FakeSpeechRecorderEngine(capable = true, finalText = "汉堡花了35元")
        val harness = mountSheet(engine, granted = true)

        composeRule.onNodeWithTag("speech_recorder_toggle").activate()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("speech_recorder_use_hint").activate()
        composeRule.waitForIdle()

        val hint = harness.hinted
        assertTrue("点使用后应回调 hint", hint != null)
        assertEquals("金额应为 3500 分", 3500L, hint!!.amountMinor)
        assertEquals("分类应为餐饮", "餐饮", hint.category)
    }

    // 用例 6：onFinal 回传无金额语句 → parseSpeechText 返回 null →
    // 显示 speech_recorder_no_result。
    @Test
    fun finalWithoutAmount_showsNoResult() {
        val engine = FakeSpeechRecorderEngine(capable = true, finalText = "今天天气不错")
        mountSheet(engine, granted = true)

        composeRule.onNodeWithTag("speech_recorder_toggle").activate()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("speech_recorder_no_result")
            .assertIsDisplayed()
        composeRule.onNodeWithTag("speech_recorder_use_hint")
            .assertDoesNotExist()
    }

    // 用例 7a：onError 回传普通错误码（7）→ 显示含错误码的通用提示，
    // 并提供重试入口。
    @Test
    fun errorCode7_showsFormattedErrorText() {
        // 普通错误码 7：按 finance_ai_speech_error_format 渲染错误码数字。
        val engine = FakeSpeechRecorderEngine(capable = true, errorCode = 7)
        mountSheet(engine, granted = true)

        composeRule.onNodeWithTag("speech_recorder_toggle").activate()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("未听清，请重试（错误码 7）")
            .assertIsDisplayed()
        // 错误态仍给出重试与关闭入口。
        composeRule.onNodeWithText("重试").assertIsDisplayed()
    }

    // 用例 7b：onError 回传错误码 9（ERROR_INSUFFICIENT_PERMISSIONS）
    // → 显示麦克风权限引导文案，而非通用错误码格式。
    @Test
    fun errorCode9_showsPermissionGuide() {
        val engine = FakeSpeechRecorderEngine(capable = true, errorCode = 9)
        mountSheet(engine, granted = true)

        composeRule.onNodeWithTag("speech_recorder_toggle").activate()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("麦克风权限不足，请在系统设置中开启后重试")
            .assertIsDisplayed()
    }

    // 用例 8：无结果态点重试 → 引擎 start 再次被调用并回传预置成功结果。
    @Test
    fun retryAfterNoResult_startsAgainAndSucceeds() {
        // 初次无预置结果：start 后停留聆听态，随后由 onFinal 空文本思路改为
        // 直接预置无金额语句，先进入 NoResult。
        val engine = FakeSpeechRecorderEngine(capable = true, finalText = "今天天气不错")
        mountSheet(engine, granted = true)

        composeRule.onNodeWithTag("speech_recorder_toggle").activate()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("speech_recorder_no_result").assertIsDisplayed()
        assertEquals("初次启动 1 次", 1, engine.startCount)

        // 切换预置成功文本，点重试后再次 start 并同步回调。
        engine.finalText = "汉堡花了35元"
        composeRule.onNodeWithText("重试").activate()
        composeRule.waitForIdle()

        assertEquals("重试应再次调用 start", 2, engine.startCount)
        composeRule.onNodeWithText("金额：35 元").assertIsDisplayed()
        composeRule.onNodeWithTag("speech_recorder_no_result").assertDoesNotExist()
    }

    // 用例 9：错误态点重试 → 引擎 start 再次被调用，状态由 Error 推进到 Result。
    @Test
    fun retryAfterError_startsAgainAndSucceeds() {
        val engine = FakeSpeechRecorderEngine(capable = true, errorCode = 7)
        mountSheet(engine, granted = true)

        composeRule.onNodeWithTag("speech_recorder_toggle").activate()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("未听清，请重试（错误码 7）")
            .assertIsDisplayed()
        assertEquals("初次启动 1 次", 1, engine.startCount)

        // 清除错误码并预置成功文本，点重试重新走最终结果路径。
        engine.errorCode = null
        engine.finalText = "汉堡花了35元"
        composeRule.onNodeWithText("重试").activate()
        composeRule.waitForIdle()

        assertEquals("重试应再次调用 start", 2, engine.startCount)
        composeRule.onNodeWithText("分类：餐饮").assertIsDisplayed()
    }

    // 用例 10：离开组合时调用 engine.destroy（识别服务资源释放）。
    @Test
    fun dispose_destroysEngine() {
        val engine = FakeSpeechRecorderEngine(capable = true, finalText = "汉堡花了35元")
        val harness = Harness()

        // 用一个外部快照状态控制 Sheet 是否在组合中。测试线程直接写入该
        // 状态即可驱动重组：置 false 后 Sheet 离开组合，从而触发其
        // DisposableEffect 的 onDispose。不能用第二次 setContent 清空
        // （Compose 测试规则一次测试只允许 setContent 一次）。
        var visible by mutableStateOf(true)
        composeRule.setContent {
            if (visible) {
                SpeechRecorderSheet(
                    onHint = { harness.hinted = it },
                    onDismiss = { harness.dismissed = true },
                    engine = engine,
                    initialPermissionGranted = true,
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("speech_recorder_toggle").activate()
        composeRule.waitForIdle()
        assertTrue("识别完成前不应提前销毁引擎", !engine.destroyed)

        // 翻转为不在组合：onDispose 应同步调用 engine.destroy。
        visible = false
        composeRule.waitForIdle()
        assertTrue("离开组合应销毁引擎", engine.destroyed)
    }
}
