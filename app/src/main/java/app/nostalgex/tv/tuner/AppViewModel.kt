package app.nostalgex.tv.tuner

import android.app.Application
import android.view.KeyEvent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.nostalgex.engine.filter.ChannelFilter
import app.nostalgex.engine.filter.PreparedItem
import app.nostalgex.engine.lineup.Lineup
import app.nostalgex.engine.model.Bundle
import app.nostalgex.engine.model.Channel
import app.nostalgex.engine.model.MediaItem
import app.nostalgex.engine.schedule.ChannelSchedule
import app.nostalgex.engine.schedule.DailyManifest
import app.nostalgex.engine.schedule.LineupEntry
import app.nostalgex.engine.schedule.SchedChannel
import app.nostalgex.engine.schedule.Season
import app.nostalgex.engine.schedule.SeasonalAnswer
import app.nostalgex.engine.server.ServerException
import app.nostalgex.engine.server.Session
import app.nostalgex.tv.NostalgexApp
import app.nostalgex.tv.data.LibraryLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDateTime
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

enum class Screen { BOOT, CONNECT, LOADING, GUIDE, SETTINGS, ERROR }

sealed class PlayState {
    data object Idle : PlayState()
    data object Tuning : PlayState()
    data object Playing : PlayState()
    data class Failed(val failure: Playback.Failure) : PlayState()
    data class Stopped(val message: String) : PlayState()
}

enum class Overlay { NONE, STRIP, NOW_PLAYING }

/** A text field being typed into with the TV's keyboard. */
data class TextEntry(val label: String, val initial: String, val password: Boolean, val onDone: (String) -> Unit)

class AppViewModel(app: Application) : AndroidViewModel(app) {
    val graph = (app as NostalgexApp).graph
    private val prefs = graph.prefs
    private val schedule = DailyManifest(graph.manifestStore)
    private val loader = LibraryLoader(graph.http)

    val playback = Playback(app, viewModelScope, graph.http, { graph.caps }, prefs.clientId)
    val connect = ConnectFlow(this, graph)

    // ── Screens ─────────────────────────────────────────────────────────────
    var screen by mutableStateOf(Screen.BOOT)
        private set
    var errorMessage by mutableStateOf("")
        private set
    var textEntry by mutableStateOf<TextEntry?>(null)
    var toast by mutableStateOf<String?>(null)
        private set

    // ── Loading ─────────────────────────────────────────────────────────────
    var loadStep by mutableIntStateOf(0) // 0 preparing, 1 scanning, 2 building, 3 finishing
        private set
    var loadDetail by mutableStateOf("")
        private set
    var loadCount by mutableIntStateOf(0)
        private set
    var loadFraction by mutableStateOf(0f)
        private set
    var stillWaiting by mutableStateOf(false)
        private set
    private val stopWaiting = AtomicBoolean(false)
    @Volatile private var lastProgressAt = 0L
    // Written by the scan's threads, copied into the loading screen's state on the main thread.
    @Volatile private var pendingDetail: String? = null
    @Volatile private var pendingCount = 0
    @Volatile private var pendingFraction = 0f

    // ── Lineup ──────────────────────────────────────────────────────────────
    var session: Session? by mutableStateOf(null)
        private set
    private var library: List<MediaItem> = emptyList()
    private var pools: Map<Int, List<MediaItem>> = emptyMap()
    var guideChannels by mutableStateOf<List<Channel>>(emptyList())
        private set
    var schedules by mutableStateOf<Map<Int, ChannelSchedule>>(emptyMap())
        private set
    var nowSec by mutableLongStateOf(System.currentTimeMillis() / 1000)
        private set
    var enabledBundles by mutableStateOf(prefs.enabledBundles)
        private set
    var retroMode by mutableStateOf(prefs.retroMode)
        private set
    var captions by mutableStateOf(prefs.captions)
        private set
    var seasonalOffer by mutableStateOf<Bundle?>(null)
        private set
    var libraryCount by mutableIntStateOf(0)
        private set

