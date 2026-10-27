package expo.modules.guardiannativeprotection

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.system.exitProcess

/** Opt-in parity against private upstream results, using the real JNI wrapper.
 * TSV columns: id, input.f32, accepted, completedSegments, inputPeakBitsLE,
 * gainBitsLE, conditionedSha256. No corpus paths or recognition text are printed.
 * Reverse replay also checks that independent windows do not share state.
 */
object FsmnIntegration {
    private data class Case(val id: String, val pcm: FloatArray, val accepted: Boolean,
        val segments: Int, val peakBits: String, val gainBits: String, val conditionedHash: String)

    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size == 2) { "FsmnIntegration manifest.tsv artifact-directory" }
        val artifacts = File(args[1])
        require(artifacts.isDirectory || artifacts.mkdirs())
        try {
            val cases = File(args[0]).readLines().filter { it.isNotBlank() && !it.startsWith('#') }.map { line ->
                val fields = line.split('\t')
                require(fields.size == 7) { "FSMN manifest needs seven tab-separated fields" }
                val bytes = File(fields[1]).readBytes()
                require(bytes.size % 4 == 0)
                val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
                Case(fields[0], FloatArray(buffer.remaining()).also { buffer.get(it) }, fields[2].toBooleanStrict(),
                    fields[3].toInt(), fields[4], fields[5], fields[6])
            }
            require(cases.isNotEmpty())
            val assets = File(checkNotNull(System.getProperty("guardian.test.assets")))
            val model = GuardianFsmnConfirmation.load { name -> File(assets, name).readBytes() }
            val rows = ArrayList<String>()
            rows.add("pass\tindex\tid\taccepted\tcompletedSegments\tspeechFrames\tposteriorFrames\tconfirmationMs\tinferenceMs\tcpuMs")
            var confirmationMs = 0L
            var cpuMs = 0L
            try {
                for ((pass, ordered) in listOf(cases, cases.reversed()).withIndex()) {
                    for ((index, case) in ordered.withIndex()) {
                        val before = case.pcm.copyOf()
                        val conditioned = GuardianFsmnConfirmation.condition(case.pcm)
                        check(bits(conditioned.inputPeak) == case.peakBits && bits(conditioned.inputGain) == case.gainBits) {
                            "FSMN conditioning differs from upstream at case $index"
                        }
                        val bytes = ByteBuffer.allocate(conditioned.pcm.size * 4).order(ByteOrder.LITTLE_ENDIAN)
                        bytes.asFloatBuffer().put(conditioned.pcm)
                        check(sha256(bytes.array()) == case.conditionedHash) { "FSMN input PCM differs from upstream at case $index" }
                        val result = model.confirm(case.pcm)
                        check(case.pcm.contentEquals(before)) { "FSMN modified original ASR PCM" }
                        check(bits(result.inputPeak) == case.peakBits && bits(result.inputGain) == case.gainBits)
                        check(result.decision.accepted == case.accepted && result.decision.detectedSegments == case.segments) {
                            "FSMN admission or completed segment count differs from upstream at case $index"
                        }
                        check(result.posteriorFrames > 0 && result.silencePosteriorMin in 0f..1f &&
                            result.silencePosteriorMax in result.silencePosteriorMin..1f && result.silencePosteriorMean.isFinite())
                        check(result.confirmationMs >= 0 && result.inferenceMs >= 0 && result.confirmationCpuMs >= 0)
                        confirmationMs += result.confirmationMs
                        cpuMs += result.confirmationCpuMs
                        rows.add("$pass\t$index\t${case.id}\t${result.decision.accepted}\t${result.decision.detectedSegments}\t" +
                            "${result.decision.speechFrames}\t${result.posteriorFrames}\t${result.confirmationMs}\t${result.inferenceMs}\t${result.confirmationCpuMs}")
                    }
                }
            } finally {
                model.close()
                File(artifacts, "fsmn-parity-private.tsv").writeText(rows.joinToString("\n") + "\n")
            }
            check(runCatching { model.confirm(cases.first().pcm) }.isFailure) { "Closed FSMN model remained usable" }
            File(artifacts, "fsmn-summary-private.json").writeText(
                "{\"cases\":${cases.size},\"passes\":2,\"calls\":${cases.size * 2}," +
                    "\"conditioningByteExact\":true,\"admissionsMatch\":true,\"completedSegmentsMatch\":true," +
                    "\"originalPcmUnchanged\":true,\"confirmationMs\":$confirmationMs,\"cpuMs\":$cpuMs}\n")
            println("PASS FSMN real-JNI parity: ${cases.size} upstream cases, forward/reverse, exact conditioning, unchanged original PCM")
        } catch (error: Throwable) {
            File(artifacts, "failure-private.txt").writeText(error.stackTraceToString())
            System.err.println("FAIL FSMN JNI parity (${error.javaClass.simpleName}); details are in the private artifact directory")
            exitProcess(1)
        }
    }

    private fun bits(value: Float): String = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putFloat(value)
        .array().joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 255) }
}
