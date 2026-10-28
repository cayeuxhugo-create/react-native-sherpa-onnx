package expo.modules.guardiannativeprotection

/** Every sample encodes its source position, so omitted quiet audio cannot hide
 * behind equal-valued silence. No model or Android runtime is needed. */
object SpeechSegmenterContinuityTest {
    private data class Phase(val samples: Int, val speech: Boolean)
    private data class Expected(val start: Int, val end: Int, val reason: String)
    private data class Window(val pcm: FloatArray, val end: Long, val reason: String)
    private val natural = "QWEN_NEURAL_SILENCE"
    private val technical = "QWEN_TECHNICAL_ROLLOVER"
    private val pause = "QWEN_PAUSE_ALIGNED_ROLLOVER"

    @JvmStatic fun main(args: Array<String>) {
        val cases = listOf(
            Triple("natural end", listOf(Phase(16000, true), Phase(19200, false)),
                listOf(Expected(0, 35200, natural))),
            Triple("continuous technical rollover", listOf(Phase(129600, true), Phase(19200, false)),
                listOf(Expected(0, 128000, technical), Expected(121600, 148800, natural))),
            Triple("silence at cap ends without rollover", listOf(Phase(128000, true), Phase(19200, false)),
                listOf(Expected(0, 128000, natural))),
            Triple("50 ms beyond cap before speech resumes",
                listOf(Phase(128000, true), Phase(800, false), Phase(800, true), Phase(19200, false)),
                listOf(Expected(0, 128000, technical), Expected(121600, 148800, natural))),
            Triple("pause-aligned rollover", listOf(Phase(64000, true), Phase(16000, false),
                Phase(49600, true), Phase(19200, false)),
                listOf(Expected(0, 80000, pause), Expected(73600, 148800, natural))),
            Triple("pending silence after an existing pause", listOf(Phase(64000, true), Phase(16000, false),
                Phase(48000, true), Phase(800, false), Phase(800, true), Phase(19200, false)),
                listOf(Expected(0, 80000, pause), Expected(73600, 148800, natural))),
            Triple("1150 ms beyond cap before speech resumes",
                listOf(Phase(128000, true), Phase(18400, false), Phase(800, true), Phase(19200, false)),
                listOf(Expected(0, 128000, technical), Expected(121600, 166400, natural))),
            Triple("quiet block crosses cap before speech resumes",
                listOf(Phase(124800, true), Phase(9600, false), Phase(800, true), Phase(19200, false)),
                listOf(Expected(0, 128000, technical), Expected(121600, 154400, natural))),
            Triple("two resumed overflows preserve both quiet intervals", listOf(Phase(128000, true),
                Phase(800, false), Phase(120800, true), Phase(800, false), Phase(800, true), Phase(19200, false)),
                listOf(Expected(0, 128000, technical), Expected(121600, 249600, technical),
                    Expected(243200, 270400, natural))),
            Triple("large terminal quiet block clears pending audio", listOf(Phase(128000, true),
                Phase(800, false), Phase(40000, false), Phase(800, true), Phase(19200, false)),
                listOf(Expected(0, 128000, natural), Expected(136800, 188800, natural))),
        )
        val chunkPatterns = listOf(intArrayOf(800), intArrayOf(137, 853, 29, 2003, 800, 12345), intArrayOf(200000))
        for ((name, phases, expected) in cases) for (chunks in chunkPatterns) {
            verify(name, phases, expected, chunks)
        }
        resetDiscardsPendingSilence()
        println("PASS segmenter continuity: ${cases.size * chunkPatterns.size} source-exact cases and reset with pending audio")
    }

    private fun verify(name: String, phases: List<Phase>, expected: List<Expected>, chunks: IntArray) {
        val source = FloatArray(phases.sumOf { it.samples }) { (it + 1).toFloat() / 1048576f }
        val windows = ArrayList<Window>()
        var available = 0
        var starts = 0
        var ends = 0
        val segmenter = GuardianSpeechSegmenter(object : GuardianSpeechSegmenter.Sink {
            override fun onSpeechStarted() { starts++ }
            override fun onSpeechEnded() { ends++ }
            override fun onWindow(id: Long, pcm: FloatArray, final: Boolean, reason: String) =
                error("Missing source clock")
            override fun onWindowAt(id: Long, pcm: FloatArray, final: Boolean, reason: String, endSample: Long) {
                check(final && id == windows.size + 1L && pcm.size in 1..128000) { "$name: invalid final" }
                val start = (endSample - pcm.size).toInt()
                check(start >= 0 && endSample <= available) { "$name: invalid source coordinates" }
                check(pcm.contentEquals(source.copyOfRange(start, endSample.toInt()))) {
                    "$name: PCM differs from source [$start,$endSample), chunks=${chunks.toList()}"
                }
                windows.add(Window(pcm, endSample, reason))
            }
        })
        var position = 0
        var chunk = 0
        for (phase in phases) {
            val stop = position + phase.samples
            while (position < stop) {
                val count = minOf(chunks[chunk++ % chunks.size], stop - position)
                val block = source.copyOfRange(position, position + count)
                available = position + count
                segmenter.accept(block, phase.speech)
                block.fill(Float.NaN) // A capture callback may immediately reuse its array.
                position += count
            }
        }
        val actual = windows.map { Expected((it.end - it.pcm.size).toInt(), it.end.toInt(), it.reason) }
        check(actual == expected) { "$name: changed boundaries/reasons: $actual, chunks=${chunks.toList()}" }
        check(starts == expected.count { it.reason == natural } && starts == ends) { "$name: unbalanced utterance callbacks" }
        for ((a, b) in windows.zipWithNext()) if (a.reason != natural) {
            check(a.pcm.takeLast(6400) == b.pcm.take(6400)) { "$name: changed rollover overlap" }
        }
    }

    private fun resetDiscardsPendingSilence() {
        var source = FloatArray(146400) { (it + 1).toFloat() / 1048576f }
        var available = 0
        var starts = 0
        var ends = 0
        var windows = 0
        val segmenter = GuardianSpeechSegmenter(object : GuardianSpeechSegmenter.Sink {
            override fun onSpeechStarted() { starts++ }
            override fun onSpeechEnded() { ends++ }
            override fun onWindow(id: Long, pcm: FloatArray, final: Boolean, reason: String) =
                error("Missing source clock")
            override fun onWindowAt(id: Long, pcm: FloatArray, final: Boolean, reason: String, endSample: Long) {
                check(final && reason == natural && endSample == available.toLong())
                check(pcm.contentEquals(source)) { "Reset replayed pending audio or old pre-roll" }
                windows++
            }
        })
        for (position in source.indices step 800) {
            available = position + 800
            segmenter.accept(source.copyOfRange(position, available), position < 128000)
        }
        check(windows == 0 && starts == 1 && ends == 0)
        segmenter.reset()
        source = FloatArray(20000) { (262144 + it + 1).toFloat() / 1048576f }
        available = 800
        segmenter.accept(source.copyOfRange(0, 800), true)
        available = source.size
        segmenter.accept(source.copyOfRange(800, source.size), false)
        check(windows == 1 && starts == 2 && ends == 2)
    }
}
