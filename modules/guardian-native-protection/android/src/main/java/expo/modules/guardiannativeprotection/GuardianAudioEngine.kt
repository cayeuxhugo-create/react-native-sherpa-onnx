package expo.modules.guardiannativeprotection

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecordingConfiguration
import android.media.AudioRouting
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.AudioDeviceInfo
import android.media.MicrophoneDirection
import android.media.MicrophoneInfo
import android.os.Build
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Process
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Point d'entrée audio natif unique de Guardian Voice.
 *
 * Microphone -> capture PCM16 -> file bornée -> traitement Float32 -> processeurs.
 * Cette classe ne dépend ni de Sherpa, ni de la logique métier Guardian.
 */
class GuardianAudioEngine(
    context: Context,
    private val configuration: Configuration = Configuration(),
) {

    data class Configuration(
        val sampleRateHz: Int = DEFAULT_SAMPLE_RATE_HZ,
        val frameDurationMs: Int = DEFAULT_FRAME_DURATION_MS,
        val audioSource: Int = MediaRecorder.AudioSource.VOICE_RECOGNITION,

        /*
         * Diagnostic multi-mic.
         *
         * Production reste MONO par défaut. Lorsque CHANNEL_IN_STEREO est
         * demandé, Guardian désentrelace L/R puis publie un seul flux mono
         * au pipeline existant selon stereoChannelSelection.
         */
        val inputChannelMask: Int = AudioFormat.CHANNEL_IN_MONO,
        val stereoChannelSelection: Int = STEREO_CHANNEL_LEFT,
        val stereoDiagnosticCaptureEnabled: Boolean = false,

        /*
         * Guardian Spatial Trust.
         *
         * Le simple fait d'avoir deux canaux ne suffit pas pour autoriser
         * un steering temporel. Ces paramètres sont fournis par
         * GuardianAudioCapabilities après qualification de la route.
         */
        val spatialSteeringAllowed: Boolean = false,
        val spatialMaximumTrustedTdoaSeconds: Double? = null,
        val spatialInputTrustName: String = "NONE",
        val voiceFocusMode: GuardianVoiceFocusMode =
            GuardianVoiceFocusMode.OFF,

        val internalBufferMultiplier: Int = DEFAULT_INTERNAL_BUFFER_MULTIPLIER,
        val queueCapacity: Int = DEFAULT_QUEUE_CAPACITY,
        val poolCapacity: Int = DEFAULT_POOL_CAPACITY,
        val maximumRestartAttempts: Int = DEFAULT_MAXIMUM_RESTART_ATTEMPTS,
        val restartBaseDelayMs: Long = DEFAULT_RESTART_BASE_DELAY_MS,
        val statisticsIntervalMs: Long = DEFAULT_STATISTICS_INTERVAL_MS,

        /*
         * Diagnostic Far Voice détaillé.
         *
         * Production = false : un faux candidat ne doit jamais produire
         * 20 lignes de log par événement. Les transitions structurantes
         * (LOCK / route) restent disponibles.
         */
        val farVoiceDiagnosticEnabled: Boolean = false,

        val adaptiveGainEnabled: Boolean = true,

        /*
         * Guardian Speech Conditioner V1.
         *
         * Active uniquement sur la route BYPASS lorsque la frame est déjà
         * classée DISTANT_SPEECH. Il ne modifie aucun seuil d'admission.
         */
        val speechConditionerEnabled: Boolean = true,

        val targetRms: Float = DEFAULT_TARGET_RMS,
        val minimumGain: Float = DEFAULT_MINIMUM_GAIN,
        val maximumGain: Float = DEFAULT_MAXIMUM_GAIN,
        val gainAttack: Float = DEFAULT_GAIN_ATTACK,
        val gainRelease: Float = DEFAULT_GAIN_RELEASE,
        val noiseFloorRise: Float = DEFAULT_NOISE_FLOOR_RISE,
        val noiseFloorFall: Float = DEFAULT_NOISE_FLOOR_FALL,
        val speechSnrThresholdDb: Float = DEFAULT_SPEECH_SNR_THRESHOLD_DB,
        val minimumSpeechRms: Float = DEFAULT_MINIMUM_SPEECH_RMS,
        val adaptiveGainMinimumRms: Float = DEFAULT_ADAPTIVE_GAIN_MINIMUM_RMS,
        val adaptiveGainMinimumSnrDb: Float = DEFAULT_ADAPTIVE_GAIN_MINIMUM_SNR_DB,
        /*
         * Guardian Capture Profile C4.
         *
         * Guardian Voice n'est pas une caméra : la parole utile peut
         * arriver de n'importe quel côté du téléphone. On ne demande
         * donc plus de beamforming orienté vers l'utilisateur ni de
         * zoom micro par défaut.
         */
        val preferredMicrophoneDirectionEnabled: Boolean = false,
        val preferredMicrophoneFieldEnabled: Boolean = false,
        val preferredMicrophoneFieldDimension: Float = 0.0f,
        val androidAgcEnabled: Boolean = false,
        val androidNoiseSuppressorEnabled: Boolean = true,

/*
 * Phase C - Speech Enhancement.
 *
 * Désactivé par défaut :
 * C1 prépare uniquement l'architecture.
 */
        val speechEnhancementEnabled: Boolean = false,
        val forceSpeechEnhancement: Boolean = false,

        /*
         * Phase C7 - Routage dynamique GTCRN.
         *
         * Ces seuils ne modifient PAS Far Voice / Ultra-Weak.
         * Ils servent uniquement à décider si l'environnement
         * acoustique justifie le débruitage neural.
         */
        val enhancementNoiseFloorEnterRms: Float =
            DEFAULT_ENHANCEMENT_NOISE_FLOOR_ENTER_RMS,
        val enhancementNoiseFloorExitRms: Float =
            DEFAULT_ENHANCEMENT_NOISE_FLOOR_EXIT_RMS,

        val stopJoinTimeoutMs: Long = DEFAULT_STOP_JOIN_TIMEOUT_MS,
    ) {
        init {
            require(sampleRateHz in 8_000..48_000)
            require(frameDurationMs in 20..500)
            require(
                inputChannelMask == AudioFormat.CHANNEL_IN_MONO ||
                    inputChannelMask == AudioFormat.CHANNEL_IN_STEREO
            ) {
                "inputChannelMask doit être MONO ou STEREO."
            }
            require(
                stereoChannelSelection in
                STEREO_CHANNEL_LEFT..STEREO_CHANNEL_VOICE_FOCUS
            ) {
                "stereoChannelSelection invalide."
            }
            require(
                spatialMaximumTrustedTdoaSeconds == null ||
                    (
                        spatialMaximumTrustedTdoaSeconds.isFinite() &&
                            spatialMaximumTrustedTdoaSeconds > 0.0 &&
                            spatialMaximumTrustedTdoaSeconds <= 0.01
                    )
            ) {
                "spatialMaximumTrustedTdoaSeconds invalide."
            }
            require(
                !spatialSteeringAllowed ||
                    spatialMaximumTrustedTdoaSeconds != null
            ) {
                "Le steering spatial exige une borne TDOA fiable."
            }
            require(internalBufferMultiplier >= 2)
            require(queueCapacity in 2..64)
            require(poolCapacity >= queueCapacity)
            require(maximumRestartAttempts in 0..10)
            require(restartBaseDelayMs in 100L..30_000L)
            require(statisticsIntervalMs >= 500L)
            require(targetRms in 0.01f..0.50f)
            require(minimumGain in 0.1f..1.0f)
            require(maximumGain in 1.0f..10.0f)
            require(maximumGain >= minimumGain)
            require(gainAttack in 0.01f..1.0f)
            require(gainRelease in 0.01f..1.0f)
            require(noiseFloorRise in 0.001f..1.0f)
            require(noiseFloorFall in 0.001f..1.0f)
            require(speechSnrThresholdDb in 0.0f..30.0f)
            require(minimumSpeechRms in 0.001f..0.50f)
            require(
                    adaptiveGainMinimumRms in
                     0.001f..0.50f
            )

            require(
                    adaptiveGainMinimumSnrDb in
                    0.0f..30.0f
            )

            require(
                    adaptiveGainMinimumRms <=
                    minimumSpeechRms
            )

            require(
                    adaptiveGainMinimumSnrDb <=
                    speechSnrThresholdDb
            )

            require(
                preferredMicrophoneFieldDimension in -1.0f..1.0f
            ) {
                "preferredMicrophoneFieldDimension doit être compris entre -1.0 et 1.0."
    }

            require(
                enhancementNoiseFloorExitRms in
                    MINIMUM_NOISE_FLOOR_RMS..MAXIMUM_NOISE_FLOOR_RMS
            )

            require(
                enhancementNoiseFloorEnterRms in
                    MINIMUM_NOISE_FLOOR_RMS..MAXIMUM_NOISE_FLOOR_RMS
            )

            require(
                enhancementNoiseFloorExitRms <
                    enhancementNoiseFloorEnterRms
            ) {
                "Le seuil de sortie GTCRN doit être inférieur au seuil d'entrée."
            }

            require(stopJoinTimeoutMs >= 500L)
        }
    }

    enum class State {
        IDLE, STARTING, RUNNING, PAUSING, PAUSED, STOPPING, ERROR, RELEASED
    }

    /*
 * Origine de l'événement Far Voice courant.
 *
 * Cette information décrit QUI a acquis l'événement,
 * pas simplement le niveau acoustique de la frame.
 */
enum class FarVoiceSource {
    NONE,
    STRICT_V2,
    ULTRA_WEAK_V2_1,
}

/*
 * Phase temporelle de l'événement Far Voice.
 *
 * ACQUIRED :
 * nouvelle acquisition sur cette frame.
 *
 * SUPPORTED :
 * l'événement existe et la frame courante apporte
 * encore une preuve acoustique directe.
 *
 * HOLD :
 * l'événement est maintenu par l'hystérésis Far Voice.
 *
 * RELEASING :
 * le signal est revenu au bruit mais le verrou
 * acoustique n'est pas encore libéré.
 */
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
)

    data class Statistics(
        val timestampMs: Long,
        val state: State,
        val sampleRateHz: Int,
        val frameDurationMs: Int,
        val capturedFrameCount: Long,
        val processedFrameCount: Long,
        val droppedFrameCount: Long,
        val enhancedDroppedFrameCount: Long,
        val capturedSampleCount: Long,
        val restartCount: Int,
        val queueDepth: Int,
        val queueCapacity: Int,
        val rawRms: Float,
        val filteredRms: Float,
        val rms: Float,
        val noiseFloor: Float,
        val snrDb: Float,
        val speechLevel: String,
        val speechLikely: Boolean,
        val gainEligible: Boolean,
        val adaptiveGain: Float,
        val outputRms: Float,

        val speechConditionerEnabled: Boolean,
        val speechConditionerApplied: Boolean,
        val speechConditionerInputRms: Float,
        val speechConditionerOutputRms: Float,

        val enhancementEnabled: Boolean,
        val enhancementRoute: String,
        val enhancementRouteReason: String,
        val enhancementApplied: Boolean,
        val enhancementInputRms: Float,
        val enhancementOutputRms: Float,
        val enhancementProcessingMs: Double,
        val enhancementAverageProcessingMs: Double,
        val enhancementP95ProcessingMs: Double,
        val peak: Float,
        val clippedSampleCount: Int,
        val limiterEngagedSampleCount: Int,
        val lastCaptureGapMs: Long?,
        val maximumCaptureGapMs: Long,
    )

    data class ActiveMicrophoneRuntime(
    val id: Int,
    val description: String,
    val deviceType: Int,
    val deviceTypeName: String,
    val location: Int,
    val group: Int,
    val indexInGroup: Int,
    val directionality: Int,
    val channelMapping: List<String>,
)

data class RuntimeAudioProfile(
    val audioSource: Int,
    val audioSourceName: String,

    val sampleRateHz: Int,
    val channelCount: Int,

    val activeMicrophones: List<ActiveMicrophoneRuntime>,
    val activeMicrophoneCount: Int,

    val noiseSuppressorRequested: Boolean,
    val noiseSuppressorActive: Boolean,

    val androidAgcRequested: Boolean,
    val androidAgcActive: Boolean,

    val directionRequested: Boolean,
    val directionApplied: Boolean,

    val fieldDimensionRequested: Boolean,
    val fieldDimensionApplied: Boolean,

    /*
     * Diagnostic réel du chemin de capture Android.
     * Les effets actifs peuvent différer des seuls effets demandés
     * explicitement par Guardian.
     */
    val activePreprocessingEffects: List<String>,
    val clientPreprocessingEffects: List<String>,
    val unprocessedSourceSupported: Boolean,

    val multiChannelActive: Boolean,
)

    private data class MicrophoneControlResult(
    val directionRequested: Boolean,
    val directionApplied: Boolean,

    val fieldDimensionRequested: Boolean,
    val fieldDimensionApplied: Boolean,
)

    /*
     * Step 4 - empreinte du chemin de capture réellement actif.
     *
     * Elle ne change aucun traitement audio. Elle sert uniquement à détecter
     * une reconfiguration Android susceptible d'invalider les hypothèses
     * spatiales établies au démarrage.
     */
    private data class RuntimeCaptureSignature(
        val clientSilenced: Boolean,
        val audioDeviceId: Int?,
        val audioDeviceType: Int?,
        val hardwareSampleRateHz: Int?,
        val hardwareChannelCount: Int?,
        val hardwareEncoding: Int?,
        val clientSampleRateHz: Int?,
        val clientChannelCount: Int?,
        val clientEncoding: Int?,
        val activeEffects: List<String>,
        val clientEffects: List<String>,
        val activeMicrophoneMappings: List<String>,
    ) {
        fun spatialIdentity(): String =
            buildString {
                append(audioDeviceId)
                append('|')
                append(audioDeviceType)
                append('|')
                append(hardwareSampleRateHz)
                append('|')
                append(hardwareChannelCount)
                append('|')
                append(hardwareEncoding)
                append('|')
                append(clientSampleRateHz)
                append('|')
                append(clientChannelCount)
                append('|')
                append(clientEncoding)
                append('|')
                append(activeEffects.joinToString(","))
                append('|')
                append(clientEffects.joinToString(","))
                append('|')
                append(activeMicrophoneMappings.joinToString(","))
            }
    }

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

    /*
 * Phase C - frontière générique de Speech Enhancement.
 *
 * GuardianAudioEngine ne dépend d'aucun modèle précis.
 * GTCRN / DPDFNet / autre moteur pourront implémenter
 * cette interface plus tard.
 */
interface AudioEnhancer {

    fun process(
        samples: FloatArray,
        sampleCount: Int,
        sampleRateHz: Int,
    ): EnhancementResult

    fun reset() = Unit

    fun release() = Unit
}

data class EnhancementResult(
    val samples: FloatArray,
    val sampleCount: Int,
    val processingTimeNanos: Long,
    val applied: Boolean,

    /*
     * false :
     * l'enhancer streaming a accepté l'entrée,
     * mais n'a pas encore assez de sortie cohérente
     * pour publier une AudioFrame.
     *
     * true :
     * la sortie peut être envoyée aux AudioProcessor.
     */
    val outputReady: Boolean = true,
)

    class AudioCaptureException(
        message: String,
        val errorCode: Int? = null,
        cause: Throwable? = null,
    ) : RuntimeException(
        if (errorCode == null) message else "$message Code=$errorCode",
        cause,
    )

    private data class PooledFrame(
    /*
     * Guardian Voice V4 - Truth Lane.
     *
     * Signal mono prétraité qui reste indépendant de GTCRN.
     * L'Adaptive Gain / Soft Limiter sont appliqués ensuite sur CE buffer.
     */
    val samples: FloatArray,

    /*
     * Copie dédiée à l'entrée du Speech Enhancement.
     *
     * GTCRN ne reçoit jamais directement samples afin qu'un enhancer
     * in-place ne puisse pas altérer la Truth Lane.
     */
    val enhancementInputSamples: FloatArray,

    /*
     * Sortie Enhanced conservée séparément de la Truth Lane.
     */
    val enhancedSamples: FloatArray,

    /*
     * C12 : copie du PCM AudioRecord normalisé AVANT
     * preprocessMonoPcm(). Buffer préalloué.
     */
    val rawSamples: FloatArray,

    /*
     * V3-A : copie pré-gain indépendante.
     *
     * Ce buffer est préalloué avec la frame afin de ne
     * créer aucune allocation supplémentaire dans le
     * chemin temps réel de capture.
     */
    val detectorSamples: FloatArray,

    var sampleCount: Int = 0,
    var capturedAtElapsedRealtimeNanos: Long = 0L,
    var sequenceNumber: Long = 0L,

    /*
     * Analyse acoustique calculée AVANT GTCRN.
     *
     * Ces valeurs suivent le PCM dans frameQueue
     * afin de pouvoir recalculer le gain sur la
     * bonne sortie neuralisée.
     */
    var frameRms: Float = 0.0f,
var noiseFloorRms: Float = 0.0f,

var speechLevel: AcousticSpeechLevel =
    AcousticSpeechLevel.NOISE,

var speechLikely: Boolean = false,
var gainEligible: Boolean = false,
var snrDb: Float = 0.0f,

var farVoiceEventId: Long = 0L,
var farVoiceSource: FarVoiceSource =
    FarVoiceSource.NONE,
var farVoicePhase: FarVoicePhase =
    FarVoicePhase.NONE,
)

    /*
 * Phase C2 - métadonnées associées au flux audio
 * entrant dans un enhancer streaming.
 *
 * Lorsque GTCRN introduit un retard de quelques samples,
 * les métadonnées doivent subir exactement le même retard
 * que l'audio.
 */
private data class EnhancementFrameMetadata(
    val capturedAtElapsedRealtimeNanos: Long,
    val sequenceNumber: Long,

    val frameRms: Float,
    val noiseFloorRms: Float,

    val speechLevel: AcousticSpeechLevel,
    val speechLikely: Boolean,
    val gainEligible: Boolean,
    val snrDb: Float,

    val farVoiceEventId: Long,
    val farVoiceSource: FarVoiceSource,
    val farVoicePhase: FarVoicePhase,
)

private data class EnhancementWorkItem(
    val samples: FloatArray,
    var sampleCount: Int = 0,
    var metadata: EnhancementFrameMetadata? = null,
)

    private enum class SessionResult { STOPPED, RECOVERABLE_ERROR, FATAL_ERROR }

    private enum class AcousticSpeechLevel {
    NOISE,
    DISTANT_SPEECH,
    CLEAR_SPEECH
}

/*
 * Phase C7 - Route d'enhancement stable.
 *
 * BYPASS :
 * le PCM prétraité passe directement vers Adaptive Gain.
 *
 * GTCRN :
 * le flux continu est confié au débruiteur neural.
 */
private enum class EnhancementRoute {
    BYPASS,
    GTCRN,
}

   private data class AcousticAnalysis(
    val frameRms: Float,
    val noiseFloor: Float,
    val snrDb: Float,

    /**
     * Classification acoustique Guardian.
     *
     * NOISE :
     * aucun signal vocal suffisamment crédible.
     *
     * DISTANT_SPEECH :
     * signal faible mais suffisamment distinct
     * du bruit de fond pour être considéré comme
     * une voix distante potentielle.
     *
     * CLEAR_SPEECH :
     * signal vocal suffisamment fort et propre.
     */
    val speechLevel: AcousticSpeechLevel,

    val speechLikely: Boolean,

    /**
     * Autorise l'Adaptive Gain à travailler
     * sur une voix claire ou distante crédible.
     */
    val gainEligible: Boolean,
    val farVoiceEventId: Long,
    val farVoiceSource: FarVoiceSource,
    val farVoicePhase: FarVoicePhase,
    )

    private val applicationContext = context.applicationContext

    private data class StereoDiagnosticCapture(
        val file: File,
        val stream: RandomAccessFile,
        var dataBytes: Long = 0L,
    )

    @Volatile
    private var stereoDiagnosticCapture: StereoDiagnosticCapture? = null
    private val lifecycleLock = Any()
    private val processors = CopyOnWriteArrayList<AudioProcessor>()

    private val stopRequested = AtomicBoolean(false)
    private val pauseRequested = AtomicBoolean(false)
    private val released = AtomicBoolean(false)

    private val capturedFrameCount = AtomicLong(0L)
    private val processedFrameCount = AtomicLong(0L)
    private val droppedFrameCount = AtomicLong(0L)
    private val capturedSampleCount = AtomicLong(0L)
    private val sequenceNumber = AtomicLong(0L)

    @Volatile private var state: State = State.IDLE
    @Volatile private var audioRecord: AudioRecord? = null
    @Volatile
        private var automaticGainControl: AutomaticGainControl? = null

    @Volatile
        private var noiseSuppressor: NoiseSuppressor? = null
    @Volatile private var captureThread: Thread? = null
    @Volatile private var processingThread: Thread? = null
    @Volatile private var restartCount: Int = 0
    @Volatile private var lastCaptureElapsedRealtimeNanos: Long? = null
    @Volatile private var lastCaptureGapMs: Long? = null
    @Volatile private var maximumCaptureGapMs: Long = 0L
    @Volatile
    private var currentAdaptiveGain: Float = 1.0f

    /*
     * Guardian Speech Conditioner V1.
     *
     * previousInput conserve la continuité du détail haute fréquence entre
     * deux AudioFrame successives.
     */
    @Volatile
    private var speechConditionerPreviousInput: Float = 0.0f

    @Volatile
    private var lastSpeechConditionerApplied: Boolean = false

    @Volatile
    private var lastSpeechConditionerInputRms: Float = 0.0f

    @Volatile
    private var lastSpeechConditionerOutputRms: Float = 0.0f

    @Volatile
    private var monoHighPassPreviousInput: Float =
    0.0f
    @Volatile
    private var lastSpeechLevel: AcousticSpeechLevel =
    AcousticSpeechLevel.NOISE

    @Volatile
    private var monoHighPassPreviousOutput: Float =
    0.0f

    @Volatile
    private var lastRawRms: Float =
    0.0f

    @Volatile
    private var lastFilteredRms: Float =
    0.0f
    @Volatile
    private var lastOutputRms: Float =
    0.0f
    @Volatile
    private var estimatedNoiseFloor: Float = 0.0f

    /*
     * C12 - diagnostic uniquement.
     * Valeur candidate AVANT le clamp historique et état
     * indiquant si MINIMUM/MAXIMUM_NOISE_FLOOR_RMS intervient.
     */
    @Volatile
    private var lastNoiseFloorCandidateRms: Float = 0.0f

    @Volatile
    private var lastNoiseFloorClampActive: Boolean = false

    @Volatile
    private var lastSnrDb: Float = 0.0f

    @Volatile
    private var speechLikely: Boolean = false
    @Volatile
    private var lastGainEligible: Boolean = false
    @Volatile
    private var gainHoldFramesRemaining: Int = 0

    @Volatile
    private var consecutiveGainEligibleFrames: Int = 0
    @Volatile
private var consecutiveDistantSpeechFrames: Int =
    0

@Volatile
private var distantSpeechHoldFramesRemaining: Int =
    0

