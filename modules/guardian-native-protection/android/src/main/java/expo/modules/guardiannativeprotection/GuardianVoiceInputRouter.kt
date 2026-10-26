package expo.modules.guardiannativeprotection

/** Single-threaded, bounded join of primary and asynchronous GTCRN frames.
 * Identity includes sequence AND capture time. Never splice a late frame into
 * another time slot, append it twice, or modify an AudioEngine pooled array. */
internal class GuardianVoiceInputRouter(
    private val noisy: Boolean,
    private val emit: (Input, FloatArray, Boolean) -> Unit,
    private val warning: (String) -> Unit,
    private val maxWaitMs: Long = 200L,
) {
    data class Key(val sequence: Long, val capturedAtNanos: Long)
    data class Input(
        val key: Key,
        val pcm: FloatArray,
        val gain: Float,
        val enhancementExpected: Boolean,
        val queuedAtMs: Long,
        val preGainRms: Float? = null,
        val preGainNoiseRms: Float? = null,
        val estimatedSnrDb: Float? = null,
        val captureChannelRevision: Long = 0L,
    )
    private val pending = ArrayDeque<Input>()
    private val enhanced = linkedMapOf<Key, FloatArray>()
    private var lastEmittedSequence = Long.MIN_VALUE

    fun primary(input: Input, nowMs: Long) {
        if (input.key.sequence <= lastEmittedSequence) return
        pending.addLast(input)
        drain(nowMs)
    }

    fun enhanced(key: Key, pcm: FloatArray, nowMs: Long) {
        if (!noisy || key.sequence <= lastEmittedSequence) return
        enhanced[key] = pcm
        // Also bounds results arriving before the primary callback.
        while (enhanced.size > 32) enhanced.remove(enhanced.keys.first())
        drain(nowMs)
    }

    fun drain(nowMs: Long) {
        while (pending.isNotEmpty()) {
            val frame = pending.first()
            val clean = enhanced[frame.key]
            val valid = clean != null && clean.size == frame.pcm.size && clean.all { it.isFinite() }
            val waits = noisy && frame.enhancementExpected
            if (waits && !valid && nowMs - frame.queuedAtMs < maxWaitMs && pending.size < 16) return
            pending.removeFirst()
            enhanced.remove(frame.key)
            val useEnhanced = waits && valid
            val pcm = if (useEnhanced) {
                // Same capture gain, no per-frame noise normalization.
                FloatArray(clean!!.size) { limit(clean[it] * frame.gain) }
            } else frame.pcm
            if (waits && !useEnhanced) warning("GTCRN absent/invalide/en retard : repli PCM original")
            lastEmittedSequence = frame.key.sequence
            enhanced.keys.removeAll { it.sequence <= lastEmittedSequence }
            emit(frame, pcm, useEnhanced)
        }
    }

    fun reset() {
        pending.clear()
        enhanced.clear()
        lastEmittedSequence = Long.MIN_VALUE
    }

    private fun limit(sample: Float): Float {
        val magnitude = kotlin.math.abs(sample)
        if (magnitude <= 0.82f) return sample
        val excess = magnitude - 0.82f
        val limited = 0.82f + (0.98f - 0.82f) * excess / (excess + 0.18f)
        return if (sample >= 0f) limited else -limited
    }
}
