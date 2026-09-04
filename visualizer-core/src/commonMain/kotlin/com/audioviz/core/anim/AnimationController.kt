package com.audioviz.core.anim

import com.audioviz.core.analysis.AudioFeatures
import com.audioviz.core.dsp.AttackRelease
import com.audioviz.core.dsp.Deadband
import com.audioviz.core.dsp.OnePole
import com.audioviz.core.dsp.SlewLimiter
import com.audioviz.core.dsp.Spring
import com.audioviz.core.util.TWO_PI
import com.audioviz.core.util.clamp01
import com.audioviz.core.util.gammaCurve
import com.audioviz.core.util.smootherStep
import com.audioviz.core.util.softKnee
import com.audioviz.core.util.wrap
import kotlin.math.exp
import kotlin.math.sin

/**
 * Converts [AudioFeatures] into [VisualParams].
 *
 * This is the layer that decides what "loud" *means* visually, and it is the
 * only place in the system that knows about both worlds. It runs on the render
 * thread at display rate — independently of the audio frame rate — which is why
 * the visual stays smooth at 120 fps even though analysis only produces ~90
 * frames a second, and why it keeps breathing when audio stops arriving
 * entirely.
 *
 * ### Mapping principles
 *
 * *Nothing is mapped directly.* Every parameter is the sum of contributions
 * from several audio signals, each with its own response curve, passed through
 * a smoother chosen for that parameter's physical character:
 *
 * | Parameter    | Sources                                   | Smoother            | Curve         |
 * |--------------|-------------------------------------------|---------------------|---------------|
 * | intensity    | slow envelope                             | spring 2.1 Hz z=.88 | gamma + knee  |
 * | pulse        | transient / onset                         | attack 20 / rel 200 | gamma 0.8     |
 * | scale        | intensity, pulse, idle breath             | spring 2.6 Hz z=.62 | gamma 1.25    |
 * | deformation  | intensity, pulse, low band                | spring 1.55 z=.72   | smootherstep  |
 * | turbulence   | high band, pulse, brightness              | attack 70 / rel 420 | linear        |
 * | glow         | intensity, pulse^1.6, high band           | env + slew limiter  | smoothstep    |
 * | colorMix     | energy, intensity, pulse, brightness      | spring + slew       | linear        |
 * | rotation     | energy, intensity, noise wobble, impulses | integrated velocity | -             |
 *
 * Because the smoothers differ, the parameters *lag each other* — glow arrives
 * a hair before the shape finishes deforming, rotation keeps carrying after the
 * pulse dies. That staggering is what makes a set of numbers read as one living
 * object instead of several synchronised animations.
 */