/*
 * Far Voice V2.1 - Ultra-Weak Acquisition.
 *
 * Ce score accumule des preuves acoustiques faibles
 * sur plusieurs frames.
 *
 * Il ne remplace PAS l'acquisition stricte V2.
 * Il constitue une deuxième voie d'acquisition pour
 * les voix lointaines qui n'atteignent pas deux fois
 * consécutivement le seuil strict.
 */
@Volatile
private var ultraWeakSpeechEvidence: Float =
    0.0f

@Volatile
private var ultraWeakAcquisitionActive: Boolean =
    false

/*
 * Far Voice V2.1 - verrou d'événement.
 *
 * true :
 * une acquisition Far Voice appartient déjà à
 * l'événement acoustique courant.
 *
 * Tant que ce verrou reste actif, V2.1 ne peut pas
 * déclarer une nouvelle acquisition.
 *
 * Il sera libéré uniquement après un retour suffisamment
 * stable au bruit, et non simplement à l'expiration
 * momentanée du hold.
 */
@Volatile
private var farVoiceAcquisitionLocked: Boolean =
    false

/*
 * MEMORY FIX V2 - réarmement Far Voice.
 *
 * Après la fin d'un événement, Guardian ne peut pas réouvrir immédiatement
 * un nouvel événement sur la même ambiance acoustique ambiguë. Aucun seuil
 * RMS/SNR n'est modifié : on exige seulement un vrai retour à un état NOISE
 * neutre avant de réarmer l'acquisition.
 */
@Volatile
private var farVoiceRearmRequired: Boolean =
    false

@Volatile
private var farVoiceRearmNoiseFrames: Int =
    0

/*
 * Guardian Soft Voice V3 - preuve indépendante d'un événement Far Voice.
 *
 * Un événement acoustique n'est considéré comme "confirmé" que si Nemotron
 * a réellement commencé à produire du lexical pendant cet événement.
 * Le bruit peut donc être classé DISTANT_SPEECH (gain conservé) sans avoir
 * le droit de réarmer indéfiniment des événements coûteux.
 */
@Volatile
private var farVoiceCurrentEventLexicallyConfirmed: Boolean =
    false

/*
 * Après un événement Far Voice non confirmé lexicalement, un simple retour
 * au bruit neutre ne suffit plus à créer une nouvelle identité d'événement.
 * La classification acoustique continue, mais l'ouverture d'un nouvel eventId
 * attend une preuve indépendante (lexical Nemotron ou CLEAR_SPEECH).
 */
@Volatile
private var farVoiceRearmNeedsIndependentSpeech: Boolean =
    false

/*
 * Identité monotone de l'événement Far Voice.
 *
 * L'ID change uniquement lors d'une NOUVELLE acquisition.
 * HOLD et RELEASING conservent donc le même ID.
 */
@Volatile
private var farVoiceEventCounter: Long =
    0L

@Volatile
private var activeFarVoiceEventId: Long =
    0L

@Volatile
private var activeFarVoiceSource: FarVoiceSource =
    FarVoiceSource.NONE

/*
 * Nemotron V6 : dernière phase acoustique Far Voice réellement publiée.
 * Elle permet au lexical de confirmer uniquement un événement qui possède
 * encore une preuve DIRECTE (ACQUIRED/SUPPORTED), jamais un HOLD/RELEASING.
 */
@Volatile
private var lastFarVoicePhase: FarVoicePhase =
    FarVoicePhase.NONE

    private fun beginFarVoiceEvent(
    source: FarVoiceSource,
) {
    farVoiceEventCounter =
        if (
            farVoiceEventCounter ==
            Long.MAX_VALUE
        ) {
            1L
        } else {
            farVoiceEventCounter + 1L
        }

    activeFarVoiceEventId =
        farVoiceEventCounter

    activeFarVoiceSource =
        source

    /*
     * Nemotron V6 : un nouvel eventId n'hérite jamais automatiquement d'un
     * ancien hint lexical. Cela évite qu'une nouvelle acquisition acoustique
     * apparue pendant une pause soit déclarée "confirmée" par le mot précédent.
     */
    farVoiceCurrentEventLexicallyConfirmed =
        false
}

private fun clearFarVoiceEvent() {
    activeFarVoiceEventId =
        0L

    activeFarVoiceSource =
        FarVoiceSource.NONE

    farVoiceCurrentEventLexicallyConfirmed =
        false
}

    /*
 * Nombre de frames NOISE consécutives observées
 * après un événement Far Voice.
 *
 * Le verrou n'est libéré qu'après un retour
 * suffisamment stable au bruit.
 */
@Volatile
private var farVoiceNoiseReleaseFrames: Int =
    0

    /*
 * Diagnostic Far Voice -> Sherpa.
 *
 * Lorsqu'une acquisition Far Voice se produit,
 * Guardian observe temporairement les frames réellement
 * envoyées aux AudioProcessor.
 *
 * Diagnostic uniquement :
 * aucune influence sur le traitement audio.
 */
@Volatile
private var farVoiceDiagnosticFramesRemaining: Int =
    0

@Volatile
private var farVoiceDiagnosticSource: String =
    "NONE"

@Volatile
private var lastStatistics: Statistics? = null

    @Volatile
        private var runtimeAudioProfile:
            RuntimeAudioProfile? = null

    /*
     * Step 4 - monitoring du chemin de capture Android.
     */
    private val runtimeCaptureChangeCount =
        AtomicLong(0L)

    @Volatile
    private var runtimeCaptureSignature:
        RuntimeCaptureSignature? = null

    @Volatile
    private var runtimeSpatialTrustInvalidated =
        false

    /*
     * Step 6 audit - validité de l'apprentissage AUTO.
     *
     * Le traitement audio peut continuer après une reconfiguration Android,
     * mais les observations de cette session ne doivent plus alimenter
     * Guardian Adaptive Audio si le chemin de capture n'est plus celui
     * qualifié au démarrage.
     */
    @Volatile
    private var runtimeRouteEvidenceValid =
        true

    @Volatile
    private var runtimeRouteEvidenceInvalidReason:
        String? = null

    @Volatile
    private var audioRecordingCallback:
        AudioManager.AudioRecordingCallback? = null

    @Volatile
    private var audioRoutingChangedListener:
        AudioRouting.OnRoutingChangedListener? = null

    /*
 * Phase C - enhancer actuellement branché.
 */
@Volatile
private var audioEnhancer: AudioEnhancer? =
    null

@Volatile
private var lastEnhancementProcessingTimeNanos: Long =
    0L

@Volatile
private var lastEnhancementApplied: Boolean =
    false

@Volatile
private var lastEnhancementInputRms: Float =
    0.0f

@Volatile
private var lastEnhancementOutputRms: Float =
    0.0f

/*
 * File temporelle C2.
 *
 * Chaque frame envoyée à l'enhancer dépose ici
 * ses métadonnées.
 *
 * Lorsqu'une sortie enhanced devient disponible,
 * on récupère les métadonnées les plus anciennes,
 * correspondant au début du flux amélioré.
 */
private val enhancementMetadataQueue =
    ArrayDeque<EnhancementFrameMetadata>()

/*
 * Phase C2 - Circuit breaker Speech Enhancement.
 *
 * true :
 * C est autorisé à traiter le flux de la session courante.
 *
 * false :
 * une anomalie C a été détectée.
 * Guardian continue alors en audio original jusqu'à
 * la prochaine réinitialisation de session.
 *
 * Cette variable appartient au processingThread.
 * @Volatile permet également un diagnostic sûr depuis
 * les autres threads sans imposer de verrou au chemin audio.
 */
@Volatile
private var speechEnhancementSessionHealthy =
    true

/*
 * Phase C7 - état du routeur GTCRN.
 *
 * Le processingThread est l'unique propriétaire des transitions.
 */
@Volatile
private var enhancementRoute: EnhancementRoute =
    EnhancementRoute.BYPASS

@Volatile
private var enhancementRouteReason: String =
    "SESSION_START"

private var enhancementNoisyFrames: Int =
    0

private var enhancementQuietFrames: Int =
    0

private var enhancementSpeechHoldFrames: Int =
    0

/*
 * Guardian Voice V4.2 - Difficult Lane.
 *
 * Le routeur C7 historique n'entrait jamais dans GTCRN une fois la parole
 * détectée. Une voix distante pouvait donc rester BYPASS pendant toute la
 * phrase. Ce compteur permet une activation bornée après quelques frames
 * DISTANT_SPEECH, sans jamais toucher à la Truth Lane.
 */
private var enhancementDifficultSpeechFrames: Int =
    0

/*
 * MEMORY FIX V2 - une seule ouverture Difficult Lane par événement Far Voice.
 *
 * Le premier champ mémorise l'événement actuellement en confirmation.
 * Le second mémorise le dernier événement ayant réellement ouvert GTCRN.
 */
private var enhancementDifficultSpeechEventId: Long =
    0L

private var enhancementLastGtcrnFarVoiceEventId: Long =
    0L

private val enhancementProcessingTimeTotalNanos =
    AtomicLong(0L)

private val enhancementProcessedFrameCount =
    AtomicLong(0L)

    /*
 * C1.2 - Fenêtre bornée des temps de traitement C.
 *
 * Elle sert uniquement au diagnostic P95.
 * On conserve les 100 dernières frames réellement
 * traitées par l'enhancer.
 */
private val enhancementProcessingTimesNanos =
    LongArray(
        ENHANCEMENT_TIMING_WINDOW_SIZE
    )

    /*
 * Buffer de travail préalloué pour le calcul P95.
 *
 * Il évite d'allouer un nouveau LongArray
 * à chaque émission de statistiques.
 */
private val enhancementP95Scratch =
    LongArray(
        ENHANCEMENT_TIMING_WINDOW_SIZE
    )

private var enhancementProcessingTimesIndex: Int =
    0

private var enhancementProcessingTimesCount: Int =
    0

    private val frameSampleCount =
        ((configuration.sampleRateHz.toLong() * configuration.frameDurationMs) / 1_000L)
            .toInt()
            .coerceAtLeast(1)

        private val spatialAudio =
            GuardianSpatialAudio(
                sampleRateHz =
                    configuration.sampleRateHz,

                maximumFrameSamples =
                    frameSampleCount,

                steeringAllowed =
                    configuration.spatialSteeringAllowed,

                maximumTrustedTdoaSeconds =
                    configuration.spatialMaximumTrustedTdoaSeconds,

                inputTrustName =
                    configuration.spatialInputTrustName,
                voiceFocusMode = configuration.voiceFocusMode,
         )

    private val frameQueue = ArrayBlockingQueue<PooledFrame>(configuration.queueCapacity)
    private val framePool = ArrayBlockingQueue<PooledFrame>(configuration.poolCapacity)

    /*
     * V4.5 - GTCRN ne partage plus la file Truth.
     * Cette file est volontairement petite : si GTCRN ne suit pas,
     * on perd une vue Enhanced, jamais une frame microphone/Nemotron.
     */
    private val enhancementWorkQueue =
        ArrayBlockingQueue<EnhancementWorkItem>(ENHANCEMENT_ASYNC_QUEUE_CAPACITY)
    private val enhancementWorkPool =
        ArrayBlockingQueue<EnhancementWorkItem>(ENHANCEMENT_ASYNC_POOL_CAPACITY)
    private val enhancedDroppedFrameCount = AtomicLong(0L)
    private val enhancementResetRequested = AtomicBoolean(false)
    @Volatile private var enhancementThread: Thread? = null

    init {
        repeat(ENHANCEMENT_ASYNC_POOL_CAPACITY) {
            enhancementWorkPool.offer(
                EnhancementWorkItem(
                    samples = FloatArray(frameSampleCount),
                )
            )
        }

        repeat(configuration.poolCapacity) {
            framePool.offer(
                PooledFrame(
                    samples =
                        FloatArray(frameSampleCount),

                    enhancementInputSamples =
                        FloatArray(frameSampleCount),

                    enhancedSamples =
                        FloatArray(frameSampleCount),

                    rawSamples =
                        FloatArray(frameSampleCount),

                    detectorSamples =
                        FloatArray(frameSampleCount),
                )
            )
        }
    }

    fun addProcessor(processor: AudioProcessor): Boolean {
        if (released.get()) return false
        return processors.addIfAbsent(processor)
    }

    fun removeProcessor(processor: AudioProcessor): Boolean = processors.remove(processor)

    fun clearProcessors() = processors.clear()

    /*
 * Phase C - installation d'un moteur d'enhancement.
 *
 * Le changement de moteur se fait hors capture active
 * afin d'éviter une course avec le thread audio.
 */
fun setAudioEnhancer(
    enhancer: AudioEnhancer?,
): Boolean {
    synchronized(lifecycleLock) {

        if (
    released.get() ||
    state == State.RELEASED
) {
    Log.w(
        TAG,
        "Impossible d'installer AudioEnhancer : GuardianAudioEngine est libéré."
    )

    return false
}

        if (
            state == State.STARTING ||
            state == State.RUNNING ||
            state == State.PAUSING ||
            state == State.STOPPING
        ) {
            Log.w(
                TAG,
                "Impossible de changer AudioEnhancer pendant une capture active."
            )

            return false
        }

        try {
            audioEnhancer?.release()
        } catch (error: Throwable) {
            Log.w(
                TAG,
                "Erreur pendant la libération de l'ancien AudioEnhancer.",
                error
            )
        }

        audioEnhancer =
            enhancer

        lastEnhancementProcessingTimeNanos =
            0L

        lastEnhancementApplied =
            false

        return true
    }
}