    // ── Tuner ───────────────────────────────────────────────────────────────
    var currentChannelId by mutableStateOf<Int?>(null)
        private set
    var currentItem by mutableStateOf<MediaItem?>(null)
        private set
    var airingStartSec by mutableStateOf(0.0)
        private set
    var playState by mutableStateOf<PlayState>(PlayState.Idle)
        private set
    var currentRung by mutableStateOf<Playback.Rung?>(null)
        private set
    var fullscreen by mutableStateOf(false)
        private set
    var osdUntil by mutableLongStateOf(0L)
        private set
    var overlay by mutableStateOf(Overlay.NONE)
        private set
    var digits by mutableStateOf("")
        private set
    private var digitJob: Job? = null
    private var token = 0
    private var failuresInARow = 0
    private var skipJob: Job? = null
    private var tuneJob: Job? = null

    // ── Focus ───────────────────────────────────────────────────────────────
    /** -2: SETTINGS in the nav bar, -1: the seasonal invite, 0.. the channel rows. */
    var guideFocus by mutableIntStateOf(0)
        private set
    var inviteChoice by mutableIntStateOf(0)
        private set
    var stripFocus by mutableIntStateOf(0)
        private set
    var panelFocus by mutableIntStateOf(0)
        private set
    var settingsFocus by mutableIntStateOf(0)
        private set
    private var bundlesAtSettingsOpen: Set<String> = emptySet()
    private var lastBackAt = 0L

    init {
        playback.events = object : Playback.Events {
            override fun onFirstFrame(req: Playback.Request, rung: Playback.Rung) {
                if (req.token != token) return
                playState = PlayState.Playing
                currentRung = rung
                failuresInARow = 0
            }

            override fun onEnded(req: Playback.Request) {
                if (req.token != token) return
                advance(naturalEnd = true)
            }

            override fun onFailed(req: Playback.Request, failure: Playback.Failure) {
                if (req.token != token) return
                failuresInARow++
                if (failure == Playback.Failure.SIGNED_OUT || failuresInARow >= 5) {
                    playState = PlayState.Stopped(
                        if (failure == Playback.Failure.SIGNED_OUT) "Your server turned down the sign-in. Sign in again in Settings."
                        else "Nothing on this channel will play right now",
                    )
                    return
                }
                playState = PlayState.Failed(failure)
                skipJob?.cancel()
                skipJob = viewModelScope.launch {
                    delay(5_000)
                    if (req.token == token) advance(naturalEnd = false)
                }
            }
        }
        playback.setCaptions(captions)
        viewModelScope.launch { boot() }
        viewModelScope.launch { clock() }
    }

    override fun onCleared() {
        playback.release()
        super.onCleared()
    }

    // ── Start-up ────────────────────────────────────────────────────────────

    private suspend fun boot() {
        // Read the shipped data before anything needs it.
        withContext(Dispatchers.IO) {
            graph.config
            graph.manifest
            graph.caps
        }
        val s = prefs.session
        if (s == null) {
            screen = Screen.CONNECT
            return
        }
        session = s
        startLibrary(s, firstSignIn = false)
    }

    /** After a sign-in from the connect screens. */
    fun signedIn(s: Session) {
        prefs.session = s
        session = s
        graph.libraryStore.clear()
        viewModelScope.launch { startLibrary(s, firstSignIn = true) }
    }

    private suspend fun startLibrary(s: Session, firstSignIn: Boolean) {
        val cached = if (firstSignIn) null else withContext(Dispatchers.IO) { graph.libraryStore.load() }
        if (cached != null && cached.items.isNotEmpty()) {
            showLoading()
            loadStep = 2
            loadDetail = "Your saved library"
            buildLineup(cached.items, s)
            enterGuide()
            // Refresh behind the guide; the guide changes only if the library did.
            refreshLibrary(s, background = true, previousSignature = cached.signature)
        } else {
            refreshLibrary(s, background = false, previousSignature = null)
        }
    }

