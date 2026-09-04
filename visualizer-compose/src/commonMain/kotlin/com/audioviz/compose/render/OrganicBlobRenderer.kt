package com.audioviz.compose.render

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.Stroke
import com.audioviz.core.geometry.PolarProfile
import com.audioviz.core.geometry.PolarProfileConfig
import com.audioviz.core.geometry.RadialShape
import com.audioviz.core.geometry.RadialShapes
import com.audioviz.core.util.clamp01
import com.audioviz.core.util.lerp

/**
 * Look and cost of [OrganicBlobRenderer].
 *
 * Every count here is a performance dial. The defaults target a mid-range phone
 * at 60 fps; see `docs/PERFORMANCE.md` for measured budgets.
 */
data class BlobRendererConfig(
    /**
     * The base geometry.
     *
     * Defaults to a **free-form** outline — deliberately not a circle, a
     * polygon or anything else with a name. The system was never built around a
     * specific shape, so its default should not be one either.
     *
     * This single field is the whole "replace the shape" story: pass
     * [RadialShapes.organic] with another seed, [RadialShapes.driftingOrganic]
     * for an outline that never settles on one form at all, a named primitive
     * like [RadialShapes.polygon], or [RadialShapes.sampled] with a traced
     * outline — and nothing else in the system changes.
     */
    val shape: RadialShape = RadialShapes.Organic,

    /** Outline samples. 128 is smooth on any display; 64 is fine on low-end hardware. */
    val sampleCount: Int = 128,

    /** How the outline is displaced. */
    val profile: PolarProfileConfig = PolarProfileConfig(),

    /** Concentric glow passes behind the body. 0 disables the glow entirely. */
    val glowLayers: Int = 3,

    /** How far the outermost glow pass extends beyond the body, as a fraction of radius. */
    val glowSpread: Float = 0.85f,

    /** Interior contour lines that carry the secondary motion. 0 disables them. */
    val innerContours: Int = 2,

    /** Draw a bright rim along the outline. */
    val drawRim: Boolean = true,

    /** Rim width in dp. */
    val rimWidthDp: Float = 1.6f,

    /** Draw the soft specular highlight inside the body. */
    val drawSpecular: Boolean = true,

    /** Light direction for the body gradient and specular, as a fraction of radius. */
    val lightOffsetX: Float = -0.34f,
    val lightOffsetY: Float = -0.40f,
) {
    companion object {
        /** Fewest layers and samples that still look intentional. */
        val Economy: BlobRendererConfig = BlobRendererConfig(
            sampleCount = 64,
            profile = PolarProfileConfig(sampleCount = 64, turbulenceOctaves = 2),
            glowLayers = 1,
            innerContours = 0,
            drawSpecular = false,
        )

        /** Every layer on, for a hero surface on capable hardware. */
        val Rich: BlobRendererConfig = BlobRendererConfig(
            sampleCount = 160,
            profile = PolarProfileConfig(sampleCount = 160, turbulenceOctaves = 5),
            glowLayers = 4,
            innerContours = 3,
        )
    }
}

/**
 * The reference renderer: a soft, deforming body with a glow, interior contours
 * and a rim light.
 *
 * It is deliberately *not* "a circle renderer". Its geometry comes entirely from
 * [BlobRendererConfig.shape], and the deformation pipeline in
 * [com.audioviz.core.geometry.PolarProfile] is identical for a circle, a
 * hexagon, a star or a traced logo. The circle is only the default.
 *
 * ### What each layer expresses
 * | Layer            | Driven by                       | Reads as               |
 * |------------------|---------------------------------|------------------------|
 * | glow passes      | `glow`, `pulse`                 | the object being lit   |
 * | body outline     | `deformation`, `turbulence`, bands | listening / reacting |
 * | body gradient    | `colorMix`, `brightness`        | mood, energy           |
 * | interior contours| `detail`, `midEnergy`           | internal life          |
 * | rim              | `glow`, `highEnergy`            | crispness, sibilance   |
 *
 * ### Allocation
 * Path objects and the radius buffer are allocated once. The gradient brushes
 * are rebuilt each frame because their colours change each frame; that is two
 * to three small objects per frame, which is well inside the budget. The shader
 * backend in [com.audioviz.compose.shader] removes even those.
 */
