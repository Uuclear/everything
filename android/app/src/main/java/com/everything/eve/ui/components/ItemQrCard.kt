// 物品详情二维码卡片（stage5-items / T6；payload 仅 itemId）
package com.everything.eve.ui.components

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.everything.eve.items.qrPayloadForItem
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter

/** 生成 PNG 位图（默认 512×512）。 */
fun generateQrPng(itemId: String, size: Int = 512): Bitmap {
    val payload = qrPayloadForItem(itemId)
    val matrix = QRCodeWriter().encode(payload, BarcodeFormat.QR_CODE, size, size)
    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    for (x in 0 until size) {
        for (y in 0 until size) {
            bmp.setPixel(x, y, if (matrix[x, y]) Color.BLACK else Color.WHITE)
        }
    }
    return bmp
}

/** 极简 SVG 字符串（无第三方 SVG 库）。 */
fun generateQrSvg(itemId: String, moduleSize: Int = 25): String {
    val payload = qrPayloadForItem(itemId)
    val matrix = QRCodeWriter().encode(payload, BarcodeFormat.QR_CODE, moduleSize, moduleSize)
    val w = matrix.width
    val h = matrix.height
    val sb = StringBuilder()
    sb.append("""<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 $w $h">""")
    for (y in 0 until h) {
        for (x in 0 until w) {
            if (matrix[x, y]) {
                sb.append("""<rect x="$x" y="$y" width="1" height="1" fill="black"/>""")
            }
        }
    }
    sb.append("</svg>")
    return sb.toString()
}

@Composable
fun ItemQrCard(
    itemId: String,
    modifier: Modifier = Modifier,
    onDownloadPng: (() -> Unit)? = null,
    onDownloadSvg: (() -> Unit)? = null,
) {
    val bitmap = remember(itemId) { generateQrPng(itemId, 256) }
    Column(modifier = modifier.testTag("qr_card")) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = "item_qr",
            modifier = Modifier
                .padding(8.dp)
                .testTag("qr_png"),
        )
        if (onDownloadPng != null) {
            TextButton(onClick = onDownloadPng, modifier = Modifier.testTag("btn_download_png")) {
                Text("下载 PNG")
            }
        }
        if (onDownloadSvg != null) {
            TextButton(onClick = onDownloadSvg, modifier = Modifier.testTag("btn_download_svg")) {
                Text("下载 SVG")
            }
        }
    }
}
