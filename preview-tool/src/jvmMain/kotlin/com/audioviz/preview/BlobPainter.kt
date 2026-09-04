package com.audioviz.preview

import com.audioviz.core.anim.VisualParams
import com.audioviz.core.color.Palette
import com.audioviz.core.color.VisualPalette
import com.audioviz.core.geometry.ParticleField
import com.audioviz.core.geometry.PolarProfile
import com.audioviz.core.geometry.RadialShape
import com.audioviz.core.util.clamp01
import com.audioviz.core.util.lerp
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Graphics2D
import java.awt.MultipleGradientPaint
import java.awt.RadialGradientPaint
import java.awt.RenderingHints
import java.awt.geom.Path2D
import java.awt.geom.Point2D

/**
 * Draws `visualizer-core`'s geometry with Java2D.
 *
 * ### What this is for
 * The Compose renderers cannot be exercised headlessly — they need a Skia
 * surface and, on this build, Compose's Android-derived dependencies. But the
 * part worth *looking* at is not the drawing calls; it is the geometry, the
 * motion and the colour, all of which live in `visualizer-core` and are plain
 * Kotlin. This painter consumes exactly the same [PolarProfile], [ParticleField],
 * [Palette] and motion field as [com.audioviz.compose.render.OrganicBlobRenderer],
 * and mirrors its layer structure, so the contact sheets it produces show what
 * the maths actually looks like.
 *
 * It is a development tool, not a second renderer: if the two ever disagree
 * about *drawing*, the Compose one is correct. What they cannot disagree about
 * is the geometry, which is the point.
 */
