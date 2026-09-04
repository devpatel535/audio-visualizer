package com.audioviz.compose

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import com.audioviz.compose.render.ShapeRenderer
import com.audioviz.core.analysis.AnalyzerConfig
import com.audioviz.core.anim.AnimationConfig
import com.audioviz.core.capture.AudioSource
import com.audioviz.core.color.VisualPalette
import kotlinx.coroutines.isActive
import kotlin.coroutines.coroutineContext
import kotlin.math.min

/**
 * Remembers a [VisualizerState] for the lifetime of the composition.
 *
 * @param source where audio comes from. Note the type: this module knows only
 *   the [AudioSource] interface, so a host can pass a real microphone, a
 *   synthetic generator, a file reader or a test double without any change
 *   here.
 * @param autoStart start capture as soon as the composable enters the
 *   composition. Set false when a permission prompt or a user gesture has to
 *   come first, and call [VisualizerState.start] yourself.
 */
@Composable
fun rememberVisualizerState(
    source: AudioSource,
    analyzerConfig: AnalyzerConfig = AnalyzerConfig(),
    animationConfig: AnimationConfig = AnimationConfig.Default,
    palette: VisualPalette = VisualPalette.OceanBlue,
    autoStart: Boolean = true,
): VisualizerState {
    val state = remember(source) {
        createVisualizerState(source, analyzerConfig, animationConfig, palette)
    }

    // Configuration can change at runtime (a settings screen, a remote config)
    // without recreating the analyzer or losing its adapted noise floor.
    LaunchedEffect(state, analyzerConfig) { state.analyzer.config = analyzerConfig }
    LaunchedEffect(state, animationConfig) { state.controller.config = animationConfig }

    DisposableEffect(state, autoStart) {
        if (autoStart) state.start()
        onDispose { state.stop() }
    }

    return state
}

/**
 * Draws an audio-reactive visual.
 *
 * ```
 * val source = remember { createMicrophoneSource() }
 * val state = rememberVisualizerState(source)
 * AudioVisualizer(
 *     renderer = remember { OrganicBlobRenderer() },
 *     state = state,
 *     modifier = Modifier.fillMaxSize(),
 * )
 * ```
 *
 * To use a different geometry, pass a different [ShapeRenderer]. Nothing else
 * changes — not the analyzer, not the controller, not this composable.
 *
 * @param radiusFraction the reference radius as a fraction of half the smaller
 *   canvas dimension. Renderers scale their geometry to it, so this is the one
 *   knob that controls how large the visual is regardless of shape.
 * @param drawBackground fill the canvas with the palette's background first.
 *   Turn it off to composite the visual over existing content.
 */
@Composable
fun AudioVisualizer(
    renderer: ShapeRenderer,
    state: VisualizerState,
    modifier: Modifier = Modifier,
    radiusFraction: Float = 0.62f,
    drawBackground: Boolean = true,
) {
    // One coroutine, one frame callback, for the life of the composable. Every
    // parameter update happens here; the draw phase only reads.
    LaunchedEffect(state) {
        while (coroutineContext.isActive) {
            withFrameNanos { nanos -> state.advance(nanos) }
        }
    }

    val surface = remember(renderer) { SurfaceTracker() }

    Canvas(modifier) {
        // Reading the tick subscribes the *draw* phase to the frame clock, so a
        // new frame repaints without recomposing anything.
        @Suppress("UNUSED_EXPRESSION")
        state.frameTick

        if (surface.update(size, density)) {
            renderer.onSurfaceChanged(size, density)
        }

        val params = state.params
        val frame = state.frame

        val reference = min(size.width, size.height) * 0.5f * radiusFraction

        frame.params = params
        frame.palette = state.renderPalette
        frame.size = size
        frame.density = density
        frame.referenceRadius = reference
        frame.center = Offset(
            x = size.width * 0.5f + params.driftX * reference,
            y = size.height * 0.5f + params.driftY * reference,
        )

        if (drawBackground) {
            drawRect(color = state.renderPalette.background)
        }

        with(renderer) { render(frame) }
    }
}

/** Detects canvas size/density changes so renderers can rebuild size-dependent state. */
private class SurfaceTracker {
    private var lastSize = Size.Unspecified
    private var lastDensity = 0f

    fun update(size: Size, density: Float): Boolean {
        if (size == lastSize && density == lastDensity) return false
        lastSize = size
        lastDensity = density
        return true
    }
}
