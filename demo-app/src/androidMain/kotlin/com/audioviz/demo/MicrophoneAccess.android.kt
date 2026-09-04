package com.audioviz.demo

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

@Composable
actual fun rememberMicrophoneAccess(): MicrophoneAccess {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    var denied by remember { mutableStateOf(false) }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { result ->
        granted = result
        denied = !result
    }

    // Capture into locals: referring to `granted` from inside the object would
    // resolve to the object's own property, not the composable's state.
    val isGranted = granted
    val isDenied = denied
    return remember(isGranted, isDenied, launcher) {
        object : MicrophoneAccess {
            override val granted: Boolean = isGranted
            override val denied: Boolean = isDenied
            override fun request() {
                launcher.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
    }
}