class BlobPainter(
    private val shape: RadialShape,
    private val palette: VisualPalette,
    private val glowLayers: Int = 3,
    private val innerContours: Int = 2,
    profileSamples: Int = 128,
) {
    private val profile = PolarProfile(
        com.audioviz.core.geometry.PolarProfileConfig(sampleCount = profileSamples),
    )
    private val particles = ParticleField(
        com.audioviz.core.geometry.ParticleFieldConfig(capacity = 140, emissionRate = 120f),
    )
    private val emitter = RadialShape { theta -> profile.radiusAt(theta) }

    private val pixelRadii = FloatArray(profileSamples)
    private val rotatedCos = FloatArray(profileSamples)
    private val rotatedSin = FloatArray(profileSamples)
    private val scratch = FloatArray(4)
    private val path = Path2D.Float()

    /**
     * Advances the geometry for one frame without drawing.
     *
     * Call this on **every** stepped frame, and [paint] only on the frames you
     * want an image of. The particle simulation is stateful, so skipping it on
     * unpainted frames would make the aura evolve at the contact sheet's rate
     * rather than at the frame rate.
     */
    fun advance(params: VisualParams) {
        profile.update(shape, params)
        particles.update(params, emitter)
    }

    fun paint(g: Graphics2D, params: VisualParams, width: Int, height: Int, radiusFraction: Float = 0.58f) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)

        val reference = minOf(width, height) * 0.5f * radiusFraction
        val cx = width * 0.5f + params.driftX * reference
        val cy = height * 0.5f + params.driftY * reference

        // Palette, resolved once per frame exactly as RenderPalette does.
        val core = sample(palette.gradient, params.colorMix, params.brightness * 0.35f)
        val edge = sample(palette.gradient, clamp01(params.colorMix + 0.14f), params.brightness * 0.55f)
        val highlight = sample(palette.gradient, clamp01(params.colorMix + 0.30f), params.brightness * 0.75f)
        val shadow = sample(palette.gradient, clamp01(params.colorMix - 0.22f), 0f)
        val glow = sample(palette.glow, clamp01(params.colorMix * 0.85f + params.glow * 0.35f), params.brightness * 0.5f)

        g.color = Color(palette.backgroundArgb, true)
        g.fillRect(0, 0, width, height)

        val n = profile.sampleCount
        for (i in 0 until n) pixelRadii[i] = profile.radii[i] * reference
        val maxRadius = profile.maxRadius * reference

        drawGlow(g, params, glow, cx, cy, maxRadius)
        drawParticles(g, params, edge, highlight, cx, cy, reference)

        rotateTables(profile.sampleCosTable, profile.sampleSinTable, params.rotation)
        buildClosedSpline(pixelRadii, rotatedCos, rotatedSin, cx, cy)

        val lightDrift = params.field.scalar(5.13f) * 0.05f
        val lightX = cx + (-0.34f + lightDrift) * reference
        val lightY = cy + (-0.40f - lightDrift) * reference

        g.paint = RadialGradientPaint(
            Point2D.Float(lightX, lightY),
            (maxRadius * 1.45f).coerceAtLeast(1f),
            floatArrayOf(0f, 0.5f, 1f),
            arrayOf(withAlpha(edge, params.opacity), withAlpha(core, params.opacity), withAlpha(shadow, params.opacity)),
            MultipleGradientPaint.CycleMethod.NO_CYCLE,
        )
        g.fill(path)

        drawInteriorContours(g, params, highlight, edge, cx, cy, reference)

        // The contour pass rebuilt `path`; restore the body outline before
        // using it as the specular clip.
        rotateTables(profile.sampleCosTable, profile.sampleSinTable, params.rotation)
        buildClosedSpline(pixelRadii, rotatedCos, rotatedSin, cx, cy)

        // Specular, breathing gently so the object is never completely still.
        val specularRadius = (maxRadius * lerp(0.30f, 0.46f, params.glow) *
            lerp(0.955f, 1.045f, params.breath)).coerceAtLeast(1f)
        val specularAlpha = 0.28f + 0.34f * params.glow + 0.10f * params.energy
        // Clipped to the body: on a concave shape the light position can fall
        // outside the outline, and an unclipped highlight floats on the
        // background as a grey smudge.
        val savedClip = g.clip
        g.clip(path)
        g.paint = RadialGradientPaint(
            Point2D.Float(lightX, lightY),
            specularRadius,
            floatArrayOf(0f, 1f),
            arrayOf(withAlpha(highlight, specularAlpha * params.opacity), fadeOut(highlight)),
            MultipleGradientPaint.CycleMethod.NO_CYCLE,
        )
        g.fillOval(
            (lightX - specularRadius).toInt(), (lightY - specularRadius).toInt(),
            (specularRadius * 2).toInt(), (specularRadius * 2).toInt(),
        )
        g.clip = savedClip

        // Rim: `blur` trades width against brightness, conserving total light.
        val softness = 1f + 2.5f * params.blur
        val rimWidth = 1.6f * lerp(0.85f, 1.6f, clamp01(params.highEnergy + params.glow * 0.5f)) * softness
        rotateTables(profile.sampleCosTable, profile.sampleSinTable, params.rotation)
        buildClosedSpline(pixelRadii, rotatedCos, rotatedSin, cx, cy)
        g.stroke = BasicStroke(rimWidth, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        g.paint = withAlpha(highlight, params.opacity * (0.14f + 0.38f * params.glow) / softness)
        g.draw(path)
    }

    private fun drawGlow(
        g: Graphics2D,
        params: VisualParams,
        glow: Color,
        cx: Float,
        cy: Float,
        maxRadius: Float,
    ) {
        val strength = clamp01(params.glow)
        if (glowLayers <= 0 || strength <= 0.001f) return
        for (layer in 0 until glowLayers) {
            val t = (layer + 1f) / glowLayers
            val spread = 1f + 0.85f * t * (0.6f + 0.7f * strength) * (1f + params.blur)
            val wobbleX = params.field.scalar(7.31f + layer) * maxRadius * 0.03f
            val wobbleY = params.field.scalar(7.31f + layer + 0.5f) * maxRadius * 0.03f
            val radius = (maxRadius * spread).coerceAtLeast(1f)
            val alpha = (0.32f * strength / glowLayers) * (1.15f - t * 0.5f)
            val x = cx + wobbleX
            val y = cy + wobbleY
            g.paint = RadialGradientPaint(
                Point2D.Float(x, y),
                radius,
                floatArrayOf(0f, 0.45f, 1f),
                arrayOf(withAlpha(glow, alpha), withAlpha(glow, alpha * 0.35f), fadeOut(glow)),
                MultipleGradientPaint.CycleMethod.NO_CYCLE,
            )
            g.fillOval((x - radius).toInt(), (y - radius).toInt(), (radius * 2).toInt(), (radius * 2).toInt())
        }
    }

    private fun drawInteriorContours(
        g: Graphics2D,
        params: VisualParams,
        highlight: Color,
        edge: Color,
        cx: Float,
        cy: Float,
        reference: Float,
    ) {
        val detail = clamp01(params.detail)
        if (innerContours <= 0 || detail <= 0.01f) return
        val n = profile.sampleCount

        for (contour in 0 until innerContours) {
            val t = (contour + 1f) / (innerContours + 1f)
            val idleBreath = 1f + 0.05f * (params.breath * 2f - 1f) * (0.35f + 0.65f * params.idle)
            val shrink = lerp(0.82f, 0.34f, t) * idleBreath
            val layer = 5.5f + contour * 4.7f
            val wobble = 0.20f * detail * (0.6f + 0.6f * t)

            for (i in 0 until n) {
                val offset = params.field.loopAt(
                    profile.sampleCosTable[i], profile.sampleSinTable[i], layer, 3,
                ) * wobble
                pixelRadii[i] = profile.radii[i] * reference * shrink * (1f + offset)
            }
            val rotation = -params.rotation * (0.35f + 0.4f * t) + contour * 0.7f
            rotateTables(profile.sampleCosTable, profile.sampleSinTable, rotation)
            buildClosedSpline(pixelRadii, rotatedCos, rotatedSin, cx, cy)

            g.stroke = BasicStroke(1.1f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
            g.paint = withAlpha(
                if (contour % 2 == 0) highlight else edge,
                params.opacity * detail * lerp(0.22f, 0.09f, t),
            )
            g.draw(path)
        }
        for (i in 0 until n) pixelRadii[i] = profile.radii[i] * reference
    }

    private fun drawParticles(
        g: Graphics2D,
        params: VisualParams,
        edge: Color,
        highlight: Color,
        cx: Float,
        cy: Float,
        reference: Float,
    ) {
        for (i in 0 until particles.count) {
            val remaining = particles.remainingLife(i)
            val fade = if (remaining > 0.85f) (1f - remaining) / 0.15f else remaining
            val alpha = clamp01(fade) * 0.75f * params.opacity * lerp(0.45f, 1f, params.glow)
            if (alpha <= 0.004f) continue
            val r = particles.size[i] * reference * lerp(0.6f, 1.15f, remaining)
            if (r < 0.3f) continue
            val x = cx + particles.positionX[i] * reference
            val y = cy + particles.positionY[i] * reference
            g.paint = withAlpha(if (particles.variation[i] > 0.65f) highlight else edge, alpha)
            g.fillOval((x - r).toInt(), (y - r).toInt(), (r * 2).toInt().coerceAtLeast(1), (r * 2).toInt().coerceAtLeast(1))
        }
    }

    /** Mirrors `PathBuilders.rotateTables`. */
    private fun rotateTables(sourceCos: FloatArray, sourceSin: FloatArray, angle: Float) {
        val c = kotlin.math.cos(angle)
        val s = kotlin.math.sin(angle)
        for (i in sourceCos.indices) {
            rotatedCos[i] = sourceCos[i] * c - sourceSin[i] * s
            rotatedSin[i] = sourceSin[i] * c + sourceCos[i] * s
        }
    }

    /** Mirrors `PathBuilders.closedPolarSpline`. */
    private fun buildClosedSpline(
        radii: FloatArray,
        cosTable: FloatArray,
        sinTable: FloatArray,
        cx: Float,
        cy: Float,
    ) {
        val n = radii.size
        path.reset()
        val factor = 1f / 6f
        path.moveTo(cx + cosTable[0] * radii[0], cy + sinTable[0] * radii[0])
        for (i in 0 until n) {
            val i0 = if (i == 0) n - 1 else i - 1
            val i2 = if (i + 1 == n) 0 else i + 1
            val i3 = if (i + 2 >= n) i + 2 - n else i + 2
            val x0 = cx + cosTable[i0] * radii[i0]; val y0 = cy + sinTable[i0] * radii[i0]
            val x1 = cx + cosTable[i] * radii[i]; val y1 = cy + sinTable[i] * radii[i]
            val x2 = cx + cosTable[i2] * radii[i2]; val y2 = cy + sinTable[i2] * radii[i2]
            val x3 = cx + cosTable[i3] * radii[i3]; val y3 = cy + sinTable[i3] * radii[i3]
            path.curveTo(
                x1 + (x2 - x0) * factor, y1 + (y2 - y0) * factor,
                x2 - (x3 - x1) * factor, y2 - (y3 - y1) * factor,
                x2, y2,
            )
        }
        path.closePath()
    }

    private fun sample(source: Palette, position: Float, lift: Float): Color {
        source.sample(position, scratch)
        val l = clamp01(lift)
        return Color(
            clamp01(scratch[0] + (1f - scratch[0]) * l),
            clamp01(scratch[1] + (1f - scratch[1]) * l),
            clamp01(scratch[2] + (1f - scratch[2]) * l),
            clamp01(scratch[3]),
        )
    }

    private companion object {
        fun withAlpha(color: Color, alpha: Float): Color =
            Color(color.red, color.green, color.blue, (clamp01(alpha) * 255).toInt())

        /**
         * Fades to the *same* RGB at zero alpha rather than to transparent
         * black. Java2D interpolates gradients in straight (non-premultiplied)
         * sRGB, so fading to `Color(0,0,0,0)` draws a dark ring.
         */
        fun fadeOut(color: Color): Color = Color(color.red, color.green, color.blue, 0)
    }
}
