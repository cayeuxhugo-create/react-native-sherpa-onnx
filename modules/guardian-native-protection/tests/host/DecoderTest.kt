package expo.modules.guardiannativeprotection

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

private class Results : GuardianQwenProgressiveDecoder.Listener {
    val partials = mutableListOf<String>()
    val finals = mutableListOf<String>()
    val finalReady = CountDownLatch(1)
    override fun onPartial(hypothesis: GuardianQwenProgressiveDecoder.Hypothesis) { partials += hypothesis.text }
    override fun onFinal(hypothesis: GuardianQwenProgressiveDecoder.Hypothesis) { finals += hypothesis.text; finalReady.countDown() }
    override fun onDecoderUnavailable(segmentId: Long, isFinal: Boolean) { error("Unexpected unavailable") }
    override fun onBackpressure(segmentId: Long, pendingFinalCount: Int) { error("Unexpected backpressure") }
}
fun decoderTests() {
    var count = 0
    val evidence = GuardianQwenProgressiveDecoder.AcousticEvidence.NEURAL_CONFIRMED
    fun scenario(name: String, finalInFlight: Boolean, closeInFlight: Boolean) {
        val entered = CountDownLatch(1); val allow = CountDownLatch(1)
        val calls = java.util.concurrent.atomic.AtomicInteger()
        val qwen = GuardianQwenRescueRecognizer {
            val n = calls.incrementAndGet()
            if (n == 1) { entered.countDown(); check(allow.await(3, TimeUnit.SECONDS)) }
            GuardianQwenRescueRecognizer.Result(if (n == 1) "STALE" else "CURRENT")
        }
        val result = Results(); val decoder = GuardianQwenProgressiveDecoder(qwen, result)
        try {
            if (finalInFlight) decoder.offerFinal(1, FloatArray(16_000), "test", evidence)
            else decoder.offerPartial(1, FloatArray(16_000), evidence)
            check(entered.await(3, TimeUnit.SECONDS))
            if (closeInFlight) decoder.close() else {
                decoder.reset()
                check(decoder.offerFinal(2, FloatArray(16_000), "test", evidence))
            }
            allow.countDown()
            if (closeInFlight) {
                check(qwen.released.await(3, TimeUnit.SECONDS))
                check(result.finals.isEmpty() && result.partials.isEmpty())
            } else {
                check(result.finalReady.await(3, TimeUnit.SECONDS))
                check(result.finals == listOf("CURRENT") && result.partials.isEmpty())
            }
        } finally {
            allow.countDown(); decoder.close(); check(qwen.released.await(3, TimeUnit.SECONDS))
        }
        count++; println("PASS $name")
    }
    scenario("reset invalidates an in-flight partial", false, false)
    scenario("reset invalidates an in-flight final", true, false)
    scenario("close invalidates an in-flight partial and releases on worker", false, true)
    scenario("close invalidates an in-flight final and releases on worker", true, true)
    run {
        val entered = CountDownLatch(1); val allow = CountDownLatch(1)
        val lengths = java.util.Collections.synchronizedList(mutableListOf<Int>())
        val qwen = GuardianQwenRescueRecognizer { pcm ->
            lengths.add(pcm.size)
            if (lengths.size == 1) { entered.countDown(); check(allow.await(3, TimeUnit.SECONDS)) }
            GuardianQwenRescueRecognizer.Result("COMPLETE")
        }
        val result = Results(); val decoder = GuardianQwenProgressiveDecoder(qwen, result)
        try {
            check(decoder.offerPartial(1, FloatArray(16_000), evidence))
            check(entered.await(3, TimeUnit.SECONDS))
            check(decoder.offerPartial(1, FloatArray(32_000), evidence))
            check(!decoder.offerPartial(1, FloatArray(112_000), evidence))
            check(decoder.offerFinal(1, FloatArray(128_000), "MAX_DURATION", evidence))
            allow.countDown()
            check(result.finalReady.await(3, TimeUnit.SECONDS))
            check(lengths == listOf(16_000, 128_000))
            check(result.finals == listOf("COMPLETE") && result.partials.isEmpty())
        } finally { allow.countDown(); decoder.close(); check(qwen.released.await(3, TimeUnit.SECONDS)) }
        count++; println("PASS long partial skipped, early decode retained, full eight-second final preserved")
    }
    println("$count decoder concurrency tests passed")
}
