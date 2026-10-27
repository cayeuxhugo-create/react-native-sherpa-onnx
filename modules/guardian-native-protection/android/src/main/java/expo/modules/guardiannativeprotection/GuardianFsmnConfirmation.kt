package expo.modules.guardiannativeprotection

import android.os.SystemClock
import java.security.MessageDigest

/** Independent, original-PCM acoustic confirmation. The target conditioning is
 * model input only: it never replaces or changes the segment sent to Qwen.
 * A session owns one handle on its ordered input worker, with fresh inference
 * state per completed window. Initialization and inference errors are fatal to
 * this gate, never permission to fall back to FireRed alone.
 * An observed false admission on another microphone remains a counterexample:
 * agreement on this original primary stream does not guarantee silence rejection
 * for every microphone, route, speaker or noise condition.
 */
internal class GuardianFsmnConfirmation private constructor(private var handle: Long) : AutoCloseable {
    data class ConditionedInput(val pcm: FloatArray, val inputGain: Float,
        val inputPeak: Float, val conditionedPeak: Float)
    data class Result(val decision: GuardianFsmnPolicy.Decision,
        val inputGain: Float, val inputPeak: Float, val conditionedPeak: Float,
        val posteriorFrames: Int, val silencePosteriorMin: Float,
        val silencePosteriorMax: Float, val silencePosteriorMean: Double,
        val confirmationMs: Long, val inferenceMs: Long, val confirmationCpuMs: Long)

    fun confirm(original: FloatArray): Result {
        check(handle != 0L) { "FSMN closed" }
        val started = System.nanoTime()
        val cpuStarted = SystemClock.currentThreadTimeMillis()
        val input = condition(original)
        val inferenceStarted = System.nanoTime()
        val p0 = GuardianFsmnNative.infer(handle, input.pcm)
        val inferenceMs = (System.nanoTime() - inferenceStarted) / 1_000_000L
        check(p0.size == 1 + (input.pcm.size - FRAME_LENGTH) / FRAME_SHIFT) { "FSMN output length invalid" }
        check(p0.all { it.isFinite() && it in 0f..1f }) { "FSMN posterior invalid" }
        val decision = GuardianFsmnPolicy.evaluate(p0, input.pcm)
        return Result(decision, input.inputGain, input.inputPeak, input.conditionedPeak, p0.size,
            p0.minOrNull()!!, p0.maxOrNull()!!, p0.sumOf { it.toDouble() } / p0.size,
            (System.nanoTime() - started) / 1_000_000L, inferenceMs,
            SystemClock.currentThreadTimeMillis() - cpuStarted)
    }

    override fun close() {
        if (handle == 0L) return
        val owned = handle
        handle = 0L
        GuardianFsmnNative.destroy(owned)
    }

    companion object {
        const val MODEL_ID = "FSMN_OFFLINE_NCNN_FP32"
        const val TARGET_PEAK = .4f
        const val MAX_INPUT_GAIN = 64f
        const val FRAME_LENGTH = 400
        const val FRAME_SHIFT = 160
        // Pin the converted FP32 weights, graph and double-precision CMVN.
        private val expected = linkedMapOf(
            "fsmn_vad.ncnn.param" to "8391d5366869edfbce1a9fe915a8d5b02a09f746ef0e64d11f9f946dd65d9a47",
            "fsmn_vad.ncnn.bin" to "3e2261cba5f55ade2c1aa3a8fe1fea6bbcc1f3f625b07ff540ef5ae737df71fa",
            "cmvn-f64.bin" to "ad23bece553b8c8536b34ec630af644888dd3ef2a84776e528e6b4de2b1669fb",
        )

        internal fun condition(original: FloatArray): ConditionedInput {
            require(original.size in FRAME_LENGTH..128000 && original.all { it.isFinite() }) {
                "FSMN requires 400..128000 finite original samples"
            }
            val peak = original.maxOf { kotlin.math.abs(it) }
            val gain = if (peak == 0f) MAX_INPUT_GAIN else minOf(MAX_INPUT_GAIN, TARGET_PEAK / peak).coerceAtLeast(1f)
            val copy = FloatArray(original.size) { original[it] * gain }
            check(copy.all { it.isFinite() }) { "FSMN conditioned PCM invalid" }
            return ConditionedInput(copy, gain, peak, peak * gain)
        }

        fun load(readAsset: (String) -> ByteArray): GuardianFsmnConfirmation {
            val assets = expected.map { (name, hash) ->
                readAsset("fsmn/$name").also { bytes ->
                    val actual = MessageDigest.getInstance("SHA-256").digest(bytes)
                        .joinToString("") { "%02x".format(it.toInt() and 255) }
                    check(actual == hash) { "FSMN asset SHA-256 mismatch: $name" }
                }
            }
            val handle = GuardianFsmnNative.create(assets[0], assets[1], assets[2])
            check(handle != 0L) { "FSMN native initialization failed" }
            return GuardianFsmnConfirmation(handle)
        }
    }
}

internal object GuardianFsmnNative {
    init { System.loadLibrary("guardian_fsmn_vad") }
    external fun create(param: ByteArray, model: ByteArray, cmvn: ByteArray): Long
    external fun infer(handle: Long, pcm: FloatArray): FloatArray
    external fun destroy(handle: Long)
}
