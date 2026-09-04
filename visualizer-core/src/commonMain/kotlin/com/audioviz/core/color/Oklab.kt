package com.audioviz.core.color

import kotlin.math.pow

/**
 * sRGB <-> Oklab conversion.
 *
 * Interpolating two colours in sRGB drags the path through a desaturated,
 * muddy middle — a deep navy blended to a light cyan goes grey halfway. Oklab is
 * perceptually uniform, so the same blend stays saturated and the *rate* of
 * apparent change is constant, which is exactly what a slow audio-driven colour
 * drift needs: no dead zone in the middle, no rush at the ends.
 *
 * Reference: Björn Ottosson, "A perceptual color space for image processing".
 */
object Oklab {

    /** sRGB electro-optical transfer function (gamma-encoded 0..1 to linear). */
    fun srgbToLinear(c: Float): Float =
        if (c <= 0.04045f) c / 12.92f else ((c + 0.055f) / 1.055f).pow(2.4f)

    /** Inverse of [srgbToLinear]. */
    fun linearToSrgb(c: Float): Float =
        if (c <= 0.0031308f) c * 12.92f else 1.055f * c.pow(1f / 2.4f) - 0.055f

    /**
     * Converts linear-light RGB to Oklab.
     * @param out receives `[L, a, b]`.
     */
    fun linearRgbToOklab(r: Float, g: Float, b: Float, out: FloatArray) {
        val l = 0.4122214708f * r + 0.5363325363f * g + 0.0514459929f * b
        val m = 0.2119034982f * r + 0.6806995451f * g + 0.1073969566f * b
        val s = 0.0883024619f * r + 0.2817188376f * g + 0.6299787005f * b

        val lc = cbrt(l)
        val mc = cbrt(m)
        val sc = cbrt(s)

        out[0] = 0.2104542553f * lc + 0.7936177850f * mc - 0.0040720468f * sc
        out[1] = 1.9779984951f * lc - 2.4285922050f * mc + 0.4505937099f * sc
        out[2] = 0.0259040371f * lc + 0.7827717662f * mc - 0.8086757660f * sc
    }

    /**
     * Converts Oklab back to linear-light RGB.
     * @param out receives `[r, g, b]`, clamped to `0..1`.
     */
    fun oklabToLinearRgb(lightness: Float, a: Float, b: Float, out: FloatArray) {
        val lc = lightness + 0.3963377774f * a + 0.2158037573f * b
        val mc = lightness - 0.1055613458f * a - 0.0638541728f * b
        val sc = lightness - 0.0894841775f * a - 1.2914855480f * b

        val l = lc * lc * lc
        val m = mc * mc * mc
        val s = sc * sc * sc

        out[0] = (4.0767416621f * l - 3.3077115913f * m + 0.2309699292f * s).coerceIn(0f, 1f)
        out[1] = (-1.2684380046f * l + 2.6097574011f * m - 0.3413193965f * s).coerceIn(0f, 1f)
        out[2] = (-0.0041960863f * l - 0.7034186147f * m + 1.7076147010f * s).coerceIn(0f, 1f)
    }

    /** Cube root that tolerates the small negative values float error can produce. */
    private fun cbrt(v: Float): Float =
        if (v < 0f) -((-v).pow(1f / 3f)) else v.pow(1f / 3f)
}
