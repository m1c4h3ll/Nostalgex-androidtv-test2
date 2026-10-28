package app.nostalgex.tv.ui

import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import app.nostalgex.tv.R

/**
 * The Apple TV app's look (TunerView, InfoPanelView, ChannelGuideView at upstream f51e72b),
 * measured at 1920x1080. Everything here is laid out in 1080p units: [Px.u] turns a 1080p
 * pixel into dp for whatever the TV's UI size is.
 */
object Nx {
    val Cyan = Color(0xFF00D4FF)
    val Pink = Color(0xFFFF2DB4)
    val Yellow = Color(0xFFFFE500)
    val Amber = Color(0xFFFFB020)
    val Red = Color(0xFFFF4D4D)
    val Panel0 = Color(0xFF0A0A1A) // rgb(0.04, 0.04, 0.10)
    val Panel1 = Color(0xFF0D0D1F) // rgb(0.05, 0.05, 0.12)
    val GridBg = Color(0xFF0D0D1F)
    val HeaderBg = Color(0xFF0A0A14) // rgb(0.04, 0.04, 0.08)
    val Ink = Color(0xFFF7F2E8)

    val VT323 = FontFamily(Font(R.font.vt323_regular))
    val DMMono = FontFamily(Font(R.font.dmmono_regular, FontWeight.Normal), Font(R.font.dmmono_medium, FontWeight.Medium))

    fun hex(s: String, fallback: Color = Yellow): Color = try {
        val h = s.removePrefix("#")
        when (h.length) {
            6 -> Color(0xFF000000 or h.toLong(16))
            8 -> Color(h.toLong(16))
            else -> fallback
        }
    } catch (_: Exception) {
        fallback
    }
}

/** A line of text in one of the app's two fonts. Sizes are 1080p pixels. */
@Composable
fun T(
    text: String,
    size: Float,
    color: Color = Color.White,
    modifier: Modifier = Modifier,
    retro: Boolean = false,
    medium: Boolean = false,
    tracking: TextUnit = 0.sp,
    maxLines: Int = 1,
    lineHeight: Float? = null,
) {
    BasicText(
        text = text,
        modifier = modifier,
        style = TextStyle(
            color = color,
            fontFamily = if (retro) Nx.VT323 else Nx.DMMono,
            fontWeight = if (medium) FontWeight.Medium else FontWeight.Normal,
            fontSize = Px.sp(size),
            letterSpacing = if (tracking == 0.sp) 0.em else tracking,
            lineHeight = lineHeight?.let { Px.sp(it) } ?: TextUnit.Unspecified,
        ),
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        softWrap = maxLines > 1,
    )
}

/**
 * 1080p pixels to dp and sp. The root sets [scale] from the screen's width (a TV's UI is
 * usually 960 dp wide, so 0.5), and [fontScale] so text keeps its measured size whatever the
 * system font size is.
 */
object Px {
    var scale = 0.5f
    var fontScale = 1f

    fun u(v: Float): androidx.compose.ui.unit.Dp = androidx.compose.ui.unit.Dp(v * scale)
    fun u(v: Int): androidx.compose.ui.unit.Dp = u(v.toFloat())
    fun sp(v: Float): TextUnit = (v * scale / fontScale).sp
}
