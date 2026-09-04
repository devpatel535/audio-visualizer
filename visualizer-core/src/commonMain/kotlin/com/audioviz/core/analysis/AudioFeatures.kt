package com.audioviz.core.analysis

/**
 * An immutable snapshot of everything the analyzer derived from one analysis
 * frame.
 *
 * Snapshots are published from the audio thread and read from the render
 * thread, so they are deliberately immutable: the reader can never observe a
 * half-updated frame, and no lock is needed on either side. One small object
 * per analysis frame (~86/s at 48 kHz with a 512-sample hop) is a rounding
 * error next to the cost of a single rendered frame.
 *
 * All `0..1` fields are already normalized, gated and smoothed; a renderer or
 * controller can use them directly without knowing anything about microphones.
 */
class AudioFeatures(
    /** Monotonic counter; lets a consumer detect that a new frame arrived. */
    val sequence: Long,

    /** Audio-clock timestamp of the end of this frame, seconds since start. */
    val timeSeconds: Double,

    /** Sample rate the frame was analysed at. */
    val sampleRate: Int,

    /** Gated, sensitivity-scaled loudness in `0..1`. The main drive signal. */
    val level: Float,

    /** Normalized loudness *before* the noise gate, `0..1`. Useful for meters. */
    val rawLevel: Float,

    /** Fast envelope of [level]: syllables and consonants. */
    val fast: Float,

    /** Slow envelope of [level]: sustained speech energy. */
    val slow: Float,

    /** Very slow envelope of [level]: session-scale "mood". */
    val sustained: Float,

    /** Transient / onset strength in `0..1`, already enveloped. */
    val transient: Float,

    /** Smoothed noise-gate value in `0..1`. */
    val gate: Float,

    /** Block RMS in dBFS. */
    val loudnessDb: Float,

    /** Peak sample level of the frame in dBFS. */
    val peakDb: Float,

    /** Current estimate of the room's noise floor, dBFS. */
    val noiseFloorDb: Float,

    /** Current loud reference used for normalization, dBFS. */
    val loudReferenceDb: Float,

    /** Normalized spectral centroid in `0..1`; higher = brighter / more sibilant. */
    val brightness: Float,

    /**
     * Per-band normalized energies, low frequency first, each in `0..1`.
     * Treat as read-only: the array is shared with every reader of this snapshot.
     */
    val bands: FloatArray,
) {
    /** True when the gate is closed and nothing meaningful is arriving. */
    val isSilent: Boolean get() = gate < 0.02f && level < 0.02f

    /** Band energy by index, or 0 when the analyzer runs without a spectrum. */
    fun band(index: Int): Float = if (index in bands.indices) bands[index] else 0f

    /** Mean of the lowest third of the bands. */
    val lowEnergy: Float get() = meanOfRange(0f, 1f / 3f)

    /** Mean of the middle third of the bands. */
    val midEnergy: Float get() = meanOfRange(1f / 3f, 2f / 3f)

    /** Mean of the top third of the bands. */
    val highEnergy: Float get() = meanOfRange(2f / 3f, 1f)

    private fun meanOfRange(from: Float, to: Float): Float {
        if (bands.isEmpty()) return 0f
        val start = (from * bands.size).toInt().coerceIn(0, bands.size - 1)
        val end = (to * bands.size).toInt().coerceIn(start + 1, bands.size)
        var sum = 0f
        for (i in start until end) sum += bands[i]
        return sum / (end - start)
    }

    companion object {
        /** All-zero snapshot used before the first frame arrives. */
        fun silent(bandCount: Int = 0): AudioFeatures = AudioFeatures(
            sequence = 0L,
            timeSeconds = 0.0,
            sampleRate = 0,
            level = 0f,
            rawLevel = 0f,
            fast = 0f,
            slow = 0f,
            sustained = 0f,
            transient = 0f,
            gate = 0f,
            loudnessDb = -100f,
            peakDb = -100f,
            noiseFloorDb = -60f,
            loudReferenceDb = -24f,
            brightness = 0f,
            bands = FloatArray(bandCount),
        )
    }
}
