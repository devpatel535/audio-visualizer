package com.audioviz.demo

import androidx.compose.runtime.Composable

/** Runtime microphone permission, where the platform has one. */
interface MicrophoneAccess {
    /** True when capture may begin. */
    val granted: Boolean

    /** True once a request has been made and refused. */
    val denied: Boolean

    /** Asks the user. A no-op where the platform grants implicitly. */
    fun request()
}

/**
 * Platform microphone permission.
 *
 * Android needs an explicit runtime grant; the JVM has none; the browser
 * prompts as part of `getUserMedia`, so both report granted and let the capture
 * layer surface a refusal through [com.audioviz.core.capture.AudioSourceStatus].
 */
@Composable
expect fun rememberMicrophoneAccess(): MicrophoneAccess
