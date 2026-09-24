/*
 * ============================================================================
 * OcrScannerEngine —— 小票 OCR 识别引擎抽象与 ML Kit 生产实现
 *                         （stage5-finance-v2 / Task 9 / B8 批次）
 * ============================================================================
 *
 * 任务：stage5-finance-v2 / Task 9 / B8（引擎层）
 * 路径：android/app/src/main/java/com/everything/eve/ui/finance/OcrScannerEngine.kt
 *
 * 作用：
 *   封装 ML Kit Text Recognition 的「中文文字识别器」，对 CameraX
 *   ImageAnalysis 产出的 ImageProxy 做 on-device 文字识别，向调用方
 *   回传原始识别文本（由调用方交给 finance.parseReceiptText 纯函数
 *   解析为 ReceiptHint 三要素）。
 *
 * 设计要点：
 *   1. 接口与实现分离：生产路径用 [DefaultOcrScannerEngine]（ML Kit）；
 *      单测注入 Fake 实现，全程不触碰真机 / 摄像头 / ML Kit native 栈。
 *   2. 识别全程在设备本地完成：ML Kit text-recognition 为自包含 AAR，
 *      中文模型随包下发，无 GMS 云依赖、不产生任何网络上传。
 *   3. 资源纪律：识别成功或失败都必须 imageProxy.close()，否则
 *      ImageAnalysis（KEEP_ONLY_LATEST）会因最新帧未归还而卡死分析链。
 *   4. 零知识红线：本文件【禁止】Log.d/i/w/e 打印识别文本、图片信息
 *      或异常消息；异常只以类型化状态回调（null 文本表示失败），
 *      识别原文绝不持久化。
 *
 * 关联：
 *   - ui/finance/OcrScannerSheet.kt（相机绑定与 UI，本引擎的调用方）
 *   - finance/OcrParser.kt（parseReceiptText 纯函数，消费识别文本）
 * ============================================================================
 */

package com.everything.eve.ui.finance

import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions

/**
 * OCR 扫描引擎接口（真实实现走 ML Kit；测试可注入 Fake，不触碰真机）。
 */
interface OcrScannerEngine {

    /**
     * 对 CameraX ImageProxy 做 on-device 文字识别。
     *
     * @param imageProxy 相机分析链产出的最新一帧；实现方必须在识别
     *   结束（成功或失败）后调用其 close() 归还帧。
     * @param onResult 识别成功回调原始文本；识别失败回调 null（仅传
     *   类型化失败状态，异常消息不上抛、不入日志）。
     */
    fun recognize(imageProxy: ImageProxy, onResult: (String?) -> Unit)

    /**
     * 释放引擎底层识别器资源（Sheet 关闭时由 DisposableEffect 调用）。
     */
    fun close()
}

/**
 * 生产环境实现：ML Kit 中文 OCR（on-device，自包含模型，无云依赖）。
 */
class DefaultOcrScannerEngine : OcrScannerEngine {

    // 中文识别器：ML Kit 16.0.x 重构后 ChineseTextRecognizerOptions 已直接实现
    // TextRecognizerOptionsInterface，getClient 直接接收该选项即可（旧版的
    // TextRecognizerOptions.Builder().setRecognizer(...) 包装类已移除）。
    // 该选项由 text-recognition-chinese 构件经 play-services-mlkit-text-recognition-chinese
    // 传递提供，中文模型随包内置，全程端侧识别。
    private val recognizer = TextRecognition.getClient(
        ChineseTextRecognizerOptions.Builder().build(),
    )

    /**
     * 把 ImageProxy 转为 ML Kit InputImage 并提交识别。
     */
    override fun recognize(imageProxy: ImageProxy, onResult: (String?) -> Unit) {
        // 从 MediaImage 构造输入：第二参数为取景旋转角（由 CameraX 计算），
        // 保证识别文本方向与用户所见预览一致。
        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            // 极端情况下帧内 image 为 null：归还帧并以 null 文本上抛失败状态。
            imageProxy.close()
            onResult(null)
            return
        }
        val inputImage = InputImage.fromMediaImage(
            mediaImage,
            imageProxy.imageInfo.rotationDegrees,
        )

        recognizer.process(inputImage)
            .addOnSuccessListener { result ->
                // 成功：仅把识别文本回调给调用方，本层不做任何持久化或日志。
                onResult(result.text)
            }
            .addOnFailureListener {
                // 失败：只上抛类型化失败状态（null）；异常消息不入日志、不扩散。
                onResult(null)
            }
            .addOnCompleteListener {
                // 无论成功失败都必须归还帧，避免分析链被未关闭帧阻塞。
                imageProxy.close()
            }
    }

    /**
     * 关闭识别器，释放模型相关资源。
     */
    override fun close() {
        recognizer.close()
    }
}
