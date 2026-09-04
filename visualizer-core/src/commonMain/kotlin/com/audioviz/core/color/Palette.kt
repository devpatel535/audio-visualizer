package com.audioviz.core.color

import com.audioviz.core.util.clamp01

/** A colour stop in a [Palette]. [argb] is `0xAARRGGBB`. */
data class ColorStop(val position: Float, val argb: Int)

/**
 * A gradient sampled by a single `0..1` parameter, interpolated in Oklab and
 * baked into a lookup table.
 *
 * The LUT is built once (Oklab conversion and the sRGB transfer function are far
 * too expensive to run per frame per vertex); sampling is an index plus one
 * linear interpolation, so a renderer can freely sample it per particle or per
 * outline segment.
 *
 * Alpha is interpolated linearly and separately, since it has no perceptual
 * geometry to respect.
 */
class Palette(
    stops: List<ColorStop>,
    private val resolution: Int = 128,
) {
    init {
        require(stops.size >= 2) { "A palette needs at least two stops" }
        require(resolution >= 2) { "resolution must be >= 2" }
    }

    private val lutR = FloatArray(resolution)
    private val lutG = FloatArray(resolution)
    private val lutB = FloatArray(resolution)
    private val lutA = FloatArray(resolution)

    init {
        val sorted = stops.sortedBy { it.position }
        val labA = FloatArray(3)
        val labB = FloatArray(3)
        val rgb = FloatArray(3)

        for (i in 0 until resolution) {
            val t = i.toFloat() / (resolution - 1)

            var upper = sorted.indexOfFirst { it.position >= t }
            if (upper < 0) upper = sorted.lastIndex
            val lower = (upper - 1).coerceAtLeast(0)

            val loStop = sorted[lower]
            val hiStop = sorted[upper]
            val span = hiStop.position - loStop.position
            val local = if (span <= 1e-6f) 0f else ((t - loStop.position) / span).coerceIn(0f, 1f)

            toOklab(loStop.argb, labA)
            toOklab(hiStop.argb, labB)

            Oklab.oklabToLinearRgb(
                labA[0] + (labB[0] - labA[0]) * local,
                labA[1] + (labB[1] - labA[1]) * local,
                labA[2] + (labB[2] - labA[2]) * local,
                rgb,
            )

            lutR[i] = Oklab.linearToSrgb(rgb[0])
            lutG[i] = Oklab.linearToSrgb(rgb[1])
            lutB[i] = Oklab.linearToSrgb(rgb[2])
            lutA[i] = alphaOf(loStop.argb) + (alphaOf(hiStop.argb) - alphaOf(loStop.argb)) * local
        }
    }

    /**
     * Samples the palette.
     *
     * @param t position in `0..1`.
     * @param out receives gamma-encoded `[r, g, b, a]` in `0..1`. Allocation-free.
     */
    fun sample(t: Float, out: FloatArray) {
        val x = clamp01(t) * (resolution - 1)
        val i = x.toInt().coerceIn(0, resolution - 1)
        val j = (i + 1).coerceAtMost(resolution - 1)
        val f = x - i
        out[0] = lutR[i] + (lutR[j] - lutR[i]) * f
        out[1] = lutG[i] + (lutG[j] - lutG[i]) * f
        out[2] = lutB[i] + (lutB[j] - lutB[i]) * f
        out[3] = lutA[i] + (lutA[j] - lutA[i]) * f
    }

    private companion object {
        fun alphaOf(argb: Int): Float = ((argb ushr 24) and 0xFF) / 255f

        fun toOklab(argb: Int, out: FloatArray) {
            val r = Oklab.srgbToLinear(((argb ushr 16) and 0xFF) / 255f)
            val g = Oklab.srgbToLinear(((argb ushr 8) and 0xFF) / 255f)
            val b = Oklab.srgbToLinear((argb and 0xFF) / 255f)
            Oklab.linearRgbToOklab(r, g, b, out)
        }
    }
}

/**
 * The complete colour treatment of a visualizer instance.
 *
 * [gradient] is driven by [com.audioviz.core.anim.VisualParams.colorMix], which
 * is spring-smoothed *and* rate-limited upstream, so a palette can be as
 * high-contrast as you like without any risk of flashing.
 */
class VisualPalette(
    /** Calm-to-energised gradient. Position 0 is silence, 1 is peak energy. */
    val gradient: Palette,

    /** Colour of the glow / bloom, sampled with the same parameter. */
    val glow: Palette,

    /** Page background, as `0xAARRGGBB`. */
    val backgroundArgb: Int,
) {
    companion object {
        /**
         * The reference treatment: deep midnight navy at rest, rising through
         * cobalt to a bright ice-blue at peak. Chosen so the *hue* barely moves
         * while lightness and chroma do the work — the transition reads as the
         * object being lit from within rather than as a colour change.
         */
        val OceanBlue: VisualPalette = VisualPalette(
            gradient = Palette(
                listOf(
                    ColorStop(0.00f, 0xFF0B1B3A.toInt()),
                    ColorStop(0.35f, 0xFF14449E.toInt()),
                    ColorStop(0.70f, 0xFF2E86FF.toInt()),
                    ColorStop(1.00f, 0xFF9FE0FF.toInt()),
                ),
            ),
            glow = Palette(
                listOf(
                    ColorStop(0.00f, 0x330E2A5C),
                    ColorStop(0.50f, 0x662E86FF),
                    ColorStop(1.00f, 0xAAB8ECFF.toInt()),
                ),
            ),
            backgroundArgb = 0xFF04070F.toInt(),
        )

        /** Warm alternative: ember to gold. */
        val Ember: VisualPalette = VisualPalette(
            gradient = Palette(
                listOf(
                    ColorStop(0.00f, 0xFF2A1206.toInt()),
                    ColorStop(0.40f, 0xFF9E3B12.toInt()),
                    ColorStop(0.75f, 0xFFF07022.toInt()),
                    ColorStop(1.00f, 0xFFFFD79A.toInt()),
                ),
            ),
            glow = Palette(
                listOf(
                    ColorStop(0.00f, 0x33341305),
                    ColorStop(0.50f, 0x66F07022),
                    ColorStop(1.00f, 0xAAFFE0B0.toInt()),
                ),
            ),
            backgroundArgb = 0xFF0B0603.toInt(),
        )

        /** Neutral, near-monochrome treatment for a restrained product surface. */
        val Graphite: VisualPalette = VisualPalette(
            gradient = Palette(
                listOf(
                    ColorStop(0.00f, 0xFF1A1D22.toInt()),
                    ColorStop(0.50f, 0xFF5A6470.toInt()),
                    ColorStop(1.00f, 0xFFE7EDF5.toInt()),
                ),
            ),
            glow = Palette(
                listOf(
                    ColorStop(0.00f, 0x2A2A3038),
                    ColorStop(1.00f, 0x99DCE6F2.toInt()),
                ),
            ),
            backgroundArgb = 0xFF07080A.toInt(),
        )
    }
}
