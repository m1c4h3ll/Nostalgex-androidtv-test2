package app.nostalgex.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import app.nostalgex.engine.model.Channel
import app.nostalgex.engine.schedule.ChannelSchedule
import app.nostalgex.tv.i18n.S
import app.nostalgex.tv.tuner.AppViewModel
import app.nostalgex.tv.tuner.PlayState
import java.time.LocalDateTime

private const val CHANNEL_COLUMN = 220f
private const val ROW_HEIGHT = 80f
private const val HEADER_HEIGHT = 60f
private const val WINDOW_SEC = 7200L // four half hours

/** TunerView: nav bar and info panel at the top left, the picture top right, the grid below. */
@Composable
fun GuideScreen(vm: AppViewModel, geo: GuideGeometry) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().height(Px.u(geo.videoHeight))) {
            Box(
                Modifier.width(Px.u(geo.panelWidth)).fillMaxHeight()
                    .background(Brush.linearGradient(listOf(Nx.Panel0, Nx.Panel1))),
            ) {
                Column(Modifier.fillMaxSize()) {
                    NavBar(focused = vm.guideFocus == -2)
                    val previewId = vm.guideChannels.getOrNull(vm.guideFocus)?.id ?: vm.currentChannelId
                    val ch = vm.channelById(previewId)
                    val sched = ch?.let { vm.schedules[it.id] }
                    if (ch != null && sched != null) InfoPanel(vm, ch, sched)
                }
            }
            // The video box: the PlayerView behind shows through here.
            Spacer(Modifier.width(Px.u(geo.videoWidth)).fillMaxHeight())
        }
        Grid(vm, Modifier.fillMaxWidth().weight(1f))
    }
}

@Composable
private fun NavBar(focused: Boolean) {
    Row(
        Modifier.fillMaxWidth().height(Px.u(140f)).padding(start = Px.u(40f), end = Px.u(40f), top = Px.u(40f)),
        verticalAlignment = Alignment.Top,
    ) {
        Logo(Px.u(54f), Modifier.padding(top = Px.u(10f)))
        Spacer(Modifier.weight(1f))
        Box(
            Modifier.background(if (focused) Nx.Yellow.copy(alpha = 0.15f) else Color.Transparent)
                .padding(horizontal = Px.u(14f), vertical = Px.u(8f)),
        ) {
            T(S.settings, 24f, if (focused) Nx.Yellow else Color.White.copy(alpha = 0.5f), medium = true)
        }
    }
}

/** InfoPanelView: channel tag, title, episode, description, year, progress, up next. */
@Composable
private fun InfoPanel(vm: AppViewModel, ch: Channel, sched: ChannelSchedule) {
    val color = Nx.hex(ch.colorHex)
    val isCurrent = ch.id == vm.currentChannelId
    val now = sched.nowPlaying
    val item = if (isCurrent) vm.currentItem ?: now?.item else now?.item
    Column(Modifier.fillMaxSize().padding(start = Px.u(40f), end = Px.u(40f), top = Px.u(4f))) {
        T(
            S.channelTag(ch.number, ch.name.uppercase()), 36f, color, retro = true,
            tracking = Px.sp(2f), modifier = Modifier.padding(bottom = Px.u(14f)),
        )
        if (item != null) {
            T(displayLine(item), 38f, Color.White, medium = true, modifier = Modifier.padding(bottom = Px.u(8f)))
            val epLine = when {
                item.type == "episode" -> listOfNotNull(
                    item.episodeTitle?.takeIf { it.isNotEmpty() }?.let { S.quote(it) },
                    Fmt.seasonEpisode(item.seasonNumber, item.episodeNumber).takeIf { it.isNotEmpty() },
                ).joinToString(" · ")
                !item.director.isNullOrEmpty() -> item.director!!
                else -> ""
            }
            if (epLine.isNotEmpty()) T(epLine, 22f, Color.White.copy(alpha = 0.7f), modifier = Modifier.padding(bottom = Px.u(8f)))
            Box(Modifier.fillMaxWidth().height(Px.u(1f)).background(Color.White.copy(alpha = 0.15f)))
            Spacer(Modifier.height(Px.u(14f)))
            if (item.summary.isNotEmpty()) {
                T(item.summary, 20f, Color.White.copy(alpha = 0.65f), maxLines = 2, lineHeight = 28f, modifier = Modifier.padding(bottom = Px.u(14f)))
            }
            // Year badge, progress bar and times.
            val start = if (isCurrent && vm.currentItem != null) vm.airingStartSec.toLong() else now?.startSec ?: 0L
            val total = item.duration * 60L
            val elapsed = (vm.nowSec - start).coerceIn(0L, total)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Px.u(14f))) {
                item.year?.let {
                    Box(Modifier.border(Px.u(1.5f), Color.White.copy(alpha = 0.3f)).padding(horizontal = Px.u(10f), vertical = Px.u(2f))) {
                        T(it.toString(), 24f, Color.White.copy(alpha = 0.7f), retro = true)
                    }
                }
                Bar(if (total > 0) elapsed.toFloat() / total else 0f, color, Modifier.weight(1f))
                T("${Fmt.hms(elapsed)} / ${Fmt.hms(total)}", 24f, Color.White.copy(alpha = 0.55f), retro = true)
            }
            Spacer(Modifier.height(Px.u(16f)))
            val next = sched.upNext
            if (next != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    T(S.upNext + " ", 24f, Color.White.copy(alpha = 0.5f), retro = true, tracking = Px.sp(2f))
                    T(displayLine(next.item), 18f, Color.White.copy(alpha = 0.75f))
                }
            }
        }
    }
}

