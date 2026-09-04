package com.audioviz.core.geometry

import com.audioviz.core.anim.VisualParams
import com.audioviz.core.util.TWO_PI
import com.audioviz.core.util.wrap
import kotlin.math.cos
import kotlin.math.sin

/** How a [PolarProfile] turns [VisualParams] into radial displacement. */
data class PolarProfileConfig(
    /** Number of samples around the outline. 96-160 is smooth on any screen. */
    val sampleCount: Int = 128,

    /** Peak radial displacement from the large-scale field, as a fraction of radius. */
    val deformationStrength: Float = 0.22f,

    /** Peak radial displacement from the fine-detail field. */
    val turbulenceStrength: Float = 0.075f,

    /** Peak radial displacement from the frequency-band harmonics. */
    val bandStrength: Float = 0.085f,

    /** First angular harmonic used by band 0. Band `b` uses `firstHarmonic + b`. */
    val firstHarmonic: Int = 2,

    /** Octaves used for the large-scale deformation. */
    val deformationOctaves: Int = 2,

    /** Octaves used for the fine detail. More = rougher, slightly more CPU. */
    val turbulenceOctaves: Int = 4,

    /** Field layer for the large-scale term; change to decorrelate two shapes. */
    val deformationLayer: Float = 0f,

    /** Field layer for the fine detail term. */
    val turbulenceLayer: Float = 1.7f,

    /** Hard bounds on the final radius as a multiple of the base radius. */
    val minRadiusFactor: Float = 0.45f,
    val maxRadiusFactor: Float = 1.85f,

    /**
     * Rotates the deformation pattern around the outline over time, in
     * revolutions per second of animation time. A small non-zero value stops
     * the lobes from looking pinned to fixed screen angles.
     */
    val patternDriftPerSecond: Float = 0.035f,

    /** Maximum bands the harmonic tables are built for. */
    val maxBands: Int = 8,
)

/**
 * Turns a [RadialShape] plus [VisualParams] into a table of radii.
 *
 * Three independent displacement terms are summed, each with a different
 * spatial and temporal character:
 *
 * 1. **Large-scale deformation** — 2 octaves of the shared motion field: the
 *    slow lobes that make the object look like it is breathing and leaning.
 * 2. **Fine turbulence** — 4 octaves of the same field on a different layer.
 *    The field advances higher octaves faster, so this term shimmers while the
 *    first term drifts, and the surface gets detail that appears on consonants.
 * 3. **Band harmonics** — `sin(k*theta)` terms whose amplitudes come from the
 *    frequency bands. The only term locked to integer harmonics, and the one
 *    that makes the shape look like it is *listening* rather than merely
 *    wobbling: low frequencies produce two or three broad lobes, highs produce
 *    many fine ones.
 *
 * All three are 2*pi-periodic by construction, so the outline is always closed
 * and seamless whatever the parameters do.
 *
 * ### Cost
 * The sample angles never change, so `cos`/`sin` for every sample — and for
 * every band harmonic — are computed once in the constructor. Per frame the
 * pattern rotation costs one `cos` and one `sin`, applied to the tables with
 * the angle-addition identities; each band costs one more pair. A 128-sample,
 * 6-band profile therefore performs 14 transcendental calls per frame instead
 * of roughly 1,300. The base shape is evaluated only when the [RadialShape]
 * instance changes, which matters for expensive definitions like the
 * superformula.
 *
 * The result is written into [radii]; nothing is allocated per frame.
 */
class PolarProfile(config: PolarProfileConfig = PolarProfileConfig()) {

    var config: PolarProfileConfig = config
        set(value) {
            val rebuild = value.sampleCount != field.sampleCount ||
                value.firstHarmonic != field.firstHarmonic ||
                value.maxBands != field.maxBands
            field = value
            if (rebuild) buildTables()
        }

    /** Radii for each sample, in units of the reference radius. */
    var radii: FloatArray = FloatArray(config.sampleCount)
        private set

    val sampleCount: Int get() = radii.size

    /** Largest radius produced by the most recent [update]. */
    var maxRadius: Float = 1f
        private set

    /** Smallest radius produced by the most recent [update]. */
    var minRadius: Float = 1f
        private set

    // Precomputed geometry. Rebuilt only when the sample count or harmonics change.
    private var cosTable = FloatArray(0)
    private var sinTable = FloatArray(0)
    private var harmonicCos = emptyArray<FloatArray>()
    private var harmonicSin = emptyArray<FloatArray>()
    private var baseRadii = FloatArray(0)
    private var cachedShape: RadialShape? = null

    // Per-band phase scratch, sized with the harmonic tables.
    private var bandPhaseCos = FloatArray(0)
    private var bandPhaseSin = FloatArray(0)

    init {
        buildTables()
    }

