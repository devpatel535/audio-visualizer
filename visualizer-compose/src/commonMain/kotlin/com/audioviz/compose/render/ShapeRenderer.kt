package com.audioviz.compose.render

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.DrawScope
import com.audioviz.core.anim.MotionField
import com.audioviz.core.anim.VisualParams

/**
 * Everything a renderer is given for one frame.
 *
 * Note what is *not* here: no microphone, no decibels, no FFT, no sample rate,
 * no analyzer configuration. A renderer cannot reach the audio layer even by
 * accident, which is what makes swapping geometry a self-contained change.
 */
class VisualFrame internal constructor() {

    /** Normalized animation parameters for this frame. */
    lateinit var params: VisualParams
        internal set

    /** Colours already resolved for this frame from the palette. */
    lateinit var palette: RenderPalette
        internal set

    /** Layout centre in pixels, including the controller's drift. */
    var center: Offset = Offset.Zero
        internal set

    /** The size the geometry should be built around, in pixels. */
    var referenceRadius: Float = 0f
        internal set

    /** Full canvas size in pixels. */
    var size: Size = Size.Zero
        internal set

    /** Device pixels per dp, for anything that must stay a fixed physical size. */
    var density: Float = 1f
        internal set

    /** Shortcut for `params.field`, the shared coherent noise. */
    val field: MotionField get() = params.field

    /** Shortcut for the energy-warped animation clock, in seconds. */
    val time: Float get() = params.time

    /** Shortcut for the clamped frame delta, in seconds. */
    val deltaTime: Float get() = params.deltaTime
}

/**
 * Draws one visual object from a set of animation parameters.
 *
 * ### The contract
 * A renderer receives [VisualFrame] and draws. That is the entire interface.
 * It decides for itself what "deformation" or "turbulence" means for its
 * geometry — a blob displaces its outline, a waveform changes its amplitude, a
 * particle system changes its emission — and the audio and animation layers
 * never learn which it was.
 *
 * ### Implementing one
 * ```
 * class TriangleRenderer : ShapeRenderer {
 *     private val path = Path()
 *     override fun DrawScope.render(frame: VisualFrame) {
 *         val p = frame.params
 *         path.reset()
 *         // ...build geometry from p.deformation, p.scale, frame.field...
 *         drawPath(path, frame.palette.core)
 *     }
 * }
 * ```
 *
 * ### Rules
 * - Allocate in the constructor or in [onSurfaceChanged], never in [render].
 *   Reuse `Path`, arrays and buffers; the loop runs 60-120 times a second.
 * - Never smooth anything yourself. Every value in [VisualParams] is already
 *   filtered, clamped and frame-rate compensated; adding another filter only
 *   adds lag.
 * - Treat [VisualParams] as read-only.
 */
interface ShapeRenderer {

    /**
     * Called whenever the canvas size or density changes, and once before the
     * first frame. Size-dependent buffers belong here.
     */
    fun onSurfaceChanged(size: Size, density: Float) {}

    /** Draws one frame. */
    fun DrawScope.render(frame: VisualFrame)
}

/**
 * Draws several renderers into one visual, back to front.
 *
 * Layering is how the system builds something that reads as a single object out
 * of independent parts: an aura behind, the body in the middle, detail in
 * front. Because every layer is driven by the same [VisualParams] and the same
 * [MotionField], they move together rather than merely coexisting.
 */
class CompositeRenderer(
    private val layers: List<ShapeRenderer>,
) : ShapeRenderer {

    constructor(vararg layers: ShapeRenderer) : this(layers.toList())

    override fun onSurfaceChanged(size: Size, density: Float) {
        layers.forEach { it.onSurfaceChanged(size, density) }
    }

    override fun DrawScope.render(frame: VisualFrame) {
        for (layer in layers) {
            with(layer) { render(frame) }
        }
    }
}
