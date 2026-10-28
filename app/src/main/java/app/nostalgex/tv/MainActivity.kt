package app.nostalgex.tv

import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import app.nostalgex.tv.tuner.AppViewModel
import app.nostalgex.tv.ui.NostalgexRoot

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent { NostalgexRoot(vm) }
    }

    override fun onStart() {
        super.onStart()
        vm.onStart()
    }

    override fun onStop() {
        vm.onStop()
        super.onStop()
    }

    /**
     * The remote goes straight to the view model, which keeps its own focus for every screen.
     * Only an open text field gets keys the normal way, for the TV's keyboard. A key press the
     * view model doesn't use (Back on the guide, twice) goes on to the system.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (vm.textEntry != null) return super.dispatchKeyEvent(event)
        if (event.action == KeyEvent.ACTION_DOWN) {
            if (vm.onKeyDown(event.keyCode)) return true
        } else if (event.action == KeyEvent.ACTION_UP && event.keyCode != KeyEvent.KEYCODE_BACK) {
            if (isRemoteKey(event.keyCode)) return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun isRemoteKey(code: Int) = when (code) {
        KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
        KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_MENU -> true
        else -> false
    }
}