fun clearAudioEnhancer(): Boolean =
    setAudioEnhancer(null)

    fun start(): Boolean {
        synchronized(lifecycleLock) {
            if (released.get()) {
                reportError(IllegalStateException("GuardianAudioEngine est déjà libéré."), false)
                return false
            }
            if (!hasRecordAudioPermission()) {
                setState(State.ERROR)
                reportError(SecurityException("La permission RECORD_AUDIO est absente."), false)
                return false
            }
            if (state == State.STARTING || state == State.RUNNING) return true
            if (
                captureThread?.isAlive == true ||
                processingThread?.isAlive == true ||
                enhancementThread?.isAlive == true
            ) {
                reportError(IllegalStateException("Un ancien thread audio est encore actif."), true)
                return false
            }

            resetRuntimeForStart()
            setState(State.STARTING)
            enhancementThread = createEnhancementThread()
            processingThread = createProcessingThread()
            captureThread = createCaptureThread()

            return try {
                enhancementThread?.start()
                processingThread?.start()
                captureThread?.start()
                true
            } catch (error: Throwable) {
                stopRequested.set(true)
                setState(State.ERROR)
                reportError(error, false)
                false
            }
        }
    }

    fun stop() = stopInternal(State.IDLE, false)

    fun pause() {
        synchronized(lifecycleLock) {
            if (released.get() || state == State.IDLE || state == State.PAUSED || state == State.RELEASED) return
            setState(State.PAUSING)
        }
        stopInternal(State.PAUSED, true)
    }

    fun resume(): Boolean {
        synchronized(lifecycleLock) {
            if (released.get()) return false
            if (state == State.RUNNING || state == State.STARTING) return true
            if (state != State.PAUSED) return start()
        }
        return start()
    }

    fun release() {
    if (!released.compareAndSet(false, true)) return

    stopInternal(
        State.RELEASED,
        false
    )

    clearProcessors()

    try {
        audioEnhancer?.release()
    } catch (error: Throwable) {
        Log.w(
            TAG,
            "Erreur pendant la libération de AudioEnhancer.",
            error
        )
    } finally {
        audioEnhancer =
            null
    }

    lastEnhancementProcessingTimeNanos =
        0L

    lastEnhancementApplied =
        false

    drainQueueToPool()
    framePool.clear()

    setState(
        State.RELEASED
    )
}

    fun isRunning(): Boolean = state == State.RUNNING
    fun isPaused(): Boolean = state == State.PAUSED
    fun isReleased(): Boolean = released.get()
    fun getState(): State = state
    fun getSampleRateHz(): Int = configuration.sampleRateHz
    fun getFrameDurationMs(): Int = configuration.frameDurationMs
    fun getFrameSampleCount(): Int = frameSampleCount
    fun getLastStatistics(): Statistics? = lastStatistics
    fun getRuntimeAudioProfile(): RuntimeAudioProfile? = runtimeAudioProfile

    /*
     * Valeur instantanée mise à jour à chaque frame acoustique.
     * Contrairement à Statistics (émises périodiquement), ce getter permet
     * d'associer le COMMIT ASR au niveau acoustique réellement courant.
     */
    fun getCurrentSpeechLevelName(): String =
        lastSpeechLevel.name

    @Volatile private var externalNeuralSpeechHint = false

    fun setVoiceActivityHint(active: Boolean) {
        // Acoustic activity is NOT independent lexical evidence.
        // This hint may route spatial processing; never re-arm Far Voice.
        externalNeuralSpeechHint = active
        spatialAudio.setVoiceActivityHint(active)
    }

    internal fun beginFocusShadowUtterance() = spatialAudio.beginFocusShadowUtterance()
    internal fun endFocusShadowUtterance(): GuardianVoiceFocusEngine.ShadowUtterance? =
        if (isRouteEvidenceValid() && getVoiceFocusMode() != GuardianVoiceFocusMode.OFF)
            spatialAudio.endFocusShadowUtterance() else null

    internal fun getVoiceFocusSnapshot(): GuardianVoiceFocusEngine.FocusSnapshot? =
        spatialAudio.getVoiceFocusSnapshot()

    internal fun getVoiceFocusMode(): GuardianVoiceFocusMode =
        configuration.voiceFocusMode

    fun isRouteEvidenceValid(): Boolean =
        runtimeRouteEvidenceValid

    fun getRouteEvidenceInvalidReason(): String? =
        runtimeRouteEvidenceInvalidReason

    /*
     * start() démarre historiquement les threads de manière asynchrone.
     * Cette attente bornée ne change pas ce contrat ; elle permet uniquement
     * aux couches qui ont besoin d'une capture réellement ouverte (AUTO
     * learning, promotion, exploration) de confirmer l'état RUNNING.
     */
    fun awaitRunning(
        timeoutMs: Long = 3_000L,
    ): Boolean {
        require(timeoutMs in 100L..30_000L)

        val deadlineNanos =
            SystemClock.elapsedRealtimeNanos() +
                timeoutMs * NANOS_PER_MILLISECOND

        while (
            SystemClock.elapsedRealtimeNanos() <
            deadlineNanos
        ) {
            when (state) {
                State.RUNNING ->
                    return true

                State.ERROR,
                State.IDLE,
                State.RELEASED ->
                    return false

                else ->
                    Thread.sleep(10L)
            }
        }

        return state ==
            State.RUNNING
    }

    private fun resetRuntimeForStart() {
        stopRequested.set(false)
        pauseRequested.set(false)
        restartCount = 0
        lastCaptureElapsedRealtimeNanos = null
        lastCaptureGapMs = null
        maximumCaptureGapMs = 0L
        currentAdaptiveGain = 1.0f
        spatialAudio.reset()
        speechConditionerPreviousInput = 0.0f
        lastSpeechConditionerApplied = false
        lastSpeechConditionerInputRms = 0.0f
        lastSpeechConditionerOutputRms = 0.0f

        monoHighPassPreviousInput = 0.0f
        monoHighPassPreviousOutput = 0.0f
        lastRawRms = 0.0f
        lastFilteredRms = 0.0f
        lastOutputRms = 0.0f
        estimatedNoiseFloor = 0.0f
        ultraWeakQuietFloor.reset()
        externalNeuralSpeechHint = false
        lastNoiseFloorCandidateRms = 0.0f
        lastNoiseFloorClampActive = false
        lastSnrDb = 0.0f
        speechLikely = false
        lastSpeechLevel = AcousticSpeechLevel.NOISE
        lastGainEligible = false
        gainHoldFramesRemaining = 0
        consecutiveGainEligibleFrames = 0
        consecutiveDistantSpeechFrames = 0
        distantSpeechHoldFramesRemaining = 0

        ultraWeakSpeechEvidence = 0.0f
        ultraWeakAcquisitionActive = false
        farVoiceAcquisitionLocked = false
        farVoiceRearmRequired = false
        farVoiceRearmNoiseFrames = 0
        farVoiceCurrentEventLexicallyConfirmed = false
        farVoiceRearmNeedsIndependentSpeech = false
        farVoiceNoiseReleaseFrames = 0
        farVoiceDiagnosticFramesRemaining = 0
        farVoiceDiagnosticSource = "NONE"
        activeFarVoiceEventId = 0L
        activeFarVoiceSource = FarVoiceSource.NONE
        lastFarVoicePhase = FarVoicePhase.NONE
        lastStatistics = null
        runtimeAudioProfile = null
        runtimeCaptureChangeCount.set(0L)
        runtimeCaptureSignature = null
        // Une route invalide reste invalide dans CETTE instance.
        // Seul un nouveau moteur construit après probe peut la réarmer.
        audioRecordingCallback = null
        audioRoutingChangedListener = null
        lastEnhancementProcessingTimeNanos = 0L
        lastEnhancementApplied = false
        
        lastEnhancementInputRms =
            0.0f

        lastEnhancementOutputRms =
            0.0f

        enhancementProcessingTimeTotalNanos.set(
            0L
        )

        enhancementProcessedFrameCount.set(
            0L
        )

        enhancementProcessingTimesIndex =
            0

        enhancementProcessingTimesCount =
            0

        enhancementProcessingTimesNanos.fill(
            0L
        )

        enhancementP95Scratch.fill(
            0L
        )

/*
 * Nouvelle session audio :
 * le circuit breaker C est réarmé.
 *
 * Si GTCRN a été coupé pendant la session précédente,
 * il pourra être retesté proprement après reset().
 */
speechEnhancementSessionHealthy =
    true

enhancementRoute =
    EnhancementRoute.BYPASS

enhancementRouteReason =
    "SESSION_START"

enhancementNoisyFrames =
    0

enhancementQuietFrames =
    0

enhancementSpeechHoldFrames =
    0

enhancementDifficultSpeechFrames =
    0

enhancementDifficultSpeechEventId =
    0L

enhancementLastGtcrnFarVoiceEventId =
    0L

        drainEnhancementQueueToPool()
        enhancementMetadataQueue.clear()
        enhancementResetRequested.set(false)
        enhancedDroppedFrameCount.set(0L)

        try {
    audioEnhancer?.reset()
} catch (error: Throwable) {
    Log.e(
        TAG,
        "Erreur pendant le reset de AudioEnhancer.",
        error
    )
}
        drainQueueToPool()
    }

    private fun createCaptureThread() = Thread({ captureLoop() }, CAPTURE_THREAD_NAME).apply {
        isDaemon = true
        uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { _, error ->
            setState(State.ERROR)
            reportError(error, false)
            stopRequested.set(true)
            stopAudioRecordSafely()
        }
    }

    private fun createEnhancementThread() = Thread({ enhancementLoop() }, ENHANCEMENT_THREAD_NAME).apply {
        isDaemon = true
        uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { _, error ->
            Log.e(TAG, "Erreur du worker GTCRN asynchrone.", error)
            tripSpeechEnhancementCircuit(
                reason = "exception worker GTCRN asynchrone",
                cause = error,
            )
        }
    }

    private fun createProcessingThread() = Thread({ processingLoop() }, PROCESSING_THREAD_NAME).apply {
        isDaemon = true
        uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { _, error ->
            setState(State.ERROR)
            reportError(error, false)
            stopRequested.set(true)
            stopAudioRecordSafely()
        }
    }

    private fun captureLoop() {
        trySetThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        var attempt = 0

        while (!shouldStop()) {
            when (runCaptureSession()) {
                SessionResult.STOPPED -> break
                SessionResult.FATAL_ERROR -> {
                    setState(State.ERROR)
                    break
                }
                SessionResult.RECOVERABLE_ERROR -> {
                    if (attempt >= configuration.maximumRestartAttempts) {
                        setState(State.ERROR)
                        reportError(AudioCaptureException("Nombre maximal de redémarrages atteint."), false)
                        break
                    }
                    attempt += 1
                    restartCount = attempt
                    val delayMs = calculateRestartDelayMs(attempt)
                    Log.w(TAG, "Redémarrage AudioRecord $attempt/${configuration.maximumRestartAttempts} dans ${delayMs} ms.")
                    if (!waitInterruptibly(delayMs)) break
                }
            }
        }

        releaseAudioRecordSafely()

        synchronized(lifecycleLock) {
            
    /*
     * Le thread de capture est terminé,
     * mais le thread de traitement peut encore vider frameQueue.
     *
     * On ne publie donc PAS IDLE / PAUSED ici.
     * stopInternal() publiera l'état final uniquement
     * après avoir joint captureThread ET processingThread.
     */
    captureThread = null
    }
}

    private fun processingLoop() {
    trySetThreadPriority(
        Process.THREAD_PRIORITY_AUDIO
    )

    var intervalStartedAtMs =
        SystemClock.elapsedRealtime()

    var intervalSquaredSum =
        0.0

    var intervalSampleCount =
        0L

    var intervalPeak =
        0.0f

    var intervalClippedSamples =
        0

    var intervalLimiterEngagedSamples =
        0

    while (
        !shouldStop() ||
        frameQueue.isNotEmpty()
    ) {

        val pooledFrame =
            try {
                frameQueue.poll(
                    PROCESSING_POLL_TIMEOUT_MS,
                    TimeUnit.MILLISECONDS
                )
            } catch (
                error: InterruptedException
            ) {
                Thread.currentThread()
                    .interrupt()

                break
            } ?: continue

        try {

            /*
 * Phase C2 - frontière streaming atomique.
 *
 * Les métadonnées doivent avancer exactement
 * au même rythme que le flux PCM amélioré.
 */
val currentFrameMetadata =
    EnhancementFrameMetadata(
        capturedAtElapsedRealtimeNanos =
            pooledFrame
                .capturedAtElapsedRealtimeNanos,

        sequenceNumber =
            pooledFrame.sequenceNumber,

        frameRms =
            pooledFrame.frameRms,

        speechLevel =
            pooledFrame.speechLevel,

        speechLikely =
            pooledFrame.speechLikely,

        gainEligible =
            pooledFrame.gainEligible,

        snrDb =
            pooledFrame.snrDb,

        noiseFloorRms =
            pooledFrame.noiseFloorRms,

        farVoiceEventId =
            pooledFrame.farVoiceEventId,

        farVoiceSource =
            pooledFrame.farVoiceSource,

        farVoicePhase =
            pooledFrame.farVoicePhase,
        )

/*
 * Guardian Voice V4.5 - Dual Lane réellement découplée.
 *
 * Truth/Nemotron avance immédiatement. GTCRN reçoit seulement une copie
 * sur sa propre file secondaire et ne peut plus ralentir processingLoop().
 */
updateEnhancementRoute(
    metadata = currentFrameMetadata
)

val enhancementActive =
    configuration.speechEnhancementEnabled &&
        enhancementRoute == EnhancementRoute.GTCRN &&
        audioEnhancer != null &&
        speechEnhancementSessionHealthy

val enhancementExpected = if (enhancementActive) {
    enqueueEnhancementWork(
        samples = pooledFrame.samples,
        sampleCount = pooledFrame.sampleCount,
        metadata = currentFrameMetadata,
    )
} else false

/*
 * La frame Truth n'attend aucune sortie neurale. La seconde vue légère
 * SPEECH_CONDITIONER peut encore être publiée immédiatement ; une sortie
 * GTCRN plus tardive remplacera cette vue dans l'historique Rescue.
 */
var enhancedFrameMetadata: EnhancementFrameMetadata? = null
var enhancedSampleCount = 0
var enhancedSource = "NONE"

/*
 * Speech Conditioner : seconde vue uniquement.
 *
 * Il ne colore plus jamais la Truth Lane. Lorsque GTCRN n'a pas produit
 * de vue pour cette frame et qu'une parole distante est identifiée,
 * on crée une candidate Enhanced indépendante.
 */
if (
    enhancedSampleCount <= 0 &&
    currentFrameMetadata.speechLevel ==
        AcousticSpeechLevel.DISTANT_SPEECH
) {
    pooledFrame.samples.copyInto(
        destination =
            pooledFrame.enhancedSamples,

        destinationOffset =
            0,

        startIndex =
            0,

        endIndex =
            pooledFrame.sampleCount,
    )

    val conditioned =
        applyDistantSpeechConditioner(
            samples =
                pooledFrame.enhancedSamples,

            sampleCount =
                pooledFrame.sampleCount,

            shouldCondition =
                true,
        )

    if (conditioned) {
        enhancedFrameMetadata =
            currentFrameMetadata

        enhancedSampleCount =
            pooledFrame.sampleCount

        enhancedSource =
            "SPEECH_CONDITIONER"
    }
} else {
    /*
     * Maintient l'état temporel du conditioner sans modifier la Truth Lane.
     */
    applyDistantSpeechConditioner(
        samples =
            pooledFrame.samples,

        sampleCount =
            pooledFrame.sampleCount,

        shouldCondition =
            false,
    )
}

/*
 * Truth Lane : Adaptive Gain + Soft Limiter uniquement.
 *
 * GTCRN et Speech Conditioner ne peuvent plus modifier ce buffer.
 */
val gainInputRms =
    calculateFloatRms(
        samples =
            pooledFrame.samples,

        sampleCount =
            pooledFrame.sampleCount,
    )

val adaptiveGain =
    updateAdaptiveGain(
        frameRms =
            gainInputRms,

        speechLevel =
            currentFrameMetadata.speechLevel,

        speechLikely =
            currentFrameMetadata.speechLikely,

        gainEligible =
            currentFrameMetadata.gainEligible,
    )

var limiterEngagedSampleCount =
    0

for (
    index in
    0 until pooledFrame.sampleCount
) {
    val truthSample =
        pooledFrame.samples[index]

    val amplifiedSample =
        truthSample *
            adaptiveGain

    if (
        abs(amplifiedSample) >
            SOFT_LIMITER_THRESHOLD
    ) {
        limiterEngagedSampleCount +=
            1
    }

    pooledFrame.samples[index] =
        applySoftLimiter(
            amplifiedSample
        )
}

/*
 * Niveau réellement envoyé au chemin primaire Nemotron.
 */
lastOutputRms =
    calculateFloatRms(
        samples =
            pooledFrame.samples,

        sampleCount =
            pooledFrame.sampleCount,
    )

            if (
                configuration.farVoiceDiagnosticEnabled &&
                farVoiceDiagnosticFramesRemaining > 0
            ) {

    Log.i(
        TAG,
        "Far Voice DIAGNOSTIC : " +
            "source=$farVoiceDiagnosticSource, " +
            "remaining=$farVoiceDiagnosticFramesRemaining, " +
            "acousticRms=${"%.5f".format(currentFrameMetadata.frameRms)}, " +
            "gainInputRms=${"%.5f".format(gainInputRms)}, " +
            "enhancementRoute=${enhancementRoute.name}, " +
            "enhancementSource=$enhancedSource, " +
            "enhancementApplied=${enhancedSampleCount > 0}, " +
            "snrDb=${"%.2f".format(currentFrameMetadata.snrDb)}, " +
            "speechLevel=${currentFrameMetadata.speechLevel.name}, " +
            "gainEligible=${currentFrameMetadata.gainEligible}, " +
            "gain=${"%.2f".format(adaptiveGain)}, " +
            "outputRms=${"%.5f".format(lastOutputRms)}, " +
            "sequence=${currentFrameMetadata.sequenceNumber}"
    )

    farVoiceDiagnosticFramesRemaining -= 1

    if (farVoiceDiagnosticFramesRemaining <= 0) {
        farVoiceDiagnosticFramesRemaining = 0
        farVoiceDiagnosticSource = "NONE"

        Log.i(
            TAG,
            "Far Voice DIAGNOSTIC END"
        )
    }
}

            val frame =
                AudioFrame(
                    samples =
                        pooledFrame.samples,

                    sampleCount =
                        pooledFrame.sampleCount,

                    sampleRateHz =
                        configuration.sampleRateHz,

                    capturedAtElapsedRealtimeNanos =
                        currentFrameMetadata
                            .capturedAtElapsedRealtimeNanos,

                    sequenceNumber =
                        currentFrameMetadata.sequenceNumber,

                    enhancedSamples =
                        pooledFrame.enhancedSamples,

                    enhancedSampleCount =
                        enhancedSampleCount,

                    enhancedCapturedAtElapsedRealtimeNanos =
                        enhancedFrameMetadata
                            ?.capturedAtElapsedRealtimeNanos
                            ?: 0L,

                    enhancedSequenceNumber =
                        enhancedFrameMetadata
                            ?.sequenceNumber
                            ?: 0L,

                    enhancementApplied =
                        enhancedSampleCount > 0,

                    enhancementSource =
                        enhancedSource,

                    rawSamples =
                        pooledFrame.rawSamples,

                    rawSampleCount =
                        pooledFrame.sampleCount,

                    /*
                     * V3-A :
                     * detectorSamples suit sa propre timeline
                     * de capture. C'est volontaire : ce flux
                     * représente toujours le signal pré-gain
                     * réellement capté, même si un enhancer
                     * streaming introduit un retard plus tard.
                     */
                    detectorSamples =
                        pooledFrame.detectorSamples,

                    detectorSampleCount =
                        pooledFrame.sampleCount,

                    detectorCapturedAtElapsedRealtimeNanos =
                        pooledFrame
                            .capturedAtElapsedRealtimeNanos,

                    detectorSequenceNumber =
                        pooledFrame.sequenceNumber,

                    speechLevel =
                        currentFrameMetadata.speechLevel.name,

                    snrDb =
                        currentFrameMetadata.snrDb,

                    adaptiveGain =
                        adaptiveGain,
                    enhancementExpected = enhancementExpected,

                    acousticRms =
                        currentFrameMetadata.frameRms,

                    acousticNoiseFloorRms =
                        currentFrameMetadata.noiseFloorRms,

                    farVoiceEventId =
                        currentFrameMetadata.farVoiceEventId,

                    farVoiceSource =
                        currentFrameMetadata.farVoiceSource,

                    farVoicePhase =
                        currentFrameMetadata.farVoicePhase,
                )

            for (
                processor in processors
            ) {
                try {
                    processor.onAudioFrame(
                        frame
                    )
                } catch (
                    error: Throwable
                ) {
                    Log.e(
                        TAG,
                        "Erreur d'un AudioProcessor.",
                        error
                    )

                    notifyProcessorError(
                        processor,
                        error,
                        true
                    )
                }
            }

            processedFrameCount
                .incrementAndGet()

           intervalLimiterEngagedSamples +=
                limiterEngagedSampleCount

            for (
                index in
                0 until pooledFrame.sampleCount
            ) {

                val sample =
                    pooledFrame.samples[index]

                val absoluteSample =
                    abs(sample)

                intervalSquaredSum +=
                    sample.toDouble() *
                        sample.toDouble()

                intervalSampleCount +=
                    1L

                intervalPeak =
                    max(
                        intervalPeak,
                        absoluteSample
                    )

                if (
                    absoluteSample >=
                        CLIPPING_THRESHOLD
                ) {
                    intervalClippedSamples +=
                        1
                }
            }

            val nowMs =
                SystemClock.elapsedRealtime()

            if (
                nowMs -
                    intervalStartedAtMs >=
                configuration
                    .statisticsIntervalMs
            ) {

                emitStatistics(
                    intervalSquaredSum,
                    intervalSampleCount,
                    intervalPeak,
                    intervalClippedSamples,
                    intervalLimiterEngagedSamples,
                )

                intervalStartedAtMs =
                    nowMs

                intervalSquaredSum =
                    0.0

                intervalSampleCount =
                    0L

                intervalPeak =
                    0.0f

                intervalClippedSamples =
                    0

                intervalLimiterEngagedSamples =
                    0
            }

        } finally {

            /*
             * Même lorsqu'une sortie Enhancement
             * n'est pas encore prête, cette PooledFrame
             * d'entrée peut être rendue au pool.
             *
             * L'enhancer possède son propre état/buffering.
             */
            recycleFrame(
                pooledFrame
            )
        }
    }

    drainQueueToPool()

/*
 * C2.6 - frontière de fin de session Enhancement.
 *
 * À ce stade :
 * - AudioRecord est arrêté ;
 * - toutes les PooledFrame déjà présentes dans frameQueue
 *   ont été consommées ;
 * - aucune nouvelle entrée ne peut arriver ;
 * - les éventuelles métadonnées restantes correspondent
 *   uniquement au résidu interne du moteur streaming.
 *
 * Guardian choisit volontairement de ne PAS publier
 * un flush partiel à l'arrêt.
 */
enhancementMetadataQueue.clear()

try {
    audioEnhancer?.reset()
} catch (error: Throwable) {
    Log.w(
        TAG,
        "Erreur pendant le reset de fin de session AudioEnhancer.",
        error,
    )
}

synchronized(
    lifecycleLock
) {
    processingThread =
        null
}
}

    private fun runCaptureSession(): SessionResult {
        val recorder = try {
            createAudioRecord()
        } catch (error: SecurityException) {
            reportError(error, false)
            return SessionResult.FATAL_ERROR
        } catch (error: Throwable) {
            reportError(error, true)
            return SessionResult.RECOVERABLE_ERROR
        }

        audioRecord = recorder
        if (restartCount > 0) {
            invalidateRuntimeRouteEvidence("AUDIO_RECORD_RESTART_REQUIRES_NEW_PROBE")
            if (configuration.voiceFocusMode != GuardianVoiceFocusMode.OFF ||
                configuration.spatialSteeringAllowed) {
                invalidateRuntimeSpatialTrust("AUDIO_RECORD_RESTART_REQUIRES_NEW_PROBE")
            }
        }
        return try {
            val microphoneControls =
    applyPreferredMicrophoneControls(
        recorder
    )

registerRuntimeAudioMonitor(
    recorder = recorder,
    microphoneControls = microphoneControls,
)

recorder.startRecording()

if (
    recorder.recordingState !=
    AudioRecord.RECORDSTATE_RECORDING
) {
    throw AudioCaptureException(
        "AudioRecord n'est pas passé à RECORDSTATE_RECORDING."
    )
}

        refreshRuntimeAudioProfile(
            recorder = recorder,
            microphoneControls = microphoneControls,
        )

        initializeRuntimeAudioMonitorBaseline(
            recorder = recorder,
        )

            restartCount = 0
            setState(State.RUNNING)

            startStereoDiagnosticCaptureIfNeeded()

            Log.i(
                TAG,
                "Capture démarrée : " +
                    "source=${configuration.audioSource}, " +
                    "sampleRate=${configuration.sampleRateHz}, " +
                    "frameSamples=$frameSampleCount, " +
                    "channels=${recorder.channelCount}, " +
                    "stereoSelection=${stereoChannelSelectionName()}"
            )
            readAudioFrames(recorder)
        } catch (error: SecurityException) {
            reportError(error, false)
            SessionResult.FATAL_ERROR
        } catch (error: Throwable) {
            val recoverable = !shouldStop()
            reportError(error, recoverable)
            if (recoverable) SessionResult.RECOVERABLE_ERROR else SessionResult.STOPPED
        } finally {
            finishStereoDiagnosticCapture()
            releaseAudioRecordSafely(recorder)
        }
    }

        private fun applyPreferredMicrophoneControls(
    recorder: AudioRecord,
): MicrophoneControlResult {

    if (
        Build.VERSION.SDK_INT <
        Build.VERSION_CODES.Q
    ) {
        return MicrophoneControlResult(
            directionRequested = false,
            directionApplied = false,
            fieldDimensionRequested = false,
            fieldDimensionApplied = false,
        )
    }

    var directionRequested = false
    var directionApplied = false

    var fieldRequested = false
    var fieldApplied = false

    if (
        configuration
            .preferredMicrophoneDirectionEnabled
    ) {
        directionRequested = true

        directionApplied =
            runCatching {
                recorder
                    .setPreferredMicrophoneDirection(
                        MicrophoneDirection
                            .MIC_DIRECTION_TOWARDS_USER
                    )
            }.onFailure { error ->
                Log.w(
                    TAG,
                    "Direction microphone impossible.",
                    error
                )
            }.getOrDefault(false)
    }

    if (
        configuration
            .preferredMicrophoneFieldEnabled
    ) {
        fieldRequested = true

        fieldApplied =
            runCatching {
                recorder
                    .setPreferredMicrophoneFieldDimension(
                        configuration
                            .preferredMicrophoneFieldDimension
                    )
            }.onFailure { error ->
                Log.w(
                    TAG,
                    "Focalisation microphone impossible.",
                    error
                )
            }.getOrDefault(false)
    }

    Log.i(
        TAG,
        "Contrôle microphone : " +
            "directionRequested=$directionRequested, " +
            "directionApplied=$directionApplied, " +
            "fieldRequested=$fieldRequested, " +
            "fieldApplied=$fieldApplied, " +
            "fieldDimension=" +
            configuration
                .preferredMicrophoneFieldDimension
    )

    return MicrophoneControlResult(
        directionRequested =
            directionRequested,

        directionApplied =
            directionApplied,

        fieldDimensionRequested =
            fieldRequested,

        fieldDimensionApplied =
            fieldApplied,
    )
}

    private fun registerRuntimeAudioMonitor(
        recorder: AudioRecord,
        microphoneControls: MicrophoneControlResult,
    ) {
        var completeMonitorInstalled = false

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val callback =
                    object : AudioManager.AudioRecordingCallback() {
                        override fun onRecordingConfigChanged(
                            configs: MutableList<AudioRecordingConfiguration>,
                        ) {
                            handleRuntimeAudioConfigurationChange(
                                recorder = recorder,
                                microphoneControls = microphoneControls,
                                trigger = "RECORDING_CONFIGURATION_CHANGED",
                            )
                        }
                    }

                recorder.registerAudioRecordingCallback(
                    applicationContext.mainExecutor,
                    callback,
                )

                audioRecordingCallback = callback
                completeMonitorInstalled = true
            } catch (error: Throwable) {
                Log.w(
                    TAG,
                    "Runtime Audio Monitor : callback de configuration indisponible.",
                    error,
                )
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                val listener =
                    AudioRouting.OnRoutingChangedListener {
                        handleRuntimeAudioConfigurationChange(
                            recorder = recorder,
                            microphoneControls = microphoneControls,
                            trigger = "ROUTING_CHANGED",
                        )
                    }

                recorder.addOnRoutingChangedListener(
                    listener,
                    null,
                )

                audioRoutingChangedListener = listener
            } catch (error: Throwable) {
                Log.w(
                    TAG,
                    "Runtime Audio Monitor : listener de routing indisponible.",
                    error,
                )
            }
        }

        if (!completeMonitorInstalled) {
            invalidateRuntimeRouteEvidence(
                reason = "RUNTIME_MONITOR_UNAVAILABLE",
            )
        }

        if (
            (configuration.spatialSteeringAllowed ||
                configuration.voiceFocusMode != GuardianVoiceFocusMode.OFF) &&
            !completeMonitorInstalled
        ) {
            invalidateRuntimeSpatialTrust(
                reason = "RUNTIME_MONITOR_UNAVAILABLE",
            )
        }
    }

    private fun unregisterRuntimeAudioMonitor(
        recorder: AudioRecord,
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val callback = audioRecordingCallback

            if (callback != null) {
                runCatching {
                    recorder.unregisterAudioRecordingCallback(callback)
                }.onFailure { error ->
                    Log.w(
                        TAG,
                        "Runtime Audio Monitor : unregister callback impossible.",
                        error,
                    )
                }
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val listener = audioRoutingChangedListener

            if (listener != null) {
                runCatching {
                    recorder.removeOnRoutingChangedListener(listener)
                }.onFailure { error ->
                    Log.w(
                        TAG,
                        "Runtime Audio Monitor : unregister routing impossible.",
                        error,
                    )
                }
            }
        }

        audioRecordingCallback = null
        audioRoutingChangedListener = null
    }

    private fun initializeRuntimeAudioMonitorBaseline(
        recorder: AudioRecord,
    ) {
        val signature = buildRuntimeCaptureSignature(recorder)
        runtimeCaptureSignature = signature

        if (signature != null) {
            Log.i(
                TAG,
                "Runtime Audio Monitor BASELINE : " +
                    runtimeCaptureSignatureDescription(signature)
            )
        }
    }

    private fun handleRuntimeAudioConfigurationChange(
        recorder: AudioRecord,
        microphoneControls: MicrophoneControlResult,
        trigger: String,
    ) {
        if (audioRecord !== recorder || shouldStop()) {
            return
        }

        val previous = runtimeCaptureSignature

        refreshRuntimeAudioProfile(
            recorder = recorder,
            microphoneControls = microphoneControls,
        )

        val current = buildRuntimeCaptureSignature(recorder)
            ?: return

        if (previous == null) {
            runtimeCaptureSignature = current
            return
        }

        if (current == previous) {
            return
        }

        runtimeCaptureSignature = current

        val changeNumber =
            runtimeCaptureChangeCount.incrementAndGet()

        val spatialIdentityChanged =
            previous.spatialIdentity() != current.spatialIdentity()

        Log.w(
            TAG,
            "Runtime Audio Monitor CHANGE #$changeNumber : " +
                "trigger=$trigger, " +
                "spatialIdentityChanged=$spatialIdentityChanged, " +
                "before={${runtimeCaptureSignatureDescription(previous)}}, " +
                "after={${runtimeCaptureSignatureDescription(current)}}"
        )

        /*
         * Même si la nouvelle route reste parfaitement exploitable pour la
         * protection vocale, elle n'est plus la route qualifiée par les probes
         * qui ont servi à nommer cette session d'apprentissage.
         */
        invalidateRuntimeRouteEvidence(
            reason = "AUDIO_CAPTURE_PATH_CHANGED:$trigger",
        )

        if (
            spatialIdentityChanged &&
            (configuration.spatialSteeringAllowed ||
                configuration.voiceFocusMode != GuardianVoiceFocusMode.OFF)
        ) {
            invalidateRuntimeSpatialTrust(
                reason = "AUDIO_CAPTURE_PATH_CHANGED:$trigger",
            )
        }
    }

    private fun invalidateRuntimeRouteEvidence(
        reason: String,
    ) {
        if (!runtimeRouteEvidenceValid) {
            return
        }

        runtimeRouteEvidenceValid =
            false

        runtimeRouteEvidenceInvalidReason =
            reason

        Log.w(
            TAG,
            "Runtime Route Evidence invalidée : reason=$reason"
        )
    }

    private fun invalidateRuntimeSpatialTrust(
        reason: String,
    ) {
        if (runtimeSpatialTrustInvalidated) {
            return
        }

        runtimeSpatialTrustInvalidated = true
        spatialAudio.invalidateRuntimeSteering(reason)

        Log.w(
            TAG,
            "Runtime Spatial Trust invalidée : reason=$reason"
        )
    }

    private fun buildRuntimeCaptureSignature(
        recorder: AudioRecord,
    ): RuntimeCaptureSignature? {
        val activeMicrophoneMappings =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                runCatching {
                    recorder.activeMicrophones
                }.getOrDefault(emptyList())
                    .flatMap { microphone ->
                        val microphoneIdentity =
                            "${microphone.id}:${microphone.description}:" +
                                "${microphone.group}:${microphone.indexInTheGroup}"

                        if (microphone.channelMapping.isEmpty()) {
                            listOf("$microphoneIdentity:NO_MAPPING")
                        } else {
                            microphone.channelMapping.map { mapping ->
                                "$microphoneIdentity:channel=${mapping.first}:" +
                                    when (mapping.second) {
                                        MicrophoneInfo.CHANNEL_MAPPING_DIRECT ->
                                            "DIRECT"

                                        MicrophoneInfo.CHANNEL_MAPPING_PROCESSED ->
                                            "PROCESSED"

                                        else ->
                                            "UNKNOWN_${mapping.second}"
                                    }
                            }
                        }
                    }
                    .sorted()
            } else {
                emptyList()
            }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val recordingConfiguration =
                runCatching {
                    recorder.activeRecordingConfiguration
                }.getOrNull()

            if (recordingConfiguration != null) {
                val device = recordingConfiguration.audioDevice
                val hardwareFormat = recordingConfiguration.format
                val clientFormat = recordingConfiguration.clientFormat

                return RuntimeCaptureSignature(
                    clientSilenced = recordingConfiguration.isClientSilenced,
                    audioDeviceId = device?.id,
                    audioDeviceType = device?.type,
                    hardwareSampleRateHz = hardwareFormat.sampleRate,
                    hardwareChannelCount = hardwareFormat.channelCount,
                    hardwareEncoding = hardwareFormat.encoding,
                    clientSampleRateHz = clientFormat.sampleRate,
                    clientChannelCount = clientFormat.channelCount,
                    clientEncoding = clientFormat.encoding,
                    activeEffects = recordingConfiguration.effects
                        .map { it.name }
                        .sorted(),
                    clientEffects = recordingConfiguration.clientEffects
                        .map { it.name }
                        .sorted(),
                    activeMicrophoneMappings = activeMicrophoneMappings,
                )
            }
        }

        val routedDevice =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                runCatching { recorder.routedDevice }.getOrNull()
            } else {
                null
            }

        return RuntimeCaptureSignature(
            clientSilenced = false,
            audioDeviceId = routedDevice?.id,
            audioDeviceType = routedDevice?.type,
            hardwareSampleRateHz = null,
            hardwareChannelCount = null,
            hardwareEncoding = null,
            clientSampleRateHz = recorder.sampleRate,
            clientChannelCount = recorder.channelCount,
            clientEncoding = recorder.audioFormat,
            activeEffects = emptyList(),
            clientEffects = emptyList(),
            activeMicrophoneMappings = activeMicrophoneMappings,
        )
    }

    private fun runtimeCaptureSignatureDescription(
        signature: RuntimeCaptureSignature,
    ): String =
        "silenced=${signature.clientSilenced}, " +
            "device=${signature.audioDeviceId}/${signature.audioDeviceType}, " +
            "hardware=${signature.hardwareSampleRateHz}Hz/" +
            "${signature.hardwareChannelCount}ch/enc=${signature.hardwareEncoding}, " +
            "client=${signature.clientSampleRateHz}Hz/" +
            "${signature.clientChannelCount}ch/enc=${signature.clientEncoding}, " +
            "effects=${signature.activeEffects}, " +
            "clientEffects=${signature.clientEffects}, " +
            "mics=${signature.activeMicrophoneMappings}"

        private fun refreshRuntimeAudioProfile(
    recorder: AudioRecord,
    microphoneControls: MicrophoneControlResult,
    ) {
    val activeMicrophones =
        if (
            Build.VERSION.SDK_INT >=
            Build.VERSION_CODES.P
        ) {
            runCatching {
                recorder.activeMicrophones
            }.onFailure { error ->
                Log.w(
                    TAG,
                    "Lecture des microphones actifs impossible.",
                    error
                )
            }.getOrDefault(
                emptyList()
            )
        } else {
            emptyList()
        }

    val runtimeMicrophones =
        activeMicrophones.map { microphone ->

            val channelMapping =
                microphone.channelMapping
                    .map { mapping ->
                        val channel =
                            mapping.first

                        val mappingType =
                            when (
                                mapping.second
                            ) {
                                MicrophoneInfo
                                    .CHANNEL_MAPPING_DIRECT ->
                                    "DIRECT"

                                MicrophoneInfo
                                    .CHANNEL_MAPPING_PROCESSED ->
                                    "PROCESSED"

                                else ->
                                    "UNKNOWN_${mapping.second}"
                            }

                        "channel=$channel:$mappingType"
                    }

            ActiveMicrophoneRuntime(
                id =
                    microphone.id,

                description =
                    microphone.description
                        ?.toString()
                        ?.takeIf {
                            it.isNotBlank()
                        }
                        ?: "Microphone ${microphone.id}",

                deviceType =
                    microphone.type,

                deviceTypeName =
                    audioDeviceTypeName(
                        microphone.type
                    ),

                location =
                    microphone.location,

                group =
                    microphone.group,

                indexInGroup =
                    microphone.indexInTheGroup,

                directionality =
                    microphone.directionality,

                channelMapping =
                    channelMapping,
            )
        }

    /*
     * Android peut insérer des effets de capture en fonction du preset
     * audio et du constructeur. On observe donc la configuration
     * réellement active au lieu de supposer que seul Guardian traite
     * le signal.
     */
    val activeRecordingConfiguration =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching {
                recorder.activeRecordingConfiguration
            }.getOrNull()
        } else {
            null
        }

    val activePreprocessingEffects =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            activeRecordingConfiguration
                ?.effects
                ?.map { descriptor ->
                    descriptor.name
                }
                ?: emptyList()
        } else {
            emptyList()
        }

    val clientPreprocessingEffects =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            activeRecordingConfiguration
                ?.clientEffects
                ?.map { descriptor ->
                    descriptor.name
                }
                ?: emptyList()
        } else {
            emptyList()
        }

    val unprocessedSourceSupported =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val audioManager =
                applicationContext.getSystemService(
                    Context.AUDIO_SERVICE
                ) as? android.media.AudioManager

            audioManager
                ?.getProperty(
                    android.media.AudioManager
                        .PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED
                )
                ?.equals(
                    "true",
                    ignoreCase = true,
                ) == true
        } else {
            false
        }

    val profile =
        RuntimeAudioProfile(
            audioSource =
                recorder.audioSource,

            audioSourceName =
                audioSourceName(
                    recorder.audioSource
                ),

            sampleRateHz =
                recorder.sampleRate,

            channelCount =
                recorder.channelCount,

            activeMicrophones =
                runtimeMicrophones,

            activeMicrophoneCount =
                runtimeMicrophones.size,

            noiseSuppressorRequested =
                configuration
                    .androidNoiseSuppressorEnabled,

            noiseSuppressorActive =
                noiseSuppressor
                    ?.enabled == true,

            androidAgcRequested =
                configuration
                    .androidAgcEnabled,

            androidAgcActive =
                automaticGainControl
                    ?.enabled == true,

            directionRequested =
            microphoneControls
                .directionRequested,

            directionApplied =
                microphoneControls
                    .directionApplied,

            fieldDimensionRequested =
             microphoneControls
                .fieldDimensionRequested,

            fieldDimensionApplied =
                microphoneControls
                 .fieldDimensionApplied,

            activePreprocessingEffects =
                activePreprocessingEffects,

            clientPreprocessingEffects =
                clientPreprocessingEffects,

            unprocessedSourceSupported =
                unprocessedSourceSupported,

            multiChannelActive =
                recorder.channelCount > 1,
        )

    runtimeAudioProfile =
        profile

    Log.i(
    TAG,
    "Runtime Audio Profile : " +
        "source=${profile.audioSourceName}, " +
        "sampleRate=${profile.sampleRateHz}, " +
        "channels=${profile.channelCount}, " +
        "activeMics=${profile.activeMicrophoneCount}, " +
        "NS=${profile.noiseSuppressorActive}, " +
        "AGC=${profile.androidAgcActive}, " +
        "direction=${profile.directionApplied}, " +
        "field=${profile.fieldDimensionApplied}, " +
        "unprocessedSupported=${profile.unprocessedSourceSupported}, " +
        "activeEffects=${profile.activePreprocessingEffects}, " +
        "clientEffects=${profile.clientPreprocessingEffects}, " +
        "multiChannel=${profile.multiChannelActive}"
        )

    profile.activeMicrophones.forEach { microphone ->
        Log.i(
            TAG,
            "Microphone actif Guardian : " +
                "id=${microphone.id}, " +
                "description=${microphone.description}, " +
                "type=${microphone.deviceTypeName}, " +
                "location=${microphone.location}, " +
                "group=${microphone.group}, " +
                "index=${microphone.indexInGroup}, " +
                "directionality=${microphone.directionality}, " +
                "mapping=${microphone.channelMapping}"
        )
    }
}

    private fun readAudioFrames(recorder: AudioRecord): SessionResult {
        val stereoActive =
            configuration.inputChannelMask ==
                AudioFormat.CHANNEL_IN_STEREO

        val pcmBuffer =
            ShortArray(
                if (stereoActive) {
                    frameSampleCount * 2
                } else {
                    frameSampleCount
                }
            )

        val monoBuffer =
            if (stereoActive) {
                ShortArray(frameSampleCount)
            } else {
                pcmBuffer
            }

        while (!shouldStop()) {
            val readCount =
                recorder.read(
                    pcmBuffer,
                    0,
                    pcmBuffer.size,
                    AudioRecord.READ_BLOCKING
                )

            when {
                readCount > 0 -> {
                    /*
                     * Temporal Segmenter V2.
                     *
                     * Cette horloge est prise immédiatement à la sortie de
                     * AudioRecord.read(). Elle représente la fin de la zone PCM
                     * que nous venons réellement de capturer, avant spatial,
                     * filtre, gain ou mise en file.
                     */
                    val captureReadEndedAtElapsedRealtimeNanos =
                        SystemClock.elapsedRealtimeNanos()

                    recordCaptureTiming(
                        captureReadEndedAtElapsedRealtimeNanos
                    )

                    if (stereoActive) {
                        val evenSampleCount =
                            readCount - (readCount % 2)

                        if (evenSampleCount <= 0) {
                            continue
                        }

                        writeStereoDiagnosticSamples(
                            pcmBuffer = pcmBuffer,
                            sampleCount = evenSampleCount,
                        )

                        val frameCount =
                            (evenSampleCount / 2)
                                .coerceAtMost(frameSampleCount)

                        val publishedFrameCount =
        if (
        configuration.stereoChannelSelection == STEREO_CHANNEL_ALIGNED_MIX ||
        configuration.stereoChannelSelection == STEREO_CHANNEL_VOICE_FOCUS
    ) {
        spatialAudio.processInterleavedPcm16(
            interleavedPcm =
                pcmBuffer,

            interleavedSampleCount =
                evenSampleCount,

            outputMono =
                monoBuffer,
        )
    } else {
        for (
            frameIndex in
            0 until frameCount
        ) {
            val left =
                pcmBuffer[
                    frameIndex * 2
                ]

            val right =
                pcmBuffer[
                    frameIndex * 2 + 1
                ]

            monoBuffer[frameIndex] =
                when (
                    configuration
                        .stereoChannelSelection
                ) {
                    STEREO_CHANNEL_RIGHT ->
                        right

                    STEREO_CHANNEL_MIX ->
                        (
                            (
                                left.toInt() +
                                    right.toInt()
                                ) /
                                2
                            )
                            .toShort()

                    else ->
                        left
                }
        }

        frameCount
    }

if (
    publishedFrameCount > 0
) {
    enqueuePcmFrame(
        pcmBuffer = monoBuffer,
        sampleCount = publishedFrameCount,
        captureReadEndedAtElapsedRealtimeNanos =
            captureReadEndedAtElapsedRealtimeNanos,
    )
}

                        
                    } else {
                        enqueuePcmFrame(
                            pcmBuffer = pcmBuffer,
                            sampleCount = readCount,
                            captureReadEndedAtElapsedRealtimeNanos =
                                captureReadEndedAtElapsedRealtimeNanos,
                        )
                    }
                }

                readCount == 0 ->
                    Thread.yield()

                readCount == AudioRecord.ERROR_DEAD_OBJECT ->
                    return recoverableReadError(
                        "AudioRecord a perdu son objet natif.",
                        readCount
                    )

                readCount == AudioRecord.ERROR_INVALID_OPERATION ->
                    return recoverableReadError(
                        "Opération AudioRecord invalide.",
                        readCount
                    )

                readCount == AudioRecord.ERROR_BAD_VALUE -> {
                    reportError(
                        AudioCaptureException(
                            "Paramètre AudioRecord invalide.",
                            readCount
                        ),
                        false
                    )
                    return SessionResult.FATAL_ERROR
                }

                readCount == AudioRecord.ERROR ->
                    return recoverableReadError(
                        "Erreur AudioRecord générique.",
                        readCount
                    )

                else ->
                    return recoverableReadError(
                        "Code AudioRecord inattendu.",
                        readCount
                    )
            }
        }

        return SessionResult.STOPPED
    }

    private fun stereoChannelSelectionName(): String =
    when (
        configuration.stereoChannelSelection
    ) {
        STEREO_CHANNEL_RIGHT ->
            "RIGHT"

        STEREO_CHANNEL_MIX ->
            "MIX"

        STEREO_CHANNEL_ALIGNED_MIX ->
            "ALIGNED_MIX"

        STEREO_CHANNEL_VOICE_FOCUS ->
            "VOICE_FOCUS"

        else ->
            "LEFT"
    }

    private fun startStereoDiagnosticCaptureIfNeeded() {
        if (
            !configuration.stereoDiagnosticCaptureEnabled ||
            configuration.inputChannelMask != AudioFormat.CHANNEL_IN_STEREO
        ) {
            return
        }

        finishStereoDiagnosticCapture()

        val externalFilesDirectory =
            applicationContext.getExternalFilesDir(null)
                ?: run {
                    Log.w(
                        TAG,
                        "MULTIMIC DIAGNOSTIC : externalFilesDir indisponible."
                    )
                    return
                }

        val diagnosticsDirectory =
            File(
                externalFilesDirectory,
                "guardian-diagnostics"
            )

        if (
            !diagnosticsDirectory.exists() &&
            !diagnosticsDirectory.mkdirs()
        ) {
            Log.w(
                TAG,
                "MULTIMIC DIAGNOSTIC : impossible de créer guardian-diagnostics."
            )
            return
        }

        try {
            val file =
                File(
                    diagnosticsDirectory,
                    "guardian-multimic-${System.currentTimeMillis()}-${stereoChannelSelectionName()}.wav"
                )

            val stream =
                RandomAccessFile(
                    file,
                    "rw"
                )

            stream.setLength(0L)
            writeStereoWavHeader(
                stream = stream,
                dataBytes = 0L,
            )
            stream.seek(44L)

            stereoDiagnosticCapture =
                StereoDiagnosticCapture(
                    file = file,
                    stream = stream,
                )

            Log.i(
                TAG,
                "MULTIMIC DIAGNOSTIC START : " +
                    "path=${file.absolutePath}, " +
                    "selection=${stereoChannelSelectionName()}, " +
                    "sampleRate=${configuration.sampleRateHz}, channels=2"
            )
        } catch (error: Throwable) {
            Log.e(
                TAG,
                "MULTIMIC DIAGNOSTIC : impossible de démarrer la capture WAV stéréo.",
                error
            )
            finishStereoDiagnosticCapture()
        }
    }

    private fun writeStereoDiagnosticSamples(
        pcmBuffer: ShortArray,
        sampleCount: Int,
    ) {
        val capture =
            stereoDiagnosticCapture
                ?: return

        val boundedCount =
            sampleCount.coerceAtMost(pcmBuffer.size)

        if (boundedCount <= 0) {
            return
        }

        try {
            val bytes =
                ByteArray(boundedCount * PCM16_BYTES_PER_SAMPLE)

            for (index in 0 until boundedCount) {
                val sample =
                    pcmBuffer[index].toInt()

                bytes[index * 2] =
                    (sample and 0xFF).toByte()

                bytes[index * 2 + 1] =
                    ((sample ushr 8) and 0xFF).toByte()
            }

            capture.stream.write(bytes)
            capture.dataBytes +=
                bytes.size.toLong()
        } catch (error: Throwable) {
            Log.e(
                TAG,
                "MULTIMIC DIAGNOSTIC : écriture WAV impossible.",
                error
            )
            finishStereoDiagnosticCapture()
        }
    }

    private fun finishStereoDiagnosticCapture() {
        val capture =
            stereoDiagnosticCapture
                ?: return

        stereoDiagnosticCapture =
            null

        try {
            writeStereoWavHeader(
                stream = capture.stream,
                dataBytes = capture.dataBytes,
            )
            capture.stream.fd.sync()
        } catch (error: Throwable) {
            Log.w(
                TAG,
                "MULTIMIC DIAGNOSTIC : finalisation WAV incomplète.",
                error
            )
        } finally {
            try {
                capture.stream.close()
            } catch (_: Throwable) {
            }
        }

        val durationMs =
            if (configuration.sampleRateHz > 0) {
                capture.dataBytes * 1_000L /
                    (
                        configuration.sampleRateHz.toLong() *
                            2L *
                            PCM16_BYTES_PER_SAMPLE.toLong()
                    )
            } else {
                0L
            }

        Log.i(
            TAG,
            "MULTIMIC DIAGNOSTIC SAVED : " +
                "path=${capture.file.absolutePath}, " +
                "duration=${durationMs}ms, " +
                "bytes=${capture.dataBytes}, channels=2"
        )
    }

    private fun writeStereoWavHeader(
        stream: RandomAccessFile,
        dataBytes: Long,
    ) {
        val boundedDataBytes =
            dataBytes.coerceAtMost(0xFFFF_FFFFL)

        val riffSize =
            (36L + boundedDataBytes)
                .coerceAtMost(0xFFFF_FFFFL)

        val channels =
            2

        val bitsPerSample =
            16

        val blockAlign =
            channels * (bitsPerSample / 8)

        val byteRate =
            configuration.sampleRateHz * blockAlign

        stream.seek(0L)
        stream.writeBytes("RIFF")
        writeLittleEndianInt(stream, riffSize)
        stream.writeBytes("WAVE")
        stream.writeBytes("fmt ")
        writeLittleEndianInt(stream, 16L)
        writeLittleEndianShort(stream, 1)
        writeLittleEndianShort(stream, channels)
        writeLittleEndianInt(
            stream,
            configuration.sampleRateHz.toLong()
        )
        writeLittleEndianInt(
            stream,
            byteRate.toLong()
        )
        writeLittleEndianShort(
            stream,
            blockAlign
        )
        writeLittleEndianShort(
            stream,
            bitsPerSample
        )
        stream.writeBytes("data")
        writeLittleEndianInt(
            stream,
            boundedDataBytes
        )
    }

    private fun writeLittleEndianInt(
        stream: RandomAccessFile,
        value: Long,
    ) {
        stream.write((value and 0xFF).toInt())
        stream.write(((value ushr 8) and 0xFF).toInt())
        stream.write(((value ushr 16) and 0xFF).toInt())
        stream.write(((value ushr 24) and 0xFF).toInt())
    }

    private fun writeLittleEndianShort(
        stream: RandomAccessFile,
        value: Int,
    ) {
        stream.write(value and 0xFF)
        stream.write((value ushr 8) and 0xFF)
    }

    private fun recoverableReadError(message: String, code: Int): SessionResult {
        reportError(AudioCaptureException(message, code), true)
        return SessionResult.RECOVERABLE_ERROR
    }

    private fun calculateFrameRms(
    pcmBuffer: ShortArray,
    sampleCount: Int,
): Float {
    if (sampleCount <= 0) {
        return 0.0f
    }

    var squaredSum = 0.0

    for (index in 0 until sampleCount) {
        val normalized =
            pcmBuffer[index].toFloat() /
                PCM16_NORMALIZATION_FACTOR

        squaredSum +=
            normalized.toDouble() *
                normalized.toDouble()
    }

    return sqrt(
        squaredSum / sampleCount.toDouble()
    )
        .toFloat()
        .coerceIn(0.0f, 1.0f)
}

    private fun preprocessMonoPcm(
    pcmBuffer: ShortArray,
    sampleCount: Int,
): FloatArray {

    if (sampleCount <= 0) {
        return FloatArray(0)
    }

    val output =
        FloatArray(sampleCount)

    var previousInput =
        monoHighPassPreviousInput

    var previousOutput =
        monoHighPassPreviousOutput

    for (index in 0 until sampleCount) {

        val input =
            pcmBuffer[index]
                .toFloat() /
                PCM16_NORMALIZATION_FACTOR

        /*
         * Filtre passe-haut 1er ordre :
         *
         * y[n] =
         *   alpha *
         *   (y[n-1] + x[n] - x[n-1])
         *
         * Il retire principalement :
         * - composante DC ;
         * - très basses fréquences ;
         * - vibrations lentes.
         */
        val filtered =
            MONO_HIGH_PASS_ALPHA *
                (
                    previousOutput +
                        input -
                        previousInput
                )

        output[index] =
            filtered.coerceIn(
                -1.0f,
                1.0f
            )

        previousInput =
            input

        previousOutput =
            filtered
    }

    monoHighPassPreviousInput =
        previousInput

    monoHighPassPreviousOutput =
        previousOutput

    return output
}

    private fun calculateFloatRms(
    samples: FloatArray,
    sampleCount: Int,
): Float {

    if (
        sampleCount <= 0 ||
        samples.isEmpty()
    ) {
        return 0.0f
    }

    val boundedCount =
        sampleCount.coerceAtMost(
            samples.size
        )

    var squaredSum =
        0.0

    for (index in 0 until boundedCount) {
        val sample =
            samples[index]

        squaredSum +=
            sample.toDouble() *
                sample.toDouble()
    }

    return sqrt(
        squaredSum /
            boundedCount.toDouble()
    )
        .toFloat()
        .coerceIn(
            0.0f,
            1.0f
        )
}

       private fun applySoftLimiter(
    sample: Float,
): Float {

    val absoluteSample =
        abs(sample)

    /*
     * Zone normale :
     * aucune modification de la parole.
     */
    if (
        absoluteSample <=
            SOFT_LIMITER_THRESHOLD
    ) {
        return sample
    }

    /*
     * Au-dessus du seuil, compression progressive
     * vers OUTPUT_LIMIT.
     *
     * Contrairement à un hard clip, on ne coupe
     * pas brutalement la forme d'onde.
     */
    val excess =
        absoluteSample -
            SOFT_LIMITER_THRESHOLD

    val availableHeadroom =
        OUTPUT_LIMIT -
            SOFT_LIMITER_THRESHOLD

    val compressedExcess =
        availableHeadroom *
            (
                excess /
                    (
                        excess +
                            SOFT_LIMITER_KNEE
                    )
                )

    val limited =
        (
            SOFT_LIMITER_THRESHOLD +
                compressedExcess
            )
            .coerceAtMost(
                OUTPUT_LIMIT
            )

    return if (sample >= 0.0f) {
        limited
    } else {
        -limited
    }
} 

    private val ultraWeakQuietFloor = GuardianUltraWeakQuietFloor()

    private fun analyzeAcousticFrame(
    frameRms: Float,
): AcousticAnalysis {

    val safeRms =
        frameRms.coerceIn(
            0.0f,
            1.0f
        )

    // Référence physique indépendante du noiseFloor V2 clampé (inchangé).
    // Une parole neurale reconnue gèle immédiatement son apprentissage.
    val ultraWeakQuietFloorWasCalibrated =
        ultraWeakQuietFloor.calibrated

    ultraWeakQuietFloor.observe(
        rms = safeRms,
        speechProtected = externalNeuralSpeechHint || farVoiceAcquisitionLocked ||
            lastSpeechLevel != AcousticSpeechLevel.NOISE,
    )

    if (
        !ultraWeakQuietFloorWasCalibrated &&
        ultraWeakQuietFloor.calibrated
    ) {
        Log.i(
            TAG,
            "Far Voice V2.5 QUIET FLOOR READY : " +
                "floorRms=${"%.6f".format(ultraWeakQuietFloor.floorRms)}"
        )
    }

    /*
     * Premier échantillon acoustique.
     *
     * On initialise une estimation prudente du bruit
     * afin d'éviter une division par zéro.
     */
    if (estimatedNoiseFloor <= 0.0f) {
        val candidateNoiseFloor =
            safeRms

        lastNoiseFloorCandidateRms =
            candidateNoiseFloor

        lastNoiseFloorClampActive =
            candidateNoiseFloor < MINIMUM_NOISE_FLOOR_RMS ||
                candidateNoiseFloor > MAXIMUM_NOISE_FLOOR_RMS

        estimatedNoiseFloor =
            candidateNoiseFloor.coerceIn(
                MINIMUM_NOISE_FLOOR_RMS,
                MAXIMUM_NOISE_FLOOR_RMS
            )
    } else {

        val currentNoiseFloor =
            estimatedNoiseFloor

        val ratioToNoise =
            if (currentNoiseFloor > 0.0f) {
                safeRms / currentNoiseFloor
            } else {
                1.0f
            }

        /*
         * Guardian Noise Floor V2 - protection speech-aware.
         *
         * Le noise floor peut continuer à descendre si l'ambiance devient
         * réellement plus silencieuse, mais il ne doit plus apprendre
         * progressivement une voix déjà identifiée comme parole/Far Voice.
         *
         * Aucun seuil de détection n'est modifié ici.
         */
        val protectNoiseFloorFromSpeech =
            farVoiceAcquisitionLocked ||
                lastSpeechLevel != AcousticSpeechLevel.NOISE

        val adaptationRate =
            when {
                /*
                 * Une baisse réelle du niveau ambiant doit rester suivie
                 * rapidement, même pendant un événement vocal.
                 */
                safeRms < currentNoiseFloor ->
                    configuration.noiseFloorFall

                /*
                 * Pendant une parole déjà crédible, on interdit uniquement
                 * la remontée du noise floor. Cela évite d'absorber les
                 * syllabes faibles dans l'estimation du bruit.
                 */
                protectNoiseFloorFromSpeech ->
                    VERY_SLOW_NOISE_FLOOR_RISE

                /*
                 * Hors parole, le suivi normal du bruit reste inchangé.
                 */
                ratioToNoise <= NOISE_TRACKING_RATIO ->
                    configuration.noiseFloorRise

                /*
                 * Une hausse forte non encore identifiée comme parole reste
                 * suivie très lentement, comme avant.
                 */
                else ->
                    VERY_SLOW_NOISE_FLOOR_RISE
            }

        /*
         * C12 : même calcul qu'avant, mais on conserve
         * la valeur non clampée pour le diagnostic.
         */
        val candidateNoiseFloor =
            estimatedNoiseFloor +
                (safeRms - estimatedNoiseFloor) *
                    adaptationRate

        lastNoiseFloorCandidateRms =
            candidateNoiseFloor

        lastNoiseFloorClampActive =
            candidateNoiseFloor < MINIMUM_NOISE_FLOOR_RMS ||
                candidateNoiseFloor > MAXIMUM_NOISE_FLOOR_RMS

        estimatedNoiseFloor =
            candidateNoiseFloor.coerceIn(
                MINIMUM_NOISE_FLOOR_RMS,
                MAXIMUM_NOISE_FLOOR_RMS
            )
    }

    val safeNoiseFloor =
        estimatedNoiseFloor.coerceAtLeast(
            MINIMUM_NOISE_FLOOR_RMS
        )

    val signalToNoiseRatio =
        safeRms /
            safeNoiseFloor

    val calculatedSnrDb =
        if (safeRms <= RMS_EPSILON) {
            0.0f
        } else {
            (
                20.0 *
                    log10(
                        signalToNoiseRatio
                            .coerceAtLeast(
                                RMS_EPSILON
                            )
                            .toDouble()
                    )
                )
                .toFloat()
        }

    val clearSpeech =
    safeRms >=
        configuration.minimumSpeechRms &&
        calculatedSnrDb >=
        configuration.speechSnrThresholdDb

    /*
     * CLEAR_SPEECH est une preuve forte déjà existante et indépendante de la
     * voie UltraWeak. Elle peut donc réarmer un cycle mis en quarantaine sans
     * modifier le moindre seuil Far Voice.
     */
    if (
        farVoiceRearmRequired &&
        farVoiceRearmNeedsIndependentSpeech &&
        clearSpeech
    ) {
        farVoiceRearmRequired = false
        farVoiceRearmNeedsIndependentSpeech = false
        farVoiceRearmNoiseFrames = 0
        ultraWeakSpeechEvidence = 0.0f
    }

    var farVoiceAcquiredThisFrame =
    false

/*
 * Far Voice Classifier V2
 *
 * Principe :
 *
 * 1. ACQUISITION
 *    Guardian reste strict pour entrer en DISTANT_SPEECH.
 *    On conserve les seuils historiques afin de ne pas
 *    augmenter brutalement les faux positifs.
 *
 * 2. MAINTIEN
 *    Une fois une voix distante confirmée, Guardian garde
 *    une courte mémoire acoustique.
 *
 *    Une syllabe faible ou une transition entre phonèmes
 *    ne doit plus faire retomber immédiatement la voix
 *    distante en NOISE.
 */

/*
 * Critère strict d'acquisition.
 */
val distantSpeechCandidate =
    !clearSpeech &&
        safeRms >=
            DISTANT_SPEECH_MINIMUM_RMS &&
        calculatedSnrDb >=
            DISTANT_SPEECH_MINIMUM_SNR_DB

            /*
 * Far Voice V2.1 - Ultra-Weak Acquisition.
 *
 * Cette voie ne remplace pas V2.
 *
 * Une frame faible crédible apporte seulement une petite
 * quantité de preuve. Plusieurs frames cohérentes sont
 * nécessaires avant d'autoriser DISTANT_SPEECH.
 */
val observedQuietRms = ultraWeakQuietFloor.floorRms
val observedQuietSnrDb = if (observedQuietRms > 0f && safeRms > 0f) {
    (20.0 * log10((safeRms / observedQuietRms).toDouble())).toFloat()
} else Float.NEGATIVE_INFINITY

// V2.4 : mêmes preuves temporelles et garde-fous de durée que V2.1.
// Le seuil historique reste un chemin de compatibilité, jamais modifié.
val legacyUltraWeakCandidate = !clearSpeech &&
    safeRms >= ULTRA_WEAK_MINIMUM_RMS &&
    calculatedSnrDb >= ULTRA_WEAK_MINIMUM_SNR_DB

val adaptiveUltraWeakCandidate = !clearSpeech &&
    ultraWeakQuietFloor.calibrated &&
    safeRms >= maxOf(ULTRA_WEAK_ADAPTIVE_HARD_FLOOR_RMS,
        observedQuietRms * ULTRA_WEAK_ADAPTIVE_MIN_RATIO) &&
    observedQuietSnrDb >= ULTRA_WEAK_ADAPTIVE_MIN_SNR_DB &&
    !ultraWeakQuietFloor.recentUnstableNoise

/*
 * V7: les preuves adaptatives doivent être suffisamment indépendantes du
 * plancher de bruit. La voie STRICT_V2 conserve ses seuils historiques.
 * Ces seuils sont des réglages de validation terrain, non des constantes
 * universelles de reconnaissance à 5 m.
 */
val ultraWeakSpeechCandidate = legacyUltraWeakCandidate || adaptiveUltraWeakCandidate

val previousUltraWeakActive =
    ultraWeakAcquisitionActive

when {
    clearSpeech -> {
        ultraWeakSpeechEvidence =
            0.0f

        ultraWeakAcquisitionActive =
            false
    }

    distantSpeechCandidate -> {
        /*
         * Une frame qui satisfait déjà les critères stricts
         * V2 constitue une preuve forte pour la mémoire V2.1.
         *
         * Cela permet notamment de survivre à une acquisition
         * distante où les frames fortes ne sont pas parfaitement
         * consécutives.
         */
        ultraWeakSpeechEvidence =
            (
                ultraWeakSpeechEvidence +
                    ULTRA_WEAK_STRONG_EVIDENCE
            ).coerceAtMost(
                ULTRA_WEAK_MAXIMUM_SCORE
            )
    }

    ultraWeakSpeechCandidate -> {
        /*
         * Signal trop faible pour V2 strict,
         * mais suffisamment au-dessus du bruit pour être
         * conservé comme preuve temporelle.
         */
        ultraWeakSpeechEvidence =
            (
                ultraWeakSpeechEvidence +
                    ULTRA_WEAK_WEAK_EVIDENCE
            ).coerceAtMost(
                ULTRA_WEAK_MAXIMUM_SCORE
            )
    }

    else -> {
        /*
         * Pas de reset brutal.
         *
         * Les preuves disparaissent progressivement afin qu'une
         * petite transition entre deux syllabes ne détruise pas
         * toute l'acquisition en cours.
         */
        ultraWeakSpeechEvidence =
            (
                ultraWeakSpeechEvidence -
                    ULTRA_WEAK_EVIDENCE_DECAY
            ).coerceAtLeast(
                0.0f
            )
    }
}

/*
 * Far Voice V2.1 ne peut effectuer une nouvelle
 * acquisition que lorsque la mémoire Far Voice
 * précédente est terminée.
 *
 * Cela évite les réacquisitions successives pendant
 * qu'un même événement vocal est déjà maintenu.
 */
ultraWeakAcquisitionActive =
    !clearSpeech &&
        !farVoiceAcquisitionLocked &&
        ultraWeakSpeechEvidence >=
            ULTRA_WEAK_ACQUISITION_SCORE

            

/*
 * Log uniquement au franchissement du seuil.
 *
 * On ne logue PAS chaque frame pour ne pas polluer logcat.
 */
if (
    ultraWeakAcquisitionActive &&
    !previousUltraWeakActive &&
    !farVoiceRearmRequired
) {
    Log.i(
        TAG,
        "Far Voice V2.1 ULTRA-WEAK ACQUIRED : " +
            "rms=${"%.5f".format(safeRms)}, " +
            "snrDb=${"%.2f".format(calculatedSnrDb)}, " +
            "score=${"%.2f".format(ultraWeakSpeechEvidence)}, " +
            "adaptive=$adaptiveUltraWeakCandidate, observedFloor=${"%.6f".format(observedQuietRms)}, " +
            "observedSnr=${"%.2f".format(observedQuietSnrDb)}dB"
    )
}

/*
 * Une acquisition V2.1 réussie verrouille
 * l'événement Far Voice courant.
 */
if (
    ultraWeakAcquisitionActive &&
    !farVoiceAcquisitionLocked &&
    !farVoiceRearmRequired
) {
    beginFarVoiceEvent(
        source =
            FarVoiceSource.ULTRA_WEAK_V2_1,
    )

    farVoiceAcquiredThisFrame =
        true

    farVoiceAcquisitionLocked =
        true

    farVoiceDiagnosticFramesRemaining =
        if (configuration.farVoiceDiagnosticEnabled) {
            FAR_VOICE_DIAGNOSTIC_FRAMES
        } else {
            0
        }

    farVoiceDiagnosticSource =
        if (configuration.farVoiceDiagnosticEnabled) {
            "V2.1_ULTRA_WEAK"
        } else {
            "NONE"
        }

    Log.i(
        TAG,
        "Far Voice LOCKED : source=V2.1_ULTRA_WEAK"
    )
}

/*
 * Confirmation initiale.
 *
 * CLEAR_SPEECH annule naturellement la recherche
 * Far Voice : la parole est déjà suffisamment forte.
 */
when {
    clearSpeech -> {
        consecutiveDistantSpeechFrames = 0
        distantSpeechHoldFramesRemaining = 0
    }

    distantSpeechCandidate -> {
        consecutiveDistantSpeechFrames =
            (
                consecutiveDistantSpeechFrames + 1
            ).coerceAtMost(
                DISTANT_SPEECH_CONFIRMATION_FRAMES
            )

        /*
         * Dès que l'acquisition est confirmée,
         * on arme la mémoire Far Voice.
         */
        if (
    consecutiveDistantSpeechFrames >=
        DISTANT_SPEECH_CONFIRMATION_FRAMES
) {
    distantSpeechHoldFramesRemaining =
    maxOf(
        distantSpeechHoldFramesRemaining,
        DISTANT_SPEECH_HOLD_FRAMES,
    )

    /*
     * V2 strict a confirmé un événement Far Voice.
     *
     * Le même verrou est partagé avec V2.1 :
     * tant que cet événement n'est pas réellement terminé,
     * V2.1 ne pourra pas annoncer une nouvelle acquisition.
     */
    val wasFarVoiceLocked =
    farVoiceAcquisitionLocked

val canOpenNewFarVoiceEvent =
    !farVoiceRearmRequired

/*
 * Nouvelle acquisition STRICT V2.
 *
 * On crée un nouvel eventId uniquement si aucun événement n'est déjà
 * verrouillé ET si le cycle précédent a réellement été réarmé.
 */
if (
    !wasFarVoiceLocked &&
    canOpenNewFarVoiceEvent
) {
    beginFarVoiceEvent(
        source =
            FarVoiceSource.STRICT_V2,
    )

    farVoiceAcquiredThisFrame =
        true
}

if (
    wasFarVoiceLocked ||
    canOpenNewFarVoiceEvent
) {
    farVoiceAcquisitionLocked =
        true

    farVoiceNoiseReleaseFrames =
        0

    if (!wasFarVoiceLocked) {

        farVoiceDiagnosticFramesRemaining =
            if (configuration.farVoiceDiagnosticEnabled) {
                FAR_VOICE_DIAGNOSTIC_FRAMES
            } else {
                0
            }

        farVoiceDiagnosticSource =
            if (configuration.farVoiceDiagnosticEnabled) {
                "V2_STRICT"
            } else {
                "NONE"
            }

        Log.i(
            TAG,
            "Far Voice LOCKED : source=V2_STRICT"
        )
    }
}

    ultraWeakSpeechEvidence =
        0.0f

    ultraWeakAcquisitionActive =
        false
}
    }

    else -> {
        /*
         * Une frame faible ne détruit plus immédiatement
         * l'état Far Voice.
         *
         * Le compteur d'acquisition est remis à zéro,
         * mais la mémoire déjà acquise peut survivre.
         */
        consecutiveDistantSpeechFrames = 0

        if (distantSpeechHoldFramesRemaining > 0) {
            distantSpeechHoldFramesRemaining -= 1
        }
    }
}

/*
 * Far Voice est actif :
 *
 * - soit la frame vient de satisfaire le critère strict ;
 * - soit une voix distante avait été confirmée récemment
 *   et sa courte mémoire est encore active.
 */

/*
 * Far Voice Event Lifecycle V2.3.
 *
 * Une acquisition UltraWeak ouvre une fenêtre acoustique
 * TEMPORAIRE permettant à Silero de confirmer la parole.
 *
 * Très important :
 *
 * ultraWeakSpeechCandidate ne peut plus prolonger
 * indéfiniment l'événement.
 *
 * Une simple ambiance acoustique faible ne doit donc
 * jamais conserver le même eventId pendant plusieurs
 * secondes ou plusieurs minutes.
 */
if (ultraWeakAcquisitionActive) {

    distantSpeechHoldFramesRemaining =
        maxOf(
            distantSpeechHoldFramesRemaining,
            ULTRA_WEAK_EVENT_HOLD_FRAMES,
        )
}

val distantSpeechConfirmed =
    !clearSpeech &&
        (
            (
                distantSpeechCandidate &&
                    consecutiveDistantSpeechFrames >=
                        DISTANT_SPEECH_CONFIRMATION_FRAMES
            ) ||
                ultraWeakAcquisitionActive ||
                distantSpeechHoldFramesRemaining > 0
        )

val speechLevel =
    when {
        clearSpeech ->
            AcousticSpeechLevel.CLEAR_SPEECH

        distantSpeechConfirmed ->
            AcousticSpeechLevel.DISTANT_SPEECH

        else ->
            AcousticSpeechLevel.NOISE
    }

/*
 * Fin d'événement Far Voice.
 *
 * Le verrou n'est jamais libéré sur une seule frame faible.
 * Il faut :
 *
 * - que le hold Far Voice soit réellement terminé ;
 * - que Guardian observe plusieurs frames NOISE consécutives.
 *
 * CLEAR_SPEECH ou DISTANT_SPEECH remet immédiatement
 * le compteur de sortie à zéro.
 */

val farVoiceReleaseNoiseFrame =
    speechLevel ==
        AcousticSpeechLevel.NOISE &&
        !clearSpeech &&
        !distantSpeechCandidate

if (farVoiceAcquisitionLocked) {

    if (
    farVoiceReleaseNoiseFrame &&
    distantSpeechHoldFramesRemaining <= 0
) {
        farVoiceNoiseReleaseFrames =
            (
                farVoiceNoiseReleaseFrames + 1
            ).coerceAtMost(
                FAR_VOICE_RELEASE_NOISE_FRAMES
            )

        if (
            farVoiceNoiseReleaseFrames >=
                FAR_VOICE_RELEASE_NOISE_FRAMES
        ) {
            val releasedEventWasLexicallyConfirmed =
                farVoiceCurrentEventLexicallyConfirmed

            farVoiceAcquisitionLocked =
                false

            clearFarVoiceEvent()

            farVoiceNoiseReleaseFrames =
                0

            /*
             * Soft Voice V3 :
             * - événement confirmé lexicalement : réarmement normal après
             *   retour neutre ;
             * - événement jamais confirmé : quarantaine. Le même bruit peut
             *   encore être classé faible/distant pour préserver le gain, mais
             *   il ne peut plus créer de nouveaux eventId coûteux tant qu'une
             *   preuve indépendante n'apparaît pas.
             */
            farVoiceRearmRequired =
                true

            farVoiceRearmNeedsIndependentSpeech =
                !releasedEventWasLexicallyConfirmed

            farVoiceRearmNoiseFrames =
                0

            ultraWeakSpeechEvidence =
                0.0f

            ultraWeakAcquisitionActive =
                false

        }
    } else {
        farVoiceNoiseReleaseFrames =
            0
    }
} else {
    farVoiceNoiseReleaseFrames =
        0
}

/*
 * MEMORY FIX V2 - hystérésis de réarmement.
 *
 * Aucun seuil acoustique n'est changé. On réutilise la même durée de
 * stabilité déjà exigée pour RELEASE, mais cette fois avec une contrainte
 * plus forte : la frame doit être NOISE et ne satisfaire ni V2 strict ni
 * UltraWeak. Ainsi, la même ambiance ambiguë ne peut pas RELEASE puis LOCK
 * quelques centaines de millisecondes plus tard.
 */
if (
    farVoiceRearmRequired &&
    !farVoiceRearmNeedsIndependentSpeech
) {
    val neutralRearmFrame =
        speechLevel == AcousticSpeechLevel.NOISE &&
            !clearSpeech &&
            !distantSpeechCandidate &&
            !ultraWeakSpeechCandidate &&
            distantSpeechHoldFramesRemaining <= 0

    if (neutralRearmFrame) {
        farVoiceRearmNoiseFrames =
            (
                farVoiceRearmNoiseFrames + 1
            ).coerceAtMost(
                FAR_VOICE_RELEASE_NOISE_FRAMES
            )

        if (
            farVoiceRearmNoiseFrames >=
                FAR_VOICE_RELEASE_NOISE_FRAMES
        ) {
            farVoiceRearmRequired =
                false

            farVoiceRearmNeedsIndependentSpeech =
                false

            farVoiceRearmNoiseFrames =
                0

            ultraWeakSpeechEvidence =
                0.0f

        }
    } else {
        farVoiceRearmNoiseFrames =
            0

        ultraWeakSpeechEvidence =
            0.0f
    }
}

lastSpeechLevel = speechLevel

/*
 * speechLikely reste volontairement strict.
 *
 * On ne fait PAS passer immédiatement une voix
 * distante pour une parole certaine.
 */
val detectedSpeech =
    speechLevel ==
        AcousticSpeechLevel.CLEAR_SPEECH

/*
 * Nemotron V6 : la phase Far Voice est calculée AVANT l'autorité de gain.
 * HOLD et RELEASING décrivent seulement l'hystérésis de l'événement : ils ne
 * constituent plus une preuve suffisante pour continuer à amplifier le PCM
 * primaire de Nemotron.
 */
val farVoicePhase =
    when {

        !farVoiceAcquisitionLocked ->
            FarVoicePhase.NONE

        farVoiceAcquiredThisFrame ->
            FarVoicePhase.ACQUIRED

        clearSpeech ||
            distantSpeechCandidate ->
            FarVoicePhase.SUPPORTED

        distantSpeechHoldFramesRemaining > 0 ->
            FarVoicePhase.HOLD

        else ->
            FarVoicePhase.RELEASING
    }

lastFarVoicePhase =
    farVoicePhase

/*
 * Adaptive Gain V6 :
 * - CLEAR_SPEECH reste immédiat ;
 * - DISTANT_SPEECH n'est amplifié que si la frame courante possède une
 *   autorité Far Voice DIRECTE (ACQUIRED/SUPPORTED) ;
 * - HOLD/RELEASING décroissent via le hold interne du gain mais ne le
 *   réarment plus continuellement.
 *
 * Aucun seuil RMS/SNR/UltraWeak n'est modifié.
 */
val gainEligible =
    detectedSpeech ||
        (
            speechLevel == AcousticSpeechLevel.DISTANT_SPEECH &&
                (
                    farVoicePhase == FarVoicePhase.ACQUIRED ||
                        farVoicePhase == FarVoicePhase.SUPPORTED
                )
        )

    lastSnrDb =
        calculatedSnrDb

    speechLikely =
        detectedSpeech

    lastGainEligible =
        gainEligible

    return AcousticAnalysis(
    frameRms =
        safeRms,

    noiseFloor =
        safeNoiseFloor,

    snrDb =
        calculatedSnrDb,

    speechLevel =
        speechLevel,

    speechLikely =
        detectedSpeech,

    gainEligible =
        gainEligible,

    farVoiceEventId =
        activeFarVoiceEventId,

    farVoiceSource =
        activeFarVoiceSource,

    farVoicePhase =
        farVoicePhase,
)
}


    /*
     * =============================================================
     * Guardian Speech Conditioner V1
     * =============================================================
     *
     * Objectif :
     * préserver davantage les attaques de mots, fricatives et consonnes
     * faibles sur une voix distante déjà reconnue acoustiquement.
     *
     * Ce n'est PAS :
     * - un VAD ;
     * - un nouveau seuil ;
     * - un noise gate ;
     * - un second débruiteur.
     *
     * Principe :
     * y[n] = x[n] + amount * (x[n] - x[n-1])
     *
     * Le terme (x[n] - x[n-1]) contient surtout les variations rapides.
     * On l'ajoute très légèrement au signal original.
     *
     * Un garde-fou RMS empêche le conditioner de devenir un gain déguisé :
     * l'énergie globale ne peut monter que faiblement.
     */
    private fun applyDistantSpeechConditioner(
        samples: FloatArray,
        sampleCount: Int,
        shouldCondition: Boolean,
    ): Boolean {

        val boundedCount =
            sampleCount.coerceIn(
                0,
                samples.size
            )

        if (boundedCount <= 0) {
            lastSpeechConditionerApplied =
                false

            lastSpeechConditionerInputRms =
                0.0f

            lastSpeechConditionerOutputRms =
                0.0f

            return false
        }

        val inputRms =
            calculateFloatRms(
                samples =
                    samples,

                sampleCount =
                    boundedCount,
            )

        lastSpeechConditionerInputRms =
            inputRms

        /*
         * Même lorsqu'on bypass le traitement, on met à jour previousInput.
         * Ainsi, la première frame DISTANT_SPEECH ne démarre jamais avec
         * un faux transitoire causé par un ancien zéro.
         */
        if (
            !configuration.speechConditionerEnabled ||
            !shouldCondition
        ) {
            speechConditionerPreviousInput =
                samples[boundedCount - 1]

            lastSpeechConditionerApplied =
                false

            lastSpeechConditionerOutputRms =
                inputRms

            return false
        }

        var previousInput =
            speechConditionerPreviousInput

        for (index in 0 until boundedCount) {

            val input =
                samples[index]

            val detail =
                input -
                    previousInput

            val conditioned =
                input +
                    SPEECH_CONDITIONER_DETAIL_AMOUNT *
                        detail

            samples[index] =
                conditioned.coerceIn(
                    -SPEECH_CONDITIONER_SAMPLE_LIMIT,
                    SPEECH_CONDITIONER_SAMPLE_LIMIT,
                )

            /*
             * Très important :
             * previousInput suit le signal ORIGINAL et non la sortie
             * conditionnée, sinon on construirait un filtre récursif.
             */
            previousInput =
                input
        }

        speechConditionerPreviousInput =
            previousInput

        var outputRms =
            calculateFloatRms(
                samples =
                    samples,

                sampleCount =
                    boundedCount,
            )

        /*
         * Garde-fou d'énergie.
         *
         * Le conditioner doit redistribuer légèrement l'énergie vers les
         * détails rapides, pas amplifier globalement la phrase.
         */
        val maximumAllowedRms =
            inputRms *
                SPEECH_CONDITIONER_MAX_RMS_RATIO

        if (
            inputRms > RMS_EPSILON &&
            outputRms > maximumAllowedRms &&
            outputRms > RMS_EPSILON
        ) {
            val correctionGain =
                (
                    maximumAllowedRms /
                        outputRms
                ).coerceIn(
                    0.0f,
                    1.0f
                )

            for (index in 0 until boundedCount) {
                samples[index] =
                    (
                        samples[index] *
                            correctionGain
                    ).coerceIn(
                        -SPEECH_CONDITIONER_SAMPLE_LIMIT,
                        SPEECH_CONDITIONER_SAMPLE_LIMIT,
                    )
            }

            outputRms =
                calculateFloatRms(
                    samples =
                        samples,

                    sampleCount =
                        boundedCount,
                )
        }

        lastSpeechConditionerApplied =
            true

        lastSpeechConditionerOutputRms =
            outputRms

        return true
    }


    private fun updateAdaptiveGain(
    frameRms: Float,
    speechLevel: AcousticSpeechLevel,
    speechLikely: Boolean,
    gainEligible: Boolean,
): Float {

    if (!configuration.adaptiveGainEnabled) {
        currentAdaptiveGain =
            1.0f

        gainHoldFramesRemaining =
            0

        consecutiveGainEligibleFrames =
            0

        return 1.0f
    }

    /*
     * Parole claire :
     * activation immédiate du gain.
     */
    if (speechLikely) {
        consecutiveGainEligibleFrames =
            GAIN_CONFIRMATION_FRAMES

        gainHoldFramesRemaining =
            GAIN_HOLD_FRAMES
    }

    /*
     * Voix distante crédible :
     * confirmation avant amplification.
     */
    else if (gainEligible) {
        consecutiveGainEligibleFrames =
            (
                consecutiveGainEligibleFrames +
                    1
            )
                .coerceAtMost(
                    GAIN_CONFIRMATION_FRAMES
                )

        if (
            consecutiveGainEligibleFrames >=
                GAIN_CONFIRMATION_FRAMES
        ) {
            gainHoldFramesRemaining =
                GAIN_HOLD_FRAMES
        }
    }

    /*
     * Pas de signal crédible.
     */
    else {
        consecutiveGainEligibleFrames =
            0

        if (gainHoldFramesRemaining > 0) {
            gainHoldFramesRemaining -=
                1
        }
    }

    val gainActive =
        speechLikely ||
            consecutiveGainEligibleFrames >=
                GAIN_CONFIRMATION_FRAMES ||
            gainHoldFramesRemaining > 0

    /*
     * Aucun signal à protéger :
     * retour progressif vers x1.
     */
    if (!gainActive) {
        currentAdaptiveGain +=
            (
                1.0f -
                    currentAdaptiveGain
            ) *
                NON_SPEECH_GAIN_RETURN_RATE

        currentAdaptiveGain =
            currentAdaptiveGain.coerceIn(
                configuration.minimumGain,
                configuration.maximumGain
            )

        return currentAdaptiveGain
    }

    /*
     * Pendant le HOLD, si la frame courante est du bruit,
     * on conserve temporairement le gain courant au lieu
     * de calculer un nouveau gain à partir du bruit.
     */
    if (
        speechLevel ==
            AcousticSpeechLevel.NOISE
    ) {
        return currentAdaptiveGain
    }

    /*
 * Far Voice Gain Continuity V2.
 *
 * Une fois DISTANT_SPEECH confirmé, Guardian possède déjà
 * une preuve temporelle qu'un événement vocal distant existe.
 *
 * Une syllabe peut momentanément descendre sous le plancher
 * Ultra-Weak sans signifier que l'événement vocal a disparu.
 *
 * Dans ce cas, on ne calcule surtout PAS le gain directement
 * depuis un RMS quasi nul : cela amplifierait excessivement
 * le bruit.
 *
 * On borne simplement la référence utilisée par l'Adaptive
 * Gain au plancher acoustique Ultra-Weak.
 *
 * La durée de cette assistance reste naturellement bornée
 * par la mémoire DISTANT_SPEECH existante.
 */
val effectiveRmsForGain =
    when (speechLevel) {

        AcousticSpeechLevel.DISTANT_SPEECH ->
            frameRms.coerceAtLeast(
                ULTRA_WEAK_MINIMUM_RMS
            )

        AcousticSpeechLevel.CLEAR_SPEECH -> {
            if (
                frameRms <
                    SILENCE_RMS_THRESHOLD
            ) {
                return currentAdaptiveGain
            }

            frameRms
        }

        AcousticSpeechLevel.NOISE ->
            return currentAdaptiveGain
    }

    /*
     * Objectif différent selon la qualité de parole.
     */
    val targetRmsForLevel =
        when (speechLevel) {
            AcousticSpeechLevel.CLEAR_SPEECH ->
                configuration.targetRms

            AcousticSpeechLevel.DISTANT_SPEECH ->
                DISTANT_SPEECH_TARGET_RMS

            AcousticSpeechLevel.NOISE ->
                configuration.targetRms
        }

    /*
     * Limite de gain différente :
     *
     * CLEAR  -> maximum normal (x4 actuellement)
     * DISTANT -> maximum Guardian distant (x6)
     */
    val maximumGainForLevel =
        when (speechLevel) {
            AcousticSpeechLevel.CLEAR_SPEECH ->
                configuration.maximumGain

            AcousticSpeechLevel.DISTANT_SPEECH ->
                DISTANT_SPEECH_MAXIMUM_GAIN

            AcousticSpeechLevel.NOISE ->
                configuration.maximumGain
        }

    val desiredGain =
        (
            targetRmsForLevel /
                effectiveRmsForGain
        )
            .coerceIn(
                configuration.minimumGain,
                maximumGainForLevel
            )

    /*
     * Descente rapide si le signal devient fort.
     * Montée plus progressive lorsqu'il faut amplifier.
     */
    val smoothing =
    if (
        desiredGain <
            currentAdaptiveGain
    ) {
        configuration.gainAttack
    } else {
        when (speechLevel) {
            AcousticSpeechLevel.DISTANT_SPEECH ->
                DISTANT_SPEECH_GAIN_RISE

            AcousticSpeechLevel.CLEAR_SPEECH ->
                configuration.gainRelease

            AcousticSpeechLevel.NOISE ->
                configuration.gainRelease
        }
    }

    currentAdaptiveGain +=
        (
            desiredGain -
                currentAdaptiveGain
        ) *
            smoothing

    /*
     * IMPORTANT :
     * le plafond final utilise bien le plafond
     * correspondant au niveau acoustique courant.
     */
    currentAdaptiveGain =
        currentAdaptiveGain.coerceIn(
            configuration.minimumGain,
            maximumGainForLevel
        )

    return currentAdaptiveGain
}


        private fun recordEnhancementProcessingTime(
    processingTimeNanos: Long,
) {
    if (processingTimeNanos <= 0L) {
        return
    }

    enhancementProcessingTimesNanos[
        enhancementProcessingTimesIndex
    ] =
        processingTimeNanos

    enhancementProcessingTimesIndex =
        (
            enhancementProcessingTimesIndex + 1
        ) %
            ENHANCEMENT_TIMING_WINDOW_SIZE

    if (
        enhancementProcessingTimesCount <
            ENHANCEMENT_TIMING_WINDOW_SIZE
    ) {
        enhancementProcessingTimesCount +=
            1
    }
}

        private fun calculateEnhancementP95Ms(): Double {

    val count =
        enhancementProcessingTimesCount

    if (count <= 0) {
        return 0.0
    }

    /*
     * Copie dans un buffer de travail préalloué.
     *
     * Aucune allocation mémoire n'est effectuée
     * pendant le calcul du P95.
     */
    for (index in 0 until count) {
        enhancementP95Scratch[index] =
            enhancementProcessingTimesNanos[index]
    }

    /*
     * On trie uniquement la portion contenant
     * des mesures valides.
     */
    java.util.Arrays.sort(
        enhancementP95Scratch,
        0,
        count,
    )

    /*
     * Nearest-rank P95 :
     * ceil(0.95 * count) - 1
     */
    val percentileIndex =
        (
            kotlin.math.ceil(
                count.toDouble() *
                    0.95
            ).toInt() - 1
        )
            .coerceIn(
                0,
                count - 1
            )

    return enhancementP95Scratch[
        percentileIndex
    ].toDouble() /
        1_000_000.0
}

    /*
 * Phase C1 - point d'entrée unique du Speech Enhancement.
 *
 * Tant que speechEnhancementEnabled=false,
 * cette fonction est un PASS-THROUGH strict :
 * aucune modification du signal.
 */

    /*
 * Phase C2 - Circuit breaker Speech Enhancement.
 *
 * Coupe l'enhancement pour le reste de la session courante.
 *
 * Une anomalie de C ne doit jamais devenir une panne
 * du pipeline audio principal Guardian.
 *
 * Après déclenchement :
 * - aucune ancienne metadata C n'est conservée ;
 * - l'état streaming de l'enhancer est réinitialisé ;
 * - les frames suivantes utilisent l'audio original ;
 * - une nouvelle session pourra réarmer C.
 *
 * Cette fonction est appelée depuis le processingThread.
 */

