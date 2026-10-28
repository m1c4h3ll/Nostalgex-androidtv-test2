package app.nostalgex.tv.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/** A QR code drawn on the TV, so no outside service ever sees the sign-in code. Level M. */
@Composable
fun QrCode(text: String, sizePx: Float, modifier: Modifier = Modifier) {
    val matrix = remember(text) {
        try {
            QRCodeWriter().encode(
                text, BarcodeFormat.QR_CODE, 0, 0,
                mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to 0),
            )
        } catch (_: Exception) {
            null
        }
    }
    Box(modifier.background(Color.White).padding(Px.u(12f))) {
        Canvas(Modifier.size(Px.u(sizePx))) {
            val m = matrix ?: return@Canvas
            val cell = size.width / m.width
            for (y in 0 until m.height) for (x in 0 until m.width) {
                if (m.get(x, y)) drawRect(Color.Black, Offset(x * cell, y * cell), Size(cell + 0.5f, cell + 0.5f))
            }
        }
    }
}
