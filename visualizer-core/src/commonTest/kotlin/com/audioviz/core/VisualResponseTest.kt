package com.audioviz.core

import com.audioviz.core.analysis.AnalyzerConfig
import com.audioviz.core.anim.AnimationConfig
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * End-to-end behavioural tests: synthetic speech in, visual parameters out.
 *
 * These are the automated form of the manual "speak quietly / normally / loudly"
 * check in the README, and they are what stops a tuning change from silently
 * turning the visualizer into a strobe or into a corpse.
 */
class VisualResponseTest {

    private val settle = 3f
    private val duration = 12f

    private fun measure(
        fill: (FloatArray) -> Unit,
        frameRate: Float = 60f,
        analyzerConfig: AnalyzerConfig = AnalyzerConfig.Deterministic,
        animationConfig: AnimationConfig = AnimationConfig.Default,
    ): ParamTraces {
        val traces = ParamTraces(settleSeconds = settle, frameRate = frameRate)
        PipelineRig(analyzerConfig, animationConfig, frameRate = frameRate)
            .run(duration, fill, traces.observer)
        return traces
    }

    @Test
    fun respondsMonotonicallyToSpeechLevel() {
        val silent = measure(silenceFiller())
        val quiet = measure(speechFiller(-42f))
        val normal = measure(speechFiller(-28f))
        val loud = measure(speechFiller(-14f))

        println(silent.report("silence"))
        println(quiet.report("quiet speech (-42 dBFS)"))
        println(normal.report("normal speech (-28 dBFS)"))
        println(loud.report("loud speech (-14 dBFS)"))

        assertTrue(
            silent.intensity.mean < quiet.intensity.mean,
            "quiet speech must read above silence (${silent.intensity.mean} vs ${quiet.intensity.mean})",
        )
        assertTrue(
            quiet.intensity.mean < normal.intensity.mean,
            "normal speech must read above quiet (${quiet.intensity.mean} vs ${normal.intensity.mean})",
        )
        assertTrue(
            normal.intensity.mean < loud.intensity.mean,
            "loud speech must read above normal (${normal.intensity.mean} vs ${loud.intensity.mean})",
        )
    }

    @Test
    fun silenceIsCalmButNotDead() {
        val silent = measure(silenceFiller())

        assertTrue(silent.intensity.maximum < 0.05f, "silence produced intensity ${silent.intensity.maximum}")
        assertTrue(silent.pulse.maximum < 0.06f, "silence produced pulse ${silent.pulse.maximum}")
        assertTrue(silent.idle.mean > 0.95f, "the visual should read as idle, got ${silent.idle.mean}")

        // Alive: the object still has form and still turns.
        assertTrue(
            silent.deformation.mean > 0.10f,
            "an idle visual must keep some form, got ${silent.deformation.mean}",
        )
        assertTrue(
            silent.rotation.mean > 0.01f,
            "an idle visual must keep rotating, got ${silent.rotation.mean}",
        )
        assertTrue(
            silent.scale.range < 0.05f,
            "an idle visual must not pulse, scale ranged ${silent.scale.range}",
        )
    }

    @Test
    fun loudSpeechDoesNotSimplyInflateTheShape() {
        // The explicit anti-requirement: "avoid a simple scale-up-with-volume
        // effect". Scale must stay a minor contributor even when shouting.
        val loud = measure(speechFiller(-8f))

        assertTrue(
            loud.scale.maximum < 1.20f,
            "scale ran away to ${loud.scale.maximum}; the shape is just inflating",
        )

        // The requirement stated as a ratio: whatever scale contributes, the
        // shape-changing parameters must contribute several times more.
        val scaleResponse = loud.scale.mean - 1f
        val deformationResponse = loud.deformation.mean - AnimationConfig.Default.idleDeformation
        assertTrue(
            deformationResponse > scaleResponse * 5f,
            "scale is carrying the reaction (scale +$scaleResponse vs deformation +$deformationResponse)",
        )

        // ...and they must move at syllable rate, not merely settle at a new offset.
        assertTrue(loud.glow.range > 0.05f, "glow barely moved (${loud.glow.range})")
        assertTrue(
            loud.deformation.maxFrameDelta > 0.002f,
            "deformation is frozen frame to frame; syllables are being smoothed away",
        )
        assertTrue(
            loud.glow.maxFrameDelta > 0.005f,
            "glow is frozen frame to frame; syllables are being smoothed away",
        )
    }

