package com.audioviz.core.geometry

import com.audioviz.core.util.TWO_PI
import com.audioviz.core.util.wrap
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/**
 * The base outline of a closed shape, expressed as radius over angle.
 *
 * This is the *only* thing that distinguishes a circle from a squircle, a
 * hexagon, a star or a traced logo in this system. Everything downstream —
 * deformation, turbulence, band harmonics, particle emission, glow — operates on
 * the profile, never on the definition, so replacing the shape is a one-line
 * change with no effect on the audio or animation layers.
 *
 * @see PolarProfile
 */
fun interface RadialShape {
    /**
     * Base radius at [theta] radians, as a multiple of the reference radius.
     * A value of 1 traces the unit circle. Must be 2*pi-periodic and positive.
     */
    fun radiusAt(theta: Float): Float
}

/** Ready-made [RadialShape] implementations. Each is stateless and allocation-free. */
object RadialShapes {

    /** The unit circle. Present as one option among many, not as the foundation. */
    val Circle: RadialShape = RadialShape { 1f }

    /**
     * Superellipse / squircle. `exponent = 2` is a circle, `4` is the classic
     * app-icon squircle, large values approach a square.
     */
    fun superellipse(exponent: Float = 4f): RadialShape = RadialShape { theta ->
        val c = abs(cos(theta)).pow(exponent)
        val s = abs(sin(theta)).pow(exponent)
        (c + s).pow(-1f / exponent)
    }

    /**
     * Regular polygon with [sides] sides and optionally rounded corners.
     *
     * @param cornerSoftness 0 = sharp corners, 1 = fully rounded into a circle.
     */
    fun polygon(sides: Int, cornerSoftness: Float = 0.12f): RadialShape {
        require(sides >= 3) { "A polygon needs at least 3 sides" }
        val segment = TWO_PI / sides
        val apothem = cos(segment * 0.5f)
        val softness = cornerSoftness.coerceIn(0f, 1f)
        return RadialShape { theta ->
            val local = wrap(theta, segment) - segment * 0.5f
            val sharp = apothem / cos(local)
            sharp + (1f - sharp) * softness
        }
    }

    /**
     * Star with [points] points. [innerRatio] is the valley radius as a
     * fraction of the peak radius.
     */
    fun star(points: Int, innerRatio: Float = 0.55f, softness: Float = 0.35f): RadialShape {
        require(points >= 3) { "A star needs at least 3 points" }
        val ratio = innerRatio.coerceIn(0.05f, 0.99f)
        val blend = softness.coerceIn(0f, 1f)
        return RadialShape { theta ->
            // Softness blends the sharp triangular profile toward a sinusoid.
            val phase = theta * points
            val sharp = 1f - (1f - ratio) * (abs(wrap(phase, TWO_PI) - PI.toFloat()) / PI.toFloat())
            val smooth = ratio + (1f - ratio) * (0.5f + 0.5f * cos(phase))
            sharp + (smooth - sharp) * blend
        }
    }

    /**
     * Gielis superformula — one equation that covers an enormous family of
     * organic outlines. Good starting points:
     *
     *  - `m=3, n1=5, n2=18, n3=18` — rounded triangle
     *  - `m=6, n1=1, n2=1,  n3=1`  — flower
     *  - `m=5, n1=2, n2=7,  n3=7`  — soft pentagon
     */
    fun superformula(
        m: Float = 5f,
        n1: Float = 2f,
        n2: Float = 7f,
        n3: Float = 7f,
        a: Float = 1f,
        b: Float = 1f,
    ): RadialShape = RadialShape { theta ->
        val t = m * theta / 4f
        val part1 = abs(cos(t) / a).pow(n2)
        val part2 = abs(sin(t) / b).pow(n3)
        (part1 + part2).pow(-1f / n1)
    }

    /**
     * An arbitrary outline supplied as a table of radii sampled at even angles.
     *
     * This is the bridge for shapes that have no closed form — an SVG path, a
     * traced logo, a silhouette from a designer. Sample the geometry once into
     * an array and the whole animation system applies to it unchanged.
     */
    fun sampled(radii: FloatArray): RadialShape {
        require(radii.size >= 3) { "Need at least 3 samples" }
        val n = radii.size
        return RadialShape { theta ->
            val x = wrap(theta, TWO_PI) / TWO_PI * n
            val i = x.toInt() % n
            val j = (i + 1) % n
            val f = x - x.toInt()
            radii[i] + (radii[j] - radii[i]) * f
        }
    }

    /** Blends two shapes; useful for morphing one geometry into another. */
    fun blend(from: RadialShape, to: RadialShape, amount: Float): RadialShape =
        RadialShape { theta ->
            val a = from.radiusAt(theta)
            a + (to.radiusAt(theta) - a) * amount.coerceIn(0f, 1f)
        }
}
