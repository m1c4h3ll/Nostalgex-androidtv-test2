package app.nostalgex.tv.ui

import android.graphics.Bitmap
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.delay
import kotlin.random.Random

/**
 * RETRO MODE's CRT look over the picture, after the Apple TV's CRTOverlayView and RetroLook
 * (upstream e48cdc1): scanlines at 0.18, a dark vignette, a little flicker and a rolling
 * tracking band.
 */
@Composable
fun CrtOverlay(modifier: Modifier = Modifier) {
    val t = rememberInfiniteTransition(label = "crt")
    val flicker by t.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(140, easing = LinearEasing), RepeatMode.Reverse), label = "flicker",
    )
    val roll by t.animateFloat(
        initialValue = -0.2f, targetValue = 1.2f,
        animationSpec = infiniteRepeatable(tween(9_000, easing = LinearEasing)), label = "roll",
    )
    Canvas(modifier.fillMaxSize()) {
        val line = maxOf(1f, size.height / 360f) // about 3 lines in 1080p pixels
        var y = 0f
        val dark = Color.Black.copy(alpha = 0.18f)
        while (y < size.height) {
            drawRect(dark, topLeft = Offset(0f, y), size = Size(size.width, line))
            y += line * 2.5f
        }
        // The tracking band, rolling down the screen.
        val bandH = size.height * 0.07f
        val bandY = roll * size.height
        drawRect(
            Brush.verticalGradient(
                listOf(Color.Transparent, Color.White.copy(alpha = 0.035f), Color.Transparent),
                startY = bandY, endY = bandY + bandH,
            ),
            topLeft = Offset(0f, bandY), size = Size(size.width, bandH),
        )
        drawRect(Color.White.copy(alpha = 0.012f * flicker))
        drawRect(
            Brush.radialGradient(
                0.55f to Color.Transparent,
                1f to Color.Black.copy(alpha = 0.62f),
                center = Offset(size.width / 2, size.height / 2),
                radius = maxOf(size.width, size.height) * 0.75f,
            ),
        )
    }
}

/** Static snow while a channel tunes in (TunerView's StaticScreenView). */
@Composable
fun StaticSnow(modifier: Modifier = Modifier) {
    val frames = remember { List(4) { noiseFrame(Random(it * 7919 + 13)) } }
    var frame by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(70)
            frame = (frame + 1) % frames.size
        }
    }
    Canvas(modifier.fillMaxSize()) {
        drawImage(
            frames[frame],
            srcOffset = IntOffset.Zero,
            srcSize = IntSize(frames[frame].width, frames[frame].height),
            dstOffset = IntOffset.Zero,
            dstSize = IntSize(size.width.toInt(), size.height.toInt()),
            filterQuality = FilterQuality.None,
        )
        drawRect(Color.Black.copy(alpha = 0.25f))
    }
}

private fun noiseFrame(r: Random): ImageBitmap {
    val w = 240
    val h = 135
    val px = IntArray(w * h)
    for (i in px.indices) {
        val v = r.nextInt(256)
        px[i] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
    }
    return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888).asImageBitmap()
}
