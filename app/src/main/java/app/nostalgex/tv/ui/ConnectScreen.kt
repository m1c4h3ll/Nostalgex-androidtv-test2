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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import app.nostalgex.tv.tuner.AppViewModel
import app.nostalgex.tv.i18n.S
import app.nostalgex.tv.tuner.ConnectFlow.Step

/** The connect screens: pick a server kind, then its sign-in. */
@Composable
fun ConnectScreen(vm: AppViewModel) {
    val c = vm.connect
    Box(
        Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Nx.Panel0, Color(0xFF14102A)))),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.width(Px.u(1100f)),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Px.u(22f)),
        ) {
            Logo(Px.u(70f))
            Spacer(Modifier.height(Px.u(10f)))
            when (c.step) {
                Step.PICK -> {
                    T(S.connectYourLibrary, 40f, Nx.Cyan, retro = true, tracking = Px.sp(3f))
                    T(S.connectIntro, 24f, Color.White.copy(alpha = 0.65f))
                    Spacer(Modifier.height(Px.u(10f)))
                    Slab("PLEX", c.focus == 0, Modifier.fillMaxWidth(), sub = S.plexSub)
                    Slab("JELLYFIN", c.focus == 1, Modifier.fillMaxWidth(), sub = S.jellyfinSub)
                    Slab("EMBY", c.focus == 2, Modifier.fillMaxWidth(), sub = S.embySub)
                }
                Step.PLEX_CODE -> {
                    T(S.goToPlexLink, 34f, Nx.Cyan, retro = true, tracking = Px.sp(2f))
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Px.u(60f))) {
                        Box(
                            Modifier.border(Px.u(3f), Nx.Yellow).background(Color(0xFF0B0B20))
                                .padding(horizontal = Px.u(44f), vertical = Px.u(18f)),
                        ) {
                            T(c.plexCode.ifEmpty { "····" }.toList().joinToString(" "), 110f, Nx.Yellow, retro = true)
                        }
                        if (c.plexCode.isNotEmpty()) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                QrCode("https://www.plex.tv/link/?pin=${c.plexCode}", 200f)
                                Spacer(Modifier.height(Px.u(10f)))
                                T(S.scanToConnect, 22f, Color.White.copy(alpha = 0.6f), retro = true)
                            }
                        }
                    }
                    Slab(S.cancel, c.focus == 0, Modifier.width(Px.u(360f)))
                }
                Step.PLEX_SERVERS -> {
                    T(S.chooseLibraries, 40f, Nx.Cyan, retro = true, tracking = Px.sp(3f))
                    c.plexServers.forEachIndexed { i, s ->
                        Slab(
                            s.name, c.focus == i, Modifier.fillMaxWidth(),
                            sub = if (s.owned) S.yourServer else S.sharedWithYou,
                            trailing = if (c.plexChosen.contains(s.machineIdentifier)) S.on else S.off,
                        )
                    }
                    Slab(S.continueLabel, c.focus == c.plexServers.size, Modifier.width(Px.u(360f)), accent = Nx.Yellow)
                }
                Step.CHECKING -> T(S.oneMoment, 40f, Nx.Cyan, retro = true)
                Step.MB_START -> {
                    T(S.connectTo(c.kind.display), 40f, Nx.Cyan, retro = true, tracking = Px.sp(3f))
                    T(S.canLookFor(c.kind.display), 24f, Color.White.copy(alpha = 0.65f), maxLines = 2)
                    Slab(S.findMyServer, c.focus == 0, Modifier.fillMaxWidth())
                    Slab(S.typeAnAddress, c.focus == 1, Modifier.fillMaxWidth())
                }
                Step.MB_FOUND -> {
                    T(S.pickYourServer, 40f, Nx.Cyan, retro = true, tracking = Px.sp(3f))
                    c.found.forEachIndexed { i, f -> Slab(f.name, c.focus == i, Modifier.fillMaxWidth(), sub = f.address) }
                    Slab(S.searchAgain, c.focus == c.found.size, Modifier.fillMaxWidth())
                    Slab(S.typeAnAddress, c.focus == c.found.size + 1, Modifier.fillMaxWidth())
                }
                Step.MB_ADDRESS -> {
                    T(S.yourKindServer(c.kind.display), 40f, Nx.Cyan, retro = true, tracking = Px.sp(3f))
                    T(S.typeAddressHint(c.kind.display), 22f, Color.White.copy(alpha = 0.65f), maxLines = 2)
                    Field(S.address, c.address.ifEmpty { S.pressOkToType }, c.focus == 0)
                    Slab(S.connect, c.focus == 1, Modifier.width(Px.u(360f)), accent = Nx.Yellow)
                }
                Step.MB_QUICK -> {
                    T(S.quickConnect, 40f, Nx.Cyan, retro = true, tracking = Px.sp(3f))
                    T(S.quickConnectHint, 24f, Color.White.copy(alpha = 0.65f), maxLines = 2)
                    Box(Modifier.border(Px.u(3f), Nx.Yellow).background(Color(0xFF0B0B20)).padding(horizontal = Px.u(44f), vertical = Px.u(18f))) {
                        T(c.quickCode.toList().joinToString(" "), 96f, Nx.Yellow, retro = true)
                    }
                    T(S.waitingForApproval, 26f, Color.White.copy(alpha = 0.5f), retro = true)
                    Slab(S.useAPassword, c.focus == 0, Modifier.width(Px.u(420f)))
                }
                Step.MB_USERS -> {
                    T(S.whosWatching, 40f, Nx.Cyan, retro = true, tracking = Px.sp(3f))
                    T(c.serverName, 24f, Color.White.copy(alpha = 0.6f))
                    c.users.forEachIndexed { i, u -> Slab(u.name, c.focus == i, Modifier.fillMaxWidth(), trailing = if (u.hasPassword) "•••" else null) }
                    Slab(S.someoneElse, c.focus == c.users.size, Modifier.fillMaxWidth())
                }
                Step.MB_PASSWORD -> {
                    T(S.signInTo(c.serverName), 40f, Nx.Cyan, retro = true, tracking = Px.sp(3f))
                    Field(S.username, c.username.ifEmpty { S.pressOkToType }, c.focus == 0)
                    Field(S.password, if (c.password.isEmpty()) S.pressOkToTypeMaybeEmpty else "•".repeat(c.password.length), c.focus == 1)
                    Slab(S.signIn, c.focus == 2, Modifier.width(Px.u(360f)), accent = Nx.Yellow)
                }
            }
            c.busy?.let { T(it, 28f, Color.White.copy(alpha = 0.6f), retro = true, tracking = Px.sp(2f)) }
            c.error?.let { T(it, 24f, Nx.Amber, medium = true, maxLines = 3) }
        }
        if (c.step == Step.PICK) {
            Box(Modifier.fillMaxSize().padding(Px.u(40f)), contentAlignment = Alignment.BottomCenter) {
                T(S.connectFooter, 18f, Color.White.copy(alpha = 0.35f))
            }
        }
    }
}

/** A text field in the connect screens' look; OK opens the TV's keyboard. */
@Composable
private fun Field(label: String, value: String, focused: Boolean) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Px.u(6f))) {
        T(label, 22f, if (focused) Nx.Cyan else Color.White.copy(alpha = 0.5f), retro = true, tracking = Px.sp(2f))
        Box(
            Modifier.fillMaxWidth().background(if (focused) Color(0xFF07071A) else Color(0xFF0E0E22))
                .border(Px.u(if (focused) 3f else 1.5f), if (focused) Nx.Cyan else Color.White.copy(alpha = 0.2f))
                .padding(horizontal = Px.u(26f), vertical = Px.u(18f)),
        ) { T(value, 28f, Color.White.copy(alpha = if (focused) 1f else 0.7f)) }
    }
}
