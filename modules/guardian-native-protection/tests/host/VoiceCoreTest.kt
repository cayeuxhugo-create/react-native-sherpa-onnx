package expo.modules.guardiannativeprotection

private data class Window(val id: Long, val pcm: FloatArray, val final: Boolean, val reason: String)
private class RecordingSink : GuardianSpeechSegmenter.Sink {
    val windows = mutableListOf<Window>()
    var starts = 0
    var ends = 0
    override fun onSpeechStarted() { starts++ }
    override fun onSpeechEnded() { ends++ }
    override fun onWindow(id: Long, pcm: FloatArray, final: Boolean, reason: String) {
        windows += Window(id, pcm, final, reason)
    }
}
private fun checkThat(condition: Boolean, message: String) { check(condition) { message } }
private var tests = 0
private fun test(name: String, run: () -> Unit) { run(); tests++; println("PASS $name") }
private val frame = 800 // 50 ms at 16 kHz
private fun silence(s: GuardianSpeechSegmenter, frames: Int) { repeat(frames) { s.accept(FloatArray(frame), false) } }

fun main() {
    test("30 seconds of noise classified non-speech never schedules Qwen") {
        val sink = RecordingSink(); val s = GuardianSpeechSegmenter(sink)
        repeat(600) { s.accept(FloatArray(frame) { if (it % 2 == 0) 0.8f else -0.8f }, false) }
        checkThat(sink.windows.isEmpty() && sink.starts == 0, "RMS must not override VAD")
    }
    test("completed utterance schedules one final and no interim offline decodes") {
        val sink = RecordingSink(); val s = GuardianSpeechSegmenter(sink)
        repeat(60) { s.accept(FloatArray(frame) { 0.001f }, true) }
        checkThat(sink.windows.isEmpty(), "Offline decode before the phrase ended")
        silence(s, 25)
        checkThat(sink.windows.size == 1 && sink.windows.single().final, "Expected one full final")
        checkThat(sink.windows.single().pcm.count { it == 0.001f } == 60 * frame, "Speech samples lost")
    }
    test("weak accepted speech is preserved without an absolute energy gate") {
        val sink = RecordingSink(); val s = GuardianSpeechSegmenter(sink)
        repeat(10) { s.accept(FloatArray(frame) { 0.0001f }, true) }; silence(s, 25)
        checkThat(sink.windows.count { it.final } == 1, "Weak voice lost")
        checkThat(sink.windows.last().pcm.count { it == 0.0001f } == 8000, "Weak samples altered")
    }
    test("1000 ms pre-roll retains a delayed onset exactly once") {
        val sink = RecordingSink(); val s = GuardianSpeechSegmenter(sink, preRollMs = 1000)
        repeat(24) { i -> s.accept(FloatArray(frame) { (i + 1) / 1000f }, false) }
        repeat(10) { s.accept(FloatArray(frame) { 0.2f }, true) }; silence(s, 28)
        val pcm = sink.windows.single { it.final }.pcm
        checkThat(pcm.take(16000).chunked(frame).map { it.first() } == (5..24).map { it / 1000f }, "Wrong pre-roll")
        checkThat(pcm.count { it == 0.2f } == 8000, "Current frame duplicated")
    }
    test("two-second pre-roll preserves weak onset before a delayed cleaned VAD decision") {
        val sink = RecordingSink(); val s = GuardianSpeechSegmenter(sink)
        repeat(48) { i -> s.accept(FloatArray(frame) { (i+1)/1000f }, false) }
        repeat(10) { s.accept(FloatArray(frame) { 0.2f }, true) }; silence(s, 28)
        val pcm = sink.windows.single().pcm
        checkThat(pcm.take(32000).chunked(frame).map { it.first() } == (9..48).map { it/1000f }, "Wrong extended pre-roll")
        checkThat(sink.windows.single().final && pcm.count { it == 0.2f } == 8000, "Onset duplicated or partial scheduled")
    }
    test("one natural final with 1200 ms tail, no partial during silence") {
        val sink = RecordingSink(); val s = GuardianSpeechSegmenter(sink)
        repeat(25) { s.accept(FloatArray(frame) { 0.1f }, true) }
        val partials = sink.windows.count { !it.final }; silence(s, 40)
        checkThat(sink.windows.count { !it.final } == partials, "Silent partial")
        val final = sink.windows.single { it.final }
        checkThat(final.pcm.size == 25 * frame + 19200, "Silence was not trimmed")
        checkThat(sink.starts == 1 && sink.ends == 1, "Unbalanced speech callbacks")
    }
    test("technical rollover overlaps 400 ms without losing source samples") {
        val sink = RecordingSink(); val s = GuardianSpeechSegmenter(sink)
        val source = FloatArray(160_000) { it.toFloat() / 160_000f }
        for (offset in source.indices step frame) s.accept(source.copyOfRange(offset, offset + frame), true)
        silence(s, 28)
        val finals = sink.windows.filter { it.final }
        checkThat(finals.size == 2 && finals[0].reason == "QWEN_TECHNICAL_ROLLOVER", "Wrong rollover")
        checkThat(finals[0].pcm.takeLast(6400) == finals[1].pcm.take(6400), "Missing acoustic overlap")
        val rebuilt = finals[0].pcm.toList() + finals[1].pcm.drop(6400)
        checkThat(rebuilt.take(source.size) == source.toList(), "PCM discontinuity")
        checkThat(sink.starts == 1 && sink.ends == 1, "Rollover became a product utterance")
    }
    test("technical cap uses an existing pause and preserves the buffered next utterance") {
        val sink = RecordingSink(); val s = GuardianSpeechSegmenter(sink)
        val source = FloatArray(160_000) { (it + 1).toFloat() / 200_000f }
        for (offset in source.indices step frame) {
            val inPause = offset >= 64_000 && offset < 80_000
            s.accept(source.copyOfRange(offset, offset + frame), !inPause)
        }
        silence(s, 28)
        val windows = sink.windows
        checkThat(windows.size == 2, "Pause generated an extra window")
        checkThat(windows[0].reason == "QWEN_PAUSE_ALIGNED_ROLLOVER", "Known pause was not used")
        checkThat(windows[0].pcm.size == 80_000, "Pause tail was truncated")
        checkThat(windows[0].pcm.takeLast(6400) == windows[1].pcm.take(6400), "Overlap lost")
        val rebuilt = windows[0].pcm.toList() + windows[1].pcm.drop(6400)
        checkThat(rebuilt.take(source.size) == source.toList(), "Buffered following speech lost or duplicated")
        checkThat(windows.all { it.pcm.size <= 128_000 && it.final }, "Unbounded or interim window")
        checkThat(sink.starts == 1 && sink.ends == 1, "Technical pause became a new product utterance")
    }
    test("an 850 ms dropout is not used as a technical pause boundary") {
        val sink = RecordingSink(); val s = GuardianSpeechSegmenter(sink)
        repeat(80) { s.accept(FloatArray(frame) { 0.02f }, true) }
        silence(s, 17)
        repeat(103) { s.accept(FloatArray(frame) { 0.02f }, true) }
        silence(s, 28)
        checkThat(sink.windows.first().reason == "QWEN_TECHNICAL_ROLLOVER", "Weak middle split at short dropout")
        checkThat(sink.windows.first().pcm.size == 128_000, "Technical cap changed")
    }
    test("brief VAD dropout keeps the same phrase and its weak consonants") {
        val sink = RecordingSink(); val s = GuardianSpeechSegmenter(sink)
        repeat(10) { s.accept(FloatArray(frame) { 0.02f }, true) }
        repeat(16) { s.accept(FloatArray(frame) { 0.003f }, false) }
        repeat(10) { s.accept(FloatArray(frame) { 0.02f }, true) }
        silence(s, 28)
        val finals = sink.windows.filter { it.final }
        checkThat(finals.size == 1 && sink.starts == 1, "Intra-phrase dropout split the sentence")
        checkThat(finals.single().pcm.count { it == 0.003f } == frame*16, "Weak middle audio discarded")
    }
    test("silence at the technical cap cannot create a noise-only rollover") {
        val sink = RecordingSink(); val s = GuardianSpeechSegmenter(sink)
        repeat(156) { s.accept(FloatArray(frame) { 0.02f }, true) }
        silence(s, 40)
        val finals = sink.windows.filter { it.final }
        checkThat(finals.size == 1 && finals.single().pcm.size == 128000, "Silent tail opened another decode")
        checkThat(finals.single().reason == "QWEN_NEURAL_SILENCE", "Silence caused a technical rollover")
        checkThat(sink.starts == 1 && sink.ends == 1, "Cap left the phrase active")
    }
    test("reset discards a broken utterance and does not reuse its pre-roll") {
        val sink = RecordingSink(); val s = GuardianSpeechSegmenter(sink)
        repeat(10) { s.accept(FloatArray(frame) { 0.1f }, true) }; s.reset(); silence(s, 20)
        checkThat(sink.windows.isEmpty() && sink.ends == 1, "Broken segment published")
        repeat(10) { s.accept(FloatArray(frame) { 0.2f }, true) }; silence(s, 28)
        checkThat(sink.windows.single { it.final }.pcm.none { it == 0.1f }, "Old speech leaked")
    }
    test("non-finite PCM is rejected") {
        val s = GuardianSpeechSegmenter(RecordingSink())
        checkThat(runCatching { s.accept(floatArrayOf(Float.NaN), true) }.isFailure, "NaN admitted")
    }
    data class Out(val sequence: Long, val pcm: List<Float>, val enhanced: Boolean)
    fun key(i: Long, time: Long = i * 50_000_000) = GuardianVoiceInputRouter.Key(i, time)
    fun input(i: Long, wait: Boolean = true, now: Long = 0L) = GuardianVoiceInputRouter.Input(key(i), floatArrayOf(0.1f, -0.1f), 2f, wait, now)
    fun router(noisy: Boolean, out: MutableList<Out>, warnings: MutableList<String> = mutableListOf()) =
        GuardianVoiceInputRouter(noisy, { f, p, e -> out += Out(f.key.sequence, p.toList(), e) }, { warnings += it })
    test("DISTANT emits unchanged samples immediately and ignores GTCRN") {
        val out = mutableListOf<Out>(); val r = router(false, out)
        r.enhanced(key(1), floatArrayOf(0.3f, 0.3f), 0); r.primary(input(1), 0)
        checkThat(out.single() == Out(1, listOf(0.1f, -0.1f), false), "Quiet path changed")
    }
    test("NOISY handles enhanced-before-primary and copies gain correctly") {
        val out = mutableListOf<Out>(); val r = router(true, out)
        r.enhanced(key(1), floatArrayOf(0.2f, -0.2f), 0); r.primary(input(1), 1)
        checkThat(out.single() == Out(1, listOf(0.4f, -0.4f), true), "Enhanced path not used")
    }
    test("asynchronous enhanced frames never reorder capture") {
        val out = mutableListOf<Out>(); val r = router(true, out)
        r.primary(input(1), 0); r.primary(input(2), 0)
        r.enhanced(key(2), floatArrayOf(0.2f, 0.2f), 20)
        checkThat(out.isEmpty(), "Frame 2 passed frame 1")
        r.enhanced(key(1), floatArrayOf(0.3f, 0.3f), 30)
        checkThat(out.map { it.sequence } == listOf(1L, 2L) && out.all { it.enhanced }, "Wrong ordering")
    }
    test("missing GTCRN falls back by deadline without waiting for another frame") {
        val out = mutableListOf<Out>(); val warnings = mutableListOf<String>(); val r = router(true, out, warnings)
        r.primary(input(1), 0); r.drain(199); checkThat(out.isEmpty(), "Early timeout")
        r.drain(200); checkThat(out.size == 1 && !out[0].enhanced && warnings.size == 1, "No fallback")
        r.enhanced(key(1), floatArrayOf(0.9f, 0.9f), 300)
        checkThat(out.size == 1, "Late result duplicated audio")
    }
    test("wrong timestamp, wrong size and non-finite enhancement cannot contaminate PCM") {
        for (variant in 0..2) {
            val out = mutableListOf<Out>(); val r = router(true, out)
            r.primary(input(1), 0)
            when (variant) {
                0 -> r.enhanced(key(1, 123), floatArrayOf(0.2f, 0.2f), 0)
                1 -> r.enhanced(key(1), floatArrayOf(0.2f), 0)
                2 -> r.enhanced(key(1), floatArrayOf(Float.NaN, 0f), 0)
            }
            r.drain(200)
            checkThat(out.single().pcm == listOf(0.1f, -0.1f) && !out[0].enhanced, "Invalid view used")
        }
    }
    test("a saturated join queue drains with bounded memory") {
        val out = mutableListOf<Out>(); val r = router(true, out)
        for (i in 1L..100L) r.primary(input(i), 0)
        checkThat(out.size >= 85, "Pending queue grew unbounded")
        r.drain(200); checkThat(out.size == 100, "Capture frames lost")
    }
    test("profile values validate and normalize") {
        checkThat(GuardianVoiceProfile.parse(" distant ") == GuardianVoiceProfile.DISTANT, "Profile normalization")
        checkThat(runCatching { GuardianVoiceProfile.parse("unknown") }.isFailure, "Invalid profile admitted")
    }
    println("$tests core tests passed")
    fireRedPolicyTests()
    fireRedStreamPolicyTests()
    adaptiveChannelsTests()
    continuousStudyTests()
    decoderTests()
    diagnosticsTests()
    wavWriterTests()
    speechControlTests()
    gtcrnComparisonTests()
    neuralInputGainTests()
    diagnosticOptionsTests()
}