    private fun showLoading() {
        screen = Screen.LOADING
        loadStep = 0
        loadDetail = ""
        loadCount = 0
        loadFraction = 0f
        stillWaiting = false
    }

    /** Reads every server; with `background`, the guide stays up and changes only if the library did. */
    private suspend fun refreshLibrary(s: Session, background: Boolean, previousSignature: String?): Boolean {
        if (!background) {
            showLoading()
            loadStep = 1
            loadDetail = "Connecting to ${s.displayName}"
        }
        stopWaiting.set(false)
        lastProgressAt = System.currentTimeMillis()
        pendingDetail = null
        val watch = if (background) null else viewModelScope.launch { stallWatch() }
        val result = try {
            withContext(Dispatchers.IO) {
                loader.scan(
                    s,
                    onProgress = { detail, n ->
                        lastProgressAt = System.currentTimeMillis()
                        pendingDetail = detail
                        pendingCount = n
                    },
                    shouldStop = { stopWaiting.get() },
                )
            }
        } catch (e: Exception) {
            watch?.cancel()
            if (background) return false
            val auth = e is ServerException && (e.status == 401 || e.status == 403)
            errorMessage = if (auth) "Your server turned down the sign-in. Sign in again."
            else "Couldn't read your library. ${e.message ?: ""}".trim()
            screen = Screen.ERROR
            return false
        }
        watch?.cancel()
        stillWaiting = false
        if (result.session != s) {
            prefs.session = result.session
            session = result.session
        }
        if (result.items.isEmpty()) {
            if (background) return false
            errorMessage = if (stopWaiting.get()) "Stopped waiting. Nothing had come back from your server."
            else "Your library looks empty. Add some films or shows to your server, then try again."
            screen = Screen.ERROR
            return false
        }
        val sig = LibraryLoader.signature(result.items)
        withContext(Dispatchers.IO) { graph.libraryStore.save(result.items, sig) }
        if (background && sig == previousSignature) return true
        if (!background) {
            loadStep = 2
            loadDetail = "Building channels"
        }
        buildLineup(result.items, result.session)
        if (!background) enterGuide()
        return true
    }

    /**
     * Copies the scan's progress onto the loading screen. 60 s with nothing back puts STOP
     * WAITING on it; 210 s stops the scan (upstream's LibraryLoadStallWatch).
     */
    private suspend fun stallWatch() {
        while (true) {
            delay(300)
            pendingDetail?.let { loadDetail = it }
            loadCount = pendingCount
            val quiet = System.currentTimeMillis() - lastProgressAt
            stillWaiting = quiet >= 60_000
            if (quiet >= 210_000) {
                stopWaiting.set(true)
                return
            }
        }
    }

    fun pressStopWaiting() {
        stopWaiting.set(true)
    }

    /** Every channel's pool (all of them, so a package switch is instant), then the guide. */
    private suspend fun buildLineup(items: List<MediaItem>, s: Session) {
        val config = graph.config
        val total = config.channels.size
        val done = AtomicInteger(0)
        pendingFraction = 0f
        val progress = viewModelScope.launch {
            while (isActive) {
                loadFraction = pendingFraction
                delay(250)
            }
        }
        val built = withContext(Dispatchers.Default) {
            val filter = ChannelFilter(graph.manifest, config.exclusiveRules, s.backend)
            val prepared = items.map(::PreparedItem)
            coroutineScope {
                config.channels.chunked(8).map { group ->
                    async {
                        group.map { ch ->
                            val pool = filter.filterPool(prepared, ch)
                            pendingFraction = done.incrementAndGet().toFloat() / total
                            ch.id to pool
                        }
                    }
                }.awaitAll().flatten().toMap()
            }
        }
        progress.cancel()
        loadFraction = 1f
        library = items
        libraryCount = items.size
        pools = built
        var enabled = enabledBundles
        if (!prefs.completedInitialLoad) {
            // A first lineup with no channel switches on every package that has content.
            val now = LocalDateTime.now()
            if (Lineup.guideChannels(config, enabled, built, now).isEmpty()) {
                val auto = Lineup.bundlesToAutoEnable(config.bundles, enabled, Lineup.availableChannelIDs(config, built))
                if (auto.isNotEmpty()) {
                    enabled = enabled + auto
                    setBundles(enabled)
                }
            }
            prefs.completedInitialLoad = true
        }
        refreshGuideChannels()
    }

