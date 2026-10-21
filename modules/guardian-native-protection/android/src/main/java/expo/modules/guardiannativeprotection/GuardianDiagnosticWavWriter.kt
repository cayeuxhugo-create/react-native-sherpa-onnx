package expo.modules.guardiannativeprotection

import java.io.BufferedWriter
import java.io.File
import java.io.RandomAccessFile
import kotlin.math.roundToInt

/** Test-only PCM16 exports. Owned by the speech-input worker, never changes ASR. */
internal class GuardianDiagnosticWavWriter(
    private val directory: File,
    profile: String,
    sessionId: String,
    private val diagnostics: GuardianVoiceDiagnostics?,
    private val maxSamples: Long = 16_000L * 180L,
) {
    private data class Stage(val file: File, val stream: RandomAccessFile, var samples: Long = 0, var limited: Boolean = false)
    private val stages = linkedMapOf<String, Stage>()
    private val prefix = "guardian-voice-" + System.currentTimeMillis() + "-" + profile + "-" + sessionId.take(8)
    private var closed = false
    private var writes = 0
    private val index: BufferedWriter

    init {
        require(directory.isDirectory || directory.mkdirs()) { "Impossible de créer le dossier WAV" }
        index = File(directory, "$prefix-index.tsv").bufferedWriter()
        index.write("stage\tsequence\tcapturedAtNs\tfileSampleStart\tsampleCount\n")
        index.flush()
        diagnostics?.event("WAV_READY", "directory" to directory.absolutePath,
            "prefix" to prefix, "sampleRateHz" to 16000, "channels" to 1,
            "format" to "PCM16", "maxSecondsPerStage" to maxSamples / 16000)
    }

    fun write(stageName: String, pcm: FloatArray, sequence: Long, capturedAtNs: Long) {
        if (closed || pcm.isEmpty()) return
        try {
            require(stageName in setOf("raw", "pregain", "truth", "asr", "vad"))
            if (pcm.any { !it.isFinite() }) {
                diagnostics?.event("WAV_INVALID_PCM", "stage" to stageName, "sequence" to sequence)
                return
            }
            val stage = stages.getOrPut(stageName) {
                val file = File(directory, "$prefix-$stageName.wav")
                val stream = RandomAccessFile(file, "rw")
                try {
                    stream.setLength(0)
                    header(stream, 0)
                    Stage(file, stream)
                } catch (error: Exception) {
                    try { stream.close() } catch (_: Exception) {}
                    throw error
                }
            }
            val count = minOf(pcm.size.toLong(), maxSamples - stage.samples).toInt()
            if (count > 0) {
                val bytes = ByteArray(count * 2)
                for (i in 0 until count) {
                    val sample = (pcm[i].coerceIn(-1f, 1f) * 32768f).roundToInt().coerceIn(-32768, 32767)
                    bytes[2 * i] = (sample and 255).toByte()
                    bytes[2 * i + 1] = ((sample ushr 8) and 255).toByte()
                }
                stage.stream.write(bytes)
                index.write("$stageName\t$sequence\t$capturedAtNs\t" + stage.samples + "\t$count\n")
                stage.samples += count
                if (++writes % 30 == 0) index.flush()
            }
            if (stage.samples >= maxSamples && !stage.limited) {
                stage.limited = true
                diagnostics?.event("WAV_LIMIT", "stage" to stageName, "maxSeconds" to maxSamples / 16000)
            }
        } catch (error: Exception) {
            diagnostics?.event("WAV_ERROR", "error" to error.javaClass.simpleName)
            close()
        }
    }

    fun close() {
        if (closed) return
        closed = true
        for ((name, stage) in stages) {
            try {
                header(stage.stream, stage.samples * 2)
                stage.stream.fd.sync()
                diagnostics?.event("WAV_SAVED", "stage" to name, "file" to stage.file.name,
                    "samples" to stage.samples, "durationMs" to stage.samples * 1000 / 16000)
            } catch (error: Exception) {
                diagnostics?.event("WAV_FINALIZE_ERROR", "stage" to name, "error" to error.javaClass.simpleName)
            } finally {
                try { stage.stream.close() } catch (_: Exception) {}
            }
        }
        try { index.close() } catch (_: Exception) {}
    }

    private fun header(stream: RandomAccessFile, bytes: Long) {
        stream.seek(0)
        stream.writeBytes("RIFF")
        le32(stream, bytes + 36)
        stream.writeBytes("WAVEfmt ")
        le32(stream, 16)
        le16(stream, 1); le16(stream, 1)
        le32(stream, 16000); le32(stream, 32000)
        le16(stream, 2); le16(stream, 16)
        stream.writeBytes("data")
        le32(stream, bytes)
        stream.seek(44 + bytes)
    }

    private fun le16(s: RandomAccessFile, n: Int) {
        s.write(n and 255); s.write((n ushr 8) and 255)
    }
    private fun le32(s: RandomAccessFile, n: Long) {
        for (shift in 0..24 step 8) s.write(((n ushr shift) and 255).toInt())
    }
}

