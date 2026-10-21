package expo.modules.guardiannativeprotection
import java.io.File
import java.nio.file.Files
fun diagnosticOptionsTests() {
    val root = Files.createTempDirectory("guardian-controls").toFile()
    val own = File(root,"private").apply { mkdir() }
    val ext = File(root,"external").apply { mkdir() }
    fun resolve(enabled: Boolean = false) = GuardianDiagnosticOptions.resolve(enabled, GuardianVoiceProfile.DISTANT, own, ext)
    fun set(dir: File, name: String, text: String) = File(dir,name).writeText(text)
    try {
        check(!resolve().enabled && resolve().wavDirectory == null)
        set(own,"guardian-voice-profile.txt","NOISY")
        check(resolve().profile == GuardianVoiceProfile.DISTANT)
        println("PASS default diagnostics remain disabled and profile file is ignored without opt-in")
        set(own,"guardian-record-wav.txt","\uFEFF ON\n")
        val active = resolve()
        check(active.enabled && active.wavDirectory == File(own,"guardian-diagnostics"))
        check(active.controlSource == "PRIVATE_FILE" && active.profile == GuardianVoiceProfile.NOISY)
        println("PASS private recording opt-in enables WAV and logs without changing persisted config")
        set(ext,"guardian-record-wav.txt","ON")
        set(own,"guardian-record-wav.txt","OFF")
        check(!resolve().enabled)
        println("PASS private OFF overrides stale external ON")
        set(own,"guardian-record-wav.txt","unknown")
        check(!resolve().enabled)
        println("PASS invalid private control never falls back to external recording")
        File(own,"guardian-record-wav.txt").delete()
        val legacy = resolve()
        check(legacy.enabled && legacy.controlSource == "EXTERNAL_FILE" && legacy.wavDirectory == File(own,"guardian-diagnostics"))
        println("PASS legacy external opt-in still writes only into private storage")
        File(ext,"guardian-record-wav.txt").delete()
        check(resolve(true).enabled && resolve(true).wavDirectory == null)
        println("PASS configured logs alone do not record audio")
        set(own,"guardian-voice-profile.txt","invalid")
        check(resolve(true).profile == GuardianVoiceProfile.DISTANT && !resolve(true).profileFromFile)
        println("PASS invalid test profile retains the configured profile")
        set(ext,"guardian-audio-source.txt","MIC")
        set(own,"guardian-audio-source.txt","VOICE_RECOGNITION")
        check(GuardianDiagnosticOptions.controlFile(own, ext, "guardian-audio-source.txt")!!.readText() == "VOICE_RECOGNITION")
        File(own,"guardian-audio-source.txt").delete()
        check(GuardianDiagnosticOptions.controlFile(own, ext, "guardian-audio-source.txt")!!.readText() == "MIC")
        check(GuardianDiagnosticOptions.controlFile(own, null, "guardian-multimic.txt") == null)
        println("PASS private route controls take precedence with legacy fallback and no external directory requirement")
    } finally { root.deleteRecursively() }
}
