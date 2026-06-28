package com.ternparagliding.ui.components

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * Render [content] as a QR code (ZXing). Sizing comes from [modifier] — the bitmap is generated at a
 * fixed resolution and scaled to fit; QR blocks stay crisp. Renders nothing if encoding fails.
 */
@Composable
fun QrCode(content: String, modifier: Modifier = Modifier) {
    val bitmap = remember(content) { qrBitmap(content, 512) }
    if (bitmap != null) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = "Team QR code",
            modifier = modifier,
        )
    }
}

private fun qrBitmap(content: String, pixels: Int): Bitmap? = runCatching {
    val hints = mapOf(
        EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
        EncodeHintType.MARGIN to 1,
    )
    val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, pixels, pixels, hints)
    val w = matrix.width
    val h = matrix.height
    Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565).apply {
        for (x in 0 until w) {
            for (y in 0 until h) {
                setPixel(x, y, if (matrix[x, y]) Color.BLACK else Color.WHITE)
            }
        }
    }
}.getOrNull()
