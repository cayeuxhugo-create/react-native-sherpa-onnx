package expo.modules.guardiannativeprotection

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

internal object ResilienceTest {
    @JvmStatic fun main(args: Array<String>) {
        val evidence = GuardianQwenProgressiveDecoder.AcousticEvidence.NEURAL_CONFIRMED
        // A failing product listener AND its error reporter must not strand the drain.
        val recovered = CountDownLatch(1)
        val calls = AtomicInteger()
        val qwen = GuardianQwenRescueRecognizer {
            calls.incrementAndGet(); GuardianQwenRescueRecognizer.Result("result")
        }
        val decoder = GuardianQwenProgressiveDecoder(qwen, object : GuardianQwenProgressiveDecoder.Listener {
            override fun onPartial(hypothesis: GuardianQwenProgressiveDecoder.Hypothesis) { error("partial consumer") }
            override fun onFinal(hypothesis: GuardianQwenProgressiveDecoder.Hypothesis) {
                if (hypothesis.segmentId == 1L) error("final consumer")
                recovered.countDown()
            }
            override fun onDecoderUnavailable(segmentId: Long, isFinal: Boolean) { error("reporter") }
            override fun onBackpressure(segmentId: Long, pendingFinalCount: Int) { error("backpressure consumer") }
        })
        try {
            check(decoder.offerFinal(1, FloatArray(16000), "test", evidence))
            check(decoder.offerFinal(2, FloatArray(16000), "test", evidence))
            check(recovered.await(3, TimeUnit.SECONDS))
            check(calls.get() == 2)
            for (invalid in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
                check(!decoder.offerFinal(3, FloatArray(16000) { invalid }, "test", evidence))
                check(!decoder.offerPartial(3, FloatArray(16000) { invalid }, evidence))
            }
            check(calls.get() == 2)
        } finally { decoder.close(); check(qwen.released.await(3, TimeUnit.SECONDS)) }
        println("PASS failing final/error callbacks do not stop later decodes; invalid primary PCM rejected")

        val warned = CountDownLatch(1)
        val allow = CountDownLatch(1)
        val completed = CountDownLatch(1)
        val slowQwen = GuardianQwenRescueRecognizer {
            check(allow.await(3, TimeUnit.SECONDS)); GuardianQwenRescueRecognizer.Result("recovered")
        }
        val slow = GuardianQwenProgressiveDecoder(slowQwen, object : GuardianQwenProgressiveDecoder.Listener {
            override fun onPartial(hypothesis: GuardianQwenProgressiveDecoder.Hypothesis) = Unit
            override fun onFinal(hypothesis: GuardianQwenProgressiveDecoder.Hypothesis) { completed.countDown() }
            override fun onDecoderUnavailable(segmentId: Long, isFinal: Boolean) { warned.countDown() }
            override fun onBackpressure(segmentId: Long, pendingFinalCount: Int) = Unit
        }, GuardianQwenProgressiveDecoder.Configuration(decodeWarningMs = 50))
        try {
            check(slow.offerFinal(1, FloatArray(16000), "test", evidence))
            check(warned.await(3, TimeUnit.SECONDS))
            check(slowQwen.released.count == 1L) // No unsafe release during native execution.
            allow.countDown(); check(completed.await(3, TimeUnit.SECONDS))
        } finally { allow.countDown(); slow.close(); check(slowQwen.released.await(3, TimeUnit.SECONDS)) }
        println("PASS slow native decode is signalled without interrupting it and can recover")

        val queue = GuardianEventDelivery<String>(2)
        val committed = mutableListOf<String>()
        check(queue.offer("id-1", "first")); check(queue.offer("id-1", "must-not-replace"))
        check(queue.offer("id-2", "second"))
        check(queue.drain { error("SQLite unavailable") }.isEmpty())
        check(queue.size == 2 && queue.lastError != null)
        check(queue.drain { committed += it } == listOf("first", "second"))
        check(committed == listOf("first", "second") && queue.size == 0 && queue.lastError == null)
        check(queue.drain { error("must not redeliver acknowledged event") }.isEmpty())
        check(queue.offer("id-3", "third")); check(queue.offer("id-4", "fourth"))
        check(!queue.offer("id-5", "fifth"))
        check(queue.size == 2)
        queue.drain { }
        check(queue.lastError == "VOICE_DELIVERY_QUEUE_FULL") // A lost offer must remain visible.
        println("PASS storage failure retains IDs, FIFO retry and acknowledgements; overflow stays visible")
    }
}
