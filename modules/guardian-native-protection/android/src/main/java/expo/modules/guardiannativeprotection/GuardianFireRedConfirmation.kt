package expo.modules.guardiannativeprotection

import java.security.MessageDigest

/** CPU non-stream FireRed, owned and closed by the ordered speech input worker.
 * Original Qwen PCM is never modified. Native NCNN is statically linked into a
 * distinct library and does not replace Sherpa/Qwen's ONNX Runtime. */
internal class GuardianFireRedConfirmation private constructor(private var handle: Long) : AutoCloseable {
    data class Result(val decision: GuardianFireRedPolicy.Decision, val elapsedMs: Long)

    fun confirm(original: FloatArray): Result {
        check(handle != 0L) { "FireRed closed" }
        require(original.size in 400..128000 && original.all { it.isFinite() })
        val started = System.nanoTime()
        val copy = GuardianNeuralInputGain.forVad(original, GuardianFireRedPolicy.MAX_INPUT_GAIN)
        val probabilities = GuardianFireRedNative.infer(handle, copy)
        check(probabilities.size == 1 + (copy.size - 400) / 160) { "FireRed output length invalid" }
        return Result(GuardianFireRedPolicy.evaluate(probabilities), (System.nanoTime() - started) / 1_000_000)
    }

    override fun close() {
        if (handle == 0L) return
        GuardianFireRedNative.destroy(handle)
        handle = 0L
    }

    companion object {
        const val MODEL_ID = "FIRERED_NONSTREAM_NCNN_FP16_WEIGHTS_CPU_FP32"
        private val expected = linkedMapOf(
            "firered_vad_non_stream.ncnn.param" to "9e361f9af4d9f94c879c75a91ba594fe612100dddd6d6fa657a748ff70e34704",
            "firered_vad_non_stream.ncnn.bin" to "961c27ff99ec8474048e3ba682d4af07315e6ac4638ad1de20675240c36b43ff",
            "cmvn.bin" to "91a5337e161117a919694f3de94f66ec0e860e9d8539da6b398e355614ed100b",
        )
        fun load(readAsset: (String) -> ByteArray): GuardianFireRedConfirmation {
            val assets = expected.map { (name, hash) ->
                readAsset("firered/$name").also { bytes ->
                    val actual = MessageDigest.getInstance("SHA-256").digest(bytes)
                        .joinToString("") { "%02x".format(it.toInt() and 255) }
                    check(actual == hash) { "FireRed asset SHA-256 mismatch: $name" }
                }
            }
            val handle = GuardianFireRedNative.create(assets[0], assets[1], assets[2])
            check(handle != 0L) { "FireRed native initialization failed" }
            return GuardianFireRedConfirmation(handle)
        }
    }
}

internal object GuardianFireRedNative {
    init { System.loadLibrary("guardian_firered_vad") }
    external fun create(param: ByteArray, model: ByteArray, cmvn: ByteArray): Long
    external fun infer(handle: Long, pcm: FloatArray): FloatArray
    external fun destroy(handle: Long)
}