/*
 * Phase C7 - Routeur GTCRN.
 *
 * Le routage est volontairement conservateur :
 * - activation seulement après bruit de fond confirmé ;
 * - aucune transition pendant une parole active ;
 * - hold après parole ;
 * - retour au bypass après calme confirmé.
 */
private fun updateEnhancementRoute(
    metadata: EnhancementFrameMetadata,
) {
    if (
        !configuration.speechEnhancementEnabled ||
        audioEnhancer == null ||
        !speechEnhancementSessionHealthy
    ) {
        if (
            enhancementRoute !=
                EnhancementRoute.BYPASS
        ) {
            switchEnhancementRoute(
                newRoute =
                    EnhancementRoute.BYPASS,
                reason =
                    "ENHANCEMENT_UNAVAILABLE",
            )
        }

        return
    }

    if (configuration.forceSpeechEnhancement) {
        if (enhancementRoute != EnhancementRoute.GTCRN) {
            switchEnhancementRoute(EnhancementRoute.GTCRN, "EXPLICIT_NOISY_PROFILE")
        }
        return
    }

    val speechActive =
        metadata.speechLevel !=
            AcousticSpeechLevel.NOISE ||
            metadata.farVoiceEventId != 0L ||
            metadata.farVoicePhase !=
                FarVoicePhase.NONE

    if (speechActive) {
        enhancementSpeechHoldFrames =
            ENHANCEMENT_ROUTE_SPEECH_HOLD_FRAMES

        enhancementNoisyFrames =
            0

        enhancementQuietFrames =
            0

        /*
         * V4.2 - Difficult Lane.
         *
         * Une parole DISTANT_SPEECH est précisément le cas où une seconde
         * oreille neurale peut apporter de l'information. Contrairement à C7,
         * on autorise donc BYPASS -> GTCRN AU DÉBUT de cette parole après
         * confirmation courte. La Truth Lane est déjà indépendante et reste
         * publiée à chaque frame : cette transition ne peut jamais retarder
         * ni remplacer Nemotron.
         */
        val difficultFarVoiceEventId =
            metadata.farVoiceEventId

        val difficultDistantSpeech =
            metadata.speechLevel ==
                AcousticSpeechLevel.DISTANT_SPEECH &&
                difficultFarVoiceEventId > 0L &&
                /*
                 * Soft Voice V3 : Far Voice seul reste un détecteur peu coûteux.
                 * GTCRN n'est autorisé qu'après une preuve lexicale Nemotron.
                 * La Truth Lane et l'Adaptive Gain continuent avant ce point.
                 */
                externalNeuralSpeechHint &&
                difficultFarVoiceEventId !=
                    enhancementLastGtcrnFarVoiceEventId

        if (
            enhancementRoute ==
                EnhancementRoute.BYPASS &&
            difficultDistantSpeech
        ) {
            if (
                enhancementDifficultSpeechEventId !=
                    difficultFarVoiceEventId
            ) {
                enhancementDifficultSpeechEventId =
                    difficultFarVoiceEventId

                enhancementDifficultSpeechFrames =
                    0
            }

            enhancementDifficultSpeechFrames =
                (
                    enhancementDifficultSpeechFrames + 1
                ).coerceAtMost(
                    ENHANCEMENT_ROUTE_DIFFICULT_SPEECH_CONFIRMATION_FRAMES
                )

            if (
                enhancementDifficultSpeechFrames >=
                    ENHANCEMENT_ROUTE_DIFFICULT_SPEECH_CONFIRMATION_FRAMES
            ) {
                /*
                 * L'événement est consommé AVANT le switch. Même si GTCRN
                 * repasse ensuite en BYPASS alors que le même événement est
                 * encore actif, Difficult Lane ne pourra pas le rouvrir.
                 */
                enhancementLastGtcrnFarVoiceEventId =
                    difficultFarVoiceEventId

                enhancementDifficultSpeechEventId =
                    0L

                switchEnhancementRoute(
                    newRoute =
                        EnhancementRoute.GTCRN,
                    reason =
                        "DISTANT_SPEECH_DIFFICULT_LANE",
                )
            }
        } else if (
            enhancementRoute ==
                EnhancementRoute.BYPASS
        ) {
            enhancementDifficultSpeechFrames =
                0

            enhancementDifficultSpeechEventId =
                0L
        }

        return
    }

    if (
        enhancementSpeechHoldFrames > 0
    ) {
        enhancementSpeechHoldFrames -=
            1

        enhancementNoisyFrames =
            0

        enhancementQuietFrames =
            0

        enhancementDifficultSpeechFrames =
            0

        return
    }

    enhancementDifficultSpeechFrames =
        0

    val noisyEnvironment =
        metadata.noiseFloorRms >=
            configuration
                .enhancementNoiseFloorEnterRms

    val quietEnvironment =
        metadata.noiseFloorRms <=
            configuration
                .enhancementNoiseFloorExitRms

    when {
        noisyEnvironment -> {
            enhancementNoisyFrames =
                (
                    enhancementNoisyFrames + 1
                ).coerceAtMost(
                    ENHANCEMENT_ROUTE_ENTER_CONFIRMATION_FRAMES
                )

            enhancementQuietFrames =
                0
        }

        quietEnvironment -> {
            enhancementQuietFrames =
                (
                    enhancementQuietFrames + 1
                ).coerceAtMost(
                    ENHANCEMENT_ROUTE_EXIT_CONFIRMATION_FRAMES
                )

            enhancementNoisyFrames =
                0
        }

        else -> {
            if (enhancementNoisyFrames > 0) {
                enhancementNoisyFrames -=
                    1
            }

            if (enhancementQuietFrames > 0) {
                enhancementQuietFrames -=
                    1
            }
        }
    }

    when (enhancementRoute) {
        EnhancementRoute.BYPASS -> {
            if (
                enhancementNoisyFrames >=
                    ENHANCEMENT_ROUTE_ENTER_CONFIRMATION_FRAMES
            ) {
                switchEnhancementRoute(
                    newRoute =
                        EnhancementRoute.GTCRN,
                    reason =
                        "NOISE_FLOOR_CONFIRMED",
                )
            }
        }

        EnhancementRoute.GTCRN -> {
            if (
                enhancementQuietFrames >=
                    ENHANCEMENT_ROUTE_EXIT_CONFIRMATION_FRAMES
            ) {
                switchEnhancementRoute(
                    newRoute =
                        EnhancementRoute.BYPASS,
                    reason =
                        "QUIET_CONFIRMED",
                )
            }
        }
    }
}

