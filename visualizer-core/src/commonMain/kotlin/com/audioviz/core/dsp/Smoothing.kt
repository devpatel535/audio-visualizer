package com.audioviz.core.dsp

import com.audioviz.core.util.LN2
import com.audioviz.core.util.TWO_PI
import com.audioviz.core.util.sanitize
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.min

/**
 * Frame-rate-independent smoothing primitives.
 *
 * Every follower in this file is parameterised by a *time constant* in seconds
 * rather than a per-frame coefficient. The per-step coefficient is derived as
 *
 *     alpha = 1 - exp(-dt / tau)
 *
 * so a 0.15 s release behaves identically at 30 fps, 60 fps, 120 fps, or at the
 * irregular block rate of an audio callback. Using a fixed per-frame alpha (the
 * common `value += (target - value) * 0.1f`) is the single most common cause of
 * visualizers that "feel different" on different devices.
 */
object Smoothing {

    /** Exponential smoothing coefficient for a step of [dt] seconds and time constant [tau]. */
    fun alpha(dt: Float, tau: Float): Float {
        if (tau <= 1e-6f) return 1f
        if (dt <= 0f) return 0f
        return 1f - exp(-dt / tau)
    }

    /** Converts a *half-life* (time to close half the gap) to a 1/e time constant. */
    fun halfLifeToTau(halfLifeSeconds: Float): Float = halfLifeSeconds / LN2

    /** Converts a 1/e time constant to a half-life. */
    fun tauToHalfLife(tau: Float): Float = tau * LN2
}

/**
 * First-order low-pass follower with a single time constant.
 *
 * @param tau 1/e time constant in seconds.
 */
class OnePole(
    var tau: Float,
    initial: Float = 0f,
) {
    var value: Float = initial
        private set

    fun reset(v: Float = 0f) {
        value = v
    }

    fun update(target: Float, dt: Float): Float {
        val t = sanitize(target, value)
        value += (t - value) * Smoothing.alpha(dt, tau)
        return value
    }
}

/**
 * Asymmetric envelope follower: rises with [attackTau], falls with [releaseTau].
 *
 * This is the workhorse of the whole system. A short attack + long release turns
 * a spiky, noisy loudness signal into something that *snaps* with the onset of
 * speech and then decays gracefully instead of flickering off between syllables.
 *
 * Typical settings used by the analyzer:
 *  - fast envelope: attack 8 ms / release 120 ms  -> transients, consonants
 *  - slow envelope: attack 120 ms / release 600 ms -> sustained speech energy
 *  - sustained:     attack 1.2 s  / release 3.0 s  -> "mood", drives color drift
 */
class AttackRelease(
    var attackTau: Float,
    var releaseTau: Float,
    initial: Float = 0f,
) {
    var value: Float = initial
        private set

    fun reset(v: Float = 0f) {
        value = v
    }

    fun update(target: Float, dt: Float): Float {
        val t = sanitize(target, value)
        val tau = if (t > value) attackTau else releaseTau
        value += (t - value) * Smoothing.alpha(dt, tau)
        return value
    }
}

/**
 * Limits how fast a value may change, in units per second.
 *
 * Used for anything that must never flash: the colour mix parameter, glow, and
 * opacity all pass through a slew limiter so that even a pathological audio
 * spike cannot produce a one-frame strobe.
 */
class SlewLimiter(
    var maxRisePerSecond: Float,
    var maxFallPerSecond: Float = maxRisePerSecond,
    initial: Float = 0f,
) {
    var value: Float = initial
        private set

    fun reset(v: Float = 0f) {
        value = v
    }

    fun update(target: Float, dt: Float): Float {
        val t = sanitize(target, value)
        val delta = t - value
        val limit = if (delta >= 0f) maxRisePerSecond * dt else maxFallPerSecond * dt
        value += if (abs(delta) <= limit) delta else (if (delta > 0f) limit else -limit)
        return value
    }
}

/**
 * Hysteretic dead-band. Ignores changes smaller than [threshold] until the input
 * has moved far enough, then tracks it again.
 *
 * This removes the last of the frame-to-frame jitter that survives smoothing —
 * the sub-perceptual wobble that reads as "cheap" on an otherwise calm visual.
 */
class Deadband(
    var threshold: Float,
    initial: Float = 0f,
) {
    var value: Float = initial
        private set

    fun reset(v: Float = 0f) {
        value = v
    }

    fun update(target: Float): Float {
        val t = sanitize(target, value)
        if (abs(t - value) > threshold) {
            value = if (t > value) t - threshold else t + threshold
        }
        return value
    }
}

/**
 * Damped harmonic oscillator, integrated with sub-stepped semi-implicit Euler.
 *
 * Springs give the visual its sense of mass: a syllable does not merely raise a
 * value, it *throws* it, and the value settles back with a little overshoot.
 *
 * @param frequencyHz natural frequency; higher = snappier.
 * @param damping damping ratio. 1.0 is critically damped (no overshoot),
 *   0.6-0.85 gives the lively-but-controlled feel used throughout the defaults.
 *
 * Sub-stepping at a fixed 1/240 s keeps the integration stable when a frame is
 * dropped, which a naive single-step Euler spring does not survive.
 */
class Spring(
    var frequencyHz: Float,
    var damping: Float,
    initial: Float = 0f,
) {
    var value: Float = initial
        private set
    var velocity: Float = 0f
        private set

    fun reset(v: Float = 0f) {
        value = v
        velocity = 0f
    }

    /** Adds an instantaneous velocity impulse — used to "kick" the shape on a transient. */
    fun impulse(v: Float) {
        velocity += v
    }

    fun update(target: Float, dt: Float): Float {
        val t = sanitize(target, value)
        val omega = TWO_PI * frequencyHz
        val k = omega * omega
        val c = 2f * damping * omega
        var remaining = min(dt, MAX_STEP_TOTAL)
        while (remaining > 0f) {
            val h = min(remaining, SUB_STEP)
            remaining -= h
            val accel = -k * (value - t) - c * velocity
            velocity += accel * h
            value += velocity * h
        }
        return value
    }

    private companion object {
        const val SUB_STEP = 1f / 240f
        const val MAX_STEP_TOTAL = 0.1f
    }
}
