package expo.modules.guardiannativeprotection

/** Admission equivalent to the official non-stream postprocessor for our fixed
 * 8s maximum windows: smooth 5, threshold .5, min speech 20 frames. The upstream
 * state machine confirms on the 21st consecutive positive smoothed frame.
 * Silence merging, padding and max-duration splitting cannot create the first
 * speech interval, so they do not change this yes/no decision. */
internal object GuardianFireRedPolicy {
    const val THRESHOLD = 0.5f
    const val MAX_INPUT_GAIN = 64f
    const val MIN_SPEECH_FRAMES = 20
    const val SMOOTH_FRAMES = 5
    data class Decision(val accepted: Boolean, val peakProbability: Float,
                        val longestPositiveFrames: Int)

    fun evaluate(probabilities: FloatArray): Decision {
        require(probabilities.all { it.isFinite() && it in 0f..1f })
        var sum = 0.0
        var run = 0
        var longest = 0
        var peak = 0f
        for (i in probabilities.indices) {
            val p = probabilities[i]
            sum += p.toDouble()
            if (i >= SMOOTH_FRAMES) sum -= probabilities[i - SMOOTH_FRAMES].toDouble()
            peak = maxOf(peak, p)
            val smoothed = sum / minOf(i + 1, SMOOTH_FRAMES)
            run = if (smoothed >= THRESHOLD) run + 1 else 0
            longest = maxOf(longest, run)
        }
        return Decision(longest > MIN_SPEECH_FRAMES, peak, longest)
    }
}
