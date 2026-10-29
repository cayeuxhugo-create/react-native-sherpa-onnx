package expo.modules.guardiannativeprotection

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Private TSV label/PCM path. Evaluate real native gates for the exact ASR variants. */
internal object VariantAdmissionReplay {
    @JvmStatic fun main(args: Array<String>) {
        val assets=File(System.getProperty("guardian.test.assets"))
        GuardianFireRedConfirmation.load { File(assets,it).readBytes() }.use { fire ->
            GuardianFsmnConfirmation.load { File(assets,it).readBytes() }.use { fsmn ->
                val out=File(args[1]).apply { mkdirs() }
                File(out,"admission.tsv").bufferedWriter().use { writer ->
                    writer.write("id\tfire\tfsmn\tpeak\tpositiveFrames\tspeechFrames\tpcmSha256\n")
                    for(line in File(args[0]).readLines().filter { it.isNotBlank() }) {
                        val row=line.split('\t'); val bytes=File(row[1]).readBytes();require(bytes.size%4==0)
                        val buffer=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
                        val pcm=FloatArray(buffer.remaining()).also { buffer.get(it) }
                        val f=fire.confirm(pcm);val m=fsmn.confirm(pcm)
                        writer.write(listOf(row[0],f.decision.accepted,m.decision.accepted,
                            f.decision.peakProbability,f.decision.longestPositiveFrames,
                            m.decision.speechFrames,GuardianPcmIdentity.sha256(pcm)).joinToString("\t")+"\n")
                    }
                }
            }
        }
    }
}
