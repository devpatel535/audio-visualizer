package com.audioviz.audio

import com.audioviz.core.capture.AudioSource
import com.audioviz.core.capture.MicrophoneConfig

/**
 * Creates the platform's microphone source.
 *
 * The returned object satisfies [AudioSource] and nothing more, which is the
 * whole point: the analyzer, the animation controller and every renderer are
 * compiled against that interface, so none of them can accidentally depend on
 * `AudioRecord`, `TargetDataLine` or the Web Audio API.
 *
 * Implementations:
 *
 * | Target        | Backend                                    | Delivery |
 * |---------------|--------------------------------------------|----------|
 * | Android       | `AudioRecord` on a dedicated audio thread   | push     |
 * | JVM / desktop | `javax.sound.sampled.TargetDataLine`        | push     |
 * | Wasm / web    | `getUserMedia` + `AnalyserNode`             | pull     |
 *
 * Failures are reported through [AudioSource.status] rather than thrown, so a
 * host can render a "microphone unavailable" state without a try/catch around
 * its composition.
 */
expect fun createMicrophoneSource(config: MicrophoneConfig = MicrophoneConfig()): AudioSource

/** Short platform identifier, for diagnostics and the demo's status line. */
expect fun audioPlatformName(): String
