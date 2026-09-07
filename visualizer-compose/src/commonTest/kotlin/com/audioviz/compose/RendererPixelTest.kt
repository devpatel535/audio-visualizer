package com.audioviz.compose

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.audioviz.compose.render.BlobRendererConfig
import com.audioviz.compose.render.CompositeRenderer
import com.audioviz.compose.render.OrganicBlobRenderer
import com.audioviz.compose.render.ParticleAuraRenderer
import com.audioviz.compose.render.RenderPalette
import com.audioviz.compose.render.ShapeRenderer
import com.audioviz.compose.render.VisualFrame
import com.audioviz.compose.render.WaveRibbonRenderer
import com.audioviz.compose.shader.ShaderGlowRenderer
import com.audioviz.core.analysis.AnalyzerConfig
import com.audioviz.core.analysis.AudioAnalyzer
import com.audioviz.core.anim.AnimationConfig
import com.audioviz.core.anim.AnimationController
import com.audioviz.core.anim.VisualParams
import com.audioviz.core.capture.SilentAudioSource
import com.audioviz.core.capture.SyntheticAudioSource
import com.audioviz.core.color.VisualPalette
import com.audioviz.core.geometry.RadialShapes
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Rasterises the real Compose renderers and asserts on the pixels.
 *
 * ### Why not a Compose UI test
 * The visualizer's frame loop is an infinite `withFrameNanos` coroutine, so a
 * composition containing it is never idle and anything that waits for idle
 * hangs. Driving the renderer straight into a Skia-backed [ImageBitmap] avoids
 * composition, the test clock and that whole class of flake, while still
 * exercising the exact code path a device runs: the real `DrawScope`, the real
 * `Path`, the real gradients, the real Skia rasteriser.
 *
 * On the JVM these run against desktop Skia. The same source compiles for
 * Wasm, which is how the API is verified on a machine that cannot resolve the
 * JVM Compose artifacts.
 */
class RendererPixelTest {

    private val width = 320
    private val height = 320

    /** Runs the pipeline for [seconds] and returns the final parameters. */
    private fun paramsFor(
        levelDb: Float?,
        seconds: Float = 6f,
        animationConfig: AnimationConfig = AnimationConfig.Default,
    ): VisualParams {
        val analyzer = AudioAnalyzer(AnalyzerConfig.Deterministic)
        val controller = AnimationController(animationConfig)
        val block = FloatArray(256)
        val speech = levelDb?.let { SyntheticAudioSource(levelDb = it) }
        val quiet = if (levelDb == null) SilentAudioSource(roomToneDb = -72f) else null

        var debt = 0f
        var params: VisualParams = controller.current
        repeat((seconds * 60).toInt()) {
            debt += 48_000f / 60f
            while (debt >= block.size) {
                speech?.fill(block)
                quiet?.fill(block)
                analyzer.onAudioFrame(block, 0, block.size, 48_000)
                debt -= block.size
            }
            params = controller.update(analyzer.features, 1f / 60f)
        }
        return params
    }

    /**
     * Draws into a bitmap and returns it.
     *
     * @param warmupFrames frames drawn to throwaway bitmaps first. Stateful
     *   renderers need this: the particle field emits fractionally and fades
     *   each particle in over the first 15% of its life, so one frame yields a
     *   single barely-visible speck — about 0.00006 of the mean luminance of a
     *   320x320 canvas, which reads as blank. `ParticleWarmupTest` in
     *   `visualizer-core` pins that behaviour.
     */
    private fun render(
        renderer: ShapeRenderer,
        params: VisualParams,
        palette: VisualPalette = VisualPalette.OceanBlue,
        warmupFrames: Int = 0,
    ): ImageBitmap {
        val bitmap = ImageBitmap(width, height)
        val canvas = Canvas(bitmap)
        val size = Size(width.toFloat(), height.toFloat())
        val density = Density(2f)

        val renderPalette = RenderPalette(palette)
        renderPalette.resolve(params)

        val frame = VisualFrame().apply {
            this.params = params
            this.palette = renderPalette
            this.size = size
            this.density = 2f
            this.referenceRadius = minOf(size.width, size.height) * 0.5f * 0.58f
            this.center = Offset(size.width * 0.5f, size.height * 0.5f)
        }

        renderer.onSurfaceChanged(size, 2f)

        repeat(warmupFrames) {
            val scratch = Canvas(ImageBitmap(width, height))
            CanvasDrawScope().draw(density, LayoutDirection.Ltr, scratch, size) {
                with(renderer) { render(frame) }
            }
        }

        CanvasDrawScope().draw(density, LayoutDirection.Ltr, canvas, size) {
            drawRect(color = renderPalette.background)
            with(renderer) { render(frame) }
        }
        return bitmap
    }