    private fun buildTables() {
        val cfg = config
        val n = cfg.sampleCount
        radii = FloatArray(n)
        cosTable = FloatArray(n)
        sinTable = FloatArray(n)
        baseRadii = FloatArray(n)
        cachedShape = null

        for (i in 0 until n) {
            val theta = i * TWO_PI / n
            cosTable[i] = cos(theta)
            sinTable[i] = sin(theta)
        }

        harmonicCos = Array(cfg.maxBands) { FloatArray(n) }
        harmonicSin = Array(cfg.maxBands) { FloatArray(n) }
        bandPhaseCos = FloatArray(cfg.maxBands)
        bandPhaseSin = FloatArray(cfg.maxBands)
        for (b in 0 until cfg.maxBands) {
            val k = (cfg.firstHarmonic + b).toFloat()
            for (i in 0 until n) {
                val angle = k * i * TWO_PI / n
                harmonicCos[b][i] = cos(angle)
                harmonicSin[b][i] = sin(angle)
            }
        }
    }

    /** Forces the base shape to be re-evaluated on the next [update]. */
    fun invalidateShape() {
        cachedShape = null
    }

    /**
     * Recomputes [radii].
     *
     * @param shape base geometry. Swap this to change the visual form entirely.
     * @param params animation parameters; the profile reads deformation,
     *   turbulence, detail, scale, band energies and the shared field.
     */
    fun update(shape: RadialShape, params: VisualParams) {
        val cfg = config
        val n = radii.size
        val field = params.field

        if (cachedShape !== shape) {
            for (i in 0 until n) baseRadii[i] = shape.radiusAt(i * TWO_PI / n)
            cachedShape = shape
        }

        val deformAmount = cfg.deformationStrength * params.deformation
        val turbulenceAmount = cfg.turbulenceStrength * params.turbulence
        val bandAmount = cfg.bandStrength * params.detail
        val bands = minOf(params.bandCount, cfg.maxBands)

        // One rotation angle per frame, applied to the precomputed tables with
        // the angle-addition identities rather than recomputing every sample.
        val patternPhase = wrap(params.time * cfg.patternDriftPerSecond * TWO_PI, TWO_PI)
        val cosPhase = cos(patternPhase)
        val sinPhase = sin(patternPhase)

        // Per-band phase offsets, so the lobes do not all peak at the same angle.
        var bandWeight = 0f
        if (bands > 0 && bandAmount > 0f) {
            for (b in 0 until bands) {
                val phase = patternPhase * (1f + b * 0.17f) + b * 1.31f
                bandPhaseCos[b] = cos(phase)
                bandPhaseSin[b] = sin(phase)
                bandWeight += 1f
            }
        }

        var maxR = Float.NEGATIVE_INFINITY
        var minR = Float.POSITIVE_INFINITY

        for (i in 0 until n) {
            val ct = cosTable[i]
            val st = sinTable[i]
            // Rotate the sample point into the drifting pattern frame.
            val cosSample = ct * cosPhase - st * sinPhase
            val sinSample = st * cosPhase + ct * sinPhase

            val base = baseRadii[i]

            val large = field.loopAt(cosSample, sinSample, cfg.deformationLayer, cfg.deformationOctaves)
            val fine = field.loopAt(cosSample, sinSample, cfg.turbulenceLayer, cfg.turbulenceOctaves)

            var harmonics = 0f
            if (bandWeight > 0f) {
                for (b in 0 until bands) {
                    // sin(k*theta + phase) from the precomputed sin/cos of k*theta.
                    val s = harmonicSin[b][i] * bandPhaseCos[b] + harmonicCos[b][i] * bandPhaseSin[b]
                    harmonics += params.band(b) * s
                }
                harmonics /= bandWeight
            }

            var r = base * (
                1f +
                    deformAmount * large +
                    turbulenceAmount * fine +
                    bandAmount * harmonics
                )
            r *= params.scale

            val lo = base * cfg.minRadiusFactor
            val hi = base * cfg.maxRadiusFactor
            if (r < lo) r = lo
            if (r > hi) r = hi

            radii[i] = r
            if (r > maxR) maxR = r
            if (r < minR) minR = r
        }

        maxRadius = if (maxR.isFinite()) maxR else 1f
        minRadius = if (minR.isFinite()) minR else 1f
    }

    /** Radius at an arbitrary angle, interpolated from the sampled table. */
    fun radiusAt(theta: Float): Float {
        val n = radii.size
        val x = wrap(theta, TWO_PI) / TWO_PI * n
        val i = x.toInt() % n
        val j = (i + 1) % n
        val f = x - x.toInt()
        return radii[i] + (radii[j] - radii[i]) * f
    }

    /** Cosine of the sample angle at [index], for renderers that plot the outline. */
    fun sampleCos(index: Int): Float = cosTable[index]

    /** Sine of the sample angle at [index]. */
    fun sampleSin(index: Int): Float = sinTable[index]

    /**
     * Cosine table for the sample angles. Renderers building a path from
     * [radii] should use this rather than calling `cos` per point.
     */
    val sampleCosTable: FloatArray get() = cosTable

    /** Sine table for the sample angles. */
    val sampleSinTable: FloatArray get() = sinTable
}
