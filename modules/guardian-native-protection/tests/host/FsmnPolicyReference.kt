package expo.modules.guardiannativeprotection

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Optional private PCM/probability reference table; the repository has no audio. */
object FsmnPolicyReference {
    private fun read(path: String): FloatArray {
        val raw = File(path).readBytes()
        require(raw.size % 4 == 0)
        val view = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        return FloatArray(view.remaining()).also { view.get(it) }
    }
    @JvmStatic fun main(args: Array<String>) {
        require(args.size == 1)
        var count = 0
        for (line in File(args[0]).readLines().drop(1)) {
            val fields = line.split('\t')
            val result = GuardianFsmnPolicy.evaluate(read(fields[1]), read(fields[2]))
            check(result.accepted == fields[3].toBoolean()) { "FSMN admission differs: ${fields[0]} $result" }
            check(result.speechFrames == fields[4].toInt()) { "FSMN frame decisions differ: ${fields[0]} $result" }
            check(result.detectedSegments == fields[5].toInt()) { "FSMN segment count differs: ${fields[0]} $result" }
            count++
        }
        println("PASS FSMN policy parity against $count upstream probability/PCM references")
    }
}
