/*
 * ============================================================================
 * SpeechRecorderSheet —— 语音记账底部弹层（stage5-finance-v2 / Task 9 / B8）
 * ============================================================================
 *
 * 任务：stage5-finance-v2 / Task 9 / B8（Compose Sheet 层）
 * 路径：android/app/src/main/java/com/everything/eve/ui/finance/SpeechRecorderSheet.kt
 *
 * 作用：
 *   以 ModalBottomSheet 形态承载「开始说话 → 离线识别 → 预览金额与
 *   分类 → 回填编辑器」的交互。识别走 [SpeechRecorderEngine]（生产
 *   为系统 on-device SpeechRecognizer，EXTRA_PREFER_OFFLINE，
 *   zh-CN），最终文本再经 finance.parseSpeechText 纯函数解析为
 *   SpeechHint。
 *
 * 设计要点：
 *   1. 设备能力优先：引擎 isOnDeviceCapable 为 false 时直接展示
 *      「不支持离线语音识别」（speech_recorder_unsupported），不申请
 *      麦克风权限、不阻断手工记账。
 *   2. 权限：RECORD_AUDIO 运行时申请；拒绝仅展示本地用途说明
 *      （speech_recorder_permission_blocked）。
 *   3. 错误码抽象：引擎原样上抛 SpeechRecognizer 错误码（Int），
 *      UI 映射为抽象中文提示；错误码数字可向用户展示，但绝不写
 *      日志；麦克风权限类错误单独映射为权限引导文案。
 *   4. 零知识：聆听态不渲染识别原文，仅展示「正在聆听」；结果态
 *      只渲染金额与分类两项（无分类显示「未分类」）；语音原文不
 *      入库、不持久化；用户只有在编辑器点保存时才走既有加密
 *      saveBuffer。
 *   5. 资源释放：DisposableEffect 中调用 engine.destroy()，避免系统
 *      识别服务泄漏。
 *
 * 测试接缝（内部参数，生产调用不传）：
 *   - initialPermissionGranted：绕过真实权限检查与系统授权弹窗。
 *
 * 关联：
 *   - ui/finance/SpeechRecorderEngine.kt（识别引擎）
 *   - finance/SpeechParser.kt（parseSpeechText 纯函数）
 *   - ui/finance/FinanceEditor.kt（入口按钮与 hint 回填）
 * ============================================================================
 */

package com.everything.eve.ui.finance

import android.Manifest
import android.content.pm.PackageManager
import android.speech.SpeechRecognizer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.core.content.ContextCompat
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.unit.dp
import com.everything.eve.R
import com.everything.eve.finance.SpeechHint
import com.everything.eve.finance.parseSpeechText

/**
 * Sheet 的界面状态机：
 *  - [SpeechSheetState.Idle]：尚未开始，展示「开始说话」；
 *  - [SpeechSheetState.Listening]：识别进行中，展示「正在聆听」；
 *  - [SpeechSheetState.Result]：最终文本解析成功（必有金额）；
 *  - [SpeechSheetState.NoResult]：最终文本无金额或识别结果为空；
 *  - [SpeechSheetState.Error]：引擎上抛错误码，展示抽象提示（可含错误码）。
 */
private sealed interface SpeechSheetState {
    data object Idle : SpeechSheetState
    data object Listening : SpeechSheetState
    data class Result(val hint: SpeechHint) : SpeechSheetState
    data object NoResult : SpeechSheetState
    data class Error(val code: Int) : SpeechSheetState
}

