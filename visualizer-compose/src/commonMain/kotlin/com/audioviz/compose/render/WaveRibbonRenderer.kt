package com.audioviz.compose.render

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import com.audioviz.core.util.TWO_PI
import com.audioviz.core.util.clamp01
import com.audioviz.core.util.lerp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Look and cost of [WaveRibbonRenderer]. */
data class WaveRibbonConfig(
    /** Stacked ribbons. Each is one extra spline per frame. */
    val ribbons: Int = 4,

    /** Points per ribbon. */
    val sampleCount: Int = 96,

    /** Peak deflection as a fraction of the canvas height. */
    val amplitudeFraction: Float = 0.30f,

    /** Width of the ribbon band as a fraction of canvas width. */
    val widthFraction: Float = 0.86f,

    /** Stroke width in dp for the front ribbon. */
    val strokeWidthDp: Float = 2.4f,

    /** Fill the area between the front ribbon and the centre line. */
    val fillBody: Boolean = true,

    /** Fade the ends of each ribbon so it has no hard edge. */
    val featherEnds: Boolean = true,
)

/**
 * A ribbon/waveform renderer, included precisely because it is *not* radial.
 *
 * It reads the same [com.audioviz.core.anim.VisualParams] as the blob and
 * samples the same [com.audioviz.core.anim.MotionField], but there is no
 * centre, no radius and no closed outline anywhere in it. Dropping it in place
 * of [OrganicBlobRenderer] is a one-line change at the call site, which is the
 * clearest demonstration that the animation system is not built around a shape.
 *
 * Each ribbon samples the field at a different layer and a different spatial
 * frequency, so the stack moves like a single fluid body rather than like four
 * copies of one curve.
 */
class WaveRibbonRenderer(
    var config: WaveRibbonConfig = WaveRibbonConfig(),
) : ShapeRenderer {

    private var xs = FloatArray(config.sampleCount)
    private var ys = FloatArray(config.sampleCount)
    private val path = Path()
    private val fillPath = Path()
    private var density = 1f

    override fun onSurfaceChanged(size: Size, density: Float) {
        this.density = density
    }

    override fun DrawScope.render(frame: VisualFrame) {
        val cfg = config
        val params = frame.params
        val palette = frame.palette
        val n = cfg.sampleCount
        if (n < 2) return
        if (xs.size != n) {
            xs = FloatArray(n)
            ys = FloatArray(n)
        }

        val width = frame.size.width * cfg.widthFraction
        val left = (frame.size.width - width) * 0.5f
        val centerY = frame.center.y
        val amplitude = frame.size.height * cfg.amplitudeFraction *
            (0.22f + 0.78f * clamp01(params.waveAmplitude))

        for (ribbon in 0 until cfg.ribbons) {
            val depth = ribbon.toFloat() / cfg.ribbons.coerceAtLeast(1)
            val layer = 2.4f + ribbon * 3.7f
            val scaleForDepth = lerp(1f, 0.45f, depth)
            val phase = params.time * lerp(0.55f, 0.22f, depth) + ribbon * 1.9f

            for (i in 0 until n) {
                val u = i.toFloat() / (n - 1)
                xs[i] = left + u * width

                // Three superposed terms at different scales, exactly as the
                // polar profile does for closed shapes.
                val large = frame.field.line(u * 1.6f + phase * 0.08f, layer, octaves = 2)
                val fine = frame.field.line(u * 5.5f, layer + 1.3f, octaves = 3) * params.turbulence
                val harmonic = bandHarmonic(params, u, phase)

                var offset = (large * 0.75f + fine * 0.30f + harmonic * 0.45f) * amplitude * scaleForDepth

                if (cfg.featherEnds) {
                    // Raised-cosine (Hann) window: the ribbon fades to nothing
                    // at both ends instead of being cut off by the canvas edge.
                    offset *= 0.5f - 0.5f * cos(u * TWO_PI)
                }

                ys[i] = centerY + offset + depth * amplitude * 0.10f
            }

            val alpha = params.opacity * lerp(0.95f, 0.20f, depth)
            val strokeWidth = (cfg.strokeWidthDp * density * lerp(1f, 0.45f, depth)).coerceAtLeast(1f)

            PathBuilders.openSpline(path, xs, ys, n)

            if (ribbon == 0 && cfg.fillBody) {
                fillPath.reset()
                fillPath.moveTo(xs[0], centerY)
                for (i in 0 until n) fillPath.lineTo(xs[i], ys[i])
                fillPath.lineTo(xs[n - 1], centerY)
                fillPath.close()
                drawPath(
                    path = fillPath,
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            palette.core.copy(alpha = 0.32f * params.opacity),
                            Color.Transparent,
                        ),
                        startY = centerY - amplitude,
                        endY = centerY + amplitude,
                    ),
                )
            }

            drawPath(
                path = path,
                color = if (ribbon == 0) palette.highlight else palette.edge,
                alpha = alpha,
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
            )
        }

        // A soft glow along the centre line, so the ribbon sits in light rather
        // than floating on the background.
        if (params.glow > 0.01f) {
            val glowRadius = frame.size.height * 0.5f * (0.5f + 0.6f * params.glow)
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        palette.glow.copy(alpha = 0.22f * params.glow),
                        Color.Transparent,
                    ),
                    center = Offset(frame.center.x, centerY),
                    radius = glowRadius,
                ),
                radius = glowRadius,
                center = Offset(frame.center.x, centerY),
            )
        }
    }

    /** Frequency-band harmonics along the ribbon, mirroring the polar profile's. */
    private fun bandHarmonic(
        params: com.audioviz.core.anim.VisualParams,
        u: Float,
        phase: Float,
    ): Float {
        val bands = params.bandCount
        if (bands == 0) return 0f
        var sum = 0f
        for (b in 0 until bands) {
            val k = (b + 1).toFloat()
            sum += params.band(b) * sin((u * k * PI.toFloat() * 2f) + phase * (1f + b * 0.13f))
        }
        return sum / bands * params.detail
    }
}
