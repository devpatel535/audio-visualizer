package com.audioviz.core.capture

import com.audioviz.core.util.TWO_PI
import com.audioviz.core.util.dbToAmplitude
import kotlin.math.exp
import kotlin.math.sin

/**
 * Deterministic speech-like signal generator.
 *
 * Two jobs:
 *  1. The demo (and any host) has something to show when there is no microphone
 *     — a browser tab without permission, an emulator, a CI screenshot.
 *  2. Tests can drive the whole pipeline at an exact dBFS level and assert on
 *     what the animation controller produces.
 *
 * The signal is a syllable-rate amplitude envelope over a harmonic stack plus
 * shaped noise: not a convincing voice, but it has the two properties that
 * matter to the analyzer — onsets at speech rate and a speech-like spectral
 * tilt.
 */
class SyntheticAudioSource(
    override val sampleRate: Int = 48_000,
    private val blockSize: Int = 512,
    /** Target RMS of the generated speech in dBFS. */
    var levelDb: Float = -26f,
    /** Syllables per second. Normal speech sits around 4. */
    var syllableRate: Float = 4.2f,
    /** Fundamental of the harmonic stack, Hz. */
    var fundamentalHz: Float = 130f,
    /** Constant background hiss in dBFS; models room tone. */
    var noiseFloorDb: Float = -62f,
    /** Fraction of time the generator is speaking rather than pausing. */
    var speechDuty: Float = 0.72f,
    seed: Int = 12345,
) : AudioSource {

    private val buffer = FloatArray(blockSize)
    private var phase = 0.0
    private var envelopePhase = 0.0
    private var samplesEmitted = 0L
    private var rng = seed.toLong() and 0xFFFFFFFFL
    private var sink: AudioFrameSink? = null

    override var status: AudioSourceStatus = AudioSourceStatus.Idle
        private set

    override fun start(sink: AudioFrameSink) {
        this.sink = sink
        status = AudioSourceStatus.Running
    }

    override fun stop() {
        sink = null
        status = AudioSourceStatus.Idle
    }

    private var sampleDebt = 0f

    /**
     * Emits enough blocks to cover [elapsedSeconds] of audio.
     *
     * The debt is carried as a fraction so that, over time, exactly
     * `sampleRate` samples are produced per second whatever the frame rate is.
     */
    override fun pump(elapsedSeconds: Float) {
        if (!status.isRunning) return
        sampleDebt += elapsedSeconds.coerceIn(0f, MAX_CATCH_UP_SECONDS) * sampleRate
        while (sampleDebt >= blockSize) {
            sampleDebt -= blockSize
            emitBlock()
        }
    }

    /** Fills [buffer] with one block and hands it to the sink. */
    fun emitBlock() {
        val target = sink ?: return
        fill(buffer)
        target.onAudioFrame(buffer, 0, buffer.size, sampleRate)
    }

    /** Fills [out] with generated audio; exposed for tests that drive the analyzer directly. */
    fun fill(out: FloatArray) {
        val amplitude = dbToAmplitude(levelDb) * SPEECH_CREST
        val noise = dbToAmplitude(noiseFloorDb)
        val dt = 1.0 / sampleRate
        val envStep = syllableRate * dt

        for (i in out.indices) {
            envelopePhase += envStep
            if (envelopePhase >= 1.0) envelopePhase -= 1.0

            // Asymmetric syllable envelope: fast attack, slower decay, then a gap.
            val e = envelopePhase.toFloat()
            val env = when {
                e > speechDuty -> 0f
                e < 0.08f -> e / 0.08f
                else -> exp(-3.2f * (e - 0.08f) / speechDuty.coerceAtLeast(0.1f))
            }

            phase += fundamentalHz * dt
            if (phase >= 1.0) phase -= 1.0
            val p = phase.toFloat() * TWO_PI

            // Harmonic stack with a -6 dB/octave tilt, roughly voice-shaped.
            var voiced = 0f
            var harmonic = 1
            var weight = 1f
            while (harmonic <= 8) {
                voiced += sin(p * harmonic) * weight
                weight *= 0.55f
                harmonic++
            }
            voiced *= 0.45f

            // Fricative energy rides on the same envelope but is broadband.
            val fricative = nextNoise() * 0.35f * env

            out[i] = (voiced * env + fricative) * amplitude + nextNoise() * noise
        }
        samplesEmitted += out.size
    }

    /** Seconds of audio generated so far. */
    val generatedSeconds: Double get() = samplesEmitted.toDouble() / sampleRate

    private fun nextNoise(): Float {
        // xorshift32 — deterministic across platforms, no allocation.
        var x = rng
        x = x xor (x shl 13) and 0xFFFFFFFFL
        x = x xor (x shr 17)
        x = x xor (x shl 5) and 0xFFFFFFFFL
        rng = x
        return ((x.toDouble() / 0xFFFFFFFFL.toDouble()).toFloat() - 0.5f) * 2f
    }

    private companion object {
        /** A long stall produces one frame of catch-up, not a burst. */
        const val MAX_CATCH_UP_SECONDS = 0.25f

        /**
         * The generator is specified by target RMS but built from a peaky
         * syllable envelope, so its raw output sits well below the requested
         * level. This factor restores it; it is measured, not derived, and is
         * pinned by `SyntheticSourceCalibrationTest`.
         */
        const val SPEECH_CREST = 6.204f
    }
}

