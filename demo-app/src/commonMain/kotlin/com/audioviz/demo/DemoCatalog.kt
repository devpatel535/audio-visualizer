package com.audioviz.demo

import com.audioviz.compose.render.BlobRendererConfig
import com.audioviz.compose.render.CompositeRenderer
import com.audioviz.compose.render.OrganicBlobRenderer
import com.audioviz.compose.render.ParticleAuraConfig
import com.audioviz.compose.render.ParticleAuraRenderer
import com.audioviz.compose.render.ShapeRenderer
import com.audioviz.compose.render.WaveRibbonConfig
import com.audioviz.compose.render.WaveRibbonRenderer
import com.audioviz.compose.shader.ShaderGlowRenderer
import com.audioviz.core.anim.AnimationConfig
import com.audioviz.core.color.VisualPalette
import com.audioviz.core.geometry.PolarProfileConfig
import com.audioviz.core.geometry.RadialShapes

/**
 * The renderers the demo can switch between at runtime.
 *
 * The point of this list is what it demonstrates rather than what it contains:
 * every entry is driven by the same analyzer and the same animation controller,
 * and switching between them at runtime changes nothing upstream. Four of them
 * differ only by a [com.audioviz.core.geometry.RadialShape]; one is not radial
 * at all; one is a stack of three cooperating layers.
 */
enum class DemoRenderer(val label: String, val description: String) {
    FREE_FORM("Free-form", "An abstract outline with no name. The default."),
    FREE_FORM_B("Free-form II", "Another seed. Same code, different creature."),
    DRIFTING("Drifting", "No fixed identity: morphs between forms forever"),
    BLOB("Circle", "The neutral base, for comparison"),
    HEXAGON("Hexagon", "Same pipeline, polygon base"),
    STAR("Star", "Same pipeline, 6-point star base"),
    SQUIRCLE("Squircle", "Same pipeline, superellipse base"),
    FLOWER("Superformula", "Same pipeline, Gielis curve base"),
    RIBBON("Ribbon", "Not radial at all: a waveform"),
    FULL_STACK("Full stack", "GPU aura + particles + free-form body"),
    ;

    /** Builds a fresh renderer. Called when the selection changes. */
    fun create(): ShapeRenderer = when (this) {
        FREE_FORM -> OrganicBlobRenderer()

        FREE_FORM_B -> OrganicBlobRenderer(
            BlobRendererConfig(shape = RadialShapes.organic(seed = 4_812, irregularity = 0.46f)),
        )

        DRIFTING -> OrganicBlobRenderer(
            BlobRendererConfig(shape = RadialShapes.driftingOrganic(count = 5, secondsPerForm = 9f)),
        )

        BLOB -> OrganicBlobRenderer(BlobRendererConfig(shape = RadialShapes.Circle))

        HEXAGON -> OrganicBlobRenderer(
            BlobRendererConfig(
                shape = RadialShapes.polygon(sides = 6, cornerSoftness = 0.18f),
                // A polygon shows deformation on its flats, so a little less of it
                // reads as a lot more movement.
                profile = PolarProfileConfig(deformationStrength = 0.16f),
            ),
        )

        STAR -> OrganicBlobRenderer(
            BlobRendererConfig(
                shape = RadialShapes.star(points = 6, innerRatio = 0.62f, softness = 0.5f),
                profile = PolarProfileConfig(deformationStrength = 0.15f, bandStrength = 0.11f),
            ),
        )

        SQUIRCLE -> OrganicBlobRenderer(
            BlobRendererConfig(shape = RadialShapes.superellipse(exponent = 4.2f)),
        )

        FLOWER -> OrganicBlobRenderer(
            BlobRendererConfig(
                shape = RadialShapes.superformula(m = 5f, n1 = 2.2f, n2 = 7f, n3 = 7f),
                profile = PolarProfileConfig(deformationStrength = 0.17f),
            ),
        )

        RIBBON -> WaveRibbonRenderer(WaveRibbonConfig(ribbons = 4))

        FULL_STACK -> {
            val blob = OrganicBlobRenderer(BlobRendererConfig.Rich)
            CompositeRenderer(
                ShaderGlowRenderer(),
                ParticleAuraRenderer.followingOutline(
                    blob,
                    ParticleAuraConfig(field = ParticleAuraConfig.Rich.field),
                ),
                blob,
            )
        }
    }
}

/** Palettes offered by the demo. */
enum class DemoPalette(val label: String, val palette: VisualPalette) {
    OCEAN("Ocean", VisualPalette.OceanBlue),
    EMBER("Ember", VisualPalette.Ember),
    GRAPHITE("Graphite", VisualPalette.Graphite),
}

/** Animation character presets. */
enum class DemoCharacter(val label: String, val config: AnimationConfig) {
    SUBTLE("Subtle", AnimationConfig.Subtle),
    DEFAULT("Default", AnimationConfig.Default),
    EXPRESSIVE("Expressive", AnimationConfig.Expressive),
}

/** Where the demo gets audio from. */
enum class DemoSource(val label: String) {
    MICROPHONE("Microphone"),
    SYNTHETIC("Demo signal"),
}
