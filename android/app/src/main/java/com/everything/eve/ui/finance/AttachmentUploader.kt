/*
 * ============================================================================
 * AttachmentUploader —— Compose 附件上传组件（stage5-finance-v2 / Task 5 / TR-3.4 / SA-3）
 * ============================================================================
 *
 * 职责：
 *   1) 暴露一个 "上传附件" Button —— 触发 SAF（Storage Access Framework）文件选择器；
 *   2) MIME 严格白名单：application/pdf / image/jpeg / image/png（与 SA-1
 *      AttachmentEntity 校验口径一致）；
 *   3) onResult 回调拿到 Uri → 端侧读取字节 → 端侧 50MB 预检 → 调
 *      [FinanceViewModel.addAttachment] 入库；
 *   4) 失败用 Toast 反馈（无需 SnackbarHostState，最简 toast）。
 *
 * SAF 选型纪律（spec NFR-2）：
 *   - **无需运行时权限**：ACTION_OPEN_DOCUMENT 由系统文件选择器代为取 uri，
 *     应用只持有 Uri 引用，不读写 /sdcard 公共目录；
 *   - **零知识红线**：明文字节在 UI 层读完即塞进 VM（VM 内部 envelope 加密）；
 *     不进日志 / 不进 SharedPreferences。
 *
 * 关联：
 *   - tasks.md TR-3.4
 *   - ui/finance/AttachmentList.kt（同 batch 列表组件）
 *   - ui/finance/FinanceViewModel.addAttachment（SA-3 VM 扩展）
 *   - data/finance/AttachmentRepository.upload（SA-2 仓库方法）
 *
 * 编码纪律：
 *   - 严禁 `--`（双连字符）；用全角破折号 `——` 或 `==========` 替代。
 *   - testTag 命名：`finance_attachment_uploader` / `finance_attachment_uploader_btn`。
 * ============================================================================
 */

package com.everything.eve.ui.finance

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.everything.eve.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 附件上传 Compose 组件（spec FR-3 / TR-3.4）。
 *
 * 设计要点：
 *   - "上传附件" Button → rememberLauncherForActivityResult(OpenDocument) 启动
 *     SAF 文件选择器；
 *   - MIME 过滤：`arrayOf("application/pdf", "image/jpeg", "image/png")`；
 *   - onResult(uri: Uri?) → 若 uri != null：异步读取字节 → 50MB 预检 →
 *     vm.addAttachment(recordId, bytes, mime)；
 *   - 失败（读字节失败 / 超 50MB / Repository 失败）→ 直接 Toast 反馈，不入库。
 *
 * @param recordId 父记录 id（policy / contract 等 v2 子类型记录的主键）。
 * @param vm FinanceViewModel（提供 addAttachment）。
 * @param modifier Compose modifier。
 */
@Composable
fun AttachmentUploader(
    recordId: String,
    vm: FinanceViewModel,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // =============================================================================
    // SAF 文件选择器 launcher（spec NFR-2 无权限；MIME 白名单硬约束）
    // =============================================================================
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        // 用户取消选择 → null 直接忽略
        if (uri == null) return@rememberLauncherForActivityResult

        scope.launch {
            // ---- 推断 MIME：先尝试 contentResolver.getType，缺省走 * / * 兜底 ----
            val mime = context.contentResolver.getType(uri) ?: "application/octet-stream"

            // ---- MIME 白名单（与 AttachmentEntity 校验口径一致）----
            if (mime !in ALLOWED_MIME_TYPES) {
                Toast.makeText(
                    context,
                    context.getString(R.string.finance_attachment_size_exceeded),
                    Toast.LENGTH_SHORT,
                ).show()
                return@launch
            }

            // ---- 端侧读字节（IO 线程）----
            val bytes: ByteArray = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                }.getOrNull()
            } ?: run {
                Toast.makeText(
                    context,
                    context.getString(R.string.finance_attachment_size_exceeded),
                    Toast.LENGTH_SHORT,
                ).show()
                return@launch
            }

            // ---- 端侧 50MB 预检（与 VM verifySize / Repository 终检三道防线）----
            val maxBytes = com.everything.eve.finance.ATTACHMENT_MAX_SIZE_BYTES
            if (bytes.size.toLong() > maxBytes) {
                Toast.makeText(
                    context,
                    context.getString(R.string.finance_attachment_size_exceeded),
                    Toast.LENGTH_SHORT,
                ).show()
                return@launch
            }

            // ---- 调 VM 入库 ----
            val result = vm.addAttachment(recordId, bytes, mime)
            if (result.isFailure) {
                Toast.makeText(
                    context,
                    context.getString(R.string.finance_attachment_size_exceeded),
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .testTag("finance_attachment_uploader"),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Button(
            onClick = {
                // 启动 SAF；MIME 过滤由 ActivityResultContracts.OpenDocument 接住
                launcher.launch(ALLOWED_MIME_TYPES)
            },
            colors = ButtonDefaults.buttonColors(),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("finance_attachment_uploader_btn"),
        ) {
            Text(
                text = stringResource(R.string.finance_attachment_uploader_button),
                modifier = Modifier.testTag("finance_attachment_uploader_btn_label"),
            )
        }
    }
}

// =============================================================================
// 私有常量
// =============================================================================

/**
 * 允许上传的 MIME 类型白名单（与 AttachmentRepository.upload 校验口径一致）。
 *
 * 严格三选 —— application/pdf（合同扫描件 / 保单 PDF）/ image/jpeg（票据照片）/
 * image/png（票据照片）；其他类型拒绝。
 */
private val ALLOWED_MIME_TYPES: Array<String> = arrayOf(
    "application/pdf",
    "image/jpeg",
    "image/png",
)
