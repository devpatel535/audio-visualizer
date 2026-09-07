package com.audioviz.core

import com.audioviz.core.geometry.ParticleField
import com.audioviz.core.geometry.ParticleFieldConfig
import com.audioviz.core.geometry.RadialShape
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The particle field needs a short run-up before anything is visible.
 *
 * Two mechanisms combine: emission accumulates fractionally, so at the default
 * rate a single frame rarely spawns even one particle; and a particle fades in
 * over the first 15% of its life, so the one that does spawn is transparent on
 * the frame it appears.
 *
 * This is correct behaviour — particles that pop into existence at full
 * brightness read as a glitch — but it is a trap for anything that renders a
 * single frame and expects to see something. It caught this project's own Skia
 * pixel test, which asserted that every renderer draws something and rendered
 * exactly one frame to check.
 */
class ParticleWarmupTest {

    private val emitter = RadialShape { 1f }

    private fun visibleAfter(frames: Int): Int {
        val field = ParticleField(ParticleFieldConfig())
        val params = FakeParams(emission = 0.74f, deltaTime = 1f / 60f)
        repeat(frames) { field.update(params, emitter) }

        var visible = 0
        for (i in 0 until field.count) {
            val remaining = field.remainingLife(i)
            val fade = if (remaining > 0.85f) (1f - remaining) / 0.15f else remaining
            if (fade > 0.05f) visible++
        }
        return visible
    }

    @Test
    fun oneFrameIsEffectivelyEmpty() {
        // Measured: exactly one particle, a couple of pixels across and still
        // fading in. Against a 320x320 canvas that is ~0.00006 of the mean
        // luminance, which is why a single-frame render of the particle layer
        // reads as blank.
        val visible = visibleAfter(1)
        assertTrue(
            visible <= 1,
            "a single frame produced $visible visible particles; the pixel test's warm-up " +
                "assumption no longer holds",
        )
    }

    @Test
    fun aShortWarmupIsEnough() {
        val visible = visibleAfter(30)
        assertTrue(visible > 5, "half a second of warm-up produced only $visible visible particles")
    }
}
