package com.audioviz.demo

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

/**
 * The browser prompts during `getUserMedia`, so there is nothing to request up
 * front; a refusal arrives as `AudioSourceState.PERMISSION_DENIED`.
 */
private object BrowserPrompted : MicrophoneAccess {
    override val granted: Boolean = true
    override val denied: Boolean = false
    override fun request() = Unit
}

@Composable
actual fun rememberMicrophoneAccess(): MicrophoneAccess = remember { BrowserPrompted }