/*
 * Transition de route uniquement à une frontière sûre.
 *
 * Le reset supprime les FIFO et l'état neural résiduel.
 * Comme les transitions sont interdites pendant la parole,
 * le résidu abandonné correspond uniquement au bruit/silence
 * situé après la période de hold.
 */
private fun switchEnhancementRoute(
    newRoute: EnhancementRoute,
    reason: String,
) {
    if (
        enhancementRoute ==
            newRoute
    ) {
        return
    }

    drainEnhancementQueueToPool()
    enhancementMetadataQueue.clear()
    enhancementResetRequested.set(true)

    enhancementRoute =
        newRoute

    enhancementRouteReason =
        reason

    enhancementNoisyFrames =
        0

    enhancementQuietFrames =
        0

    enhancementSpeechHoldFrames =
        0

    enhancementDifficultSpeechFrames =
        0

    enhancementDifficultSpeechEventId =
        0L

    Log.i(
        TAG,
        "Speech Enhancement route : " +
            "route=${newRoute.name}, " +
            "reason=$reason, " +
            "noiseFloor=${"%.5f".format(estimatedNoiseFloor)}",
    )
}

private fun tripSpeechEnhancementCircuit(
    reason: String,
    cause: Throwable? = null,
) {
    if (
        !speechEnhancementSessionHealthy
    ) {
        return
    }

    speechEnhancementSessionHealthy =
        false

    enhancementRoute =
        EnhancementRoute.BYPASS

    enhancementRouteReason =
        "CIRCUIT_BREAKER"

    enhancementNoisyFrames =
        0

    enhancementQuietFrames =
        0

    enhancementSpeechHoldFrames =
        0

    enhancementDifficultSpeechFrames =
        0

    enhancementDifficultSpeechEventId =
        0L

    enhancementLastGtcrnFarVoiceEventId =
        0L

    drainEnhancementQueueToPool()
    enhancementMetadataQueue.clear()
    enhancementResetRequested.set(true)

    if (cause != null) {
        Log.e(
            TAG,
            "Speech Enhancement désactivé pour la session : $reason",
            cause,
        )
    } else {
        Log.e(
            TAG,
            "Speech Enhancement désactivé pour la session : $reason",
        )
    }
}


