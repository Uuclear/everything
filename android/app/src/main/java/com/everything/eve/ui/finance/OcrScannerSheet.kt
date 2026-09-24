/*
 * ============================================================================
 * OcrScannerSheet —— 小票扫描底部弹层（stage5-finance-v2 / Task 9 / B8 批次）
 * ============================================================================
 *
 * 任务：stage5-finance-v2 / Task 9 / B8（Compose Sheet 层）
 * 路径：android/app/src/main/java/com/everything/eve/ui/finance/OcrScannerSheet.kt
 *
 * 作用：
 *   以 ModalBottomSheet 形态承载「对准小票 → 拍照识别 → 预览三要素 →
 *   回填编辑器」的交互。相机预览用 CameraX PreviewView，识别用
 *   CameraX ImageAnalysis（KEEP_ONLY_LATEST）取最新一帧交给
 *   [OcrScannerEngine]（生产为 ML Kit 中文识别），识别原文再经
 *   finance.parseReceiptText 纯函数解析为 ReceiptHint。
 *
 * 设计要点：
 *   1. 权限：CAMERA 为运行时权限，打开 Sheet 时通过
 *      rememberLauncherForActivityResult 申请；被拒绝仅展示本地用途
 *      说明（ocr_scanner_permission_blocked）与关闭入口，绝不阻断
 *      手工记账。
 *   2. 帧纪律：未处于「待识别」状态时，analyzer 必须立刻 close 帧；
 *      用户点「拍照识别」后仅放行一帧（AtomicBoolean 单次握手），
 *      帧的最终 close 由引擎实现保证（成功失败都归还）。
 *   3. 零知识：预览只渲染金额、日期（yyyy-MM-dd）、商家三项限定
 *      要素；识别原文不入库、不写日志、不随任何状态持久化；用户
 *      只有在编辑器点保存时才走既有加密 saveBuffer。
 *   4. 资源释放：DisposableEffect 中解绑相机生命周期用例并调用
 *      engine.close()，避免相机 / 识别器泄漏。
 *
 * 测试接缝（内部参数，生产调用均不传）：
 *   - initialPermissionGranted：绕过真实权限检查与系统授权弹窗；
 *   - onFrameProcessorReady：Robolectric 无真机相机，测试借此拿到
 *     ImageAnalysis.Analyzer 并泵入伪帧驱动识别流程。
 *
 * 关联：
 *   - ui/finance/OcrScannerEngine.kt（识别引擎）
 *   - finance/OcrParser.kt（parseReceiptText 纯函数）
 *   - ui/finance/FinanceEditor.kt（入口按钮与 hint 回填）
 * ============================================================================
 */

package com.everything.eve.ui.finance

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.everything.eve.R
import com.everything.eve.finance.ReceiptHint
import com.everything.eve.finance.parseReceiptText
import java.math.BigDecimal
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Sheet 的界面状态机：
 *  - [OcrSheetState.Idle]：尚未发起识别，展示「拍照识别」按钮；
 *  - [OcrSheetState.Recognizing]：已放行一帧、等待识别结果，展示「识别中」；
 *  - [OcrSheetState.Result]：成功解析出至少一个要素，展示三要素预览；
 *  - [OcrSheetState.NoResult]：识别失败或三要素全空，展示无结果说明与重试。
 */
private sealed interface OcrSheetState {
    data object Idle : OcrSheetState
    data object Recognizing : OcrSheetState
    data class Result(val hint: ReceiptHint) : OcrSheetState
    data object NoResult : OcrSheetState
}

