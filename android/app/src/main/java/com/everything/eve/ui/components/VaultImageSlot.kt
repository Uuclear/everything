// 证件/卡面影像槽：相册选图 + 本地解密预览（零知识：不写日志）
package com.everything.eve.ui.components

import android.graphics.BitmapFactory
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.everything.eve.R
import com.everything.eve.vault.VAULT_IMAGE_MIME_WHITELIST
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 单张影像附件槽（正面/反面/卡面）。
 *
 * @param loadPreview 按附件 id 解密字节（仅内存预览）。
 * @param onUpload 上传字节并返回新附件 id。
 */
@Composable
fun VaultImageSlot(
    label: String,
    attachmentId: String?,
    onAttachmentIdChange: (String?) -> Unit,
    loadPreview: suspend (String) -> Result<ByteArray>,
    onUpload: suspend (ByteArray, String) -> Result<String>,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var previewBytes by remember(attachmentId) { mutableStateOf<ByteArray?>(null) }

    LaunchedEffect(attachmentId) {
        previewBytes = null
        val id = attachmentId
        if (id != null) {
            previewBytes = loadPreview(id).getOrNull()
        }
    }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val mime = context.contentResolver.getType(uri) ?: "image/jpeg"
            if (mime !in VAULT_IMAGE_MIME_WHITELIST) {
                Toast.makeText(context, context.getString(R.string.vault_image_mime_rejected), Toast.LENGTH_SHORT).show()
                return@launch
            }
            val bytes = withContext(Dispatchers.IO) {
                context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            }
            if (bytes == null) {
                Toast.makeText(context, context.getString(R.string.vault_image_read_failed), Toast.LENGTH_SHORT).show()
                return@launch
            }
            val uploadResult = onUpload(bytes, mime)
            if (uploadResult.isSuccess) {
                onAttachmentIdChange(uploadResult.getOrNull())
            } else {
                Toast.makeText(context, context.getString(R.string.vault_image_upload_failed), Toast.LENGTH_SHORT).show()
            }
        }
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.labelMedium)
        val bitmap = previewBytes?.let {
            remember(it) { BitmapFactory.decodeByteArray(it, 0, it.size) }
        }
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = label,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(160.dp)
                    .padding(vertical = 8.dp),
            )
        } else if (attachmentId != null) {
            Text(
                stringResource(R.string.vault_image_preview_pending),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(vertical = 4.dp),
            )
        }
        Button(
            onClick = { launcher.launch(VAULT_IMAGE_MIME_WHITELIST.toTypedArray()) },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.vault_image_pick))
        }
        if (attachmentId != null) {
            OutlinedButton(
                onClick = {
                    onAttachmentIdChange(null)
                    previewBytes = null
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.vault_image_remove))
            }
        }
    }
}