    private fun setBundles(b: Set<String>) {
        enabledBundles = b
        prefs.enabledBundles = b
    }

    private fun refreshGuideChannels() {
        val now = LocalDateTime.now()
        val config = graph.config
        guideChannels = Lineup.guideChannels(config, enabledBundles, pools, now)
        seasonalOffer = Season.bundleToOffer(config.bundles, enabledBundles, now) { id ->
            prefs.seasonalAnswer(id)?.silences(now.year) == true
        }
        if (guideFocus >= guideChannels.size) guideFocus = maxOf(0, guideChannels.size - 1)
        viewModelScope.launch { rebuildSchedules() }
    }

    private fun enterGuide() {
        screen = Screen.GUIDE
        if (guideChannels.isEmpty()) return
        val last = prefs.lastChannelId
        val start = guideChannels.firstOrNull { it.id == last } ?: guideChannels.first()
        guideFocus = guideChannels.indexOf(start).coerceAtLeast(0)
        if (currentChannelId == null || guideChannels.none { it.id == currentChannelId }) tune(start.id)
    }

    // ── Schedules ───────────────────────────────────────────────────────────

    private fun hour() = LocalDateTime.now().hour

    private fun poolFor(ch: Channel, hour: Int = hour()): List<MediaItem> =
        Lineup.timeFilteredPool(ch, pools[ch.id] ?: emptyList(), hour).filter { it.duration > 0 }

    private fun lineupEntries(): List<LineupEntry> {
        val h = hour()
        return guideChannels.map { LineupEntry(SchedChannel(it.id, it.isPremiereChannel), poolFor(it, h)) }
    }

    private val manifestKey: String get() = session?.manifestKey ?: "local"

    /** TunerView.buildAllSchedules: every channel at one instant, then one title on two channels resolved. */
    suspend fun rebuildSchedules() {
        val lineup = lineupEntries()
        val key = manifestKey
        val now = System.currentTimeMillis() / 1000.0
        val shown = withContext(Dispatchers.Default) {
            val raw = LinkedHashMap<Int, ChannelSchedule>()
            for ((ch, pool) in lineup) {
                if (pool.isEmpty()) continue
                schedule.buildSchedule(ch, pool, now, key)?.let { raw[ch.id] = it }
            }
            schedule.resolveConflicts(raw)
        }
        schedules = shown
        nowSec = now.toLong()
    }

    private suspend fun clock() {
        var ticks = 0
        while (true) {
            delay(5_000)
            nowSec = System.currentTimeMillis() / 1000
            ticks++
            if (screen != Screen.GUIDE && screen != Screen.SETTINGS) continue
            if (ticks % 6 == 0) {
                rebuildSchedules()
                withContext(Dispatchers.IO) { graph.manifestStore.flush() }
                // A season can start while the app runs.
                if (ticks % 120 == 0) refreshGuideChannels()
            }
            // A stream that never reports finishing can't hold the channel: the clock takes
            // over five minutes past the programme's whole length.
            val item = currentItem
            if (item != null && playState == PlayState.Playing &&
                nowSec > airingStartSec + item.duration * 60 + 300
            ) advance(naturalEnd = false)
        }
    }

    // ── Tuning ──────────────────────────────────────────────────────────────

