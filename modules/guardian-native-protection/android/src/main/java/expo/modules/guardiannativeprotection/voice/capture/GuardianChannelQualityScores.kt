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

/** Whole-utterance microphone ranking, AFTER neural confirmation on each source.
 * Compare all microphones on the SAME speech support, not their loudest samples.
 * This measures band-energy contrast, never recognition accuracy or speech presence.
 */
internal object GuardianMicrophoneSelection {
    data class Candidate(val index: Int, val pcm: FloatArray, val confirmed: Boolean,
        val speech: BooleanArray, val contributedSamples: Int)
    data class Quality(val contrastDb: Double?, val clippedFraction: Double)
    data class Selection(val candidate: Candidate, val qualities: Map<Int, Quality>, val reason: String)

    fun select(candidates: List<Candidate>, preferred: Int? = null): Selection? {
        require(candidates.map { it.index }.distinct().size == candidates.size)
        val eligible = candidates.filter { it.confirmed }
        if (eligible.isEmpty()) return null
        val size = eligible.first().pcm.size
        require(size >= 400 && candidates.all { it.pcm.size == size && it.pcm.all(Float::isFinite) })
        val frames = 1 + (size - 400) / 160
        require(eligible.all { it.speech.size == frames })
        val support = BooleanArray(frames) { i -> eligible.any { it.speech[i] } }
        val baseline = eligible.firstOrNull { it.index == preferred }
            ?: eligible.sortedWith(compareByDescending<Candidate> { it.contributedSamples }.thenBy { it.index }).first()
        val qualities = candidates.associate { it.index to quality(it.pcm, support) }
        // A small apparent advantage must not toggle the source between phrases.
        // With no usable background estimate, preserve the confirmed incumbent.
        val base = qualities.getValue(baseline.index).contrastDb
        val best = eligible.filter { qualities.getValue(it.index).contrastDb != null &&
            qualities.getValue(it.index).clippedFraction <= .02 }
            .maxWithOrNull(compareBy<Candidate> { qualities.getValue(it.index).contrastDb!! }.thenBy { -it.index })
        val winner = if (best != null && base != null &&
            (qualities.getValue(best.index).contrastDb!! >= base + 3.0 ||
                qualities.getValue(baseline.index).clippedFraction > .02)) best else baseline
        return Selection(winner, qualities, if (winner.index == baseline.index)
            "CONFIRMED_INCUMBENT" else "CONFIRMED_BAND_CONTRAST_ADVANTAGE")
    }

    private fun quality(pcm: FloatArray, support: BooleanArray): Quality {
        val clipped = pcm.count { kotlin.math.abs(it) >= .979f }.toDouble() / pcm.size
        val band = DoubleArray(pcm.size)
        val hpA = kotlin.math.exp(-2.0 * Math.PI * 120 / 16000)
        val lpA = 1 - kotlin.math.exp(-2.0 * Math.PI * 3800 / 16000)
        var previous = 0.0; var hp = 0.0; var lp = 0.0
        for (i in pcm.indices) {
            val x = pcm[i].toDouble(); hp = hpA * (hp + x - previous); previous = x
            lp += lpA * (hp - lp); band[i] = lp * lp
        }
        var voice = 0.0; var voiceCount = 0
        val background = ArrayList<Double>()
        for (i in support.indices) {
            var energy = 0.0
            for (j in i * 160 until i * 160 + 400) energy += band[j]
            energy /= 400
            if (support[i]) { voice += energy; voiceCount++ }
            // Leave 100 ms around neural support out of the noise estimate.
            else if ((maxOf(0, i - 10)..minOf(support.lastIndex, i + 10)).none { support[it] }) background.add(energy)
        }
        if (voiceCount == 0 || background.size < 20) return Quality(null, clipped)
        background.sort()
        val noise = background[background.size / 2]
        if (noise <= 0 || voice <= 0) return Quality(null, clipped)
        val contrast = 10 * kotlin.math.log10(voice / voiceCount / noise) - minOf(12.0, clipped * 600)
        return Quality(contrast.takeIf { it.isFinite() }, clipped)
    }
}
