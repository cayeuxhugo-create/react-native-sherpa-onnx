package expo.modules.guardiannativeprotection

/** A digital-zero startup block is not an ambient-noise measurement. */
internal object GuardianNoiseFloorSeed {
    fun initial(rms: Float, minimum: Float, maximum: Float): Float {
        require(rms.isFinite() && rms >= 0f)
        return if (rms == 0f) 0f else rms.coerceIn(minimum, maximum)
    }
}

/** One bounded gain for a completed neurally confirmed window. Never evidence
 * of speech, never a per-frame level controller; it preserves waveform/SNR. */
internal object GuardianSpeechSegmentGain {
    data class Result(val pcm: FloatArray, val gain: Float)
    fun apply(pcm: FloatArray, confirmed: Boolean, maximumGain: Float = 4f): Result {
        require(maximumGain.isFinite() && maximumGain in 1f..4f)
        require(pcm.all { it.isFinite() })
        val peak = pcm.maxOfOrNull { kotlin.math.abs(it) } ?: 0f
        val rms = if (pcm.isEmpty()) 0f else kotlin.math.sqrt(pcm.sumOf { it.toDouble()*it } / pcm.size).toFloat()
        val gain = if (!confirmed || peak == 0f || rms == 0f) 1f else
            minOf(maximumGain, .015f / rms, .8f / peak).coerceAtLeast(1f)
        return Result(FloatArray(pcm.size) { pcm[it] * gain }, gain)
    }
}
