package com.audioviz.core.analysis

import com.audioviz.core.capture.AudioFrameSink
import com.audioviz.core.dsp.AttackRelease
import com.audioviz.core.dsp.BandAnalyzer
import com.audioviz.core.dsp.BlockLevel
import com.audioviz.core.dsp.DcBlocker
import com.audioviz.core.dsp.LevelNormalizer
import com.audioviz.core.dsp.NoiseGate
import com.audioviz.core.dsp.OnsetDetector
import com.audioviz.core.dsp.RealFft
import com.audioviz.core.dsp.measureBlock
import com.audioviz.core.util.clamp01
import kotlin.concurrent.Volatile

/**
 * Turns raw PCM into [AudioFeatures].
 *
 * ### Where this runs
 * [onAudioFrame] is called from whatever thread the platform capture uses — a
 * dedicated recording thread on Android and the JVM, the animation frame
 * callback in a browser. It publishes an immutable snapshot to [features],
 * which the render thread reads. That is the entire synchronisation contract:
 * one writer, many readers, no locks, no shared mutable state.
 *
 * ### Framing
 * Device buffer sizes vary wildly (Android hands out anything from 240 to 4096
 * samples). The analyzer therefore re-frames the input into a fixed sliding
 * window of [AnalyzerConfig.fftSize] advanced by [AnalyzerConfig.hopSize], so
 * every internal time constant is expressed against a stable frame rate no
 * matter what the device does.
 *
 * ### Cost
 * At 48 kHz with a 1024-point window and a 512 hop, this runs ~94 times a
 * second: one 512-point complex FFT, one pass over 513 bins for the bands, one
 * for the flux. Under 1% of a phone core.
 */
