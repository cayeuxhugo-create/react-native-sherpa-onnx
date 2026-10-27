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
    count += diagnosticSchedulerTests()
    println("$count decoder concurrency tests passed")
}

private fun diagnosticSchedulerTests(): Int {
    var count = 0
    val evidence = GuardianQwenProgressiveDecoder.AcousticEvidence.NEURAL_CONFIRMED
    fun await(latch: CountDownLatch) { check(latch.await(3, TimeUnit.SECONDS)) }
    fun records(done: CountDownLatch): Pair<MutableList<String>, GuardianVoiceDiagnostics> {
        val events = java.util.Collections.synchronizedList(mutableListOf<String>())
        return events to GuardianVoiceDiagnostics(true, "TEST", { line ->
            events.add(line)
            if (line.contains("\"event\":\"STUDY_QWEN_RESULT\"")) done.countDown()
        })
    }
    fun resultLines(events: MutableList<String>) = synchronized(events) {
        events.filter { it.contains("\"event\":\"STUDY_QWEN_RESULT\"") }
    }
    fun scenario(name: String, run: () -> Unit) { run(); count++; println("PASS $name") }

    scenario("two global diagnostic slots after primary, overflow explicit, FIFO and final priority") {
        val entered = CountDownLatch(1); val allow = CountDownLatch(1); val done = CountDownLatch(2)
        val calls = java.util.Collections.synchronizedList(mutableListOf<Pair<Int, Float>>())
        val (events, diag) = records(done)
        val qwen = GuardianQwenRescueRecognizer { pcm ->
            calls.add(pcm.size to pcm[0])
            if (calls.size == 1) { entered.countDown(); await(allow) }
            GuardianQwenRescueRecognizer.Result("RESULT_${pcm.size}")
        }
        val result = Results(); val decoder = GuardianQwenProgressiveDecoder(qwen, result, diagnostics = diag)
        try {
            check(decoder.offerFinal(1, FloatArray(16000), "PRIMARY", evidence)); await(entered)
            val channel0 = FloatArray(24000) { .25f }
            check(decoder.offerDiagnostic(9, channel0, "CHANNEL_0"))
            channel0.fill(.9f)
            check(decoder.offerDiagnostic(9, FloatArray(32000), "CHANNEL_1"))
            check(decoder.pendingDiagnosticCount() == 2)
            check(decoder.offerDiagnosticStatus(9, FloatArray(48000), "CHANNEL_2") ==
                GuardianQwenProgressiveDecoder.DiagnosticOfferStatus.QUEUE_FULL)
            check(decoder.offerFinal(2, FloatArray(40000), "PRIMARY", evidence))
            allow.countDown(); await(done)
            check(calls.map { it.first } == listOf(16000, 40000, 24000, 32000))
            check(calls[2].second == .25f) { "Queued diagnostic did not own its PCM" }
            check(result.finals == listOf("RESULT_16000", "RESULT_40000") && result.partials.isEmpty())
            check(decoder.pendingDiagnosticCount() == 0)
            check(resultLines(events).size == 2 && resultLines(events).none { it.contains("CHANNEL_2") })
            check(events.any { it.contains("\"status\":\"QUEUE_FULL\"") && it.contains("\"accepted\":false") })
        } finally { allow.countDown(); decoder.close(); await(qwen.released) }
    }

    scenario("in-flight diagnostic remains non-preemptible, two queued slots stay globally bounded") {
        val entered = CountDownLatch(1); val allow = CountDownLatch(1); val done = CountDownLatch(3)
        val calls = java.util.Collections.synchronizedList(mutableListOf<Int>())
        val (_, diag) = records(done)
        val qwen = GuardianQwenRescueRecognizer { pcm ->
            calls.add(pcm.size)
            if (calls.size == 1) { entered.countDown(); await(allow) }
            GuardianQwenRescueRecognizer.Result("RESULT_${pcm.size}")
        }
        val result = Results(); val decoder = GuardianQwenProgressiveDecoder(qwen, result, diagnostics = diag)
        try {
            check(decoder.offerDiagnostic(7, FloatArray(16000), "CHANNEL_0")); await(entered)
            check(decoder.offerDiagnostic(7, FloatArray(24000), "CHANNEL_1"))
            check(decoder.offerDiagnostic(7, FloatArray(32000), "CHANNEL_2"))
            check(!decoder.offerDiagnostic(7, FloatArray(48000), "CHANNEL_3"))
            check(decoder.pendingDiagnosticCount() == 2)
            check(decoder.offerFinal(1, FloatArray(40000), "PRIMARY", evidence))
            allow.countDown(); await(done)
            check(calls == listOf(16000, 40000, 24000, 32000))
            check(result.finals == listOf("RESULT_40000") && result.partials.isEmpty())
        } finally { allow.countDown(); decoder.close(); await(qwen.released) }
    }

    scenario("reset gives queued and in-flight diagnostics terminal dropped states without product callbacks") {
        val entered = CountDownLatch(1); val allow = CountDownLatch(1); val done = CountDownLatch(4)
        val calls = java.util.concurrent.atomic.AtomicInteger()
        val (events, diag) = records(done)
        val qwen = GuardianQwenRescueRecognizer {
            if (calls.incrementAndGet() == 1) { entered.countDown(); await(allow) }
            GuardianQwenRescueRecognizer.Result("PRIVATE_DIAGNOSTIC")
        }
        val result = Results(); val decoder = GuardianQwenProgressiveDecoder(qwen, result, diagnostics = diag)
        try {
            check(decoder.offerDiagnostic(1, FloatArray(16000), "OLD_INFLIGHT")); await(entered)
            check(decoder.offerDiagnostic(2, FloatArray(16000), "OLD_QUEUED_0"))
            check(decoder.offerDiagnostic(2, FloatArray(16000), "OLD_QUEUED_1"))
            decoder.reset()
            check(decoder.pendingDiagnosticCount() == 0)
            check(decoder.offerDiagnostic(3, FloatArray(16000), "NEW"))
            allow.countDown(); await(done)
            val lines = resultLines(events)
            check(lines.count { it.contains("\"status\":\"DROPPED\"") && it.contains("\"reason\":\"RESET\"") } == 3)
            check(lines.count { it.contains("\"status\":\"DECODED\"") } == 1)
            check(lines.filter { it.contains("DROPPED") }.all { it.contains("\"text\":null") && it.contains("\"eligible\":false") })
            check(calls.get() == 2 && result.finals.isEmpty() && result.partials.isEmpty())
        } finally { allow.countDown(); decoder.close(); await(qwen.released) }
    }

    scenario("close reports both queued diagnostics dropped and rejects new offers explicitly") {
        val entered = CountDownLatch(1); val allow = CountDownLatch(1); val done = CountDownLatch(3)
        val (events, diag) = records(done)
        val qwen = GuardianQwenRescueRecognizer {
            entered.countDown(); await(allow)
            GuardianQwenRescueRecognizer.Result("CLOSED_DIAGNOSTIC")
        }
        val result = Results(); val decoder = GuardianQwenProgressiveDecoder(qwen, result, diagnostics = diag)
        try {
            check(decoder.offerDiagnostic(1, FloatArray(16000), "INFLIGHT")); await(entered)
            check(decoder.offerDiagnostic(2, FloatArray(16000), "QUEUED_0"))
            check(decoder.offerDiagnostic(2, FloatArray(16000), "QUEUED_1"))
            decoder.close()
            check(decoder.offerDiagnosticStatus(3, FloatArray(16000), "CLOSED") ==
                GuardianQwenProgressiveDecoder.DiagnosticOfferStatus.CLOSED)
            check(decoder.pendingDiagnosticCount() == 0)
            allow.countDown(); await(done); await(qwen.released)
            check(resultLines(events).all { it.contains("\"status\":\"DROPPED\"") && it.contains("DECODER_CLOSED") })
            check(result.finals.isEmpty() && result.partials.isEmpty())
        } finally { allow.countDown(); decoder.close(); await(qwen.released) }
    }

    scenario("invalid input, empty transcript and decode failure are distinct diagnostic statuses") {
        val entered = CountDownLatch(1); val allow = CountDownLatch(1); val done = CountDownLatch(3)
        val calls = java.util.concurrent.atomic.AtomicInteger()
        val (events, diag) = records(done)
        val qwen = GuardianQwenRescueRecognizer {
            when (calls.incrementAndGet()) {
                1 -> { entered.countDown(); await(allow); GuardianQwenRescueRecognizer.Result("  ") }
                2 -> error("Diagnostic native failure")
                else -> GuardianQwenRescueRecognizer.Result("DIAGNOSTIC_ONLY")
            }
        }
        val result = Results(); val decoder = GuardianQwenProgressiveDecoder(qwen, result, diagnostics = diag)
        try {
            check(decoder.offerDiagnosticStatus(1, FloatArray(0), "EMPTY_PCM") == GuardianQwenProgressiveDecoder.DiagnosticOfferStatus.EMPTY_PCM)
            check(decoder.offerDiagnosticStatus(1, FloatArray(1), "SHORT") == GuardianQwenProgressiveDecoder.DiagnosticOfferStatus.INVALID_LENGTH)
            check(decoder.offerDiagnosticStatus(1, FloatArray(16000) { Float.NaN }, "NAN") == GuardianQwenProgressiveDecoder.DiagnosticOfferStatus.INVALID_PCM)
            check(decoder.offerDiagnosticStatus(0, FloatArray(16000), "SEGMENT") == GuardianQwenProgressiveDecoder.DiagnosticOfferStatus.INVALID_SEGMENT)
            check(decoder.offerDiagnosticStatus(1, FloatArray(16000), " ") == GuardianQwenProgressiveDecoder.DiagnosticOfferStatus.INVALID_LANE)
            check(decoder.offerDiagnostic(1, FloatArray(16000), "EMPTY_TEXT")); await(entered)
            check(decoder.offerDiagnostic(2, FloatArray(16000), "FAILED"))
            check(decoder.offerDiagnostic(3, FloatArray(16000), "DECODED"))
            allow.countDown(); await(done)
            val lines = resultLines(events)
            check(lines.any { it.contains("\"status\":\"EMPTY\"") && it.contains("EMPTY_TEXT") })
            check(lines.any { it.contains("\"status\":\"FAILED\"") && it.contains("DECODE_FAILED") })
            check(lines.any { it.contains("\"status\":\"DECODED\"") && it.contains("DIAGNOSTIC_ONLY") })
            check(calls.get() == 3 && result.finals.isEmpty() && result.partials.isEmpty())
        } finally { allow.countDown(); decoder.close(); await(qwen.released) }
    }

    scenario("channel rotation follows successful physical offers, not IDs, failures or list parity") {
        val order = GuardianQwenProgressiveDecoder.DiagnosticChannelOrder()
        val channels = listOf(7, 0, 4, 1)
        check(order.ordered(channels) == listOf(0, 1, 4, 7))
        val served = mutableListOf<Int>()
        // Repeated odd IDs deliberately cannot explain the resulting physical rotation.
        for (windowId in listOf(3, 3, 5, 9, 11, 11)) {
            check(windowId % 2 == 1)
            val attempts = order.ordered(channels)
            val accepted = attempts.first()
            served += accepted
            order.accepted(accepted)
            // Remaining full-queue offers do not move the cursor.
            check(order.ordered(channels).first() == attempts[1])
        }
        check(served == listOf(0, 1, 4, 7, 0, 1))
        order.accepted(4)
        check(order.ordered(listOf(0, 1, 7)) == listOf(7, 0, 1))
        val all = GuardianQwenProgressiveDecoder.DiagnosticChannelOrder()
        val thirty = (0 until 30).toList()
        val seen = mutableSetOf<Int>()
        repeat(15) {
            val accepted = all.ordered(thirty).take(2)
            accepted.forEach { channel -> seen += channel; all.accepted(channel) }
        }
        check(seen == thirty.toSet())
        check(all.ordered(emptyList()).isEmpty())
    }
    return count
}
