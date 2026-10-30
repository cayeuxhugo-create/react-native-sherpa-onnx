package expo.modules.guardiannativeprotection

import android.content.Context
import com.k2fsa.sherpa.onnx.OfflineSpeechDenoiserGtcrnModelConfig
import com.k2fsa.sherpa.onnx.OfflineSpeechDenoiserModelConfig
import com.k2fsa.sherpa.onnx.OnlineSpeechDenoiser
import com.k2fsa.sherpa.onnx.OnlineSpeechDenoiserConfig
import java.security.MessageDigest

/** Separate, unity-gain GTCRN instance for acoustic confirmation only.
 * The main enhancement leveler, microphone selection and ASR PCM are untouched.
 */
internal class GuardianTemporalGtcrn(context: Context) : GuardianTemporalConfirmationView.Backend {
    private val denoiser: OnlineSpeechDenoiser
    private var closed = false
    override val frameShiftInSamples: Int

    init {
        val application = context.applicationContext
        val hash = application.assets.open(MODEL_ASSET).use { stream ->
            MessageDigest.getInstance("SHA-256").digest(stream.readBytes())
                .joinToString("") { "%02x".format(it.toInt() and 255) }
        }
        check(hash == MODEL_SHA256) { "Temporal GTCRN model SHA-256 mismatch" }
        val created = OnlineSpeechDenoiser(
            assetManager = application.assets,
            config = OnlineSpeechDenoiserConfig(model = OfflineSpeechDenoiserModelConfig(
                gtcrn = OfflineSpeechDenoiserGtcrnModelConfig(model = MODEL_ASSET),
                numThreads = 1, debug = false, provider = "cpu",
            )),
        )
        try {
            check(created.sampleRate == 16_000) { "Temporal GTCRN requires 16 kHz" }
            check(created.frameShiftInSamples == 256) { "Temporal GTCRN frame shift changed" }
            frameShiftInSamples = created.frameShiftInSamples
            denoiser = created
        } catch (error: Throwable) {
            created.release()
            throw error
        }
    }

    override fun run(pcm: FloatArray): FloatArray {
        check(!closed)
        require(pcm.size == frameShiftInSamples && pcm.all(Float::isFinite))
        val output = denoiser.run(pcm, 16_000)
        check(output.sampleRate == 16_000) { "Temporal GTCRN output sample rate changed" }
        return output.samples
    }

    override fun reset() {
        check(!closed)
        denoiser.reset()
    }

    override fun close() {
        if (closed) return
        closed = true
        denoiser.release()
    }

    companion object {
        const val MODEL_ASSET = "gtcrn_simple.onnx"
        const val MODEL_SHA256 = "e77603ac0c23dac3227dd2d7135b3a585cbee2679048aecfa886657d3ae1b534"
        fun loadView(context: Context): GuardianTemporalConfirmationView =
            GuardianTemporalConfirmationView(GuardianTemporalGtcrn(context))
    }
}
