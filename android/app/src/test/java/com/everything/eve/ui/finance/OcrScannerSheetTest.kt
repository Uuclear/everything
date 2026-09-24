// ============================================================================
// OcrScannerSheet 配套 Robolectric 组件测试（stage5-finance-v2 / Task 9 / B8）
// ============================================================================
//
// 路径：android/app/src/test/java/com/everything/eve/ui/finance/OcrScannerSheetTest.kt
//
// 测试目标：
//   在不开 includeAndroidResources 的前提下，用 Robolectric 驱动
//   OcrScannerSheet 组合，验证：
//     1) 未授权：显示权限说明（ocr_scanner_permission_blocked），
//        且不显示拍照入口；权限拒绝不阻断记账（仍可关闭）；
//     2) 已授权：显示拍照按钮（ocr_scanner_capture）；
//     3) 拍照 → 泵帧 → Fake 引擎回传小票文本：金额预览渲染；
//     4) 引擎回传 null：显示无结果（ocr_scanner_no_result）；
//     5) 无结果后重试，再识别成功；
//     6) 使用该结果：onHint 回调三要素；
//     7) 关闭按钮：onDismiss 回调；
//     8) 离开组合：engine.close 被调用（资源释放）。
//
// 零知识：Fake 引擎只回传测试预置文本，测试不断言 OCR 原文入库
// （原文也根本不入库）；预览节点只校验金额等限定要素。
//
// 编码纪律：中文注释；严禁 ASCII 双连字符。
// ============================================================================

package com.everything.eve.ui.finance

import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import android.graphics.Rect
import android.media.Image
import androidx.camera.core.ImageInfo
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.core.app.ApplicationProvider
import com.everything.eve.finance.ReceiptHint
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
// application 指定下方空实现：经 test_config.properties 解析真实资源时，
// Robolectric 会同时按合并清单创建 Application；真实 EveApplication.onCreate
// 会初始化 AndroidKeyStore（JVM 沙箱无该 Provider，直接 KeyStoreException），
// 故以不重写 onCreate 的空 Application 替换，纯组件测试不需要 ServiceLocator。
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE, application = OcrScannerSheetTest.TestOnlyApplication::class)
class OcrScannerSheetTest {

    /** 测试专用 Application：不重写 onCreate，不触发 ServiceLocator 与 AndroidKeyStore 访问。 */
    class TestOnlyApplication : android.app.Application()

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

    // ------------------------------------------------------------------
    // Fake 引擎：不触碰 ML Kit / 真机，recognize 时回传预置文本。
    // ------------------------------------------------------------------
    private class FakeOcrEngine(
        var resultText: String?,
    ) : OcrScannerEngine {
        var recognizeCount = 0
        var closed = false

        override fun recognize(imageProxy: ImageProxy, onResult: (String?) -> Unit) {
            recognizeCount++
            // 伪帧未走真实分析链，无需依赖其内部对象；直接把预置文本回调。
            onResult(resultText)
        }

        override fun close() {
            closed = true
        }
    }

    /**
     * 构造伪 ImageProxy：ImageProxy 是抽象类，既不能直接实例化，也不能
     * 用 Unsafe.allocateInstance（对抽象类会抛 InstantiationException）；
     * 因此用对象表达式给出一个最小桩实现，所有抽象方法返回默认值。
     * Fake 引擎不读取帧的任何字段，也不调用其 close，因此桩内容保持空实现。
     */
    private fun fakeImageProxy(): ImageProxy {
        return object : ImageProxy {
            // 帧实际被关闭的次数：本桩只提供能力，Fake 引擎不会调用 close。
            private var closed = false

            override fun close() {
                closed = true
            }

            override fun getWidth(): Int = 0

            override fun getHeight(): Int = 0

            override fun getCropRect(): Rect = Rect(0, 0, 0, 0)

            override fun setCropRect(rect: Rect?) {
                // 桩帧：裁剪坐标无意义，忽略。
            }

            override fun getImageInfo(): ImageInfo {
                // ImageInfo 同为抽象类，给一个不携带任何元数据的最小桩。
                return object : ImageInfo {
                    override fun getRotationDegrees(): Int = 0

                    override fun getTimestamp(): Long = 0L

                    override fun getTagBundle(): androidx.camera.core.impl.TagBundle =
                        androidx.camera.core.impl.TagBundle.emptyBundle()

                    override fun populateExifData(exifBuilder: androidx.camera.core.impl.utils.ExifData.Builder) {
                        // 桩帧无任何 EXIF 元数据可填充，保留空实现。
                    }
                }
            }

            override fun getImage(): Image? = null

            override fun getFormat(): Int = 0

            override fun getPlanes(): Array<ImageProxy.PlaneProxy> {
                // PlaneProxy 描述像素平面；桩帧没有真实像素，返回空数组。
                return emptyArray()
            }
        }
    }