class OrganicBlobRenderer(
    config: BlobRendererConfig = BlobRendererConfig(),
) : ShapeRenderer {

    var config: BlobRendererConfig = config
        set(value) {
            field = value
            profile.config = value.profile.copy(sampleCount = value.sampleCount)
            if (pixelRadii.size != value.sampleCount) {
                pixelRadii = FloatArray(value.sampleCount)
                rotatedCos = FloatArray(value.sampleCount)
                rotatedSin = FloatArray(value.sampleCount)
            }
        }

    private val profile = PolarProfile(config.profile.copy(sampleCount = config.sampleCount))
    private var pixelRadii = FloatArray(config.sampleCount)

    // Sample angles rotated into the object's current orientation, refreshed
    // once per path per frame at a cost of two transcendental calls each.
    private var rotatedCos = FloatArray(config.sampleCount)
    private var rotatedSin = FloatArray(config.sampleCount)

    private val bodyPath = Path()
    private val contourPath = Path()

    private var density = 1f

    /** The outline in reference-radius units; other layers can emit along it. */
    val outline: PolarProfile get() = profile

    override fun onSurfaceChanged(size: Size, density: Float) {
        this.density = density
    }

    override fun DrawScope.render(frame: VisualFrame) {
        val cfg = config
        val params = frame.params
        val palette = frame.palette
        val radius = frame.referenceRadius
        if (radius <= 0f) return

        profile.update(cfg.shape, params)

        val n = profile.sampleCount
        if (pixelRadii.size != n) {
            pixelRadii = FloatArray(n)
            rotatedCos = FloatArray(n)
            rotatedSin = FloatArray(n)
        }
        for (i in 0 until n) pixelRadii[i] = profile.radii[i] * radius

        val cx = frame.center.x
        val cy = frame.center.y
        val maxRadius = profile.maxRadius * radius

        drawGlow(cfg, params, palette, frame.center, maxRadius)

        PathBuilders.rotateTables(
            profile.sampleCosTable, profile.sampleSinTable,
            params.rotation, rotatedCos, rotatedSin,
        )
        PathBuilders.closedPolarSpline(bodyPath, pixelRadii, rotatedCos, rotatedSin, cx, cy)

        // Body: a radial gradient offset toward the light, so the object reads
        // as a volume rather than a filled outline. The gradient's own centre
        // drifts slightly with the noise field, which stops it looking pasted on.
        val lightDrift = frame.field.scalar(LIGHT_CHANNEL) * 0.05f
        val lightCenter = Offset(
            cx + (cfg.lightOffsetX + lightDrift) * radius,
            cy + (cfg.lightOffsetY - lightDrift) * radius,
        )
        drawPath(
            path = bodyPath,
            brush = Brush.radialGradient(
                colors = listOf(palette.edge, palette.core, palette.shadow),
                center = lightCenter,
                radius = maxRadius * 1.45f,
            ),
            alpha = params.opacity,
        )

        drawInteriorContours(cfg, params, palette, frame, cx, cy, radius)

        if (cfg.drawSpecular) {
            // A single soft highlight, scaled by glow. Cheap, and it is most of
            // what makes the surface look wet rather than flat. The breath
            // oscillator gives it a slow swell so the object is never
            // completely still, even in total silence.
            val specularRadius = maxRadius * lerp(0.30f, 0.46f, params.glow) *
                lerp(0.955f, 1.045f, params.breath)
            // Clipped to the body: on a concave shape (a star, a traced logo)
            // the light position can fall outside the outline, and an unclipped
            // highlight then floats on the background as a grey smudge. A
            // specular belongs *on* the surface.
            clipPath(bodyPath) {
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            palette.highlight.copy(
                                alpha = 0.28f + 0.34f * params.glow + 0.10f * params.energy,
                            ),
                            Color.Transparent,
                        ),
                        center = lightCenter,
                        radius = specularRadius,
                    ),
                    radius = specularRadius,
                    center = lightCenter,
                    alpha = params.opacity,
                )
            }
        }

        if (cfg.drawRim) {
            // A soft rim is a wide, faint stroke; a crisp one is narrow and
            // bright. `blur` moves between them while conserving roughly the
            // same total light, so the edge softens without the object dimming.
            val softness = 1f + 2.5f * params.blur
            val width = (cfg.rimWidthDp * density).coerceAtLeast(1f) *
                lerp(0.85f, 1.6f, clamp01(params.highEnergy + params.glow * 0.5f)) *
                softness
            drawPath(
                path = bodyPath,
                color = palette.highlight,
                alpha = params.opacity * (0.14f + 0.38f * params.glow) / softness,
                style = Stroke(width = width),
            )
        }
    }

    /**
     * Concentric soft passes behind the body.
     *
     * Several wide, low-alpha radial gradients approximate a bloom far more
     * cheaply than a real blur, and unlike a blur the cost does not grow with
     * the canvas size. Each pass is offset slightly by the noise field so the
     * halo is not a perfect circle around a deformed body.
     */
    private fun DrawScope.drawGlow(
        cfg: BlobRendererConfig,
        params: com.audioviz.core.anim.VisualParams,
        palette: RenderPalette,
        center: Offset,
        maxRadius: Float,
    ) {
        if (cfg.glowLayers <= 0) return
        val strength = clamp01(params.glow)
        if (strength <= 0.001f) return

        for (layer in 0 until cfg.glowLayers) {
            val t = (layer + 1f) / cfg.glowLayers
            // `blur` widens and softens the halo. It peaks when the visual is
            // idle, so a resting object has a diffuse aura and an active one
            // has a tighter, brighter core.
            val spread = 1f + cfg.glowSpread * t * (0.6f + 0.7f * strength) * (1f + params.blur)
            val wobbleX = params.field.scalar(GLOW_CHANNEL + layer) * maxRadius * 0.03f
            val wobbleY = params.field.scalar(GLOW_CHANNEL + layer + 0.5f) * maxRadius * 0.03f
            val glowRadius = maxRadius * spread
            val alpha = (0.32f * strength / cfg.glowLayers) * (1.15f - t * 0.5f)

            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        palette.glow.copy(alpha = alpha),
                        palette.glow.copy(alpha = alpha * 0.35f),
                        Color.Transparent,
                    ),
                    center = Offset(center.x + wobbleX, center.y + wobbleY),
                    radius = glowRadius,
                ),
                radius = glowRadius,
                center = Offset(center.x + wobbleX, center.y + wobbleY),
            )
        }
    }

    /**
     * Interior contours: the same outline at smaller scales, each offset in the
     * noise field and counter-rotated.
     *
     * This is the "secondary motion" layer. It costs one extra spline per
     * contour and is what stops the body from looking like a solid blob when the
     * audio gets busy — there is structure moving inside it.
     */
    private fun DrawScope.drawInteriorContours(
        cfg: BlobRendererConfig,
        params: com.audioviz.core.anim.VisualParams,
        palette: RenderPalette,
        frame: VisualFrame,
        cx: Float,
        cy: Float,
        radius: Float,
    ) {
        if (cfg.innerContours <= 0) return
        val detail = clamp01(params.detail)
        if (detail <= 0.01f) return

        val n = profile.sampleCount
        val width = (1.1f * density).coerceAtLeast(1f)

        for (contour in 0 until cfg.innerContours) {
            val t = (contour + 1f) / (cfg.innerContours + 1f)
            // Interior structure breathes, and breathes *more* when idle: in
            // silence this is the only thing besides the drifting noise field
            // that is moving, so it carries the sense of a living object.
            val idleBreath = 1f + 0.05f * (params.breath * 2f - 1f) * (0.35f + 0.65f * params.idle)
            val shrink = lerp(0.82f, 0.34f, t) * idleBreath
            val layer = 5.5f + contour * 4.7f
            val wobble = 0.20f * detail * (0.6f + 0.6f * t)

            for (i in 0 until n) {
                // Three octaves and a well-separated layer, so a contour is
                // its own shape rather than a scaled copy of the outline.
                val offset = frame.field.loopAt(
                    profile.sampleCosTable[i],
                    profile.sampleSinTable[i],
                    layer,
                    octaves = 3,
                ) * wobble
                pixelRadii[i] = profile.radii[i] * radius * shrink * (1f + offset)
            }

            // Counter-rotating, and slower for deeper contours: layered motion
            // at different speeds is what reads as depth.
            val rotation = -params.rotation * (0.35f + 0.4f * t) + contour * 0.7f
            PathBuilders.rotateTables(
                profile.sampleCosTable, profile.sampleSinTable,
                rotation, rotatedCos, rotatedSin,
            )
            PathBuilders.closedPolarSpline(contourPath, pixelRadii, rotatedCos, rotatedSin, cx, cy)

            drawPath(
                path = contourPath,
                color = if (contour % 2 == 0) palette.highlight else palette.edge,
                alpha = params.opacity * detail * lerp(0.22f, 0.09f, t),
                style = Stroke(width = width),
            )
        }

        // Restore the buffer for the next frame's body pass.
        for (i in 0 until n) pixelRadii[i] = profile.radii[i] * radius
    }

    private companion object {
        const val LIGHT_CHANNEL = 5.13f
        const val GLOW_CHANNEL = 7.31f
    }
}
