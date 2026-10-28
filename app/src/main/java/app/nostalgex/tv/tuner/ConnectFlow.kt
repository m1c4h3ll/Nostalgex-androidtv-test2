package app.nostalgex.tv.tuner

import android.view.KeyEvent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.nostalgex.engine.server.MediaBrowser
import app.nostalgex.engine.server.MediaBrowserSession
import app.nostalgex.engine.server.PlexAuth
import app.nostalgex.engine.server.PlexResolve
import app.nostalgex.engine.server.PlexServer
import app.nostalgex.engine.server.SavedPlexServer
import app.nostalgex.engine.server.ServerKind
import app.nostalgex.engine.server.Session
import app.nostalgex.tv.AppGraph
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * The connect screens, after upstream's sign-in redesign (`SignInFlow.swift`): Plex through a
 * plex.tv/link code, Jellyfin by Quick Connect or password, Emby by picking a user. Servers on
 * the home network are found by broadcast, or the address is typed.
 */
class ConnectFlow(private val vm: AppViewModel, private val graph: AppGraph) {

    enum class Step { PICK, PLEX_CODE, PLEX_SERVERS, CHECKING, MB_START, MB_FOUND, MB_ADDRESS, MB_QUICK, MB_USERS, MB_PASSWORD }

    var step by mutableStateOf(Step.PICK)
        private set
    var focus by mutableIntStateOf(0)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var busy by mutableStateOf<String?>(null)
        private set
    var kind by mutableStateOf(ServerKind.JELLYFIN)
        private set

    // Plex
    var plexCode by mutableStateOf("")
        private set
    var plexServers by mutableStateOf<List<PlexServer>>(emptyList())
        private set
    var plexChosen by mutableStateOf<Set<String>>(emptySet())
        private set
    private var plexToken: String? = null

    // Jellyfin and Emby
    var found by mutableStateOf<List<MediaBrowser.Found>>(emptyList())
        private set
    var address by mutableStateOf("")
        private set
    var serverName by mutableStateOf("")
        private set
    private var serverBase: String? = null
    var quickCode by mutableStateOf("")
        private set
    var quickConnectAvailable by mutableStateOf(false)
        private set
    var users by mutableStateOf<List<MediaBrowser.PublicUser>>(emptyList())
        private set
    var username by mutableStateOf("")
        private set
    var password by mutableStateOf("")
        private set

    private var job: Job? = null
    private val http get() = graph.http
    private val clientId get() = graph.prefs.clientId

    fun reset() {
        job?.cancel()
        step = Step.PICK
        focus = 0
        error = null
        busy = null
        plexToken = null
        plexCode = ""
        found = emptyList()
        users = emptyList()
        password = ""
    }

    private fun go(s: Step, f: Int = 0) {
        step = s
        focus = f
    }

    /** How many focusable things the current step has. */
    fun focusCount(): Int = when (step) {
        Step.PICK -> 3
        Step.PLEX_CODE -> 1 // CANCEL
        Step.PLEX_SERVERS -> plexServers.size + 1 // each server, then CONTINUE
        Step.CHECKING -> 0
        Step.MB_START -> 2 // FIND MY SERVER, TYPE AN ADDRESS
        Step.MB_FOUND -> found.size + 2 // each server, SEARCH AGAIN, TYPE AN ADDRESS
        Step.MB_ADDRESS -> 2 // field, CONNECT
        Step.MB_QUICK -> 1 // USE A PASSWORD
        Step.MB_USERS -> users.size + 1 // each user, SOMEONE ELSE
        Step.MB_PASSWORD -> 3 // name, password, SIGN IN
    }

    fun onKey(code: Int): Boolean {
        val n = focusCount()
        when (code) {
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT -> if (n > 0) focus = minOf(n - 1, focus + 1)
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_LEFT -> if (n > 0) focus = maxOf(0, focus - 1)
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_BUTTON_A -> activate()
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE, KeyEvent.KEYCODE_BUTTON_B -> return back()
            else -> return false
        }
        return true
    }

    private fun back(): Boolean {
        job?.cancel()
        busy = null
        error = null
        when (step) {
            Step.PICK -> return false
            Step.PLEX_CODE, Step.PLEX_SERVERS, Step.MB_START -> go(Step.PICK)
            Step.CHECKING -> go(Step.PICK)
            Step.MB_FOUND, Step.MB_ADDRESS -> go(Step.MB_START)
            Step.MB_QUICK, Step.MB_USERS -> go(Step.MB_ADDRESS, 1)
            Step.MB_PASSWORD -> if (kind == ServerKind.EMBY && users.isNotEmpty()) go(Step.MB_USERS) else go(Step.MB_ADDRESS, 1)
        }
        return true
    }