private fun enqueueEnhancementWork(
    samples: FloatArray,
    sampleCount: Int,
    metadata: EnhancementFrameMetadata,
): Boolean {
    val work =
        enhancementWorkPool.poll()
            ?: run {
                /*
                 * Saturation GTCRN : on abandonne UNIQUEMENT la vue Enhanced.
                 * Truth/Nemotron continue sans aucune perte.
                 */
                enhancedDroppedFrameCount.incrementAndGet()
                return false
            }

    val bounded =
        sampleCount.coerceIn(0, work.samples.size)

    samples.copyInto(
        destination = work.samples,
        startIndex = 0,
        endIndex = bounded,
    )

    work.sampleCount = bounded
    work.metadata = metadata

    if (!enhancementWorkQueue.offer(work)) {
        recycleEnhancementWork(work)
        enhancedDroppedFrameCount.incrementAndGet()
        return false
    }
    return true
}

private fun enhancementLoop() {
    trySetThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)

    var previousEnhancementSequence: Long? = null
    while (!shouldStop() || enhancementWorkQueue.isNotEmpty()) {
        if (enhancementResetRequested.compareAndSet(true, false)) {
            enhancementMetadataQueue.clear()
            previousEnhancementSequence = null
            try {
                audioEnhancer?.reset()
            } catch (error: Throwable) {
                Log.w(TAG, "Reset GTCRN asynchrone impossible.", error)
            }
        }

        val work =
            try {
                enhancementWorkQueue.poll(
                    ENHANCEMENT_POLL_TIMEOUT_MS,
                    TimeUnit.MILLISECONDS,
                )
            } catch (error: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            } ?: continue

        try {
            val metadata = work.metadata ?: continue

            if (!speechEnhancementSessionHealthy) {
                continue
            }

            if (
                enhancementMetadataQueue.size >=
                    ENHANCEMENT_METADATA_QUEUE_MAX_ITEMS
            ) {
                tripSpeechEnhancementCircuit(
                    reason =
                        "GTCRN metadata queue overflow " +
                            "(${enhancementMetadataQueue.size}/$ENHANCEMENT_METADATA_QUEUE_MAX_ITEMS)"
                )
                continue
            }

            if (previousEnhancementSequence != null &&
                metadata.sequenceNumber != previousEnhancementSequence!! + 1L) {
                enhancementMetadataQueue.clear()
                audioEnhancer?.reset()
            }
            previousEnhancementSequence = metadata.sequenceNumber
            enhancementMetadataQueue.addLast(metadata)

            val result =
                applySpeechEnhancementAsync(
                    samples = work.samples,
                    sampleCount = work.sampleCount,
                )

            if (!result.applied) {
                enhancementMetadataQueue.removeLastOrNull()
                continue
            }

            if (!result.outputReady) {
                continue
            }

            val outputMetadata =
                enhancementMetadataQueue.removeFirstOrNull()
                    ?: continue

            val count =
                result.sampleCount.coerceIn(0, result.samples.size)

            if (count <= 0) continue

            val published =
                EnhancedAudioFrame(
                    samples = result.samples.copyOf(count),
                    sampleCount = count,
                    capturedAtElapsedRealtimeNanos =
                        outputMetadata.capturedAtElapsedRealtimeNanos,
                    sequenceNumber = outputMetadata.sequenceNumber,
                    source = "GTCRN",
                )

            for (processor in processors) {
                try {
                    processor.onEnhancedAudioFrame(published)
                } catch (error: Throwable) {
                    Log.e(TAG, "Erreur AudioProcessor Enhanced.", error)
                }
            }
        } catch (error: Throwable) {
            tripSpeechEnhancementCircuit(
                reason = "exception worker GTCRN asynchrone",
                cause = error,
            )
        } finally {
            recycleEnhancementWork(work)
        }
    }

    drainEnhancementQueueToPool()
    enhancementMetadataQueue.clear()
}

