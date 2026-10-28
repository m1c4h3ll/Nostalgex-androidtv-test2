package app.nostalgex.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import app.nostalgex.tv.tuner.AppViewModel

/** LoadingView with its step rail: preparing, reading the library, building channels. */
@Composable
fun LoadingScreen(vm: AppViewModel) {
    Box(
        Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Nx.Panel0, Color(0xFF14102A)))),
        contentAlignment = Alignment.Center,
    ) {
        Column(Modifier.width(Px.u(1100f)), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Px.u(26f))) {
            Logo(Px.u(80f))
            Spacer(Modifier.height(Px.u(20f)))
            val steps = listOf("PREPARING", "READING YOUR LIBRARY", "BUILDING CHANNELS")
            Row(horizontalArrangement = Arrangement.spacedBy(Px.u(30f)), verticalAlignment = Alignment.CenterVertically) {
                steps.forEachIndexed { i, s ->
                    val state = when {
                        i < vm.loadStep -> Color(0xFF6BE675)
                        i == vm.loadStep -> Nx.Cyan
                        else -> Color.White.copy(alpha = 0.3f)
                    }
                    T((if (i < vm.loadStep) "✓ " else if (i == vm.loadStep) "▸ " else "  ") + s, 26f, state, retro = true, tracking = Px.sp(2f))
                }
            }
            val headline = when (vm.loadStep) {
                0 -> "Getting ready"
                1 -> "Reading your library"
                else -> "Building your channels"
            }
            T(headline, 40f, Color.White, medium = true)
            T(vm.loadDetail, 24f, Color.White.copy(alpha = 0.6f))
            if (vm.loadStep == 1 && vm.loadCount > 0) T("${vm.loadCount} programmes so far", 24f, Nx.Yellow, retro = true)
            if (vm.loadStep >= 2) Bar(vm.loadFraction, Nx.Cyan, Modifier.width(Px.u(800f)), height = 6f)
            if (vm.stillWaiting) {
                Spacer(Modifier.height(Px.u(10f)))
                T("Still waiting on your server", 28f, Nx.Amber, medium = true)
                Slab("STOP WAITING", true, Modifier.width(Px.u(420f)), accent = Nx.Amber)
            }
        }
    }
}