/** ChannelGuideView: a header of half hours, then a row per channel. */
@Composable
private fun Grid(vm: AppViewModel, modifier: Modifier) {
    val windowStart = (vm.nowSec / 1800) * 1800
    val programsWidth = 1920f - CHANNEL_COLUMN
    Box(modifier.background(Nx.GridBg).clipToBounds()) {
        Column(Modifier.fillMaxSize()) {
            // Header
            Row(Modifier.fillMaxWidth().height(Px.u(HEADER_HEIGHT)).background(Nx.HeaderBg), verticalAlignment = Alignment.CenterVertically) {
                T(S.channelColumn, 18f, Color.White.copy(alpha = 0.7f), medium = true, modifier = Modifier.width(Px.u(CHANNEL_COLUMN)).padding(start = Px.u(12f)))
                for (i in 0 until 4) {
                    Row(Modifier.width(Px.u(programsWidth / 4)), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.width(Px.u(1f)).height(Px.u(HEADER_HEIGHT)).background(Color.White.copy(alpha = 0.2f)))
                        T(Fmt.clock(windowStart + i * 1800L), 20f, Color.White.copy(alpha = 0.95f), medium = true, modifier = Modifier.padding(start = Px.u(14f)))
                    }
                }
            }
            Box(Modifier.fillMaxWidth().height(Px.u(1f)).background(Color.White.copy(alpha = 0.25f)))
            RowsArea(vm, windowStart, programsWidth)
        }
        // The now line.
        val x = CHANNEL_COLUMN + (vm.nowSec - windowStart).toFloat() / WINDOW_SEC * programsWidth
        Box(Modifier.offset(x = Px.u(x)).width(Px.u(2f)).fillMaxHeight().background(Nx.Cyan))
    }
}

@Composable
private fun RowsArea(vm: AppViewModel, windowStart: Long, programsWidth: Float) {
    val channels = vm.guideChannels
    val invite = vm.seasonalOffer
    if (channels.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                T(S.noChannelsFound, 30f, Nx.Yellow, retro = true)
                T(S.noChannelsHint, 22f, Color.White.copy(alpha = 0.6f))
            }
        }
        return
    }
    // Rows that fit, scrolled so the focused one stays in view, two rows below the top where it can.
    val visible = 6
    val focus = vm.guideFocus.coerceAtLeast(0)
    val top = (focus - 2).coerceIn(0, maxOf(0, channels.size - visible))
    Column(Modifier.fillMaxSize()) {
        if (invite != null && top == 0) InviteRow(vm, invite, focused = vm.guideFocus == -1)
        for (i in top until minOf(channels.size, top + visible)) {
            val ch = channels[i]
            ChannelRow(vm, ch, vm.schedules[ch.id], focused = vm.guideFocus == i, windowStart, programsWidth)
        }
    }
}

