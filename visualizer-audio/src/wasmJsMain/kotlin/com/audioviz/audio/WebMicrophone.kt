package com.audioviz.audio

import com.audioviz.core.capture.AudioFrameSink
import com.audioviz.core.capture.AudioSource
import com.audioviz.core.capture.AudioSourceState
import com.audioviz.core.capture.AudioSourceStatus
import com.audioviz.core.capture.MicrophoneConfig
import org.khronos.webgl.Float32Array
import org.khronos.webgl.get

actual fun createMicrophoneSource(config: MicrophoneConfig): AudioSource =
    WebAudioMicrophoneSource(config)

actual fun audioPlatformName(): String = "Web / Web Audio API"

// ---------------------------------------------------------------------------
// Minimal typed bindings. Only the handful of members this file uses are
// declared; everything else stays out of the way.
// ---------------------------------------------------------------------------

internal external interface AudioNode : JsAny {
    fun connect(destination: AudioNode): AudioNode
    fun disconnect()
}

internal external interface AnalyserNode : AudioNode {
    var fftSize: Int
    var smoothingTimeConstant: Double
    fun getFloatTimeDomainData(array: Float32Array)
}

internal external class AudioContext : JsAny {
    val sampleRate: Float
    val state: String
    fun createAnalyser(): AnalyserNode
    fun createMediaStreamSource(stream: JsAny): AudioNode
    fun resume(): JsAny
    fun close(): JsAny
}

/** `navigator.mediaDevices.getUserMedia`, or `null` on an insecure origin. */
private fun requestMicrophone(
    voiceProcessing: Boolean,
    onGranted: (JsAny) -> Unit,
    onDenied: (String) -> Unit,
): Unit = js(
    """{
        if (!navigator.mediaDevices || !navigator.mediaDevices.getUserMedia) {
            onDenied('getUserMedia is unavailable; a secure origin (https or localhost) is required');
            return;
        }
        navigator.mediaDevices.getUserMedia({
            audio: {
                echoCancellation: voiceProcessing,
                autoGainControl: voiceProcessing,
                noiseSuppression: voiceProcessing
            }
        }).then(function (stream) { onGranted(stream); })
          .catch(function (err) { onDenied(String(err && err.name ? err.name : err)); });
    }"""
)

/** Stops every track of a MediaStream so the browser drops the recording indicator. */
private fun stopStream(stream: JsAny): Unit = js(
    """{
        var tracks = stream.getTracks ? stream.getTracks() : [];
        for (var i = 0; i < tracks.length; i++) tracks[i].stop();
    }"""
)

/**
 * Web capture built on `getUserMedia` and an `AnalyserNode`.
 *
 * ### Why pull instead of push
 * The browser gives no worker thread we may block on, and an `AudioWorklet`
 * would mean shipping a separate JavaScript file alongside the Wasm bundle. An
 * `AnalyserNode` keeps a rolling window of the most recent `fftSize` samples,
 * updated by the browser's own audio thread, which the render loop can sample
 * for free — so [pump] is called once per animation frame and hands over
 * exactly the audio that elapsed since the previous frame.
 *
 * ### Keeping the stream continuous
 * The analyser's window (2048 samples, ~43 ms at 48 kHz) is longer than one
 * frame (~17 ms at 60 fps), so naively forwarding the whole window every frame
 * would feed the analyzer the same audio two or three times and make its clock
 * run fast. Instead only the newest `elapsed * sampleRate` samples are taken,
 * which reconstructs a continuous, non-overlapping stream. A frame longer than
 * the window (below ~23 fps) drops the excess rather than duplicating it.
 *
 * If you need sample-exact capture — recording, or an onset detector that must
 * not miss a single click — replace this with an `AudioWorklet` writing into a
 * `SharedArrayBuffer` ring; the rest of the system is unaffected because it only
 * sees [AudioSource].
 */
class WebAudioMicrophoneSource(
    private val config: MicrophoneConfig,
) : AudioSource {

    override var status: AudioSourceStatus = AudioSourceStatus.Idle
        private set

    override var sampleRate: Int = 0
        private set

    private var context: AudioContext? = null
    private var analyser: AnalyserNode? = null
    private var stream: JsAny? = null
    private var jsBuffer: Float32Array? = null
    private var samples: FloatArray = FloatArray(0)
    private var historySize: Int = 0
    private var sink: AudioFrameSink? = null

    override fun start(sink: AudioFrameSink) {
        if (status.state == AudioSourceState.STARTING || status.isRunning) return
        this.sink = sink
        status = AudioSourceStatus.Starting

        requestMicrophone(
            voiceProcessing = config.enableVoiceProcessing,
            onGranted = { granted -> open(granted) },
            onDenied = { reason ->
                status = AudioSourceStatus(
                    if (reason.contains("NotAllowed") || reason.contains("Permission")) {
                        AudioSourceState.PERMISSION_DENIED
                    } else {
                        AudioSourceState.UNAVAILABLE
                    },
                    reason,
                )
            },
        )
    }

    private fun open(mediaStream: JsAny) {
        try {
            val ctx = AudioContext()
            val node = ctx.createAnalyser()
            // The window must cover at least one frame at the lowest frame rate
            // we intend to survive; 2048 samples is ~43 ms at 48 kHz.
            node.fftSize = HISTORY_SAMPLES
            // We read the time-domain buffer, which this does not affect, but a
            // zero here keeps the frequency data honest for anyone who adds it.
            node.smoothingTimeConstant = 0.0
            ctx.createMediaStreamSource(mediaStream).connect(node)

            context = ctx
            analyser = node
            stream = mediaStream
            historySize = HISTORY_SAMPLES
            jsBuffer = Float32Array(historySize)
            samples = FloatArray(historySize)
            sampleRate = ctx.sampleRate.toInt()

            // Autoplay policy: a context created before a user gesture starts
            // suspended. Resuming is harmless when it is already running.
            ctx.resume()
            status = AudioSourceStatus.Running
        } catch (e: Throwable) {
            status = AudioSourceStatus(
                AudioSourceState.ERROR,
                "failed to open the audio graph: ${e.message}",
            )
        }
    }

    override fun pump(elapsedSeconds: Float) {
        val node = analyser ?: return
        val buffer = jsBuffer ?: return
        val target = sink ?: return
        val rate = sampleRate
        if (rate <= 0) return

        val wanted = (elapsedSeconds.coerceAtLeast(0f) * rate).toInt().coerceIn(0, historySize)
        if (wanted <= 0) return

        node.getFloatTimeDomainData(buffer)

        // Take the newest `wanted` samples: the analyser window is a rolling
        // buffer whose last element is the most recent sample.
        val start = historySize - wanted
        for (i in 0 until wanted) {
            samples[i] = buffer[start + i]
        }
        target.onAudioFrame(samples, 0, wanted, rate)
    }

    override fun stop() {
        analyser?.disconnect()
        analyser = null
        stream?.let { stopStream(it) }
        stream = null
        context?.close()
        context = null
        jsBuffer = null
        sink = null
        sampleRate = 0
        if (!status.isFailed) status = AudioSourceStatus.Idle
    }

    private companion object {
        /** Power of two, and long enough to cover a frame down to ~23 fps. */
        const val HISTORY_SAMPLES = 2048
    }
}
