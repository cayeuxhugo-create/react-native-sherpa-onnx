package expo.modules.guardiannativeprotection

fun diagnosticsTests() {
    val disabled = mutableListOf<String>()
    GuardianVoiceDiagnostics(output = { disabled += it }).event("HIDDEN", "text" to "private")
    check(disabled.isEmpty())
    println("PASS diagnostics disabled by default")
    val records = mutableListOf<String>()
    val diag = GuardianVoiceDiagnostics(true, "DISTANT", { records += it }, "test-session")
    diag.event("QUOTE_TEST", "text" to "une \"voix\"\navec\tdu bruit et \\ un emoji 😀", "infinite" to Float.NaN,
        "value" to 0.25, "profile" to "INVALID_OVERRIDE", "long" to "x".repeat(600))
    check(records.size == 1 && records[0].contains("DISTANT") && !records[0].contains("INVALID_OVERRIDE"))
    println("TRACE_VALIDATION " + records[0])
    println("PASS diagnostic JSON bounded and reserved fields preserved")
    GuardianVoiceDiagnostics(true, "NOISY", { throw IllegalStateException("sink failed") }).event("SAFE")
    println("PASS diagnostic sink errors do not reach the engine")
    check(GuardianVoiceDiagnostics.rms(floatArrayOf(0.3f, -0.3f)) == 0.3f)
    check(GuardianVoiceDiagnostics.dbfs(0f) == null)
    check(kotlin.math.abs(GuardianVoiceDiagnostics.dbfs(0.1f)!! + 20.0) < 0.001)
    println("PASS PCM summary and dBFS silence handling")
    println("4 diagnostic tests passed")
}