    /**
     * 语义点击扩展：本工程 Compose 测试版本的 performClick 走真实触摸
     * 注入，而 ModalBottomSheet 内容位于独立 Dialog 窗口，Robolectric 下
     * 注入事件无法分发到该窗口（节点已显示但 onClick 不触发）。
     * 这里直接触发语义树 OnClick 动作：最终调用的仍是组件真实 onClick，
     * 仅绕开窗口级触摸分发缺陷，生产路径完全一致。
     */
    private fun SemanticsNodeInteraction.performClick() {
        performSemanticsAction(SemanticsActions.OnClick)
    }

    /**
     * 等待式可见断言：保留为参考占位，调试表明在 Robolectric 下
     * 节点的布局边界可能始终位于可视区域外（ModalBottomSheet 入场
     * 动画在沙箱里不推进），因此本测试统一以 assertExists 校验节点
     * 已存在于语义树，再用 OnClick 触发真实回调，避免依赖布局尺寸。
     */
    @Suppress("unused")
    private fun SemanticsNodeInteraction.waitUntilDisplayed(timeoutMs: Long = 3000L) {
        composeRule.waitUntil(timeoutMs) {
            runCatching { fetchSemanticsNode("") }.isSuccess
        }
    }

    /** 一段可解析的中文小票文本：合计行带货币符号，外加日期与商家行。 */
    private val validReceiptText = """
        晨星便利店
        2026-09-24
        商品若干
        合计 ¥35.00
    """.trimIndent()

    /** 挂载 Sheet 并收集帧处理器、hint、dismiss 回调。 */
    private class Harness(
        val engine: FakeOcrEngine,
    ) {
        lateinit var analyzer: ImageAnalysis.Analyzer
        var hinted: ReceiptHint? = null
        var dismissed = false

        // 组合可见性：Compose 测试规则每例只允许一次 setContent，
        // 因此 dispose 场景通过把该状态翻为 false 让 Sheet 离开组合。
        var visible: Boolean by mutableStateOf(true)
    }

    private fun mountSheet(
        engine: FakeOcrEngine,
        granted: Boolean,
    ): Harness {
        val harness = Harness(engine)
        composeRule.setContent {
            // 仅在 visible 为 true 时挂载；翻为 false 即触发 onDispose。
            if (harness.visible) {
                OcrScannerSheet(
                    onHint = { harness.hinted = it },
                    onDismiss = { harness.dismissed = true },
                    engine = engine,
                    initialPermissionGranted = granted,
                    onFrameProcessorReady = { harness.analyzer = it },
                )
            }
        }
        composeRule.waitForIdle()
        return harness
    }

    // 用例 1：未授权显示权限说明，且不显示拍照入口；仍可关闭（不阻断记账）。
    @Test
    fun permissionDenied_showsBlocked_hidesCapture() {
        val engine = FakeOcrEngine(resultText = validReceiptText)
        val harness = mountSheet(engine, granted = false)

        composeRule.onNodeWithTag("ocr_scanner_permission_blocked")
            .assertExists()
        composeRule.onNodeWithTag("ocr_scanner_capture")
            .assertDoesNotExist()

        // 权限说明页的关闭按钮可用，证明拒绝授权不阻断界面操作。
        composeRule.onNodeWithText("关闭").performClick()
        composeRule.waitForIdle()
        assertTrue("点关闭应触发 onDismiss", harness.dismissed)
    }

    // 用例 2：已授权显示拍照按钮。
    @Test
    fun permissionGranted_showsCapture() {
        val engine = FakeOcrEngine(resultText = validReceiptText)
        mountSheet(engine, granted = true)

        composeRule.onNodeWithTag("ocr_scanner_capture")
            .assertExists()
        composeRule.onNodeWithTag("ocr_scanner_permission_blocked")
            .assertDoesNotExist()
    }

