package expo.modules.guardiannativeprotection

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Diagnostic, cross-channel Wiener predictor residual: X0 - H * X1.
 *
 * H is estimated only from complete Hann windows inside the declared prefix.
 * Prefix speech is observed, never silently removed from this experimental fit.
 * Separate from the mono Wiener gain mask: no gain, normalization, limiter or cascade.
 *
 * One instance belongs to one uninterrupted, synchronous pair of source streams.
 * Output sample zero has input sample zero's origin. Only finalized samples are
 * returned, retaining 256..511 input samples; empty input never pads or flushes.
 * Route changes and gaps require a new instance, owned by the calling worker.
 */
internal class GuardianReferenceNoiseEnhancer(val profile: Profile) {
    enum class Profile(val startSample: Long, val endSample: Long) {
        SHORT(8_000L, 48_000L),
        LONG(32_000L, 240_000L);

        val stableStartSample: Long get() = endSample + FADE_SAMPLES
    }

    var inputSamples = 0L
        private set
    var outputSamples = 0L
        private set
    var calibrationReady = false
        private set
    var profileFrames = 0
        private set
    var outputPeak = 0f
        private set
    var observedSpeechDuringProfile = false
        private set
    var profileFinite = true
        private set
    var degenerateReference = false
        private set

    private val primaryRing = FloatArray(RING_SIZE)
    private val referenceRing = FloatArray(RING_SIZE)
    private val overlap = DoubleArray(RING_SIZE)
    private val weights = DoubleArray(RING_SIZE)
    private val window = DoubleArray(WINDOW_SIZE) { .5 - .5 * cos(2.0 * PI * it / WINDOW_SIZE) }
    private val primaryReal = DoubleArray(FFT_SIZE)
    private val primaryImaginary = DoubleArray(FFT_SIZE)
    private val referenceReal = DoubleArray(FFT_SIZE)
    private val referenceImaginary = DoubleArray(FFT_SIZE)
    private val referencePowerSum = DoubleArray(BINS)
    private val crossRealSum = DoubleArray(BINS)
    private val crossImaginarySum = DoubleArray(BINS)
    private val transferReal = DoubleArray(BINS)
    private val transferImaginary = DoubleArray(BINS)
    private val fft = DoubleFft()
    private var nextFrame = 0L

    /** Call before processing the speech-positive block whose exclusive end is given. */
    fun markCalibrationSpeech(inputEndSample: Long) {
        require(inputEndSample >= inputSamples)
        if (inputSamples < profile.endSample && inputEndSample > profile.startSample) {
            observedSpeechDuringProfile = true
        }
    }

    fun process(primary: FloatArray, reference: FloatArray): FloatArray {
        require(primary.size == reference.size) { "Reference sources must share one sample clock" }
        require(primary.all { it.isFinite() } && reference.all { it.isFinite() }) { "Reference sources must be finite" }
        if (primary.isEmpty()) return FloatArray(0)
        val output = FloatArray(primary.size + WINDOW_SIZE)
        var count = 0
        for (sample in primary.indices) {
            val inputIndex = ringIndex(inputSamples)
            primaryRing[inputIndex] = primary[sample]
            referenceRing[inputIndex] = reference[sample]
            inputSamples++
            if (!calibrationReady && inputSamples >= profile.endSample) finishCalibration()
            val center = nextFrame * HOP_SIZE
            if (inputSamples < center + HOP_SIZE) continue
            analyzeFrame(center)
            while (outputSamples < center) {
                val index = ringIndex(outputSamples)
                val original = primaryRing[index]
                val value = if (outputSamples < profile.endSample || degenerateReference || !profileFinite) {
                    original
                } else {
                    val residual = overlap[index] / max(weights[index], 1e-12)
                    val fadeIndex = outputSamples - profile.endSample
                    if (fadeIndex < FADE_SAMPLES) {
                        (original.toDouble() + fadeIndex.toDouble() / FADE_SAMPLES * (residual - original.toDouble())).toFloat()
                    } else residual.toFloat()
                }
                check(value.isFinite()) { "Reference residual must remain finite" }
                output[count++] = value
                outputPeak = max(outputPeak, kotlin.math.abs(value))
                overlap[index] = 0.0
                weights[index] = 0.0
                outputSamples++
            }
            nextFrame++
        }
        return output.copyOf(count)
    }

