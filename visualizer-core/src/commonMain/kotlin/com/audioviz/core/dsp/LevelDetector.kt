package com.audioviz.core.dsp

import com.audioviz.core.util.amplitudeToDb
import com.audioviz.core.util.clamp01
import com.audioviz.core.util.sanitize
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Removes DC offset and sub-audible rumble with a one-pole high-pass.
 *
 *     y[n] = x[n] - x[n-1] + R * y[n-1]
 *
 * Many phone and laptop microphones present a small DC bias, and handling noise
 * puts a lot of energy below 20 Hz. Both inflate RMS without being audible, so
 * the visualizer would react to the user putting the phone down. Cutting them
 * first makes every downstream measurement mean what it claims to mean.
 */
class DcBlocker(private val pole: Float = 0.995f) {
    private var lastIn = 0f
    private var lastOut = 0f

    fun reset() {
        lastIn = 0f
        lastOut = 0f
    }

    /** Filters [length] samples of [input] in place starting at [offset]. */
    fun processInPlace(input: FloatArray, offset: Int, length: Int) {
        var xPrev = lastIn
        var yPrev = lastOut
        for (i in offset until offset + length) {
            val x = sanitize(input[i])
            val y = x - xPrev + pole * yPrev
            input[i] = y
            xPrev = x
            yPrev = y
        }
        lastIn = xPrev
        lastOut = yPrev
    }
}

/** Block-level loudness measurements, in linear amplitude and dBFS. */
class BlockLevel {
    var rms: Float = 0f
        internal set
    var peak: Float = 0f
        internal set
    var rmsDb: Float = -100f
        internal set
    var peakDb: Float = -100f
        internal set
}

/** Computes RMS and peak of a block without allocating. */
fun measureBlock(input: FloatArray, offset: Int, length: Int, into: BlockLevel): BlockLevel {
    var sum = 0.0
    var peak = 0f
    for (i in offset until offset + length) {
        val v = sanitize(input[i])
        sum += v.toDouble() * v
        val a = abs(v)
        if (a > peak) peak = a
    }
    val rms = if (length > 0) sqrt(sum / length).toFloat() else 0f
    into.rms = rms
    into.peak = peak
    into.rmsDb = amplitudeToDb(rms)
    into.peakDb = amplitudeToDb(peak)
    return into
}

/** How incoming loudness is mapped onto the normalized `0..1` range. */
sealed interface NormalizationMode {
    /**
     * Tracks the room's noise floor and a decaying loud reference, and maps the
     * span between them onto `0..1`. A quiet laptop mic and a hot USB condenser
     * end up driving the visual identically after a few seconds of speech.
     */
    data object Adaptive : NormalizationMode

    /**
     * Fixed dBFS window. Deterministic and reproducible — the right choice for
     * tests, for calibrated hardware, and for A/B comparing tuning changes.
     */
    data class Fixed(val floorDb: Float = -55f, val ceilingDb: Float = -12f) : NormalizationMode
}

/**
 * Adaptive loudness normalizer.
 *
 * Keeps two slowly-moving references in the dB domain:
 *
 *  - **noise floor** — falls quickly toward any new quieter level and rises only
 *    at [floorRiseDbPerSecond], so it settles on the room tone rather than on
 *    the gaps between words.
 *  - **loud reference** — jumps instantly to any new peak and decays at
 *    [loudDecayDbPerSecond], so it represents "how loud this speaker has been
 *    recently".
 *
 * `level = (db - gateOpenDb) / (loudRef - gateOpenDb)`, clamped to `0..1`.
 *
 * Working in dB rather than linear amplitude is what makes the response feel
 * natural: perceived loudness is roughly logarithmic, so a linear mapping
 * spends almost its whole range on the loudest 10% of speech.
 */
