package com.audioviz.core.capture

/**
 * Receives blocks of mono float PCM in `-1..1`.
 *
 * Implementations must be cheap and must not block: on Android and the JVM this
 * is called from the recording thread, and anything slow here shows up as
 * dropped audio, not as a dropped frame.
 */
fun interface AudioFrameSink {
    fun onAudioFrame(samples: FloatArray, offset: Int, length: Int, sampleRate: Int)
}

/** Lifecycle of an [AudioSource]. */
enum class AudioSourceState {
    /** Created but not started. */
    IDLE,

    /** Start requested; waiting on permission or device open. */
    STARTING,

    /** Delivering audio. */
    RUNNING,

    /** The user or the OS refused microphone access. */
    PERMISSION_DENIED,

    /** No capture device, or the platform has no implementation. */
    UNAVAILABLE,

    /** Started and then failed. [AudioSourceStatus.message] carries the detail. */
    ERROR,
}

/** State plus an optional human-readable detail. */
data class AudioSourceStatus(
    val state: AudioSourceState,
    val message: String? = null,
) {
    val isRunning: Boolean get() = state == AudioSourceState.RUNNING

    val isFailed: Boolean get() =
        state == AudioSourceState.PERMISSION_DENIED ||
            state == AudioSourceState.UNAVAILABLE ||
            state == AudioSourceState.ERROR

    companion object {
        val Idle = AudioSourceStatus(AudioSourceState.IDLE)
        val Starting = AudioSourceStatus(AudioSourceState.STARTING)
        val Running = AudioSourceStatus(AudioSourceState.RUNNING)
    }
}

/** Requested capture parameters. Platforms honour these on a best-effort basis. */
data class MicrophoneConfig(
    /** Preferred sample rate. 48 kHz is native on essentially all modern hardware. */
    val preferredSampleRate: Int = 48_000,

    /**
     * Fallback rates tried in order when the preferred rate is refused.
     * 44.1 kHz is the usual second choice; 16 kHz always works on Android.
     */
    val fallbackSampleRates: List<Int> = listOf(44_100, 32_000, 16_000),

    /** Capture block size in samples. Smaller = lower latency, more callbacks. */
    val blockSize: Int = 1024,

    /**
     * Platform voice processing (AEC / noise suppression / AGC).
     *
     * Off by default and deliberately so: automatic gain control fights the
     * analyzer's own normalization, producing a visual that breathes in and out
     * on its own during silence. Turn it on only if the same stream is also
     * being used for a call.
     */
    val enableVoiceProcessing: Boolean = false,
)

/**
 * A source of microphone (or synthetic) audio.
 *
 * Two delivery models are supported behind one interface, because the platforms
 * genuinely differ:
 *
 *  - **push** (Android `AudioRecord`, JVM `TargetDataLine`): a dedicated
 *    capture thread calls the sink as blocks arrive. [pump] does nothing.
 *  - **pull** (Web Audio): the browser has no worker thread we can block on, so
 *    the host calls [pump] once per animation frame and the source hands over
 *    whatever the `AnalyserNode` currently holds.
 *
 * The host composable calls [pump] unconditionally every frame, so a renderer or
 * controller never has to know which model it is talking to.
 */
interface AudioSource {
    /** Current status. Safe to read from any thread. */
    val status: AudioSourceStatus

    /** Sample rate actually in use, or 0 before the device opens. */
    val sampleRate: Int

    /** Begins capture, delivering blocks to [sink]. Idempotent. */
    fun start(sink: AudioFrameSink)

    /**
     * Gives a pull-based source a chance to deliver.
     *
     * Called once per rendered frame from the render loop. Push-based sources
     * ignore it entirely.
     *
     * @param elapsedSeconds time since the previous call. A pull-based source
     *   needs this to hand over exactly the audio that elapsed: delivering a
     *   fixed block per frame would make its clock run at whatever ratio the
     *   block size happens to bear to the frame time, and the analyzer's time
     *   constants would silently mean something different.
     */
    fun pump(elapsedSeconds: Float) {}

    /** Stops capture and releases the device. Idempotent. */
    fun stop()
}
