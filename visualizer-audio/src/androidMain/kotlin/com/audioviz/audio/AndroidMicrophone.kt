package com.audioviz.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Process
import com.audioviz.core.capture.AudioFrameSink
import com.audioviz.core.capture.AudioSource
import com.audioviz.core.capture.AudioSourceState
import com.audioviz.core.capture.AudioSourceStatus
import com.audioviz.core.capture.MicrophoneConfig

actual fun createMicrophoneSource(config: MicrophoneConfig): AudioSource =
    AndroidMicrophoneSource(config)

actual fun audioPlatformName(): String = "Android / AudioRecord"

/**
 * Android capture on a dedicated `THREAD_PRIORITY_URGENT_AUDIO` thread.
 *
 * ### Why `UNPROCESSED` and why the effects are switched off
 * The default `MIC` source runs the platform's voice pipeline, and automatic
 * gain control in particular is actively hostile to a visualizer: it spends the
 * first second of every utterance changing the very gain the analyzer is trying
 * to measure, and it *raises* the gain during silence, so a quiet room slowly
 * turns into a noisy one and the visual starts breathing on its own. Requesting
 * `UNPROCESSED` (and explicitly releasing AGC/NS/AEC where the device exposes
 * them) gives a stable signal that the analyzer's own normalization can work
 * with. Set [MicrophoneConfig.enableVoiceProcessing] if the same microphone is
 * also feeding a call, where echo cancellation matters more.
 *
 * ### Permission
 * `RECORD_AUDIO` must already be granted. If it is not, the failure surfaces
 * through [status] as [AudioSourceState.PERMISSION_DENIED] rather than as a
 * thrown exception, so the UI can show a rationale without a try/catch.
 */
