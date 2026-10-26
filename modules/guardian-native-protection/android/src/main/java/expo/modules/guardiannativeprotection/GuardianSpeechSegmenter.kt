package expo.modules.guardiannativeprotection

/** Single-threaded PCM segmentation. Speech decisions MUST come from a neural VAD,
 * never from RMS/Far Voice or the words returned by the recognizer. */
internal class GuardianSpeechSegmenter(
    private val sink: Sink,
    private val sampleRate: Int = 16_000,
    private val preRollMs: Int = PRE_ROLL_MS,
) {
    interface Sink {
        fun onSpeechStarted()
        fun onSpeechEnded()
        fun onWindow(id: Long, pcm: FloatArray, final: Boolean, reason: String)
        fun onWindowAt(id: Long, pcm: FloatArray, final: Boolean, reason: String, endSample: Long) =
            onWindow(id, pcm, final, reason)
    }

    private val preRollSamples = sampleRate * preRollMs / 1_000
    private val tailSamples = sampleRate * TAIL_MS / 1_000
    private val endSilenceSamples = sampleRate * END_SILENCE_MS / 1_000
    private val maxSamples = sampleRate * 8
    private val ring = FloatArray(preRollSamples)
    private var ringHead = 0
    private var ringSize = 0
    private val segment = FloatArray(maxSamples)
    private var size = 0
    private var id = 0L
    private var active = false
    private var silenceSamples = 0
    private var lastSpeechEnd = 0
    private var pauseCut = 0
    private var totalSamples = 0L
    private var segmentStart = 0L

    fun accept(pcm: FloatArray, speech: Boolean) {
        require(pcm.all { it.isFinite() }) { "PCM non fini" }
        if (pcm.isEmpty()) return
        val inputStart = totalSamples
        totalSamples += pcm.size
        if (!active && speech) {
            segmentStart = inputStart - ringSize
            id++
            active = true
            size = 0
            for (i in 0 until ringSize) {
                segment[size++] = ring[(ringHead - ringSize + i + ring.size) % ring.size]
            }
            silenceSamples = 0
            lastSpeechEnd = size
            pauseCut = 0
            sink.onSpeechStarted()
        }
        if (active) {
            var offset = 0
            while (offset < pcm.size) {
                if (size == maxSamples && !speech) {
                    // The final silence may hit the technical cap before the
                    // end timeout. Count it without opening a new noise-only
                    // rollover window or growing the fixed PCM buffer.
                    silenceSamples += pcm.size - offset
                    if (silenceSamples >= endSilenceSamples) finishNatural()
                    break
                }
                if (size == maxSamples) {
                    // Prefer a completed neural pause already inside the buffer.
                    // Retain the entire remainder: selecting a boundary must never
                    // discard the beginning of the following utterance.
                    val cut = if (pauseCut > 0) pauseCut else size
                    val reason = if (pauseCut > 0) "QWEN_PAUSE_ALIGNED_ROLLOVER" else "QWEN_TECHNICAL_ROLLOVER"
                    sink.onWindowAt(id, segment.copyOf(cut), true, reason, segmentStart + cut)
                    id++
                    val overlap = sampleRate * ROLLOVER_OVERLAP_MS / 1_000
                    val retainedStart = cut - overlap
                    segment.copyInto(segment, 0, retainedStart, size)
                    size -= retainedStart
                    segmentStart += retainedStart
                    lastSpeechEnd = (lastSpeechEnd - retainedStart).coerceAtLeast(0)
                    pauseCut = 0
                }
                val take = minOf(pcm.size - offset, maxSamples - size)
                pcm.copyInto(segment, size, offset, offset + take)
                size += take
                offset += take
                if (speech) {
                    if (silenceSamples >= sampleRate * MIN_ROLLOVER_PAUSE_MS / 1_000) {
                        val candidate = lastSpeechEnd + minOf(tailSamples, silenceSamples)
                        // Bound both windows and avoid repeatedly cutting a tiny
                        // prefix near the start of a technical window.
                        if (candidate >= sampleRate * MIN_ROLLOVER_PREFIX_MS / 1_000 &&
                            candidate <= maxSamples - sampleRate * MIN_ROLLOVER_REMAINDER_MS / 1_000) {
                            pauseCut = candidate
                        }
                    }
                    lastSpeechEnd = size
                    silenceSamples = 0
                } else {
                    silenceSamples += take
                }
                if (!speech && silenceSamples >= endSilenceSamples) {
                    finishNatural()
                    break
                }
            }
            // Offline Qwen is called once per completed segment. Keeping the
            // capture and pre-roll continuous does not require partial decodes.
        }
        for (sample in pcm) {
            ring[ringHead] = sample
            ringHead = (ringHead + 1) % ring.size
            ringSize = minOf(ringSize + 1, ring.size)
        }
    }

    private fun finishNatural() {
        val end = minOf(size, lastSpeechEnd + tailSamples)
        sink.onWindowAt(id, segment.copyOf(end), true, "QWEN_NEURAL_SILENCE", segmentStart + end)
        active = false
        size = 0
        sink.onSpeechEnded()
    }

    /** A capture gap must not concatenate unrelated audio or validate old noise. */
    fun reset() {
        val wasActive = active
        active = false
        size = 0
        totalSamples = 0
        segmentStart = 0
        ringSize = 0
        ringHead = 0
        silenceSamples = 0
        lastSpeechEnd = 0
        pauseCut = 0
        if (wasActive) sink.onSpeechEnded()
    }
    companion object {
        // Keep weak consonants before/after the confident neural speech core,
        // and bridge short VAD dropouts inside a phrase. Not a speech gate.
        const val PRE_ROLL_MS = 2000
        const val TAIL_MS = 1200
        const val END_SILENCE_MS = 1200
        const val ROLLOVER_OVERLAP_MS = 400
        const val MIN_ROLLOVER_PAUSE_MS = 900
        const val MIN_ROLLOVER_PREFIX_MS = 2000
        const val MIN_ROLLOVER_REMAINDER_MS = 1000
    }

}
