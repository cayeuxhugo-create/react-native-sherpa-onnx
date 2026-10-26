package expo.modules.guardiannativeprotection

/** Runs against the actual Android capture classes; the tracker itself has no Android calls. */
internal object CaptureRouteGenerationTest {
    @JvmStatic
    fun main(args: Array<String>) {
        repeatedRouteChangesRemainVisible()
        duplicateCallbacksDoNotChangeGeneration()
        recorderBaselineKeepsGenerationMonotonic()
        println("PASS 3 capture route generation tests")
    }

    private fun repeatedRouteChangesRemainVisible() {
        val routes = GuardianAudioEngine.CaptureRouteTracker<String>()
        routes.baseline("built-in mono")
        check(routes.update("USB mono") == "built-in mono")
        val firstChange = routes.generation
        // Capture identity keeps advancing after the first change has invalidated spatial trust.
        check(routes.update("Bluetooth mono") == "USB mono")
        check(routes.generation > firstChange)
        check(routes.update("built-in mono") == "Bluetooth mono")
        check(routes.generation == firstChange + 2L)
    }

    private fun duplicateCallbacksDoNotChangeGeneration() {
        val routes = GuardianAudioEngine.CaptureRouteTracker<List<String>>()
        routes.baseline(listOf("device=1", "NS=off"))
        check(routes.update(listOf("device=1", "NS=on")) != null)
        val changed = routes.generation
        // Recording-configuration and routing callbacks can report equal fresh objects.
        check(routes.update(listOf("device=1", "NS=on")) == null)
        check(routes.generation == changed)
        // A preprocessing change also changes the stream that Wiener calibrated.
        check(routes.update(listOf("device=1", "NS=off")) != null)
        check(routes.generation > changed)
    }

    private fun recorderBaselineKeepsGenerationMonotonic() {
        val routes = GuardianAudioEngine.CaptureRouteTracker<String>()
        check(routes.update("built-in") == null)
        check(routes.update("USB") == "built-in")
        val beforeRestart = routes.generation
        routes.baseline(null)
        routes.baseline("built-in")
        check(routes.generation == beforeRestart)
        check(routes.update("USB") == "built-in")
        check(routes.generation > beforeRestart)
    }
}
