package com.audioviz.core.anim

/**
 * The complete, normalized description of "how the visual should look right
 * now" — and the only thing a renderer is allowed to see.
 *
 * This interface is the shape-agnostic contract of the whole system. Nothing
 * here mentions circles, radii, microphones, decibels or FFTs: it is a
 * vocabulary of *visual intent*. A blob, a polygon, an SVG path, a waveform, a
 * particle field or a fragment shader can each interpret the same parameters in
 * whatever way suits its geometry, and swapping one for another requires no
 * change anywhere upstream.
 *
 * Every scalar is in `0..1` unless documented otherwise, already smoothed,
 * clamped and frame-rate compensated. A renderer should never need to add its
 * own smoothing on top.
 */
interface VisualParams {

    // ---- Primary drives ---------------------------------------------------

    /** Sustained overall energy. Spring-driven, so it has weight. `0..1`. */
    val intensity: Float

    /** Short transient punch from onsets and consonants. `0..1`. */
    val pulse: Float

    /** Very slow "session mood". Moves over seconds, not syllables. `0..1`. */
    val energy: Float

    /** How idle the visual currently is: 1 in silence, 0 during active speech. */
    val idle: Float

    /** Layered breathing oscillator in `0..1`; the source of idle life. */
    val breath: Float

    // ---- Form -------------------------------------------------------------

    /** Multiplier around 1.0. Deliberately a small contributor. */
    val scale: Float

    /** How far the geometry departs from its base form. `0..1`. */
    val deformation: Float

    /** High-frequency roughness on top of [deformation]. `0..1`. */
    val turbulence: Float

    /** Secondary/detail motion amount: inner rings, ripples, trailing shapes. `0..1`. */
    val detail: Float

    /** Amplitude for wave/ribbon style renderers. `0..1`. */
    val waveAmplitude: Float

    /** Suggested particle emission rate. `0..1`. */
    val emission: Float

    // ---- Placement --------------------------------------------------------

    /** Accumulated rotation in radians. Always advances; never snaps back. */
    val rotation: Float

    /** Current angular velocity in radians/second. */
    val rotationVelocity: Float

    /** Horizontal drift as a fraction of the layout's reference size. `-1..1`. */
    val driftX: Float

    /** Vertical drift as a fraction of the layout's reference size. `-1..1`. */
    val driftY: Float

    // ---- Light and colour -------------------------------------------------

    /** Glow / bloom strength. `0..1`. */
    val glow: Float

    /** Overall opacity. `0..1`. */
    val opacity: Float

    /** Suggested softness/blur radius as a fraction of the reference size. `0..1`. */
    val blur: Float

    /** Position along the configured palette, slew-limited so it can never flash. `0..1`. */
    val colorMix: Float

    /** Additional lightness applied on top of the palette sample. `0..1`. */
    val brightness: Float

    // ---- Raw-ish signals, for renderers that want them ---------------------

    /** Gated, normalized instantaneous loudness. `0..1`. */
    val level: Float

    /** Noise-gate value; 0 means the room is quiet. `0..1`. */
    val gate: Float

    /** Spectral brightness. `0..1`. */
    val spectralBrightness: Float

    /** Number of frequency bands available. */
    val bandCount: Int

    /** Normalized energy of band [index], low frequency first. `0..1`. */
    fun band(index: Int): Float

    /** Mean of the lower third of the spectrum. */
    val lowEnergy: Float

    /** Mean of the middle third of the spectrum. */
    val midEnergy: Float

    /** Mean of the upper third of the spectrum. */
    val highEnergy: Float

    // ---- Time -------------------------------------------------------------

    /**
     * Energy-warped animation clock in seconds. Advances faster when the audio
     * is lively, which makes a shape feel *excited* rather than merely larger.
     * Use this instead of wall-clock time for anything periodic.
     */
    val time: Float

    /** Seconds since the previous update, already clamped to a sane range. */
    val deltaTime: Float

    /** The shared coherent noise field. Sample it for any spatial variation. */
    val field: MotionField
}

/**
 * Mutable [VisualParams] owned by the [AnimationController].
 *
 * A single instance is reused for the lifetime of the controller and handed to
 * the renderer by reference, so a running visualizer allocates nothing per
 * frame. Renderers must treat it as read-only through the [VisualParams] view.
 */
class MutableVisualParams internal constructor(
    override val field: MotionField,
    bandCapacity: Int,
) : VisualParams {

    override var intensity: Float = 0f
        internal set
    override var pulse: Float = 0f
        internal set
    override var energy: Float = 0f
        internal set
    override var idle: Float = 1f
        internal set
    override var breath: Float = 0.5f
        internal set

    override var scale: Float = 1f
        internal set
    override var deformation: Float = 0f
        internal set
    override var turbulence: Float = 0f
        internal set
    override var detail: Float = 0f
        internal set
    override var waveAmplitude: Float = 0f
        internal set
    override var emission: Float = 0f
        internal set

    override var rotation: Float = 0f
        internal set
    override var rotationVelocity: Float = 0f
        internal set
    override var driftX: Float = 0f
        internal set
    override var driftY: Float = 0f
        internal set

    override var glow: Float = 0f
        internal set
    override var opacity: Float = 1f
        internal set
    override var blur: Float = 0f
        internal set
    override var colorMix: Float = 0f
        internal set
    override var brightness: Float = 0f
        internal set

    override var level: Float = 0f
        internal set
    override var gate: Float = 0f
        internal set
    override var spectralBrightness: Float = 0f
        internal set

    override var time: Float = 0f
        internal set
    override var deltaTime: Float = 0f
        internal set

    private val bandValues = FloatArray(bandCapacity)
    override var bandCount: Int = 0
        internal set

    override fun band(index: Int): Float =
        if (index in 0 until bandCount) bandValues[index] else 0f

    override var lowEnergy: Float = 0f
        internal set
    override var midEnergy: Float = 0f
        internal set
    override var highEnergy: Float = 0f
        internal set

    /** Copies band energies in without allocating. */
    internal fun setBands(source: FloatArray) {
        val n = minOf(source.size, bandValues.size)
        for (i in 0 until n) bandValues[i] = source[i]
        bandCount = n
    }
}