    private fun analyzeFrame(center: Long) {
        val start = center - HOP_SIZE
        primaryReal.fill(0.0)
        primaryImaginary.fill(0.0)
        referenceReal.fill(0.0)
        referenceImaginary.fill(0.0)
        for (i in 0 until WINDOW_SIZE) {
            val source = start + i
            if (source >= 0L) {
                primaryReal[i] = primaryRing[ringIndex(source)].toDouble() * window[i]
                referenceReal[i] = referenceRing[ringIndex(source)].toDouble() * window[i]
            }
        }
        fft.transform(primaryReal, primaryImaginary, inverse = false)
        fft.transform(referenceReal, referenceImaginary, inverse = false)
        for (bin in 0 until BINS) {
            primaryReal[bin] /= WINDOW_SUM
            primaryImaginary[bin] /= WINDOW_SUM
            referenceReal[bin] /= WINDOW_SUM
            referenceImaginary[bin] /= WINDOW_SUM
        }
        primaryImaginary[0] = 0.0
        primaryImaginary[BINS - 1] = 0.0
        referenceImaginary[0] = 0.0
        referenceImaginary[BINS - 1] = 0.0
        if (start >= profile.startSample && center + HOP_SIZE <= profile.endSample) {
            profileFrames++
            for (bin in 0 until BINS) {
                val ar = primaryReal[bin]
                val ai = primaryImaginary[bin]
                val br = referenceReal[bin]
                val bi = referenceImaginary[bin]
                // Match abs(complex128)**2 and X0 * conj(X1) in the prototype.
                val magnitude = sqrt(br * br + bi * bi)
                referencePowerSum[bin] += magnitude * magnitude
                crossRealSum[bin] += ar * br + ai * bi
                crossImaginarySum[bin] += ai * br - ar * bi
            }
        }
        // All earlier frame supports end before the literal bypass boundary.
        if (!calibrationReady || degenerateReference || !profileFinite) return
        for (bin in 0 until BINS) {
            val br = referenceReal[bin]
            val bi = referenceImaginary[bin]
            primaryReal[bin] -= transferReal[bin] * br - transferImaginary[bin] * bi
            primaryImaginary[bin] -= transferReal[bin] * bi + transferImaginary[bin] * br
        }
        for (bin in BINS until FFT_SIZE) {
            primaryReal[bin] = primaryReal[FFT_SIZE - bin]
            primaryImaginary[bin] = -primaryImaginary[FFT_SIZE - bin]
        }
        fft.transform(primaryReal, primaryImaginary, inverse = true)
        for (i in 0 until WINDOW_SIZE) {
            val sample = start + i
            if (sample < outputSamples) continue
            val index = ringIndex(sample)
            overlap[index] += primaryReal[i] * WINDOW_SUM * window[i]
            weights[index] += window[i] * window[i]
        }
    }

    private fun finishCalibration() {
        calibrationReady = true
        if (profileFrames == 0) {
            degenerateReference = true
            return
        }
        val power = DoubleArray(BINS) { referencePowerSum[it] / profileFrames }
        profileFinite = power.all { it.isFinite() } && crossRealSum.all { it.isFinite() } && crossImaginarySum.all { it.isFinite() }
        if (!profileFinite) return
        // FFT bins 8..243 are 125..3796.875 Hz: exactly 120 <= f < 3800.
        val band = power.copyOfRange(8, 244).sortedArray()
        val median = (band[band.size / 2 - 1] + band[band.size / 2]) / 2.0
        val regularizer = .001 * median
        if (regularizer <= 0.0 || power.maxOrNull() == 0.0) {
            degenerateReference = true
            return
        }
        for (bin in 0 until BINS) {
            val denominator = power[bin] + regularizer
            transferReal[bin] = (crossRealSum[bin] / profileFrames) / denominator
            transferImaginary[bin] = (crossImaginarySum[bin] / profileFrames) / denominator
        }
        profileFinite = transferReal.all { it.isFinite() } && transferImaginary.all { it.isFinite() }
    }

    private fun ringIndex(sample: Long) = (sample and (RING_SIZE - 1).toLong()).toInt()

    private class DoubleFft {
        private val reverse = IntArray(FFT_SIZE) { Integer.reverse(it) ushr (32 - 10) }
        private val cosine = DoubleArray(FFT_SIZE / 2) { cos(2.0 * PI * it / FFT_SIZE) }
        private val sine = DoubleArray(FFT_SIZE / 2) { sin(2.0 * PI * it / FFT_SIZE) }

        fun transform(real: DoubleArray, imaginary: DoubleArray, inverse: Boolean) {
            for (i in 0 until FFT_SIZE) {
                val j = reverse[i]
                if (j <= i) continue
                val r = real[i]; real[i] = real[j]; real[j] = r
                val im = imaginary[i]; imaginary[i] = imaginary[j]; imaginary[j] = im
            }
            var size = 2
            while (size <= FFT_SIZE) {
                val half = size / 2
                val stride = FFT_SIZE / size
                for (start in 0 until FFT_SIZE step size) {
                    for (offset in 0 until half) {
                        val root = offset * stride
                        val c = cosine[root]
                        val s = if (inverse) sine[root] else -sine[root]
                        val a = start + offset
                        val b = a + half
                        val r = c * real[b] - s * imaginary[b]
                        val im = s * real[b] + c * imaginary[b]
                        real[b] = real[a] - r
                        imaginary[b] = imaginary[a] - im
                        real[a] += r
                        imaginary[a] += im
                    }
                }
                size *= 2
            }
            if (inverse) for (i in 0 until FFT_SIZE) {
                real[i] /= FFT_SIZE
                imaginary[i] /= FFT_SIZE
            }
        }
    }

    companion object {
        const val SAMPLE_RATE = 16_000
        const val WINDOW_SIZE = 512
        const val HOP_SIZE = 256
        const val FFT_SIZE = 1024
        const val FADE_SAMPLES = 1600
        private const val BINS = FFT_SIZE / 2 + 1
        private const val RING_SIZE = 1024
        private const val WINDOW_SUM = 256.0
    }
}
