package app.nostalgex.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import app.nostalgex.tv.tuner.AppViewModel
import app.nostalgex.tv.tuner.Overlay
import app.nostalgex.tv.tuner.PlayState
import app.nostalgex.tv.tuner.Playback
import app.nostalgex.tv.i18n.S
import kotlinx.coroutines.delay

/** Over the picture while it tunes, fails or stops: snow (RETRO MODE) or a dim TUNING card. */
@Composable
fun TuningLayer(vm: AppViewModel, compact: Boolean) {
    val ch = vm.channelById(vm.currentChannelId)
    val scale = if (compact) 0.6f else 1f
    when (val st = vm.playState) {
        PlayState.Tuning -> {
            if (vm.retroMode) StaticSnow() else Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.7f)))
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                T(S.tuning(ch?.number), 44f * scale, Color.White, retro = true, tracking = Px.sp(4f))
            }
        }
        is PlayState.Failed -> Card(
            title = vm.currentItem?.let { displayLine(it) } ?: "",
            message = S.failure(st.failure),
            hint = S.nextProgrammeSoon,
            accent = if (st.failure.serverSide) Nx.Amber else Nx.Red,
            scale = scale,
        )
        is PlayState.Stopped -> Card(
            title = ch?.let { S.channelShort(it.number, it.name) } ?: "",
            message = st.message,
            hint = S.pressOkToTryAgain,
            accent = Nx.Amber,
            scale = scale,
        )
        PlayState.Idle -> Box(Modifier.fillMaxSize().background(Color.Black))
        PlayState.Playing -> {}
    }
}

@Composable
private fun Card(title: String, message: String, hint: String, accent: Color, scale: Float) {
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.75f)), contentAlignment = Alignment.Center) {
        Column(
            Modifier.border(Px.u(3f * scale), accent).background(Color(0xE6080816)).padding(Px.u(36f * scale)),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Px.u(12f * scale)),
        ) {
            if (title.isNotEmpty()) T(title, 26f * scale, Color.White.copy(alpha = 0.7f))
            T(message, 34f * scale, accent, medium = true, maxLines = 2)
            T(hint, 24f * scale, Color.White.copy(alpha = 0.5f), retro = true)
        }
    }
}

/** Full screen (PlayerView): the channel OSD, the mini channel strip, the Now Playing panel. */
@Composable
fun FullscreenOverlay(vm: AppViewModel) {
    var tick by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(vm.osdUntil) {
        while (System.currentTimeMillis() < vm.osdUntil) {
            delay(250)
            tick = System.currentTimeMillis()
        }
        tick = System.currentTimeMillis()
    }
    val showOsd = tick < vm.osdUntil || vm.playState !is PlayState.Playing
    Box(Modifier.fillMaxSize()) {
        if (showOsd && vm.overlay == Overlay.NONE) Osd(vm)
        if (vm.digits.isNotEmpty()) {
            Box(Modifier.fillMaxSize().padding(Px.u(60f)), contentAlignment = Alignment.TopEnd) {
                T(vm.digits, 96f, Nx.Yellow, retro = true)
            }
        }
        when (vm.overlay) {
            Overlay.STRIP -> MiniStrip(vm)
            Overlay.NOW_PLAYING -> NowPlayingPanel(vm)
            Overlay.NONE -> {}
        }
    }
}

@Composable
private fun Osd(vm: AppViewModel) {
    val ch = vm.channelById(vm.currentChannelId) ?: return
    val item = vm.currentItem
    val color = Nx.hex(ch.colorHex)
    Box(
        Modifier.fillMaxWidth().height(Px.u(260f))
            .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.85f), Color.Transparent))),
    ) {
        Row(Modifier.padding(start = Px.u(80f), top = Px.u(56f)), verticalAlignment = Alignment.Top) {
            Box(Modifier.border(Px.u(3f), color).padding(horizontal = Px.u(18f), vertical = Px.u(4f))) {
                T(ch.number.toString(), 72f, color, retro = true)
            }
            Spacer(Modifier.width(Px.u(28f)))
            Column {
                T(ch.name.uppercase(), 34f, color, retro = true, tracking = Px.sp(2f))
                if (item != null) {
                    T(displayLine(item), 36f, Color.White, medium = true)
                    val left = (vm.airingStartSec + item.duration * 60 - vm.nowSec).toLong()
                    val ep = if (item.type == "episode") item.episodeTitle?.takeIf { it.isNotEmpty() }?.let { S.quote(it) + " · " } ?: "" else ""
                    T(ep + S.minutesLeft(maxOf(0L, left / 60), Fmt.clock(vm.nowSec)), 24f, Color.White.copy(alpha = 0.6f), retro = true)
                }
            }
        }
    }
}