    fun channelById(id: Int?): Channel? = guideChannels.firstOrNull { it.id == id }

    /** AppState.selectChannel: what the clock says is on, from where it is. */
    fun tune(channelId: Int) {
        val ch = channelById(channelId) ?: return
        val s = session ?: return
        val pool = poolFor(ch)
        if (pool.isEmpty()) return
        skipJob?.cancel()
        tuneJob?.cancel()
        token++ // whatever the old programme reports from here on is ignored
        currentChannelId = channelId
        prefs.lastChannelId = channelId
        failuresInARow = 0
        playState = PlayState.Tuning
        currentRung = null
        showOsd()
        val precomputed = schedules[channelId]
        val key = manifestKey
        tuneJob = viewModelScope.launch {
            val now = System.currentTimeMillis() / 1000.0
            val pick = withContext(Dispatchers.Default) {
                schedule.tuneIn(SchedChannel(ch.id, ch.isPremiereChannel), pool, precomputed, now, key)
            }
            val item = pick?.item ?: pool.random()
            val seek = pick?.seekSeconds ?: 0L
            startItem(item, s, now - seek)
        }
    }

    private fun startItem(item: MediaItem, s: Session, airingStart: Double) {
        token++
        currentItem = item
        airingStartSec = airingStart
        playState = PlayState.Tuning
        currentRung = null
        playback.play(Playback.Request(item, s, airingStart, token))
    }

    /** AppState.advanceToNextItem: the next programme, never the one another channel has on. */
    private fun advance(naturalEnd: Boolean) {
        val ch = channelById(currentChannelId) ?: return
        val s = session ?: return
        val prev = currentItem?.ratingKey
        val pool = poolFor(ch)
        if (pool.isEmpty()) return
        val lineup = lineupEntries()
        val key = manifestKey
        tuneJob?.cancel()
        token++
        tuneJob = viewModelScope.launch {
            val now = System.currentTimeMillis() / 1000.0
            val pick = withContext(Dispatchers.Default) {
                schedule.advancePick(SchedChannel(ch.id, ch.isPremiereChannel), pool, prev, now, lineup, key, naturalEnd)
            }
            val item = pick?.item ?: pool.random()
            startItem(item, s, now - (pick?.seekSeconds ?: 0L))
            rebuildSchedules()
        }
    }

    private fun showOsd(ms: Long = 4_000) {
        osdUntil = System.currentTimeMillis() + ms
    }

    private fun stepChannel(delta: Int) {
        val list = guideChannels
        if (list.isEmpty()) return
        val i = list.indexOfFirst { it.id == currentChannelId }.let { if (it < 0) 0 else it }
        val next = list[Math.floorMod(i + delta, list.size)]
        tune(next.id)
        guideFocus = list.indexOf(next)
    }

    fun retryStopped() {
        currentChannelId?.let { tune(it) }
    }

    /** The app went to the background: live TV doesn't pause, it stops. */
    fun onStop() {
        playback.stop()
        viewModelScope.launch(Dispatchers.IO) { graph.manifestStore.flush() }
    }

    /** Back in front: tune again to what is on now. */
    fun onStart() {
        if (screen == Screen.GUIDE || screen == Screen.SETTINGS) {
            viewModelScope.launch {
                rebuildSchedules()
                currentChannelId?.let { tune(it) }
            }
        }
    }

    // ── Settings ────────────────────────────────────────────────────────────

    fun openSettings() {
        bundlesAtSettingsOpen = enabledBundles
        settingsFocus = 0
        fullscreen = false
        overlay = Overlay.NONE
        screen = Screen.SETTINGS
    }

    private fun closeSettings() {
        screen = Screen.GUIDE
        if (enabledBundles != bundlesAtSettingsOpen) {
            refreshGuideChannels()
            if (guideChannels.none { it.id == currentChannelId }) {
                guideChannels.firstOrNull()?.let { tune(it.id) } ?: run {
                    playback.stop()
                    currentChannelId = null
                    currentItem = null
                }
            }
        }
    }

