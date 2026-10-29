package expo.modules.guardiannativeprotection

/** Identity of the exact float32 waveform, not of its transcription. */
internal object GuardianPcmIdentity {
    fun sha256(pcm: FloatArray): String {
        val bytes = java.nio.ByteBuffer.allocate(pcm.size * 4).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        pcm.forEach { bytes.putFloat(it) }
        return java.security.MessageDigest.getInstance("SHA-256").digest(bytes.array())
            .joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}

/** Peak-bounded model conditioning. A gain is never evidence of speech. */
internal object GuardianNeuralInputGain {
    fun gain(pcm: FloatArray, maximum: Float): Float {
        require(maximum >= 1f && maximum.isFinite())
        require(pcm.all { it.isFinite() })
        val peak = pcm.maxOfOrNull { kotlin.math.abs(it) } ?: 0f
        return if (peak == 0f) maximum else minOf(maximum, 0.8f / peak).coerceAtLeast(1f)
    }

    fun forVad(pcm: FloatArray, maximum: Float = 4f): FloatArray {
        val gain = gain(pcm, maximum)
        return FloatArray(pcm.size) { pcm[it] * gain }
    }
}

/** Match compensation to the INPUT samples, including delayed/partial native
 * outputs. Never divide an old output by the gain of the newest input block. */
internal class GuardianGtcrnLeveler(private val capacity: Int = 16000) {
    private data class Block(val gain: Float, var remaining: Int)
    private val pending = ArrayDeque<Block>()
    private var samples = 0

    fun prepareInPlace(pcm: FloatArray) {
        require(pcm.size <= capacity - samples) { "GTCRN gain history overflow" }
        if (pcm.isEmpty()) return
        val gain = GuardianNeuralInputGain.gain(pcm, 6f)
        for (i in pcm.indices) pcm[i] *= gain
        pending.addLast(Block(gain, pcm.size))
        samples += pcm.size
    }

    fun restoreInPlace(pcm: FloatArray) {
        require(pcm.size <= samples) { "GTCRN output exceeds gain history" }
        require(pcm.all { it.isFinite() }) { "GTCRN non-finite output" }
        var offset = 0
        while (offset < pcm.size) {
            val block = pending.first()
            val count = minOf(pcm.size - offset, block.remaining)
            for (i in offset until offset + count) pcm[i] /= block.gain
            offset += count
            block.remaining -= count
            samples -= count
            if (block.remaining == 0) pending.removeFirst()
        }
    }

    fun reset() { pending.clear(); samples = 0 }
}

/** The denoised neural view can admit weak speech. Clear short words that
 * denoising occasionally suppresses require agreement of both original VAD
 * views (gain1 and gain4). Never OR a single raw view with the cleaned view:
 * amplified raw admitted recorded silence; unboosted raw alone admitted pink
 * noise in regression tests. Missing enhancement uses the original raw VAD. */
internal class GuardianSpeechEvidence {
    fun accept(primarySpeech: Boolean, enhancedSpeech: Boolean, clearRawSpeech: Boolean,
               samples: Int, enhancedAvailable: Boolean): Boolean {
        require(samples >= 0)
        return if (enhancedAvailable) enhancedSpeech || (primarySpeech && clearRawSpeech)
            else primarySpeech
    }
    fun reset() = Unit
}
