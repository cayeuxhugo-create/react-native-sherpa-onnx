package expo.modules.guardiannativeprotection

/** Model-independent checks: no native library, recording or transcription. */
internal object FsmnConditioningTest {
    @JvmStatic
    fun main(args: Array<String>) {
        for (size in intArrayOf(400, 128000)) {
            val original = FloatArray(size)
            val input = GuardianFsmnConfirmation.condition(original)
            check(input.pcm !== original && input.pcm.contentEquals(original))
            check(input.inputGain == 64f && input.inputPeak == 0f && input.conditionedPeak == 0f)
        }
        for (peak in floatArrayOf(.001f, .01f, .4f, .8f, 1f)) {
            val original = FloatArray(800) { if (it % 2 == 0) peak else -peak }
            val before = original.copyOf()
            val input = GuardianFsmnConfirmation.condition(original)
            val expectedGain = minOf(64f, .4f / peak).coerceAtLeast(1f)
            check(input.inputGain.toRawBits() == expectedGain.toRawBits())
            check(input.inputPeak == peak && input.conditionedPeak == peak * expectedGain)
            check(input.pcm.contentEquals(FloatArray(original.size) { original[it] * expectedGain }))
            check(original.contentEquals(before)) { "FSMN conditioning changed original Qwen PCM" }
            input.pcm.fill(Float.NaN)
            check(original.contentEquals(before)) { "FSMN model view aliases original PCM" }
        }
        // A strong signal is not attenuated: this is the measured study policy.
        val strong = GuardianFsmnConfirmation.condition(FloatArray(400) { .8f })
        check(strong.inputGain == 1f && strong.pcm.all { it == .8f })
        for (invalid in listOf(FloatArray(399), FloatArray(128001),
            FloatArray(400) { Float.NaN }, FloatArray(400) { Float.POSITIVE_INFINITY })) {
            check(runCatching { GuardianFsmnConfirmation.condition(invalid) }.isFailure)
        }
        val unverified = runCatching { GuardianFsmnConfirmation.load { byteArrayOf(1, 2, 3) } }.exceptionOrNull()
        check(unverified is IllegalStateException && unverified.message?.startsWith("FSMN asset SHA-256 mismatch:") == true) {
            "Unverified FSMN assets must never initialize the native model"
        }
        println("PASS FSMN Float32 conditioning: measured gain policy, strong-signal floor, immutable original, independent copy, input bounds and unverified-asset rejection")
    }
}
