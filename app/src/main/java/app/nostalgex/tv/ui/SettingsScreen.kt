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
import app.nostalgex.tv.BuildConfig
import app.nostalgex.engine.schedule.Season
import app.nostalgex.tv.i18n.Language
import app.nostalgex.tv.i18n.S
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
            T(S.settings, 44f, Nx.Yellow, retro = true, tracking = Px.sp(3f))
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
                        val season = S.spotlightLabel(b, now)
                        val sub = buildString {
                            append(S.bundleDescription(b))
                            append(" · ").append(S.channelsWithEnough(avail, total))
                            if (season.isNotEmpty() && !Season.isInSeason(b, now)) append(" · $season")
                        }
                        Slab(b.name, f, Modifier.fillMaxWidth(), sub = sub, trailing = if (on) S.on else S.off, size = 26f)
                    }
                    SettingsRow.Retro -> Slab(S.retroMode, f, Modifier.fillMaxWidth(), sub = S.retroModeSub, trailing = if (vm.retroMode) S.on else S.off, size = 26f, accent = Nx.Pink)
                    SettingsRow.Captions -> Slab(S.subtitles, f, Modifier.fillMaxWidth(), sub = S.subtitlesSub, trailing = if (vm.captions) S.on else S.off, size = 26f, accent = Nx.Pink)
                    SettingsRow.SubtitleLanguage -> Slab(S.subtitleLanguage, f, Modifier.fillMaxWidth(), sub = S.subtitleLanguageSub, trailing = Language.from(vm.subtitleLanguage)?.label ?: vm.subtitleLanguage.uppercase(), size = 26f, accent = Nx.Pink)
                    SettingsRow.AudioLanguage -> Slab(S.audioLanguage, f, Modifier.fillMaxWidth(), sub = S.audioLanguageSub, trailing = vm.audioLanguage?.let { Language.from(it)?.label ?: it.uppercase() } ?: S.original, size = 26f, accent = Nx.Pink)
                    SettingsRow.Startup -> Slab(S.onStartup, f, Modifier.fillMaxWidth(), sub = S.onStartupSub, trailing = if (vm.startOnLastChannel) S.lastChannel else S.guideLabel, size = 26f, accent = Nx.Pink)
                    SettingsRow.UiLanguage -> Slab(S.language, f, Modifier.fillMaxWidth(), sub = S.languageSub, trailing = vm.language.label, size = 26f, accent = Nx.Pink)
                    SettingsRow.Rescan -> Slab(S.rescanLibrary, f, Modifier.fillMaxWidth(), sub = S.rescanSub, size = 26f, accent = Nx.Yellow)
                    SettingsRow.SignOut -> Slab(S.signOut, f, Modifier.fillMaxWidth(), sub = vm.session?.displayName ?: "", size = 26f, accent = Nx.Amber)
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
            T(S.channelsOnSummary(vm.guideChannels.size, vm.libraryCount), 26f, Nx.Cyan, retro = true)
            T(vm.session?.displayName ?: "", 22f, Color.White.copy(alpha = 0.6f), maxLines = 2)
            Spacer(Modifier.height(Px.u(20f)))
            T(S.settingsHint, 20f, Color.White.copy(alpha = 0.45f), maxLines = 2)
            Spacer(Modifier.weight(1f))
            Box(Modifier.padding(bottom = Px.u(50f))) {
                T(
                    S.about(BuildConfig.VERSION_NAME),
                    17f, Color.White.copy(alpha = 0.35f), maxLines = 5, lineHeight = 24f,
                )
            }
        }
    }
}