class AnimationController(
    config: AnimationConfig = AnimationConfig.Default,
    bandCapacity: Int = 8,
    seed: Int = 90_210,
) {
    var config: AnimationConfig = config
        set(value) {
            field = value
            applyConfig()
        }

    /** The shared noise field. Renderers sample it through [VisualParams.field]. */
    val field: MotionField = MotionField(seed = seed)

    private val params = MutableVisualParams(field, bandCapacity)

    // ---- Smoothers, one per visual character --------------------------------
    private val intensitySpring = Spring(config.intensitySpringHz, config.intensitySpringDamping)
    private val pulseEnvelope = AttackRelease(config.pulseAttackTau, config.pulseReleaseTau)
    private val energyFollower = OnePole(config.energyTau)
    private val scaleSpring = Spring(config.scaleSpringHz, config.scaleSpringDamping, initial = 1f)
    private val deformationSpring = Spring(config.deformationSpringHz, config.deformationSpringDamping)
    private val turbulenceEnvelope = AttackRelease(config.turbulenceAttackTau, config.turbulenceReleaseTau)
    private val detailFollower = OnePole(config.detailTau)
    private val waveFollower = OnePole(config.waveTau)
    private val emissionEnvelope = AttackRelease(config.emissionAttackTau, config.emissionReleaseTau)
    private val glowEnvelope = AttackRelease(config.glowAttackTau, config.glowReleaseTau)
    private val glowSlew = SlewLimiter(config.glowRisePerSecond, config.glowFallPerSecond)
    private val opacityFollower = OnePole(config.opacityTau, initial = config.baseOpacity)
    private val blurFollower = OnePole(config.blurTau, initial = config.baseBlur)
    private val colorSpring = Spring(config.colorSpringHz, config.colorSpringDamping)
    private val colorSlew = SlewLimiter(config.colorRisePerSecond, config.colorFallPerSecond)
    private val brightnessFollower = OnePole(config.brightnessTau)
    private val driftXFollower = OnePole(config.driftTau)
    private val driftYFollower = OnePole(config.driftTau)

    private val deformationDeadband = Deadband(config.jitterDeadband)
    private val turbulenceDeadband = Deadband(config.jitterDeadband)

    // ---- Integrated / stateful values --------------------------------------
    private var rotation = 0f
    private var rotationImpulse = 0f
    private var animationTime = 0f
    private var breathPhasePrimary = 0f
    private var breathPhaseSecondary = 0.31f
    private var previousPulse = 0f
    private var lastSequence = -1L

    init {
        applyConfig()
    }

    /** Resets every follower. Call when audio restarts after a long pause. */
    fun reset() {
        intensitySpring.reset()
        pulseEnvelope.reset()
        energyFollower.reset()
        scaleSpring.reset(1f)
        deformationSpring.reset()
        turbulenceEnvelope.reset()
        detailFollower.reset()
        waveFollower.reset()
        emissionEnvelope.reset()
        glowEnvelope.reset()
        glowSlew.reset()
        opacityFollower.reset(config.baseOpacity)
        blurFollower.reset(config.baseBlur)
        colorSpring.reset()
        colorSlew.reset()
        brightnessFollower.reset()
        driftXFollower.reset()
        driftYFollower.reset()
        deformationDeadband.reset()
        turbulenceDeadband.reset()
        rotation = 0f
        rotationImpulse = 0f
        animationTime = 0f
        breathPhasePrimary = 0f
        breathPhaseSecondary = 0.31f
        previousPulse = 0f
        lastSequence = -1L
    }

    /**
     * Advances the animation by [deltaSeconds] using the latest audio snapshot.
     *
     * @param features most recent analyzer output. Passing the *same* snapshot
     *   repeatedly is expected and correct: audio arrives at ~90 Hz, frames at
     *   60-120 Hz, and every follower here is driven by wall-clock time, so the
     *   motion stays smooth between audio updates.
     * @return the controller's own reusable parameter object. Valid until the
     *   next call; never allocated per frame.
     */
    fun update(features: AudioFeatures, deltaSeconds: Float): VisualParams {
        val cfg = config
        val dt = deltaSeconds.coerceIn(cfg.minDeltaSeconds, cfg.maxDeltaSeconds)

        // ---------------------------------------------------------- primary
        // intensity: gamma expands quiet speech, the soft knee stops a shout
        // from pinning everything at 1, and the spring gives the value mass.
        val intensityTarget = softKnee(
            gammaCurve(features.slow, cfg.intensityGamma),
            cfg.intensityKnee,
        )
        val intensity = clamp01(intensitySpring.update(intensityTarget, dt))

        // pulse: a separate, much faster path so consonants read instantly even
        // while `intensity` is still climbing.
        val pulse = clamp01(
            pulseEnvelope.update(gammaCurve(features.transient, cfg.pulseGamma), dt),
        )

        val energy = clamp01(energyFollower.update(features.sustained, dt))

        val low = features.lowEnergy
        val mid = features.midEnergy
        val high = features.highEnergy

        // ------------------------------------------------------- idle life
        val activity = clamp01(maxOf(intensity, pulse * 0.85f))
        val idle = 1f - smootherStep(0f, cfg.idleFadeEnd, activity)

        // -------------------------------------------------- animation clock
        // Louder audio does not just make things bigger, it makes time move
        // faster. This single line does more for "responsive" than any amount
        // of extra amplitude.
        val timeScale = 1f +
            cfg.timeScaleFromIntensity * intensity +
            cfg.timeScaleFromPulse * pulse
        animationTime = wrap(animationTime + dt * timeScale, TIME_WRAP)
        field.advance(dt, timeScale)

        // Two oscillators at unrelated periods: the sum never repeats visibly,
        // which is what separates "breathing" from "blinking on a timer". Each
        // keeps its own wrapped phase so the hourly wrap of `animationTime`
        // can never introduce a discontinuity here.
        breathPhasePrimary = wrap(breathPhasePrimary + dt * timeScale / cfg.breathPeriodPrimary, 1f)
        breathPhaseSecondary = wrap(breathPhaseSecondary + dt * timeScale / cfg.breathPeriodSecondary, 1f)
        val breathRaw =
            0.62f * sin(TWO_PI * breathPhasePrimary) +
                0.38f * sin(TWO_PI * breathPhaseSecondary + 1.7f)
        val breath = 0.5f + 0.5f * breathRaw

        // ------------------------------------------------------------ scale
        val scaleTarget = 1f +
            cfg.scaleFromIntensity * gammaCurve(intensity, 1.25f) +
            cfg.scaleFromPulse * pulse +
            cfg.scaleFromIdleBreath * idle * (breath * 2f - 1f)
        // A rising transient is a *push*, not a new target: injecting velocity
        // makes the shape recoil and settle like something with mass.
        val pulseRise = (pulse - previousPulse).coerceAtLeast(0f)
        if (pulseRise > 1e-4f) scaleSpring.impulse(pulseRise * cfg.scaleImpulseGain)
        val scale = scaleSpring.update(scaleTarget, dt)

        // ------------------------------------------------------ deformation
        // The spring carries only the *slow form* — how deformed the object is
        // while someone is speaking at all. The transient is added afterwards
        // rather than passed through the spring, because a 1.55 Hz spring would
        // filter a 4 Hz syllable rate almost completely away and the shape
        // would stop looking synchronised with the voice.
        val formTarget = cfg.idleDeformation +
            cfg.deformationFromIntensity * smootherStep(0f, 1f, intensity) +
            cfg.deformationFromLowBand * low
        val form = deformationSpring.update(formTarget, dt)
        val deformation = clamp01(
            deformationDeadband.update(
                softKnee(
                    form + cfg.deformationFromPulse * gammaCurve(pulse, 0.8f),
                    cfg.summationKnee,
                ),
            ),
        )

        // ------------------------------------------------------- turbulence
        val turbulenceBase = turbulenceEnvelope.update(
            cfg.idleTurbulence +
                cfg.turbulenceFromHighBand * high +
                cfg.turbulenceFromBrightness * features.brightness * intensity,
            dt,
        )
        val turbulence = clamp01(
            turbulenceDeadband.update(
                softKnee(turbulenceBase + cfg.turbulenceFromPulse * pulse, cfg.summationKnee),
            ),
        )

        // ----------------------------------------------------------- detail
        val detail = clamp01(
            detailFollower.update(
                cfg.idleDetail +
                    cfg.detailFromMidBand * mid +
                    cfg.detailFromIntensity * intensity,
                dt,
            ),
        )

        val wave = clamp01(
            waveFollower.update(
                cfg.idleWaveAmplitude +
                    cfg.waveFromLevel * features.level +
                    cfg.waveFromPulse * pulse,
                dt,
            ),
        )

        val emission = clamp01(
            emissionEnvelope.update(
                cfg.idleEmission +
                    cfg.emissionFromIntensity * intensity +
                    cfg.emissionFromPulse * pulse,
                dt,
            ),
        )

        // ------------------------------------------------------------- glow
        // Same split as deformation: a smoothed base from sustained energy,
        // plus an un-smoothed transient term so an emphasised word actually
        // reads as a flare. The slew limiter downstream is the safety net —
        // whatever the inputs do, glow cannot change faster than
        // `glowRisePerSecond`, so a one-frame flash is not expressible.
        val glowBase = glowEnvelope.update(
            cfg.idleGlow +
                cfg.glowFromIntensity * smootherStep(0f, 1f, intensity) +
                cfg.glowFromHighBand * high,
            dt,
        )
        val glowTarget = softKnee(
            glowBase + cfg.glowFromPulse * gammaCurve(pulse, 1.6f),
            cfg.summationKnee,
        )
        val glow = clamp01(glowSlew.update(glowTarget, dt))

        val opacity = clamp01(
            opacityFollower.update(cfg.baseOpacity + cfg.opacityFromIntensity * intensity, dt),
        )

        val blur = clamp01(
            blurFollower.update(
                (cfg.baseBlur + cfg.blurFromIdle * idle - cfg.blurFromPulse * pulse)
                    .coerceAtLeast(0f),
                dt,
            ),
        )

        // --------------------------------------------------------- rotation
        // Velocity, not angle: the shape keeps its momentum through a pause and
        // never snaps back to a reference orientation.
        rotationImpulse *= exp(-dt / cfg.rotationImpulseTau)
        if (pulseRise > 1e-4f) rotationImpulse += pulseRise * cfg.rotationImpulse
        val wobble = field.scalar(ROTATION_CHANNEL)
        val rotationVelocity = cfg.baseRotationSpeed *
            (1f + cfg.rotationFromEnergy * energy + cfg.rotationFromIntensity * intensity) +
            cfg.rotationWobble * wobble +
            rotationImpulse
        rotation = wrap(rotation + rotationVelocity * dt, TWO_PI)

        // ------------------------------------------------------------ drift
        val driftAmount = cfg.idleDrift + cfg.driftFromIntensity * intensity
        val driftX = driftXFollower.update(field.scalar(DRIFT_X_CHANNEL) * driftAmount, dt)
        val driftY = driftYFollower.update(field.scalar(DRIFT_Y_CHANNEL) * driftAmount, dt)

        // ----------------------------------------------------------- colour
        // Four contributors on four time scales, then a spring, then a hard
        // rate limit. Colour can only ever *drift*, never switch.
        val colorTarget = softKnee(
            cfg.colorFromEnergy * energy +
                cfg.colorFromIntensity * intensity +
                cfg.colorFromPulse * pulse +
                cfg.colorFromBrightness * features.brightness * intensity,
            cfg.summationKnee,
        )
        val colorMix = clamp01(colorSlew.update(colorSpring.update(colorTarget, dt), dt))

        val brightness = clamp01(
            brightnessFollower.update(
                cfg.baseBrightness +
                    cfg.brightnessFromIntensity * intensity +
                    cfg.brightnessFromPulse * pulse,
                dt,
            ),
        )

        // ---------------------------------------------------------- publish
        previousPulse = pulse
        lastSequence = features.sequence

        return params.apply {
            this.intensity = intensity
            this.pulse = pulse
            this.energy = energy
            this.idle = idle
            this.breath = breath
            this.scale = scale
            this.deformation = deformation
            this.turbulence = turbulence
            this.detail = detail
            this.waveAmplitude = wave
            this.emission = emission
            this.rotation = rotation
            this.rotationVelocity = rotationVelocity
            this.driftX = driftX
            this.driftY = driftY
            this.glow = glow
            this.opacity = opacity
            this.blur = blur
            this.colorMix = colorMix
            this.brightness = brightness
            this.level = features.level
            this.gate = features.gate
            this.spectralBrightness = features.brightness
            this.lowEnergy = low
            this.midEnergy = mid
            this.highEnergy = high
            this.time = animationTime
            this.deltaTime = dt
            setBands(features.bands)
        }
    }

    /** The most recent parameters, without advancing anything. */
    val current: VisualParams get() = params

    private fun applyConfig() {
        val cfg = config
        intensitySpring.frequencyHz = cfg.intensitySpringHz
        intensitySpring.damping = cfg.intensitySpringDamping
        pulseEnvelope.attackTau = cfg.pulseAttackTau
        pulseEnvelope.releaseTau = cfg.pulseReleaseTau
        energyFollower.tau = cfg.energyTau
        scaleSpring.frequencyHz = cfg.scaleSpringHz
        scaleSpring.damping = cfg.scaleSpringDamping
        deformationSpring.frequencyHz = cfg.deformationSpringHz
        deformationSpring.damping = cfg.deformationSpringDamping
        turbulenceEnvelope.attackTau = cfg.turbulenceAttackTau
        turbulenceEnvelope.releaseTau = cfg.turbulenceReleaseTau
        detailFollower.tau = cfg.detailTau
        waveFollower.tau = cfg.waveTau
        emissionEnvelope.attackTau = cfg.emissionAttackTau
        emissionEnvelope.releaseTau = cfg.emissionReleaseTau
        glowEnvelope.attackTau = cfg.glowAttackTau
        glowEnvelope.releaseTau = cfg.glowReleaseTau
        glowSlew.maxRisePerSecond = cfg.glowRisePerSecond
        glowSlew.maxFallPerSecond = cfg.glowFallPerSecond
        opacityFollower.tau = cfg.opacityTau
        blurFollower.tau = cfg.blurTau
        colorSpring.frequencyHz = cfg.colorSpringHz
        colorSpring.damping = cfg.colorSpringDamping
        colorSlew.maxRisePerSecond = cfg.colorRisePerSecond
        colorSlew.maxFallPerSecond = cfg.colorFallPerSecond
        brightnessFollower.tau = cfg.brightnessTau
        driftXFollower.tau = cfg.driftTau
        driftYFollower.tau = cfg.driftTau
        deformationDeadband.threshold = cfg.jitterDeadband
        turbulenceDeadband.threshold = cfg.jitterDeadband

        field.octaves = cfg.fieldOctaves
        field.baseFrequency = cfg.fieldBaseFrequency
        field.lacunarity = cfg.fieldLacunarity
        field.gain = cfg.fieldGain
        field.timeRate = cfg.fieldTimeRate
        field.timeLacunarity = cfg.fieldTimeLacunarity
    }

    private companion object {
        /**
         * The animation clock wraps hourly so float precision never degrades in
         * an always-on session. Renderers that need a strictly continuous
         * oscillator should read [VisualParams.breath] or sample
         * [VisualParams.field], both of which are wrap-safe.
         */
        const val TIME_WRAP = 3600f

        const val ROTATION_CHANNEL = 0.37f
        const val DRIFT_X_CHANNEL = 1.61f
        const val DRIFT_Y_CHANNEL = 2.71f
    }
}
