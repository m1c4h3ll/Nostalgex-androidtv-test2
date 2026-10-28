package app.nostalgex.tv.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import app.nostalgex.tv.R
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun Logo(height: Dp, modifier: Modifier = Modifier) {
    Image(
        painter = painterResource(R.drawable.logo_wordmark),
        contentDescription = "Nostalgex",
        contentScale = ContentScale.Fit,
        modifier = modifier.height(height),
    )
}

/** A focusable row or button in the connect screens' look: a dark slab, a cyan frame when focused. */
@Composable
fun Slab(
    text: String,
    focused: Boolean,
    modifier: Modifier = Modifier,
    accent: Color = Nx.Cyan,
    sub: String? = null,
    trailing: String? = null,
    size: Float = 28f,
) {
    Box(
        modifier
            .background(if (focused) accent.copy(alpha = 0.22f) else Color(0xFF12122A))
            .border(Px.u(if (focused) 3f else 1.5f), if (focused) accent else Color.White.copy(alpha = 0.15f))
            .padding(horizontal = Px.u(30f), vertical = Px.u(18f)),
    ) {
        androidx.compose.foundation.layout.Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            androidx.compose.foundation.layout.Column(Modifier.weight(1f)) {
                T(text, size, if (focused) Color.White else Color.White.copy(alpha = 0.8f), medium = true)
                if (sub != null) T(sub, size * 0.72f, Color.White.copy(alpha = 0.55f), maxLines = 2)
            }
            if (trailing != null) T(trailing, size, if (focused) accent else Color.White.copy(alpha = 0.6f), retro = true)
        }
    }
}

/** A thin progress bar. */
@Composable
fun Bar(fraction: Float, color: Color, modifier: Modifier = Modifier, height: Float = 4f) {
    Box(modifier.height(Px.u(height)).background(Color.White.copy(alpha = 0.12f))) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(fraction.coerceIn(0f, 1f)).background(color))
    }
}

object Fmt {
    private val clock = DateTimeFormatter.ofPattern("h:mm a", Locale.US)

    fun clock(sec: Long): String = clock.format(Instant.ofEpochSecond(sec).atZone(ZoneId.systemDefault()))

    /** 1:52:12, or 52:12 under an hour. */
    fun hms(totalSec: Long): String {
        val s = maxOf(0L, totalSec)
        val h = s / 3600
        val m = (s % 3600) / 60
        val ss = s % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, ss) else "%d:%02d".format(m, ss)
    }

    fun minutes(min: Int): String = if (min >= 60) "${min / 60}h ${min % 60}m" else "${min}m"

    fun seasonEpisode(season: Int?, episode: Int?): String {
        val sNum = season?.takeIf { it > 0 }?.let { "S%02d".format(it) } ?: ""
        val eNum = episode?.takeIf { it > 0 }?.let { "E%02d".format(it) } ?: ""
        return sNum + eNum
    }
}

/** What the guide prints for an item: "Show: Episode", "Artist — Song", or the title. */
fun displayLine(item: app.nostalgex.engine.model.MediaItem): String = when {
    item.librarySource == "musicVideo" && item.type != "episode" && !item.artist.isNullOrEmpty() -> "${item.artist} — ${item.title}"
    item.type == "episode" && !item.episodeTitle.isNullOrEmpty() -> item.title
    else -> item.title
}

fun Modifier.padH(v: Float) = this.padding(horizontal = Px.u(v))
