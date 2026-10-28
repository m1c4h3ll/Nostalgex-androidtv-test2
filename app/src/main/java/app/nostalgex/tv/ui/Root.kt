package app.nostalgex.tv.ui

import android.graphics.Color as AColor
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import app.nostalgex.tv.tuner.AppViewModel
import app.nostalgex.tv.tuner.Screen

/** Guide geometry, after TunerView: the top section is 51.43% of the height, the video 4:3. */
class GuideGeometry(val width: Float, val height: Float) {
    val videoHeight = height * 0.5143f
    val videoWidth = minOf(width, videoHeight * 4f / 3f)
    val panelWidth = width - videoWidth
}

@Composable
fun NostalgexRoot(vm: AppViewModel) {
    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
        val density = LocalDensity.current
        // Everything is laid out in 1080p pixels: one of them is maxWidth / 1920.
        Px.scale = maxWidth.value / 1920f
        Px.fontScale = density.fontScale
        val geo = GuideGeometry(1920f, maxHeight.value / Px.scale)

        val showVideo = vm.screen == Screen.GUIDE || vm.screen == Screen.SETTINGS
        if (showVideo && vm.screen == Screen.GUIDE) VideoLayer(vm, geo)

        when (vm.screen) {
            Screen.BOOT -> BootScreen()
            Screen.CONNECT -> ConnectScreen(vm)
            Screen.LOADING -> LoadingScreen(vm)
            Screen.GUIDE -> if (vm.fullscreen) FullscreenOverlay(vm) else GuideScreen(vm, geo)
            Screen.SETTINGS -> SettingsScreen(vm)
            Screen.ERROR -> ErrorScreen(vm)
        }

        vm.toast?.let { msg ->
            Box(Modifier.fillMaxSize().padding(bottom = Px.u(70f)), contentAlignment = Alignment.BottomCenter) {
                Box(
                    Modifier.background(Color(0xE6101024)).border(Px.u(2f), Nx.Cyan.copy(alpha = 0.6f))
                        .padding(horizontal = Px.u(36f), vertical = Px.u(18f)),
                ) { T(msg, 24f, Color.White) }
            }
        }
        vm.textEntry?.let { TextEntryOverlay(vm) }
    }
}

/**
 * The one PlayerView, in the guide's video box or full screen. In RETRO MODE the guide's box
 * crops the picture to 4:3 (the panel and grid cover the overflow); full screen keeps it whole.
 */
@OptIn(UnstableApi::class)
@Composable
fun VideoLayer(vm: AppViewModel, geo: GuideGeometry) {
    val full = vm.fullscreen
    val mod = if (full) Modifier.fillMaxSize()
    else Modifier.offset(x = Px.u(geo.panelWidth), y = Px.u(0f)).size(Px.u(geo.videoWidth), Px.u(geo.videoHeight))
    val resize = if (!full && vm.retroMode) AspectRatioFrameLayout.RESIZE_MODE_ZOOM else AspectRatioFrameLayout.RESIZE_MODE_FIT
    Box(mod) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                    useController = false
                    setShutterBackgroundColor(AColor.BLACK)
                    setKeepContentOnPlayerReset(false)
                    player = vm.playback.player
                    keepScreenOn = true
                }
            },
            update = { it.resizeMode = resize },
            modifier = Modifier.fillMaxSize(),
        )
        if (vm.retroMode) CrtOverlay()
        TuningLayer(vm, compact = !full)
    }
}

@Composable
fun BootScreen() {
    Box(Modifier.fillMaxSize().background(Nx.Panel0), contentAlignment = Alignment.Center) {
        Logo(Px.u(110f))
    }
}

@Composable
fun ErrorScreen(vm: AppViewModel) {
    Box(Modifier.fillMaxSize().background(Nx.Panel0), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Px.u(28f))) {
            Logo(Px.u(80f))
            T(vm.errorMessage, 28f, Color.White, modifier = Modifier.width(Px.u(1200f)), maxLines = 4)
            T("PRESS OK TO TRY AGAIN", 30f, Nx.Yellow, retro = true)
            T("MENU TO SIGN OUT", 26f, Color.White.copy(alpha = 0.5f), retro = true)
        }
    }
}

/** The TV's keyboard over a text field, for an address, a name or a password. */
@kotlin.OptIn(ExperimentalComposeUiApi::class)
@Composable
fun TextEntryOverlay(vm: AppViewModel) {
    val entry = vm.textEntry ?: return
    var value by remember(entry) { mutableStateOf(entry.initial) }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    fun done() {
        vm.textEntry = null
        entry.onDone(value)
    }
    BackHandler { vm.textEntry = null }
    Box(Modifier.fillMaxSize().background(Color(0xF0050510)), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Px.u(24f))) {
            T(entry.label, 34f, Nx.Cyan, retro = true)
            Box(
                Modifier.width(Px.u(1100f)).background(Color(0xFF07071A)).border(Px.u(3f), Nx.Cyan)
                    .padding(horizontal = Px.u(28f), vertical = Px.u(22f)),
            ) {
                BasicTextField(
                    value = value,
                    onValueChange = { value = it },
                    singleLine = true,
                    textStyle = TextStyle(color = Color.White, fontFamily = Nx.DMMono, fontSize = Px.sp(34f)),
                    cursorBrush = SolidColor(Nx.Cyan),
                    visualTransformation = if (entry.password) PasswordVisualTransformation() else VisualTransformation.None,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = if (entry.password) KeyboardType.Password else KeyboardType.Uri,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(onDone = { done() }),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus).onPreviewKeyEvent { e ->
                        if (e.type == KeyEventType.KeyUp && (e.key == Key.Enter || e.key == Key.NumPadEnter || e.key == Key.DirectionCenter)) {
                            done()
                            true
                        } else false
                    },
                )
            }
            T("DONE ON THE KEYBOARD TO CONFIRM · BACK TO CANCEL", 22f, Color.White.copy(alpha = 0.5f))
        }
    }
    LaunchedEffect(entry) {
        focus.requestFocus()
        keyboard?.show()
    }
}
