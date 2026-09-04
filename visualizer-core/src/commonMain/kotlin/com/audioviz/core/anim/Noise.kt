package com.audioviz.core.anim

import com.audioviz.core.util.wrap
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/**
 * Classic 3D Perlin gradient noise.
 *
 * Chosen over a hash-per-frame random walk because it is *coherent*: nearby
 * points in space and time give nearby values, which is the whole reason the
 * motion reads as organic rather than as noise. The permutation table is built
 * once in the constructor; sampling allocates nothing and uses no trigonometry.
 *
 * The lattice repeats every 256 units on every axis, which the time axis in
 * [MotionField] exploits to wrap its phase without a visible seam.
 */
class PerlinNoise3D(seed: Int = 1337) {

    private val permutation = IntArray(512)

    init {
        val p = IntArray(256) { it }
        // Fisher-Yates with a small LCG so the field is identical on every
        // platform — important for reproducible tests and screenshots.
        var state = if (seed == 0) 1 else seed
        for (i in 255 downTo 1) {
            state = state * 1_664_525 + 1_013_904_223
            val j = ((state ushr 8) and 0x7FFFFF) % (i + 1)
            val tmp = p[i]
            p[i] = p[j]
            p[j] = tmp
        }
        for (i in 0 until 512) permutation[i] = p[i and 255]
    }

    /** Samples the field. Result is approximately `-1..1`. */
    fun noise(x: Float, y: Float, z: Float): Float {
        val xi = floor(x).toInt() and 255
        val yi = floor(y).toInt() and 255
        val zi = floor(z).toInt() and 255

        val xf = x - floor(x)
        val yf = y - floor(y)
        val zf = z - floor(z)

        val u = fade(xf)
        val v = fade(yf)
        val w = fade(zf)

        val a = permutation[xi] + yi
        val aa = permutation[a and 255] + zi
        val ab = permutation[(a + 1) and 255] + zi
        val b = permutation[(xi + 1) and 255] + yi
        val ba = permutation[b and 255] + zi
        val bb = permutation[(b + 1) and 255] + zi

        val x1 = lerpF(
            grad(permutation[aa and 255], xf, yf, zf),
            grad(permutation[ba and 255], xf - 1f, yf, zf),
            u,
        )
        val x2 = lerpF(
            grad(permutation[ab and 255], xf, yf - 1f, zf),
            grad(permutation[bb and 255], xf - 1f, yf - 1f, zf),
            u,
        )
        val y1 = lerpF(x1, x2, v)

        val x3 = lerpF(
            grad(permutation[(aa + 1) and 255], xf, yf, zf - 1f),
            grad(permutation[(ba + 1) and 255], xf - 1f, yf, zf - 1f),
            u,
        )
        val x4 = lerpF(
            grad(permutation[(ab + 1) and 255], xf, yf - 1f, zf - 1f),
            grad(permutation[(bb + 1) and 255], xf - 1f, yf - 1f, zf - 1f),
            u,
        )
        val y2 = lerpF(x3, x4, v)

        // Perlin 3D peaks near +/-0.866; rescale so callers can assume -1..1.
        return (lerpF(y1, y2, w) * NORMALIZE).coerceIn(-1f, 1f)
    }

    private companion object {
        const val NORMALIZE = 1.1547005f

        fun fade(t: Float): Float = t * t * t * (t * (t * 6f - 15f) + 10f)

        fun lerpF(a: Float, b: Float, t: Float): Float = a + t * (b - a)

        fun grad(hash: Int, x: Float, y: Float, z: Float): Float {
            val h = hash and 15
            val u = if (h < 8) x else y
            val v = if (h < 4) y else if (h == 12 || h == 14) x else z
            return (if (h and 1 == 0) u else -u) + (if (h and 2 == 0) v else -v)
        }
    }
}

/**
 * The shared, animated noise field that every renderer samples.
 *
 * This is the single most important piece for "one coherent living visual
 * rather than several independent animations": edge displacement, internal
 * detail, particle drift and position wander all read from the *same* evolving
 * field, so they move together the way parts of one object do.
 *
 * ### Layered time
 * Each octave carries its own phase advancing at its own rate
 * (`rate_i = timeRate * timeLacunarity^i`). Fine detail therefore shimmers
 * faster than the large-scale shape drifts — the signature of organic motion,
 * and something a single global time value cannot produce.
 *
 * ### Seamless closed shapes
 * [loop] samples the field along a circle in noise space, so `theta` and
 * `theta + 2*pi` land on the same point by construction. A closed outline
 * deformed by it can never show a seam, whatever the parameters.
 */
