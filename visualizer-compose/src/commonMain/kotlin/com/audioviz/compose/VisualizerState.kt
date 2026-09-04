package com.audioviz.compose

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.audioviz.compose.render.RenderPalette
import com.audioviz.compose.render.VisualFrame
import com.audioviz.core.analysis.AnalyzerConfig
import com.audioviz.core.analysis.AudioAnalyzer
import com.audioviz.core.anim.AnimationConfig
import com.audioviz.core.anim.AnimationController
import com.audioviz.core.anim.VisualParams
import com.audioviz.core.capture.AudioSource
import com.audioviz.core.capture.AudioSourceStatus
import com.audioviz.core.color.VisualPalette

/**
 * Owns one visualizer instance: the analyzer, the animation controller, the
 * audio source and the per-frame parameter object.
 *
 * ### Threading
 * [AudioAnalyzer.onAudioFrame] runs wherever the platform's capture thread is;
 * everything in this class runs on the UI thread. The only thing crossing
 * between them is the immutable [com.audioviz.core.analysis.AudioFeatures]
 * snapshot the analyzer publishes, read once per frame in [advance].
 *
 * ### Why the frame counter exists
 * Compose redraws when a snapshot state that the *draw* phase reads changes.
 * Bumping [frameTick] from the frame callback invalidates only the draw phase —
 * no recomposition, no layout, no allocation of a new composition scope, 60-120
 * times a second. Storing the parameters themselves in a `State` would
 * recompose the whole subtree every frame instead.
 */
@Stable
class VisualizerState internal constructor(
    val analyzer: AudioAnalyzer,
    val controller: AnimationController,
    palette: VisualPalette,
    private val source: AudioSource,
) {
    internal val frame = VisualFrame()
    internal val renderPalette = RenderPalette(palette)

    /**
     * Bumped once per rendered frame.
     *
     * Read it **inside a draw lambda** — `Canvas`, `drawBehind`, `drawWithCache`
     * — to make that drawing repaint every frame without recomposing anything:
     *
     * ```
     * Canvas(modifier) {
     *     state.frameTick          // subscribes the draw phase to the frame clock
     *     val p = state.params     // now safe to read; it changes every frame
     *     // ...draw a meter, an fps graph, a debug overlay...
     * }
     * ```
     *
     * Reading it during *composition* instead would recompose the subtree 60-120
     * times a second, which is exactly what this design exists to avoid.
     */
    var frameTick by mutableIntStateOf(0)
        private set

    /** Live capture status, as Compose state. */
    var status: AudioSourceStatus by mutableStateOf(source.status)
        private set

    /** Measured frames per second, smoothed. Useful for an on-screen readout. */
    var framesPerSecond: Float by mutableStateOf(0f)
        private set

    /** The most recent animation parameters. Valid between frames. */
    var params: VisualParams = controller.current
        private set

    private var previousFrameNanos = 0L
    private var fpsAccumulator = 0f
    private var fpsFrames = 0
    private var started = false

    /** Starts capture. Safe to call repeatedly. */
    fun start() {
        if (started) return
        started = true
        analyzer.reset()
        controller.reset()
        source.start(analyzer)
        status = source.status
    }

    /** Stops capture and releases the device. */
    fun stop() {
        if (!started) return
        started = false
        source.stop()
        status = source.status
        previousFrameNanos = 0L
    }

    /**
     * Advances one display frame.
     *
     * @param frameTimeNanos the value handed to `withFrameNanos`; using the
     *   frame clock rather than a wall clock keeps `dt` aligned with what the
     *   compositor will actually present.
     */
    internal fun advance(frameTimeNanos: Long) {
        val deltaSeconds = if (previousFrameNanos == 0L) {
            DEFAULT_FRAME_SECONDS
        } else {
            (frameTimeNanos - previousFrameNanos) / NANOS_PER_SECOND
        }
        previousFrameNanos = frameTimeNanos

        // Pull-based sources (the browser, the synthetic generator) deliver
        // here; push-based ones ignore it.
        source.pump(deltaSeconds)

        params = controller.update(analyzer.features, deltaSeconds)
        renderPalette.resolve(params)

        val currentStatus = source.status
        if (currentStatus != status) status = currentStatus

        fpsAccumulator += deltaSeconds
        fpsFrames++
        if (fpsAccumulator >= FPS_WINDOW_SECONDS) {
            framesPerSecond = fpsFrames / fpsAccumulator
            fpsAccumulator = 0f
            fpsFrames = 0
        }

        frameTick++
    }

    private companion object {
        const val NANOS_PER_SECOND = 1_000_000_000f
        const val DEFAULT_FRAME_SECONDS = 1f / 60f
        const val FPS_WINDOW_SECONDS = 0.5f
    }
}

/** Creates a [VisualizerState]; see the `rememberVisualizerState` composable. */
internal fun createVisualizerState(
    source: AudioSource,
    analyzerConfig: AnalyzerConfig,
    animationConfig: AnimationConfig,
    palette: VisualPalette,
): VisualizerState = VisualizerState(
    analyzer = AudioAnalyzer(analyzerConfig),
    controller = AnimationController(
        config = animationConfig,
        bandCapacity = analyzerConfig.bandCount.coerceAtLeast(1),
    ),
    palette = palette,
    source = source,
)
