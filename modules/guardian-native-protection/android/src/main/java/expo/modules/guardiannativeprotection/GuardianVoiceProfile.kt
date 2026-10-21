package expo.modules.guardiannativeprotection

/** Capture remains unchanged. Only the ASR input route differs. */
enum class GuardianVoiceProfile {
    DISTANT, NOISY;

    companion object {
        fun parse(value: String): GuardianVoiceProfile =
            entries.firstOrNull { it.name == value.trim().uppercase(java.util.Locale.ROOT) }
                ?: throw IllegalArgumentException("voiceProfile doit être DISTANT ou NOISY")
    }
}