/**
 * 小票扫描底部弹层。
 *
 * @param onHint 用户点「使用该结果」时回调解析出的三要素（仅回调，
 *   是否关闭由调用方决定）。
 * @param onDismiss 用户下滑关闭或点关闭按钮。
 * @param engine 识别引擎；生产默认构造 [DefaultOcrScannerEngine]，
 *   测试注入 Fake。
 * @param initialPermissionGranted 内部测试接缝：null（默认）走真实
 *   权限检查；true / false 直接给定授权态且不弹系统授权框。
 * @param onFrameProcessorReady 内部测试接缝：把帧处理器回传给测试，
 *   便于在无真机相机环境泵入伪帧。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OcrScannerSheet(
    onHint: (ReceiptHint) -> Unit,
    onDismiss: () -> Unit,
    engine: OcrScannerEngine? = null,
    initialPermissionGranted: Boolean? = null,
    onFrameProcessorReady: ((ImageAnalysis.Analyzer) -> Unit)? = null,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // 生产路径：调用方不传 engine 时才构造 ML Kit 默认引擎；remember 避免重复创建。
    val actualEngine: OcrScannerEngine = engine ?: remember { DefaultOcrScannerEngine() }

    // 权限态：测试接缝优先；否则按 ContextCompat 检查 CAMERA 授权。
    var permissionGranted by remember {
        mutableStateOf(
            initialPermissionGranted
                ?: (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
                    == PackageManager.PERMISSION_GRANTED),
        )
    }

    // 运行时权限申请 launcher：结果直接写回权限态。
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        permissionGranted = granted
    }

    // 生产路径（无测试接缝）首次进入自动申请一次权限；测试给定接缝时不弹系统框。
    LaunchedEffect(Unit) {
        if (initialPermissionGranted == null && !permissionGranted) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    // 界面状态，默认待命。
    var sheetState: OcrSheetState by remember { mutableStateOf(OcrSheetState.Idle) }

    // 帧处理器：内部用 AtomicBoolean 做「单帧握手」，点一次拍照只放行一帧。
    val frameProcessor = remember(actualEngine) {
        OcrFrameProcessor(
            engine = actualEngine,
            onText = { text ->
                // 回到主线程后解析：原文只在内存中流经纯函数，绝不持久化。
                val hint = text?.let { parseReceiptText(it) }
                sheetState = if (hint != null) {
                    OcrSheetState.Result(hint)
                } else {
                    OcrSheetState.NoResult
                }
            },
        )
    }

    // 测试接缝：把帧处理器交出去（生产始终为 null）。
    LaunchedEffect(frameProcessor) {
        onFrameProcessorReady?.invoke(frameProcessor)
    }

    // 相机预览与分析用例（仅授权后需要绑定）。
    val previewView = remember { PreviewView(context) }
    val preview = remember { Preview.Builder().build() }
    val imageAnalysis = remember {
        ImageAnalysis.Builder()
            // 最新帧优先：用户拍照前的大量帧直接丢弃，避免分析链堆积。
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
    }

    // 相机绑定 / 解绑：授权态变化时重绑；离开组合时解绑。
    DisposableEffect(permissionGranted) {
        if (!permissionGranted) {
            // 未授权不绑定任何相机用例。
            return@DisposableEffect onDispose { }
        }

        val mainExecutor = ContextCompat.getMainExecutor(context)
        // analyzer 始终挂载：非待识别帧由处理器内部立即 close，保证分析链不卡。
        imageAnalysis.setAnalyzer(mainExecutor, frameProcessor)

        var cameraProvider: ProcessCameraProvider? = null
        // CameraX 初始化在无相机服务环境（含 Robolectric）可能抛异常；
        // 用 runCatching 兜底，异常消息不读取、不打印（零知识 / 无日志）。
        runCatching {
            val providerFuture = ProcessCameraProvider.getInstance(context)
            providerFuture.addListener(
                {
                    runCatching {
                        val provider = providerFuture.get()
                        cameraProvider = provider
                        // 预览面在绑定前挂上 PreviewView。
                        preview.surfaceProvider = previewView.surfaceProvider
                        provider.unbindAll()
                        provider.bindToLifecycle(
                            lifecycleOwner,
                            CameraSelector.DEFAULT_BACK_CAMERA,
                            preview,
                            imageAnalysis,
                        )
                    }
                },
                mainExecutor,
            )
        }

        onDispose {
            // 离开组合：解绑全部用例，关闭相机。
            runCatching { cameraProvider?.unbindAll() }
        }
    }

    // 引擎释放：Sheet 整体离开组合时关闭识别器。
    DisposableEffect(actualEngine) {
        onDispose {
            runCatching { actualEngine.close() }
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
        ) {
            Text(
                text = stringResource(R.string.finance_ai_ocr_sheet_title),
                style = MaterialTheme.typography.titleMedium,
            )

            if (!permissionGranted) {
                // 权限被拒 / 尚未授权：仅展示本地用途说明与关闭入口，不阻断手工记账。
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { testTag = "ocr_scanner_permission_blocked" },
                ) {
                    Text(text = stringResource(R.string.finance_ai_camera_permission_text))
                    OutlinedButton(onClick = onDismiss) {
                        Text(stringResource(R.string.finance_ai_close))
                    }
                }
            } else {
                // 相机预览区域。
                AndroidView(
                    factory = { previewView },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(220.dp),
                )

                when (val current = sheetState) {
                    OcrSheetState.Idle -> {
                        // 待命：发起一次拍照识别（置待识别标志，下一帧生效）。
                        Button(
                            onClick = {
                                frameProcessor.prepareCapture()
                                sheetState = OcrSheetState.Recognizing
                            },
                            modifier = Modifier.semantics { testTag = "ocr_scanner_capture" },
                        ) {
                            Text(stringResource(R.string.finance_ai_capture))
                        }
                    }

                    OcrSheetState.Recognizing -> {
                        // 识别中：仅展示抽象状态，不展示原文。
                        Text(text = stringResource(R.string.finance_ai_recognizing))
                    }

                    OcrSheetState.NoResult -> {
                        // 无结果：说明 + 重试 / 关闭。
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .semantics { testTag = "ocr_scanner_no_result" },
                        ) {
                            Text(text = stringResource(R.string.finance_ai_ocr_no_result))
                            OutlinedButton(
                                onClick = {
                                    frameProcessor.prepareCapture()
                                    sheetState = OcrSheetState.Recognizing
                                },
                            ) {
                                Text(stringResource(R.string.finance_ai_retry))
                            }
                            OutlinedButton(onClick = onDismiss) {
                                Text(stringResource(R.string.finance_ai_close))
                            }
                        }
                    }

                    is OcrSheetState.Result -> {
                        val hint = current.hint
                        // 预览只渲染限定三要素，且每项非空才显示。
                        hint.amountMinor?.let { minor ->
                            Text(
                                text = stringResource(
                                    R.string.finance_ai_preview_amount,
                                    financeMinorToYuan(minor),
                                ),
                            )
                        }
                        hint.ts?.let { ts ->
                            Text(
                                text = stringResource(
                                    R.string.finance_ai_preview_date,
                                    formatReceiptDate(ts),
                                ),
                            )
                        }
                        hint.merchant?.let { merchant ->
                            Text(
                                text = stringResource(
                                    R.string.finance_ai_preview_merchant,
                                    merchant,
                                ),
                            )
                        }
                        // 使用该结果：仅回调 hint，不主动关闭（由调用方编排）。
                        Button(
                            onClick = { onHint(hint) },
                            modifier = Modifier.semantics { testTag = "ocr_scanner_use_hint" },
                        ) {
                            Text(stringResource(R.string.finance_ai_use_hint))
                        }
                        OutlinedButton(
                            onClick = {
                                frameProcessor.prepareCapture()
                                sheetState = OcrSheetState.Recognizing
                            },
                        ) {
                            Text(stringResource(R.string.finance_ai_retry))
                        }
                        OutlinedButton(onClick = onDismiss) {
                            Text(stringResource(R.string.finance_ai_close))
                        }
                    }
                }
            }
        }
    }
}

/**
 * CameraX 单帧处理器：实现 [ImageAnalysis.Analyzer]。
 *
 * 握手规则：
 *   - 外部通过 [prepareCapture] 置「有待识别帧」标志；
 *   - analyze 仅在标志为 true 时把帧交给引擎（compareAndSet 保证
 *     多帧并发到达时也只放行一帧），其余帧立即 close；
 *   - 引擎负责在识别完成（成功 / 失败）后 close 已放行的帧。
 */
