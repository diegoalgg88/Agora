package com.newoether.agora.assistant

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import android.Manifest
import android.content.pm.PackageManager

/**
 * Transparent, finish-immediately trampoline that requests RECORD_AUDIO from the assistant
 * overlay. [VoiceInteractionSession] windows cannot host runtime permission dialogs, so the
 * overlay launches this activity, the user answers the system dialog, and the activity closes
 * back into the session. Grant result is not tracked here — the overlay re-checks on the next
 * mic tap.
 */
class AssistantVoicePermissionActivity : ComponentActivity() {

    private val request = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { finish() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val permission = Manifest.permission.RECORD_AUDIO
        if (ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED) {
            finish()
            return
        }
        request.launch(permission)
    }
}
