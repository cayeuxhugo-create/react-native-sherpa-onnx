package expo.modules.guardiannativeprotection

import java.io.File
import java.util.Locale

/** Explicit local recording opt-in, independent of JavaScript configuration.
 * Private controls take precedence over the legacy external controls. */
internal data class GuardianDiagnosticOptions(
    val enabled: Boolean,
    val profile: GuardianVoiceProfile,
    val wavDirectory: File?,
    val controlSource: String,
    val profileFromFile: Boolean,
) {
    companion object {
        fun controlFile(privateRoot: File, externalRoot: File?, name: String): File? {
            val own = File(privateRoot, name)
            if (own.exists()) return own
            return externalRoot?.let { File(it, name) }?.takeIf { it.exists() }
        }

        fun resolve(configEnabled: Boolean, configuredProfile: GuardianVoiceProfile,
                    privateRoot: File, externalRoot: File?): GuardianDiagnosticOptions {
            fun choose(name: String): File? {
                return controlFile(privateRoot, externalRoot, name)
            }
            fun read(file: File?): String? = file?.let {
                runCatching { it.readText().removePrefix("\uFEFF").trim() }.getOrNull()
            }
            val control = choose("guardian-record-wav.txt")
            val recording = read(control)?.uppercase(Locale.ROOT) == "ON"
            val enabled = configEnabled || recording
            val requested = if (enabled) read(choose("guardian-voice-profile.txt")) else null
            val override = requested?.let { runCatching { GuardianVoiceProfile.parse(it) }.getOrNull() }
            return GuardianDiagnosticOptions(
                enabled = enabled,
                profile = override ?: configuredProfile,
                wavDirectory = if (recording) File(privateRoot, "guardian-diagnostics") else null,
                controlSource = if (recording) {
                    if (control?.parentFile == privateRoot) "PRIVATE_FILE" else "EXTERNAL_FILE"
                } else if (configEnabled) "CONFIG" else "DISABLED",
                profileFromFile = override != null,
            )
        }
    }
}
