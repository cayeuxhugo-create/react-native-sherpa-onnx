package expo.modules.guardiannativeprotection

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Streaming port of filters.py's adaptive-dd-a0.98-f0.5 (16 kHz mono).
 *
 * Output sample zero has the same origin as input sample zero. process() returns
 * only newly finalized samples: the centered 512-point STFT retains 256..511
 * input samples, plus the caller's block scheduling. There is deliberately no
 * padded flush at stop. A session owns one instance on a single worker thread.
 *
 * The initial 0.5..3 seconds must be noise only. Raw VAD speech reported before
 * or across that boundary permanently selects original audio for this session.
 * A clean calibration passes original audio through 3 s, then applies the exact
 * fixed 100 ms raised-cosine fade. Candidate windows beginning before 3.1 s
 * should nevertheless use original audio in their entirety.
 */
internal class GuardianWienerEnhancer {
    var inputSamples: Long = 0L
        private set
    var outputSamples: Long = 0L
        private set
    var calibrationReady: Boolean = false
        private set
    // Valid means "not tainted", including during collection. Ready is separate.
    val calibrationValid: Boolean get() = !calibrationSpeech

    private var calibrationSpeech = false
    private var nextFrame = 0L
    private val inputRing = FloatArray(1024)
    private val overlap = DoubleArray(1024)
    private val weights = DoubleArray(1024)
    private val window = FloatArray(FFT_SIZE) {
        (.5 - .5 * cos(2.0 * PI * it / FFT_SIZE)).toFloat()
    }
    // Python computes window**2 in float32 before adding to double OLA weights.
    private val squaredWindow = FloatArray(FFT_SIZE) { window[it] * window[it] }
    private val fade = FloatArray(FADE_SAMPLES) {
        (.5 - .5 * cos(PI * it / (FADE_SAMPLES - 1))).toFloat()
    }
    private val fft = Radix2Fft()
    private val real = DoubleArray(FFT_SIZE)
    private val imaginary = DoubleArray(FFT_SIZE)
    private val power = DoubleArray(BINS)
    private var startupPower: Array<DoubleArray>? = Array(PROFILE_STOP_FRAME) { DoubleArray(BINS) }
    // np.mean(float32, axis=0) accumulates and divides in float32.
    private val profileSum = FloatArray(BINS)
    private val noise = DoubleArray(BINS)
    private val lastPosterior = DoubleArray(BINS)
    private val lastGain = DoubleArray(BINS)
    private val posteriorSmooth = DoubleArray(BINS) { 1.0 }
    private val currentGain = DoubleArray(BINS)
    private val mask = DoubleArray(BINS)
    private var havePreviousGain = false

    /**
     * Report raw-VAD speech before feeding its block whenever possible.
     * inputEndSample is the exclusive original-stream end of that VAD block.
     * One 50 ms block after 3 s is conservatively included, so a block spanning
     * the calibration boundary cannot silently train a speech profile.
     */
    fun markCalibrationSpeech(inputEndSample: Long = inputSamples) {
        require(inputEndSample >= 0L)
        if (inputEndSample <= CALIBRATION_SAMPLES + CALIBRATION_VAD_GUARD_SAMPLES) {
            calibrationSpeech = true
            startupPower = null
        }
    }

    fun process(samples: FloatArray): FloatArray {
        require(samples.all { it.isFinite() }) { "Wiener input must be finite" }
        if (samples.isEmpty()) return FloatArray(0)
        // A block may complete the hop retained from the previous call.
        val result = FloatArray(samples.size + FFT_SIZE)
        var resultSize = 0
        for (sample in samples) {
            inputRing[ringIndex(inputSamples)] = sample
            inputSamples++
            if (!calibrationReady && inputSamples >= CALIBRATION_SAMPLES) finishCalibration()
            val needed = if (nextFrame == 0L) HOP + 1L else nextFrame * HOP + HOP
            if (inputSamples < needed) continue
            analyzeFrame(nextFrame)
            val finalizedEnd = nextFrame * HOP
            while (outputSamples < finalizedEnd) {
                val index = ringIndex(outputSamples)
                val original = inputRing[index]
                val output = if (!calibrationValid || outputSamples < CALIBRATION_SAMPLES) {
                    original
                } else {
                    val enhanced = (overlap[index] / max(weights[index], 1e-12)).toFloat()
                    val fadeIndex = outputSamples - CALIBRATION_SAMPLES
                    if (fadeIndex < FADE_SAMPLES) {
                        // Preserve float32 operation ordering in Python's crossfade.
                        original + fade[fadeIndex.toInt()] * (enhanced - original)
                    } else enhanced
                }
                check(output.isFinite()) { "Wiener output must be finite" }
                result[resultSize++] = output
                overlap[index] = 0.0
                weights[index] = 0.0
                outputSamples++
            }
            nextFrame++
        }
        return result.copyOf(resultSize)
    }

    private fun finishCalibration() {
        calibrationReady = true
        val saved = startupPower
        if (!calibrationSpeech && saved != null) {
            val count = (PROFILE_STOP_FRAME - PROFILE_START_FRAME).toFloat()
            for (bin in 0 until BINS) noise[bin] = max((profileSum[bin] / count).toDouble(), EPS)
            // The profile is now known. Replay only bounded historical powers,
            // never audio output, to initialize exactly the offline DD and EMA.
            for (frame in saved.indices) gains(frame.toLong(), saved[frame])
        }
        startupPower = null
    }

