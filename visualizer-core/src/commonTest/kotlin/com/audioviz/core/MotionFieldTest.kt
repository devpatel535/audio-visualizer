package com.audioviz.core

import com.audioviz.core.anim.MotionField
import com.audioviz.core.anim.PerlinNoise3D
import com.audioviz.core.util.TWO_PI
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MotionFieldTest {

    @Test
    fun perlinStaysInRangeAndIsDeterministic() {
        val a = PerlinNoise3D(seed = 4242)
        val b = PerlinNoise3D(seed = 4242)
        var minimum = Float.MAX_VALUE
        var maximum = -Float.MAX_VALUE

        var i = 0
        while (i < 5000) {
            val x = i * 0.013f
            val y = i * 0.029f - 7f
            val z = i * 0.007f
            val v = a.noise(x, y, z)
            assertEquals(v, b.noise(x, y, z), 0f, "same seed must give identical values")
            assertTrue(v.isFinite(), "noise produced a non-finite value")
            if (v < minimum) minimum = v
            if (v > maximum) maximum = v
            i++
        }

        assertTrue(minimum >= -1f && maximum <= 1f, "noise escaped -1..1: $minimum..$maximum")
        assertTrue(maximum > 0.4f && minimum < -0.4f, "noise barely moved: $minimum..$maximum")
    }

    @Test
    fun perlinIsContinuous() {
        val noise = PerlinNoise3D()
        var largestStep = 0f
        var t = 0f
        while (t < 40f) {
            val a = noise.noise(t, 3.3f, 1.1f)
            val b = noise.noise(t + 0.005f, 3.3f, 1.1f)
            val step = abs(b - a)
            if (step > largestStep) largestStep = step
            t += 0.005f
        }
        // A coherent field cannot jump; a hash-based one would.
        assertTrue(largestStep < 0.05f, "field is not continuous, largest step $largestStep")
    }

    @Test
    fun loopIsSeamlessAroundTheCircle() {
        // The property that lets any closed outline be deformed without a seam.
        val field = MotionField()
        repeat(30) { field.advance(1f / 60f) }

        for (layer in listOf(0f, 1.7f, 3.3f)) {
            val atZero = field.loop(0f, layer)
            val atTwoPi = field.loop(TWO_PI, layer)
            assertEquals(atZero, atTwoPi, 1e-4f, "seam at layer $layer")

            val justBefore = field.loop(TWO_PI - 0.001f, layer)
            assertTrue(
                abs(justBefore - atZero) < 0.01f,
                "discontinuity approaching the seam at layer $layer",
            )
        }
    }

    @Test
    fun fieldOutputsStayNormalized() {
        val field = MotionField()
        var minimum = Float.MAX_VALUE
        var maximum = -Float.MAX_VALUE
        repeat(400) { step ->
            field.advance(1f / 60f, speed = 1f + step * 0.002f)
            var i = 0
            while (i < 64) {
                val theta = i * TWO_PI / 64
                for (v in listOf(
                    field.loop(theta),
                    field.line(i / 64f),
                    field.plane(theta, theta * 0.5f),
                    field.scalar(i * 0.1f),
                )) {
                    assertTrue(v.isFinite())
                    if (v < minimum) minimum = v
                    if (v > maximum) maximum = v
                }
                i++
            }
        }
        assertTrue(minimum >= -1.01f && maximum <= 1.01f, "field escaped -1..1: $minimum..$maximum")
    }

    @Test
    fun higherOctavesEvolveFasterThanLowerOnes() {
        // Layered time is what makes the motion organic rather than uniform.
        val field = MotionField(timeRate = 0.25f, timeLacunarity = 2f)
        val theta = 1.2f

        val coarseStart = field.loop(theta, octaves = 1)
        val detailedStart = field.loop(theta, octaves = 4)
        repeat(30) { field.advance(1f / 60f) }
        val coarseDelta = abs(field.loop(theta, octaves = 1) - coarseStart)
        val detailedDelta = abs(field.loop(theta, octaves = 4) - detailedStart)

        assertTrue(
            detailedDelta > coarseDelta,
            "detail ($detailedDelta) should move faster than the base shape ($coarseDelta)",
        )
    }
}
