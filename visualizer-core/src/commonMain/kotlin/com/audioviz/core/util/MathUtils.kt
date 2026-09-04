package com.audioviz.core.util

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sign

const val TWO_PI: Float = (2.0 * PI).toFloat()
const val LN2: Float = 0.6931472f

/** Linear interpolation. [t] is not clamped. */
@Suppress("NOTHING_TO_INLINE")
inline fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

/** Inverse lerp, clamped to `0..1`. Returns 0 when the range is degenerate. */
fun inverseLerp(a: Float, b: Float, v: Float): Float {
    val d = b - a
    return if (abs(d) < 1e-9f) 0f else ((v - a) / d).coerceIn(0f, 1f)
}

@Suppress("NOTHING_TO_INLINE")
inline fun clamp01(v: Float): Float = if (v < 0f) 0f else if (v > 1f) 1f else v

/** Hermite `3t^2 - 2t^3` ease over `edge0..edge1`. The classic "no hard edges" ramp. */
fun smoothStep(edge0: Float, edge1: Float, x: Float): Float {
    val t = inverseLerp(edge0, edge1, x)
    return t * t * (3f - 2f * t)
}

/** Quintic ease, `6t^5 - 15t^4 + 10t^3`. Zero 1st *and* 2nd derivative at both ends. */
fun smootherStep(edge0: Float, edge1: Float, x: Float): Float {
    val t = inverseLerp(edge0, edge1, x)
    return t * t * t * (t * (t * 6f - 15f) + 10f)
}

/**
 * Gamma response curve on `0..1`.
 *
 * `gamma < 1` expands quiet input (more visible reaction to soft speech);
 * `gamma > 1` compresses it (only loud input moves the visual).
 */
fun gammaCurve(x: Float, gamma: Float): Float =
    if (gamma == 1f) clamp01(x) else clamp01(x).pow(gamma)

/**
 * Soft-knee compressor curve on `0..1`.
 *
 * Below [knee] the response is linear; above it the curve bends over towards 1
 * so that shouting does not slam every parameter to its maximum. This is what
 * keeps the visual "controlled" at high input levels without a hard clamp.
 */
fun softKnee(x: Float, knee: Float = 0.7f, ceiling: Float = 1f): Float {
    val v = clamp01(x)
    if (v <= knee) return v
    val over = (v - knee) / (1f - knee).coerceAtLeast(1e-6f)
    val compressed = 1f - exp(-2.2f * over)
    return knee + (ceiling - knee) * compressed
}

/** Symmetric signed power curve, keeps the sign of [x] while shaping magnitude. */
fun signedPow(x: Float, exponent: Float): Float = abs(x).pow(exponent) * sign(x)

/** Amplitude (0..1 linear) to decibels, floored so that silence is finite. */
fun amplitudeToDb(amplitude: Float, floorDb: Float = -100f): Float {
    val a = amplitude.coerceAtLeast(1e-7f)
    val db = 20f * (ln(a) / 2.302585f)
    return if (db < floorDb) floorDb else db
}

/** Decibels back to linear amplitude. */
fun dbToAmplitude(db: Float): Float = 10f.pow(db / 20f)

/** Wraps [v] into `0 until period`, correct for negative inputs. */
fun wrap(v: Float, period: Float): Float {
    val m = v % period
    return if (m < 0f) m + period else m
}

/** True when [v] is finite; used to keep a bad audio buffer from poisoning state. */
fun isFinite(v: Float): Boolean = !v.isNaN() && !v.isInfinite()

/** Replaces NaN/Inf with [fallback]. */
fun sanitize(v: Float, fallback: Float = 0f): Float = if (isFinite(v)) v else fallback
