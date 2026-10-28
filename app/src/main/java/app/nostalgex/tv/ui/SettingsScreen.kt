package app.nostalgex.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import app.nostalgex.engine.schedule.Season
import app.nostalgex.tv.BuildConfig
import app.nostalgex.tv.tuner.AppViewModel
import app.nostalgex.tv.tuner.SettingsRow
import java.time.LocalDateTime

/** Settings, after the Apple TV's SettingsPageView: packages, the look, the connection. */
@Composable
fun SettingsScreen(vm: AppViewModel) {
    val rows = vm.settingsRows()
    val focus = vm.settingsFocus.coerceIn(0, rows.size - 1)
    val visible = 8
    val top = (focus - 3).coerceIn(0, maxOf(0, rows.size - visible))
    val now = LocalDateTime.now()
    Row(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Nx.Panel0, Color(0xFF14102A))))) {
        // Left: the list.
        Column(
            Modifier.width(Px.u(1180f)).fillMaxHeight().padding(start = Px.u(90f), top = Px.u(60f), end = Px.u(40f)).clipToBounds(),
            verticalArrangement = Arrangement.spacedBy(Px.u(12f)),
        ) {
            T("SETTINGS", 44f, Nx.Yellow, retro = true, tracking = Px.sp(3f))
            Spacer(Modifier.height(Px.u(8f)))
            if (top > 0) T("▲", 22f, Color.White.copy(alpha = 0.4f))
            for (i in top until minOf(rows.size, top + visible)) {
                val row = rows[i]
                val f = i == focus
                when (row) {
                    is SettingsRow.Package -> {
                        val b = row.bundle
                        val on = vm.enabledBundles.contains(b.id)
                        val (avail, total) = vm.packageCounts(b)
                        val season = Season.spotlightLabel(b, now)
                        val sub = buildString {
                            append(b.description)
                            append(" · $avail of $total channels have enough to air")
                            if (season.isNotEmpty() && !Season.isInSeason(b, now)) append(" · $season")
                        }
                        Slab(b.name, f, Modifier.fillMaxWidth(), sub = sub, trailing = if (on) "ON" else "OFF", size = 26f)
                    }
                    SettingsRow.Retro -> Slab("RETRO MODE", f, Modifier.fillMaxWidth(), sub = "The picture cropped to 4:3 under scanlines, and snow between channels", trailing = if (vm.retroMode) "ON" else "OFF", size = 26f, accent = Nx.Pink)
                    SettingsRow.Captions -> Slab("SUBTITLES", f, Modifier.fillMaxWidth(), sub = "Show subtitles when a programme has them", trailing = if (vm.captions) "ON" else "OFF", size = 26f, accent = Nx.Pink)
                    SettingsRow.Rescan -> Slab("RESCAN LIBRARY", f, Modifier.fillMaxWidth(), sub = "Read every server again, behind the guide", size = 26f, accent = Nx.Yellow)
                    SettingsRow.SignOut -> Slab("SIGN OUT", f, Modifier.fillMaxWidth(), sub = vm.session?.displayName ?: "", size = 26f, accent = Nx.Amber)
                }
            }
            if (top + visible < rows.size) T("▼", 22f, Color.White.copy(alpha = 0.4f))
        }
        // Right: about.
        Column(
            Modifier.fillMaxHeight().weight(1f).padding(top = Px.u(150f), end = Px.u(80f)),
            verticalArrangement = Arrangement.spacedBy(Px.u(18f)),
        ) {
            Logo(Px.u(46f))
            T("${vm.guideChannels.size} CHANNELS ON · ${vm.libraryCount} PROGRAMMES", 26f, Nx.Cyan, retro = true)
            T(vm.session?.displayName ?: "", 22f, Color.White.copy(alpha = 0.6f), maxLines = 2)
            Spacer(Modifier.height(Px.u(20f)))
            T("OK switches · BACK saves and returns to the guide", 20f, Color.White.copy(alpha = 0.45f), maxLines = 2)
            Spacer(Modifier.weight(1f))
            Box(Modifier.padding(bottom = Px.u(50f))) {
                T(
                    "NOSTALGEX FOR ANDROID TV ${BuildConfig.VERSION_NAME}\nChannels, schedule and guide from Nostalgex by Chad Mueller (MIT), github.com/chadMueller/Nostalgex. Fonts: VT323, DM Mono (OFL).",
                    17f, Color.White.copy(alpha = 0.35f), maxLines = 5, lineHeight = 24f,
                )
            }
        }
    }
}
