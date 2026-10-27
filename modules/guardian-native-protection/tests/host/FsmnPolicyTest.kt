package expo.modules.guardiannativeprotection

/** Synthetic checks of the upstream FSMN semantics, independent of a model. */
object FsmnPolicyTest {
    @JvmStatic fun main(args: Array<String>) {
        fun evaluate(probabilities: FloatArray, level: Float = .01f): GuardianFsmnPolicy.Decision {
            val pcm = FloatArray(if (probabilities.isEmpty()) 0 else 400 + (probabilities.size - 1) * 160) { level }
            val original = pcm.copyOf()
            val result = GuardianFsmnPolicy.evaluate(probabilities, pcm)
            check(pcm.contentEquals(original))
            return result
        }
        check(!evaluate(FloatArray(80) { .4f }).accepted) // speech posterior .6 is insufficient.
        check(evaluate(FloatArray(80) { .2f }).accepted) // upstream Float32 complement rounding.
        check(!evaluate(FloatArray(80) { java.lang.Math.nextUp(.2f) }).accepted)
        check(evaluate(FloatArray(80) { java.lang.Math.nextDown(.2f) }).accepted)
        check(!evaluate(FloatArray(14) { .01f }).accepted)
        check(!evaluate(FloatArray(15) { .01f }).accepted) // onset at final frame is not closed.
        val sixteen = evaluate(FloatArray(16) { if (it < 15) .01f else .99f })
        check(sixteen.accepted && sixteen.speechFrames == 15 && sixteen.detectedSegments == 1)
        val interleaved = evaluate(FloatArray(21) { if (it < 20 && it % 4 != 0) .01f else .99f })
        check(interleaved.accepted && interleaved.speechFrames == 15)
        val two = evaluate(FloatArray(220) { if (it in 20..50 || it in 170..200) .01f else .99f })
        check(two.accepted && two.detectedSegments == 2 && two.speechFrames == 62)
        val extended = evaluate(FloatArray(6200) { .01f })
        check(extended.detectedSegments == 2) // default 60 s segment limit.
        // Upstream's +1e-6 floor means its -100 dB check does not reject even
        // zero normalized PCM. A new absolute RMS gate must not be invented.
        check(evaluate(FloatArray(40) { .01f }, 0f).accepted)
        check(!evaluate(FloatArray(40) { .99f }, 0f).accepted)
        check(!evaluate(floatArrayOf()).accepted)
        check(runCatching { evaluate(floatArrayOf(Float.NaN)) }.isFailure)
        check(runCatching { evaluate(floatArrayOf(-.1f)) }.isFailure)
        check(runCatching { evaluate(floatArrayOf(.1f), 1.1f) }.isFailure)
        check(runCatching { GuardianFsmnPolicy.evaluate(floatArrayOf(.1f), FloatArray(399)) }.isFailure)
        println("PASS FSMN upstream probability difference, 20/15 ring, segment closure/reset, short onsets, normalized-energy behavior")
    }
}
