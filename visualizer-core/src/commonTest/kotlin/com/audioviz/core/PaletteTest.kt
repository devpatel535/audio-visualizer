package com.audioviz.core

import com.audioviz.core.color.ColorStop
import com.audioviz.core.color.Oklab
import com.audioviz.core.color.Palette
import com.audioviz.core.color.VisualPalette
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PaletteTest {

    @Test
    fun oklabRoundTripsThroughSrgb() {
        val lab = FloatArray(3)
        val rgb = FloatArray(3)
        var channel = 0
        while (channel < 3) {
            var i = 0
            while (i <= 10) {
                val v = i / 10f
                val r = if (channel == 0) v else 0.3f
                val g = if (channel == 1) v else 0.4f
                val b = if (channel == 2) v else 0.6f
                Oklab.linearRgbToOklab(r, g, b, lab)
                Oklab.oklabToLinearRgb(lab[0], lab[1], lab[2], rgb)
                assertEquals(r, rgb[0], 1e-3f)
                assertEquals(g, rgb[1], 1e-3f)
                assertEquals(b, rgb[2], 1e-3f)
                i++
            }
            channel++
        }
    }

    @Test
    fun paletteHitsItsEndpoints() {
        val palette = Palette(
            listOf(
                ColorStop(0f, 0xFF102040.toInt()),
                ColorStop(1f, 0xFFA0D8FF.toInt()),
            ),
        )
        val out = FloatArray(4)

        palette.sample(0f, out)
        assertEquals(0x10 / 255f, out[0], 0.02f)
        assertEquals(0x20 / 255f, out[1], 0.02f)
        assertEquals(0x40 / 255f, out[2], 0.02f)

        palette.sample(1f, out)
        assertEquals(0xA0 / 255f, out[0], 0.02f)
        assertEquals(0xD8 / 255f, out[1], 0.02f)
        assertEquals(0xFF / 255f, out[2], 0.02f)
    }

    @Test
    fun paletteIsSmoothAndNeverLeavesTheGamut() {
        val palette = VisualPalette.OceanBlue.gradient
        val out = FloatArray(4)
        val previous = FloatArray(4)
        var largestStep = 0f

        var i = 0
        while (i <= 1000) {
            palette.sample(i / 1000f, out)
            for (c in 0 until 4) {
                assertTrue(out[c] in 0f..1f, "channel $c left the gamut: ${out[c]}")
            }
            if (i > 0) {
                for (c in 0 until 3) {
                    val step = abs(out[c] - previous[c])
                    if (step > largestStep) largestStep = step
                }
            }
            out.copyInto(previous)
            i++
        }
        assertTrue(largestStep < 0.03f, "palette has a visible discontinuity (step $largestStep)")
    }

    @Test
    fun defaultBluePaletteBrightensMonotonically() {
        // The intended aesthetic: dark blue at rest rising to light blue at
        // peak, with lightness increasing the whole way and no dip in the middle
        // (which naive sRGB interpolation would produce).
        val palette = VisualPalette.OceanBlue.gradient
        val out = FloatArray(4)
        var previousLuma = -1f
        var i = 0
        while (i <= 100) {
            palette.sample(i / 100f, out)
            val luma = 0.2126f * out[0] + 0.7152f * out[1] + 0.0722f * out[2]
            assertTrue(luma >= previousLuma - 1e-3f, "lightness dipped at t=${i / 100f}")
            previousLuma = luma
            i++
        }
        assertTrue(previousLuma > 0.6f, "the bright end is not bright enough: $previousLuma")
    }

    @Test
    fun everyBundledPaletteIsUsable() {
        val out = FloatArray(4)
        for (treatment in listOf(VisualPalette.OceanBlue, VisualPalette.Ember, VisualPalette.Graphite)) {
            for (p in listOf(treatment.gradient, treatment.glow)) {
                for (i in 0..20) {
                    p.sample(i / 20f, out)
                    for (c in 0 until 4) assertTrue(out[c].isFinite() && out[c] in 0f..1f)
                }
            }
        }
    }
}