@Composable
private fun MiniStrip(vm: AppViewModel) {
    val list = vm.guideChannels
    if (list.isEmpty()) return
    val focus = vm.stripFocus.coerceIn(0, list.size - 1)
    val start = (focus - 2).coerceIn(0, maxOf(0, list.size - 5))
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        Row(
            Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.9f))))
                .padding(horizontal = Px.u(60f), vertical = Px.u(50f)),
            horizontalArrangement = Arrangement.spacedBy(Px.u(18f)),
        ) {
            for (i in start until minOf(list.size, start + 5)) {
                val ch = list[i]
                val color = Nx.hex(ch.colorHex)
                val sel = i == focus
                val now = vm.schedules[ch.id]?.nowPlaying?.item
                Column(
                    Modifier.weight(1f).background(if (sel) color.copy(alpha = 0.35f) else Color(0xCC101024))
                        .border(Px.u(if (sel) 3f else 1f), if (sel) color else Color.White.copy(alpha = 0.15f))
                        .padding(Px.u(18f)),
                ) {
                    T("${ch.number}  ${ch.name.uppercase()}", 22f, color, retro = true)
                    T(now?.let { displayLine(it) } ?: "", 20f, Color.White.copy(alpha = if (sel) 1f else 0.7f))
                }
            }
        }
    }
}

@Composable
private fun NowPlayingPanel(vm: AppViewModel) {
    val ch = vm.channelById(vm.currentChannelId) ?: return
    val item = vm.currentItem
    val color = Nx.hex(ch.colorHex)
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        Column(
            Modifier.fillMaxWidth().background(Color(0xF0070714)).padding(horizontal = Px.u(80f), vertical = Px.u(44f)),
            verticalArrangement = Arrangement.spacedBy(Px.u(12f)),
        ) {
            T(S.channelTag(ch.number, ch.name.uppercase()), 32f, color, retro = true, tracking = Px.sp(2f))
            if (item != null) {
                T(displayLine(item), 38f, Color.White, medium = true)
                val meta = listOfNotNull(
                    item.episodeTitle?.takeIf { item.type == "episode" && it.isNotEmpty() }?.let { S.quote(it) },
                    Fmt.seasonEpisode(item.seasonNumber, item.episodeNumber).takeIf { it.isNotEmpty() },
                    item.year?.toString(),
                    item.contentRating?.takeIf { it.isNotEmpty() },
                    Fmt.minutes(item.duration),
                ).joinToString(" · ")
                T(meta, 22f, Color.White.copy(alpha = 0.6f))
                if (item.summary.isNotEmpty()) T(item.summary, 20f, Color.White.copy(alpha = 0.65f), maxLines = 3, lineHeight = 28f)
                val total = item.duration * 60L
                val elapsed = (vm.nowSec - vm.airingStartSec.toLong()).coerceIn(0L, total)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Px.u(14f))) {
                    Bar(if (total > 0) elapsed.toFloat() / total else 0f, color, Modifier.weight(1f))
                    T("${Fmt.hms(elapsed)} / ${Fmt.hms(total)}", 24f, Color.White.copy(alpha = 0.55f), retro = true)
                }
                vm.schedules[ch.id]?.upNext?.let { T("${S.upNext}  ${displayLine(it.item)}", 22f, Color.White.copy(alpha = 0.6f), retro = true) }
            }
            Spacer(Modifier.height(Px.u(8f)))
            Row(horizontalArrangement = Arrangement.spacedBy(Px.u(18f))) {
                Slab(S.subtitles, vm.panelFocus == 0, trailing = if (vm.captions) S.on else S.off, modifier = Modifier.width(Px.u(420f)), size = 24f)
                Slab(S.retroMode, vm.panelFocus == 1, trailing = if (vm.retroMode) S.on else S.off, modifier = Modifier.width(Px.u(420f)), size = 24f)
            }
            T(playbackInfo(vm), 20f, Color.White.copy(alpha = 0.4f))
        }
    }
}

/** PLAYBACK INFORMATION in one line: how the programme reaches the TV. */
private fun playbackInfo(vm: AppViewModel): String {
    val item = vm.currentItem ?: return ""
    val how = when (vm.currentRung) {
        Playback.Rung.DIRECT -> S.directPlay
        Playback.Rung.SERVER -> S.serverStream
        Playback.Rung.CAPPED -> S.serverStream1080
        null -> S.starting
    }
    val file = listOfNotNull(
        item.container?.uppercase(), item.videoCodec?.uppercase(), item.audioCodec?.uppercase(),
        item.videoHeight.takeIf { it > 0 }?.let { "${it}p" },
        item.bitrate.takeIf { it > 0 }?.let { S.bitrate(it) },
    ).joinToString(" · ")
    return "$how · $file"
}
