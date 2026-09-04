package com.audioviz.core.anim

/**
 * Every constant in the audio-to-visual mapping, in one place.
 *
 * The grouping mirrors the four ideas the mapping is built on:
 *
 *  1. **Different sources.** `intensity`, `pulse`, `energy`, band energies and
 *     spectral brightness are separate signals with different time scales.
 *  2. **Different smoothers.** Some parameters are spring-driven (mass and
 *     overshoot), some use attack/release envelopes (snap then settle), some are
 *     slew-limited (physically incapable of flashing).
 *  3. **Different response curves.** Gamma, smootherstep and soft-knee shape how
 *     each parameter reacts across the input range.
 *  4. **Different strengths.** Scale is deliberately the weakest response in the
 *     system; deformation, turbulence and glow carry the reaction.
 *
 * Everything is a plain `Float`, so a settings screen or a remote config can
 * drive it directly.
 */
data class AnimationConfig(

    // ---------------------------------------------------------------- timing
    /** Frames slower than this are treated as this long, so a stall cannot jump the visual. */
    val maxDeltaSeconds: Float = 1f / 15f,

    /** Frames faster than this are clamped, guarding against 0 and denormals. */
    val minDeltaSeconds: Float = 1f / 480f,

    /** How much louder audio speeds up the animation clock and the noise field. */
    val timeScaleFromIntensity: Float = 0.85f,

    /** Extra clock speed-up on a transient. */
    val timeScaleFromPulse: Float = 0.55f,

    // ------------------------------------------------------------- responses
    /** Gamma on the slow envelope before it becomes `intensity`. <1 expands quiet speech. */
    val intensityGamma: Float = 0.85f,

    /** Soft-knee point for `intensity`; above it, shouting stops adding much. */
    val intensityKnee: Float = 0.72f,

    /** Natural frequency of the intensity spring, Hz. Higher = snappier. */
    val intensitySpringHz: Float = 2.1f,

    /** Damping ratio of the intensity spring. <1 overshoots slightly. */
    val intensitySpringDamping: Float = 0.88f,

    /** Gamma on the transient signal before it becomes `pulse`. */
    val pulseGamma: Float = 0.8f,

    val pulseAttackTau: Float = 0.020f,
    val pulseReleaseTau: Float = 0.200f,

    /** Time constant of the long-horizon `energy` follower, seconds. */
    val energyTau: Float = 1.6f,

    /** Activity level at which the visual is considered fully awake (idle -> 0). */
    val idleFadeEnd: Float = 0.28f,

    // ----------------------------------------------------------------- scale
    /**
     * Scale contributions. Kept small on purpose: "louder = bigger" is the
     * cheapest-looking possible mapping, so it is present only as a hint.
     * The sum of these three is the maximum growth (here about 11%).
     */
    val scaleFromIntensity: Float = 0.055f,
    val scaleFromPulse: Float = 0.045f,
    val scaleFromIdleBreath: Float = 0.012f,

    val scaleSpringHz: Float = 2.6f,
    val scaleSpringDamping: Float = 0.62f,

    /**
     * Velocity kick injected into the scale spring on a transient's rising edge.
     * This, not the scale target, is what makes an emphasised syllable read as a
     * physical recoil rather than as a step change in size.
     */
    val scaleImpulseGain: Float = 1.4f,

    // ----------------------------------------------------------- deformation
    /** Deformation present in silence: the object is alive, not frozen. */
    val idleDeformation: Float = 0.16f,
    val deformationFromIntensity: Float = 0.46f,
    val deformationFromPulse: Float = 0.30f,
    val deformationFromLowBand: Float = 0.14f,
    val deformationSpringHz: Float = 1.55f,
    val deformationSpringDamping: Float = 0.72f,

    // ------------------------------------------------------------ turbulence
    val idleTurbulence: Float = 0.12f,
    val turbulenceFromHighBand: Float = 0.40f,
    val turbulenceFromPulse: Float = 0.34f,
    val turbulenceFromBrightness: Float = 0.20f,
    val turbulenceAttackTau: Float = 0.070f,
    val turbulenceReleaseTau: Float = 0.300f,

    // ---------------------------------------------------------------- detail
    val idleDetail: Float = 0.20f,
    val detailFromMidBand: Float = 0.45f,
    val detailFromIntensity: Float = 0.30f,
    val detailTau: Float = 0.30f,

    // ------------------------------------------------------------------ wave
    val idleWaveAmplitude: Float = 0.10f,
    val waveFromLevel: Float = 0.55f,
    val waveFromPulse: Float = 0.35f,
    val waveTau: Float = 0.09f,

    // -------------------------------------------------------------- emission
    val idleEmission: Float = 0.06f,
    val emissionFromIntensity: Float = 0.45f,
    val emissionFromPulse: Float = 0.70f,
    val emissionAttackTau: Float = 0.03f,
    val emissionReleaseTau: Float = 0.35f,

    // -------------------------------------------------------------- rotation
    /** Base angular velocity in radians/second; the visual never stands still. */
    val baseRotationSpeed: Float = 0.055f,
    val rotationFromEnergy: Float = 1.4f,
    val rotationFromIntensity: Float = 0.9f,

    /** Amplitude of the slow noise wobble added to angular velocity, rad/s. */
    val rotationWobble: Float = 0.06f,

    /** Angular impulse per unit of transient rise, rad/s. */
    val rotationImpulse: Float = 0.5f,

    /** Time constant with which a rotation impulse decays, seconds. */
    val rotationImpulseTau: Float = 0.55f,

    // ----------------------------------------------------------------- drift
    val idleDrift: Float = 0.012f,
    val driftFromIntensity: Float = 0.022f,
    val driftTau: Float = 0.45f,

    // ------------------------------------------------------------------ glow
    val idleGlow: Float = 0.10f,
    val glowFromIntensity: Float = 0.50f,
    val glowFromPulse: Float = 0.40f,
    val glowFromHighBand: Float = 0.18f,
    val glowAttackTau: Float = 0.045f,
    val glowReleaseTau: Float = 0.240f,

    /**
     * Hard limits on how fast glow may change, per second. At 4.5/s a full
     * 0-to-1 swing still takes 13 frames at 60 fps, so an emphasised word flares
     * convincingly while a strobe remains impossible to express.
     */
    val glowRisePerSecond: Float = 4.5f,
    val glowFallPerSecond: Float = 1.8f,

    // --------------------------------------------------------------- opacity
    val baseOpacity: Float = 0.86f,
    val opacityFromIntensity: Float = 0.14f,
    val opacityTau: Float = 0.35f,

    // ------------------------------------------------------------------ blur
    val baseBlur: Float = 0.06f,
    val blurFromIdle: Float = 0.05f,
    val blurFromPulse: Float = 0.045f,
    val blurTau: Float = 0.25f,

    // ---------------------------------------------------------------- colour
    val colorFromEnergy: Float = 0.26f,
    val colorFromIntensity: Float = 0.46f,
    val colorFromPulse: Float = 0.24f,
    val colorFromBrightness: Float = 0.16f,

    /** Colour is spring-smoothed first... */
    val colorSpringHz: Float = 1.1f,
    val colorSpringDamping: Float = 1.0f,

    /** ...then hard-limited, so no input can produce a visible flash. */
    val colorRisePerSecond: Float = 1.1f,
    val colorFallPerSecond: Float = 0.55f,

    val baseBrightness: Float = 0.12f,
    val brightnessFromIntensity: Float = 0.55f,
    val brightnessFromPulse: Float = 0.30f,
    val brightnessTau: Float = 0.18f,

    // ------------------------------------------------------------- idle life
    /** Periods of the two breathing oscillators, seconds. Deliberately coprime-ish. */
    val breathPeriodPrimary: Float = 5.3f,
    val breathPeriodSecondary: Float = 8.9f,

    // ---------------------------------------------------------- motion field
    val fieldOctaves: Int = 3,
    val fieldBaseFrequency: Float = 0.55f,
    val fieldLacunarity: Float = 2.05f,
    val fieldGain: Float = 0.5f,
    val fieldTimeRate: Float = 0.22f,
    val fieldTimeLacunarity: Float = 1.85f,

    // ------------------------------------------------------------- stability
    /** Dead-band applied to deformation and turbulence to kill sub-pixel jitter. */
    val jitterDeadband: Float = 0.0015f,

    /**
     * Soft-knee point applied to every summed parameter.
     *
     * Contributions are chosen to sum slightly above 1 at full input; the knee
     * bends that sum asymptotically toward 1 instead of clipping. A clipped
     * parameter is a dead parameter — it stops responding exactly when the user
     * is being most emphatic — so nothing in this system is allowed to reach its
     * ceiling by simple addition.
     */
    val summationKnee: Float = 0.75f,
) {
    companion object {
        /** Reference tuning: calm, premium, suitable for a voice assistant. */
        val Default: AnimationConfig = AnimationConfig()

        /**
         * Larger, faster, more theatrical. Good for a music visualizer or a
         * full-screen "listening" state.
         */
        val Expressive: AnimationConfig = AnimationConfig(
            scaleFromIntensity = 0.10f,
            scaleFromPulse = 0.085f,
            deformationFromIntensity = 0.72f,
            deformationFromPulse = 0.50f,
            turbulenceFromPulse = 0.55f,
            glowFromPulse = 0.60f,
            timeScaleFromIntensity = 1.3f,
            rotationFromEnergy = 2.2f,
            colorFromPulse = 0.42f,
        )

        /**
         * Minimal movement: for a small always-on indicator where a lively blob
         * would be a distraction.
         */
        val Subtle: AnimationConfig = AnimationConfig(
            scaleFromIntensity = 0.03f,
            scaleFromPulse = 0.02f,
            idleDeformation = 0.10f,
            deformationFromIntensity = 0.32f,
            deformationFromPulse = 0.16f,
            turbulenceFromPulse = 0.20f,
            glowFromPulse = 0.22f,
            timeScaleFromIntensity = 0.45f,
            baseRotationSpeed = 0.03f,
            colorFromPulse = 0.15f,
        )
    }
}
