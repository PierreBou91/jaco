package dev.pbou.stt

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
        setContent {
            SttTheme { SttAppUi(graph) }
        }
    }

    override fun onResume() {
        super.onResume()
        // Picks up models copied in with adb while the app was in the background.
        (application as SttApplication).graph.refreshStore()
    }
}