private fun applySpeechEnhancementAsync(
    samples: FloatArray,
    sampleCount: Int,
): EnhancementResult {
    val enhancer = audioEnhancer
        ?: return EnhancementResult(
            samples = samples,
            sampleCount = sampleCount,
            processingTimeNanos = 0L,
            applied = false,
            outputReady = true,
        )

    val startedAt = SystemClock.elapsedRealtimeNanos()
    val result =
        enhancer.process(
            samples = samples,
            sampleCount = sampleCount,
            sampleRateHz = configuration.sampleRateHz,
        )
    val elapsedNanos =
        SystemClock.elapsedRealtimeNanos() - startedAt

    lastEnhancementInputRms =
        calculateFloatRms(samples, sampleCount)
    lastEnhancementOutputRms =
        calculateFloatRms(result.samples, result.sampleCount)
    lastEnhancementProcessingTimeNanos = elapsedNanos
    lastEnhancementApplied = result.applied

    if (result.applied) {
        enhancementProcessingTimeTotalNanos.addAndGet(elapsedNanos)
        enhancementProcessedFrameCount.incrementAndGet()
        recordEnhancementProcessingTime(elapsedNanos)
    }

    return result.copy(processingTimeNanos = elapsedNanos)
}

private fun recycleEnhancementWork(work: EnhancementWorkItem) {
    work.sampleCount = 0
    work.metadata = null
    enhancementWorkPool.offer(work)
}

private fun drainEnhancementQueueToPool() {
    while (true) {
        val work = enhancementWorkQueue.poll() ?: break
        recycleEnhancementWork(work)
    }
}

private fun applySpeechEnhancement(

    samples: FloatArray,
    sampleCount: Int,
): EnhancementResult {

    val inputRms =
    calculateFloatRms(
        samples = samples,
        sampleCount = sampleCount,
    )

lastEnhancementInputRms =
    inputRms

    /*
 * Circuit breaker C2.
 *
 * Si C a été déclaré non sain pour cette session,
 * Guardian continue immédiatement en audio original.
 */
if (
    !speechEnhancementSessionHealthy
) {
    lastEnhancementProcessingTimeNanos =
        0L

    lastEnhancementApplied =
        false

    lastEnhancementOutputRms =
        inputRms

    return EnhancementResult(
        samples =
            samples,
        sampleCount =
            sampleCount,
        processingTimeNanos =
            0L,
        applied =
            false,
        outputReady =
            true,
    )
}

    if (
        !configuration.speechEnhancementEnabled ||
        enhancementRoute !=
            EnhancementRoute.GTCRN
    ) {

        lastEnhancementProcessingTimeNanos =
            0L

        lastEnhancementApplied =
            false

        lastEnhancementOutputRms =
            inputRms

        return EnhancementResult(
            samples = samples,
            sampleCount = sampleCount,
            processingTimeNanos = 0L,
            applied = false,
        )
    }

    val enhancer =
        audioEnhancer

    if (enhancer == null) {

        lastEnhancementProcessingTimeNanos =
            0L

        lastEnhancementApplied =
            false

        lastEnhancementOutputRms =
            inputRms

        return EnhancementResult(
            samples = samples,
            sampleCount = sampleCount,
            processingTimeNanos = 0L,
            applied = false,
        )
    }

    val startedAt =
        SystemClock.elapsedRealtimeNanos()

    val result =
        try {
            enhancer.process(
                samples = samples,
                sampleCount = sampleCount,
                sampleRateHz =
                    configuration.sampleRateHz,
            )
        } catch (error: Throwable) {

    /*
     * C2 - panne isolée du Speech Enhancement.
     *
     * Une exception GTCRN ne doit jamais arrêter
     * GuardianAudioEngine.
     *
     * On ouvre le circuit pour toute la session :
     * - GTCRN est reset ;
     * - ses métadonnées en attente sont supprimées ;
     * - la frame courante repasse en audio original ;
     * - les frames suivantes n'appelleront plus GTCRN.
     */
    tripSpeechEnhancementCircuit(
        reason =
            "exception pendant le traitement GTCRN",
        cause =
            error,
    )

    lastEnhancementProcessingTimeNanos =
        0L

    lastEnhancementApplied =
        false

    lastEnhancementOutputRms =
        inputRms

    EnhancementResult(
        samples =
            samples,
        sampleCount =
            sampleCount,
        processingTimeNanos =
            0L,
        applied =
            false,
        outputReady =
            true,
    )
}

    val elapsedNanos =
        SystemClock.elapsedRealtimeNanos() -
            startedAt

        val outputRms =
    calculateFloatRms(
        samples = result.samples,
        sampleCount = result.sampleCount,
    )

lastEnhancementOutputRms =
    outputRms

if (result.applied) {

    enhancementProcessingTimeTotalNanos
        .addAndGet(
            elapsedNanos
        )

    enhancementProcessedFrameCount
        .incrementAndGet()

    recordEnhancementProcessingTime(
        elapsedNanos
    )
}

    lastEnhancementProcessingTimeNanos =
        elapsedNanos

    lastEnhancementApplied =
        result.applied

    return result.copy(
        processingTimeNanos =
            elapsedNanos,
    )
}


    private fun enqueuePcmFrame(
        pcmBuffer: ShortArray,
        sampleCount: Int,
        captureReadEndedAtElapsedRealtimeNanos: Long,
    ) {
        val pooledFrame = framePool.poll()
            ?: frameQueue.poll()?.also { droppedFrameCount.incrementAndGet() }
            ?: run {
                droppedFrameCount.incrementAndGet()
                return
            }

        val boundedSampleCount =
    sampleCount.coerceAtMost(
        pooledFrame.samples.size
    )

val rawRms =
    calculateFrameRms(
        pcmBuffer =
            pcmBuffer,

        sampleCount =
            boundedSampleCount,
    )

/*
 * C12 - Stage A / RAW_MIC.
 * Copie observationnelle du PCM AudioRecord avant filtre.
 */
for (index in 0 until boundedSampleCount) {
    pooledFrame.rawSamples[index] =
        pcmBuffer[index].toFloat() /
            PCM16_NORMALIZATION_FACTOR
}

val preprocessedSamples =
    preprocessMonoPcm(
        pcmBuffer =
            pcmBuffer,

        sampleCount =
            boundedSampleCount,
    )

val filteredRms =
    calculateFloatRms(
        samples =
            preprocessedSamples,

        sampleCount =
            boundedSampleCount,
    )

lastRawRms =
    rawRms

lastFilteredRms =
    filteredRms

val frameRms =
    filteredRms

val acousticAnalysis =
    analyzeAcousticFrame(
        frameRms = frameRms
    )

/*
 * Phase C3 - Préparation du flux avant Speech Enhancement.
 *
 * L'analyse acoustique reste basée sur le signal
 * prétraité original :
 *
 * - noise floor
 * - SNR
 * - CLEAR / DISTANT / NOISE
 *
 * En revanche, l'Adaptive Gain et le Soft Limiter
 * seront appliqués APRÈS GTCRN dans processingLoop().
 */

pooledFrame.frameRms =
    acousticAnalysis.frameRms

pooledFrame.noiseFloorRms =
    acousticAnalysis.noiseFloor

pooledFrame.farVoiceEventId =
    acousticAnalysis.farVoiceEventId

pooledFrame.farVoiceSource =
    acousticAnalysis.farVoiceSource

pooledFrame.farVoicePhase =
    acousticAnalysis.farVoicePhase

pooledFrame.speechLevel =
    acousticAnalysis.speechLevel

pooledFrame.speechLikely =
    acousticAnalysis.speechLikely

pooledFrame.gainEligible =
    acousticAnalysis.gainEligible

pooledFrame.snrDb =
    acousticAnalysis.snrDb

/*
 * Guardian Speech Capture V3-A.
 *
 * On fige une copie indépendante du signal mono
 * prétraité AVANT GTCRN / Adaptive Gain / Limiter.
 *
 * Cette copie ne participe encore à AUCUNE décision :
 * elle sert uniquement à observer les phrases ratées
 * et prépare le futur Detector indépendant.
 */
preprocessedSamples.copyInto(
    destination =
        pooledFrame.detectorSamples,

    destinationOffset =
        0,

    startIndex =
        0,

    endIndex =
        boundedSampleCount,
)

/*
 * Important :
 * GTCRN doit recevoir directement le signal issu
 * du passe-haut, sans amplification Guardian
 * et sans Soft Limiter.
 */
preprocessedSamples.copyInto(
    destination =
        pooledFrame.samples,

    destinationOffset =
        0,

    startIndex =
        0,

    endIndex =
        boundedSampleCount,
)

/*
 * À ce stade outputRms représente temporairement
 * le signal pré-GTCRN.
 *
 * Il sera remplacé par le niveau réellement publié
 * après GTCRN + Adaptive Gain + Soft Limiter.
 */
lastOutputRms =
    filteredRms

        pooledFrame.sampleCount = boundedSampleCount

        /*
         * Temporal V2.1 : l'horloge publiée est le DEBUT acoustique de la frame et non le
         * moment où le CPU termine preprocessMonoPcm(). Ainsi Boundary,
         * Rescue, Qwen et le handoff A -> B raisonnent tous sur la même
         * timeline AudioRecord.
         */
        val frameDurationNanos =
            if (configuration.sampleRateHz > 0) {
                boundedSampleCount.toLong() *
                    NANOS_PER_SECOND /
                    configuration.sampleRateHz.toLong()
            } else {
                0L
            }

        pooledFrame.capturedAtElapsedRealtimeNanos =
            (
                captureReadEndedAtElapsedRealtimeNanos -
                    frameDurationNanos
                )
                .coerceAtLeast(0L)

        pooledFrame.sequenceNumber = sequenceNumber.incrementAndGet()
        capturedFrameCount.incrementAndGet()
        capturedSampleCount.addAndGet(boundedSampleCount.toLong())

        if (!frameQueue.offer(pooledFrame)) {
            frameQueue.poll()?.let {
                recycleFrame(it)
                droppedFrameCount.incrementAndGet()
            }
            if (!frameQueue.offer(pooledFrame)) {
                recycleFrame(pooledFrame)
                droppedFrameCount.incrementAndGet()
            }
        }
    }

    private fun configureAndroidAudioEffects(
    recorder: AudioRecord,
) {
    releaseAndroidAudioEffects()

    val audioSessionId =
        recorder.audioSessionId

    // -----------------------------
    // Noise Suppressor
    // -----------------------------

    val noiseSuppressorAvailable =
        NoiseSuppressor.isAvailable()

    /*
     * Lorsque le Speech Enhancement neural (GTCRN) est actif, Guardian
     * n'empile pas volontairement le NoiseSuppressor Android devant lui.
     * Cela évite deux débruiteurs successifs pouvant retirer une partie
     * de la parole faible.
     *
     * Quand GTCRN est désactivé, le comportement historique est conservé.
     */
    val shouldEnableAndroidNoiseSuppressor =
        configuration.androidNoiseSuppressorEnabled &&
            !configuration.speechEnhancementEnabled

    if (
        shouldEnableAndroidNoiseSuppressor &&
        noiseSuppressorAvailable
    ) {
        var createdSuppressor: NoiseSuppressor? = null

        try {
            createdSuppressor =
                NoiseSuppressor.create(
                    audioSessionId
                )

            if (createdSuppressor != null) {
                createdSuppressor.enabled = true

                noiseSuppressor =
                    createdSuppressor

                Log.i(
                    TAG,
                    "NoiseSuppressor Android : " +
                        "available=true, " +
                        "enabled=${createdSuppressor.enabled}, " +
                        "session=$audioSessionId"
                )
            } else {
                Log.w(
                    TAG,
                    "NoiseSuppressor Android disponible " +
                        "mais create() a retourné null."
                )
            }
        } catch (error: Throwable) {

            try {
                createdSuppressor?.release()
            } catch (_: Throwable) {
            }

            noiseSuppressor = null

            Log.w(
                TAG,
                "Impossible d'activer NoiseSuppressor Android.",
                error
            )
        }
    } else {
        Log.i(
            TAG,
            "NoiseSuppressor Android : " +
                "available=$noiseSuppressorAvailable, " +
                "requested=${configuration.androidNoiseSuppressorEnabled}, " +
                "speechEnhancement=${configuration.speechEnhancementEnabled}, " +
                "effective=$shouldEnableAndroidNoiseSuppressor"
        )
    }

    // -----------------------------
    // Automatic Gain Control
    // -----------------------------

    val agcAvailable =
        AutomaticGainControl.isAvailable()

    if (
        configuration.androidAgcEnabled &&
        agcAvailable
    ) {
        var createdAgc: AutomaticGainControl? = null

        try {
            createdAgc =
                AutomaticGainControl.create(
                    audioSessionId
                )

            if (createdAgc != null) {
                createdAgc.enabled = true

                automaticGainControl =
                    createdAgc

                Log.i(
                    TAG,
                    "AutomaticGainControl Android : " +
                        "available=true, " +
                        "enabled=${createdAgc.enabled}, " +
                        "session=$audioSessionId"
                )
            } else {
                Log.w(
                    TAG,
                    "AutomaticGainControl Android disponible " +
                        "mais create() a retourné null."
                )
            }
        } catch (error: Throwable) {

            try {
                createdAgc?.release()
            } catch (_: Throwable) {
            }

            automaticGainControl = null

            Log.w(
                TAG,
                "Impossible d'activer AutomaticGainControl Android.",
                error
            )
        }
    } else {
        Log.i(
            TAG,
            "AutomaticGainControl Android : " +
                "available=$agcAvailable, " +
                "requested=${configuration.androidAgcEnabled}"
        )
    }
}

    private fun releaseAndroidAudioEffects() {
    try {
        noiseSuppressor?.enabled = false
    } catch (error: Throwable) {
        Log.w(
            TAG,
            "Erreur pendant la désactivation du NoiseSuppressor.",
            error
        )
    }

    try {
        noiseSuppressor?.release()
    } catch (error: Throwable) {
        Log.w(
            TAG,
            "Erreur pendant la libération du NoiseSuppressor.",
            error
        )
    } finally {
        noiseSuppressor = null
    }

    try {
        automaticGainControl?.enabled = false
    } catch (error: Throwable) {
        Log.w(
            TAG,
            "Erreur pendant la désactivation de l'AGC Android.",
            error
        )
    }

    try {
        automaticGainControl?.release()
    } catch (error: Throwable) {
        Log.w(
            TAG,
            "Erreur pendant la libération de l'AGC Android.",
            error
        )
    } finally {
        automaticGainControl = null
    }
}

    private fun createAudioRecord(): AudioRecord {
        if (!hasRecordAudioPermission()) {
            throw SecurityException(
                "La permission RECORD_AUDIO a été retirée."
            )
        }

        val channelMask =
            configuration.inputChannelMask

        val inputChannelCount =
            if (
                channelMask ==
                    AudioFormat.CHANNEL_IN_STEREO
            ) {
                2
            } else {
                1
            }

        val minimumBufferSize =
            AudioRecord.getMinBufferSize(
                configuration.sampleRateHz,
                channelMask,
                AudioFormat.ENCODING_PCM_16BIT,
            )

        if (minimumBufferSize <= 0) {
            throw AudioCaptureException(
                "AudioRecord.getMinBufferSize a échoué.",
                minimumBufferSize
            )
        }

        val frameSizeBytes =
            frameSampleCount *
                inputChannelCount *
                PCM16_BYTES_PER_SAMPLE

        val requestedBufferSizeBytes =
            max(
                minimumBufferSize *
                    configuration.internalBufferMultiplier,
                frameSizeBytes *
                    MINIMUM_FRAME_BUFFER_COUNT,
            )

        val recorder =
            AudioRecord.Builder()
                .setAudioSource(
                    configuration.audioSource
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(
                            AudioFormat.ENCODING_PCM_16BIT
                        )
                        .setSampleRate(
                            configuration.sampleRateHz
                        )
                        .setChannelMask(
                            channelMask
                        )
                        .build(),
                )
                .setBufferSizeInBytes(
                    requestedBufferSizeBytes
                )
                .build()

        if (
            recorder.state !=
                AudioRecord.STATE_INITIALIZED
        ) {
            try {
                recorder.release()
            } catch (_: Throwable) {
            }

            throw AudioCaptureException(
                "AudioRecord n'a pas pu être initialisé."
            )
        }

        configureAndroidAudioEffects(
            recorder
        )

        return recorder
    }

    private fun stopInternal(targetState: State, isPause: Boolean) {
        val localCaptureThread: Thread?
        val localProcessingThread: Thread?
        val localEnhancementThread: Thread?
        synchronized(lifecycleLock) {
            if (state == State.RELEASED && targetState != State.RELEASED) return
            pauseRequested.set(isPause)
            stopRequested.set(true)
            if (!isPause && state != State.RELEASED) setState(State.STOPPING)
            localCaptureThread = captureThread
            localProcessingThread = processingThread
            localEnhancementThread = enhancementThread
            stopAudioRecordSafely()
        }

        joinThread(localCaptureThread)
        joinThread(localProcessingThread)
        joinThread(localEnhancementThread)

        synchronized(lifecycleLock) {
            releaseAudioRecordSafely()
            drainQueueToPool()
            if (captureThread?.isAlive != true) captureThread = null
            if (processingThread?.isAlive != true) processingThread = null
            if (enhancementThread?.isAlive != true) enhancementThread = null
            drainEnhancementQueueToPool()
            setState(if (released.get()) State.RELEASED else targetState)
        }
    }

    private fun joinThread(thread: Thread?) {
        if (thread == null || thread === Thread.currentThread()) return
        try {
            thread.join(configuration.stopJoinTimeoutMs)
            if (thread.isAlive) {
                thread.interrupt()
                thread.join(SECONDARY_JOIN_TIMEOUT_MS)
            }
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            reportError(error, true)
        }
    }

    private fun stopAudioRecordSafely() {
        val recorder = audioRecord ?: return
        try {
            if (recorder.state == AudioRecord.STATE_INITIALIZED && recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) recorder.stop()
        } catch (error: Throwable) {
            Log.w(TAG, "Erreur pendant AudioRecord.stop().", error)
        }
    }

    private fun releaseAudioRecordSafely(expectedRecorder: AudioRecord? = null) {
        val recorder = expectedRecorder ?: audioRecord ?: return

        unregisterRuntimeAudioMonitor(recorder)

        try {
            if (recorder.state == AudioRecord.STATE_INITIALIZED && recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) recorder.stop()
        } catch (error: Throwable) {
            Log.w(TAG, "Erreur pendant AudioRecord.stop().", error)
        }

        releaseAndroidAudioEffects()

        try { recorder.release() } catch (error: Throwable) {
            Log.w(TAG, "Erreur pendant AudioRecord.release().", error)
        }
        if (audioRecord === recorder) audioRecord = null
    }

    private fun recycleFrame(
    frame: PooledFrame,
) {
    frame.sampleCount =
        0

    frame.capturedAtElapsedRealtimeNanos =
        0L

    frame.sequenceNumber =
        0L

    frame.frameRms =
        0.0f

    frame.speechLevel =
        AcousticSpeechLevel.NOISE

    frame.speechLikely =
        false

    frame.gainEligible =
        false

    frame.snrDb =
        0.0f

    frame.noiseFloorRms =
        0.0f

    frame.farVoiceEventId =
        0L

    frame.farVoiceSource =
        FarVoiceSource.NONE

    frame.farVoicePhase =
        FarVoicePhase.NONE

    framePool.offer(frame)
}

    private fun drainQueueToPool() {
        while (true) recycleFrame(frameQueue.poll() ?: break)
    }

    private fun recordCaptureTiming(
        captureReadEndedAtElapsedRealtimeNanos: Long,
    ) {
        val nowNanos =
            captureReadEndedAtElapsedRealtimeNanos

        lastCaptureElapsedRealtimeNanos?.let { previousNanos ->
            val gapMs =
                (
                    (nowNanos - previousNanos) /
                        NANOS_PER_MILLISECOND
                    )
                    .coerceAtLeast(0L)

            lastCaptureGapMs = gapMs

            if (gapMs > maximumCaptureGapMs) {
                maximumCaptureGapMs = gapMs
            }
        }

        lastCaptureElapsedRealtimeNanos = nowNanos
    }

    private fun emitStatistics(
        squaredSum: Double,
        measuredSampleCount: Long,
        peak: Float,
        clippedSampleCount: Int,
        limiterEngagedSampleCount: Int,
    ) {
        val rms = if (measuredSampleCount > 0L) {
            sqrt(squaredSum / measuredSampleCount.toDouble()).toFloat()
        } else 0.0f

        val enhancementFrameCount =
    enhancementProcessedFrameCount.get()

val enhancementAverageProcessingMs =
    if (enhancementFrameCount > 0L) {
        (
            enhancementProcessingTimeTotalNanos.get()
                .toDouble() /
                enhancementFrameCount.toDouble()
        ) /
            1_000_000.0
    } else {
        0.0
    }

        val enhancementP95ProcessingMs =
    calculateEnhancementP95Ms()

        val statistics = Statistics(
    timestampMs =
        System.currentTimeMillis(),

    state =
        state,

    sampleRateHz =
        configuration.sampleRateHz,

    frameDurationMs =
        configuration.frameDurationMs,

    capturedFrameCount =
        capturedFrameCount.get(),

    processedFrameCount =
        processedFrameCount.get(),

    droppedFrameCount =
        droppedFrameCount.get(),

    enhancedDroppedFrameCount =
        enhancedDroppedFrameCount.get(),

    capturedSampleCount =
        capturedSampleCount.get(),

    restartCount =
        restartCount,

    queueDepth =
        frameQueue.size,

    queueCapacity =
        configuration.queueCapacity,

    rawRms =
        lastRawRms,

    filteredRms =
        lastFilteredRms,

    rms =
        rms.coerceIn(
            0.0f,
            1.0f
        ),

    noiseFloor =
        estimatedNoiseFloor.coerceIn(
            0.0f,
            1.0f
        ),

    snrDb =
    lastSnrDb,

speechLevel =
    lastSpeechLevel.name,

speechLikely =
    speechLikely,

gainEligible =
    lastGainEligible,

    adaptiveGain =
        currentAdaptiveGain,

    outputRms =
        lastOutputRms,

    speechConditionerEnabled =
        configuration.speechConditionerEnabled,

    speechConditionerApplied =
        lastSpeechConditionerApplied,

    speechConditionerInputRms =
        lastSpeechConditionerInputRms,

    speechConditionerOutputRms =
        lastSpeechConditionerOutputRms,

    enhancementEnabled =
    configuration.speechEnhancementEnabled,

enhancementRoute =
    enhancementRoute.name,

enhancementRouteReason =
    enhancementRouteReason,

enhancementApplied =
    lastEnhancementApplied,

enhancementInputRms =
    lastEnhancementInputRms,

enhancementOutputRms =
    lastEnhancementOutputRms,

enhancementProcessingMs =
    lastEnhancementProcessingTimeNanos
        .toDouble() /
        1_000_000.0,

enhancementAverageProcessingMs =
    enhancementAverageProcessingMs,

    enhancementP95ProcessingMs =
    enhancementP95ProcessingMs,

    peak =
        peak.coerceIn(
            0.0f,
            1.0f
        ),

    clippedSampleCount =
        clippedSampleCount,

    limiterEngagedSampleCount =
        limiterEngagedSampleCount,

    lastCaptureGapMs =
        lastCaptureGapMs,

    maximumCaptureGapMs =
        maximumCaptureGapMs,
    )

        lastStatistics =
    statistics

    Log.d(
    TAG,
    "Mono Core : " +
        "rawRms=${"%.5f".format(statistics.rawRms)}, " +
        "filteredRms=${"%.5f".format(statistics.filteredRms)}, " +
        "noiseFloorCandidate=${"%.5f".format(lastNoiseFloorCandidateRms)}, " +
        "noiseFloor=${"%.5f".format(statistics.noiseFloor)}, " +
        "noiseFloorClampActive=$lastNoiseFloorClampActive, " +
        "snrDb=${"%.2f".format(statistics.snrDb)}, " +
        "speechLevel=${statistics.speechLevel}, " +
        "speech=${statistics.speechLikely}, " +
        "gainEligible=${statistics.gainEligible}, " +
        "gain=${"%.2f".format(statistics.adaptiveGain)}, " +
        "outputRms=${"%.5f".format(statistics.outputRms)}, " +
        "conditionerEnabled=${statistics.speechConditionerEnabled}, " +
        "conditionerApplied=${statistics.speechConditionerApplied}, " +
        "conditionerInRms=${"%.5f".format(statistics.speechConditionerInputRms)}, " +
        "conditionerOutRms=${"%.5f".format(statistics.speechConditionerOutputRms)}, " +
        "enhancementEnabled=${statistics.enhancementEnabled}, " +
        "enhancementApplied=${statistics.enhancementApplied}, " +
        "enhancementInputRms=${"%.5f".format(statistics.enhancementInputRms)}, " +
        "enhancementOutputRms=${"%.5f".format(statistics.enhancementOutputRms)}, " +
        "enhancementMs=${"%.3f".format(statistics.enhancementProcessingMs)}, " +
        "enhancementAvgMs=${"%.3f".format(statistics.enhancementAverageProcessingMs)}, " +
        "enhancementP95Ms=${"%.3f".format(statistics.enhancementP95ProcessingMs)}, " +
        "peak=${"%.5f".format(statistics.peak)}, " +
        "clipped=${statistics.clippedSampleCount}, " +
        "limiter=${statistics.limiterEngagedSampleCount}"
)

        for (processor in processors) {
            try { processor.onAudioEngineStatistics(statistics) }
            catch (error: Throwable) { Log.e(TAG, "Erreur statistiques AudioProcessor.", error) }
        }
    }

    private fun setState(newState: State) {
        if (state == newState) return
        state = newState
        Log.i(TAG, "État audio : $newState")
        for (processor in processors) {
            try { processor.onAudioEngineStateChanged(newState) }
            catch (error: Throwable) { Log.e(TAG, "Erreur changement d'état AudioProcessor.", error) }
        }
    }

    private fun reportError(error: Throwable, recoverable: Boolean) {
        Log.e(TAG, "Erreur audio, recoverable=$recoverable", error)
        for (processor in processors) notifyProcessorError(processor, error, recoverable)
    }

    private fun notifyProcessorError(processor: AudioProcessor, error: Throwable, recoverable: Boolean) {
        try { processor.onAudioEngineError(error, recoverable) }
        catch (processorError: Throwable) { Log.e(TAG, "Erreur dans onAudioEngineError.", processorError) }
    }

    private fun audioSourceName(
    source: Int,
): String =
    when (source) {
        MediaRecorder.AudioSource.UNPROCESSED ->
            "UNPROCESSED"

        MediaRecorder.AudioSource.VOICE_RECOGNITION ->
            "VOICE_RECOGNITION"

        MediaRecorder.AudioSource.MIC ->
            "MIC"

        MediaRecorder.AudioSource.VOICE_COMMUNICATION ->
            "VOICE_COMMUNICATION"

        else ->
            "SOURCE_$source"
    }

    private fun audioDeviceTypeName(
    type: Int,
): String =
    when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_MIC ->
            "BUILTIN_MIC"

        AudioDeviceInfo.TYPE_BLUETOOTH_SCO ->
            "BLUETOOTH_SCO"

        AudioDeviceInfo.TYPE_USB_DEVICE ->
            "USB_DEVICE"

        AudioDeviceInfo.TYPE_USB_HEADSET ->
            "USB_HEADSET"

        AudioDeviceInfo.TYPE_WIRED_HEADSET ->
            "WIRED_HEADSET"

        else ->
            "TYPE_$type"
    }

    private fun hasRecordAudioPermission(): Boolean =
        applicationContext.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun shouldStop(): Boolean = stopRequested.get() || pauseRequested.get() || released.get()

    private fun calculateRestartDelayMs(attempt: Int): Long {
        val exponent = (attempt - 1).coerceIn(0, 5)
        return (configuration.restartBaseDelayMs * (1L shl exponent)).coerceAtMost(MAXIMUM_RESTART_DELAY_MS)
    }

    private fun waitInterruptibly(delayMs: Long): Boolean {
        val deadline = SystemClock.elapsedRealtime() + delayMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (shouldStop()) return false
            val remaining = deadline - SystemClock.elapsedRealtime()
            SystemClock.sleep(remaining.coerceAtMost(RESTART_SLEEP_SLICE_MS))
        }
        return !shouldStop()
    }

    private fun trySetThreadPriority(priority: Int) {
        try { Process.setThreadPriority(priority) }
        catch (error: Throwable) { Log.w(TAG, "Impossible d'appliquer la priorité $priority.", error) }
    }

    companion object {
        private const val TAG = "GuardianAudioEngine"
        private const val CAPTURE_THREAD_NAME = "guardian-audio-capture"
        private const val PROCESSING_THREAD_NAME = "guardian-audio-processing"

        const val DEFAULT_SAMPLE_RATE_HZ = 16_000
        const val DEFAULT_FRAME_DURATION_MS = 50

        const val STEREO_CHANNEL_LEFT = 0
        const val STEREO_CHANNEL_RIGHT = 1
        const val STEREO_CHANNEL_MIX = 2
        const val STEREO_CHANNEL_ALIGNED_MIX = 3
        const val STEREO_CHANNEL_VOICE_FOCUS = 4
        /*
 * Guardian Mono Core A3
 * Détection acoustique de voix distante.
 *
 * Ces seuils sont volontairement distincts
 * des seuils CLEAR_SPEECH.
 */
        private const val DISTANT_SPEECH_MINIMUM_RMS = 0.0020f
        private const val DISTANT_SPEECH_MINIMUM_SNR_DB = 6.0f
        private const val DISTANT_SPEECH_CONFIRMATION_FRAMES = 2
        private const val DISTANT_SPEECH_TARGET_RMS = 0.08f
        private const val FAR_VOICE_RELEASE_NOISE_FRAMES = 8
        private const val FAR_VOICE_DIAGNOSTIC_FRAMES = 20
        private const val DISTANT_SPEECH_HOLD_FRAMES = 8

        /*
 * Far Voice V2.3.
 *
 * 12 frames Guardian × 50 ms = 600 ms.
 *
 * Fenêtre maximale accordée à une acquisition
 * UltraWeak pour obtenir une confirmation neurale.
 */
private const val ULTRA_WEAK_EVENT_HOLD_FRAMES =
    12
    
        /*
 * Far Voice V2.1 - Ultra-Weak Acquisition.
 *
 * Contrairement à V2, ces seuils ne suffisent jamais
 * à eux seuls pour déclarer DISTANT_SPEECH.
 *
 * Ils servent uniquement à accumuler progressivement
 * des preuves acoustiques sur plusieurs frames.
 */
private const val ULTRA_WEAK_MINIMUM_RMS =
    0.00125f

private const val ULTRA_WEAK_MINIMUM_SNR_DB =
    1.5f

// Seuils de l'Ultra-Weak existant rendus relatifs au bruit physique calme.
// Le plancher absolu reste une sécurité anti-hiss ; non évalué avant calibration.
private const val ULTRA_WEAK_ADAPTIVE_HARD_FLOOR_RMS = 0.00020f
private const val ULTRA_WEAK_ADAPTIVE_MIN_RATIO = 1.85f
private const val ULTRA_WEAK_ADAPTIVE_MIN_SNR_DB = 5.3f

private const val ULTRA_WEAK_STRONG_EVIDENCE =
    1.0f

private const val ULTRA_WEAK_WEAK_EVIDENCE =
    0.45f

private const val ULTRA_WEAK_EVIDENCE_DECAY =
    0.35f

private const val ULTRA_WEAK_ACQUISITION_SCORE =
    3.6f

private const val ULTRA_WEAK_MAXIMUM_SCORE =
    4.0f
        private const val DISTANT_SPEECH_MAXIMUM_GAIN = 6.0f
        private const val DISTANT_SPEECH_GAIN_RISE = 0.22f
        private const val DEFAULT_INTERNAL_BUFFER_MULTIPLIER = 4
        private const val DEFAULT_QUEUE_CAPACITY = 32
        private const val DEFAULT_POOL_CAPACITY = 40
        private const val DEFAULT_MAXIMUM_RESTART_ATTEMPTS = 5
        private const val DEFAULT_RESTART_BASE_DELAY_MS = 500L
        private const val DEFAULT_STATISTICS_INTERVAL_MS = 1_000L
        private const val ENHANCEMENT_ASYNC_QUEUE_CAPACITY = 6
        private const val ENHANCEMENT_ASYNC_POOL_CAPACITY = 8

        /*
         * Le denoiser peut avoir une petite latence interne, mais sa file de
         * métadonnées ne doit jamais croître sans limite si outputReady reste
         * faux. 32 frames = 1,6 s avec des frames Guardian de 50 ms.
         */
        private const val ENHANCEMENT_METADATA_QUEUE_MAX_ITEMS = 32
        private const val ENHANCEMENT_POLL_TIMEOUT_MS = 50L
        private const val ENHANCEMENT_THREAD_NAME = "GuardianGtcrnWorker"
        private const val DEFAULT_STOP_JOIN_TIMEOUT_MS = 2_500L
        private const val MAXIMUM_RESTART_DELAY_MS = 8_000L
        private const val SECONDARY_JOIN_TIMEOUT_MS = 500L
        private const val PROCESSING_POLL_TIMEOUT_MS = 100L
        private const val RESTART_SLEEP_SLICE_MS = 50L
        private const val PCM16_BYTES_PER_SAMPLE = 2
        private const val PCM16_NORMALIZATION_FACTOR = 32_768.0f
        private const val CLIPPING_THRESHOLD = 0.99f
        private const val DEFAULT_TARGET_RMS = 0.12f

        private const val DEFAULT_MINIMUM_GAIN = 0.75f

        private const val DEFAULT_MAXIMUM_GAIN = 4.0f

        private const val DEFAULT_GAIN_ATTACK = 0.35f

        private const val DEFAULT_GAIN_RELEASE = 0.08f

        private const val SILENCE_RMS_THRESHOLD = 0.002f

        private const val OUTPUT_LIMIT = 0.98f
        private const val SOFT_LIMITER_THRESHOLD = 0.82f
        private const val SOFT_LIMITER_KNEE = 0.18f

        /*
         * Guardian Speech Conditioner V1.
         *
         * +15 % du détail inter-sample = accentuation volontairement douce.
         * À Nyquist, cela reste de l'ordre de quelques dB seulement.
         *
         * Le RMS global ne peut dépasser +8 % avant l'Adaptive Gain.
         */
        private const val SPEECH_CONDITIONER_DETAIL_AMOUNT =
            0.15f

        private const val SPEECH_CONDITIONER_MAX_RMS_RATIO =
            1.08f

        private const val SPEECH_CONDITIONER_SAMPLE_LIMIT =
            0.98f

        private const val MONO_HIGH_PASS_ALPHA = 0.97f
        private const val DEFAULT_NOISE_FLOOR_RISE =
         0.015f

        private const val DEFAULT_NOISE_FLOOR_FALL =
         0.10f

        private const val VERY_SLOW_NOISE_FLOOR_RISE =
         0.002f

        private const val DEFAULT_SPEECH_SNR_THRESHOLD_DB =
         4.0f

        private const val DEFAULT_MINIMUM_SPEECH_RMS =
         0.010f

        private const val DEFAULT_ADAPTIVE_GAIN_MINIMUM_RMS =
         0.004f

        private const val DEFAULT_ADAPTIVE_GAIN_MINIMUM_SNR_DB =
         2.0f

        private const val MINIMUM_NOISE_FLOOR_RMS =
         0.001f

        private const val MAXIMUM_NOISE_FLOOR_RMS =
         0.10f

        private const val NOISE_TRACKING_RATIO =
         1.5f

        private const val NON_SPEECH_GAIN_RETURN_RATE =
         0.15f

        private const val GAIN_CONFIRMATION_FRAMES =
        1

        private const val GAIN_HOLD_FRAMES =
        8

        private const val RMS_EPSILON =
         0.0001f
         /*
     * C1.2
    *
    * Fenêtre glissante utilisée pour mesurer le P95
    * du coût du Speech Enhancement.
    */
    private const val ENHANCEMENT_TIMING_WINDOW_SIZE =
    100

    private const val MAX_ENHANCEMENT_METADATA_FRAMES =
    32

    /*
     * Phase C7 - baseline initiale de routage GTCRN.
     *
     * Ces seuils sont distincts des seuils Far Voice et
     * seront qualifiés plus tard sur le corpus Guardian.
     */
    private const val DEFAULT_ENHANCEMENT_NOISE_FLOOR_ENTER_RMS =
        0.0030f

    private const val DEFAULT_ENHANCEMENT_NOISE_FLOOR_EXIT_RMS =
        0.0018f

    /*
     * 8 x 50 ms = 400 ms de bruit confirmé.
     */
    private const val ENHANCEMENT_ROUTE_ENTER_CONFIRMATION_FRAMES =
        8

    /*
     * 24 x 50 ms = 1,2 s de calme confirmé.
     */
    private const val ENHANCEMENT_ROUTE_EXIT_CONFIRMATION_FRAMES =
        24

    /*
     * 12 x 50 ms = 600 ms de verrou après parole.
     */
    private const val ENHANCEMENT_ROUTE_SPEECH_HOLD_FRAMES =
        12

    /*
     * 3 x 50 ms = 150 ms de parole distante confirmée avant ouverture
     * de la Difficult Lane. Ce n'est pas un gate ASR : Truth continue.
     */
    private const val ENHANCEMENT_ROUTE_DIFFICULT_SPEECH_CONFIRMATION_FRAMES =
        3

        private const val MINIMUM_FRAME_BUFFER_COUNT = 4
        private const val NANOS_PER_MILLISECOND = 1_000_000L
        private const val NANOS_PER_SECOND = 1_000_000_000L
    }
}