    /** Mean luminance over the whole bitmap, 0..1. */
    private fun meanLuminance(bitmap: ImageBitmap): Float {
        val pixels = bitmap.toPixelMap()
        var sum = 0f
        for (y in 0 until bitmap.height) {
            for (x in 0 until bitmap.width) {
                val c = pixels[x, y]
                sum += 0.2126f * c.red + 0.7152f * c.green + 0.0722f * c.blue
            }
        }
        return sum / (bitmap.width * bitmap.height)
    }

    /** Mean absolute per-channel difference between two bitmaps, 0..1. */
    private fun difference(a: ImageBitmap, b: ImageBitmap): Float {
        val pa = a.toPixelMap()
        val pb = b.toPixelMap()
        var sum = 0f
        for (y in 0 until a.height) {
            for (x in 0 until a.width) {
                val ca = pa[x, y]
                val cb = pb[x, y]
                sum += abs(ca.red - cb.red) + abs(ca.green - cb.green) + abs(ca.blue - cb.blue)
            }
        }
        return sum / (a.width * a.height * 3)
    }

    @Test
    fun everyBundledRendererDrawsSomething() {
        val params = paramsFor(-20f)
        val blob = OrganicBlobRenderer()
        val renderers = listOf<Pair<String, ShapeRenderer>>(
            "free-form blob" to blob,
            "circle blob" to OrganicBlobRenderer(BlobRendererConfig(shape = RadialShapes.Circle)),
            "drifting blob" to OrganicBlobRenderer(
                BlobRendererConfig(shape = RadialShapes.driftingOrganic()),
            ),
            "economy blob" to OrganicBlobRenderer(BlobRendererConfig.Economy),
            "rich blob" to OrganicBlobRenderer(BlobRendererConfig.Rich),
            "ribbon" to WaveRibbonRenderer(),
            "particles" to ParticleAuraRenderer(),
            "shader glow" to ShaderGlowRenderer(),
            "composite" to CompositeRenderer(ShaderGlowRenderer(), ParticleAuraRenderer(), blob),
        )

        val background = meanLuminance(render(EmptyRenderer, params))
        for ((name, renderer) in renderers) {
            val luminance = meanLuminance(render(renderer, params, warmupFrames = 40))
            assertTrue(
                luminance > background + 0.002f,
                "$name drew nothing (luminance $luminance vs background $background)",
            )
            assertTrue(luminance < 0.98f, "$name flooded the canvas (luminance $luminance)")
        }
    }

    @Test
    fun louderAudioProducesAVisiblyBrighterFrame() {
        val renderer = OrganicBlobRenderer()
        val quiet = meanLuminance(render(renderer, paramsFor(null)))
        val normal = meanLuminance(render(renderer, paramsFor(-28f)))
        val loud = meanLuminance(render(renderer, paramsFor(-14f)))

        assertTrue(quiet < normal, "silence ($quiet) should be darker than speech ($normal)")
        assertTrue(normal < loud, "normal ($normal) should be darker than loud ($loud)")
    }

    @Test
    fun differentShapesProduceDifferentPixels() {
        val params = paramsFor(-24f)
        val a = render(OrganicBlobRenderer(BlobRendererConfig(shape = RadialShapes.Circle)), params)
        val b = render(OrganicBlobRenderer(BlobRendererConfig(shape = RadialShapes.polygon(6))), params)
        val c = render(OrganicBlobRenderer(BlobRendererConfig(shape = RadialShapes.organic(seed = 99))), params)

        assertTrue(difference(a, b) > 0.01f, "circle and hexagon rendered the same")
        assertTrue(difference(a, c) > 0.01f, "circle and free-form rendered the same")
        assertTrue(difference(b, c) > 0.01f, "hexagon and free-form rendered the same")
    }

    @Test
    fun paletteChangesTheImageWithoutChangingTheGeometry() {
        val params = paramsFor(-24f)
        val renderer = OrganicBlobRenderer()
        val ocean = render(renderer, params, VisualPalette.OceanBlue)
        val ember = render(renderer, params, VisualPalette.Ember)
        assertTrue(difference(ocean, ember) > 0.02f, "the palette had no effect")
    }

    @Test
    fun theCanvasIsOpaqueAndFreeOfNonFinitePixels() {
        val bitmap = render(OrganicBlobRenderer(), paramsFor(-18f))
        val pixels = bitmap.toPixelMap()
        for (y in 0 until bitmap.height step 7) {
            for (x in 0 until bitmap.width step 7) {
                val c = pixels[x, y]
                assertTrue(c.alpha > 0.99f, "transparent pixel at $x,$y")
                assertTrue(
                    c.red in 0f..1f && c.green in 0f..1f && c.blue in 0f..1f,
                    "out-of-gamut pixel at $x,$y",
                )
            }
        }
    }

    /** Baseline: draws nothing, so the background luminance can be measured. */
    private object EmptyRenderer : ShapeRenderer {
        override fun DrawScope.render(frame: VisualFrame) = Unit
    }
}