class AndroidMicrophoneSource(
    private val config: MicrophoneConfig,
) : AudioSource {

    @Volatile
    override var status: AudioSourceStatus = AudioSourceStatus.Idle
        private set

    @Volatile
    override var sampleRate: Int = 0
        private set

    private var record: AudioRecord? = null
    private var thread: Thread? = null
    private val effects = mutableListOf<android.media.audiofx.AudioEffect>()

    @Volatile
    private var running = false

    @SuppressLint("MissingPermission")
    override fun start(sink: AudioFrameSink) {
        if (running) return
        status = AudioSourceStatus.Starting

        val sources = if (config.enableVoiceProcessing) {
            intArrayOf(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                MediaRecorder.AudioSource.MIC,
            )
        } else {
            intArrayOf(
                MediaRecorder.AudioSource.UNPROCESSED,
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                MediaRecorder.AudioSource.MIC,
            )
        }
        val rates = buildList {
            add(config.preferredSampleRate)
            addAll(config.fallbackSampleRates)
        }

        var opened: AudioRecord? = null
        var chosenRate = 0
        var lastError: String? = null

        outer@ for (audioSource in sources) {
            for (rate in rates) {
                val minBytes = AudioRecord.getMinBufferSize(
                    rate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_FLOAT,
                )
                if (minBytes <= 0) {
                    lastError = "no float PCM buffer available at $rate Hz"
                    continue
                }
                // Four blocks of headroom, and never below the device minimum.
                val bufferBytes = maxOf(minBytes, config.blockSize * BYTES_PER_FLOAT * 4)

                val candidate = try {
                    AudioRecord.Builder()
                        .setAudioSource(audioSource)
                        .setAudioFormat(
                            AudioFormat.Builder()
                                .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                                .setSampleRate(rate)
                                .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                                .build(),
                        )
                        .setBufferSizeInBytes(bufferBytes)
                        .build()
                } catch (e: SecurityException) {
                    status = AudioSourceStatus(
                        AudioSourceState.PERMISSION_DENIED,
                        "RECORD_AUDIO has not been granted",
                    )
                    return
                } catch (e: UnsupportedOperationException) {
                    lastError = "source $audioSource unsupported at $rate Hz"
                    continue
                } catch (e: IllegalArgumentException) {
                    lastError = "invalid capture format at $rate Hz: ${e.message}"
                    continue
                } catch (e: IllegalStateException) {
                    lastError = "capture device busy: ${e.message}"
                    continue
                }

                if (candidate.state != AudioRecord.STATE_INITIALIZED) {
                    candidate.release()
                    lastError = "AudioRecord failed to initialise (source $audioSource, $rate Hz)"
                    continue
                }

                opened = candidate
                chosenRate = rate
                break@outer
            }
        }

        val target = opened
        if (target == null) {
            status = AudioSourceStatus(
                AudioSourceState.UNAVAILABLE,
                lastError ?: "no capture device is available",
            )
            return
        }

        if (!config.enableVoiceProcessing) disablePlatformEffects(target.audioSessionId)

        try {
            target.startRecording()
        } catch (e: IllegalStateException) {
            releaseEffects()
            target.release()
            status = AudioSourceStatus(AudioSourceState.ERROR, "startRecording failed: ${e.message}")
            return
        }

        if (target.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            releaseEffects()
            target.release()
            status = AudioSourceStatus(
                AudioSourceState.PERMISSION_DENIED,
                "recording did not start; RECORD_AUDIO is most likely missing",
            )
            return
        }

        record = target
        sampleRate = chosenRate
        running = true
        status = AudioSourceStatus.Running

        thread = Thread({ captureLoop(target, chosenRate, sink) }, "audioviz-capture").apply {
            start()
        }
    }

    override fun stop() {
        running = false
        thread?.join(500)
        thread = null

        releaseEffects()

        record?.let { r ->
            runCatching { if (r.recordingState == AudioRecord.RECORDSTATE_RECORDING) r.stop() }
            runCatching { r.release() }
        }
        record = null
        sampleRate = 0
        if (!status.isFailed) status = AudioSourceStatus.Idle
    }

    private fun captureLoop(target: AudioRecord, rate: Int, sink: AudioFrameSink) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val buffer = FloatArray(config.blockSize)
        try {
            while (running) {
                val read = target.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
                when {
                    read > 0 -> sink.onAudioFrame(buffer, 0, read, rate)

                    read == AudioRecord.ERROR_INVALID_OPERATION -> {
                        status = AudioSourceStatus(
                            AudioSourceState.PERMISSION_DENIED,
                            "the microphone stopped delivering audio; check RECORD_AUDIO",
                        )
                        return
                    }

                    read == AudioRecord.ERROR_DEAD_OBJECT -> {
                        status = AudioSourceStatus(
                            AudioSourceState.ERROR,
                            "the audio device was lost (headset unplugged, or a call took over)",
                        )
                        return
                    }

                    read < 0 -> {
                        status = AudioSourceStatus(AudioSourceState.ERROR, "read failed with code $read")
                        return
                    }
                }
            }
        } catch (e: Throwable) {
            if (running) {
                status = AudioSourceStatus(
                    AudioSourceState.ERROR,
                    "capture failed: ${e::class.simpleName}: ${e.message}",
                )
            }
        }
    }

    private fun releaseEffects() {
        effects.forEach { runCatching { it.release() } }
        effects.clear()
    }

    /**
     * Turns off any platform voice effects attached to this session.
     *
     * `UNPROCESSED` should already avoid them, but a number of devices attach
     * AGC regardless, so this is belt and braces. Every call is guarded: these
     * classes are optional and vendor implementations are known to throw.
     */
    private fun disablePlatformEffects(sessionId: Int) {
        runCatching {
            if (AutomaticGainControl.isAvailable()) {
                AutomaticGainControl.create(sessionId)?.also {
                    it.enabled = false
                    effects += it
                }
            }
        }
        runCatching {
            if (NoiseSuppressor.isAvailable()) {
                NoiseSuppressor.create(sessionId)?.also {
                    it.enabled = false
                    effects += it
                }
            }
        }
        runCatching {
            if (AcousticEchoCanceler.isAvailable()) {
                AcousticEchoCanceler.create(sessionId)?.also {
                    it.enabled = false
                    effects += it
                }
            }
        }
    }

    private companion object {
        const val BYTES_PER_FLOAT = 4
    }
}
