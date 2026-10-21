package expo.modules.guardiannativeprotection

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Real JNI/native inference test, not a native stub. Manifest TSV is private. */
fun main(args: Array<String>) {
    val assets = File(args[0])
    val readAsset: (String) -> ByteArray = { File(assets, it).readBytes() }
    check(runCatching { GuardianFireRedConfirmation.load { ByteArray(8) } }.isFailure)
    val engine = GuardianFireRedConfirmation.load(readAsset)
    try {
        check(!engine.confirm(FloatArray(16000)).decision.accepted)
        check(runCatching { engine.confirm(FloatArray(200)) }.isFailure)
        check(runCatching { engine.confirm(FloatArray(128001)) }.isFailure)
        check(runCatching { engine.confirm(floatArrayOf(Float.NaN)) }.isFailure)
        val results = File(args[1]).readLines().map { line ->
            val fields = line.split('\t')
            val bytes = File(fields[2]).readBytes()
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
            val pcm = FloatArray(buffer.remaining()).also { buffer.get(it) }
            val before = pcm.copyOf()
            val r = engine.confirm(pcm)
            check(pcm.contentEquals(before)) { "Original Qwen PCM mutated" }
            val repeated = engine.confirm(pcm)
            check(r.decision == repeated.decision) { "State leaked between windows" }
            "${fields[0]}\t${fields[1]}\t${r.decision.accepted}\t${r.decision.peakProbability}\t${r.decision.longestPositiveFrames}\t${r.elapsedMs}"
        }
        File(args[2]).writeText(results.joinToString("\n")+"\n")
    } finally { engine.close(); engine.close() }
    check(runCatching { engine.confirm(FloatArray(16000)) }.isFailure)
    println("Real FireRed JNI checks passed; original PCM unchanged; output written")
}
