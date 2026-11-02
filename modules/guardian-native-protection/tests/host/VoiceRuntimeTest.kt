package expo.modules.guardiannativeprotection
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
internal object VoiceRuntimeTest {
    @JvmStatic fun main(args: Array<String>) {
        val persisted = CountDownLatch(1)
        val snapshots = java.util.Collections.synchronizedList(mutableListOf<GuardianVoiceEngine.Status>())
        val attempts = java.util.Collections.synchronizedList(mutableListOf<GuardianVoiceEvent>())
        val config = GuardianNativeConfig("child", "name", "family", "code", "device")
        val engine = GuardianVoiceEngine(android.content.Context(), config, GuardianVoiceEngine.Settings(),
            object: GuardianVoiceEngine.Listener {
                override fun onVoiceEvent(event: GuardianVoiceEvent) {
                    attempts += event
                    if (attempts.size == 1) error("SQLite failure")
                    persisted.countDown()
                }
                override fun onVoiceStatusChanged(status: GuardianVoiceEngine.Status) { snapshots += status }
            })
        try {
            check(!engine.getStatus().recognizerAvailable)
            engine.start()
            engine.reportRecognizerFailure("injected failure")
            check(!engine.getStatus().recognizerAvailable)
            engine.reportAudioCapture()
            engine.emitManualAlert()
            check(persisted.await(8, TimeUnit.SECONDS))
            check(attempts.size == 2 && attempts[0].eventId == attempts[1].eventId)
            for (i in 0..100) { if (engine.getStatus().pendingDeliveryCount == 0) break; Thread.sleep(10) }
            check(engine.getStatus().pendingDeliveryCount == 0 && engine.getStatus().deliveryError == null)
            check(snapshots.any { it.pendingDeliveryCount == 1 && it.deliveryError != null })
            check(engine.getStatus().lastAudioAt != null)
            engine.reportRecognizerRecovery()
            // Recovery is posted to the serial owner; wait for the resulting status.
            for (i in 0..100) { if (engine.getStatus().recognizerAvailable) break; Thread.sleep(10) }
            check(engine.getStatus().recognizerAvailable)
            println("PASS real VoiceEngine retains and retries exact event ID; health and capture are independent")
        } finally { engine.destroy() }
        val dir = kotlin.io.path.createTempDirectory("guardian-journal").toFile()
        try {
            val event = attempts[0]
            GuardianVoiceEventJournal(dir).save(event)
            val restarted = GuardianVoiceEventJournal(dir)
            check(restarted.readPending() == listOf(event))
            restarted.save(event); check(restarted.readPending().size == 1)
            File(dir, "corrupt.json").writeText("invalid")
            var corrupt = 0
            check(restarted.readPending { _, _ -> corrupt++ } == listOf(event)); check(corrupt == 1)
            check(File(dir, "corrupt.json").exists())
            File(dir, "corrupt.json").delete()
            restarted.remove(event.eventId); check(restarted.readPending().isEmpty())
            try { restarted.remove("../escape"); error("unsafe ID accepted") } catch (_: IllegalArgumentException) { }
            println("PASS journal round-trip, process-like recreation, idempotency, acknowledgement and path validation")
        } finally { dir.deleteRecursively() }
    }
}
