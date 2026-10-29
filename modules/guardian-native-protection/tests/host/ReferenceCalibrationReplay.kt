package expo.modules.guardiannativeprotection

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Offline opt-in corpus runner. TSV: label, synchronous primary.f32, reference.f32.
 * Produces both calibrations, plus actual streaming VAD prefix observations.
 * No Qwen stub: this runner makes no claim about recognized words. */
internal object ReferenceCalibrationReplay {
    private fun read(path: String): FloatArray {
        val bytes = File(path).readBytes(); require(bytes.size % 4 == 0)
        val view = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        return FloatArray(view.remaining()).also { view.get(it) }
    }
    @JvmStatic fun main(args: Array<String>) {
        val root = File(args[1]).apply { mkdirs() }
        val assets = File(System.getProperty("guardian.test.assets"))
        for (line in File(args[0]).readLines().filter { it.isNotBlank() }) {
            val row = line.split('\t'); val x = read(row[1]); val y = read(row[2])
            require(x.size == y.size)
            val variants = listOf(false, true).map { GuardianReferenceNoiseEnhancer(
                GuardianReferenceNoiseEnhancer.Profile.LONG, robustCalibration=it) }
            val outputs = listOf(File(root,"${row[0]}-legacy.f32"), File(root,"${row[0]}-robust.f32")).map { it.outputStream() }
            GuardianFireRedStreaming.load { File(assets, it).readBytes() }.use { vad ->
                try {
                    for (start in x.indices step 800) {
                        val end = minOf(start + 800, x.size)
                        val p=x.copyOfRange(start,end); val r=y.copyOfRange(start,end)
                        val speech=vad.accept(p).speech
                        variants.forEachIndexed { i, enhancer ->
                            if(speech)enhancer.markCalibrationSpeech(end.toLong())
                            val pcm=enhancer.process(p,r)
                            val b=ByteBuffer.allocate(pcm.size*4).order(ByteOrder.LITTLE_ENDIAN)
                            pcm.forEach { b.putFloat(it) }; outputs[i].write(b.array())
                        }
                    }
                } finally { outputs.forEach { it.close() } }
            }
            variants.forEachIndexed { i,e -> println("${row[0]}\t${if(i==0) "legacy" else "robust"}\t${e.profileFrames}\t${e.acceptedProfileFrames}\t${e.rejectedProfileFrames}\t${e.degenerateReference}\t${e.observedSpeechDuringProfile}\t${e.outputSamples}") }
        }
    }
}
