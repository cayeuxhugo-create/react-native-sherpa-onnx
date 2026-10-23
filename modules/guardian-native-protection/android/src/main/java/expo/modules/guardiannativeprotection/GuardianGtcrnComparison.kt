package expo.modules.guardiannativeprotection

import java.io.File
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Recording-only secondary lane. Never calls the segmenter/decoder/listeners.
 * Its bounded queue, files and worker are independent of the original lane. */
internal class GuardianGtcrnComparison(
    private val directory: File,
    private val profile: GuardianVoiceProfile,
    private val diagnostics: GuardianVoiceDiagnostics?,
) : AutoCloseable {
    private data class Message(val key: GuardianVoiceInputRouter.Key, val primary: GuardianVoiceInputRouter.Input? = null, val enhanced: FloatArray? = null)
    private val queue = ArrayBlockingQueue<Message>(32)
    private val closed = AtomicBoolean(false)
    private val dropped = AtomicInteger()
    private val worker = Thread({ runWorker() }, "Guardian-GTCRN-Comparison").apply {
        isDaemon = true; priority = Thread.MIN_PRIORITY; start()
    }
    fun primary(input: GuardianVoiceInputRouter.Input) = offer(Message(input.key, primary=input))
    fun enhanced(key: GuardianVoiceInputRouter.Key, pcm: FloatArray) = offer(Message(key, enhanced=pcm))
    private fun offer(message: Message) {
        if (!closed.get() && !queue.offer(message)) dropped.incrementAndGet()
    }
    override fun close() { closed.set(true) }
    private fun runWorker() {
        val primary = linkedMapOf<GuardianVoiceInputRouter.Key, GuardianVoiceInputRouter.Input>()
        val enhanced = linkedMapOf<GuardianVoiceInputRouter.Key, FloatArray>()
        var writer: GuardianDiagnosticWavWriter? = null
        var pairs = 0L
        var samples = 0L
        var lastSequence = Long.MIN_VALUE
        try {
            while (!closed.get() || queue.isNotEmpty()) {
                val m = queue.poll(50, TimeUnit.MILLISECONDS) ?: continue
                if (m.key.sequence <= lastSequence) { dropped.incrementAndGet(); continue }
                m.primary?.let { primary[m.key] = it }
                m.enhanced?.let { enhanced[m.key] = it }
                while (primary.size > 16) { primary.remove(primary.keys.first()); dropped.incrementAndGet() }
                while (enhanced.size > 16) { enhanced.remove(enhanced.keys.first()); dropped.incrementAndGet() }
                val input = primary[m.key] ?: continue
                val clean = enhanced[m.key] ?: continue
                primary.remove(m.key); enhanced.remove(m.key)
                if (clean.size != input.pcm.size || clean.any { !it.isFinite() } || !input.gain.isFinite() || input.gain <= 0) {
                    dropped.incrementAndGet(); continue
                }
                // Same capture gain/limiter as the former enhanced route, not a
                // separately normalized noise signal. Original input is immutable.
                val matched = FloatArray(clean.size) { i -> limit(clean[i] * input.gain) }
                val w = writer ?: GuardianDiagnosticWavWriter(directory, profile.name,
                    diagnostics?.sessionId ?: "comparison", diagnostics, emitLifecycleEvents=false).also { writer = it }
                w.write("truth", input.pcm, m.key.sequence, m.key.capturedAtNanos)
                w.write("asr", matched, m.key.sequence, m.key.capturedAtNanos)
                check(!w.isClosed) { "GTCRN comparison WAV closed unexpectedly" }
                lastSequence = m.key.sequence; pairs++; samples += input.pcm.size
            }
        } catch (error: Exception) {
            closed.set(true)
            diagnostics?.event("GTCRN_COMPARISON_ERROR", "error" to error.javaClass.simpleName)
        } finally {
            writer?.close()
            diagnostics?.event("GTCRN_COMPARISON_SAVED", "prefix" to writer?.filePrefix,
                "pairs" to pairs, "samples" to samples, "dropped" to dropped.get(),
                "unmatched" to primary.size + enhanced.size,
                "role" to "RECORDING_ONLY_NO_DECISION")
        }
    }
    private fun limit(x: Float): Float {
        val magnitude = kotlin.math.abs(x)
        if (magnitude <= .82f) return x
        val excess = magnitude - .82f
        val y = .82f + .16f * excess / (excess + .18f)
        return if (x >= 0f) y else -y
    }
}