/**
 * 语音记账底部弹层。
 *
 * @param onHint 用户点「使用该结果」时回调解析出的金额与分类（仅
 *   回调，是否关闭由调用方决定）。
 * @param onDismiss 用户下滑关闭或点关闭按钮。
 * @param engine 识别引擎；生产默认构造 [DefaultSpeechRecorderEngine]，
 *   测试注入 Fake。
 * @param initialPermissionGranted 内部测试接缝：null（默认）走真实
 *   权限检查；true / false 直接给定授权态且不弹系统授权框。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpeechRecorderSheet(
    onHint: (SpeechHint) -> Unit,
    onDismiss: () -> Unit,
    engine: SpeechRecorderEngine? = null,
    initialPermissionGranted: Boolean? = null,
) {
    val context = LocalContext.current

    // 生产路径：调用方不传 engine 时才构造系统 on-device 引擎；remember 避免重复创建。
    val actualEngine: SpeechRecorderEngine = engine ?: remember {
        DefaultSpeechRecorderEngine(context)
    }

    // 设备是否支持离线识别（构造期已求值）。
    val capable = actualEngine.isOnDeviceCapable

    // 权限态：测试接缝优先；否则按 ContextCompat 检查 RECORD_AUDIO 授权。
    var permissionGranted by remember {
        mutableStateOf(
            initialPermissionGranted
                ?: (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
                    == PackageManager.PERMISSION_GRANTED),
        )
    }

    // 运行时权限申请 launcher：结果直接写回权限态。
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        permissionGranted = granted
    }

    // 生产路径（无测试接缝）且设备支持时，首次进入自动申请一次权限。
    LaunchedEffect(Unit) {
        if (capable && initialPermissionGranted == null && !permissionGranted) {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    // 界面状态，默认待命。
    var sheetState: SpeechSheetState by remember { mutableStateOf(SpeechSheetState.Idle) }

    // 防止重复启动 / 回调乱序导致的状态漂移。
    var listening by remember { mutableStateOf(false) }

    /**
     * 开始一次离线识别：监听部分 / 最终结果与错误码。
     */
    fun startSession() {
        listening = true
        sheetState = SpeechSheetState.Listening
        actualEngine.start(
            onPartial = {
                // 部分结果原文不渲染、不持久化（零知识），聆听态仅展示固定状态。
            },
            onFinal = { text ->
                listening = false
                // 原文只在内存中流经纯函数：无金额即视为无结果。
                val hint = text?.let { parseSpeechText(it) }
                sheetState = if (hint != null) {
                    SpeechSheetState.Result(hint)
                } else {
                    SpeechSheetState.NoResult
                }
            },
            onError = { code ->
                listening = false
                // 错误码原样接收，仅用于 UI 映射，不打日志。
                sheetState = SpeechSheetState.Error(code)
            },
        )
    }

    // Sheet 整体离开组合时销毁引擎（先内部取消再 destroy），避免识别服务泄漏。
    DisposableEffect(actualEngine) {
        onDispose {
            runCatching { actualEngine.destroy() }
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
                text = stringResource(R.string.finance_ai_speech_sheet_title),
                style = MaterialTheme.typography.titleMedium,
            )

            when {
                !capable -> {
                    // 设备不支持离线识别：只展示说明与关闭入口，不申请权限。
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .semantics { testTag = "speech_recorder_unsupported" },
                    ) {
                        Text(text = stringResource(R.string.finance_ai_speech_unsupported))
                        OutlinedButton(onClick = onDismiss) {
                            Text(stringResource(R.string.finance_ai_close))
                        }
                    }
                }

                !permissionGranted -> {
                    // 麦克风权限被拒 / 尚未授权：仅展示本地用途说明，不阻断手工记账。
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .semantics { testTag = "speech_recorder_permission_blocked" },
                    ) {
                        Text(text = stringResource(R.string.finance_ai_mic_permission_text))
                        OutlinedButton(onClick = onDismiss) {
                            Text(stringResource(R.string.finance_ai_close))
                        }
                    }
                }

                else -> {
                    // 主操作按钮：聆听中为「停止」（取消本次识别），其余为「开始说话」。
                    Button(
                        onClick = {
                            if (listening) {
                                actualEngine.stop()
                                listening = false
                                sheetState = SpeechSheetState.Idle
                            } else {
                                startSession()
                            }
                        },
                        modifier = Modifier.semantics { testTag = "speech_recorder_toggle" },
                    ) {
                        Text(
                            stringResource(
                                if (listening) R.string.finance_ai_speech_stop
                                else R.string.finance_ai_speech_start,
                            ),
                        )
                    }

                    when (val current = sheetState) {
                        SpeechSheetState.Idle -> {
                            // 待命态无额外文案。
                        }

                        SpeechSheetState.Listening -> {
                            // 聆听态只展示固定状态，不展示任何识别片段。
                            Text(text = stringResource(R.string.finance_ai_speech_listening))
                        }

                        SpeechSheetState.NoResult -> {
                            // 无金额：说明 + 重试 / 关闭。
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .semantics { testTag = "speech_recorder_no_result" },
                            ) {
                                Text(text = stringResource(R.string.finance_ai_speech_no_result))
                                OutlinedButton(onClick = { startSession() }) {
                                    Text(stringResource(R.string.finance_ai_retry))
                                }
                                OutlinedButton(onClick = onDismiss) {
                                    Text(stringResource(R.string.finance_ai_close))
                                }
                            }
                        }

                        is SpeechSheetState.Error -> {
                            // 错误码映射抽象中文提示：
                            //  - ERROR_INSUFFICIENT_PERMISSIONS（9）：麦克风权限引导；
                            //  - 其余错误：通用「未听清」提示，可显示错误码数字。
                            val message = if (current.code == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                                stringResource(R.string.finance_ai_speech_error_permission)
                            } else {
                                stringResource(
                                    R.string.finance_ai_speech_error_format,
                                    current.code,
                                )
                            }
                            Text(text = message)
                            OutlinedButton(onClick = { startSession() }) {
                                Text(stringResource(R.string.finance_ai_retry))
                            }
                            OutlinedButton(onClick = onDismiss) {
                                Text(stringResource(R.string.finance_ai_close))
                            }
                        }

                        is SpeechSheetState.Result -> {
                            val hint = current.hint
                            // 金额必有（纯函数已保证），分类可空时显示「未分类」。
                            Text(
                                text = stringResource(
                                    R.string.finance_ai_preview_amount,
                                    financeMinorToYuan(hint.amountMinor ?: 0L),
                                ),
                            )
                            Text(
                                text = stringResource(
                                    R.string.finance_ai_preview_category,
                                    hint.category ?: stringResource(R.string.finance_ai_uncategorized),
                                ),
                            )
                            // 使用该结果：仅回调 hint，不主动关闭（由调用方编排）。
                            Button(
                                onClick = { onHint(hint) },
                                modifier = Modifier.semantics { testTag = "speech_recorder_use_hint" },
                            ) {
                                Text(stringResource(R.string.finance_ai_use_hint))
                            }
                            OutlinedButton(onClick = { startSession() }) {
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
}
