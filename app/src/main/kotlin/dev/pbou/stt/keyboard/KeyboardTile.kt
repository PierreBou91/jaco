package dev.pbou.stt.keyboard

import android.Manifest
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
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
 *
 * With the optional WRITE_SECURE_SETTINGS permission (only grantable from a computer, see the manifest),
 * the tile switches in one tap instead: to Jaco, or back to the keyboard used before.
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

        fun isCurrent(context: Context): Boolean = current(context)?.startsWith(context.packageName + "/") == true

        private fun current(context: Context): String? =
            Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
    }
}

/** Switching between Jaco and the keyboard the user came from. */
object KeyboardSwitch {
    private const val PREFS = "keyboard"
    private const val PREVIOUS = "previous_ime"
    private const val SHOW_WINDOW_MS = 5_000L

    /** Set just before Jaco may become the keyboard: it then opens itself in the next text field. */
    @Volatile private var showUntil = 0L

    /** Whether the one-tap switch permission was given (from a computer, with adb). */
    fun canSwitchDirectly(context: Context) =
        context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    /** Jaco's id in the system's keyboard list, null if it is not turned on. */
    fun jacoId(context: Context): String? =
        context.getSystemService(InputMethodManager::class.java).enabledInputMethodList
            .firstOrNull { it.packageName == context.packageName }?.id

    /** The keyboard to go back to, if it is still turned on. */
    fun previous(context: Context): String? {
        val id = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(PREVIOUS, null) ?: return null
        val enabled = context.getSystemService(InputMethodManager::class.java).enabledInputMethodList
        return id.takeIf { enabled.any { it.id == id && it.packageName != context.packageName } }
    }

    /** Remembers the current keyboard (if it is not Jaco), the one to go back to. */
    fun rememberCurrent(context: Context) {
        val current = Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD) ?: return
        if (current.startsWith(context.packageName + "/")) return
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(PREVIOUS, current).apply()
    }

    /** Switches without asking. Needs [canSwitchDirectly]. False if it could not. */
    fun switchDirectly(context: Context, id: String): Boolean = runCatching {
        Settings.Secure.putString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD, id)
    }.getOrDefault(false)

    fun showOnNextField() {
        showUntil = SystemClock.elapsedRealtime() + SHOW_WINDOW_MS
    }

    /** True once if Jaco was just chosen: it should open even though the text field did not ask. */
    fun takeShowRequest(): Boolean {
        val show = SystemClock.elapsedRealtime() < showUntil
        showUntil = 0L
        return show
    }
}

/**
 * Invisible: shows the system keyboard chooser (it needs a window with focus), then closes, back to the
 * app being typed in. If the Jaco keyboard is not turned on yet, opens the phone's keyboard list instead.
 * With the one-tap permission, switches straight away (to Jaco, or back from it) and closes.
 */
class KeyboardPickerActivity : ComponentActivity() {
    private var requested = false
    private var pickerShown = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val jaco = KeyboardSwitch.jacoId(this)
        if (jaco == null) {
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
            finish()
            return
        }
        if (KeyboardSwitch.canSwitchDirectly(this)) {
            val target = if (KeyboardTile.isCurrent(this)) KeyboardSwitch.previous(this) else jaco
            if (target != null) {
                if (target == jaco) {
                    KeyboardSwitch.rememberCurrent(this)
                    KeyboardSwitch.showOnNextField()
                }
                if (KeyboardSwitch.switchDirectly(this, target)) {
                    finish()
                    return
                }
            }
        }
        // The chooser. Its switch key will come back to the current keyboard.
        KeyboardSwitch.rememberCurrent(this)
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
            pickerShown -> {
                // The chooser was closed: if Jaco was picked, it opens in the field without another tap.
                KeyboardSwitch.showOnNextField()
                finish()
            }
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
