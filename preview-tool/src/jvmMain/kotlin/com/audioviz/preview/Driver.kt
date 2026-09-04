package com.audioviz.preview

import com.audioviz.core.analysis.AnalyzerConfig
import com.audioviz.core.analysis.AudioAnalyzer
import com.audioviz.core.anim.AnimationConfig
import com.audioviz.core.anim.AnimationController
import com.audioviz.core.anim.VisualParams

/**
 * Runs the real capture → analyzer → controller chain at a simulated frame rate.
 *
 * Identical in structure to the host composable's frame loop, minus Compose: an
 * audio generator fills blocks, the analyzer consumes them, and the controller
 * is stepped once per simulated display frame.
 */
class Driver(
    analyzerConfig: AnalyzerConfig = AnalyzerConfig.Deterministic,
    animationConfig: AnimationConfig = AnimationConfig.Default,
    private val sampleRate: Int = 48_000,
    private val frameRate: Float = 60f,
    private val blockSize: Int = 256,
) {
    val analyzer = AudioAnalyzer(analyzerConfig)
    val controller = AnimationController(animationConfig, bandCapacity = analyzerConfig.bandCount)

    private val block = FloatArray(blockSize)
    private var sampleDebt = 0f

    /** Steps one display frame and returns the parameters for it. */
    fun step(fill: (FloatArray) -> Unit): VisualParams {
        sampleDebt += sampleRate / frameRate
        while (sampleDebt >= blockSize) {
            fill(block)
            analyzer.onAudioFrame(block, 0, blockSize, sampleRate)
            sampleDebt -= blockSize
        }
        return controller.update(analyzer.features, 1f / frameRate)
    }

    /** Steps [seconds] worth of frames, discarding the output. */
    fun settle(seconds: Float, fill: (FloatArray) -> Unit) {
        repeat((seconds * frameRate).toInt()) { step(fill) }
    }
}
