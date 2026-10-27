package expo.modules.guardiannativeprotection

import java.util.Random
import kotlin.math.abs

private data class ReferenceRun(
    val output: FloatArray,
    val enhancer: GuardianReferenceNoiseEnhancer,
)

/** Synthetic signal/clock regressions only; no private recordings or ASR. */
fun referenceNoiseEnhancerTests() {
    var tests = 0
    fun test(name: String, run: () -> Unit) { run(); tests++; println("PASS $name") }

    test("reference profiles finish at the exact input boundary and count only complete prefix frames") {
        for ((profile, end, expectedFrames) in listOf(
            Triple(GuardianReferenceNoiseEnhancer.Profile.SHORT, 48_000, 154),
            Triple(GuardianReferenceNoiseEnhancer.Profile.LONG, 240_000, 811),
        )) {
            val enhancer = GuardianReferenceNoiseEnhancer(profile)
            check(!enhancer.calibrationReady && enhancer.inputSamples == 0L && enhancer.outputSamples == 0L)
            enhancer.process(FloatArray(end - 1), FloatArray(end - 1))
            check(!enhancer.calibrationReady)
            check(enhancer.profileFrames == expectedFrames)
            enhancer.process(floatArrayOf(0f), floatArrayOf(0f))
            check(enhancer.calibrationReady && enhancer.profileFrames == expectedFrames)
            check(enhancer.profileFinite && enhancer.degenerateReference)
            check(!enhancer.observedSpeechDuringProfile && enhancer.outputPeak == 0f)
            val produced = enhancer.outputSamples
            repeat(3) { check(enhancer.process(FloatArray(0), FloatArray(0)).isEmpty()) }
            check(enhancer.inputSamples == end.toLong() && enhancer.outputSamples == produced)
        }
    }

    val random = Random(350L)
    val reference = FloatArray(256_139) { (random.nextGaussian() * .02).toFloat() }
    val primary = FloatArray(reference.size) { index ->
        (.7 * reference[index] + random.nextGaussian() * .003 +
            if (index >= 49_600) .005 * kotlin.math.sin(index * .081) else 0.0).toFloat()
    }
    test("reference noise DSP is chunk invariant, owns its outputs and never modifies either source") {
        for (profile in GuardianReferenceNoiseEnhancer.Profile.entries) {
            val whole = referenceRun(profile, primary, reference, intArrayOf(primary.size))
            val regular = referenceRun(profile, primary, reference, intArrayOf(800))
            val irregular = referenceRun(profile, primary, reference, intArrayOf(1, 257, 17, 901, 63, 800, 511, 4097))
            check(whole.output.contentEquals(regular.output) && regular.output.contentEquals(irregular.output)) {
                "Caller block boundaries changed the diagnostic PCM for $profile"
            }
            val end = if (profile == GuardianReferenceNoiseEnhancer.Profile.SHORT) 48_000 else 240_000
            check(whole.output.copyOfRange(0, end).contentEquals(primary.copyOfRange(0, end))) {
                "Prefix bypass changed original samples for $profile"
            }
            check(whole.enhancer.profileFinite && !whole.enhancer.degenerateReference)
            val queuedCopy = irregular.output.copyOf()
            irregular.enhancer.process(FloatArray(4096) { .08f }, FloatArray(4096) { -.04f })
            check(irregular.output.contentEquals(queuedCopy)) { "A later block mutated an earlier output" }
        }
    }

    test("speech observations flag overlapping calibration blocks without suppressing diagnostic output") {
        val baseline = referenceRun(GuardianReferenceNoiseEnhancer.Profile.SHORT, primary, reference, intArrayOf(800))
        val observed = referenceRun(GuardianReferenceNoiseEnhancer.Profile.SHORT, primary, reference, intArrayOf(800), speechEnd = 16_000)
        check(observed.enhancer.observedSpeechDuringProfile)
        check(observed.enhancer.calibrationReady && observed.enhancer.profileFinite)
        check(observed.output.contentEquals(baseline.output)) { "A speech observation became a diagnostic veto" }

        val before = GuardianReferenceNoiseEnhancer(GuardianReferenceNoiseEnhancer.Profile.SHORT)
        before.markCalibrationSpeech(800)
        check(!before.observedSpeechDuringProfile)
        before.process(FloatArray(47_200), FloatArray(47_200))
        before.markCalibrationSpeech(48_800)
        check(before.observedSpeechDuringProfile) { "A block spanning profile end was not observed" }

        val after = GuardianReferenceNoiseEnhancer(GuardianReferenceNoiseEnhancer.Profile.SHORT)
        after.process(FloatArray(48_000), FloatArray(48_000))
        after.markCalibrationSpeech(48_800)
        check(!after.observedSpeechDuringProfile) { "Normal speech after the prefix tainted the observation" }
    }

    test("silent or degenerate references remain finite without inventing a flush or changing sample origin") {
        val silent = referenceRun(GuardianReferenceNoiseEnhancer.Profile.SHORT, FloatArray(64_123), FloatArray(64_123), intArrayOf(800))
        check(silent.output.all { it == 0f } && silent.enhancer.degenerateReference)
        val zeroReference = referenceRun(GuardianReferenceNoiseEnhancer.Profile.SHORT, primary, FloatArray(primary.size), intArrayOf(1, 513, 800))
        check(zeroReference.enhancer.calibrationReady && zeroReference.enhancer.profileFinite && zeroReference.enhancer.degenerateReference)
        check(maxDifference(zeroReference.output, primary.copyOf(zeroReference.output.size)) < 1e-7f)

        val impulse = FloatArray(64_123)
        impulse[52_123] = .5f
        val aligned = referenceRun(GuardianReferenceNoiseEnhancer.Profile.SHORT, impulse, FloatArray(impulse.size), intArrayOf(400, 801))
        check(aligned.output.indices.maxBy { abs(aligned.output[it]) } == 52_123)
        check(abs(aligned.output[52_123] - .5f) < 1e-7f)

        val zeroPrimary = referenceRun(GuardianReferenceNoiseEnhancer.Profile.SHORT, FloatArray(primary.size), reference, intArrayOf(800))
        check(zeroPrimary.output.all { it == 0f }) { "A reference alone generated diagnostic speech-like PCM" }
    }

    test("reference output uses the prototype linear fade without gain normalization") {
        val rng = Random(1600L)
        val source = FloatArray(52_000) { if (it < 48_000) (rng.nextGaussian() * .03).toFloat() else 0f }
        source[48_400] = .5f
        val run = referenceRun(GuardianReferenceNoiseEnhancer.Profile.SHORT, source, source, intArrayOf(800))
        // Matching source/reference learns approximately unity. At fade index400,
        // linear weight=.25 preserves .75 of the .5 impulse, unlike a cosine fade.
        check(abs(run.output[48_400] - .375f) < .003f) {
            "The 100ms fade is not the prototype linear blend: ${run.output[48_400]}"
        }
        check(run.enhancer.outputPeak >= .375f && run.enhancer.outputPeak < 1f)
    }

    test("new instances reset state and invalid pairs fail before consuming either source") {
        referenceRun(GuardianReferenceNoiseEnhancer.Profile.SHORT, primary, reference, intArrayOf(800))
        val fresh = referenceRun(GuardianReferenceNoiseEnhancer.Profile.SHORT, FloatArray(reference.size), reference, intArrayOf(901, 1, 800))
        check(fresh.output.all { it == 0f } && !fresh.enhancer.observedSpeechDuringProfile)
        val invalid = GuardianReferenceNoiseEnhancer(GuardianReferenceNoiseEnhancer.Profile.SHORT)
        check(runCatching { invalid.process(floatArrayOf(0f), FloatArray(0)) }.isFailure)
        check(runCatching { invalid.process(floatArrayOf(Float.NaN), floatArrayOf(0f)) }.isFailure)
        check(runCatching { invalid.process(floatArrayOf(0f), floatArrayOf(Float.POSITIVE_INFINITY)) }.isFailure)
        check(invalid.inputSamples == 0L && invalid.outputSamples == 0L && !invalid.calibrationReady)
        val following = invalid.process(floatArrayOf(.1f), floatArrayOf(.2f))
        check(following.isEmpty() && invalid.inputSamples == 1L)
    }
    println("$tests reference noise enhancer synthetic groups passed")
}

