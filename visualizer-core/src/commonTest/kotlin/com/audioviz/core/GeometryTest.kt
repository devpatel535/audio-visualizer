package com.audioviz.core

import com.audioviz.core.anim.VisualParams
import com.audioviz.core.geometry.ParticleField
import com.audioviz.core.geometry.ParticleFieldConfig
import com.audioviz.core.geometry.PolarProfile
import com.audioviz.core.geometry.PolarProfileConfig
import com.audioviz.core.geometry.RadialShape
import com.audioviz.core.geometry.RadialShapes
import com.audioviz.core.util.TWO_PI
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

class GeometryTest {

    private fun paramsAfter(seconds: Float, fill: (FloatArray) -> Unit): Pair<PipelineRig, VisualParams> {
        val rig = PipelineRig()
        var last: VisualParams? = null
        rig.run(seconds, fill) { last = it }
        return rig to last!!
    }

    // ---- RadialShape --------------------------------------------------------

    @Test
    fun everyBuiltInShapeIsPeriodicAndPositive() {
        val shapes = mapOf(
            "circle" to RadialShapes.Circle,
            "squircle" to RadialShapes.superellipse(4f),
            "hexagon" to RadialShapes.polygon(6),
            "triangle" to RadialShapes.polygon(3, cornerSoftness = 0f),
            "star" to RadialShapes.star(5),
            "superformula" to RadialShapes.superformula(),
            "sampled" to RadialShapes.sampled(FloatArray(64) { 1f + 0.2f * kotlin.math.sin(it * 0.5f) }),
        )
        for ((name, shape) in shapes) {
            var i = 0
            while (i < 512) {
                val theta = i * TWO_PI / 512
                val r = shape.radiusAt(theta)
                assertTrue(r.isFinite() && r > 0.05f, "$name gave radius $r at $theta")
                assertTrue(r < 4f, "$name gave an absurd radius $r at $theta")
                val wrapped = shape.radiusAt(theta + TWO_PI)
                assertTrue(abs(r - wrapped) < 1e-3f, "$name is not 2*pi-periodic at $theta")
                i++
            }
        }
    }

    @Test
    fun everyBuiltInShapePeaksAtOne() {
        // The interchangeability contract: swapping a shape must not change how
        // large the visual is on screen. Caught the un-normalised superformula,
        // which peaked above 2 and drew twice the size of everything else.
        val shapes = mapOf(
            "circle" to RadialShapes.Circle,
            "squircle" to RadialShapes.superellipse(4.2f),
            "hexagon" to RadialShapes.polygon(6),
            "triangle" to RadialShapes.polygon(3, cornerSoftness = 0f),
            "star" to RadialShapes.star(6),
            "superformula" to RadialShapes.superformula(),
            "flower" to RadialShapes.superformula(m = 6f, n1 = 1f, n2 = 1f, n3 = 1f),
            "sampled" to RadialShapes.sampled(FloatArray(64) { 4.7f + 1.9f * kotlin.math.sin(it * 0.4f) }),
        )
        for ((name, shape) in shapes) {
            var peak = 0f
            var i = 0
            while (i < 2048) {
                val r = shape.radiusAt(i * TWO_PI / 2048)
                if (r > peak) peak = r
                i++
            }
            assertTrue(
                peak in 0.97f..1.03f,
                "$name peaks at $peak; every shape must peak at 1 so they are interchangeable",
            )
        }
    }

    @Test
    fun starStaysStarShapedAtEverySoftness() {
        // Regression: the sharp and smooth terms were in antiphase, so a
        // softness of 0.5 cancelled them and the star collapsed to a circle.
        val points = 6
        for (softness in listOf(0f, 0.25f, 0.5f, 0.75f, 1f)) {
            val star = RadialShapes.star(points, innerRatio = 0.55f, softness = softness)
            var peak = 0f
            var trough = Float.MAX_VALUE
            var i = 0
            while (i < 1024) {
                val r = star.radiusAt(i * TWO_PI / 1024)
                if (r > peak) peak = r
                if (r < trough) trough = r
                i++
            }
            assertTrue(
                peak - trough > 0.35f,
                "softness $softness flattened the star (peak $peak, trough $trough)",
            )
            // A point must sit at theta = 0.
            assertTrue(
                star.radiusAt(0f) > 0.97f,
                "softness $softness moved the point away from theta=0 (${star.radiusAt(0f)})",
            )
        }
    }

    // ---- PolarProfile -------------------------------------------------------

