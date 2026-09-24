/*
 * ============================================================================
 * SpeechRecorderEngine —— 语音记账识别引擎抽象与系统 SpeechRecognizer 生产实现
 *                            （stage5-finance-v2 / Task 9 / B8 批次）
 * ============================================================================
 *
 * 任务：stage5-finance-v2 / Task 9 / B8（引擎层）
 * 路径：android/app/src/main/java/com/everything/eve/ui/finance/SpeechRecorderEngine.kt
 *
 * 作用：
 *   封装 android.speech.SpeechRecognizer 的「on-device 离线识别」能力，
 *   实时回调部分识别结果与最终文本（最终文本由调用方交给
 *   finance.parseSpeechText 纯函数解析为 SpeechHint）。
 *
 * 设计要点：
 *   1. 强制离线：仅在 API 31+ 且设备支持 on-device recognition 时创建
 *      SpeechRecognizer.createOnDeviceSpeechRecognizer，并对 RecognizerIntent
 *      额外设置 EXTRA_PREFER_OFFLINE=true；语音内容不上传、不走云端。
 *   2. 接口与实现分离：生产路径用 [DefaultSpeechRecorderEngine]；
 *      单测注入 Fake 实现，不触碰真机 / 麦克风 / 系统识别服务。
 *   3. 零知识红线：本文件【禁止】把识别文本写日志；onError 的错误码
 *      原样以 Int 上抛，由 UI 层映射为抽象中文提示。
 *   4. 资源纪律：stop 走 cancel（放弃本次识别），destroy 释放
 *      SpeechRecognizer；Sheet 关闭时必须 destroy，避免监听服务泄漏。
 *
 * 关联：
 *   - ui/finance/SpeechRecorderSheet.kt（UI 与权限，本引擎的调用方）
 *   - finance/SpeechParser.kt（parseSpeechText 纯函数，消费最终文本）
 * ============================================================================
 */

package com.everything.eve.ui.finance

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * 语音识别引擎接口（真实实现走系统 SpeechRecognizer；测试可注入 Fake）。
 */
interface SpeechRecorderEngine {

    /**
     * 设备是否支持 on-device 离线语音识别（API 31+ 且设备具备该能力）。
     * false 时 UI 直接展示「不支持离线语音识别」说明，不申请麦克风权限。
     */
    val isOnDeviceCapable: Boolean

    /**
     * 开始一次离线语音识别。
     *
     * @param onPartial 实时部分识别结果（设备本地文本，仅用于界面展示）。
     * @param onFinal 最终识别文本；异常中断回调 null。
     * @param onError 识别失败；参数为 SpeechRecognizer 原始错误码，原样上抛。
     */
    fun start(onPartial: (String) -> Unit, onFinal: (String?) -> Unit, onError: (Int) -> Unit)

    /**
     * 停止本次识别（放弃当前会话）。
     */
    fun stop()

    /**
     * 销毁识别器并释放系统服务（Sheet 关闭时由 DisposableEffect 调用）。
     */
    fun destroy()
}

/**
 * 生产环境实现：android.speech.SpeechRecognizer，强制 EXTRA_PREFER_OFFLINE。
 *
 * @param context 上下文（内部取 applicationContext，避免持有 Activity 泄漏）。
 */
class DefaultSpeechRecorderEngine(
    context: Context,
) : SpeechRecorderEngine {

    private val appContext = context.applicationContext

    /**
     * API 31+ 且设备支持 on-device recognition 才视为可用；
     * 低版本系统直接判定不可用（属性在构造期一次性求值）。
     */
    override val isOnDeviceCapable: Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            runCatching { SpeechRecognizer.isOnDeviceRecognitionAvailable(appContext) }
                .getOrDefault(false)

    // on-device 识别器实例：仅在设备支持时创建；不支持时保持 null。
    private val recognizer: SpeechRecognizer? =
        if (isOnDeviceCapable) {
            runCatching { SpeechRecognizer.createOnDeviceSpeechRecognizer(appContext) }
                .getOrNull()
        } else {
            null
        }

    // 防止 stop/destroy 重复调用系统服务造成崩溃。
    private var started = false
    private var destroyed = false

    /**
     * 配置离线识别 Intent 并开始监听。
     */
    override fun start(
        onPartial: (String) -> Unit,
        onFinal: (String?) -> Unit,
        onError: (Int) -> Unit,
    ) {
        val sr = recognizer ?: return
        if (started || destroyed) return

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            // 自由语音识别模式（非受限命令词）。
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
            )
            // 识别语言固定简体中文。
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
            // 关键：强制偏好离线识别，语音内容不上传云端。
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            // 仅返回结果文本，不需要设备不支持的置信度等附加数据。
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        }

        sr.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                // 系统已就绪：本实现不向 UI 暴露此瞬时状态。
            }

            override fun onBeginningOfSpeech() {
                // 用户开始说话：无需处理（录音动画由 UI 会话状态驱动）。
            }

            override fun onRmsChanged(rmsdB: Float) {
                // 音量电平：不消费，避免高频回调。
            }

            override fun onBufferReceived(buffer: ByteArray?) {
                // 原始音频缓冲：不接收、不缓存（零知识：音频不出系统服务）。
            }

            override fun onEndOfSpeech() {
                // 说话结束：最终结果由 onResults/onError 承接。
            }

            override fun onError(error: Int) {
                // 错误码原样上抛，由 UI 层映射抽象提示；错误码不写日志。
                started = false
                onError(error)
            }

            override fun onResults(results: Bundle?) {
                started = false
                onFinal(extractTopResult(results))
            }

            override fun onPartialResults(partialResults: Bundle?) {
                // 部分结果同样只取首选文本，仅回调给 UI 实时展示。
                extractTopResult(partialResults)?.takeIf { it.isNotBlank() }?.let(onPartial)
            }

            override fun onEvent(eventType: Int, params: Bundle?) {
                // 预留事件：本实现不处理。
            }
        })

        started = true
        sr.startListening(intent)
    }

    /**
     * 从结果 Bundle 中取 RESULTS_RECOGNITION 列表的首项；
     * 无结果返回 null。
     */
    private fun extractTopResult(bundle: Bundle?): String? =
        bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?.firstOrNull()

    /**
     * 取消本次识别（若在进行中）。cancel 不会触发最终结果回调。
     */
    override fun stop() {
        if (destroyed) return
        if (started) {
            runCatching { recognizer?.cancel() }
            started = false
        }
    }

    /**
     * 销毁识别器：先 cancel 再 destroy，避免系统识别服务泄漏。
     */
    override fun destroy() {
        if (destroyed) return
        destroyed = true
        runCatching {
            if (started) recognizer?.cancel()
            recognizer?.destroy()
        }
        started = false
    }
}