    private fun analyzeFrame(frame: Long) {
        if (calibrationSpeech) return
        val start = frame * HOP - HOP
        for (i in 0 until FFT_SIZE) {
            val source = start + i
            val reflected = if (source < 0L) -source else source
            real[i] = (inputRing[ringIndex(reflected)] * window[i]).toDouble()
            imaginary[i] = 0.0
        }
        fft.transform(real, imaginary, inverse = false)
        for (bin in 0 until BINS) {
            // NumPy's float32 rFFT returns complex64. Quantize its components
            // before float32 magnitude and squared-power operations.
            real[bin] = real[bin].toFloat().toDouble()
            imaginary[bin] = if (bin == 0 || bin == HOP) 0.0 else imaginary[bin].toFloat().toDouble()
            val magnitude = sqrt(real[bin] * real[bin] + imaginary[bin] * imaginary[bin]).toFloat()
            power[bin] = (magnitude * magnitude).toDouble()
        }
        if (!calibrationReady) {
            val saved = checkNotNull(startupPower)
            power.copyInto(saved[frame.toInt()])
            if (frame >= PROFILE_START_FRAME && frame < PROFILE_STOP_FRAME) {
                for (bin in 0 until BINS) profileSum[bin] += power[bin].toFloat()
            }
            return
        }
        gains(frame, power)
        for (bin in 0 until BINS) {
            real[bin] *= mask[bin]
            imaginary[bin] *= mask[bin]
        }
        for (bin in BINS until FFT_SIZE) {
            real[bin] = real[FFT_SIZE - bin]
            imaginary[bin] = -imaginary[FFT_SIZE - bin]
        }
        fft.transform(real, imaginary, inverse = true)
        for (i in 0 until FFT_SIZE) {
            val sample = start + i
            // Frames whose output was emitted during calibration are not replayed.
            if (sample < outputSamples) continue
            val index = ringIndex(sample)
            overlap[index] += real[i] * window[i]
            weights[index] += squaredWindow[i]
        }
    }

    private fun gains(frame: Long, row: DoubleArray) {
        for (bin in 0 until BINS) {
            val posterior = (row[bin] / max(noise[bin], EPS)).coerceIn(1e-12, MAX_SNR)
            val excess = max(posterior - 1.0, 0.0)
            val prior = (if (havePreviousGain) {
                .98 * lastGain[bin] * lastGain[bin] * lastPosterior[bin] + .02 * excess
            } else excess).coerceIn(1e-12, MAX_SNR)
            currentGain[bin] = (prior / (1.0 + prior)).coerceIn(.5, 1.0)
            lastPosterior[bin] = posterior
            lastGain[bin] = currentGain[bin]
            if (frame >= ADAPTIVE_START_FRAME) {
                posteriorSmooth[bin] = .8 * posteriorSmooth[bin] + .2 * posterior
            }
        }
        if (frame >= ADAPTIVE_START_FRAME) {
            for (bin in 0 until BINS) {
                val local = (posteriorSmooth[max(0, bin - 1)] + posteriorSmooth[bin] +
                    posteriorSmooth[min(BINS - 1, bin + 1)]) / 3.0
                val presence = ((local - 1.5) / 4.5).coerceIn(0.0, .98)
                val retention = .98 + .02 * presence
                val observation = min(row[bin], 3.0 * noise[bin])
                noise[bin] = max(retention * noise[bin] + (1.0 - retention) * observation, EPS)
            }
        }
        for (bin in 0 until BINS) {
            val frequencyMean = (currentGain[max(0, bin - 1)] + currentGain[bin] +
                currentGain[min(BINS - 1, bin + 1)]) / 3.0
            mask[bin] = if (havePreviousGain) .35 * frequencyMean + .65 * mask[bin] else frequencyMean
        }
        havePreviousGain = true
    }

    private fun ringIndex(sample: Long): Int = (sample and 1023L).toInt()

    /** Double FFT arithmetic; float32 analysis boundaries are explicit above. */
    private class Radix2Fft {
        private val reverse = IntArray(FFT_SIZE) { Integer.reverse(it) ushr (32 - 9) }
        private val cosine = DoubleArray(HOP) { cos(2.0 * PI * it / FFT_SIZE) }
        private val sine = DoubleArray(HOP) { sin(2.0 * PI * it / FFT_SIZE) }

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
        const val CALIBRATION_SAMPLES = 48_000L
        const val STABLE_START_SAMPLE = 49_600L
        private const val CALIBRATION_VAD_GUARD_SAMPLES = 800L
        private const val FFT_SIZE = 512
        private const val HOP = 256
        private const val BINS = HOP + 1
        private const val FADE_SAMPLES = 1600
        private const val PROFILE_START_FRAME = 33
        private const val PROFILE_STOP_FRAME = 187
        // Python round(3 * 16000 / 256) uses ties-to-even: round(187.5) = 188.
        private const val ADAPTIVE_START_FRAME = 188L
        private const val EPS = 1e-18
        private const val MAX_SNR = 1e12
    }
}