class MotionField(
    var octaves: Int = 3,
    var baseFrequency: Float = 0.55f,
    var lacunarity: Float = 2.05f,
    var gain: Float = 0.5f,
    var timeRate: Float = 0.22f,
    var timeLacunarity: Float = 1.85f,
    seed: Int = 90_210,
) {
    private val noise = PerlinNoise3D(seed)
    private val phases = FloatArray(MAX_OCTAVES) { it * 13.7f }

    /** Seconds of field time elapsed, before per-octave rate multiplication. */
    var elapsed: Float = 0f
        private set

    /**
     * Advances every octave phase.
     *
     * @param speed multiplier from the controller; louder audio makes the whole
     *   field evolve faster, which is far more convincing than merely making it
     *   bigger.
     */
    fun advance(dt: Float, speed: Float = 1f) {
        elapsed = wrap(elapsed + dt * speed, LOOP_PERIOD)
        var rate = timeRate
        for (i in 0 until MAX_OCTAVES) {
            phases[i] = wrap(phases[i] + dt * speed * rate, LOOP_PERIOD)
            rate *= timeLacunarity
        }
    }

    /**
     * Seamless fractal noise around a closed contour.
     *
     * @param theta angle in radians; `theta` and `theta + 2*pi` give the same value.
     * @param layer decorrelates independent uses of the field (outer edge vs.
     *   inner detail vs. a second ring) without needing extra noise objects.
     */
    fun loop(theta: Float, layer: Float = 0f, octaves: Int = this.octaves): Float =
        loopAt(cos(theta), sin(theta), layer, octaves)

    /**
     * [loop] for a caller that already holds `cos(theta)` and `sin(theta)`.
     *
     * A renderer sampling a closed outline evaluates the same fixed set of
     * angles every frame, so it can precompute the table once and rotate it
     * with two multiplies per sample instead of paying for a sine and a cosine
     * per sample per octave. At 128 samples and two field layers that is ~500
     * transcendental calls per frame removed.
     */
    fun loopAt(
        cosTheta: Float,
        sinTheta: Float,
        layer: Float = 0f,
        octaves: Int = this.octaves,
    ): Float {
        var amplitude = 1f
        var radius = baseFrequency
        var sum = 0f
        var norm = 0f
        val count = octaves.coerceIn(1, MAX_OCTAVES)
        val cosT = cosTheta
        val sinT = sinTheta
        for (o in 0 until count) {
            sum += noise.noise(
                cosT * radius + layer * 17.3f,
                sinT * radius + layer * 31.1f,
                phases[o],
            ) * amplitude
            norm += amplitude
            amplitude *= gain
            radius *= lacunarity
        }
        return sum / norm
    }

    /** Fractal noise along an open parameter, for waveforms and ribbons. */
    fun line(u: Float, layer: Float = 0f, octaves: Int = this.octaves): Float {
        var amplitude = 1f
        var frequency = baseFrequency * 4f
        var sum = 0f
        var norm = 0f
        val count = octaves.coerceIn(1, MAX_OCTAVES)
        for (o in 0 until count) {
            sum += noise.noise(u * frequency, layer * 19.7f + o * 5.3f, phases[o]) * amplitude
            norm += amplitude
            amplitude *= gain
            frequency *= lacunarity
        }
        return sum / norm
    }

    /** Fractal noise over a plane, for particle fields and shader-free grain. */
    fun plane(x: Float, y: Float, layer: Float = 0f, octaves: Int = this.octaves): Float {
        var amplitude = 1f
        var frequency = baseFrequency
        var sum = 0f
        var norm = 0f
        val count = octaves.coerceIn(1, MAX_OCTAVES)
        for (o in 0 until count) {
            sum += noise.noise(x * frequency, y * frequency + layer * 23.9f, phases[o]) * amplitude
            norm += amplitude
            amplitude *= gain
            frequency *= lacunarity
        }
        return sum / norm
    }

    /**
     * A single slowly-wandering scalar in `-1..1`, distinct per [channel].
     * Used for position drift and rotation wobble.
     */
    fun scalar(channel: Float, octaves: Int = 2): Float {
        var amplitude = 1f
        var sum = 0f
        var norm = 0f
        val count = octaves.coerceIn(1, MAX_OCTAVES)
        for (o in 0 until count) {
            sum += noise.noise(channel * 11.3f + o * 3.1f, channel * 7.7f, phases[o] * 0.45f) * amplitude
            norm += amplitude
            amplitude *= gain
        }
        return sum / norm
    }

    private companion object {
        const val MAX_OCTAVES = 6

        /** Perlin's lattice period; wrapping here is invisible. */
        const val LOOP_PERIOD = 256f
    }
}