/**
 * Noise floor V2.5 : référence physique indépendante du clamp V2 strict.
 *
 * Objectifs :
 * - ne jamais considérer le tout premier échantillon comme une calibration ;
 * - résister aux impulsions et aux valeurs de démarrage anormalement basses ;
 * - n'autoriser l'Ultra-Weak adaptatif qu'après une fenêtre acoustique stable ;
 * - geler l'apprentissage dès qu'une parole connue/protégée est présente ;
 * - conserver exactement les règles historiques après calibration.
 *
 * Important : cette classe estime uniquement le bruit de fond physique.
 * Elle ne décide jamais qu'un signal est de la parole.
 */
internal class GuardianUltraWeakQuietFloor {
    var floorRms: Float = 0f
        private set

    var calibrated: Boolean = false
        private set

    var recentUnstableNoise: Boolean = false
        private set

    private var unstableFrames = 0

    /*
     * Fenêtre bornée utilisée uniquement AVANT calibration.
     * 12 frames Guardian x 50 ms ~= 600 ms.
     *
     * Le percentile bas donne la référence calme ; le percentile haut sert
     * uniquement à vérifier que la fenêtre est suffisamment stationnaire.
     */
    private val calibrationWindow =
        java.util.ArrayDeque<Float>(CALIBRATION_WINDOW_FRAMES)

    fun reset() {
        floorRms = 0f
        calibrated = false
        recentUnstableNoise = false
        unstableFrames = 0
        calibrationWindow.clear()
    }

    fun observe(
        rms: Float,
        speechProtected: Boolean,
    ) {
        if (!rms.isFinite() || rms < 0f) {
            return
        }

        val value =
            rms.coerceIn(
                MIN_TRACKED_RMS,
                MAX_TRACKED_RMS,
            )

        /*
         * Une parole déjà connue ne doit jamais participer à l'apprentissage.
         *
         * Avant calibration on jette également la fenêtre partielle : on ne
         * concatène pas deux périodes calmes séparées par une parole connue.
         */
        if (speechProtected) {
            if (!calibrated) {
                floorRms = 0f
                calibrationWindow.clear()
            }
            return
        }

        if (!calibrated) {
            observeCalibrationFrame(value)
            return
        }

        val ratio =
            value /
                floorRms.coerceAtLeast(
                    MIN_TRACKED_RMS,
                )

        when {
            ratio <= TRACKING_MAX_RATIO -> {
                floorRms +=
                    (value - floorRms) *
                        if (value < floorRms) {
                            FLOOR_FALL_ALPHA
                        } else {
                            FLOOR_RISE_ALPHA
                        }

                unstableFrames =
                    (unstableFrames - 1)
                        .coerceAtLeast(0)
            }

            else -> {
                /*
                 * Ne jamais absorber une montée brusque : elle peut être une
                 * parole distante. Seuls les écarts extrêmes répétés marquent
                 * l'environnement comme momentanément instable.
                 */
                if (ratio > UNSTABLE_NOISE_RATIO) {
                    unstableFrames += 1
                }
            }
        }

        recentUnstableNoise =
            unstableFrames >=
                UNSTABLE_NOISE_CONFIRMATION_FRAMES

        if (unstableFrames == 0) {
            recentUnstableNoise = false
        }
    }

    private fun observeCalibrationFrame(
        value: Float,
    ) {
        calibrationWindow.addLast(value)

        while (
            calibrationWindow.size >
                CALIBRATION_WINDOW_FRAMES
        ) {
            calibrationWindow.removeFirst()
        }

        if (
            calibrationWindow.size <
                CALIBRATION_WINDOW_FRAMES
        ) {
            floorRms = 0f
            return
        }

        val ordered =
            calibrationWindow
                .toList()
                .sorted()

        val lowerReference =
            ordered[CALIBRATION_LOW_INDEX]
                .coerceAtLeast(
                    MIN_TRACKED_RMS,
                )

        val upperReference =
            ordered[CALIBRATION_HIGH_INDEX]

        /*
         * Une impulsion, une transition forte ou un démarrage encore instable
         * ne doit pas valider le bruit de fond. La fenêtre glisse alors d'une
         * frame et sera réévaluée au prochain appel.
         */
        val stationary =
            upperReference <=
                lowerReference *
                    CALIBRATION_MAX_SPREAD_RATIO

        floorRms =
            lowerReference

        if (!stationary) {
            return
        }

        calibrated = true
        unstableFrames = 0
        recentUnstableNoise = false
        calibrationWindow.clear()
    }

    private companion object {
        private const val MIN_TRACKED_RMS =
            0.00002f

        private const val MAX_TRACKED_RMS =
            0.02f

        private const val CALIBRATION_WINDOW_FRAMES =
            12

        /*
         * Sur 12 valeurs triées : index 2 ~= P25 et index 9 ~= P75.
         * On évite ainsi qu'une valeur de démarrage ou un pic unique dicte la
         * calibration.
         */
        private const val CALIBRATION_LOW_INDEX =
            2

        private const val CALIBRATION_HIGH_INDEX =
            9

        private const val CALIBRATION_MAX_SPREAD_RATIO =
            1.30f

        /* Règles historiques post-calibration, volontairement inchangées. */
        private const val TRACKING_MAX_RATIO =
            1.18f

        private const val FLOOR_FALL_ALPHA =
            0.16f

        private const val FLOOR_RISE_ALPHA =
            0.01f

        private const val UNSTABLE_NOISE_RATIO =
            8.0f

        private const val UNSTABLE_NOISE_CONFIRMATION_FRAMES =
            5
    }
}
