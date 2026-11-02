package expo.modules.guardiannativeprotection

/** Extra evidence for the opt-in LONG rescue, never a minimum duration for the
 * original admission path. Counts coherent islands of FSMN speech posterior,
 * not energy, ASR words, or the sum of scattered positive frames in a window.
 * Frame indices refer to FSMN's 10 ms shifts in this exact confirmation window.
 */
internal object GuardianTemporalSpeechCore {
    const val MIN_ISLAND_FRAMES = 15
    const val MAX_GAP_FRAMES = 120
    const val MIN_CORE_POSITIVE_FRAMES = 100
    const val MIN_CORE_DENSITY = .25

    data class Result(
        val accepted: Boolean,
        val positiveFrames: Int,
        val corePositiveFrames: Int,
        val coreSpanFrames: Int,
        val coreDensity: Double,
        val coreStartFrame: Int = -1,
        val coreEndFrameExclusive: Int = -1,
    )

    fun evaluate(p0: FloatArray): Result {
        require(p0.all { it.isFinite() && it in 0f..1f }) { "Invalid FSMN posterior for temporal core" }
        var positiveFrames = 0
        var islandStart = -1
        var groupStart = -1
        var groupEnd = -1
        var groupPositive = 0
        var bestStart = -1
        var bestEnd = -1
        var bestPositive = 0
        var bestDensity = 0.0
        var bestQualifies = false

        fun finishGroup() {
            if (groupStart < 0) return
            val density = groupPositive.toDouble() / (groupEnd - groupStart)
            val qualifies = groupPositive >= MIN_CORE_POSITIVE_FRAMES && density >= MIN_CORE_DENSITY
            // A qualifying core must win even when a larger sparse group
            // exists. Rejected windows retain their strongest group for logs.
            if ((qualifies && !bestQualifies) || (qualifies == bestQualifies &&
                    (groupPositive > bestPositive || (groupPositive == bestPositive && density > bestDensity)))) {
                bestStart = groupStart
                bestEnd = groupEnd
                bestPositive = groupPositive
                bestDensity = density
                bestQualifies = qualifies
            }
        }

        fun finishIsland(end: Int) {
            if (islandStart < 0) return
            val length = end - islandStart
            if (length >= MIN_ISLAND_FRAMES) {
                if (groupStart < 0 || islandStart - groupEnd > MAX_GAP_FRAMES) {
                    finishGroup()
                    groupStart = islandStart
                    groupPositive = 0
                }
                groupEnd = end
                groupPositive += length
            }
            islandStart = -1
        }

        for (i in p0.indices) {
            // Match GuardianFsmnPolicy, including Float32 subtraction before
            // promotion and the Double probability-difference threshold.
            val positive = (1f - p0[i]).toDouble() >= p0[i].toDouble() + GuardianFsmnPolicy.SPEECH_NOISE_THRESHOLD
            if (positive) {
                positiveFrames++
                if (islandStart < 0) islandStart = i
            } else finishIsland(i)
        }
        finishIsland(p0.size)
        finishGroup()
        return Result(bestQualifies, positiveFrames, bestPositive,
            if (bestStart < 0) 0 else bestEnd - bestStart, bestDensity, bestStart, bestEnd)
    }
}

/** The additional model can rescue only an ordinary, fully evaluated FSMN
 * refusal on a clean, coherent LONG view. It cannot override an accepted path,
 * missing inference, failed FireRed, suspect calibration, or missing sources.
 */
internal object GuardianTemporalRescuePolicy {
    fun eligible(
        originalAccepted: Boolean,
        useFiltered: Boolean,
        calibrationClean: Boolean,
        coherent: Boolean,
        filteredFireAccepted: Boolean,
        filteredFsmnAvailable: Boolean,
        filteredFsmnAccepted: Boolean,
    ): Boolean = !originalAccepted && !useFiltered && calibrationClean && coherent &&
        filteredFireAccepted && filteredFsmnAvailable && !filteredFsmnAccepted

    fun admit(eligible: Boolean, gtcrnFireAccepted: Boolean, gtcrnFsmnAccepted: Boolean,
        coreAccepted: Boolean): Boolean = eligible && gtcrnFireAccepted && gtcrnFsmnAccepted && coreAccepted
}
