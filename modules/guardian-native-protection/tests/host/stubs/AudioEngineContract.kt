package expo.modules.guardiannativeprotection
class GuardianAudioEngine {
 interface AudioEnhancer {
  fun process(samples: FloatArray, sampleCount: Int, sampleRateHz: Int): EnhancementResult
  fun reset()
  fun release()
 }
 data class EnhancementResult(val samples: FloatArray, val sampleCount: Int,
   val processingTimeNanos: Long, val applied: Boolean, val outputReady: Boolean = true)
 enum class State { IDLE }
 class Statistics
enum class FarVoiceSource {
    NONE,
    STRICT_V2,
    ULTRA_WEAK_V2_1,
}


enum class FarVoicePhase {
    NONE,
    ACQUIRED,
    SUPPORTED,
    HOLD,
    RELEASING,
}


    data class AudioFrame(
    /*
     * Guardian Voice V4 - Truth Lane.
     *
     * Flux primaire envoyé à Nemotron :
     * - même capture microphone ;
     * - prétraitement mono léger ;
     * - Adaptive Gain ;
     * - Soft Limiter ;
     * - JAMAIS remplacé ni retardé par GTCRN.
     *
     * Une frame Truth est publiée pour chaque frame de capture valide.
     */
    val samples: FloatArray,
    val sampleCount: Int,
    val sampleRateHz: Int,
    val capturedAtElapsedRealtimeNanos: Long,
    val sequenceNumber: Long,

    /*
     * Guardian Voice V4 - Enhanced Lane.
     *
     * Seconde vue optionnelle, produite uniquement lorsque le routeur
     * acoustique décide qu'un traitement peut aider :
     * - GTCRN en environnement bruité ;
     * - Speech Conditioner pour une parole distante lorsque GTCRN
     *   n'est pas la route active.
     *
     * Cette vue n'a AUCUN pouvoir sur la continuité de la Truth Lane.
     * Son timestamp/sequenceNumber sont indépendants car un enhancer
     * streaming peut produire sa sortie avec retard.
     */
    val enhancedSamples: FloatArray,
    val enhancedSampleCount: Int,
    val enhancedCapturedAtElapsedRealtimeNanos: Long,
    val enhancedSequenceNumber: Long,
    val enhancementApplied: Boolean,
    val enhancementSource: String,

    /*
     * Guardian Diagnostic Capture C12.
     *
     * Copie Float32 du PCM16 tel qu'il sort d'AudioRecord,
     * AVANT preprocessMonoPcm().
     *
     * Diagnostic uniquement : ce flux ne participe à
     * aucune décision acoustique, aucun gain et aucun ASR.
     */
    val rawSamples: FloatArray,
    val rawSampleCount: Int,

    /*
     * Guardian Speech Capture V3-A.
     *
     * Copie du signal mono prétraité AVANT :
     * - Speech Enhancement ;
     * - Adaptive Gain ;
     * - Soft Limiter.
     *
     * Ce flux est diagnostic uniquement en V3-A.
     * Il ne modifie aucune décision Far Voice / Silero / ASR.
     *
     * Les timestamps/sequence dédiés décrivent la frame
     * de capture dont proviennent detectorSamples.
     */
    val detectorSamples: FloatArray,
    val detectorSampleCount: Int,
    val detectorCapturedAtElapsedRealtimeNanos: Long,
    val detectorSequenceNumber: Long,

    /*
     * Mesures acoustiques calculées AVANT Adaptive Gain.
     */
    val acousticRms: Float,
    val acousticNoiseFloorRms: Float,
    val snrDb: Float,

    /*
     * Classification historique conservée
     * pour compatibilité.
     */
    val speechLevel: String,

    /*
     * Identité de l'événement Far Voice.
     *
     * 0 = aucun événement Far Voice actif.
     *
     * Une même acquisition conserve le même ID
     * jusqu'à sa libération acoustique réelle.
     */
    val farVoiceEventId: Long,
    val farVoiceSource: FarVoiceSource,
    val farVoicePhase: FarVoicePhase,

    val adaptiveGain: Float,
    val enhancementExpected: Boolean = false,
    val captureChannelRevision: Long = 0L,
    val captureChannels: GuardianCaptureChannelFrame? = null,
)


    data class EnhancedAudioFrame(
        val samples: FloatArray,
        val sampleCount: Int,
        val capturedAtElapsedRealtimeNanos: Long,
        val sequenceNumber: Long,
        val source: String,
    )

    interface AudioProcessor {
        fun onAudioFrame(frame: AudioFrame)

        /*
         * V4.5 - seconde voie totalement asynchrone.
         * Une sortie GTCRN arrive ici sans jamais bloquer ni republier
         * une frame Truth/Nemotron.
         */
        fun onEnhancedAudioFrame(frame: EnhancedAudioFrame) = Unit

        fun onAudioEngineStateChanged(state: State) = Unit
        fun onAudioEngineError(error: Throwable, recoverable: Boolean) = Unit
        fun onAudioEngineStatistics(statistics: Statistics) = Unit
    }


}