private class OcrFrameProcessor(
    private val engine: OcrScannerEngine,
    private val onText: (String?) -> Unit,
) : ImageAnalysis.Analyzer {

    // true 表示用户已点拍照、正在等待下一帧。
    private val pendingCapture = AtomicBoolean(false)

    /** 用户点「拍照识别 / 重试」时调用：等待下一帧到来。 */
    fun prepareCapture() {
        pendingCapture.set(true)
    }

    override fun analyze(imageProxy: ImageProxy) {
        // 抢占式握手：只有成功把标志从 true 翻成 false 的那一帧交给引擎。
        if (!pendingCapture.compareAndSet(true, false)) {
            imageProxy.close()
            return
        }
        engine.recognize(imageProxy, onText)
    }
}

/**
 * 把「分」为单位的金额精确转成「元」展示文本（去尾零）。
 * 例：3500 得「35」、3550 得「35.5」、10005 得「100.05」。
 * 全程 BigDecimal 精确十进制，禁止 Double。
 */
internal fun financeMinorToYuan(amountMinor: Long): String =
    BigDecimal.valueOf(amountMinor)
        .movePointLeft(2)
        .stripTrailingZeros()
        .toPlainString()

/**
 * 把 epoch 毫秒格式化为小票日期预览（固定 yyyy-MM-dd）。
 */
private fun formatReceiptDate(ts: Long): String =
    SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(ts))
