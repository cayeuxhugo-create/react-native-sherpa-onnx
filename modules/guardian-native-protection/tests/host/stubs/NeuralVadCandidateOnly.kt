package expo.modules.guardiannativeprotection

/** Deliberately permissive candidate VAD for wiring tests only.
 * Native FireRed is REAL in these tests; no claim about Silero accuracy. */
internal class GuardianNeuralVad(context: android.content.Context) {
    fun isSpeech(pcm: FloatArray, maximumInputGain: Float = 4f) = pcm.any { it != 0f }
    fun reset() = Unit
    fun close() = Unit
}
