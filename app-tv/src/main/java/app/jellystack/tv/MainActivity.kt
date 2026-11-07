package app.jellystack.tv

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.navigationevent.setViewTreeNavigationEventDispatcherOwner
import dev.jellystack.design.tv.VideoProTvRoot

class MainActivity : AppCompatActivity() {
    private var systemDictationEnabled by mutableStateOf(false)
    private var pendingDictationSettings = false
    private val microphonePermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (pendingDictationSettings) {
                pendingDictationSettings = false
                if (granted) {
                    openDictationSettings()
                } else {
                    Toast.makeText(
                        this,
                        R.string.system_dictation_microphone_denied,
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        systemDictationEnabled = SystemDictationAccess.isEnabled(this)
        window.decorView.setViewTreeNavigationEventDispatcherOwner(this)
        setContent {
            VideoProTvRoot(
                onExitConfirmed = ::finish,
                systemDictationEnabled = systemDictationEnabled,
                onOpenSystemDictationSettings = ::requestDictationSettings,
            )
        }
    }

    override fun onResume() {
        super.onResume()
        systemDictationEnabled = SystemDictationAccess.isEnabled(this)
    }

    private fun requestDictationSettings() {
        if (Build.VERSION.SDK_INT < 33) {
            Toast.makeText(this, R.string.system_dictation_android13_required, Toast.LENGTH_SHORT).show()
            return
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            pendingDictationSettings = true
            microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        openDictationSettings()
    }

    private fun openDictationSettings() {
        if (!SystemDictationAccess.openSettings(this)) {
            Toast.makeText(this, R.string.system_dictation_open_settings_failed, Toast.LENGTH_SHORT).show()
        }
    }
}