    private fun activate() {
        error = null
        when (step) {
            Step.PICK -> when (focus) {
                0 -> startPlex()
                1 -> { kind = ServerKind.JELLYFIN; go(Step.MB_START) }
                else -> { kind = ServerKind.EMBY; go(Step.MB_START) }
            }
            Step.PLEX_CODE -> { job?.cancel(); go(Step.PICK) }
            Step.PLEX_SERVERS -> {
                if (focus < plexServers.size) {
                    val id = plexServers[focus].machineIdentifier
                    plexChosen = if (plexChosen.contains(id)) plexChosen - id else plexChosen + id
                } else if (plexChosen.isNotEmpty()) {
                    finishPlex(plexServers.filter { plexChosen.contains(it.machineIdentifier) })
                } else error = "Pick at least one library."
            }
            Step.CHECKING -> {}
            Step.MB_START -> if (focus == 0) search() else go(Step.MB_ADDRESS)
            Step.MB_FOUND -> when {
                focus < found.size -> checkServer(found[focus].address)
                focus == found.size -> search()
                else -> go(Step.MB_ADDRESS)
            }
            Step.MB_ADDRESS -> if (focus == 0) editAddress() else checkServer(address)
            Step.MB_QUICK -> { job?.cancel(); go(Step.MB_PASSWORD) }
            Step.MB_USERS -> if (focus < users.size) {
                val u = users[focus]
                username = u.name
                password = ""
                if (!u.hasPassword) signInPassword() else go(Step.MB_PASSWORD, 1)
            } else {
                username = ""
                go(Step.MB_PASSWORD, 0)
            }
            Step.MB_PASSWORD -> when (focus) {
                0 -> vm.textEntry = TextEntry("USERNAME", username, false) { username = it; focus = 1 }
                1 -> vm.textEntry = TextEntry("PASSWORD", password, true) { password = it; signInPassword() }
                else -> signInPassword()
            }
        }
    }

    // ── Plex ────────────────────────────────────────────────────────────────

    private fun startPlex() {
        go(Step.PLEX_CODE)
        plexCode = ""
        busy = "GETTING A CODE"
        job?.cancel()
        job = vm.launch {
            val auth = PlexAuth(http, clientId)
            while (true) {
                val pin = try {
                    withContext(Dispatchers.IO) { auth.requestPin() }
                } catch (e: Exception) {
                    busy = null
                    error = "COULD NOT REACH PLEX.TV. CHECK YOUR CONNECTION"
                    return@launch
                }
                plexCode = pin.code
                busy = "WAITING FOR AUTHORIZATION"
                // Every 2 s for 5 minutes, then a new code.
                repeat(150) {
                    delay(2_000)
                    val token = try { withContext(Dispatchers.IO) { auth.checkPin(pin.id) } } catch (_: Exception) { null }
                    if (token != null) {
                        plexToken = token
                        discoverPlex(token)
                        return@launch
                    }
                }
            }
        }
    }

    private suspend fun discoverPlex(token: String) {
        busy = "FINDING YOUR SERVERS"
        val servers = try {
            withContext(Dispatchers.IO) { PlexAuth(http, clientId).servers(token) }
        } catch (e: Exception) {
            busy = null
            error = "SERVER DISCOVERY FAILED. TRY AGAIN"
            return
        }
        busy = null
        when {
            servers.isEmpty() -> error = "NO PLEX SERVERS FOUND ON THIS ACCOUNT"
            servers.size == 1 -> finishPlex(servers)
            else -> {
                plexServers = servers
                plexChosen = PlexAuth.defaultChoice(servers)
                go(Step.PLEX_SERVERS, plexServers.size)
            }
        }
    }

    private fun finishPlex(chosen: List<PlexServer>) {
        val token = plexToken ?: return
        go(Step.CHECKING)
        busy = "CHECKING YOUR SERVER"
        job = vm.launch {
            val saved = withContext(Dispatchers.IO) {
                chosen.mapNotNull { s ->
                    val url = PlexResolve.bestConnection(http, s) ?: return@mapNotNull null
                    SavedPlexServer(s.name, s.machineIdentifier, url, s.accessToken, s.connections)
                }
            }
            busy = null
            if (saved.isEmpty()) {
                error = "CAN'T REACH ${chosen.joinToString(", ") { it.name.uppercase() }}. IS IT ON?"
                go(if (plexServers.size > 1) Step.PLEX_SERVERS else Step.PICK)
                return@launch
            }
            vm.signedIn(Session.Plex(clientId, token, saved))
        }
    }

