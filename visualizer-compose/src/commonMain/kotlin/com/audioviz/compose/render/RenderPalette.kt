package com.audioviz.compose.render

import androidx.compose.ui.graphics.Color
import com.audioviz.core.anim.VisualParams
import com.audioviz.core.color.VisualPalette
import com.audioviz.core.util.clamp01

/**
 * The palette sampled for one frame.
 *
 * Sampling happens once per frame rather than once per drawn element: a
 * renderer with 128 outline segments and 200 particles would otherwise pay for
 * 300 palette lookups, and every one of them would be identical.
 *
 * [core], [edge] and [highlight] are three points along the same gradient, a
 * short distance apart. Using neighbouring samples rather than unrelated
 * colours is what keeps a multi-layer visual looking lit rather than painted.
 */
class RenderPalette internal constructor(
    private val source: VisualPalette,
) {
    private val scratch = FloatArray(4)

    /** Body colour: the palette at the current mix. */
    var core: Color = Color.Transparent
        private set

    /** Slightly further along the gradient; use for rims and leading edges. */
    var edge: Color = Color.Transparent
        private set

    /** The brightest sample; use sparingly, for specular detail. */
    var highlight: Color = Color.Transparent
        private set

    /** Deepest sample; use for interior shadow and the far side of a gradient. */
    var shadow: Color = Color.Transparent
        private set

    /** Glow colour, from the palette's separate glow ramp. */
    var glow: Color = Color.Transparent
        private set

    /** The configured background. */
    var background: Color = Color.Black
        private set

    internal fun resolve(params: VisualParams) {
        val mix = clamp01(params.colorMix)
        val lift = params.brightness

        core = sample(source.gradient, mix, lift * 0.35f)
        edge = sample(source.gradient, clamp01(mix + 0.14f), lift * 0.55f)
        highlight = sample(source.gradient, clamp01(mix + 0.30f), lift * 0.75f)
        shadow = sample(source.gradient, clamp01(mix - 0.22f), 0f)
        glow = sample(source.glow, clamp01(mix * 0.85f + params.glow * 0.35f), lift * 0.5f)
        background = Color(source.backgroundArgb)
    }

    /**
     * Samples [palette] at [position] and lifts the result toward white by
     * [lift].
     *
     * Lifting rather than switching to a second palette means brightness and
     * hue stay independent: audio can make the object *brighter* without also
     * making it a different colour, which is the difference between a premium
     * treatment and a disco light.
     */
    private fun sample(palette: com.audioviz.core.color.Palette, position: Float, lift: Float): Color {
        palette.sample(position, scratch)
        val l = clamp01(lift)
        return Color(
            red = clamp01(scratch[0] + (1f - scratch[0]) * l),
            green = clamp01(scratch[1] + (1f - scratch[1]) * l),
            blue = clamp01(scratch[2] + (1f - scratch[2]) * l),
            alpha = clamp01(scratch[3]),
        )
    }
}
