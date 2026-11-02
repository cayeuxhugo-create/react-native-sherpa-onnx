package expo.modules.guardiannativeprotection

/** Extend with real lookahead without dropping any sample of the candidate.
 * Availability and route continuity must still be verified by the window owner. */
internal object GuardianAsrWindowPolicy {
    const val MAX_SAMPLES = 128_000L
    const val CONTEXT_SAMPLES = 32_000L
    fun contextEnd(start: Long, end: Long): Long? {
        if (start < 0 || end <= start || end - start >= MAX_SAMPLES) return null
        return end + minOf(CONTEXT_SAMPLES, MAX_SAMPLES - (end - start))
    }
}

/** Pure admission rule used by the opt-in candidate, independently testable. */
internal object GuardianAdaptiveAdmissionPolicy {
    fun useFiltered(calibrationClean: Boolean, coherent: Boolean, fire: Boolean, fsmn: Boolean,
        originalAccepted: Boolean, selectedChannel: Int?, filteredChannel: Int): Boolean =
        calibrationClean && coherent && fire && fsmn &&
            (!originalAccepted || selectedChannel == filteredChannel)
}
