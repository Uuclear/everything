/*
 * ============================================================================
 * AttachmentList —— Compose 附件列表组件（stage5-finance-v2 / Task 5 / TR-3.4 / SA-3）
 * ============================================================================
 *
 * 职责：
 *   1) 订阅 [FinanceViewModel.attachmentsByRecordId] Flow，渲染当前父记录下的
 *      全部附件（按 updated_at 降序）；
 *   2) 每条附件显示 mime + size（KB / MB 自适应）+ sha256 前 8 字符；
 *   3) 点击 → onPreview(id, mime) 回调（父层决定如何打开 Intent）；
 *   4) 长按 → 弹确认对话框 → 调 [FinanceViewModel.removeAttachment]；
 *   5) 空态走 R.string.finance_attachment_empty。
 *
 * 设计要点（与 SA-3 边界一致）：
 *   - **零知识（spec NFR-1）**：列表不展示 name / amount / 保单号明文；mime 与
 *     size / sha256 摘要仅用于本地展示，不进日志；
 *   - **不直接打开 Intent**：onPreview 是回调，避免此处耦合 Context；
 *   - **不直接 Toast 删除**：删除走 Material3 AlertDialog 二次确认。
 *
 * 关联：
 *   - tasks.md TR-3.4
 *   - ui/finance/AttachmentUploader.kt（同 batch 上传组件）
 *   - ui/finance/FinanceViewModel.attachmentsByRecordId / removeAttachment（SA-3）
 *   - data/finance/AttachmentRepository.listByRecordId / delete（SA-2）
 *
 * 编码纪律：
 *   - 严禁 `--`（双连字符）；用全角破折号 `——` 或 `==========` 替代。
 *   - testTag 命名：`finance_attachment_list` / `finance_attachment_item` /
 *     `finance_attachment_item_delete_dialog` 等。
 * ============================================================================
 */

package com.everything.eve.ui.finance

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.unit.dp
import com.everything.eve.R
import com.everything.eve.data.finance.entity.AttachmentEntity

/**
 * 附件列表 Compose 组件（spec FR-3 / TR-3.4）。
 *
 * 实现要点：
 *   - 内部 collectAsState `vm.attachmentsByRecordId(recordId).collectAsState(initial = emptyList())`；
 *   - LazyColumn 渲染每条 AttachmentEntity；
 *   - 点击 → onPreview(id, mime)；
 *   - 长按 → 弹确认对话框 → vm.removeAttachment(id)；
 *   - 空态显示 stringResource(R.string.finance_attachment_empty)。
 *
 * @param recordId 父记录 id（policy / contract 等 v2 子类型记录的主键）。
 * @param vm FinanceViewModel（提供 attachmentsByRecordId / removeAttachment）。
 * @param modifier Compose modifier。
 * @param onPreview 点击预览回调；由父层（编辑器 Screen）决定打开 Intent 方式。
 */
@Composable
fun AttachmentList(
    recordId: String,
    vm: FinanceViewModel,
    modifier: Modifier = Modifier,
    onPreview: (attachmentId: String, mime: String) -> Unit = { _, _ -> },
) {
    // =============================================================================
    // 订阅附件 Flow（按父记录 id 过滤；Room 写入时 UI 自动刷新）
    // =============================================================================
    val attachments by vm.attachmentsByRecordId(recordId).collectAsState(initial = emptyList())

    // =============================================================================
    // 长按删除确认对话框状态（每个 item 共享一个 selectedId；空表示未选中）
    // =============================================================================
    var pendingDeleteId by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .semantics { testTag = "finance_attachment_list" },
    ) {
        if (attachments.isEmpty()) {
            // ---- 空态 ----
            Text(
                text = stringResource(R.string.finance_attachment_empty),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp)
                    .testTag("finance_attachment_list_empty"),
            )
        } else {
            // ---- 列表渲染 ----
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { testTag = "finance_attachment_list_items" },
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(
                    items = attachments,
                    key = { it.id },
                ) { item ->
                    AttachmentListItem(
                        item = item,
                        onClick = { onPreview(item.id, item.mime) },
                        onLongClick = { pendingDeleteId = item.id },
                    )
                }
            }
        }
    }

    // =============================================================================
    // 删除确认对话框（Material3 AlertDialog）
    // =============================================================================
    pendingDeleteId?.let { targetId ->
        AlertDialog(
            onDismissRequest = { pendingDeleteId = null },
            title = {
                Text(
                    text = stringResource(R.string.finance_attachment_delete_confirm_title),
                    modifier = Modifier.testTag("finance_attachment_item_delete_dialog_title"),
                )
            },
            text = {
                Text(
                    text = stringResource(R.string.finance_attachment_delete_confirm_message),
                    modifier = Modifier.testTag("finance_attachment_item_delete_dialog_message"),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.removeAttachment(targetId)
                        pendingDeleteId = null
                    },
                    modifier = Modifier.testTag("finance_attachment_item_delete_confirm"),
                ) {
                    Text(stringResource(R.string.finance_attachment_delete_confirm_title))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { pendingDeleteId = null },
                    modifier = Modifier.testTag("finance_attachment_item_delete_cancel"),
                ) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
            modifier = Modifier.testTag("finance_attachment_item_delete_dialog"),
        )
    }
}

/**
 * 单条附件列表 item。
 *
 * 显示 mime + size（KB / MB 自适应）+ sha256 前 8 字符。
 * 点击 / 长按由 combinedClickable 接住。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AttachmentListItem(
    item: AttachmentEntity,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
            )
            .semantics { testTag = "finance_attachment_item" },
    ) {
        Box(modifier = Modifier.padding(12.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = item.mime,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.testTag("finance_attachment_item_mime"),
                )
                Text(
                    text = humanReadableSize(item.size),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.testTag("finance_attachment_item_size"),
                )
                // sha256 前 8 字符 —— 用于本地对账摘要展示
                val sha8 = item.sha256.take(8)
                Text(
                    text = "sha:$sha8",
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.testTag("finance_attachment_item_sha"),
                )
            }
        }
    }
}

/**
 * 字节数 → 人类可读尺寸（KB / MB 自适应）。
 *
 * @param size 字节数。
 * @return "1.2 KB" / "3.4 MB" / "512 B"。
 */
private fun humanReadableSize(size: Long): String {
    val kb = 1024L
    val mb = 1024L * 1024L
    return when {
        size < kb -> "$size B"
        size < mb -> "%.1f KB".format(size.toDouble() / kb)
        else -> "%.1f MB".format(size.toDouble() / mb)
    }
}
