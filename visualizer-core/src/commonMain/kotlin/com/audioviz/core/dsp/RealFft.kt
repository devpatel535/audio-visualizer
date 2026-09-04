package com.audioviz.core.dsp

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Real-input FFT, allocation-free after construction.
 *
 * A real signal of length `N` is transformed with an `N/2`-point complex FFT
 * (the standard real-input packing), which halves both the butterfly count and
 * the memory traffic versus zero-padding the imaginary part. Twiddle factors,
 * the bit-reversal permutation and the analysis window are all precomputed in
 * the constructor, so [magnitudes] performs no allocation and no trigonometry.
 *
 * @param size FFT size; must be a power of two and at least 4.
 * @param window analysis window applied before the transform.
 */
class RealFft(
    val size: Int,
    window: WindowFunction = WindowFunction.HANN,
) {
    init {
        require(size >= 4) { "FFT size must be >= 4, was $size" }
        require(size and (size - 1) == 0) { "FFT size must be a power of two, was $size" }
    }

    /** Number of usable magnitude bins, including DC and Nyquist. */
    val binCount: Int = size / 2 + 1

    private val half = size / 2

    // Working buffers for the half-length complex transform.
    private val re = FloatArray(half)
    private val im = FloatArray(half)
    private val windowed = FloatArray(size)

    private val windowTable = FloatArray(size) { window.valueAt(it, size) }

    /** Coherent gain of the window; magnitudes are divided by it to stay comparable. */
    private val windowGain: Float = run {
        var sum = 0f
        for (v in windowTable) sum += v
        (sum / size).coerceAtLeast(1e-6f)
    }

    // Bit-reversal permutation for the half-length transform.
    private val reversed = IntArray(half).also { table ->
        var bits = 0
        while ((1 shl bits) < half) bits++
        for (i in 0 until half) {
            var x = i
            var r = 0
            for (b in 0 until bits) {
                r = (r shl 1) or (x and 1)
                x = x shr 1
            }
            table[i] = r
        }
    }

    // Twiddles for the half-length complex FFT, laid out per stage.
    private val cosTable = FloatArray(half / 2)
    private val sinTable = FloatArray(half / 2)

    // Twiddles for the real-input unpacking step, indexed by output bin 0..half.
    private val unpackCos = FloatArray(half + 1)
    private val unpackSin = FloatArray(half + 1)

    init {
        for (i in 0 until half / 2) {
            val angle = -2.0 * PI * i / half
            cosTable[i] = cos(angle).toFloat()
            sinTable[i] = sin(angle).toFloat()
        }
        for (k in 0..half) {
            val angle = -PI * k / half
            unpackCos[k] = cos(angle).toFloat()
            unpackSin[k] = sin(angle).toFloat()
        }
    }

    /**
     * Computes the magnitude spectrum of [input].
     *
     * @param input at least [size] samples starting at [offset].
     * @param out receives [binCount] linear magnitudes (already window-compensated).
     */
    fun magnitudes(input: FloatArray, offset: Int = 0, out: FloatArray) {
        require(out.size >= binCount) { "out must hold at least $binCount bins" }
        require(offset + size <= input.size) { "input too short for FFT size $size" }

        for (i in 0 until size) {
            windowed[i] = input[offset + i] * windowTable[i]
        }

        // Pack the real signal into half as many complex samples.
        for (i in 0 until half) {
            val r = reversed[i]
            re[r] = windowed[2 * i]
            im[r] = windowed[2 * i + 1]
        }

        transformHalf()
        unpack(out)
    }

    /** In-place iterative radix-2 complex FFT over [re]/[im], input already bit-reversed. */
    private fun transformHalf() {
        var span = 1
        while (span < half) {
            val step = half / (span * 2)
            var start = 0
            while (start < half) {
                var twiddle = 0
                for (i in start until start + span) {
                    val j = i + span
                    val wr = cosTable[twiddle]
                    val wi = sinTable[twiddle]
                    val tr = re[j] * wr - im[j] * wi
                    val ti = re[j] * wi + im[j] * wr
                    re[j] = re[i] - tr
                    im[j] = im[i] - ti
                    re[i] += tr
                    im[i] += ti
                    twiddle += step
                }
                start += span * 2
            }
            span = span shl 1
        }
    }

    /**
     * Recovers the `size`-point real spectrum from the `half`-point complex one:
     *
     *     X[k] = 0.5*(Z[k] + conj(Z[H-k])) - 0.5i*W^k*(Z[k] - conj(Z[H-k]))
     *
     * with `H = size/2` and `W^k = exp(-i*pi*k/H)`. Only magnitudes are kept.
     */
    private fun unpack(out: FloatArray) {
        val scale = 2f / (size * windowGain)

        // DC and Nyquist fall out of the k=0 case as purely real values.
        val dc = re[0] + im[0]
        val nyquist = re[0] - im[0]
        out[0] = kotlin.math.abs(dc) * scale * 0.5f
        out[half] = kotlin.math.abs(nyquist) * scale * 0.5f

        for (k in 1 until half) {
            val mirror = half - k
            val evenRe = 0.5f * (re[k] + re[mirror])
            val evenIm = 0.5f * (im[k] - im[mirror])
            val oddRe = 0.5f * (im[k] + im[mirror])
            val oddIm = -0.5f * (re[k] - re[mirror])

            val wr = unpackCos[k]
            val wi = unpackSin[k]
            val rotRe = oddRe * wr - oddIm * wi
            val rotIm = oddRe * wi + oddIm * wr

            val xr = evenRe + rotRe
            val xi = evenIm + rotIm
            out[k] = sqrt(xr * xr + xi * xi) * scale
        }
    }
}

/** Analysis windows available to [RealFft]. */
enum class WindowFunction {
    /** No window. Cheapest, but leaks badly; only useful for pure level work. */
    RECTANGULAR,

    /** Raised cosine. Good general-purpose choice for speech and music. */
    HANN,

    /** Slightly wider main lobe than Hann, lower side lobes. */
    HAMMING,
    ;

    internal fun valueAt(index: Int, size: Int): Float {
        // Periodic (DFT-even) form: the correct variant for spectral analysis.
        val phase = 2.0 * PI * index / size
        return when (this) {
            RECTANGULAR -> 1f
            HANN -> (0.5 - 0.5 * cos(phase)).toFloat()
            HAMMING -> (0.54 - 0.46 * cos(phase)).toFloat()
        }
    }
}
