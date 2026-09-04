package com.audioviz.core

import com.audioviz.core.analysis.AnalyzerConfig
import com.audioviz.core.analysis.AudioAnalyzer
import com.audioviz.core.anim.AnimationConfig
import com.audioviz.core.anim.AnimationController
import com.audioviz.core.anim.VisualParams
import com.audioviz.core.capture.SilentAudioSource
import com.audioviz.core.capture.SyntheticAudioSource
import kotlin.math.abs

/**
 * Drives the full capture -> analyzer -> controller chain in real time, off any
 * thread, so behaviour can be asserted rather than eyeballed.
 *
 * Audio is produced at the sample rate and frames are stepped at [frameRate],
 * exactly as the real host does — including the fact that several audio blocks
 * can land between two frames, and that a frame can arrive with no new audio
 * at all.
 */
class PipelineRig(
    analyzerConfig: AnalyzerConfig = AnalyzerConfig.Deterministic,
    animationConfig: AnimationConfig = AnimationConfig.Default,
    private val sampleRate: Int = 48_000,
    private val frameRate: Float = 60f,
    private val blockSize: Int = 256,
) {
    val analyzer = AudioAnalyzer(analyzerConfig)
    val controller = AnimationController(animationConfig)

    private val block = FloatArray(blockSize)
    private var sampleDebt = 0f

    fun run(seconds: Float, fill: (FloatArray) -> Unit, observe: (VisualParams) -> Unit = {}) {
        val dt = 1f / frameRate
        val samplesPerFrame = sampleRate / frameRate
        var elapsed = 0f
        while (elapsed < seconds) {
            sampleDebt += samplesPerFrame
            while (sampleDebt >= blockSize) {
                fill(block)
                analyzer.onAudioFrame(block, 0, blockSize, sampleRate)
                sampleDebt -= blockSize
            }
            observe(controller.update(analyzer.features, dt))
            elapsed += dt
        }
    }
}

/** Rolling statistics for one scalar parameter. */
class Trace(val name: String) {
    var count = 0
        private set
    var sum = 0.0
        private set
    var minimum = Float.MAX_VALUE
        private set
    var maximum = -Float.MAX_VALUE
        private set
    var maxFrameDelta = 0f
        private set
    var sawNonFinite = false
        private set

    private var previous = Float.NaN

    fun add(value: Float) {
        if (!value.isFinite()) {
            sawNonFinite = true
            return
        }
        count++
        sum += value.toDouble()
        if (value < minimum) minimum = value
        if (value > maximum) maximum = value
        if (!previous.isNaN()) {
            val delta = abs(value - previous)
            if (delta > maxFrameDelta) maxFrameDelta = delta
        }
        previous = value
    }

    val mean: Float get() = if (count == 0) 0f else (sum / count).toFloat()
    val range: Float get() = if (count == 0) 0f else maximum - minimum

    override fun toString(): String =
        "$name: mean=${fmt(mean)} min=${fmt(minimum)} max=${fmt(maximum)} maxStep=${fmt(maxFrameDelta)}"

    private fun fmt(v: Float): String {
        val scaled = (v * 1000f).toInt() / 1000f
        return scaled.toString()
    }
}

/** Every parameter of interest, traced together. */
class ParamTraces(private val settleSeconds: Float = 0f, private val frameRate: Float = 60f) {
    val intensity = Trace("intensity")
    val pulse = Trace("pulse")
    val deformation = Trace("deformation")
    val turbulence = Trace("turbulence")
    val scale = Trace("scale")
    val glow = Trace("glow")
    val colorMix = Trace("colorMix")
    val opacity = Trace("opacity")
    val rotation = Trace("rotation")
    val idle = Trace("idle")

    private var frames = 0

    val observer: (VisualParams) -> Unit = { p ->
        frames++
        if (frames >= settleSeconds * frameRate) {
            intensity.add(p.intensity)
            pulse.add(p.pulse)
            deformation.add(p.deformation)
            turbulence.add(p.turbulence)
            scale.add(p.scale)
            glow.add(p.glow)
            colorMix.add(p.colorMix)
            opacity.add(p.opacity)
            rotation.add(p.rotationVelocity)
            idle.add(p.idle)
        }
    }

    val all: List<Trace>
        get() = listOf(
            intensity, pulse, deformation, turbulence,
            scale, glow, colorMix, opacity, rotation, idle,
        )

    fun report(label: String): String =
        buildString {
            appendLine("=== $label ===")
            all.forEach { appendLine("  $it") }
        }
}

/** Speech at a given level, plus the room tone that always accompanies it. */
fun speechFiller(levelDb: Float, sampleRate: Int = 48_000): (FloatArray) -> Unit {
    val source = SyntheticAudioSource(sampleRate = sampleRate, levelDb = levelDb)
    return { buffer -> source.fill(buffer) }
}

/** An empty room: dither only, well below any sane gate threshold. */
fun silenceFiller(roomToneDb: Float = -72f, sampleRate: Int = 48_000): (FloatArray) -> Unit {
    val source = SilentAudioSource(sampleRate = sampleRate, roomToneDb = roomToneDb)
    return { buffer -> source.fill(buffer) }
}