private fun referenceRun(
    profile: GuardianReferenceNoiseEnhancer.Profile,
    primary: FloatArray,
    reference: FloatArray,
    chunks: IntArray,
    speechEnd: Int? = null,
): ReferenceRun {
    check(primary.size == reference.size)
    val enhancer = GuardianReferenceNoiseEnhancer(profile)
    val output = FloatArray(primary.size)
    var offset = 0
    var block = 0
    var produced = 0
    var marked = false
    while (offset < primary.size) {
        val end = minOf(primary.size, offset + chunks[block++ % chunks.size])
        if (!marked && speechEnd != null && end >= speechEnd) {
            enhancer.markCalibrationSpeech(end.toLong())
            marked = true
        }
        val p = primary.copyOfRange(offset, end)
        val r = reference.copyOfRange(offset, end)
        val pCopy = p.copyOf(); val rCopy = r.copyOf()
        val clean = enhancer.process(p, r)
        check(p.contentEquals(pCopy) && r.contentEquals(rCopy)) { "Source PCM was mutated" }
        check(clean.all { it.isFinite() })
        clean.copyInto(output, produced)
        produced += clean.size
        check(enhancer.inputSamples == end.toLong() && enhancer.outputSamples == produced.toLong())
        val retained = enhancer.inputSamples - enhancer.outputSamples
        check(retained in 0L..511L)
        if (end >= 512) check(retained in 256L..511L) { "Finalized sample clock or bounded lookahead changed" }
        offset = end
    }
    check(enhancer.process(FloatArray(0), FloatArray(0)).isEmpty()) { "Empty input fabricated a final flush" }
    check(enhancer.outputSamples == produced.toLong())
    var peak = 0f
    for (index in 0 until produced) peak = maxOf(peak, abs(output[index]))
    check(enhancer.outputPeak == peak)
    return ReferenceRun(output.copyOf(produced), enhancer)
}

private fun maxDifference(first: FloatArray, second: FloatArray): Float {
    check(first.size == second.size)
    return first.indices.maxOfOrNull { abs(first[it] - second[it]) } ?: 0f
}

internal object ReferenceNoiseEnhancerTest {
    @JvmStatic
    fun main(args: Array<String>) = referenceNoiseEnhancerTests()
}
