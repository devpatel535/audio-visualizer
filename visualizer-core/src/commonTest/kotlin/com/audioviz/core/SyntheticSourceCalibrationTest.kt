package com.audioviz.core

import com.audioviz.core.capture.SyntheticAudioSource
import com.audioviz.core.util.amplitudeToDb
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertTrue

class SyntheticSourceCalibrationTest {

    private fun measureDb(levelDb: Float): Float {
        val source = SyntheticAudioSource(levelDb = levelDb, noiseFloorDb = -120f)
        val buffer = FloatArray(48_000)
        var sum = 0.0
        repeat(4) {
            source.fill(buffer)
            for (v in buffer) sum += v.toDouble() * v
        }
        return amplitudeToDb(sqrt(sum / (buffer.size * 4)).toFloat())
    }

    @Test
    fun generatorHitsItsRequestedLevel() {
        for (target in listOf(-45f, -30f, -18f, -8f)) {
            val measured = measureDb(target)
            println("requested $target dBFS -> measured $measured dBFS")
            assertTrue(
                kotlin.math.abs(measured - target) < 2f,
                "generator is miscalibrated: asked for $target dBFS, produced $measured dBFS",
            )
        }
    }
}