    /** The rows Settings shows, in order. */
    fun settingsRows(): List<SettingsRow> {
        val rows = ArrayList<SettingsRow>()
        for (b in graph.config.bundles) rows.add(SettingsRow.Package(b))
        rows.add(SettingsRow.Retro)
        rows.add(SettingsRow.Captions)
        rows.add(SettingsRow.Rescan)
        rows.add(SettingsRow.SignOut)
        return rows
    }

    /** Channels a package would add, and how many of them have enough to air. */
    fun packageCounts(b: Bundle): Pair<Int, Int> {
        val config = graph.config
        val avail = b.channelIDs.count { id ->
            val ch = config.channelsById[id] ?: return@count false
            (pools[id]?.size ?: 0) >= ch.minItems
        }
        return avail to b.channelIDs.size
    }

    private fun activateSettingsRow(row: SettingsRow) {
        when (row) {
            is SettingsRow.Package -> {
                val id = row.bundle.id
                val next = if (enabledBundles.contains(id)) {
                    if (enabledBundles.size <= 1) return // never the last package off
                    enabledBundles - id
                } else enabledBundles + id
                setBundles(next)
            }
            SettingsRow.Retro -> {
                retroMode = !retroMode
                prefs.retroMode = retroMode
            }
            SettingsRow.Captions -> toggleCaptions()
            SettingsRow.Rescan -> {
                val s = session ?: return
                screen = Screen.GUIDE
                showToast("Reading your library again…")
                viewModelScope.launch {
                    val ok = refreshLibrary(s, background = true, previousSignature = null)
                    showToast(if (ok) "Library read: $libraryCount items" else "Couldn't reach your server")
                }
            }
            SettingsRow.SignOut -> signOut()
        }
    }

    fun toggleCaptions() {
        captions = !captions
        prefs.captions = captions
        playback.setCaptions(captions)
    }

    fun toggleRetro() {
        retroMode = !retroMode
        prefs.retroMode = retroMode
    }

    fun signOut() {
        playback.stop()
        prefs.clearSession()
        graph.libraryStore.clear()
        graph.manifestStore.clearAll()
        session = null
        library = emptyList()
        pools = emptyMap()
        guideChannels = emptyList()
        schedules = emptyMap()
        currentChannelId = null
        currentItem = null
        playState = PlayState.Idle
        fullscreen = false
        connect.reset()
        screen = Screen.CONNECT
    }

    fun retryAfterError() {
        val s = session
        if (s == null) {
            screen = Screen.CONNECT
            return
        }
        viewModelScope.launch { refreshLibrary(s, background = false, previousSignature = null) }
    }

    private fun showToast(text: String) {
        toast = text
        viewModelScope.launch {
            delay(3_500)
            if (toast == text) toast = null
        }
    }

    // ── Seasonal invite ─────────────────────────────────────────────────────

    private fun answerInvite(choice: Int) {
        val b = seasonalOffer ?: return
        val year = LocalDateTime.now().year
        when (choice) {
            0 -> {
                prefs.setSeasonalAnswer(b.id, null)
                setBundles(enabledBundles + b.id)
            }
            1 -> prefs.setSeasonalAnswer(b.id, SeasonalAnswer(notNowYear = year))
            else -> prefs.setSeasonalAnswer(b.id, SeasonalAnswer(never = true))
        }
        guideFocus = 0
        refreshGuideChannels()
    }

    // ── The remote ──────────────────────────────────────────────────────────

