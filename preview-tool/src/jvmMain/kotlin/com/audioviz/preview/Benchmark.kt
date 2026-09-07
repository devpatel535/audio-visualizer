package com.audioviz.preview

import com.audioviz.core.analysis.AnalyzerConfig
import com.audioviz.core.analysis.AudioAnalyzer
import com.audioviz.core.anim.AnimationConfig
import com.audioviz.core.anim.AnimationController
import com.audioviz.core.anim.VisualParams
import com.audioviz.core.capture.SyntheticAudioSource
import com.audioviz.core.color.VisualPalette
import com.audioviz.core.geometry.ParticleField
import com.audioviz.core.geometry.ParticleFieldConfig
import com.audioviz.core.geometry.PolarProfile
import com.audioviz.core.geometry.PolarProfileConfig
import com.audioviz.core.geometry.RadialShape
import com.audioviz.core.geometry.RadialShapes

/**
 * Measures the pure-Kotlin part of the pipeline.
 *
 * Everything here is `visualizer-core`, so the numbers are real measurements
 * rather than operation counts — on *this* machine, which is a desktop JVM and
 * not a phone. Treat them as a relative budget between stages and as a
 * regression guard, not as a device figure.
 *
 * Rasterisation is deliberately excluded: it happens in Skia on the GPU and is
 * device-bound, so a JVM measurement of it would be actively misleading.
 */
object Benchmark {

    private const val WARMUP_ITERATIONS = 20_000
    private const val TRIALS = 7

    fun run() {
        println("=== visualizer-core throughput ===")
        println("JVM ${System.getProperty("java.version")} on ${System.getProperty("os.arch")}, " +
            "${Runtime.getRuntime().availableProcessors()} cores")
        println()

        val results = listOf(
            benchmarkAnalyzer(AnalyzerConfig(), "analyzer, 1024/512 + FFT + 6 bands"),
            benchmarkAnalyzer(AnalyzerConfig.LevelOnly, "analyzer, level-only (no FFT)"),
            benchmarkController(),
            benchmarkProfile(RadialShapes.Organic, 128, "polar profile, 128 samples"),
            benchmarkProfile(RadialShapes.Organic, 64, "polar profile, 64 samples"),
            benchmarkProfile(RadialShapes.driftingOrganic(), 128, "polar profile, 128, dynamic shape"),
            benchmarkParticles(160),
            benchmarkPalette(),
        )

        val nameWidth = results.maxOf { it.name.length }
        println("%-${nameWidth}s  %11s  %10s  %13s".format("stage", "per call", "calls/s", "share of core"))
        println("-".repeat(nameWidth + 40))
        for (r in results) {
            println(
                "%-${nameWidth}s  %8.2f us  %9.1f  %11.3f %%".format(
                    r.name,
                    r.microsPerCall,
                    r.callsPerSecond,
                    r.percentOfCore,
                )
            )
        }
        println()
        val perFrame = results[2].microsPerCall + results[3].microsPerCall + results[6].microsPerCall
        println(
            "Render frame (controller + 128-sample profile + 160 particles): %.2f us = %.2f %% of a 16.67 ms frame"
                .format(perFrame, perFrame / 16_666.7 * 100.0)
        )
        println(
            "Audio + render together: %.3f %% of one core at 60 fps"
                .format(results[0].percentOfCore + perFrame * 60.0 / 10_000.0)
        )
    }

    private class Result(
        val name: String,
        val microsPerCall: Double,
        /** Calls per second of wall time, when the stage runs at a fixed rate. */
        val callsPerSecond: Double = 60.0,
    ) {
        /** Share of one core, as a percentage, at this stage's natural rate. */
        val percentOfCore: Double get() = microsPerCall * callsPerSecond / 10_000.0
    }

    /**
     * Times [body] with warm-up and several trials, reporting the median so a
     * GC pause or a scheduler hiccup in one trial cannot skew the result.
     */
    private fun measure(name: String, iterations: Int, body: () -> Unit): Result {
        repeat(WARMUP_ITERATIONS.coerceAtMost(iterations * 4)) { body() }
        val samples = DoubleArray(TRIALS)
        for (trial in 0 until TRIALS) {
            val start = System.nanoTime()
            repeat(iterations) { body() }
            samples[trial] = (System.nanoTime() - start) / 1_000.0 / iterations
        }
        samples.sort()
        return Result(name, samples[TRIALS / 2])
    }

    private fun benchmarkAnalyzer(config: AnalyzerConfig, name: String): Result {
        val analyzer = AudioAnalyzer(config)
        // Pre-generate the audio. Filling it inside the timed loop would fold
        // the signal generator's cost — eight harmonics and a noise source per
        // sample — into the analyzer's figure, which is most of what a first
        // pass at this benchmark measured.
        val source = SyntheticAudioSource(levelDb = -24f)
        val corpus = FloatArray(config.hopSize * 64)
        source.fill(corpus)

        var offset = 0
        // One call per analysis frame's worth of samples, so the number is
        // "cost per analysis frame" rather than "cost per arbitrary block".
        val framesPerSecond = 48_000.0 / config.hopSize
        val timed = measure(name, 20_000) {
            analyzer.onAudioFrame(corpus, offset, config.hopSize, 48_000)
            offset += config.hopSize
            if (offset + config.hopSize > corpus.size) offset = 0
        }
        return Result(timed.name, timed.microsPerCall, framesPerSecond)
    }

    private fun benchmarkController(): Result {
        val analyzer = AudioAnalyzer(AnalyzerConfig())
        val controller = AnimationController(AnimationConfig.Default)
        val source = SyntheticAudioSource(levelDb = -24f)
        val block = FloatArray(512)
        repeat(200) {
            source.fill(block)
            analyzer.onAudioFrame(block, 0, block.size, 48_000)
        }
        val features = analyzer.features
        return measure("animation controller", 200_000) {
            controller.update(features, 1f / 60f)
        }
    }

    private fun benchmarkProfile(shape: RadialShape, samples: Int, name: String): Result {
        val params = settledParams()
        val profile = PolarProfile(PolarProfileConfig(sampleCount = samples))
        return measure(name, 50_000) { profile.update(shape, params) }
    }

    private fun benchmarkParticles(capacity: Int): Result {
        val params = settledParams()
        val field = ParticleField(ParticleFieldConfig(capacity = capacity, emissionRate = 400f))
        val emitter = RadialShape { 1f }
        // Fill the pool first so the measurement reflects a saturated system.
        repeat(2_000) { field.update(params, emitter) }
        return measure("particle field, $capacity", 50_000) { field.update(params, emitter) }
    }

    private fun benchmarkPalette(): Result {
        val palette = VisualPalette.OceanBlue.gradient
        val out = FloatArray(4)
        var t = 0f
        val result = measure("palette sample (LUT)", 2_000_000) {
            t += 0.0007f
            if (t > 1f) t -= 1f
            palette.sample(t, out)
        }
        // Keep the JIT from eliminating the work entirely.
        if (out[0] < -1f) println("unreachable ${out[0]}")
        return result
    }

    private fun settledParams(): VisualParams {
        val analyzer = AudioAnalyzer(AnalyzerConfig())
        val controller = AnimationController(AnimationConfig.Default)
        val source = SyntheticAudioSource(levelDb = -20f)
        val block = FloatArray(512)
        var params = controller.current
        repeat(400) {
            source.fill(block)
            analyzer.onAudioFrame(block, 0, block.size, 48_000)
            params = controller.update(analyzer.features, 1f / 60f)
        }
        return params
    }
}

fun main() {
    Benchmark.run()
}
