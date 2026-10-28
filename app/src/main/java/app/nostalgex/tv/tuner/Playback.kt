package app.nostalgex.tv.tuner

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.extractor.DefaultExtractorsFactory
import app.nostalgex.engine.model.MediaItem
import app.nostalgex.engine.server.DeviceCaps
import app.nostalgex.engine.server.Http
import app.nostalgex.engine.server.MediaBrowser
import app.nostalgex.engine.server.MediaBrowserStreams
import app.nostalgex.engine.server.PlexAuth
import app.nostalgex.engine.server.PlexStreams
import app.nostalgex.engine.server.Session
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale
import androidx.media3.common.MediaItem as ExoItem

/**
 * Plays one programme the way a channel airs it: joined at the point the clock says, with
 * upstream's fallback ladder (`AppState+Playback.swift`, ported to Roku as `Fallback.brs`):
 *
 *  - direct play, when this TV decodes the file as stored;
 *  - the server stream (Plex's transcoder, or Jellyfin/Emby's HLS), copying what it can;
 *  - the 1080p stream (Plex 12 Mb/s with no HEVC, Jellyfin and Emby 8 Mb/s at 1920 wide).
 *
 * A start that errors, or shows no picture by its deadline (15 s direct, 20 s a server stream,
 * 40 s a 4K one), moves down a rung at the same point in the airing. A server stream that errors
 * gets one retry, unless direct play came first. A picture that starves after it started (under
 * 0.25 s of progress for 3 s is a stall; three in 120 s, or one of 20 s) moves down too. When
 * nothing is left, [Events.onFailed] says why.
 */
