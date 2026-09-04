package com.audioviz.core.dsp

import com.audioviz.core.util.amplitudeToDb
import com.audioviz.core.util.clamp01
import kotlin.math.ln
import kotlin.math.pow

/**
 * Splits a magnitude spectrum into logarithmically spaced bands and normalizes
 * each one independently.
 *
 * Per-band normalization matters more than it looks: in speech the 4-8 kHz band
 * sits 25-30 dB below the 100-300 Hz band, so a shared normalization would leave
 * the high bands permanently near zero and the visual would never show
 * sibilance. Giving each band its own floor/reference tracker makes every band
 * expressive over its own natural range.
 *
 * Bin ranges are computed once in the constructor; [update] allocates nothing.
 */
class BandAnalyzer(
    val bandCount: Int,
    binCount: Int,
    sampleRate: Int,
    minHz: Float = 55f,
    maxHz: Float = 8_000f,
    attackTau: Float = 0.045f,
    releaseTau: Float = 0.22f,
) {
    /** Smoothed, normalized band energies in `0..1`. Reused across calls. */
    val bands: FloatArray = FloatArray(bandCount)

    /** Lower bin index (inclusive) of each band. */
    private val binStart = IntArray(bandCount)

    /** Upper bin index (exclusive) of each band. */
    private val binEnd = IntArray(bandCount)

    private val normalizers = Array(bandCount) {
        LevelNormalizer(
            floorRiseDbPerSecond = 1.0f,
            floorFallTau = 1.2f,
            loudDecayDbPerSecond = 4.5f,
            minimumDynamicRangeDb = 12f,
        )
    }
    private val followers = Array(bandCount) { AttackRelease(attackTau, releaseTau) }

    /** Centre frequency of each FFT bin, precomputed for the spectral centroid. */
    private val binHz = FloatArray(binCount) { it * sampleRate * 0.5f / (binCount - 1).coerceAtLeast(1) }

    private val logMin = ln(minHz.coerceAtLeast(1f))
    private val logMax = ln(maxHz.coerceAtLeast(minHz * 2f))

    /** Normalized spectral centroid in `0..1`; a proxy for perceived brightness. */
    var brightness: Float = 0f
        private set

    private val brightnessFollower = AttackRelease(0.12f, 0.45f)

    init {
        val nyquist = sampleRate * 0.5f
        val top = minOf(maxHz, nyquist * 0.98f)
        for (b in 0 until bandCount) {
            val lo = edgeHz(b, bandCount, minHz, top)
            val hi = edgeHz(b + 1, bandCount, minHz, top)
            var startBin = hzToBin(lo, sampleRate, binCount)
            var endBin = hzToBin(hi, sampleRate, binCount)
            if (endBin <= startBin) endBin = startBin + 1
            startBin = startBin.coerceIn(1, binCount - 1)
            endBin = endBin.coerceIn(startBin + 1, binCount)
            binStart[b] = startBin
            binEnd[b] = endBin
        }
    }

    fun reset() {
        bands.fill(0f)
        normalizers.forEach { it.reset() }
        followers.forEach { it.reset() }
        brightnessFollower.reset()
        brightness = 0f
    }

    /** Consumes a magnitude spectrum and refreshes [bands] and [brightness]. */
    fun update(magnitudes: FloatArray, dt: Float) {
        for (b in 0 until bandCount) {
            var sum = 0f
            val start = binStart[b]
            val end = binEnd[b]
            for (i in start until end) sum += magnitudes[i]
            val mean = sum / (end - start)
            val db = amplitudeToDb(mean)
            val normalized = normalizers[b].update(db, dt, gateOpenMarginDb = 4f)
            bands[b] = followers[b].update(normalized, dt)
        }

        // Spectral centroid over the analysed span, mapped logarithmically.
        var weighted = 0f
        var total = 0f
        val lo = binStart[0]
        val hi = binEnd[bandCount - 1]
        for (i in lo until hi) {
            val m = magnitudes[i]
            weighted += m * binHz[i]
            total += m
        }
        val target = if (total > 1e-7f) {
            val centroidHz = (weighted / total).coerceAtLeast(1f)
            clamp01((ln(centroidHz) - logMin) / (logMax - logMin))
        } else {
            0f
        }
        brightness = brightnessFollower.update(target, dt)
    }

    private companion object {
        fun edgeHz(index: Int, count: Int, minHz: Float, maxHz: Float): Float {
            val t = index.toFloat() / count
            return minHz * (maxHz / minHz).pow(t)
        }

        fun hzToBin(hz: Float, sampleRate: Int, binCount: Int): Int {
            val nyquist = sampleRate * 0.5f
            return ((hz / nyquist) * (binCount - 1)).toInt().coerceIn(0, binCount - 1)
        }
    }
}

/**
 * Spectral-flux onset detector with an adaptive threshold.
 *
 * Flux is the sum of *positive* frame-to-frame changes in the magnitude
 * spectrum: energy appearing where there was none. That is exactly what a plosive
 * or a consonant looks like, and unlike a plain amplitude derivative it fires on
 * timbral changes at constant loudness too.
 *
 * The raw flux is divided by the frame's total magnitude, so the detector
 * responds to *change* rather than to volume; a shouted vowel held steady
 * produces almost no onset, which is what keeps the visual from pulsing
 * continuously during loud sustained speech.
 */
class OnsetDetector(
    binCount: Int,
    private val lowBin: Int = 1,
    highBin: Int = binCount,
    var sensitivity: Float = 1.3f,
) {
    private val previous = FloatArray(binCount)
    private val highBinClamped = highBin.coerceIn(lowBin + 1, binCount)
    private val mean = OnePole(0.55f)
    private val deviation = OnePole(0.55f)
    private var primed = false

    /** Raw relative flux of the most recent frame, before thresholding. */
    var flux: Float = 0f
        private set

    fun reset() {
        previous.fill(0f)
        mean.reset()
        deviation.reset()
        primed = false
        flux = 0f
    }

    /** Returns onset strength in `0..1` for the supplied spectrum. */
    fun update(magnitudes: FloatArray, dt: Float): Float {
        var positiveChange = 0f
        var total = 0f
        for (i in lowBin until highBinClamped) {
            val m = magnitudes[i]
            val d = m - previous[i]
            if (d > 0f) positiveChange += d
            total += m
            previous[i] = m
        }
        if (!primed) {
            primed = true
            return 0f
        }

        val relative = if (total > 1e-7f) positiveChange / total else 0f
        flux = relative

        val m = mean.update(relative, dt)
        val d = deviation.update(kotlin.math.abs(relative - m), dt)

        val threshold = m + sensitivity * d
        val span = (m + 2f * d).coerceAtLeast(0.02f)
        return clamp01((relative - threshold) / span)
    }
}
