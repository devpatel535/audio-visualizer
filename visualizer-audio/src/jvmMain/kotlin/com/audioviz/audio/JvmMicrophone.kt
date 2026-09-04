package com.audioviz.audio

import com.audioviz.core.capture.AudioFrameSink
import com.audioviz.core.capture.AudioSource
import com.audioviz.core.capture.AudioSourceState
import com.audioviz.core.capture.AudioSourceStatus
import com.audioviz.core.capture.MicrophoneConfig
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.LineUnavailableException
import javax.sound.sampled.TargetDataLine

actual fun createMicrophoneSource(config: MicrophoneConfig): AudioSource =
    JavaSoundMicrophoneSource(config)

actual fun audioPlatformName(): String = "JVM / javax.sound.sampled"

/**
 * Desktop capture on a dedicated thread.
 *
 * `TargetDataLine.read` blocks until the requested bytes are available, so the
 * loop naturally paces itself to the device and never spins. Analysis runs on
 * this thread — it is a few hundred microseconds of work per block — and only
 * the finished [com.audioviz.core.analysis.AudioFeatures] snapshot crosses over
 * to the UI thread.
 *
 * 16-bit signed little-endian PCM is requested rather than float, because it is
 * the format every Windows, macOS and Linux mixer supports without resampling.
 */
class JavaSoundMicrophoneSource(
    private val config: MicrophoneConfig,
) : AudioSource {

    @Volatile
    override var status: AudioSourceStatus = AudioSourceStatus.Idle
        private set

    @Volatile
    override var sampleRate: Int = 0
        private set

    private var line: TargetDataLine? = null
    private var thread: Thread? = null

    @Volatile
    private var running = false

    override fun start(sink: AudioFrameSink) {
        if (running) return
        status = AudioSourceStatus.Starting

        val rates = buildList {
            add(config.preferredSampleRate)
            addAll(config.fallbackSampleRates)
        }

        var opened: TargetDataLine? = null
        var chosenRate = 0
        var lastError: String? = null

        for (rate in rates) {
            val format = AudioFormat(rate.toFloat(), BITS_PER_SAMPLE, 1, true, false)
            val info = DataLine.Info(TargetDataLine::class.java, format)
            if (!AudioSystem.isLineSupported(info)) {
                lastError = "no line supports mono ${BITS_PER_SAMPLE}-bit at $rate Hz"
                continue
            }
            try {
                val candidate = AudioSystem.getLine(info) as TargetDataLine
                // Four blocks of headroom: enough that a scheduling hiccup on
                // the UI thread cannot cause an overrun, small enough that
                // latency stays imperceptible.
                candidate.open(format, config.blockSize * BYTES_PER_SAMPLE * 4)
                candidate.start()
                opened = candidate
                chosenRate = rate
                break
            } catch (e: LineUnavailableException) {
                lastError = "device busy or unavailable at $rate Hz: ${e.message}"
            } catch (e: SecurityException) {
                status = AudioSourceStatus(
                    AudioSourceState.PERMISSION_DENIED,
                    "microphone access was refused: ${e.message}",
                )
                return
            } catch (e: IllegalArgumentException) {
                lastError = "unsupported format at $rate Hz: ${e.message}"
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

        line = target
        sampleRate = chosenRate
        running = true
        status = AudioSourceStatus.Running

        thread = Thread({ captureLoop(target, chosenRate, sink) }, "audioviz-capture").apply {
            isDaemon = true
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    override fun stop() {
        running = false
        thread?.join(500)
        thread = null
        line?.let {
            it.stop()
            it.flush()
            it.close()
        }
        line = null
        sampleRate = 0
        if (!status.isFailed) status = AudioSourceStatus.Idle
    }

    private fun captureLoop(target: TargetDataLine, rate: Int, sink: AudioFrameSink) {
        val bytes = ByteArray(config.blockSize * BYTES_PER_SAMPLE)
        val samples = FloatArray(config.blockSize)
        try {
            while (running) {
                val read = target.read(bytes, 0, bytes.size)
                if (read <= 0) continue
                val count = read / BYTES_PER_SAMPLE
                var b = 0
                for (i in 0 until count) {
                    // Little-endian signed 16-bit -> -1..1
                    val low = bytes[b].toInt() and 0xFF
                    val high = bytes[b + 1].toInt()
                    samples[i] = ((high shl 8) or low) * INV_FULL_SCALE
                    b += 2
                }
                sink.onAudioFrame(samples, 0, count, rate)
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

    private companion object {
        const val BITS_PER_SAMPLE = 16
        const val BYTES_PER_SAMPLE = 2
        const val INV_FULL_SCALE = 1f / 32_768f
    }
}