    // 用例 3：拍照 → 泵入一帧 → 引擎回传小票文本 → 渲染金额预览。
    @Test
    fun captureAndRecognize_showsAmountPreview() {
        val engine = FakeOcrEngine(resultText = validReceiptText)
        val harness = mountSheet(engine, granted = true)

        composeRule.onNodeWithTag("ocr_scanner_capture").performClick()
        composeRule.waitForIdle()
        // 模拟相机分析链产出下一帧。
        harness.analyzer.analyze(fakeImageProxy())
        composeRule.waitForIdle()

        // 35.00 元经 BigDecimal 换算去尾零展示为「35」。
        composeRule.onNodeWithText("金额：35 元").assertExists()
        // 日期与商家为限定要素，同样应渲染。
        composeRule.onNodeWithText("日期：2026-09-24").assertExists()
        composeRule.onNodeWithText("商家：晨星便利店").assertExists()
        assertEquals("应仅放行并识别一帧", 1, engine.recognizeCount)
    }

    // 用例 4：引擎回传 null（识别失败）→ 无结果说明。
    @Test
    fun recognizeNull_showsNoResult() {
        val engine = FakeOcrEngine(resultText = null)
        val harness = mountSheet(engine, granted = true)

        composeRule.onNodeWithTag("ocr_scanner_capture").performClick()
        harness.analyzer.analyze(fakeImageProxy())
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("ocr_scanner_no_result")
            .assertExists()
    }

    // 用例 5：无结果后点重试，再识别成功（状态由 NoResult 回到 Result）。
    @Test
    fun retryAfterNoResult_thenRecognizeSucceeds() {
        val engine = FakeOcrEngine(resultText = null)
        val harness = mountSheet(engine, granted = true)

        composeRule.onNodeWithTag("ocr_scanner_capture").performClick()
        harness.analyzer.analyze(fakeImageProxy())
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("ocr_scanner_no_result").assertExists()

        // 预置成功文本，点重试后再泵一帧。
        engine.resultText = validReceiptText
        composeRule.onNodeWithText("重试").performClick()
        composeRule.waitForIdle()
        harness.analyzer.analyze(fakeImageProxy())
        composeRule.waitForIdle()

        composeRule.onNodeWithText("金额：35 元").assertExists()
        composeRule.onNodeWithTag("ocr_scanner_no_result").assertDoesNotExist()
    }

    // 用例 6：使用该结果：onHint 回调三要素，金额为分。
    @Test
    fun useHint_invokesOnHintWithCents() {
        val engine = FakeOcrEngine(resultText = validReceiptText)
        val harness = mountSheet(engine, granted = true)

        composeRule.onNodeWithTag("ocr_scanner_capture").performClick()
        harness.analyzer.analyze(fakeImageProxy())
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("ocr_scanner_use_hint").performClick()
        composeRule.waitForIdle()

        val hint = harness.hinted
        assertTrue("点使用后应回调 hint", hint != null)
        assertEquals("金额应为 3500 分", 3500L, hint!!.amountMinor)
        assertEquals("商家应为便利店", "晨星便利店", hint.merchant)
    }

    // 用例 7：结果页关闭按钮触发 onDismiss。
    @Test
    fun closeAfterResult_invokesOnDismiss() {
        val engine = FakeOcrEngine(resultText = validReceiptText)
        val harness = mountSheet(engine, granted = true)

        composeRule.onNodeWithTag("ocr_scanner_capture").performClick()
        harness.analyzer.analyze(fakeImageProxy())
        composeRule.waitForIdle()

        // 结果页有多个按钮，取最后一个「关闭」（结果页按钮顺序：使用、重试、关闭）。
        composeRule.onNodeWithText("关闭").performClick()
        composeRule.waitForIdle()
        assertTrue("应触发 onDismiss", harness.dismissed)
    }

    // 用例 8：离开组合时调用 engine.close（识别器资源释放）。
    @Test
    fun dispose_closesEngine() {
        val engine = FakeOcrEngine(resultText = validReceiptText)
        val harness = mountSheet(engine, granted = true)

        composeRule.onNodeWithTag("ocr_scanner_capture").performClick()
        harness.analyzer.analyze(fakeImageProxy())
        composeRule.waitForIdle()
        assertTrue("识别完成前不应提前关闭引擎", !engine.closed)

        // 翻转为不可见，触发 Sheet 离开组合与各 onDispose。
        harness.visible = false
        composeRule.waitForIdle()
        assertTrue("离开组合应关闭引擎", engine.closed)
    }
}