/** Constant-amplitude sine, for calibration and unit tests. */
class SineAudioSource(
    override val sampleRate: Int = 48_000,
    private val blockSize: Int = 512,
    var frequencyHz: Float = 440f,
    var levelDb: Float = -20f,
) : AudioSource {
    private val buffer = FloatArray(blockSize)
    private var phase = 0.0
    private var sink: AudioFrameSink? = null

    override var status: AudioSourceStatus = AudioSourceStatus.Idle
        private set

    override fun start(sink: AudioFrameSink) {
        this.sink = sink
        status = AudioSourceStatus.Running
    }

    override fun stop() {
        sink = null
        status = AudioSourceStatus.Idle
    }

    private var sampleDebt = 0f

    override fun pump(elapsedSeconds: Float) {
        val target = sink ?: return
        sampleDebt += elapsedSeconds.coerceIn(0f, 0.25f) * sampleRate
        while (sampleDebt >= buffer.size) {
            sampleDebt -= buffer.size
            fill(buffer)
            target.onAudioFrame(buffer, 0, buffer.size, sampleRate)
        }
    }

    fun fill(out: FloatArray) {
        // RMS of a sine is peak/sqrt(2); scale so measured RMS matches levelDb.
        val amp = dbToAmplitude(levelDb) * 1.41421356f
        val step = frequencyHz.toDouble() / sampleRate
        for (i in out.indices) {
            phase += step
            if (phase >= 1.0) phase -= 1.0
            out[i] = sin(phase.toFloat() * TWO_PI) * amp
        }
    }
}

/** Digital silence; used to verify the idle behaviour of the whole chain. */
class SilentAudioSource(
    override val sampleRate: Int = 48_000,
    blockSize: Int = 512,
    /** Optional dither/room tone level in dBFS. */
    var roomToneDb: Float = -90f,
    seed: Int = 7,
) : AudioSource {
    private val buffer = FloatArray(blockSize)
    private var rng = seed.toLong() and 0xFFFFFFFFL
    private var sink: AudioFrameSink? = null

    override var status: AudioSourceStatus = AudioSourceStatus.Idle
        private set

    override fun start(sink: AudioFrameSink) {
        this.sink = sink
        status = AudioSourceStatus.Running
    }

    override fun stop() {
        sink = null
        status = AudioSourceStatus.Idle
    }

    private var sampleDebt = 0f

    override fun pump(elapsedSeconds: Float) {
        val target = sink ?: return
        sampleDebt += elapsedSeconds.coerceIn(0f, 0.25f) * sampleRate
        while (sampleDebt >= buffer.size) {
            sampleDebt -= buffer.size
            fill(buffer)
            target.onAudioFrame(buffer, 0, buffer.size, sampleRate)
        }
    }

    fun fill(out: FloatArray) {
        val amp = dbToAmplitude(roomToneDb)
        for (i in out.indices) {
            var x = rng
            x = x xor (x shl 13) and 0xFFFFFFFFL
            x = x xor (x shr 17)
            x = x xor (x shl 5) and 0xFFFFFFFFL
            rng = x
            out[i] = (((x.toDouble() / 0xFFFFFFFFL.toDouble()).toFloat() - 0.5f) * 2f) * amp
        }
    }
}
