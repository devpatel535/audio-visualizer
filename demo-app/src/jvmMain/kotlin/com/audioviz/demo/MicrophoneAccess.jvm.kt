package com.audioviz.demo

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

private object AlwaysGranted : MicrophoneAccess {
    override val granted: Boolean = true
    override val denied: Boolean = false
    override fun request() = Unit
}

@Composable
actual fun rememberMicrophoneAccess(): MicrophoneAccess = remember { AlwaysGranted }