@Composable
private fun InviteRow(vm: AppViewModel, bundle: app.nostalgex.engine.model.Bundle, focused: Boolean) {
    val accent = when (bundle.id) {
        "seasonal" -> Nx.hex("#FF6B00")
        "tis-the-season" -> Nx.hex("#E74C3C")
        else -> Nx.Yellow
    }
    Row(
        Modifier.fillMaxWidth().height(Px.u(72f)).background(if (focused) accent.copy(alpha = 0.85f) else accent.copy(alpha = 0.22f))
            .padding(horizontal = Px.u(20f)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Px.u(18f)),
    ) {
        T(S.inviteHeadline(bundle, LocalDateTime.now()), 22f, if (focused) Color.Black else Color.White, medium = true, modifier = Modifier.weight(1f))
        val labels = S.inviteChoices
        labels.forEachIndexed { i, l ->
            val sel = focused && vm.inviteChoice == i
            Box(
                Modifier.background(if (sel) Color.Black else if (focused) Color.Black.copy(alpha = 0.18f) else accent)
                    .padding(horizontal = Px.u(14f), vertical = Px.u(6f)),
            ) { T(l, 16f, if (sel) accent else Color.Black, medium = true) }
        }
    }
}

@Composable
private fun ChannelRow(
    vm: AppViewModel,
    ch: Channel,
    sched: ChannelSchedule?,
    focused: Boolean,
    windowStart: Long,
    programsWidth: Float,
) {
    val color = Nx.hex(ch.colorHex)
    val isLive = ch.id == vm.currentChannelId
    Row(
        Modifier.fillMaxWidth().height(Px.u(ROW_HEIGHT))
            .background(if (focused) color.copy(alpha = 0.32f) else Color.White.copy(alpha = 0.03f))
            .then(if (focused) Modifier.border(Px.u(2f), color) else Modifier),
    ) {
        // Channel column
        Row(Modifier.width(Px.u(CHANNEL_COLUMN)).fillMaxHeight(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(Px.u(if (isLive || focused) 8f else 4f)).fillMaxHeight().background(if (isLive || focused) color else color.copy(alpha = 0.4f)))
            Spacer(Modifier.width(Px.u(if (isLive) 8f else 10f)))
            if (isLive) {
                Box(Modifier.background(color).padding(horizontal = Px.u(4f), vertical = Px.u(2f))) {
                    T(if (vm.playState is PlayState.Playing) "▶" else "…", 12f, Color.Black)
                }
                Spacer(Modifier.width(Px.u(6f)))
            }
            T(ch.number.toString(), 20f, if (focused) Color.White else color, medium = true, modifier = Modifier.padding(end = Px.u(8f)))
            T(ch.name.uppercase(), 18f, if (focused) Color.White else color.copy(alpha = 0.95f), modifier = Modifier.weight(1f).padding(end = Px.u(6f)))
            Box(Modifier.width(Px.u(1f)).fillMaxHeight().background(Color.White.copy(alpha = 0.15f)))
        }
        // Programmes
        Box(Modifier.width(Px.u(programsWidth)).fillMaxHeight().clipToBounds()) {
            val windowEnd = windowStart + WINDOW_SEC
            sched?.entries?.forEach { e ->
                if (e.endSec <= windowStart || e.startSec >= windowEnd) return@forEach
                val s = maxOf(e.startSec, windowStart)
                val t = minOf(e.endSec, windowEnd)
                val x = (s - windowStart).toFloat() / WINDOW_SEC * programsWidth
                val w = maxOf(1f, (t - s).toFloat() / WINDOW_SEC * programsWidth)
                val onNow = e.isNowPlaying
                Box(
                    Modifier.offset(x = Px.u(x)).width(Px.u(w)).fillMaxHeight()
                        .background(
                            when {
                                onNow -> color.copy(alpha = if (focused) 0.55f else 0.25f)
                                else -> Color.White.copy(alpha = 0.02f)
                            },
                        ),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Box(Modifier.width(Px.u(1f)).fillMaxHeight().background(Color.White.copy(alpha = 0.12f)))
                    T(
                        displayLine(e.item), 20f,
                        if (e.isConflict) Color.White.copy(alpha = 0.5f) else Color.White.copy(alpha = 0.85f),
                        modifier = Modifier.padding(start = Px.u(14f), end = Px.u(8f)),
                    )
                }
            }
        }
    }
    Box(Modifier.fillMaxWidth().height(Px.u(1f)).background(Color.White.copy(alpha = 0.08f)))
}

@Suppress("unused")
private fun sizeOf(v: Float) = Modifier.size(Px.u(v))
