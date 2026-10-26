package expo.modules.guardiannativeprotection

/** Experimental smoothing of measured speech-band excess/background ratio.
 * This score ranks channels; it is NEVER evidence that speech is present. */
internal class GuardianChannelQualityScores {
    private val scores = FloatArray(4) { Float.NaN }
    fun update(view: Int, excessToBackgroundDb: Float, clippedFraction: Float): Float {
        if (!excessToBackgroundDb.isFinite() || !clippedFraction.isFinite()) return -60f
        val value = excessToBackgroundDb.coerceIn(-60f,120f) -
            (clippedFraction.coerceIn(0f,1f)*600f).coerceAtMost(12f)
        val previous=scores[view]
        val score=if (previous.isFinite()) previous+.25f*(value-previous) else value
        scores[view]=score
        return score
    }
    fun reset() { scores.fill(Float.NaN) }
}
