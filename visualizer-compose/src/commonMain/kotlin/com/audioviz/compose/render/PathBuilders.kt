package com.audioviz.compose.render

import androidx.compose.ui.graphics.Path

/**
 * Geometry helpers shared by the bundled renderers.
 *
 * All of them write into a caller-owned [Path] and caller-owned arrays, so a
 * renderer can rebuild its geometry every frame without allocating.
 */
object PathBuilders {

    /**
     * Rotates a precomputed sine/cosine table by [angle] radians.
     *
     * Two transcendental calls for the whole table, via the angle-addition
     * identities, instead of one pair per point. Rotation is applied to the
     * geometry rather than through `DrawScope.rotate` so that gradients and the
     * light direction stay fixed in screen space while the object turns.
     */
    fun rotateTables(
        sourceCos: FloatArray,
        sourceSin: FloatArray,
        angle: Float,
        outCos: FloatArray,
        outSin: FloatArray,
    ) {
        val c = kotlin.math.cos(angle)
        val s = kotlin.math.sin(angle)
        val n = minOf(sourceCos.size, sourceSin.size, outCos.size, outSin.size)
        for (i in 0 until n) {
            val ci = sourceCos[i]
            val si = sourceSin[i]
            outCos[i] = ci * c - si * s
            outSin[i] = si * c + ci * s
        }
    }

    /**
     * Builds a closed cubic spline through evenly spaced polar samples.
     *
     * A polyline through 128 points looks smooth until the shape deforms, at
     * which point the facets become visible along the flatter sections. A
     * Catmull-Rom spline converted to cubic Béziers stays smooth at any sample
     * count, which lets the sample count drop to ~64 on low-end hardware
     * without the outline looking cut from paper.
     *
     * The sample angles are fixed, so their sines and cosines are supplied
     * precomputed — see [com.audioviz.core.geometry.PolarProfile.sampleCosTable].
     * Building a 128-point outline this way costs no transcendental calls at
     * all; computing them inline would cost about a thousand per path, and a
     * composite renderer draws three or four paths a frame. Rotation belongs to
     * the caller, via `DrawScope.rotate`, which the GPU applies for free.
     *
     * @param path reset and filled by this call.
     * @param radii radius per sample, in pixels, one full turn.
     * @param cosTable cosine of each sample angle; same length as [radii].
     * @param sinTable sine of each sample angle; same length as [radii].
     * @param tension 1.0 is a standard Catmull-Rom; lower is tighter.
     */
    fun closedPolarSpline(
        path: Path,
        radii: FloatArray,
        cosTable: FloatArray,
        sinTable: FloatArray,
        centerX: Float,
        centerY: Float,
        tension: Float = 1f,
    ) {
        val n = radii.size
        if (n < 3 || cosTable.size < n || sinTable.size < n) return
        path.reset()

        val factor = tension / 6f
        path.moveTo(centerX + cosTable[0] * radii[0], centerY + sinTable[0] * radii[0])

        for (i in 0 until n) {
            val i0 = if (i == 0) n - 1 else i - 1
            val i1 = i
            val i2 = if (i + 1 == n) 0 else i + 1
            val i3 = if (i + 2 >= n) i + 2 - n else i + 2

            val x0 = centerX + cosTable[i0] * radii[i0]
            val y0 = centerY + sinTable[i0] * radii[i0]
            val x1 = centerX + cosTable[i1] * radii[i1]
            val y1 = centerY + sinTable[i1] * radii[i1]
            val x2 = centerX + cosTable[i2] * radii[i2]
            val y2 = centerY + sinTable[i2] * radii[i2]
            val x3 = centerX + cosTable[i3] * radii[i3]
            val y3 = centerY + sinTable[i3] * radii[i3]

            path.cubicTo(
                x1 + (x2 - x0) * factor,
                y1 + (y2 - y0) * factor,
                x2 - (x3 - x1) * factor,
                y2 - (y3 - y1) * factor,
                x2,
                y2,
            )
        }
        path.close()
    }

    /**
     * Builds an open cubic spline through a list of points.
     *
     * @param count number of valid entries in [xs] and [ys].
     */
    fun openSpline(
        path: Path,
        xs: FloatArray,
        ys: FloatArray,
        count: Int,
        tension: Float = 1f,
    ) {
        if (count < 2) return
        path.reset()
        path.moveTo(xs[0], ys[0])
        if (count == 2) {
            path.lineTo(xs[1], ys[1])
            return
        }
        val factor = tension / 6f
        for (i in 0 until count - 1) {
            val i0 = if (i - 1 < 0) 0 else i - 1
            val i1 = i
            val i2 = i + 1
            val i3 = if (i + 2 > count - 1) count - 1 else i + 2
            path.cubicTo(
                xs[i1] + (xs[i2] - xs[i0]) * factor,
                ys[i1] + (ys[i2] - ys[i0]) * factor,
                xs[i2] - (xs[i3] - xs[i1]) * factor,
                ys[i2] - (ys[i3] - ys[i1]) * factor,
                xs[i2],
                ys[i2],
            )
        }
    }
}
