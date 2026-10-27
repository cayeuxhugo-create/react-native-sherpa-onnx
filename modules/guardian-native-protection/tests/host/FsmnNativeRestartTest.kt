package expo.modules.guardiannativeprotection

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicReference

/** Real JNI posterior/restart regression. Private seven-column upstream manifest.
 * Old OpenMP libraries are allowed only for explicit posterior-reference export;
 * restart mode requires the compile-checked serial backend handshake.
 */
object FsmnNativeRestartTest {
    private data class Case(val id: String, val pcm: FloatArray, val accepted: Boolean, val segments: Int,
                            val peakBits: String, val gainBits: String, val conditionedSha: String)
    private fun bytes(values: FloatArray): ByteArray = ByteBuffer.allocate(values.size * 4)
        .order(ByteOrder.LITTLE_ENDIAN).also { it.asFloatBuffer().put(values) }.array()
    private fun sha(values: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(values)
        .joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun bits(value: Float): String = bytes(floatArrayOf(value)).joinToString("") { "%02x".format(it.toInt() and 255) }

    @JvmStatic fun main(args: Array<String>) {
        require(args.size in 4..5) { "assets manifest.tsv output-directory export|restart [--allow-legacy]" }
        val assets = File(args[0]); val output = File(args[2]); check(output.isDirectory || output.mkdirs())
        val legacy = args.size == 5 && args[4] == "--allow-legacy"
        require(args[3] in setOf("export", "restart") && (!legacy || args[3] == "export"))
        val cases = File(args[1]).readLines().filter { it.isNotBlank() && !it.startsWith('#') }.map { line ->
            val fields = line.split('\t'); require(fields.size == 7)
            val raw = File(fields[1]).readBytes(); require(raw.size % 4 == 0)
            val buffer = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
            Case(fields[0], FloatArray(buffer.remaining()).also { buffer.get(it) }, fields[2].toBooleanStrict(),
                fields[3].toInt(), fields[4], fields[5], fields[6])
        }
        require(cases.isNotEmpty())
        if (!legacy) check(GuardianFsmnNative.executionBackend() == "NCNN_SERIAL_CPU")
        val param = File(assets, "fsmn/fsmn_vad.ncnn.param").readBytes()
        val weights = File(assets, "fsmn/fsmn_vad.ncnn.bin").readBytes()
        val cmvn = File(assets, "fsmn/cmvn-f64.bin").readBytes()
        var calls = 0
        fun infer(handle: Long, case: Case): FloatArray {
            val before = case.pcm.copyOf()
            val conditioned = GuardianFsmnConfirmation.condition(case.pcm)
            check(bits(conditioned.inputPeak) == case.peakBits && bits(conditioned.inputGain) == case.gainBits)
            check(sha(bytes(conditioned.pcm)) == case.conditionedSha)
            val conditionedBefore = conditioned.pcm.copyOf()
            val posterior = GuardianFsmnNative.infer(handle, conditioned.pcm)
            calls++
            check(case.pcm.contentEquals(before) && conditioned.pcm.contentEquals(conditionedBefore))
            check(posterior.size == 1 + (case.pcm.size - 400) / 160)
            val decision = GuardianFsmnPolicy.evaluate(posterior, conditioned.pcm)
            check(decision.accepted == case.accepted && decision.detectedSegments == case.segments) { "Upstream decision mismatch: ${case.id}" }
            return posterior
        }
        if (args[3] == "export") {
            val handle = GuardianFsmnNative.create(param, weights, cmvn); check(handle != 0L)
            try {
                File(output, "posteriors-private.tsv").bufferedWriter().use { writer ->
                    writer.write("index\tid\tframes\tsha256\n")
                    for ((index, case) in cases.withIndex()) {
                        val posterior = infer(handle, case)
                        val data = bytes(posterior)
                        File(output, "$index.f32").writeBytes(data)
                        writer.write("$index\t${case.id}\t${posterior.size}\t${sha(data)}\n")
                    }
                }
            } finally { GuardianFsmnNative.destroy(handle) }
        } else {
            val references = HashMap<String, FloatArray>()
            repeat(20) { session ->
                // Match production ownership: construct before the ordered input
                // worker, then infer and destroy on a newly created worker.
                val handle = GuardianFsmnNative.create(param, weights, cmvn); check(handle != 0L)
                val failure = AtomicReference<Throwable?>()
                val worker = Thread({
                    try {
                        for (offset in 0 until minOf(cases.size, 3)) {
                            val case = cases[(session * 3 + offset) % cases.size]
                            val posterior = infer(handle, case)
                            val prior = references[case.id]
                            if (prior == null) references[case.id] = posterior else check(prior.contentEquals(posterior))
                            check(infer(handle, case).contentEquals(posterior)) { "State leaked between calls" }
                        }
                    } catch (error: Throwable) { failure.set(error) }
                    finally { GuardianFsmnNative.destroy(handle) }
                }, "Guardian-Speech-Restart-$session")
                worker.isDaemon = true
                worker.start(); worker.join(30_000); check(!worker.isAlive) { "FSMN worker did not terminate" }
                failure.get()?.let { throw it }
            }
        }
        File(output, "summary.json").writeText("{\"mode\":\"${args[3]}\",\"legacyReferenceOnly\":$legacy," +
            "\"cases\":${cases.size},\"sessions\":${if (args[3] == "restart") 20 else 1},\"calls\":$calls," +
            "\"upstreamDecisionsMatch\":true,\"sourceUnchanged\":true}\n")
        println("PASS native FSMN ${args[3]}: ${cases.size} cases, $calls calls")
    }
}
