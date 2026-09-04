package com.audioviz.core

import com.audioviz.core.dsp.LevelNormalizer
import com.audioviz.core.dsp.NoiseGate
import com.audioviz.core.dsp.NormalizationMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LevelNormalizerTest {

    private val dt = 1f / 90f

    @Test
    fun adaptiveFloorSettlesOnTheRoomTone() {
        val normalizer = LevelNormalizer()
        repeat(2000) { normalizer.update(-58f, dt, gateOpenMarginDb = 9f) }
        assertEquals(-58f, normalizer.noiseFloorDb, 1.5f)
    }

    @Test
    fun quietAndLoudMicrophonesConvergeOnTheSameNormalizedLevel() {
        // The requirement in one test: a hot mic and a quiet mic, offset by 20 dB,
        // must drive the visual identically once the floor has adapted.
        fun run(offset: Float): Float {
            val normalizer = LevelNormalizer()
            var level = 0f
            repeat(3000) { i ->
                // Alternating background / speech, as real input does.
                val db = if ((i / 40) % 3 == 0) -60f + offset else -28f + offset
                level = normalizer.update(db, dt, gateOpenMarginDb = 9f)
            }
            return level
        }

        val quiet = run(0f)
        val loud = run(20f)
        assertTrue(
            kotlin.math.abs(quiet - loud) < 0.1f,
            "normalization failed to equalise gain: quiet=$quiet loud=$loud",
        )
    }

    @Test
    fun fixedModeIsExactlyReproducible() {
        val normalizer = LevelNormalizer(NormalizationMode.Fixed(floorDb = -55f, ceilingDb = -15f))
        // Gate margin 0 => the mapping is a plain linear ramp between the bounds.
        assertEquals(0f, normalizer.update(-55f, dt, 0f), 1e-4f)
        assertEquals(1f, normalizer.update(-15f, dt, 0f), 1e-4f)
        assertEquals(0.5f, normalizer.update(-35f, dt, 0f), 1e-3f)
    }

    @Test
    fun loudReferenceDecaysAfterAShout() {
        val normalizer = LevelNormalizer(loudDecayDbPerSecond = 6f)
        repeat(200) { normalizer.update(-50f, dt, 9f) }
        normalizer.update(-6f, dt, 9f)
        val afterShout = normalizer.loudReferenceDb
        repeat((2f / dt).toInt()) { normalizer.update(-50f, dt, 9f) }
        assertTrue(
            normalizer.loudReferenceDb < afterShout - 8f,
            "loud reference did not decay: $afterShout -> ${normalizer.loudReferenceDb}",
        )
    }

    @Test
    fun gateHysteresisPreventsChatter() {
        val gate = NoiseGate(openMarginDb = 9f, closeMarginDb = 5f, holdSeconds = 0f)
        val floor = -60f

        // Sitting exactly between the two thresholds must not toggle the gate.
        repeat(50) { gate.update(floor + 12f, floor, dt) }
        assertTrue(gate.isOpen)
        repeat(50) { gate.update(floor + 7f, floor, dt) }
        assertTrue(gate.isOpen, "gate closed inside the hysteresis band")

        repeat(200) { gate.update(floor + 1f, floor, dt) }
        assertTrue(!gate.isOpen, "gate did not close below the close threshold")
    }

    @Test
    fun gateHoldsThroughShortGaps() {
        val gate = NoiseGate(holdSeconds = 0.3f)
        val floor = -60f
        repeat(50) { gate.update(floor + 20f, floor, dt) }
        // A 150 ms gap between words: the gate must stay open.
        repeat((0.15f / dt).toInt()) { gate.update(floor - 5f, floor, dt) }
        assertTrue(gate.isOpen, "gate dropped out during a normal inter-word gap")
        // A 1 s silence: it must close.
        repeat((1f / dt).toInt()) { gate.update(floor - 5f, floor, dt) }
        assertTrue(!gate.isOpen)
    }

    @Test
    fun gateOutputIsSmooth() {
        val gate = NoiseGate(attackTau = 0.05f)
        val floor = -60f
        var previous = gate.value
        var largestStep = 0f
        repeat(200) {
            val v = gate.update(floor + 20f, floor, dt)
            val step = kotlin.math.abs(v - previous)
            if (step > largestStep) largestStep = step
            previous = v
        }
        assertTrue(largestStep < 0.3f, "gate switched instead of fading: step $largestStep")
    }
}
