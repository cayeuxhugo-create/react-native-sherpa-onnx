package expo.modules.guardiannativeprotection

/**
 * Contrat des résultats ASR, indépendant du moteur.
 * Qwen est le seul décodeur audio en production.
 * La Word Timeline conserve l'identité des segments et ses mots.
 */
object GuardianAsrContract {
    data class RecognitionResult(
        val text: String,
        val language: String,
        val confidence: Float?,
        val isFinal: Boolean,
        val timestampMs: Long = System.currentTimeMillis(),
        val originId: Long? = null,
        val technicalSegmentId: Long = 0L,
        val boundaryReason: String? = null,
        val observedAtElapsedRealtimeMs: Long = 0L,
        val wordEvidence: List<GuardianWordTimelineEngine.AcousticWordEvidence> = emptyList(),
    )

    // Compatibilité avec les clés de diagnostic exposées à React Native.
    // Non instancié par le décodeur Qwen actuel ; ne pas annoncer "available".
    data class Diagnostics(
        val timestampMs: Long,
        val initialized: Boolean,
        val released: Boolean,
        val vadEnabled: Boolean,
        val vadEngine: String,
        val vadSpeechActive: Boolean,
        val utteranceHasAudio: Boolean,
        val vadThreshold: Float,
        val vadMinSpeechDuration: Float,
        val vadMinSilenceDuration: Float,
        val vadPreRollFrames: Int,
        val vadPreRollDurationMs: Int,
        val effectiveVadPreRollFrames: Int,
        val lastPartialText: String?,
        val lastFinalText: String?,
        val lastConfidence: Float?,
        val finalizedUtteranceCount: Long,
        val secondaryAsrEnabled: Boolean,
        val secondaryAsrModelAvailable: Boolean,
        val secondaryAsrInitialized: Boolean,
    )

    data class FocusShadowResult(
        val sessionToken: String,
        val primaryText: String,
        val textByView: Map<String, String>,
        val completedViews: Int,
        val alignedMeaningful: Boolean,
        val interrupted: Boolean,
        val elapsedMs: Long,
    )

    data class LexicalWordResolutionResult(
        val wordId: Long,
        val segmentId: Long,
        val originalWord: String,
        val replacementWord: String?,
        val supportingViews: Int,
        val observedAtElapsedRealtimeMs: Long,
    )

    interface Listener {
        fun onPartialResult(result: RecognitionResult)
        fun onFinalResult(result: RecognitionResult)
        fun onSpeechStarted()
        fun onSpeechEnded()
        fun onRecognizerError(error: Throwable)
        fun onVoiceFocusShadowDiagnostic(result: FocusShadowResult) = Unit
        fun onLexicalWordResolution(result: LexicalWordResolutionResult) = Unit
        fun onFinalCorrection(original: RecognitionResult, corrected: RecognitionResult) = Unit
        fun onFinalReviewStarted(originId: Long) = Unit
        fun onFinalReviewCompleted(originId: Long) = Unit
        fun onQwenReviewCompleted(correctionAccepted: Boolean) = Unit
    }
}