    // ── Jellyfin and Emby ──────────────────────────────────────────────────

    private fun search() {
        busy = "SEARCHING..."
        go(Step.MB_FOUND)
        found = emptyList()
        job?.cancel()
        job = vm.launch {
            val list = withContext(Dispatchers.IO) { MediaBrowser.discover(kind) }
            busy = null
            found = list
            if (list.isEmpty()) error = "NO ${kind.display.uppercase()} SERVER ANSWERED ON THIS NETWORK. TYPE ITS ADDRESS INSTEAD."
            focus = if (list.isEmpty()) list.size + 1 else 0
        }
    }

    private fun editAddress() {
        vm.textEntry = TextEntry("SERVER ADDRESS", address.ifEmpty { "http://" }, false) {
            address = it.trim()
            focus = 1
        }
    }

    private fun checkServer(raw: String) {
        if (raw.isBlank()) {
            error = "TYPE YOUR SERVER'S ADDRESS"
            go(Step.MB_ADDRESS)
            return
        }
        address = raw
        busy = "CHECKING THE ADDRESS"
        job?.cancel()
        job = vm.launch {
            val hit = withContext(Dispatchers.IO) { MediaBrowser.checkAddress(http, raw) }
            if (hit == null) {
                busy = null
                error = "NOTHING ANSWERED AT THAT ADDRESS"
                go(Step.MB_ADDRESS, 0)
                return@launch
            }
            val (base, info) = hit
            // The server says which it is; a Jellyfin picked for an Emby server switches over.
            info.kind?.let { kind = it }
            serverBase = base
            serverName = info.name
            if (kind == ServerKind.EMBY) {
                val list = withContext(Dispatchers.IO) { MediaBrowser.publicUsers(http, base) }
                busy = null
                users = list
                if (list.isNotEmpty()) go(Step.MB_USERS) else go(Step.MB_PASSWORD)
            } else {
                val qc = withContext(Dispatchers.IO) { MediaBrowser.quickConnectEnabled(http, base) }
                quickConnectAvailable = qc != false
                if (quickConnectAvailable) startQuickConnect(base) else {
                    busy = null
                    go(Step.MB_PASSWORD)
                }
            }
        }
    }

    private suspend fun startQuickConnect(base: String) {
        val deviceId = clientId
        val start = withContext(Dispatchers.IO) {
            try { MediaBrowser.quickConnectInitiate(http, base, deviceId) } catch (_: Exception) { null }
        }
        busy = null
        if (start == null) {
            go(Step.MB_PASSWORD)
            return
        }
        quickCode = start.code
        go(Step.MB_QUICK)
        repeat(150) {
            delay(2_000)
            val ok = withContext(Dispatchers.IO) { MediaBrowser.quickConnectApproved(http, base, deviceId, start.secret) }
            if (ok) {
                busy = "SIGNING IN"
                try {
                    val s = withContext(Dispatchers.IO) {
                        MediaBrowser.quickConnectSignIn(http, kind, base, deviceId, serverName, start.secret)
                    }
                    busy = null
                    vm.signedIn(Session.MediaBrowser(s))
                } catch (e: Exception) {
                    busy = null
                    error = "QUICK CONNECT DIDN'T FINISH. USE A PASSWORD INSTEAD."
                }
                return
            }
        }
        error = "THE CODE EXPIRED. TRY AGAIN, OR USE A PASSWORD."
    }

    private fun signInPassword() {
        val base = serverBase ?: return
        if (username.isBlank()) {
            error = "TYPE YOUR USERNAME"
            focus = 0
            return
        }
        busy = "SIGNING IN"
        job?.cancel()
        job = vm.launch {
            try {
                val s: MediaBrowserSession = withContext(Dispatchers.IO) {
                    MediaBrowser.signInWithPassword(http, kind, base, clientId, serverName, username, password)
                }
                busy = null
                vm.signedIn(Session.MediaBrowser(s))
            } catch (e: MediaBrowser.SignInException) {
                busy = null
                error = when (e.code) {
                    "incorrect" -> "THAT NAME OR PASSWORD ISN'T RIGHT"
                    "network" -> "COULDN'T REACH THE SERVER"
                    else -> e.message?.uppercase() ?: "SIGN-IN FAILED"
                }
                if (step != Step.MB_PASSWORD) go(Step.MB_PASSWORD, 1)
            }
        }
    }
}
