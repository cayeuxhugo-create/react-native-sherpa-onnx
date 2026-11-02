package expo.modules.guardiannativeprotection

import java.security.MessageDigest

/** Official streaming postprocessor, preserving state across 50ms capture blocks.
 * start/end events, not an instantaneous probability, drive the PCM segmenter. */
internal class GuardianFireRedStreamPolicy {
    private val recent = ArrayDeque<Double>()
    private var sum = 0.0
    private var state = 0
    private var speechFrames = 0
    private var silenceFrames = 0
    private var hitMax = false
    var active = false; private set
    fun accept(probabilities: FloatArray): Boolean {
        require(probabilities.all { it.isFinite() && it in 0f..1f })
        for (p in probabilities) {
            recent.addLast(p.toDouble()); sum += p
            if (recent.size > 5) sum -= recent.removeFirst()
            val speech = sum / recent.size >= 0.5
            if (hitMax) { active = true; hitMax = false }
            when (state) {
                0 -> if (speech) { state = 1; speechFrames++ } else { silenceFrames++; speechFrames = 0 }
                1 -> if (speech) {
                    speechFrames++
                    if (speechFrames >= 8) { state = 2; active = true; silenceFrames = 0 }
                } else { state = 0; silenceFrames = 1; speechFrames = 0 }
                2, 3 -> {
                    speechFrames++
                    if (speech) {
                        state = 2; silenceFrames = 0
                        if (speechFrames >= 2000) { hitMax = true; speechFrames = 0; active = false }
                    } else {
                        state = 3; silenceFrames++
                        if (silenceFrames >= 20) { state = 0; active = false; speechFrames = 0 }
                    }
                }
            }
        }
        return active
    }
    fun reset() { recent.clear(); sum = 0.0; state = 0; speechFrames = 0; silenceFrames = 0; hitMax = false; active = false }
}

internal class GuardianFireRedStreaming private constructor(private var handle: Long) : AutoCloseable {
    private val policy = GuardianFireRedStreamPolicy()
    data class Result(val speech: Boolean, val peak: Float, val frames: Int, val elapsedMs: Long)
    fun accept(original: FloatArray): Result {
        check(handle != 0L)
        require(original.size in 1..800 && original.all { it.isFinite() }) { "FireRed capture requires 1..800 samples at 16kHz" }
        val start = System.nanoTime()
        val probabilities = GuardianFireRedStreamingNative.accept(handle, GuardianNeuralInputGain.forVad(original, 64f))
        return Result(policy.accept(probabilities), probabilities.maxOrNull() ?: 0f,
            probabilities.size, (System.nanoTime()-start)/1_000_000)
    }
    fun reset() { check(handle != 0L); GuardianFireRedStreamingNative.reset(handle); policy.reset() }
    override fun close() { if (handle != 0L) { GuardianFireRedStreamingNative.destroy(handle); handle = 0L } }
    companion object {
        const val MODEL_ID = "FIRERED_STREAM_NCNN_FP16_WEIGHTS_CPU_FP32"
        fun load(readAsset: (String) -> ByteArray): GuardianFireRedStreaming {
            val expected = linkedMapOf(
                "firered_vad_packed_cache_stream.ncnn.param" to "682987e97772317f58424a578f09521fa6c4476108c66e9f48fa38be6a97f1a4",
                "firered_vad_packed_cache_stream.ncnn.bin" to "e243b3bdc8faceb1adc2f3b7884d1c2ebef3d36f85f43be9c342d38bacc57785",
                "cmvn.bin" to "91a5337e161117a919694f3de94f66ec0e860e9d8539da6b398e355614ed100b")
            val b = expected.map { (name, hash) -> readAsset("firered/$name").also {
                check(MessageDigest.getInstance("SHA-256").digest(it).joinToString("") { v -> "%02x".format(v.toInt() and 255) } == hash) { "FireRed streaming SHA-256 mismatch: $name" }
            } }
            val h = GuardianFireRedStreamingNative.create(b[0],b[1],b[2]);check(h != 0L)
            return GuardianFireRedStreaming(h)
        }
    }
}
internal object GuardianFireRedStreamingNative {
    init { System.loadLibrary("guardian_firered_vad") }
    external fun create(param: ByteArray, model: ByteArray, cmvn: ByteArray): Long
    external fun accept(handle: Long, pcm: FloatArray): FloatArray
    external fun reset(handle: Long)
    external fun destroy(handle: Long)
}