class LevelNormalizer(
    var mode: NormalizationMode = NormalizationMode.Adaptive,
    var floorRiseDbPerSecond: Float = 0.6f,
    var floorFallTau: Float = 0.8f,
    var loudDecayDbPerSecond: Float = 3.0f,
    var absoluteFloorDb: Float = -75f,
    var maximumFloorDb: Float = -28f,
    var minimumDynamicRangeDb: Float = 16f,
    var maximumDynamicRangeDb: Float = 48f,
) {
    var noiseFloorDb: Float = -60f
        private set
    var loudReferenceDb: Float = -24f
        private set

    fun reset(floorDb: Float = -60f, loudDb: Float = -24f) {
        noiseFloorDb = floorDb
        loudReferenceDb = loudDb
    }

    /** Feeds one block's loudness and returns the normalized `0..1` level. */
    fun update(db: Float, dt: Float, gateOpenMarginDb: Float): Float {
        val value = sanitize(db, absoluteFloorDb)

        when (val m = mode) {
            is NormalizationMode.Fixed -> {
                noiseFloorDb = m.floorDb
                loudReferenceDb = m.ceilingDb
            }

            NormalizationMode.Adaptive -> {
                // Noise floor: fall fast, rise slowly.
                noiseFloorDb = if (value < noiseFloorDb) {
                    noiseFloorDb + (value - noiseFloorDb) * Smoothing.alpha(dt, floorFallTau)
                } else {
                    minOf(noiseFloorDb + floorRiseDbPerSecond * dt, value)
                }
                noiseFloorDb = noiseFloorDb.coerceIn(absoluteFloorDb, maximumFloorDb)

                // Loud reference: instant attack, slow decay.
                loudReferenceDb = if (value > loudReferenceDb) {
                    value
                } else {
                    loudReferenceDb - loudDecayDbPerSecond * dt
                }
                val lowest = noiseFloorDb + minimumDynamicRangeDb
                val highest = noiseFloorDb + maximumDynamicRangeDb
                loudReferenceDb = loudReferenceDb.coerceIn(lowest, minOf(highest, 0f))
            }
        }

        val openDb = noiseFloorDb + gateOpenMarginDb
        val span = (loudReferenceDb - openDb).coerceAtLeast(6f)
        return clamp01((value - openDb) / span)
    }
}

/**
 * Noise gate with hysteresis, hold and a smoothed envelope.
 *
 * Hysteresis ([openMarginDb] > [closeMarginDb]) stops the gate chattering when
 * the level hovers at the threshold; [holdSeconds] keeps it open through the
 * short gaps between words; the [AttackRelease] on the output means the gate
 * fades rather than switches, which is the difference between a visual that
 * "settles" and one that blinks.
 */
class NoiseGate(
    var openMarginDb: Float = 9f,
    var closeMarginDb: Float = 5f,
    var holdSeconds: Float = 0.35f,
    attackTau: Float = 0.035f,
    releaseTau: Float = 0.30f,
) {
    private val envelope = AttackRelease(attackTau, releaseTau, 0f)
    private var holdRemaining = 0f
    private var latchedOpen = false

    var attackTau: Float
        get() = envelope.attackTau
        set(v) { envelope.attackTau = v }

    var releaseTau: Float
        get() = envelope.releaseTau
        set(v) { envelope.releaseTau = v }

    /** Smoothed gate value in `0..1`. */
    val value: Float get() = envelope.value

    /** True while the gate is latched open (before smoothing). */
    val isOpen: Boolean get() = latchedOpen

    fun reset() {
        envelope.reset(0f)
        holdRemaining = 0f
        latchedOpen = false
    }

    fun update(db: Float, noiseFloorDb: Float, dt: Float): Float {
        val openThreshold = noiseFloorDb + openMarginDb
        val closeThreshold = noiseFloorDb + closeMarginDb

        if (db >= openThreshold) {
            latchedOpen = true
            holdRemaining = holdSeconds
        } else if (db < closeThreshold) {
            holdRemaining -= dt
            if (holdRemaining <= 0f) latchedOpen = false
        } else {
            // Between the two thresholds: keep the current state (hysteresis).
            holdRemaining -= dt
            if (holdRemaining <= 0f && !latchedOpen) latchedOpen = false
        }

        return envelope.update(if (latchedOpen) 1f else 0f, dt)
    }
}
