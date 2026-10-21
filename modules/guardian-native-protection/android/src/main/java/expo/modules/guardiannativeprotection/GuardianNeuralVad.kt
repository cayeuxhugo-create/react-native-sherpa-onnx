package expo.modules.guardiannativeprotection

import android.content.Context
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig

/** Owned by the audio worker, not by Qwen's inference worker. */
internal class GuardianNeuralVad(context: Context) {
    companion object {
        const val THRESHOLD = 0.5f
        const val MIN_SPEECH_MS = 192
    }
    private val vad = Vad(
        assetManager = context.applicationContext.assets,
        config = VadModelConfig(
            sileroVadModelConfig = SileroVadModelConfig(
                model = "silero_vad.onnx",
                threshold = THRESHOLD,
                minSpeechDuration = MIN_SPEECH_MS / 1000f,
                minSilenceDuration = 0.128f,
                maxSpeechDuration = 30f,
                windowSize = 512,
            ),
            sampleRate = 16_000,
            numThreads = 1,
            provider = "cpu",
        ),
    )

    fun isSpeech(pcm: FloatArray, maximumInputGain: Float = 4f): Boolean {
        // Condition only this neural view; leave the decoder PCM untouched.
        vad.acceptWaveform(GuardianNeuralInputGain.forVad(pcm, maximumInputGain))
        val speech = vad.isSpeechDetected()
        // The segmenter owns audio; do not retain a second unbounded PCM queue.
        while (!vad.empty()) vad.pop()
        return speech
    }

    fun reset() = vad.reset()
    fun close() = vad.release()
}