    /** Every key press while no text field is open. Returns true when it was used. */
    fun onKeyDown(code: Int): Boolean = when (screen) {
        Screen.CONNECT -> connect.onKey(code)
        Screen.GUIDE -> if (fullscreen) fullscreenKey(code) else guideKey(code)
        Screen.SETTINGS -> settingsKey(code)
        Screen.LOADING -> when (code) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                if (stillWaiting) pressStopWaiting()
                true
            }
            else -> false
        }
        Screen.ERROR -> when (code) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                retryAfterError()
                true
            }
            KeyEvent.KEYCODE_MENU -> { signOut(); true }
            else -> false
        }
        Screen.BOOT -> false
    }

    private fun isOk(code: Int) = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER ||
        code == KeyEvent.KEYCODE_NUMPAD_ENTER || code == KeyEvent.KEYCODE_BUTTON_A

    private fun isBack(code: Int) = code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_ESCAPE ||
        code == KeyEvent.KEYCODE_BUTTON_B

    private fun digitKey(code: Int): Boolean {
        val d = when (code) {
            in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> code - KeyEvent.KEYCODE_0
            in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9 -> code - KeyEvent.KEYCODE_NUMPAD_0
            else -> return false
        }
        digits = (digits + d).takeLast(3)
        digitJob?.cancel()
        digitJob = viewModelScope.launch {
            delay(1_500)
            val n = digits.toIntOrNull()
            digits = ""
            val ch = guideChannels.firstOrNull { it.number == n }
            if (ch != null) {
                tune(ch.id)
                guideFocus = guideChannels.indexOf(ch)
            } else showToast("No channel $n")
        }
        return true
    }

    private fun guideKey(code: Int): Boolean {
        if (digitKey(code)) return true
        val n = guideChannels.size
        val hasInvite = seasonalOffer != null
        when {
            code == KeyEvent.KEYCODE_DPAD_DOWN -> {
                guideFocus = when {
                    guideFocus == -2 -> if (hasInvite) -1 else 0
                    guideFocus == -1 -> 0
                    n == 0 -> guideFocus
                    guideFocus >= n - 1 -> 0 // Down off the last channel wraps to channel one
                    else -> guideFocus + 1
                }
            }
            code == KeyEvent.KEYCODE_DPAD_UP -> {
                guideFocus = when {
                    guideFocus > 0 -> guideFocus - 1
                    guideFocus == 0 -> if (hasInvite) -1 else -2 // Up off channel one does not wrap
                    else -> -2
                }
            }
            code == KeyEvent.KEYCODE_DPAD_LEFT -> if (guideFocus == -1) inviteChoice = maxOf(0, inviteChoice - 1)
            code == KeyEvent.KEYCODE_DPAD_RIGHT -> if (guideFocus == -1) inviteChoice = minOf(2, inviteChoice + 1)
            isOk(code) -> when {
                guideFocus == -2 -> openSettings()
                guideFocus == -1 -> answerInvite(inviteChoice)
                n == 0 -> openSettings()
                else -> {
                    val ch = guideChannels[guideFocus.coerceIn(0, n - 1)]
                    if (ch.id == currentChannelId) {
                        if (playState is PlayState.Stopped) retryStopped() else enterFullscreen()
                    } else tune(ch.id)
                }
            }
            code == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE || code == KeyEvent.KEYCODE_MEDIA_PLAY -> if (currentChannelId != null) enterFullscreen()
            code == KeyEvent.KEYCODE_MENU || code == KeyEvent.KEYCODE_SETTINGS -> openSettings()
            code == KeyEvent.KEYCODE_CHANNEL_UP || code == KeyEvent.KEYCODE_PAGE_UP -> stepChannel(+1)
            code == KeyEvent.KEYCODE_CHANNEL_DOWN || code == KeyEvent.KEYCODE_PAGE_DOWN -> stepChannel(-1)
            isBack(code) -> {
                if (guideFocus < 0) { guideFocus = 0; return true }
                val now = System.currentTimeMillis()
                if (now - lastBackAt < 2_500) return false // let the activity close
                lastBackAt = now
                showToast("Press BACK again to turn off the TV")
            }
            else -> return false
        }
        return true
    }

    private fun enterFullscreen() {
        fullscreen = true
        overlay = Overlay.NONE
        showOsd()
    }

    private fun fullscreenKey(code: Int): Boolean {
        if (overlay == Overlay.NONE && digitKey(code)) return true
        when (overlay) {
            Overlay.STRIP -> {
                val list = guideChannels
                when {
                    code == KeyEvent.KEYCODE_DPAD_LEFT -> stripFocus = maxOf(0, stripFocus - 1)
                    code == KeyEvent.KEYCODE_DPAD_RIGHT -> stripFocus = minOf(list.size - 1, stripFocus + 1)
                    isOk(code) -> {
                        list.getOrNull(stripFocus)?.let { tune(it.id); guideFocus = stripFocus }
                        overlay = Overlay.NONE
                    }
                    isBack(code) || code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN -> overlay = Overlay.NONE
                    else -> return false
                }
                return true
            }
            Overlay.NOW_PLAYING -> {
                when {
                    code == KeyEvent.KEYCODE_DPAD_UP -> if (panelFocus > 0) panelFocus-- else overlay = Overlay.NONE
                    code == KeyEvent.KEYCODE_DPAD_DOWN -> panelFocus = minOf(1, panelFocus + 1)
                    isOk(code) -> if (panelFocus == 0) toggleCaptions() else toggleRetro()
                    isBack(code) -> overlay = Overlay.NONE
                    code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT -> {}
                    else -> return false
                }
                return true
            }
            Overlay.NONE -> {}
        }
        when {
            code == KeyEvent.KEYCODE_DPAD_RIGHT || code == KeyEvent.KEYCODE_CHANNEL_UP || code == KeyEvent.KEYCODE_PAGE_UP -> stepChannel(+1)
            code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_CHANNEL_DOWN || code == KeyEvent.KEYCODE_PAGE_DOWN -> stepChannel(-1)
            code == KeyEvent.KEYCODE_DPAD_UP -> {
                stripFocus = guideChannels.indexOfFirst { it.id == currentChannelId }.coerceAtLeast(0)
                overlay = Overlay.STRIP
            }
            code == KeyEvent.KEYCODE_DPAD_DOWN || code == KeyEvent.KEYCODE_INFO -> {
                panelFocus = 0
                overlay = Overlay.NOW_PLAYING
            }
            code == KeyEvent.KEYCODE_CAPTIONS -> toggleCaptions()
            isOk(code) -> if (playState is PlayState.Stopped) retryStopped() else showOsd()
            code == KeyEvent.KEYCODE_MENU || code == KeyEvent.KEYCODE_SETTINGS -> openSettings()
            isBack(code) -> {
                fullscreen = false
                guideFocus = guideChannels.indexOfFirst { it.id == currentChannelId }.coerceAtLeast(0)
            }
            code == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> showOsd()
            else -> return false
        }
        return true
    }

    private fun settingsKey(code: Int): Boolean {
        val rows = settingsRows()
        when {
            code == KeyEvent.KEYCODE_DPAD_DOWN -> settingsFocus = minOf(rows.size - 1, settingsFocus + 1)
            code == KeyEvent.KEYCODE_DPAD_UP -> settingsFocus = maxOf(0, settingsFocus - 1)
            isOk(code) -> rows.getOrNull(settingsFocus)?.let(::activateSettingsRow)
            isBack(code) || code == KeyEvent.KEYCODE_MENU -> closeSettings()
            code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT -> {}
            else -> return false
        }
        return true
    }

    // ── Used by the connect flow ───────────────────────────────────────────

    internal fun launch(block: suspend () -> Unit): Job = viewModelScope.launch { block() }
}

sealed class SettingsRow {
    data class Package(val bundle: Bundle) : SettingsRow()
    data object Retro : SettingsRow()
    data object Captions : SettingsRow()
    data object Rescan : SettingsRow()
    data object SignOut : SettingsRow()
}
