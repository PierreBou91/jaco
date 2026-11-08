package dev.pbou.stt.keyboard

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

/**
 * A keyboard cannot ask for a permission itself: this invisible screen asks for the microphone, then
 * closes, back to the app being typed in.
 */
class MicPermissionActivity : ComponentActivity() {

    private val ask = registerForActivityResult(ActivityResultContracts.RequestPermission()) { finish() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) ask.launch(Manifest.permission.RECORD_AUDIO)
    }
}
