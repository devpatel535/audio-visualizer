package com.audioviz.core.analysis

import com.audioviz.core.dsp.NormalizationMode
import com.audioviz.core.dsp.WindowFunction

/**
 * Everything the audio analyzer can be tuned with.
 *
 * Defaults are chosen for close-range speech on a phone or laptop microphone.
 * See `docs/TUNING.md` for what to change and in which direction.
 */
data class AnalyzerConfig(
    // ---- Framing ---------------------------------------------------------
    /** FFT / analysis window size in samples. Power of two. */
    val fftSize: Int = 1024,

    /**
     * Samples between successive analysis frames. `fftSize / 2` gives 50%
     * overlap: ~11 ms of latency at 48 kHz, which is below the threshold where
     * speech and visual feel out of sync.
     */
    val hopSize: Int = 512,

    /** Window applied before the FFT. */
    val window: WindowFunction = WindowFunction.HANN,

    // ---- Input conditioning ---------------------------------------------
    /** Linear gain applied to incoming samples before anything else. */
    val inputGain: Float = 1f,

    /** High-pass pole for the DC blocker; closer to 1 = lower cutoff. */
    val dcBlockerPole: Float = 0.995f,

    // ---- Normalization ---------------------------------------------------
    val normalization: NormalizationMode = NormalizationMode.Adaptive,

    /**
     * Master sensitivity. Multiplies the normalized level *after* the noise
     * gate, so it makes the visual more reactive without also making it react
     * to room tone. `0.6` = reserved, `1.0` = default, `1.8` = eager.
     */
    val sensitivity: Float = 1f,

    /** Lowest the adaptive noise floor may settle, dBFS. */
    val absoluteFloorDb: Float = -75f,

    /** Highest the adaptive noise floor may drift, dBFS. Guards very noisy rooms. */
    val maximumFloorDb: Float = -28f,

    /** Minimum span between floor and loud reference, dB. */
    val minimumDynamicRangeDb: Float = 16f,

    /** Maximum span between floor and loud reference, dB. */
    val maximumDynamicRangeDb: Float = 48f,

    /** How fast the loud reference forgets a shout, dB per second. */
    val loudDecayDbPerSecond: Float = 3f,

    // ---- Noise gate ------------------------------------------------------
    /** dB above the noise floor at which the gate opens. Raise it in noisy rooms. */
    val gateOpenMarginDb: Float = 9f,

    /** dB above the noise floor at which the gate starts closing. Must be < open. */
    val gateCloseMarginDb: Float = 5f,

    /** How long the gate stays open after the level drops, seconds. */
    val gateHoldSeconds: Float = 0.35f,

    val gateAttackTau: Float = 0.035f,
    val gateReleaseTau: Float = 0.30f,

    // ---- Envelopes -------------------------------------------------------
    /** Fast envelope: follows syllables and consonants. */
    val fastAttackTau: Float = 0.008f,
    val fastReleaseTau: Float = 0.120f,

    /** Slow envelope: overall speech energy. */
    val slowAttackTau: Float = 0.120f,
    val slowReleaseTau: Float = 0.600f,

    /** Sustained envelope: session "mood", drives long-horizon colour drift. */
    val sustainedAttackTau: Float = 1.2f,
    val sustainedReleaseTau: Float = 3.0f,

    // ---- Transients ------------------------------------------------------
    /** Envelope applied to the detected transient so it decays instead of spiking. */
    val transientAttackTau: Float = 0.015f,
    val transientReleaseTau: Float = 0.130f,

    /** Gain on the `fast - slow` difference before it is mixed with spectral flux. */
    val transientDifferenceGain: Float = 1.4f,

    /**
     * Expander floor for the combined transient signal, in `0..1`.
     *
     * Continuous speech produces a steady trickle of small onsets. Without a
     * floor the transient signal never returns to zero between syllables and
     * `pulse` sits permanently half-open, which both looks like constant
     * throbbing and leaves no headroom for a real emphasis. Everything below
     * this fraction is discarded and the remainder is rescaled to `0..1`.
     */
    val transientThreshold: Float = 0.20f,

    /** Spectral-flux threshold multiplier. Higher = fewer, stronger onsets. */
    val onsetSensitivity: Float = 1.3f,

    // ---- Spectrum --------------------------------------------------------
    /** When false the FFT is skipped entirely; level-only mode. */
    val enableSpectrum: Boolean = true,

    /** Number of logarithmically spaced bands. */
    val bandCount: Int = 6,

    val minBandHz: Float = 55f,
    val maxBandHz: Float = 8_000f,

    val bandAttackTau: Float = 0.045f,
    val bandReleaseTau: Float = 0.22f,
) {
    init {
        require(fftSize >= 64 && fftSize and (fftSize - 1) == 0) {
            "fftSize must be a power of two >= 64"
        }
        require(hopSize in 1..fftSize) { "hopSize must be in 1..fftSize" }
        require(bandCount >= 1) { "bandCount must be >= 1" }
        require(gateCloseMarginDb < gateOpenMarginDb) {
            "gateCloseMarginDb must be below gateOpenMarginDb for hysteresis to work"
        }
    }

    companion object {
        /**
         * Cheapest useful configuration: no FFT, small window, and **no
         * overlap**. For low-end phones, or when the renderer does not use band
         * energies.
         *
         * The missing overlap matters as much as the missing FFT. An earlier
         * version of this preset kept a half-window hop, which doubled the
         * analysis rate and cancelled almost all of the saving — measurably so:
         * it cost 0.51% of a core against the full analyzer's 0.62%. Dropping
         * the overlap takes it to roughly a fifth of that, and 512 samples at
         * 48 kHz is still a 10.7 ms update, well inside the latency budget.
         */
        val LevelOnly: AnalyzerConfig = AnalyzerConfig(
            fftSize = 512,
            hopSize = 512,
            enableSpectrum = false,
        )

        /** Noisy environments: higher gate, slower floor, calmer transients. */
        val NoisyRoom: AnalyzerConfig = AnalyzerConfig(
            gateOpenMarginDb = 14f,
            gateCloseMarginDb = 9f,
            sensitivity = 0.85f,
            onsetSensitivity = 1.7f,
        )

        /** Quiet studio / headset mic: reacts to whispers. */
        val CloseMic: AnalyzerConfig = AnalyzerConfig(
            gateOpenMarginDb = 6f,
            gateCloseMarginDb = 3f,
            sensitivity = 1.25f,
        )

        /**
         * Deterministic mapping for tests and for calibrated hardware: no
         * adaptive floor tracking, so identical input always yields identical
         * output.
         */
        val Deterministic: AnalyzerConfig = AnalyzerConfig(
            normalization = NormalizationMode.Fixed(floorDb = -55f, ceilingDb = -12f),
        )
    }
}
