package com.audioviz.compose.shader

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import com.audioviz.compose.render.ShapeRenderer
import com.audioviz.compose.render.VisualFrame
import com.audioviz.core.util.clamp01

/**
 * SkSL / AGSL source for the aura.
 *
 * ### Why this belongs on the GPU
 * A convincing bloom is a wide, smooth falloff evaluated per pixel. On the CPU
 * the options are a real blur (a full-screen read-modify-write, the single most
 * expensive thing a mobile 2D renderer can do) or a stack of overlapping
 * gradient circles, which is what the Canvas path does — cheap, but the banding
 * shows on a dark background and the layer count caps how smooth it can get.
 *
 * On the GPU the same falloff is a handful of ALU instructions per pixel with
 * no bandwidth cost at all, and the angular wobble that makes the halo follow
 * the deformed body is free rather than another layer.
 *
 * ### What it does not do
 * No texture sampling, no child shaders, no loops: those are where AGSL and
 * SkSL differ and where old drivers misbehave. Everything here is arithmetic
 * that has been in both dialects since day one.
 */
internal const val AURA_SHADER_SOURCE = """
uniform float2 uResolution;
uniform float2 uCenter;
uniform float  uRadius;
uniform float  uGlow;
uniform float  uTurbulence;
uniform float  uTime;
uniform float4 uInner;
uniform float4 uOuter;

half4 main(float2 fragCoord) {
    float2 d = (fragCoord - uCenter) / uRadius;
    float r = length(d);
    float angle = atan(d.y, d.x);

    // Three angular harmonics at unrelated rates: the halo breathes with the
    // body instead of sitting around it as a perfect circle.
    float wobble =
        0.070 * sin(angle * 3.0 + uTime * 1.30) +
        0.045 * sin(angle * 5.0 - uTime * 0.87) +
        0.028 * sin(angle * 8.0 + uTime * 2.10);
    float rr = max(r * (1.0 - wobble * uTurbulence), 0.0);

    // A tight core plus a wide halo. Summing two exponentials gives a falloff
    // with a bright centre and a very long, banding-free tail.
    float core = exp(-rr * rr * 2.60);
    float halo = exp(-rr * 1.15);

    float a = clamp((core * 0.72 + halo * 0.48) * uGlow, 0.0, 1.0);
    float4 c = mix(uOuter, uInner, clamp(core, 0.0, 1.0));

    // Skia runtime shaders return premultiplied colour.
    float alpha = a * c.a;
    return half4(float4(c.rgb * alpha, alpha));
}
"""

/** Look and cost of [ShaderGlowRenderer]. */
data class ShaderGlowConfig(
    /** How far the aura extends beyond the reference radius. */
    val spread: Float = 2.2f,

    /** Overall opacity multiplier. */
    val strength: Float = 1f,

    /** Canvas gradient layers used when runtime shaders are unavailable. */
    val fallbackLayers: Int = 3,
)

/**
 * A GPU aura that sits behind whatever renderer draws next.
 *
 * Use it as the first layer of a [com.audioviz.compose.render.CompositeRenderer]:
 *
 * ```
 * CompositeRenderer(
 *     ShaderGlowRenderer(),
 *     ParticleAuraRenderer(),
 *     OrganicBlobRenderer(),
 * )
 * ```
 *
 * If [createShaderProgram] returns `null` — Android before API 33, or a driver
 * that refuses the program — it silently draws the layered-gradient fallback
 * instead. The visual is a little less smooth; nothing else changes, and no
 * caller has to test for it.
 */
class ShaderGlowRenderer(
    var config: ShaderGlowConfig = ShaderGlowConfig(),
) : ShapeRenderer {

    private val program: ShaderProgram? = createShaderProgram(AURA_SHADER_SOURCE)

    /** True when the GPU path is in use. Surfaced by the demo's diagnostics. */
    val usingGpu: Boolean get() = program != null

    override fun onSurfaceChanged(size: Size, density: Float) = Unit

    override fun DrawScope.render(frame: VisualFrame) {
        val params = frame.params
        val strength = clamp01(params.glow) * config.strength
        if (strength <= 0.002f) return

        val radius = frame.referenceRadius * config.spread
        if (radius <= 0f) return

        val shader = program
        if (shader == null) {
            drawFallback(frame, strength, radius)
            return
        }

        val inner = frame.palette.glow
        val outer = frame.palette.core

        shader.setFloat2("uResolution", size.width, size.height)
        shader.setFloat2("uCenter", frame.center.x, frame.center.y)
        shader.setFloat("uRadius", radius)
        shader.setFloat("uGlow", strength)
        shader.setFloat("uTurbulence", clamp01(params.turbulence + params.deformation * 0.5f))
        shader.setFloat("uTime", params.time)
        shader.setFloat4("uInner", inner.red, inner.green, inner.blue, inner.alpha)
        shader.setFloat4("uOuter", outer.red, outer.green, outer.blue, 0f)

        drawRect(brush = shader.brush())
    }

    /** Stacked radial gradients: the same falloff, approximated in a few layers. */
    private fun DrawScope.drawFallback(frame: VisualFrame, strength: Float, radius: Float) {
        val layers = config.fallbackLayers.coerceAtLeast(1)
        val center: Offset = frame.center
        for (layer in 0 until layers) {
            val t = (layer + 1f) / layers
            val layerRadius = radius * (0.45f + 0.55f * t)
            val alpha = (0.30f * strength / layers) * (1.2f - t * 0.6f)
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        frame.palette.glow.copy(alpha = alpha),
                        frame.palette.glow.copy(alpha = alpha * 0.3f),
                        Color.Transparent,
                    ),
                    center = center,
                    radius = layerRadius,
                ),
                radius = layerRadius,
                center = center,
            )
        }
    }
}