@OptIn(UnstableApi::class)
class Playback(
    context: Context,
    private val scope: CoroutineScope,
    private val http: Http,
    private val caps: () -> DeviceCaps,
    private val clientId: String,
) {
    enum class Rung { DIRECT, SERVER, CAPPED }

    data class Request(val item: MediaItem, val session: Session, val airingStartSec: Double, val token: Int)

    enum class Failure(val message: String, val serverSide: Boolean) {
        UNREACHABLE("Can't reach your server", true),
        REFUSED("Your server wouldn't start this one", true),
        SIGNED_OUT("Your server turned down the sign-in", true),
        SLOW("Your server couldn't keep up", true),
        NO_PICTURE("Your server took too long to start this one", true),
        UNDECODABLE("This TV can't play this file", false),
        UNKNOWN("This one won't play", false),
    }

    interface Events {
        fun onFirstFrame(req: Request, rung: Rung)
        fun onEnded(req: Request)
        fun onFailed(req: Request, failure: Failure)
    }

    var events: Events? = null

    val player: ExoPlayer = ExoPlayer.Builder(context)
        .setAudioAttributes(
            AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),
            true,
        )
        .setHandleAudioBecomingNoisy(true)
        .build()

    private class Active(val req: Request, val rungs: List<Playback.Rung>) {
        var index = 0
        var firstFrame = false
        var retried = false
        var hls = false
        var startOffsetMs = 0L
        var startChecked = false
        var stop: (() -> Unit)? = null
        var deadline: Job? = null
        var monitor: Job? = null
        var retry: Job? = null
        val rung get() = rungs[index]
    }

    private var active: Active? = null

    /** The rung playing now, for PLAYBACK INFORMATION. */
    val currentRung: Rung? get() = active?.rung

    init {
        player.addListener(object : Player.Listener {
            override fun onRenderedFirstFrame() {
                val a = active ?: return
                if (a.firstFrame) return
                a.firstFrame = true
                a.deadline?.cancel()
                startMonitor(a)
                events?.onFirstFrame(a.req, a.rung)
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                val a = active ?: return
                if (playbackState == Player.STATE_READY && !a.startChecked) {
                    a.startChecked = true
                    // A playlist that starts at the offset rather than at 0:00 (copyts off on
                    // some servers) is shorter than the offset: play it from its start.
                    val d = player.duration
                    if (a.hls && a.startOffsetMs > 0 && d != C.TIME_UNSET && d + 5_000 < a.startOffsetMs) player.seekTo(0)
                }
                if (playbackState == Player.STATE_ENDED) {
                    teardown(stopStream = true)
                    events?.onEnded(a.req)
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                val a = active ?: return
                step(a, classify(error), fromError = true)
            }
        })
        setCaptions(false)
    }

    fun play(req: Request) {
        teardown(stopStream = true)
        val c = caps()
        val direct = when (val s = req.session) {
            is Session.Plex -> PlexStreams.canDirectPlay(req.item, c)
            is Session.MediaBrowser -> MediaBrowserStreams.canDirectPlay(req.item, c)
        }
        val rungs = buildList { if (direct) add(Rung.DIRECT); add(Rung.SERVER); add(Rung.CAPPED) }
        val a = Active(req, rungs)
        active = a
        start(a, 0)
    }

    /** Stops playback and any server stream behind it. */
    fun stop() {
        teardown(stopStream = true)
        player.stop()
        player.clearMediaItems()
    }

    fun release() {
        stop()
        player.release()
    }

    fun setCaptions(on: Boolean) {
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !on)
            .setPreferredTextLanguage(if (on) Locale.getDefault().language else null)
            .setSelectUndeterminedTextLanguage(on)
            .build()
    }

    private fun secondsIn(req: Request): Long {
        val now = System.currentTimeMillis() / 1000.0
        val total = req.item.duration * 60L
        return maxOf(0L, minOf(total - 10, (now - req.airingStartSec).toLong()))
    }

    private class Stream(val url: String, val headers: Map<String, String>, val hls: Boolean, val stop: (() -> Unit)?)

    private fun resolve(req: Request, rung: Rung, offsetSec: Long): Stream? {
        val c = caps()
        return when (val s = req.session) {
            is Session.Plex -> {
                val server = s.servers.getOrNull(req.item.serverIndex) ?: return null
                val headers = mapOf(
                    "X-Plex-Token" to server.token,
                    "X-Plex-Client-Identifier" to clientId,
                    "X-Plex-Product" to PlexAuth.PRODUCT,
                )
                if (rung == Rung.DIRECT) Stream(PlexStreams.directPlayUrl(server, req.item), headers, false, null)
                else {
                    val session = PlexStreams.newSession()
                    val url = PlexStreams.transcodeUrl(server, req.item, offsetSec, session, clientId, c, rung == Rung.CAPPED)
                    val stopUrl = PlexStreams.stopUrl(server, session)
                    Stream(url, headers, true) { fireAndForget("GET", stopUrl, headers) }
                }
            }
            is Session.MediaBrowser -> {
                val mb = s.session
                // Jellyfin reads Authorization; Emby also takes its own spellings.
                val auth = MediaBrowser.authHeader(mb.deviceId, mb.token)
                val headers = mapOf("Authorization" to auth, "X-Emby-Authorization" to auth, "X-Emby-Token" to mb.token)
                if (rung == Rung.DIRECT) Stream(MediaBrowserStreams.staticStreamUrl(mb, req.item), headers, false, null)
                else {
                    val ps = MediaBrowserStreams.newPlaySessionId()
                    val url = MediaBrowserStreams.hlsUrl(mb, req.item, ps, c, rung == Rung.CAPPED)
                    val stopUrl = MediaBrowserStreams.stopUrl(mb, ps)
                    Stream(url, headers, true) { fireAndForget("DELETE", stopUrl, headers) }
                }
            }
        }
    }

    private fun fireAndForget(method: String, url: String, headers: Map<String, String>) {
        scope.launch(Dispatchers.IO) {
            try { http.request(method, url, headers, null, 8_000) } catch (_: Exception) { }
        }
    }

    private fun buildSource(s: Stream): MediaSource {
        val factory = DefaultHttpDataSource.Factory()
            .setUserAgent("Nostalgex/1.0 (Android TV)")
            .setDefaultRequestProperties(s.headers)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(30_000)
            .setAllowCrossProtocolRedirects(true)
        val builder = ExoItem.Builder().setUri(s.url)
        if (s.hls) builder.setMimeType(MimeTypes.APPLICATION_M3U8)
        val item = builder.build()
        return if (s.hls) HlsMediaSource.Factory(factory).createMediaSource(item)
        else ProgressiveMediaSource.Factory(factory, DefaultExtractorsFactory().setConstantBitrateSeekingEnabled(true))
            .createMediaSource(item)
    }

    private fun start(a: Active, index: Int) {
        if (active !== a) return
        a.index = index
        a.firstFrame = false
        a.startChecked = false
        a.monitor?.cancel()
        a.deadline?.cancel()
        a.stop?.invoke()
        a.stop = null
        val offset = secondsIn(a.req)
        val stream = resolve(a.req, a.rung, offset)
        if (stream == null) {
            fail(a, Failure.UNKNOWN)
            return
        }
        a.stop = stream.stop
        a.hls = stream.hls
        a.startOffsetMs = offset * 1000
        player.setMediaSource(buildSource(stream), offset * 1000)
        player.prepare()
        player.playWhenReady = true
        val fourK = a.req.item.videoHeight > 1200
        val deadlineMs = when {
            a.rung == Rung.DIRECT -> 15_000L
            fourK && a.rung == Rung.SERVER -> 40_000L
            else -> 20_000L
        }
        a.deadline = scope.launch {
            delay(deadlineMs)
            if (active === a && !a.firstFrame) step(a, Failure.NO_PICTURE, fromError = false)
        }
    }

    /** One rung down, or the retry, or the end of the ladder. */
    private fun step(a: Active, failure: Failure, fromError: Boolean) {
        if (active !== a) return
        a.deadline?.cancel()
        a.monitor?.cancel()
        if (failure == Failure.SIGNED_OUT) {
            fail(a, failure)
            return
        }
        // A server stream that errors gets one retry, unless direct play came first.
        if (fromError && a.rung == Rung.SERVER && !a.retried && a.index == 0) {
            a.retried = true
            player.stop()
            a.retry = scope.launch {
                delay(3_000)
                start(a, a.index)
            }
            return
        }
        if (a.index + 1 < a.rungs.size) start(a, a.index + 1) else fail(a, failure)
    }

    private fun fail(a: Active, failure: Failure) {
        if (active !== a) return
        teardown(stopStream = true)
        player.stop()
        events?.onFailed(a.req, failure)
    }

    private fun startMonitor(a: Active) {
        a.monitor?.cancel()
        a.monitor = scope.launch {
            var last = player.currentPosition
            var stalled = 0
            val stalls = ArrayDeque<Long>()
            var playedFor = 0
            while (active === a) {
                delay(1_000)
                if (active !== a) break
                if (!player.playWhenReady) { last = player.currentPosition; continue }
                val pos = player.currentPosition
                val dur = player.duration
                val nearEnd = dur != C.TIME_UNSET && pos > dur - 5_000
                if (pos - last < 250 && !nearEnd && playedFor >= 1) {
                    stalled++
                    if (stalled == 3) {
                        val now = System.currentTimeMillis()
                        stalls.addLast(now)
                        while (stalls.isNotEmpty() && stalls.first() < now - 120_000) stalls.removeFirst()
                    }
                    if (stalled >= 20 || (stalled >= 3 && stalls.size >= 3)) {
                        step(a, Failure.SLOW, fromError = false)
                        break
                    }
                } else {
                    stalled = 0
                    if (pos - last >= 250) playedFor++
                }
                last = pos
            }
        }
    }

    private fun teardown(stopStream: Boolean) {
        val a = active ?: return
        active = null
        a.deadline?.cancel()
        a.monitor?.cancel()
        a.retry?.cancel()
        if (stopStream) a.stop?.invoke()
        a.stop = null
    }

    private fun classify(e: PlaybackException): Failure {
        var cause: Throwable? = e.cause
        while (cause != null) {
            if (cause is HttpDataSource.InvalidResponseCodeException) {
                return when (cause.responseCode) {
                    401, 403 -> Failure.SIGNED_OUT
                    else -> Failure.REFUSED
                }
            }
            cause = cause.cause
        }
        return when (e.errorCode) {
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
            PlaybackException.ERROR_CODE_IO_UNSPECIFIED -> Failure.UNREACHABLE
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
            PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND -> Failure.REFUSED
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
            PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED -> Failure.UNDECODABLE
            PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW -> Failure.SLOW
            else -> Failure.UNKNOWN
        }
    }
}
