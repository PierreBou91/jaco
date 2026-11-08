package dev.pbou.stt.keyboard

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.view.MotionEvent
import android.view.inputmethod.InputMethodManager
import androidx.activity.ComponentActivity

/**
 * A Quick Settings tile that brings up the keyboard chooser, from any app. Some keyboards (SwiftKey) have
 * no key to switch keyboards, and gesture navigation hides the system's switch button: without this, the
 * Jaco keyboard could not be reached while typing.
 */
class KeyboardTile : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        val tile = qsTile ?: return
        tile.state = if (isCurrent(this)) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.updateTile()
    }

    override fun onClick() {
        super.onClick()
        val intent = Intent(this, KeyboardPickerActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE))
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    companion object {
        fun component(context: Context) = ComponentName(context, KeyboardTile::class.java)

        fun isEnabled(context: Context): Boolean =
            context.getSystemService(InputMethodManager::class.java).enabledInputMethodList.any { it.packageName == context.packageName }

        fun isCurrent(context: Context): Boolean =
            Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
                ?.startsWith(context.packageName + "/") == true
    }
}

/**
 * Invisible: shows the system keyboard chooser (it needs a window with focus), then closes, back to the
 * app being typed in. If the Jaco keyboard is not turned on yet, opens the phone's keyboard list instead.
 */
class KeyboardPickerActivity : ComponentActivity() {
    private var requested = false
    private var pickerShown = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!KeyboardTile.isEnabled(this)) {
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
            finish()
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        when {
            !hasFocus -> if (requested) pickerShown = true // the chooser took the focus
            !requested -> {
                requested = true
                // The system only shows the chooser to the app it has registered as the focused one,
                // which happens a moment after the focus event: ask a little later.
                window.decorView.postDelayed({
                    getSystemService(InputMethodManager::class.java).showInputMethodPicker()
                }, PICKER_DELAY_MS)
                // Never stay as an invisible screen over the app if the chooser does not come.
                window.decorView.postDelayed({ if (!pickerShown) finish() }, GIVE_UP_MS)
            }
            pickerShown -> finish() // the chooser was closed
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        finish()
        return true
    }

    private companion object {
        const val PICKER_DELAY_MS = 200L
        const val GIVE_UP_MS = 2_000L
    }
}
