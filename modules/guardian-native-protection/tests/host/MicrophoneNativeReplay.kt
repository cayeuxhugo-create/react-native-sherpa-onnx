package expo.modules.guardiannativeprotection

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Explicitly opt-in private corpus. TSV: label, original.f32, alternate.f32.
 * Real native VAD; no ASR mock output is represented as a transcription result. */
internal object MicrophoneNativeReplay {
    private fun read(path: String): FloatArray {
        val view = ByteBuffer.wrap(File(path).readBytes()).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        return FloatArray(view.remaining()).also { view.get(it) }
    }
    @JvmStatic fun main(args: Array<String>) {
        require(args.size == 2)
        val assets = File(args[0])
        GuardianFireRedConfirmation.load { File(assets, it).readBytes() }.use { fire ->
            GuardianFsmnConfirmation.load { File(assets, it).readBytes() }.use { fsmn ->
                println("label\tchannel\tfire\tfsmn\tfirePeak\tfsmnFrames\tcontrastDb\tselected")
                for (line in File(args[1]).readLines().drop(1).filter { it.isNotBlank() }) {
                    val row = line.split('\t')
                    val checks = row.drop(1).mapIndexed { index, path ->
                        val pcm = read(path); val f = fire.confirm(pcm); val m = fsmn.confirm(pcm)
                        Triple(GuardianMicrophoneSelection.Candidate(index, pcm,
                            f.decision.accepted && m.decision.accepted, f.speechSupport,
                            if (index == 0) pcm.size else 0), f, m)
                    }
                    val chosen = GuardianMicrophoneSelection.select(checks.map { it.first })
                    for ((c, f, m) in checks) println(listOf(row[0], c.index,
                        f.decision.accepted, m.decision.accepted, f.decision.peakProbability,
                        m.decision.speechFrames, chosen?.qualities?.get(c.index)?.contrastDb,
                        chosen?.candidate?.index).joinToString("\t"))
                }
            }
        }
    }
}
