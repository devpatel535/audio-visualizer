package com.audioviz.core

import com.audioviz.core.dsp.RealFft
import com.audioviz.core.dsp.WindowFunction
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RealFftTest {

    /** Reference implementation: O(n^2) DFT, used only to validate the fast path. */
    private fun naiveMagnitudes(input: FloatArray): FloatArray {
        val n = input.size
        val out = FloatArray(n / 2 + 1)
        for (k in out.indices) {
            var re = 0.0
            var im = 0.0
            for (t in 0 until n) {
                val angle = -2.0 * PI * k * t / n
                re += input[t] * cos(angle)
                im += input[t] * sin(angle)
            }
            out[k] = sqrt(re * re + im * im).toFloat()
        }
        return out
    }

    @Test
    fun matchesNaiveDftOnRandomInput() {
        val n = 256
        var seed = 987654321L
        val input = FloatArray(n) {
            seed = (seed * 6364136223846793005L + 1442695040888963407L)
            ((seed ushr 33).toFloat() / Int.MAX_VALUE.toFloat()) - 0.5f
        }

        val fft = RealFft(n, WindowFunction.RECTANGULAR)
        val fast = FloatArray(fft.binCount)
        fft.magnitudes(input, 0, fast)

        val reference = naiveMagnitudes(input)
        // The fast path is amplitude-normalized (2/N, and 1/N at DC and Nyquist).
        for (k in reference.indices) {
            val scale = if (k == 0 || k == n / 2) 1f / n else 2f / n
            val expected = reference[k] * scale
            assertTrue(
                abs(expected - fast[k]) < 1e-4f,
                "bin $k: expected $expected, got ${fast[k]}",
            )
        }
    }

    @Test
    fun recoversSineAmplitudeAtAnExactBin() {
        val n = 1024
        val bin = 32
        val amplitude = 0.4f
        val input = FloatArray(n) { amplitude * sin(2f * PI.toFloat() * bin * it / n) }

        val fft = RealFft(n, WindowFunction.HANN)
        val out = FloatArray(fft.binCount)
        fft.magnitudes(input, 0, out)

        assertEquals(amplitude, out[bin], 0.02f, "window-compensated amplitude should survive")

        // Energy must be concentrated: neighbours far from the peak stay tiny.
        assertTrue(out[bin + 6] < amplitude * 0.02f, "leakage too high: ${out[bin + 6]}")
    }

    @Test
    fun dcIsReportedAtBinZero() {
        val n = 128
        val input = FloatArray(n) { 0.25f }
        val fft = RealFft(n, WindowFunction.RECTANGULAR)
        val out = FloatArray(fft.binCount)
        fft.magnitudes(input, 0, out)
        assertEquals(0.25f, out[0], 1e-4f)
        assertTrue(out[1] < 1e-4f)
    }

    @Test
    fun rejectsNonPowerOfTwoSizes() {
        var threw = false
        try {
            RealFft(300)
        } catch (_: IllegalArgumentException) {
            threw = true
        }
        assertTrue(threw, "a non-power-of-two size must be rejected")
    }
}
