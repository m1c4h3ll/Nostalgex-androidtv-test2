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
                    T("CONNECT YOUR LIBRARY", 40f, Nx.Cyan, retro = true, tracking = Px.sp(3f))
                    T("Retro TV for your own movies and shows. Pick your server.", 24f, Color.White.copy(alpha = 0.65f))
                    Spacer(Modifier.height(Px.u(10f)))
                    Slab("PLEX", c.focus == 0, Modifier.fillMaxWidth(), sub = "Sign in with a code at plex.tv/link")
                    Slab("JELLYFIN", c.focus == 1, Modifier.fillMaxWidth(), sub = "Quick Connect or your password")
                    Slab("EMBY", c.focus == 2, Modifier.fillMaxWidth(), sub = "Pick your user on the server")
                }
                Step.PLEX_CODE -> {
                    T("GO TO plex.tv/link AND ENTER:", 34f, Nx.Cyan, retro = true, tracking = Px.sp(2f))
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
                                T("SCAN TO CONNECT", 22f, Color.White.copy(alpha = 0.6f), retro = true)
                            }
                        }
                    }
                    Slab("CANCEL", c.focus == 0, Modifier.width(Px.u(360f)))
                }
                Step.PLEX_SERVERS -> {
                    T("CHOOSE YOUR LIBRARIES", 40f, Nx.Cyan, retro = true, tracking = Px.sp(3f))
                    c.plexServers.forEachIndexed { i, s ->
                        Slab(
                            s.name, c.focus == i, Modifier.fillMaxWidth(),
                            sub = if (s.owned) "Your server" else "Shared with you",
                            trailing = if (c.plexChosen.contains(s.machineIdentifier)) "ON" else "OFF",
                        )
                    }
                    Slab("CONTINUE", c.focus == c.plexServers.size, Modifier.width(Px.u(360f)), accent = Nx.Yellow)
                }
                Step.CHECKING -> T("ONE MOMENT", 40f, Nx.Cyan, retro = true)
                Step.MB_START -> {
                    T("CONNECT TO ${c.kind.display.uppercase()}", 40f, Nx.Cyan, retro = true, tracking = Px.sp(3f))
                    T("Nostalgex can look for your ${c.kind.display} server on this network. Or type its address.", 24f, Color.White.copy(alpha = 0.65f), maxLines = 2)
                    Slab("FIND MY SERVER", c.focus == 0, Modifier.fillMaxWidth())
                    Slab("TYPE AN ADDRESS", c.focus == 1, Modifier.fillMaxWidth())
                }
                Step.MB_FOUND -> {
                    T("PICK YOUR SERVER", 40f, Nx.Cyan, retro = true, tracking = Px.sp(3f))
                    c.found.forEachIndexed { i, f -> Slab(f.name, c.focus == i, Modifier.fillMaxWidth(), sub = f.address) }
                    Slab("SEARCH AGAIN", c.focus == c.found.size, Modifier.fillMaxWidth())
                    Slab("TYPE AN ADDRESS", c.focus == c.found.size + 1, Modifier.fillMaxWidth())
                }
                Step.MB_ADDRESS -> {
                    T("YOUR ${c.kind.display.uppercase()} SERVER", 40f, Nx.Cyan, retro = true, tracking = Px.sp(3f))
                    T("Type the address of your ${c.kind.display} server, like 192.168.1.20 or https://media.example.com", 22f, Color.White.copy(alpha = 0.65f), maxLines = 2)
                    Field("ADDRESS", c.address.ifEmpty { "Press OK to type" }, c.focus == 0)
                    Slab("CONNECT", c.focus == 1, Modifier.width(Px.u(360f)), accent = Nx.Yellow)
                }
                Step.MB_QUICK -> {
                    T("QUICK CONNECT", 40f, Nx.Cyan, retro = true, tracking = Px.sp(3f))
                    T("In Jellyfin on your phone or computer: your profile, Quick Connect, then enter:", 24f, Color.White.copy(alpha = 0.65f), maxLines = 2)
                    Box(Modifier.border(Px.u(3f), Nx.Yellow).background(Color(0xFF0B0B20)).padding(horizontal = Px.u(44f), vertical = Px.u(18f))) {
                        T(c.quickCode.toList().joinToString(" "), 96f, Nx.Yellow, retro = true)
                    }
                    T("WAITING FOR APPROVAL", 26f, Color.White.copy(alpha = 0.5f), retro = true)
                    Slab("USE A PASSWORD", c.focus == 0, Modifier.width(Px.u(420f)))
                }
                Step.MB_USERS -> {
                    T("WHO'S WATCHING?", 40f, Nx.Cyan, retro = true, tracking = Px.sp(3f))
                    T(c.serverName, 24f, Color.White.copy(alpha = 0.6f))
                    c.users.forEachIndexed { i, u -> Slab(u.name, c.focus == i, Modifier.fillMaxWidth(), trailing = if (u.hasPassword) "•••" else null) }
                    Slab("SOMEONE ELSE", c.focus == c.users.size, Modifier.fillMaxWidth())
                }
                Step.MB_PASSWORD -> {
                    T("SIGN IN TO ${c.serverName.uppercase()}", 40f, Nx.Cyan, retro = true, tracking = Px.sp(3f))
                    Field("USERNAME", c.username.ifEmpty { "Press OK to type" }, c.focus == 0)
                    Field("PASSWORD", if (c.password.isEmpty()) "Press OK to type (may be empty)" else "•".repeat(c.password.length), c.focus == 1)
                    Slab("SIGN IN", c.focus == 2, Modifier.width(Px.u(360f)), accent = Nx.Yellow)
                }
            }
            c.busy?.let { T(it, 28f, Color.White.copy(alpha = 0.6f), retro = true, tracking = Px.sp(2f)) }
            c.error?.let { T(it, 24f, Nx.Amber, medium = true, maxLines = 3) }
        }
        if (c.step == Step.PICK) {
            Box(Modifier.fillMaxSize().padding(Px.u(40f)), contentAlignment = Alignment.BottomCenter) {
                T("Free and open source. Based on Nostalgex by Chad Mueller (MIT). Never supplies any media of its own.", 18f, Color.White.copy(alpha = 0.35f))
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