    @Test
    fun nothingJumpsBetweenFrames() {
        val loud = measure(speechFiller(-12f))

        // Glow and colour are rate-limited upstream, so these are hard bounds
        // derived from the configuration rather than empirical thresholds.
        val cfg = AnimationConfig.Default
        val frame = 1f / 60f
        assertTrue(
            loud.glow.maxFrameDelta <= cfg.glowRisePerSecond * frame + 1e-3f,
            "glow stepped ${loud.glow.maxFrameDelta} in one frame",
        )
        assertTrue(
            loud.colorMix.maxFrameDelta <= cfg.colorRisePerSecond * frame + 1e-3f,
            "colour stepped ${loud.colorMix.maxFrameDelta} in one frame",
        )
        assertTrue(
            loud.deformation.maxFrameDelta < 0.10f,
            "deformation stepped ${loud.deformation.maxFrameDelta} in one frame",
        )
        assertTrue(
            loud.scale.maxFrameDelta < 0.03f,
            "scale stepped ${loud.scale.maxFrameDelta} in one frame",
        )
    }

    @Test
    fun behavesTheSameAtEveryFrameRate() {
        val at30 = measure(speechFiller(-26f), frameRate = 30f)
        val at60 = measure(speechFiller(-26f), frameRate = 60f)
        val at120 = measure(speechFiller(-26f), frameRate = 120f)

        for ((label, a, b) in listOf(
            Triple("intensity", at30.intensity.mean, at120.intensity.mean),
            Triple("deformation", at30.deformation.mean, at120.deformation.mean),
            Triple("glow", at30.glow.mean, at120.glow.mean),
            Triple("colorMix", at30.colorMix.mean, at120.colorMix.mean),
        )) {
            assertTrue(
                abs(a - b) < 0.06f,
                "$label differs across frame rates: 30 fps=$a, 120 fps=$b (60 fps reference " +
                    "${at60.intensity.mean})",
            )
        }
    }

    @Test
    fun everyParameterStaysInRangeAndFinite() {
        for (level in listOf(-60f, -42f, -28f, -14f, -3f)) {
            val traces = measure(speechFiller(level))
            for (trace in traces.all) {
                assertTrue(!trace.sawNonFinite, "${trace.name} produced NaN/Inf at $level dBFS")
            }
            for (trace in listOf(
                traces.intensity, traces.pulse, traces.deformation,
                traces.turbulence, traces.glow, traces.colorMix,
                traces.opacity, traces.idle,
            )) {
                assertTrue(
                    trace.minimum >= 0f && trace.maximum <= 1f,
                    "${trace.name} escaped 0..1 at $level dBFS: ${trace.minimum}..${trace.maximum}",
                )
            }
            assertTrue(
                traces.scale.minimum > 0.5f && traces.scale.maximum < 2f,
                "scale escaped a sane range at $level dBFS: ${traces.scale.minimum}..${traces.scale.maximum}",
            )
        }
    }

    @Test
    fun adaptiveNormalizationEqualisesMicrophoneGain() {
        // Same speech, 18 dB apart, adaptive mode: the visual must land in the
        // same place once the floor and loud reference have settled.
        val config = AnalyzerConfig()
        val quietMic = measure(speechFiller(-40f), analyzerConfig = config)
        val hotMic = measure(speechFiller(-22f), analyzerConfig = config)

        println(quietMic.report("adaptive, quiet mic"))
        println(hotMic.report("adaptive, hot mic"))

        assertTrue(
            abs(quietMic.intensity.mean - hotMic.intensity.mean) < 0.18f,
            "adaptive normalization failed: ${quietMic.intensity.mean} vs ${hotMic.intensity.mean}",
        )
    }

    @Test
    fun presetsRemainWithinTheirCharacter() {
        val subtle = measure(speechFiller(-20f), animationConfig = AnimationConfig.Subtle)
        val expressive = measure(speechFiller(-20f), animationConfig = AnimationConfig.Expressive)

        assertTrue(
            subtle.deformation.mean < expressive.deformation.mean,
            "Subtle should move less than Expressive " +
                "(${subtle.deformation.mean} vs ${expressive.deformation.mean})",
        )
        assertTrue(
            subtle.scale.range < expressive.scale.range,
            "Subtle should scale less than Expressive",
        )
    }
}
