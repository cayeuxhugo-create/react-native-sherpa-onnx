package expo.modules.guardiannativeprotection

import kotlin.math.sin
import kotlin.random.Random

internal object MicrophoneSelectionTest {
    @JvmStatic fun main(args: Array<String>) {
        val n = 64000; val frames = 1 + (n - 400) / 160
        val support = BooleanArray(frames) { it in 100..250 }
        fun pcm(noise: Double, voice: Double, gain: Double = 1.0): FloatArray {
            val random = Random(42)
            return FloatArray(n) { i ->
                ((random.nextDouble(-1.0, 1.0) * noise +
                    if (i in 16000..40400) voice * sin(i * 2 * Math.PI * 220 / 16000) else 0.0) * gain).toFloat()
            }
        }
        fun c(index: Int, x: FloatArray, confirmed: Boolean = true) =
            GuardianMicrophoneSelection.Candidate(index, x, confirmed, support, if (index == 0) n else 0)
        val noisy = c(0, pcm(.025, .04)); val clear = c(1, pcm(.002, .04))
        val better = checkNotNull(GuardianMicrophoneSelection.select(listOf(noisy, clear)))
        check(better.candidate.index == 1 && better.candidate.pcm === clear.pcm)
        check(better.qualities.getValue(1).contrastDb!! > better.qualities.getValue(0).contrastDb!! + 3)
        check(GuardianMicrophoneSelection.select(listOf(noisy, clear.copy(confirmed=false)))!!.candidate.index == 0)
        val same = listOf(c(0, pcm(.002, .04)), c(1, pcm(.002, .04, 3.0)))
        check(GuardianMicrophoneSelection.select(same)!!.candidate.index == 0) { "Loudness is not microphone quality" }
        check(GuardianMicrophoneSelection.select(same, preferred=1)!!.candidate.index == 1)
        val noNoise = same.map { it.copy(speech=BooleanArray(frames) { true }) }
        check(GuardianMicrophoneSelection.select(noNoise)!!.candidate.index == 0)
        check(GuardianMicrophoneSelection.select(same.map { it.copy(confirmed=false) }) == null)
        val clipped = clear.copy(pcm=FloatArray(n) { if (it in 16000..40400) 1f else clear.pcm[it] })
        check(GuardianMicrophoneSelection.select(listOf(noisy, clipped))!!.candidate.index == 0)
        check(runCatching { GuardianMicrophoneSelection.select(listOf(clear, clear)) }.isFailure)
        check(runCatching { GuardianMicrophoneSelection.select(listOf(noisy, clear.copy(pcm=FloatArray(n) { Float.NaN }))) }.isFailure)
        val tiny = c(0, FloatArray(400) { .00001f }).copy(speech=booleanArrayOf(true))
        check(GuardianMicrophoneSelection.select(listOf(tiny))!!.candidate.index == 0)
        println("PASS 10 microphone selection cases: actual PCM selection, contrast, confirmation, gain invariance, hysteresis, missing background, clipping, duplicate IDs, invalid PCM and weak mono")
    }
}
