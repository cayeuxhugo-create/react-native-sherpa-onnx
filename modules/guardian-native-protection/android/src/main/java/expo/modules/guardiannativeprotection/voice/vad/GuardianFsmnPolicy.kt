package expo.modules.guardiannativeprotection

import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max

/** Completed-window port of FSMN's default offline postprocessor.
 * The model returns silence posterior p0, not a speech score. Upstream tests
 * exp(log(1-p0)) >= exp(log(p0)) + .6, i.e. a probability DIFFERENCE of .6.
 * Its 200 ms ring requires 15 speech frames, then the real segment must close.
 * No transcript, device RMS threshold, or amplitude gain is speech evidence.
 */
internal object GuardianFsmnPolicy {
    const val FRAME_LENGTH = 400
    const val FRAME_SHIFT = 160
    const val WINDOW_FRAMES = 20
    const val SIL_TO_SPEECH_FRAMES = 15
    const val SPEECH_NOISE_THRESHOLD = .6

    data class Decision(val accepted: Boolean, val speechFrames: Int, val detectedSegments: Int)

    fun evaluate(silenceProbabilities: FloatArray, conditionedPcm: FloatArray): Decision {
        require(conditionedPcm.all { it.isFinite() && abs(it) <= 1f }) { "FSMN requires normalized finite PCM" }
        val frames = if (conditionedPcm.size < FRAME_LENGTH) 0
            else 1 + (conditionedPcm.size - FRAME_LENGTH) / FRAME_SHIFT
        require(silenceProbabilities.size == frames) { "FSMN frame count differs from PCM" }
        require(silenceProbabilities.all { it.isFinite() && it in 0f..1f }) { "Invalid FSMN posterior" }
        if (frames == 0) return Decision(false, 0, 0)

        val window = IntArray(WINDOW_FRAMES)
        var position = 0
        var sum = 0
        var windowSpeech = false
        var inSegment = false
        var segmentStart = -1
        var bufferStart = 0
        var silenceFrames = 0
        var noiseDecibel = -100.0
        var speechFrames = 0
        var segments = 0

        fun closeSegment(endFrame: Int) {
            segments++
            // The upstream buffer advances past the final 10 ms frame.
            bufferStart = max(bufferStart, endFrame) + 1
            inSegment = false
            segmentStart = -1
            silenceFrames = 0
            window.fill(0)
            sum = 0
            position = 0
            windowSpeech = false
        }

        for (frame in 0 until frames) {
            var energy = 0.0
            val offset = frame * FRAME_SHIFT
            for (i in offset until offset + FRAME_LENGTH) {
                val sample = conditionedPcm[i].toDouble()
                energy += sample * sample
            }
            // Upstream uses sum of normalized squared PCM, not RMS or int16.
            // With its +1e-6 floor, bounded PCM gives -60..26.03 dB: the
            // default -100 dB energy and SNR checks cannot reject such PCM.
            val decibel = 10 * log10(energy + 1e-6)
            val snr = decibel - noiseDecibel
            val silence = silenceProbabilities[frame].toDouble()
            // NumPy keeps (1-p0) in Float32 before math.log/exp promotes it.
            // Preserve that rounding at the exact .2f posterior boundary.
            val speechProbability = (1f - silenceProbabilities[frame]).toDouble()
            val probabilitySpeech = speechProbability >= silence + SPEECH_NOISE_THRESHOLD
            val speech = probabilitySpeech && decibel >= -100.0 && snr >= -100.0
            if (!probabilitySpeech) {
                noiseDecibel = if (noiseDecibel < -99.9) decibel
                    else (decibel + noiseDecibel * 99) / 100
            }
            if (speech) speechFrames++
            sum -= window[position]
            window[position] = if (speech) 1 else 0
            sum += window[position]
            position = (position + 1) % WINDOW_FRAMES

            // Upstream deliberately uses >=15 for onset and <=15 for offset.
            val change = if (!windowSpeech && sum >= SIL_TO_SPEECH_FRAMES) {
                windowSpeech = true
                3 // silence -> speech
            } else if (windowSpeech && sum <= SIL_TO_SPEECH_FRAMES) {
                windowSpeech = false
                1 // speech -> silence
            } else if (windowSpeech) 0 else 2
            val finalFrame = frame == frames - 1
            when (change) {
                3 -> {
                    silenceFrames = 0
                    if (!inSegment) {
                        // 20-frame detector latency + 20-frame start lookback.
                        segmentStart = max(bufferStart, frame - 40)
                        inSegment = true
                        bufferStart = frame + 1
                        // Upstream's first onset branch does not finalize a
                        // newly opened segment on the very last frame.
                    } else if (frame - segmentStart + 1 > 6000 || finalFrame) {
                        closeSegment(frame)
                    } else bufferStart = frame + 1
                }
                0, 1 -> {
                    silenceFrames = 0
                    if (inSegment) {
                        if (frame - segmentStart + 1 > 6000 || finalFrame) closeSegment(frame)
                        else bufferStart = frame + 1
                    }
                }
                2 -> {
                    silenceFrames++
                    if (!inSegment) {
                        if (frame >= 40) bufferStart = max(bufferStart, frame - 40)
                        // The upstream final all-silence path emits only a
                        // fake endpoint, with no completed output segment.
                    } else if (silenceFrames >= 65) {
                        // (800-150)/10 - lookahead100/10 - 1 = 54.
                        closeSegment(frame - 54)
                    } else if (frame - segmentStart + 1 > 6000 || finalFrame) {
                        closeSegment(frame)
                    } else if (silenceFrames <= 10) {
                        bufferStart = frame + 1
                    }
                }
            }
        }
        return Decision(segments > 0, speechFrames, segments)
    }
}
