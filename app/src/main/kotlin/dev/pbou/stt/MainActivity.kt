package dev.pbou.stt

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.content.IntentCompat
import dev.pbou.stt.ui.SttAppUi
import dev.pbou.stt.ui.theme.SttTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        val graph = (application as SttApplication).graph
        // A recreated activity (rotation...) gets its old intent again: open the shared file only once.
        if (savedInstanceState == null) handle(intent)
        setContent {
            SttTheme { SttAppUi(graph) }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    /** A file shared to the app ("Share" or "Open with"): transcribe it. From a notification: show its item. */
    private fun handle(intent: Intent?) {
        intent?.getStringExtra(EXTRA_ITEM)?.let { id ->
            (application as SttApplication).graph.showItem.trySend(id)
            return
        }
        val uri = when (intent?.action) {
            Intent.ACTION_SEND -> IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
            Intent.ACTION_VIEW -> intent.data
            else -> null
        } ?: return
        (application as SttApplication).graph.openFile(uri)
    }

    companion object {
        const val EXTRA_ITEM = "dev.pbou.stt.ITEM"
    }

    override fun onResume() {
        super.onResume()
        // Picks up models copied in with adb while the app was in the background.
        (application as SttApplication).graph.refreshStore()
    }
}
