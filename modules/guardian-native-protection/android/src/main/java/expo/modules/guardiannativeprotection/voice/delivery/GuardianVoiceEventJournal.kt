package expo.modules.guardiannativeprotection

import android.util.AtomicFile
import java.io.File
import org.json.JSONObject

/** Private, no-backup fallback only when SQLite rejects an event. No audio.
 * One atomic file per stable event ID; replay into SQLite is idempotent.
 * Failure of BOTH storage paths is reported, never acknowledged as durable. */
internal class GuardianVoiceEventJournal(private val directory: File) {
    @Synchronized fun save(event: GuardianVoiceEvent) {
        check(directory.isDirectory || directory.mkdirs()) { "Voice journal directory unavailable" }
        val target = file(event.eventId)
        check(target.exists() || names().size < 128) { "Voice journal full" }
        val data = JSONObject().apply {
            put("eventId", event.eventId); put("priority", event.priority.name)
            put("createdAt", event.createdAt); put("recordedAt", event.recordedAt)
            put("schemaVersion", event.schemaVersion)
            put("childId", event.childId); put("familyId", event.familyId)
            put("familyCode", event.familyCode); put("deviceId", event.deviceId)
            put("cause", event.cause.name); put("state", event.state.name)
            put("confidenceScore", event.confidenceScore)
            put("recognizedText", event.recognizedText); put("wakePhrase", event.wakePhrase)
            put("emergencyPhrase", event.emergencyPhrase); put("latitude", event.latitude)
            put("longitude", event.longitude); put("accuracy", event.accuracy)
        }.toString().toByteArray(Charsets.UTF_8)
        check(data.size <= 65_536) { "Voice journal event too large" }
        val atomic = AtomicFile(target)
        val stream = atomic.startWrite()
        try { stream.write(data); atomic.finishWrite(stream) }
        catch (error: Exception) { atomic.failWrite(stream); throw error }
    }

    @Synchronized fun readPending(onCorrupt: (String, Exception) -> Unit = { _, error -> throw error }): List<GuardianVoiceEvent> =
        names().mapNotNull { name -> try { readEvent(name) } catch (error: Exception) { onCorrupt(name, error); null } }

    private fun readEvent(name: String): GuardianVoiceEvent {
        val bytes = AtomicFile(File(directory, name)).openRead().use { input ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(4096)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                check(output.size() + n <= 65_536) { "Voice journal event too large" }
                output.write(buffer, 0, n)
            }
            output.toByteArray()
        }
        val j = JSONObject(String(bytes, Charsets.UTF_8))
        fun string(key: String): String? = if (j.isNull(key)) null else j.getString(key)
        fun double(key: String): Double? = if (j.isNull(key)) null else j.getDouble(key)
        return GuardianVoiceEvent(
            eventId=j.getString("eventId"), priority=GuardianEventPriority.valueOf(j.getString("priority")),
            createdAt=j.getLong("createdAt"), recordedAt=j.getLong("recordedAt"), schemaVersion=j.getInt("schemaVersion"),
            childId=j.getString("childId"), familyId=j.getString("familyId"),
            familyCode=j.getString("familyCode"), deviceId=j.getString("deviceId"),
            cause=GuardianVoiceCause.valueOf(j.getString("cause")), state=GuardianVoiceState.valueOf(j.getString("state")),
            confidenceScore=j.getInt("confidenceScore"), recognizedText=string("recognizedText"),
            wakePhrase=string("wakePhrase"), emergencyPhrase=string("emergencyPhrase"),
            latitude=double("latitude"), longitude=double("longitude"), accuracy=double("accuracy")?.toFloat(),
        ).also { check(file(it.eventId).name == name) { "Voice journal ID mismatch" } }
    }

    @Synchronized fun remove(id: String) { AtomicFile(file(id)).delete() }
    private fun file(id: String): File {
        require(id.matches(Regex("[A-Za-z0-9_-]{1,128}"))) { "Invalid voice event ID" }
        return File(directory, "$id.json")
    }
    private fun names(): List<String> {
        if (!directory.exists()) return emptyList()
        val files = checkNotNull(directory.listFiles()) { "Voice journal unreadable" }
        return files.map { it.name.removeSuffix(".bak") }.filter { it.endsWith(".json") }.distinct().sorted()
    }
}