class AudioAnalyzer(
    config: AnalyzerConfig = AnalyzerConfig(),
) : AudioFrameSink {

    var config: AnalyzerConfig = config
        set(value) {
            val needsRebuild = value.fftSize != field.fftSize ||
                value.hopSize != field.hopSize ||
                value.bandCount != field.bandCount ||
                value.enableSpectrum != field.enableSpectrum ||
                value.window != field.window ||
                value.minBandHz != field.minBandHz ||
                value.maxBandHz != field.maxBandHz
            field = value
            applyTunables()
            if (needsRebuild) rebuild(activeSampleRate)
        }

    /** Latest published snapshot. Read from any thread. */
    @Volatile
    var features: AudioFeatures = AudioFeatures.silent(config.bandCount)
        private set

    // ---- Fixed-size state, rebuilt only when framing or rate changes --------
    private var window = FloatArray(config.fftSize)
    private var filled = 0
    private var activeSampleRate = 0
    private var fft: RealFft? = null
    private var magnitudes = FloatArray(0)
    private var bandAnalyzer: BandAnalyzer? = null
    private var onsetDetector: OnsetDetector? = null

    // ---- Per-frame state ---------------------------------------------------
    private val dcBlocker = DcBlocker(config.dcBlockerPole)
    private val blockLevel = BlockLevel()
    private val normalizer = LevelNormalizer()
    private val gate = NoiseGate()
    private val fastEnvelope = AttackRelease(config.fastAttackTau, config.fastReleaseTau)
    private val slowEnvelope = AttackRelease(config.slowAttackTau, config.slowReleaseTau)
    private val sustainedEnvelope = AttackRelease(config.sustainedAttackTau, config.sustainedReleaseTau)
    private val transientEnvelope = AttackRelease(config.transientAttackTau, config.transientReleaseTau)

    private var sequence = 0L
    private var samplesProcessed = 0L
    private var sawFirstFrame = false

    init {
        applyTunables()
    }

    /** Clears every follower and estimate. Call when the input device changes. */
    fun reset() {
        window.fill(0f)
        filled = 0
        dcBlocker.reset()
        normalizer.reset()
        gate.reset()
        fastEnvelope.reset()
        slowEnvelope.reset()
        sustainedEnvelope.reset()
        transientEnvelope.reset()
        bandAnalyzer?.reset()
        onsetDetector?.reset()
        sequence = 0L
        samplesProcessed = 0L
        sawFirstFrame = false
        features = AudioFeatures.silent(config.bandCount)
    }

    override fun onAudioFrame(samples: FloatArray, offset: Int, length: Int, sampleRate: Int) {
        if (length <= 0 || sampleRate <= 0) return
        if (sampleRate != activeSampleRate) rebuild(sampleRate)

        val cfg = config
        val gain = cfg.inputGain
        val size = cfg.fftSize
        val hop = cfg.hopSize

        var src = offset
        var remaining = length
        while (remaining > 0) {
            val n = minOf(size - filled, remaining)
            for (i in 0 until n) {
                window[filled + i] = samples[src + i] * gain
            }
            dcBlocker.processInPlace(window, filled, n)
            filled += n
            src += n
            remaining -= n
            samplesProcessed += n

            if (filled == size) {
                analyzeWindow()
                // Slide the window forward by one hop; `copyInto` handles the overlap.
                window.copyInto(window, 0, hop, size)
                filled = size - hop
            }
        }
    }

    private fun analyzeWindow() {
        val cfg = config
        val rate = activeSampleRate
        val dt = if (sawFirstFrame) {
            cfg.hopSize.toFloat() / rate
        } else {
            sawFirstFrame = true
            cfg.fftSize.toFloat() / rate
        }

        measureBlock(window, 0, cfg.fftSize, blockLevel)
        val db = blockLevel.rmsDb

        val rawLevel = normalizer.update(db, dt, cfg.gateOpenMarginDb)
        val gateValue = gate.update(db, normalizer.noiseFloorDb, dt)
        val level = clamp01(rawLevel * gateValue * cfg.sensitivity)

        val fast = fastEnvelope.update(level, dt)
        val slow = slowEnvelope.update(level, dt)
        val sustained = sustainedEnvelope.update(level, dt)

        var brightness = 0f
        var onset = 0f
        val bands: FloatArray
        val analyzer = bandAnalyzer
        val fftInstance = fft
        if (cfg.enableSpectrum && analyzer != null && fftInstance != null) {
            fftInstance.magnitudes(window, 0, magnitudes)
            analyzer.update(magnitudes, dt)
            brightness = analyzer.brightness
            onset = onsetDetector?.update(magnitudes, dt) ?: 0f
            bands = analyzer.bands.copyOf()
        } else {
            bands = EMPTY_BANDS
        }

        // Two independent views of "something just happened", combined by max:
        // the envelope difference catches loudness jumps, spectral flux catches
        // timbral ones (a consonant at constant volume).
        val envelopeDifference = clamp01((fast - slow) * cfg.transientDifferenceGain)
        val combined = maxOf(envelopeDifference, onset)
        // Expand above the threshold so the signal genuinely returns to zero
        // between syllables instead of resting half-open.
        val floor = cfg.transientThreshold.coerceIn(0f, 0.9f)
        val expanded = clamp01((combined - floor) / (1f - floor))
        val transientTarget = expanded * gateValue
        val transient = transientEnvelope.update(transientTarget, dt)

        sequence++
        features = AudioFeatures(
            sequence = sequence,
            timeSeconds = samplesProcessed.toDouble() / rate,
            sampleRate = rate,
            level = level,
            rawLevel = rawLevel,
            fast = fast,
            slow = slow,
            sustained = sustained,
            transient = transient,
            gate = gateValue,
            loudnessDb = db,
            peakDb = blockLevel.peakDb,
            noiseFloorDb = normalizer.noiseFloorDb,
            loudReferenceDb = normalizer.loudReferenceDb,
            brightness = brightness,
            bands = bands,
        )
    }

    /** Pushes config values that do not require reallocating anything. */
    private fun applyTunables() {
        val cfg = config
        normalizer.mode = cfg.normalization
        normalizer.absoluteFloorDb = cfg.absoluteFloorDb
        normalizer.maximumFloorDb = cfg.maximumFloorDb
        normalizer.minimumDynamicRangeDb = cfg.minimumDynamicRangeDb
        normalizer.maximumDynamicRangeDb = cfg.maximumDynamicRangeDb
        normalizer.loudDecayDbPerSecond = cfg.loudDecayDbPerSecond

        gate.openMarginDb = cfg.gateOpenMarginDb
        gate.closeMarginDb = cfg.gateCloseMarginDb
        gate.holdSeconds = cfg.gateHoldSeconds
        gate.attackTau = cfg.gateAttackTau
        gate.releaseTau = cfg.gateReleaseTau

        fastEnvelope.attackTau = cfg.fastAttackTau
        fastEnvelope.releaseTau = cfg.fastReleaseTau
        slowEnvelope.attackTau = cfg.slowAttackTau
        slowEnvelope.releaseTau = cfg.slowReleaseTau
        sustainedEnvelope.attackTau = cfg.sustainedAttackTau
        sustainedEnvelope.releaseTau = cfg.sustainedReleaseTau
        transientEnvelope.attackTau = cfg.transientAttackTau
        transientEnvelope.releaseTau = cfg.transientReleaseTau

        onsetDetector?.sensitivity = cfg.onsetSensitivity
    }

    /** (Re)allocates the FFT and spectrum helpers for a new sample rate or framing. */
    private fun rebuild(sampleRate: Int) {
        activeSampleRate = sampleRate
        val cfg = config

        if (window.size != cfg.fftSize) window = FloatArray(cfg.fftSize)
        window.fill(0f)
        filled = 0
        dcBlocker.reset()
        sawFirstFrame = false

        if (cfg.enableSpectrum && sampleRate > 0) {
            val transform = RealFft(cfg.fftSize, cfg.window)
            fft = transform
            magnitudes = FloatArray(transform.binCount)
            bandAnalyzer = BandAnalyzer(
                bandCount = cfg.bandCount,
                binCount = transform.binCount,
                sampleRate = sampleRate,
                minHz = cfg.minBandHz,
                maxHz = cfg.maxBandHz,
                attackTau = cfg.bandAttackTau,
                releaseTau = cfg.bandReleaseTau,
            )
            onsetDetector = OnsetDetector(
                binCount = transform.binCount,
                sensitivity = cfg.onsetSensitivity,
            )
        } else {
            fft = null
            magnitudes = FloatArray(0)
            bandAnalyzer = null
            onsetDetector = null
        }
    }

    private companion object {
        val EMPTY_BANDS = FloatArray(0)
    }
}
