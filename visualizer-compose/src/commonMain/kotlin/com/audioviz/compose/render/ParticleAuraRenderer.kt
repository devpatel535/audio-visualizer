package com.audioviz.compose.render

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.DrawScope
import com.audioviz.core.geometry.ParticleField
import com.audioviz.core.geometry.ParticleFieldConfig
import com.audioviz.core.geometry.RadialShape
import com.audioviz.core.geometry.RadialShapes
import com.audioviz.core.util.clamp01
import com.audioviz.core.util.lerp

/** Look and cost of [ParticleAuraRenderer]. */
data class ParticleAuraConfig(
    /** Simulation parameters, including the hard particle cap. */
    val field: ParticleFieldConfig = ParticleFieldConfig(),

    /** Outline particles are born on, in reference-radius units. */
    val emitter: RadialShape = RadialShapes.Circle,

    /** Particle radius multiplier. */
    val sizeScale: Float = 1f,

    /** Peak alpha of a freshly emitted particle. */
    val peakAlpha: Float = 0.75f,
) {
    companion object {
        /** A handful of sparks; costs almost nothing. */
        val Economy: ParticleAuraConfig = ParticleAuraConfig(
            field = ParticleFieldConfig(capacity = 48, emissionRate = 40f),
        )

        /** A dense aura for a hero surface. */
        val Rich: ParticleAuraConfig = ParticleAuraConfig(
            field = ParticleFieldConfig(capacity = 320, emissionRate = 220f),
        )
    }
}

/**
 * Sparks shed from the outline of whatever shape is in front of them.
 *
 * The particles are simulated in [ParticleField] — pure Kotlin,
 * structure-of-arrays, no allocation — and drift under the *same* noise field
 * that deforms the body. That shared field is why the aura looks like it is
 * being thrown off the object rather than sprinkled over it.
 *
 * Pass [ParticleAuraConfig.emitter] to make the particles follow a specific
 * outline; [followingOutline] wires it to a live [OrganicBlobRenderer] so the
 * sparks leave the deformed edge rather than a static circle.
 */
class ParticleAuraRenderer(
    config: ParticleAuraConfig = ParticleAuraConfig(),
) : ShapeRenderer {

    var config: ParticleAuraConfig = config
        set(value) {
            field = value
            particles = ParticleField(value.field)
        }

    private var particles = ParticleField(config.field)

    override fun DrawScope.render(frame: VisualFrame) {
        val cfg = config
        val params = frame.params
        val palette = frame.palette
        val radius = frame.referenceRadius
        if (radius <= 0f) return

        particles.update(params, cfg.emitter)

        val cx = frame.center.x
        val cy = frame.center.y

        for (i in 0 until particles.count) {
            val remaining = particles.remainingLife(i)
            // Fade in over the first 15% of life, out over the rest: a particle
            // that appears at full brightness reads as a glitch.
            val fade = if (remaining > 0.85f) (1f - remaining) / 0.15f else remaining
            val alpha = clamp01(fade) * cfg.peakAlpha *
                params.opacity * lerp(0.45f, 1f, params.glow)
            if (alpha <= 0.004f) continue

            val color = if (particles.variation[i] > 0.65f) palette.highlight else palette.edge
            drawCircle(
                color = color,
                radius = particles.size[i] * radius * cfg.sizeScale * lerp(0.6f, 1.15f, remaining),
                center = Offset(
                    cx + particles.positionX[i] * radius,
                    cy + particles.positionY[i] * radius,
                ),
                alpha = alpha,
            )
        }
    }

    override fun onSurfaceChanged(size: Size, density: Float) = Unit

    companion object {
        /**
         * Emits along a live blob's deformed outline.
         *
         * The profile is one frame behind when the aura is drawn behind the
         * body, which is invisible at any frame rate and avoids having to run
         * the deformation twice.
         */
        fun followingOutline(
            blob: OrganicBlobRenderer,
            config: ParticleAuraConfig = ParticleAuraConfig(),
        ): ParticleAuraRenderer = ParticleAuraRenderer(
            config.copy(emitter = RadialShape { theta -> blob.outline.radiusAt(theta) }),
        )
    }
}