    @Test
    fun profileOutlineIsClosedAndBounded() {
        val (_, params) = paramsAfter(6f, speechFiller(-18f))
        val config = PolarProfileConfig(sampleCount = 128)
        val profile = PolarProfile(config)

        for (shape in listOf(RadialShapes.Circle, RadialShapes.polygon(5), RadialShapes.star(6))) {
            profile.update(shape, params)
            for (r in profile.radii) {
                assertTrue(r.isFinite() && r > 0f, "profile produced radius $r")
            }
            // The outline must close: the last sample and the first must be
            // neighbours, not strangers.
            val step = TWO_PI / profile.sampleCount
            val first = profile.radii.first()
            val last = profile.radii.last()
            val typicalStep = (1 until profile.sampleCount)
                .maxOf { abs(profile.radii[it] - profile.radii[it - 1]) }
            assertTrue(
                abs(last - first) <= typicalStep * 2.5f + 1e-4f,
                "seam at the wrap point: $last -> $first (typical step $typicalStep, angle step $step)",
            )
        }
    }

    @Test
    fun profileKeepsMovingWhileTheRoomIsSilent() {
        // "Subtly alive when idle": the parameters are constant in silence, so
        // the motion has to come from the shared field. This proves it does.
        val rig = PipelineRig()
        val profile = PolarProfile(PolarProfileConfig(sampleCount = 96))
        val fill = silenceFiller()

        var previous: FloatArray? = null
        var totalMovement = 0f
        var largestStep = 0f

        rig.run(6f, fill) { params ->
            profile.update(RadialShapes.Circle, params)
            val current = profile.radii.copyOf()
            previous?.let { old ->
                var frameMovement = 0f
                for (i in current.indices) {
                    val delta = abs(current[i] - old[i])
                    frameMovement += delta
                    if (delta > largestStep) largestStep = delta
                }
                totalMovement += frameMovement / current.size
            }
            previous = current
        }

        assertTrue(totalMovement > 0.02f, "idle outline is frozen (total movement $totalMovement)")
        assertTrue(largestStep < 0.01f, "idle outline jitters (largest single-sample step $largestStep)")
    }

    @Test
    fun profileReactsMoreToLoudAudioThanToSilence() {
        fun movement(fill: (FloatArray) -> Unit): Float {
            val rig = PipelineRig()
            val profile = PolarProfile(PolarProfileConfig(sampleCount = 96))
            var previous: FloatArray? = null
            var total = 0f
            rig.run(8f, fill) { params ->
                profile.update(RadialShapes.Circle, params)
                val current = profile.radii.copyOf()
                previous?.let { old ->
                    var sum = 0f
                    for (i in current.indices) sum += abs(current[i] - old[i])
                    total += sum / current.size
                }
                previous = current
            }
            return total
        }

        val idle = movement(silenceFiller())
        val speaking = movement(speechFiller(-18f))
        assertTrue(
            speaking > idle * 3f,
            "speech should visibly energise the outline: idle=$idle speaking=$speaking",
        )
    }

    // ---- ParticleField ------------------------------------------------------

    @Test
    fun particlesRespectCapacityAndNeverGoBad() {
        val rig = PipelineRig()
        val particles = ParticleField(ParticleFieldConfig(capacity = 64, emissionRate = 500f))
        val emitter = RadialShape { 1f }
        var peak = 0

        rig.run(10f, speechFiller(-12f)) { params ->
            particles.update(params, emitter)
            if (particles.count > peak) peak = particles.count
            for (i in 0 until particles.count) {
                assertTrue(particles.positionX[i].isFinite() && particles.positionY[i].isFinite())
                assertTrue(abs(particles.positionX[i]) < 20f, "particle escaped to ${particles.positionX[i]}")
                assertTrue(particles.remainingLife(i) in 0f..1f)
            }
        }
        assertTrue(peak in 1..64, "capacity was violated or nothing was emitted (peak $peak)")
    }

    @Test
    fun particlesAreRecycledRatherThanLeaked() {
        val rig = PipelineRig()
        val particles = ParticleField(ParticleFieldConfig(capacity = 200, maxLifeSeconds = 0.4f))
        val emitter = RadialShape { 1f }

        rig.run(6f, speechFiller(-14f)) { params -> particles.update(params, emitter) }
        val whileSpeaking = particles.count
        assertTrue(whileSpeaking > 0, "no particles were emitted during speech")

        rig.run(4f, silenceFiller()) { params -> particles.update(params, emitter) }
        assertTrue(
            particles.count < whileSpeaking,
            "particles were not recycled once the room went quiet ($whileSpeaking -> ${particles.count})",
        )
    }
}
