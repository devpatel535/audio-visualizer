package com.audioviz.core

import com.audioviz.core.dsp.AttackRelease
import com.audioviz.core.dsp.Deadband
import com.audioviz.core.dsp.OnePole
import com.audioviz.core.dsp.SlewLimiter
import com.audioviz.core.dsp.Smoothing
import com.audioviz.core.dsp.Spring
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SmoothingTest {

    @Test
    fun onePoleReachesTargetIndependentlyOfFrameRate() {
        // The core promise of the whole system: identical behaviour at any frame rate.
        val slow = OnePole(tau = 0.25f)
        val fast = OnePole(tau = 0.25f)

        repeat(60) { slow.update(1f, 1f / 60f) }
        repeat(240) { fast.update(1f, 1f / 240f) }

        assertTrue(
            abs(slow.value - fast.value) < 0.002f,
            "60 fps gave ${slow.value}, 240 fps gave ${fast.value}",
        )
    }

    @Test
    fun onePoleClosesOneHalfLifeInAHalfLife() {
        val tau = Smoothing.halfLifeToTau(0.2f)
        val follower = OnePole(tau)
        val steps = 200
        repeat(steps) { follower.update(1f, 0.2f / steps) }
        assertEquals(0.5f, follower.value, 0.01f)
    }

    @Test
    fun attackReleaseIsAsymmetric() {
        val follower = AttackRelease(attackTau = 0.01f, releaseTau = 0.5f)

        repeat(20) { follower.update(1f, 1f / 100f) }
        val afterAttack = follower.value
        assertTrue(afterAttack > 0.85f, "fast attack should be near 1, was $afterAttack")

        repeat(20) { follower.update(0f, 1f / 100f) }
        val afterRelease = follower.value
        assertTrue(afterRelease > 0.5f, "slow release should still be high, was $afterRelease")
    }

    @Test
    fun slewLimiterRespectsItsRate() {
        val limiter = SlewLimiter(maxRisePerSecond = 2f, maxFallPerSecond = 1f)
        limiter.update(1f, 0.1f)
        assertEquals(0.2f, limiter.value, 1e-5f)

        repeat(100) { limiter.update(1f, 0.1f) }
        limiter.update(0f, 0.1f)
        assertEquals(0.9f, limiter.value, 1e-5f)
    }

    @Test
    fun deadbandIgnoresTinyChanges() {
        val band = Deadband(threshold = 0.01f)
        band.update(0.5f)
        val baseline = band.value

        // Anything inside the dead zone leaves the output exactly where it was.
        band.update(0.495f)
        assertEquals(baseline, band.value, 1e-6f, "sub-threshold change must not move the value")
        band.update(0.5f)
        assertEquals(baseline, band.value, 1e-6f, "returning to the same input must not move it either")

        band.update(0.6f)
        assertTrue(band.value > baseline, "supra-threshold change must move the value")
    }

    @Test
    fun deadbandConvertsJitterIntoAStandstill() {
        // A signal dithering by +/-0.002 around 0.5 must not produce ongoing
        // motion: this is the last line of defence against visible frame jitter.
        val band = Deadband(threshold = 0.01f)
        repeat(10) { band.update(0.5f) }
        val settled = band.value

        var maximumExcursion = 0f
        repeat(200) { i ->
            val dither = if (i % 2 == 0) 0.002f else -0.002f
            band.update(0.5f + dither)
            val excursion = abs(band.value - settled)
            if (excursion > maximumExcursion) maximumExcursion = excursion
        }
        assertTrue(maximumExcursion < 0.0021f, "dead-band leaked jitter of $maximumExcursion")
    }

    @Test
    fun springSettlesOnTargetAndStaysStableWithLongFrames() {
        val spring = Spring(frequencyHz = 2f, damping = 0.8f)
        // 8 fps: a single-step Euler spring diverges here; the sub-stepped one must not.
        repeat(80) { spring.update(1f, 1f / 8f) }
        assertTrue(spring.value.isFinite(), "spring diverged")
        assertEquals(1f, spring.value, 0.05f)
    }

    @Test
    fun springOvershootsWhenUnderdamped() {
        val spring = Spring(frequencyHz = 3f, damping = 0.35f)
        var peak = 0f
        repeat(120) {
            spring.update(1f, 1f / 120f)
            if (spring.value > peak) peak = spring.value
        }
        assertTrue(peak > 1.05f, "under-damped spring should overshoot, peaked at $peak")
    }

    @Test
    fun springImpulseMovesTheValue() {
        val spring = Spring(frequencyHz = 2.5f, damping = 0.7f)
        spring.impulse(1f)
        spring.update(0f, 1f / 60f)
        assertTrue(spring.value > 0f, "an impulse must produce motion")
    }
}
