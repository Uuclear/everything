/*
 * 通用 OCR 底部弹层：复用 ML Kit + CameraX 管线，解析函数由调用方注入。
 */
package com.everything.eve.ui.vault

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
import com.everything.eve.ui.finance.DefaultOcrScannerEngine
import com.everything.eve.ui.finance.OcrScannerEngine
import java.util.concurrent.atomic.AtomicBoolean

private sealed interface GenericOcrSheetState {
    data object Idle : GenericOcrSheetState
    data object Recognizing : GenericOcrSheetState
    data class Parsed<T>(val value: T) : GenericOcrSheetState
    data object NoResult : GenericOcrSheetState
}

/**
 * @param titleRes 弹层标题
 * @param noResultRes 无法解析时的说明
 * @param parse 识别原文 → 结构化结果；null 表示无有效字段
 * @param preview 仅展示允许外露的字段（禁止展示完整 OCR 原文）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <T> TextOcrScannerSheet(
    titleRes: Int,
    noResultRes: Int,
    parse: (String) -> T?,
    preview: @Composable (T) -> Unit,
    onApply: (T) -> Unit,
    onDismiss: () -> Unit,
    engine: OcrScannerEngine? = null,
    initialPermissionGranted: Boolean? = null,
    onFrameProcessorReady: ((ImageAnalysis.Analyzer) -> Unit)? = null,
    testTagPrefix: String = "text_ocr",
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val actualEngine: OcrScannerEngine = engine ?: remember { DefaultOcrScannerEngine() }

    var permissionGranted by remember {
        mutableStateOf(
            initialPermissionGranted
                ?: (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
                    == PackageManager.PERMISSION_GRANTED),
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> permissionGranted = granted }

    LaunchedEffect(Unit) {
        if (initialPermissionGranted == null && !permissionGranted) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    var sheetState: GenericOcrSheetState by remember { mutableStateOf(GenericOcrSheetState.Idle) }

    val frameProcessor = remember(actualEngine) {
        VaultOcrFrameProcessor(
            engine = actualEngine,
            onText = { text ->
                val parsed = text?.let(parse)
                sheetState = if (parsed != null) {
                    GenericOcrSheetState.Parsed(parsed)
                } else {
                    GenericOcrSheetState.NoResult
                }
            },
        )
    }

    LaunchedEffect(frameProcessor) {
        onFrameProcessorReady?.invoke(frameProcessor)
    }

    val previewView = remember { PreviewView(context) }
    val preview = remember { Preview.Builder().build() }
    val imageAnalysis = remember {
        ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
    }

    DisposableEffect(permissionGranted) {
        if (!permissionGranted) return@DisposableEffect onDispose { }

        val mainExecutor = ContextCompat.getMainExecutor(context)
        imageAnalysis.setAnalyzer(mainExecutor, frameProcessor)

        var cameraProvider: ProcessCameraProvider? = null
        runCatching {
            val providerFuture = ProcessCameraProvider.getInstance(context)
            providerFuture.addListener(
                {
                    runCatching {
                        val provider = providerFuture.get()
                        cameraProvider = provider
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
            runCatching { cameraProvider?.unbindAll() }
        }
    }

    DisposableEffect(actualEngine) {
        onDispose { runCatching { actualEngine.close() } }
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
        ) {
            Text(text = stringResource(titleRes), style = MaterialTheme.typography.titleMedium)

            if (!permissionGranted) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { testTag = "${testTagPrefix}_permission_blocked" },
                ) {
                    Text(text = stringResource(R.string.finance_ai_camera_permission_text))
                    OutlinedButton(onClick = onDismiss) {
                        Text(stringResource(R.string.finance_ai_close))
                    }
                }
            } else {
                AndroidView(
                    factory = { previewView },
                    modifier = Modifier.fillMaxWidth().height(220.dp),
                )

                when (val current = sheetState) {
                    GenericOcrSheetState.Idle -> {
                        Button(
                            onClick = {
                                frameProcessor.prepareCapture()
                                sheetState = GenericOcrSheetState.Recognizing
                            },
                            modifier = Modifier.semantics { testTag = "${testTagPrefix}_capture" },
                        ) {
                            Text(stringResource(R.string.finance_ai_capture))
                        }
                    }

                    GenericOcrSheetState.Recognizing -> {
                        Text(text = stringResource(R.string.finance_ai_recognizing))
                    }

                    GenericOcrSheetState.NoResult -> {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .semantics { testTag = "${testTagPrefix}_no_result" },
                        ) {
                            Text(text = stringResource(noResultRes))
                            OutlinedButton(
                                onClick = {
                                    frameProcessor.prepareCapture()
                                    sheetState = GenericOcrSheetState.Recognizing
                                },
                            ) {
                                Text(stringResource(R.string.finance_ai_retry))
                            }
                            OutlinedButton(onClick = onDismiss) {
                                Text(stringResource(R.string.finance_ai_close))
                            }
                        }
                    }

                    is GenericOcrSheetState.Parsed<*> -> {
                        @Suppress("UNCHECKED_CAST")
                        val value = current.value as T
                        preview(value)
                        Button(
                            onClick = { onApply(value) },
                            modifier = Modifier.semantics { testTag = "${testTagPrefix}_use_hint" },
                        ) {
                            Text(stringResource(R.string.finance_ai_use_hint))
                        }
                        OutlinedButton(
                            onClick = {
                                frameProcessor.prepareCapture()
                                sheetState = GenericOcrSheetState.Recognizing
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

private class VaultOcrFrameProcessor(
    private val engine: OcrScannerEngine,
    private val onText: (String?) -> Unit,
) : ImageAnalysis.Analyzer {

    private val pendingCapture = AtomicBoolean(false)

    fun prepareCapture() {
        pendingCapture.set(true)
    }

    override fun analyze(imageProxy: ImageProxy) {
        if (!pendingCapture.compareAndSet(true, false)) {
            imageProxy.close()
            return
        }
        engine.recognize(imageProxy, onText)
    }
}
