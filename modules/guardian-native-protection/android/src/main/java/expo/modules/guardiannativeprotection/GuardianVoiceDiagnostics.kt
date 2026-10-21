package expo.modules.guardiannativeprotection

import android.os.SystemClock
import android.util.Log
import java.util.UUID

/** Opt-in, bounded structured records. No PCM, identities, location or credentials.
 * Transcriptions ARE included when enabled; use a controlled test session. */
class GuardianVoiceDiagnostics(
    val enabled: Boolean = false,
    private val profile: String = "UNKNOWN",
    private val output: (String) -> Unit = { Log.i("GuardianVoiceDiag", it) },
    val sessionId: String = UUID.randomUUID().toString(),
) {
    fun event(name: String, vararg fields: Pair<String, Any?>) {
        if (!enabled) return
        try {
            val values = linkedMapOf<String, Any?>(
                "schema" to 1, "session" to sessionId, "profile" to profile,
                "utcMs" to System.currentTimeMillis(),
                "elapsedMs" to SystemClock.elapsedRealtime(), "event" to name,
            )
            for ((key, value) in fields) if (key !in values) values[key] = value
            output(values.entries.joinToString(prefix = "{", postfix = "}") {
                quote(it.key) + ":" + encode(it.value)
            })
        } catch (_: Exception) {
            // Collection is best effort and cannot replace engine behavior.
        }
    }

    private fun encode(value: Any?): String = when (value) {
        null -> "null"
        is Boolean -> value.toString()
        is Number -> if (value.toDouble().isFinite()) value.toString() else "null"
        else -> quote(value.toString().take(512))
    }

    private fun quote(value: String): String = buildString {
        append('"')
        for (c in value) {
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (c.code < 32 || c.isSurrogate()) {
                    append("\\u"); append(c.code.toString(16).padStart(4, '0'))
                } else append(c)
            }
        }
        append('"')
    }

    companion object {
        fun dbfs(rms: Float): Double? = if (rms > 0f && rms.isFinite()) {
            20.0 * kotlin.math.log10(rms.toDouble())
        } else null

        fun rms(pcm: FloatArray): Float {
            if (pcm.isEmpty()) return 0f
            var sum = 0.0
            for (sample in pcm) sum += sample.toDouble() * sample.toDouble()
            return kotlin.math.sqrt(sum / pcm.size).toFloat()
        }
    }
}

