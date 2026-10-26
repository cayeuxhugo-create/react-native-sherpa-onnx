package expo.modules.guardiannativeprotection

import android.util.Log
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Guardian Spatial Audio V3 (V2 conservé, Voice Focus optionnel).
 *
 * Front-end spatial adaptatif à deux canaux.
 *
 * Objectifs :
 * - ne jamais supposer que LEFT ou RIGHT est le "meilleur" microphone ;
 * - qualifier les deux canaux à partir du PCM réellement reçu ;
 * - estimer le TDOA avec GCC-PHAT dans la bande utile de la parole ;
 * - stabiliser le TDOA dans le temps sans le figer pendant toute une phrase ;
 * - appliquer un délai fractionnaire causal avec transition progressive ;
 * - pondérer les canaux selon leur qualité mesurée ;
 * - retomber automatiquement sur une route sûre si l'information spatiale
 *   devient instable ou non exploitable.
 *
 * Cette classe reste volontairement indépendante :
 * - de Sherpa / Nemotron ;
 * - de Qwen ;
 * - du VAD ;
 * - de Far Voice ;
 * - de toute connaissance du modèle de téléphone.
 *
 * Elle travaille uniquement à partir des deux canaux PCM fournis par
 * GuardianAudioEngine.
 *
 * Convention du lag :
 *
 * lag > 0 : RIGHT arrive après LEFT -> LEFT doit être retardé.
 * lag < 0 : LEFT arrive après RIGHT -> RIGHT doit être retardé.
 */
internal class GuardianSpatialAudio(
    private val sampleRateHz: Int,
    private val maximumFrameSamples: Int,
    private val steeringAllowed: Boolean = false,
    private val maximumTrustedTdoaSeconds: Double? = null,
    private val inputTrustName: String = "NONE",
    private val voiceFocusMode: GuardianVoiceFocusMode = GuardianVoiceFocusMode.OFF,
    private val holdDuringSpeech: Boolean = false,
) {


    /*
     * Step 4 - confiance runtime.
     *
     * La qualification initiale vient de GuardianAudioCapabilities.
     * Si Android modifie ensuite la route de capture, GuardianAudioEngine
     * invalide cette confiance. Le steering reste alors désactivé jusqu'à
     * la création d'un nouveau GuardianSpatialAudio après un nouveau probe.
     */
    private val runtimeSteeringAllowed =
        AtomicBoolean(steeringAllowed)

    private val runtimeSteeringInvalidationPending =
        AtomicBoolean(false)
    private val runtimeFocusOutputAllowed = AtomicBoolean(true)

    /* Le focus n’utilise jamais une ancienne qualification après un reroutage. */
    private val focusProcessor = if (voiceFocusMode != GuardianVoiceFocusMode.OFF) {
        GuardianVoiceFocusEngine(
            sampleRate = sampleRateHz,
            holdDuringSpeech = holdDuringSpeech,
            frameCapacity = maximumFrameSamples,
            allowRelativeAlignment = inputTrustName == "INTER_CHANNEL_ONLY" ||
                (steeringAllowed && inputTrustName == "PHYSICAL_TDOA"),
            alignmentLimitSamples = if (steeringAllowed && maximumTrustedTdoaSeconds != null) {
                (sampleRateHz * maximumTrustedTdoaSeconds).toFloat()
            } else 13f,
        )
    } else null

    fun setVoiceActivityHint(active: Boolean) { focusProcessor?.setVoiceHint(active) }

    // Les quatre PCM proviennent de la MÊME capture AudioRecord : un seul moteur.
    fun beginFocusShadowUtterance() {
        if (runtimeFocusOutputAllowed.get()) focusProcessor?.beginShadowUtterance()
    }
    fun endFocusShadowUtterance(): GuardianVoiceFocusEngine.ShadowUtterance? =
        if (runtimeFocusOutputAllowed.get()) focusProcessor?.endShadowUtterance() else null

    fun getVoiceFocusSnapshot(): GuardianVoiceFocusEngine.FocusSnapshot? =
        focusProcessor?.getSnapshot()

    @Volatile
    private var runtimeSteeringInvalidationReason: String? =
        null

    enum class Route {
        SAFE_FALLBACK,
        REFERENCE_LEFT,
        REFERENCE_RIGHT,
        WEIGHTED_MIX,
        STEERED_WEIGHTED_MIX,
    }

    data class Snapshot(
        /* Compatibilité V1. */
        val instantaneousLagSamples: Int,
        val stableLagSamples: Int,
        val lagMilliseconds: Double,
        val correlation: Float,
        val confidence: Float,
        val peakMargin: Float,
        val leftRms: Float,
        val rightRms: Float,
        val reliable: Boolean,
        val alignmentApplied: Boolean,

        /* Diagnostic V2. */
        val instantaneousFractionalLagSamples: Float,
        val steeringLagSamples: Float,
        val gccPeak: Float,
        val leftSpeechBandRms: Float,
        val rightSpeechBandRms: Float,
        val leftNoiseFloorRms: Float,
        val rightNoiseFloorRms: Float,
        val leftSnrDb: Float,
        val rightSnrDb: Float,
        val leftQuality: Float,
        val rightQuality: Float,
        val leftWeight: Float,
        val rightWeight: Float,
        val leftClipRatio: Float,
        val rightClipRatio: Float,
        val route: Route,
    )

    private data class LagEstimate(
        val integerLagSamples: Int,
        val fractionalLagSamples: Float,
        val gccPeak: Float,
        val peakSharpness: Float,
        val coherence: Float,
        val confidence: Float,
        val reliable: Boolean,
    )

    private data class ChannelMetrics(
        val rawRms: Float,
        val speechBandRms: Float,
        val noiseFloorRms: Float,
        val snrDb: Float,
        val clippingRatio: Float,
        val quality: Float,
        val usable: Boolean,
    )

    private data class SpatialDecision(
        val route: Route,
        val leftWeight: Float,
        val rightWeight: Float,
    )

    /*
     * 0,8 ms représente ~27 cm de différence de trajet dans l'air.
     * C'est volontairement plus large que la géométrie d'un smartphone
     * classique, sans dépendre d'un modèle précis.
     */
    /*
     * Fenêtre d'analyse GCC-PHAT.
     *
     * Elle reste volontairement large afin de conserver le diagnostic
     * inter-canaux même sur une route PROCESSED. Elle ne constitue PAS
     * automatiquement une borne de TDOA physique.
     */
    private val maximumLagSamples =
        (
            sampleRateHz.toDouble() *
                max(
                    MAXIMUM_ANALYSIS_DELAY_SECONDS,
                    if (
                        steeringAllowed &&
                        maximumTrustedTdoaSeconds != null &&
                        maximumTrustedTdoaSeconds.isFinite() &&
                        maximumTrustedTdoaSeconds > 0.0
                    ) {
                        maximumTrustedTdoaSeconds
                    } else {
                        0.0
                    },
                )
            )
            .let { samples ->
                kotlin.math.ceil(
                    samples
                )
                    .toInt()
            }
            .coerceIn(
                1,
                ABSOLUTE_MAXIMUM_LAG_SAMPLES,
            )

    /*
     * Borne de steering physiquement fiable.
     *
     * - 0 : steering interdit ;
     * - >0 : route DIRECT + géométrie fiable, borne fournie par
     *   GuardianAudioCapabilities.
     *
     * ceil() évite de sous-estimer la borne physique lorsqu'elle tombe
     * entre deux échantillons.
     */
    private val maximumTrustedSteeringLagSamples =
        if (
            steeringAllowed &&
            maximumTrustedTdoaSeconds != null &&
            maximumTrustedTdoaSeconds.isFinite() &&
            maximumTrustedTdoaSeconds > 0.0
        ) {
            kotlin.math.ceil(
                sampleRateHz.toDouble() *
                    maximumTrustedTdoaSeconds
            )
                .toInt()
                .coerceIn(
                    1,
                    maximumLagSamples,
                )
        } else {
            0
        }

    /*
     * +2 pour l'interpolation fractionnaire lorsque l'échantillon demandé
     * tombe juste avant la frontière de frame.
     */
    private val historySampleCount =
        maximumLagSamples +
            FRACTIONAL_DELAY_HISTORY_MARGIN

    private val fftSize =
        nextPowerOfTwo(
            (maximumFrameSamples * 2)
                .coerceAtLeast(MINIMUM_FFT_SIZE),
        )

    /* PCM courant. */
    private val leftPcm =
        ShortArray(maximumFrameSamples)

    private val rightPcm =
        ShortArray(maximumFrameSamples)

    /* Analyse speech-band continue. */
    private val leftSpatial =
        FloatArray(maximumFrameSamples)

    private val rightSpatial =
        FloatArray(maximumFrameSamples)

    /* Historique causal pour retard fractionnaire. */
    private val leftHistory =
        ShortArray(historySampleCount)

    private val rightHistory =
        ShortArray(historySampleCount)

    /* Fenêtre d'analyse réutilisée. */
    private val analysisWindow =
        FloatArray(maximumFrameSamples)

    private var analysisWindowSampleCount =
        0

    /* Buffers FFT préalloués : aucune allocation dans process(). */
    private val leftFftReal =
        FloatArray(fftSize)

    private val leftFftImag =
        FloatArray(fftSize)

    private val rightFftReal =
        FloatArray(fftSize)

    private val rightFftImag =
        FloatArray(fftSize)

    private val gccReal =
        FloatArray(fftSize)

    private val gccImag =
        FloatArray(fftSize)

    /* Historique robuste des TDOA fiables. */
    private val lagHistory =
        FloatArray(LAG_HISTORY_SIZE)

    private val lagHistoryScratch =
        FloatArray(LAG_HISTORY_SIZE)

    private var lagHistoryCount =
        0

    private var lagHistoryWriteIndex =
        0

    /* État du filtre passe-haut / passe-bas d'analyse. */
    private var leftHighPassPreviousInput =
        0.0f

    private var leftHighPassPreviousOutput =
        0.0f

    private var rightHighPassPreviousInput =
        0.0f

    private var rightHighPassPreviousOutput =
        0.0f

    private var leftLowPassPreviousOutput =
        0.0f

    private var rightLowPassPreviousOutput =
        0.0f

    private val highPassAlpha: Float =
        (
            1.0 /
                (
                    1.0 +
                        2.0 *
                        PI *
                        SPATIAL_HIGH_PASS_HZ /
                        sampleRateHz.toDouble()
                    )
            )
            .toFloat()

    private val lowPassAlpha: Float =
        (
            (
                2.0 *
                    PI *
                    SPATIAL_LOW_PASS_HZ
                ) /
                (
                    sampleRateHz.toDouble() +
                        2.0 *
                        PI *
                        SPATIAL_LOW_PASS_HZ
                    )
            )
            .toFloat()

    /* Noise-floor indépendant pour chaque canal. */
    private var leftNoiseFloorRms =
        0.0f

    private var rightNoiseFloorRms =
        0.0f

    private var processedFrameCount =
        0L

    /* Décision spatiale avec hystérésis. */
    private var activeRoute =
        Route.SAFE_FALLBACK

    private var pendingRoute =
        Route.SAFE_FALLBACK

    private var pendingRouteCount =
        0

    private var currentLeftWeight =
        0.5f

    private var currentRightWeight =
        0.5f

    private var targetLeftWeight =
        0.5f

    private var targetRightWeight =
        0.5f

    /* Steering continu mais lent. */
    private var targetSteeringLagSamples =
        0.0f

    private var currentSteeringLagSamples =
        0.0f

    private var consecutiveUnreliableAnalyses =
        0

    private var steeringEvidenceHoldAnalysesRemaining =
        0

    private var lastLagEstimate =
        emptyLagEstimate()

    @Volatile
    private var lastSnapshot:
        Snapshot? = null

    private var lastLoggedRoute =
        Route.SAFE_FALLBACK
    private var lastLoggedFocusView = "OFF"

    private var lastLoggedSteeringBucket =
        Int.MIN_VALUE

    init {
        require(sampleRateHz in 8_000..48_000) {
            "sampleRateHz invalide pour GuardianSpatialAudio."
        }

        require(maximumFrameSamples > 0) {
            "maximumFrameSamples doit être > 0."
        }

        Log.i(
            TAG,
            "SPATIAL V2 INIT : " +
                "sampleRate=$sampleRateHz, " +
                "frameSamples=$maximumFrameSamples, " +
                "fftSize=$fftSize, " +
                "analysisMaxLag=$maximumLagSamples, " +
                "steeringAllowed=$steeringAllowed, " +
                "trustedSteeringMaxLag=$maximumTrustedSteeringLagSamples, " +
                "inputTrust=$inputTrustName, " +
                "band=${SPATIAL_HIGH_PASS_HZ.toInt()}-" +
                "${SPATIAL_LOW_PASS_HZ.toInt()}Hz"
        )
    }

    fun getLastSnapshot():
        Snapshot? =
        lastSnapshot

    fun isRuntimeSteeringAllowed(): Boolean =
        runtimeSteeringAllowed.get()

    fun invalidateRuntimeSteering(
        reason: String,
    ) {
        val normalizedReason =
            reason.trim().ifBlank {
                "RUNTIME_AUDIO_CONFIGURATION_CHANGED"
            }

        runtimeSteeringInvalidationReason =
            normalizedReason

        val wasAllowed =
            runtimeSteeringAllowed
                .getAndSet(false)

        runtimeSteeringInvalidationPending
            .set(true)
        runtimeFocusOutputAllowed.set(false)

        if (wasAllowed) {
            Log.w(
                TAG,
                "SPATIAL TRUST INVALIDATED : " +
                    "reason=$normalizedReason, " +
                    "inputTrust=$inputTrustName"
            )
        }
    }

    private fun applyRuntimeSteeringInvalidationIfNeeded() {
        if (
            !runtimeSteeringInvalidationPending
                .compareAndSet(true, false)
        ) {
            return
        }

        /*
         * Cette méthode est appelée uniquement depuis le thread de capture.
         * Toute mutation de l'état DSP reste donc mono-threadée.
         */
        focusProcessor?.invalidateRuntimeRoute()
        lagHistory.fill(0.0f)
        lagHistoryScratch.fill(0.0f)
        lagHistoryCount = 0
        lagHistoryWriteIndex = 0

        targetSteeringLagSamples = 0.0f
        currentSteeringLagSamples = 0.0f
        consecutiveUnreliableAnalyses = 0
        steeringEvidenceHoldAnalysesRemaining = 0

        if (activeRoute == Route.STEERED_WEIGHTED_MIX) {
            activeRoute =
                Route.WEIGHTED_MIX
        }

        if (pendingRoute == Route.STEERED_WEIGHTED_MIX) {
            pendingRoute =
                Route.WEIGHTED_MIX
            pendingRouteCount =
                0
        }

        Log.w(
            TAG,
            "SPATIAL STEERING DISABLED AT RUNTIME : " +
                "reason=${runtimeSteeringInvalidationReason ?: "UNKNOWN"}"
        )
    }

    /**
     * Transforme : L R L R ... -> mono adaptatif.
     *
     * Le contrat est identique à la V1 pour GuardianAudioEngine.
     */
    fun processInterleavedPcm16(
        interleavedPcm: ShortArray,
        interleavedSampleCount: Int,
        outputMono: ShortArray,
    ): Int {

        applyRuntimeSteeringInvalidationIfNeeded()

        val boundedInterleavedCount =
            interleavedSampleCount
                .coerceIn(
                    0,
                    interleavedPcm.size,
                )

        val evenSampleCount =
            boundedInterleavedCount -
                (boundedInterleavedCount % 2)

        val frameCount =
            min(
                evenSampleCount / 2,
                min(
                    maximumFrameSamples,
                    outputMono.size,
                ),
            )

        if (frameCount <= 0) {
            return 0
        }

        processedFrameCount +=
            1L

        var leftRawSquaredSum =
            0.0

        var rightRawSquaredSum =
            0.0

        var leftBandSquaredSum =
            0.0

        var rightBandSquaredSum =
            0.0

        var leftClippedSamples =
            0

        var rightClippedSamples =
            0

        for (
            frameIndex in
            0 until frameCount
        ) {
            val left =
                interleavedPcm[
                    frameIndex * 2
                ]

            val right =
                interleavedPcm[
                    frameIndex * 2 + 1
                ]

            leftPcm[frameIndex] =
                left

            rightPcm[frameIndex] =
                right

            val leftFloat =
                left.toFloat() /
                    PCM16_NORMALIZATION_FACTOR

            val rightFloat =
                right.toFloat() /
                    PCM16_NORMALIZATION_FACTOR

            leftRawSquaredSum +=
                leftFloat.toDouble() *
                    leftFloat.toDouble()

            rightRawSquaredSum +=
                rightFloat.toDouble() *
                    rightFloat.toDouble()

            if (
                abs(left.toInt()) >=
                PCM16_CLIPPING_THRESHOLD
            ) {
                leftClippedSamples +=
                    1
            }

            if (
                abs(right.toInt()) >=
                PCM16_CLIPPING_THRESHOLD
            ) {
                rightClippedSamples +=
                    1
            }

            val leftBand =
                filterLeft(
                    leftFloat,
                )

            val rightBand =
                filterRight(
                    rightFloat,
                )

            leftSpatial[frameIndex] =
                leftBand

            rightSpatial[frameIndex] =
                rightBand

            leftBandSquaredSum +=
                leftBand.toDouble() *
                    leftBand.toDouble()

            rightBandSquaredSum +=
                rightBand.toDouble() *
                    rightBand.toDouble()
        }

        val leftRawRms =
            rmsFromSquaredSum(
                leftRawSquaredSum,
                frameCount,
            )

        val rightRawRms =
            rmsFromSquaredSum(
                rightRawSquaredSum,
                frameCount,
            )

        val leftBandRms =
            rmsFromSquaredSum(
                leftBandSquaredSum,
                frameCount,
            )

        val rightBandRms =
            rmsFromSquaredSum(
                rightBandSquaredSum,
                frameCount,
            )

        val leftClipRatio =
            leftClippedSamples.toFloat() /
                frameCount.toFloat()

        val rightClipRatio =
            rightClippedSamples.toFloat() /
                frameCount.toFloat()

        leftNoiseFloorRms =
            updateNoiseFloor(
                currentRms = leftBandRms,
                previousNoiseFloor = leftNoiseFloorRms,
            )

        rightNoiseFloorRms =
            updateNoiseFloor(
                currentRms = rightBandRms,
                previousNoiseFloor = rightNoiseFloorRms,
            )

        val leftMetrics =
            buildChannelMetrics(
                rawRms = leftRawRms,
                speechBandRms = leftBandRms,
                noiseFloorRms = leftNoiseFloorRms,
                clippingRatio = leftClipRatio,
            )

        val rightMetrics =
            buildChannelMetrics(
                rawRms = rightRawRms,
                speechBandRms = rightBandRms,
                noiseFloorRms = rightNoiseFloorRms,
                clippingRatio = rightClipRatio,
            )

        val analysisDue =
            processedFrameCount == 1L ||
                processedFrameCount %
                SPATIAL_ANALYSIS_INTERVAL_FRAMES ==
                0L

        if (analysisDue) {
            lastLagEstimate =
                estimateLagGccPhat(
                    sampleCount = frameCount,
                    leftMetrics = leftMetrics,
                    rightMetrics = rightMetrics,
                )

            updateLagTracker(
                lastLagEstimate,
            )

            val decision =
                chooseSpatialDecision(
                    left = leftMetrics,
                    right = rightMetrics,
                    lag = lastLagEstimate,
                )

            updateDecisionTracker(
                decision,
            )

            updateSmoothedWeights()
            updateSmoothedSteering()
        }

        /* V2 est conservé comme repli. Voice Focus reçoit exactement le même
         * PCM avant tout prétraitement mono ; aucune deuxième capture Android. */
        val focusOutputAvailable = if (focusProcessor != null) {
            focusProcessor.process(
                ch0 = leftPcm,
                ch1 = rightPcm,
                band0 = leftSpatial,
                band1 = rightSpatial,
                count = frameCount,
                output = outputMono,
                lagSamples = lastLagEstimate.fractionalLagSamples,
                lagReliable = lastLagEstimate.reliable,
                lagConfidence = lastLagEstimate.confidence,
                coherence = lastLagEstimate.coherence,
                lagMeasurementFresh = analysisDue,
            ) > 0
        } else false
        if (!runtimeFocusOutputAllowed.get() && voiceFocusMode != GuardianVoiceFocusMode.OFF) {
            // Chemin Android modifié : pas de fusion sur des mappings périmés.
            // En revanche, le canal de repli doit rester réellement audible.
            val safeUseSecond = rightMetrics.usable &&
                (!leftMetrics.usable ||
                    (rightMetrics.rawRms > leftMetrics.rawRms * 1.75f &&
                        rightMetrics.snrDb > leftMetrics.snrDb + 3f))
            for (i in 0 until frameCount) {
                outputMono[i] = if (safeUseSecond) rightPcm[i] else leftPcm[i]
            }
        } else if (!focusOutputAvailable || voiceFocusMode != GuardianVoiceFocusMode.ACTIVE) {
            renderOutput(outputMono = outputMono, sampleCount = frameCount)
        }

        updateHistory(
            sampleCount = frameCount,
        )

        publishDiagnosticsIfNeeded(
            left = leftMetrics,
            right = rightMetrics,
            lag = lastLagEstimate,
        )

        return frameCount
    }

    fun reset() {
        // Une invalidation runtime persiste jusqu'à NOUVELLE instance créée
        // après probe matériel ; reset() ne requalifie pas les micros.
        focusProcessor?.reset()
        if (!runtimeFocusOutputAllowed.get()) {
            focusProcessor?.invalidateRuntimeRoute()
        }
        leftPcm.fill(0)
        rightPcm.fill(0)
        leftSpatial.fill(0.0f)
        rightSpatial.fill(0.0f)
        leftHistory.fill(0)
        rightHistory.fill(0)

        leftFftReal.fill(0.0f)
        leftFftImag.fill(0.0f)
        rightFftReal.fill(0.0f)
        rightFftImag.fill(0.0f)
        gccReal.fill(0.0f)
        gccImag.fill(0.0f)

        lagHistory.fill(0.0f)
        lagHistoryScratch.fill(0.0f)
        lagHistoryCount = 0
        lagHistoryWriteIndex = 0

        leftHighPassPreviousInput = 0.0f
        leftHighPassPreviousOutput = 0.0f
        rightHighPassPreviousInput = 0.0f
        rightHighPassPreviousOutput = 0.0f
        leftLowPassPreviousOutput = 0.0f
        rightLowPassPreviousOutput = 0.0f

        leftNoiseFloorRms = 0.0f
        rightNoiseFloorRms = 0.0f

        processedFrameCount = 0L

        activeRoute = Route.SAFE_FALLBACK
        pendingRoute = Route.SAFE_FALLBACK
        pendingRouteCount = 0

        currentLeftWeight = 0.5f
        currentRightWeight = 0.5f
        targetLeftWeight = 0.5f
        targetRightWeight = 0.5f

        targetSteeringLagSamples = 0.0f
        currentSteeringLagSamples = 0.0f
        consecutiveUnreliableAnalyses = 0
        steeringEvidenceHoldAnalysesRemaining = 0

        lastLagEstimate =
            emptyLagEstimate()

        lastSnapshot =
            null

        lastLoggedRoute =
            Route.SAFE_FALLBACK
        lastLoggedFocusView = "OFF"

        lastLoggedSteeringBucket =
            Int.MIN_VALUE
    }

    /* --------------------------------------------------------------------- */
    /* Channel quality                                                      */
    /* --------------------------------------------------------------------- */

    private fun buildChannelMetrics(
        rawRms: Float,
        speechBandRms: Float,
        noiseFloorRms: Float,
        clippingRatio: Float,
    ): ChannelMetrics {

        val snrDb =
            20.0f *
                log10(
                    (
                        speechBandRms +
                            RMS_EPSILON
                        ) /
                        (
                            noiseFloorRms +
                                RMS_EPSILON
                            )
                )

        val usable =
            rawRms >=
                MINIMUM_RAW_CHANNEL_RMS &&
                speechBandRms >=
                MINIMUM_SPEECH_BAND_RMS &&
                clippingRatio <=
                MAXIMUM_USABLE_CLIPPING_RATIO

        val warmup =
            processedFrameCount <
                NOISE_FLOOR_WARMUP_FRAMES

        val snrScore =
            if (warmup) {
                0.50f
            } else {
                normalizeScore(
                    value = snrDb,
                    minimum = QUALITY_SNR_MIN_DB,
                    maximum = QUALITY_SNR_TARGET_DB,
                )
            }

        val energyScore =
            normalizeScore(
                value = speechBandRms,
                minimum = MINIMUM_SPEECH_BAND_RMS,
                maximum = TARGET_SPEECH_BAND_RMS,
            )

        val rawValidityScore =
            normalizeScore(
                value = rawRms,
                minimum = MINIMUM_RAW_CHANNEL_RMS,
                maximum = TARGET_RAW_CHANNEL_RMS,
            )

        val clippingScore =
            (
                1.0f -
                    normalizeScore(
                        value = clippingRatio,
                        minimum = CLIPPING_PENALTY_START_RATIO,
                        maximum = MAXIMUM_USABLE_CLIPPING_RATIO,
                    )
                )
                .coerceIn(
                    0.0f,
                    1.0f,
                )

        val quality =
            if (!usable) {
                0.0f
            } else {
                (
                    snrScore *
                        QUALITY_WEIGHT_SNR +
                        energyScore *
                        QUALITY_WEIGHT_SPEECH_BAND_ENERGY +
                        rawValidityScore *
                        QUALITY_WEIGHT_RAW_VALIDITY +
                        clippingScore *
                        QUALITY_WEIGHT_CLIPPING
                    )
                    .coerceIn(
                        0.0f,
                        1.0f,
                    )
            }

        return ChannelMetrics(
            rawRms = rawRms,
            speechBandRms = speechBandRms,
            noiseFloorRms = noiseFloorRms,
            snrDb = snrDb,
            clippingRatio = clippingRatio,
            quality = quality,
            usable = usable,
        )
    }

    private fun updateNoiseFloor(
        currentRms: Float,
        previousNoiseFloor: Float,
    ): Float {
        val boundedCurrent =
            currentRms
                .coerceAtLeast(
                    MINIMUM_NOISE_FLOOR_RMS,
                )

        if (
            previousNoiseFloor <=
            0.0f
        ) {
            return boundedCurrent
        }

        val alpha =
            when {
                boundedCurrent <=
                    previousNoiseFloor ->
                    NOISE_FLOOR_FALL_ALPHA

                boundedCurrent <=
                    previousNoiseFloor *
                    NOISE_FLOOR_NEAR_RATIO ->
                    NOISE_FLOOR_NEAR_RISE_ALPHA

                else ->
                    NOISE_FLOOR_SPEECH_GUARDED_RISE_ALPHA
            }

        return (
            previousNoiseFloor +
                alpha *
                (
                    boundedCurrent -
                        previousNoiseFloor
                    )
            )
            .coerceAtLeast(
                MINIMUM_NOISE_FLOOR_RMS,
            )
    }

    /* --------------------------------------------------------------------- */
    /* GCC-PHAT                                                             */
    /* --------------------------------------------------------------------- */

    private fun estimateLagGccPhat(
        sampleCount: Int,
        leftMetrics: ChannelMetrics,
        rightMetrics: ChannelMetrics,
    ): LagEstimate {

        if (
            sampleCount <
                MINIMUM_CORRELATION_SAMPLES ||
            !leftMetrics.usable ||
            !rightMetrics.usable
        ) {
            return emptyLagEstimate()
        }

        ensureAnalysisWindow(
            sampleCount,
        )

        leftFftReal.fill(0.0f)
        leftFftImag.fill(0.0f)
        rightFftReal.fill(0.0f)
        rightFftImag.fill(0.0f)
        gccReal.fill(0.0f)
        gccImag.fill(0.0f)

        for (
            index in
            0 until sampleCount
        ) {
            val window =
                analysisWindow[index]

            leftFftReal[index] =
                leftSpatial[index] *
                    window

            rightFftReal[index] =
                rightSpatial[index] *
                    window
        }

        fft(
            real = leftFftReal,
            imag = leftFftImag,
            inverse = false,
        )

        fft(
            real = rightFftReal,
            imag = rightFftImag,
            inverse = false,
        )

        for (
            bin in
            0 until fftSize
        ) {
            val mirroredBin =
                min(
                    bin,
                    fftSize - bin,
                )

            val frequencyHz =
                mirroredBin.toDouble() *
                    sampleRateHz.toDouble() /
                    fftSize.toDouble()

            if (
                frequencyHz <
                    GCC_MINIMUM_FREQUENCY_HZ ||
                frequencyHz >
                    GCC_MAXIMUM_FREQUENCY_HZ
            ) {
                gccReal[bin] =
                    0.0f

                gccImag[bin] =
                    0.0f

                continue
            }

            val leftReal =
                leftFftReal[bin]

            val leftImag =
                leftFftImag[bin]

            val rightReal =
                rightFftReal[bin]

            val rightImag =
                rightFftImag[bin]

            /* conj(LEFT) * RIGHT -> lag positif si RIGHT arrive après LEFT. */
            val crossReal =
                leftReal *
                    rightReal +
                    leftImag *
                    rightImag

            val crossImag =
                leftReal *
                    rightImag -
                    leftImag *
                    rightReal

            val magnitude =
                sqrt(
                    crossReal.toDouble() *
                        crossReal.toDouble() +
                        crossImag.toDouble() *
                        crossImag.toDouble()
                )
                    .toFloat()

            if (
                magnitude <=
                GCC_PHAT_EPSILON
            ) {
                gccReal[bin] =
                    0.0f

                gccImag[bin] =
                    0.0f
            } else {
                val denominator =
                    magnitude +
                        GCC_PHAT_REGULARIZATION

                gccReal[bin] =
                    crossReal /
                        denominator

                gccImag[bin] =
                    crossImag /
                        denominator
            }
        }

        fft(
            real = gccReal,
            imag = gccImag,
            inverse = true,
        )

        val effectiveMaximumLag =
            min(
                maximumLagSamples,
                (
                    sampleCount -
                        MINIMUM_CORRELATION_SAMPLES
                    )
                    .coerceAtLeast(0),
            )

        if (
            effectiveMaximumLag <=
            0
        ) {
            return emptyLagEstimate()
        }

        var bestLag =
            0

        var bestPeak =
            -1.0f

        for (
            lag in
            -effectiveMaximumLag..
                effectiveMaximumLag
        ) {
            val value =
                abs(
                    gccReal[
                        correlationIndexForLag(
                            lag,
                        )
                    ]
                )

            if (
                value >
                bestPeak
            ) {
                bestPeak =
                    value

                bestLag =
                    lag
            }
        }

        var secondBestPeak =
            0.0f

        for (
            lag in
            -effectiveMaximumLag..
                effectiveMaximumLag
        ) {
            if (
                abs(
                    lag -
                        bestLag
                ) <=
                GCC_PEAK_EXCLUSION_RADIUS_SAMPLES
            ) {
                continue
            }

            secondBestPeak =
                max(
                    secondBestPeak,
                    abs(
                        gccReal[
                            correlationIndexForLag(
                                lag,
                            )
                        ]
                    ),
                )
        }

        val peakSharpness =
            if (
                bestPeak <=
                GCC_PHAT_EPSILON
            ) {
                0.0f
            } else {
                (
                    (
                        bestPeak -
                            secondBestPeak
                        ) /
                        bestPeak
                    )
                    .coerceIn(
                        0.0f,
                        1.0f,
                    )
            }

        val fractionalOffset =
            parabolicPeakOffset(
                bestLag,
            )

        val fractionalLag =
            (
                bestLag.toFloat() +
                    fractionalOffset
                )
                .coerceIn(
                    -maximumLagSamples.toFloat(),
                    maximumLagSamples.toFloat(),
                )

        val coherence =
            normalizedCorrelationAtLag(
                lagSamples =
                    fractionalLag.roundToInt(),
                sampleCount = sampleCount,
            )

        val coherenceScore =
            normalizeScore(
                value = coherence,
                minimum = MINIMUM_USEFUL_COHERENCE,
                maximum = TARGET_COHERENCE,
            )

        val peakScore =
            normalizeScore(
                value = peakSharpness,
                minimum = MINIMUM_USEFUL_PEAK_SHARPNESS,
                maximum = TARGET_PEAK_SHARPNESS,
            )

        val weakerQuality =
            min(
                leftMetrics.quality,
                rightMetrics.quality,
            )

        val confidence =
            (
                coherenceScore *
                    LAG_CONFIDENCE_WEIGHT_COHERENCE +
                    peakScore *
                    LAG_CONFIDENCE_WEIGHT_PEAK +
                    weakerQuality *
                    LAG_CONFIDENCE_WEIGHT_CHANNEL_QUALITY
                )
                .coerceIn(
                    0.0f,
                    1.0f,
                )

        val reliable =
            coherence >=
                MINIMUM_RELIABLE_COHERENCE &&
                peakSharpness >=
                MINIMUM_RELIABLE_PEAK_SHARPNESS &&
                weakerQuality >=
                MINIMUM_CHANNEL_QUALITY_FOR_STEERING &&
                confidence >=
                MINIMUM_RELIABLE_LAG_CONFIDENCE

        return LagEstimate(
            integerLagSamples = bestLag,
            fractionalLagSamples = fractionalLag,
            gccPeak = bestPeak,
            peakSharpness = peakSharpness,
            coherence = coherence,
            confidence = confidence,
            reliable = reliable,
        )
    }

    private fun parabolicPeakOffset(
        lagSamples: Int,
    ): Float {
        val centerIndex =
            correlationIndexForLag(
                lagSamples,
            )

        val previousIndex =
            correlationIndexForLag(
                lagSamples - 1,
            )

        val nextIndex =
            correlationIndexForLag(
                lagSamples + 1,
            )

        val previous =
            abs(
                gccReal[previousIndex]
            )

        val center =
            abs(
                gccReal[centerIndex]
            )

        val next =
            abs(
                gccReal[nextIndex]
            )

        val denominator =
            previous -
                2.0f *
                center +
                next

        if (
            abs(denominator) <=
            PARABOLIC_INTERPOLATION_EPSILON
        ) {
            return 0.0f
        }

        return (
            0.5f *
                (
                    previous -
                        next
                    ) /
                denominator
            )
            .coerceIn(
                -0.5f,
                0.5f,
            )
    }

    private fun normalizedCorrelationAtLag(
        lagSamples: Int,
        sampleCount: Int,
    ): Float {
        val leftStart:
            Int

        val rightStart:
            Int

        val overlapCount:
            Int

        if (
            lagSamples >=
            0
        ) {
            leftStart =
                0

            rightStart =
                lagSamples

            overlapCount =
                sampleCount -
                    lagSamples
        } else {
            leftStart =
                -lagSamples

            rightStart =
                0

            overlapCount =
                sampleCount +
                    lagSamples
        }

        if (
            overlapCount <=
            0
        ) {
            return 0.0f
        }

        var crossSum =
            0.0

        var leftSquaredSum =
            0.0

        var rightSquaredSum =
            0.0

        for (
            index in
            0 until overlapCount
        ) {
            val left =
                leftSpatial[
                    leftStart +
                        index
                ]
                    .toDouble()

            val right =
                rightSpatial[
                    rightStart +
                        index
                ]
                    .toDouble()

            crossSum +=
                left *
                    right

            leftSquaredSum +=
                left *
                    left

            rightSquaredSum +=
                right *
                    right
        }

        val denominator =
            sqrt(
                leftSquaredSum *
                    rightSquaredSum
            )

        if (
            denominator <=
            CORRELATION_EPSILON
        ) {
            return 0.0f
        }

        return (
            crossSum /
                denominator
            )
            .toFloat()
            .coerceIn(
                -1.0f,
                1.0f,
            )
    }

    private fun correlationIndexForLag(
        lagSamples: Int,
    ): Int =
        if (
            lagSamples >=
            0
        ) {
            lagSamples %
                fftSize
        } else {
            (
                fftSize +
                    lagSamples
                ) %
                fftSize
        }

    /* --------------------------------------------------------------------- */
    /* Temporal tracker                                                      */
    /* --------------------------------------------------------------------- */

    private fun isLagTrustedForSteering(
        estimate: LagEstimate,
    ): Boolean =
        runtimeSteeringAllowed.get() &&
            maximumTrustedSteeringLagSamples > 0 &&
            estimate.reliable &&
            abs(estimate.fractionalLagSamples) <=
            maximumTrustedSteeringLagSamples.toFloat()

    private fun updateLagTracker(
        estimate: LagEstimate,
    ) {
        val trustedForSteering =
            isLagTrustedForSteering(
                estimate,
            )

        if (
            trustedForSteering
        ) {
            consecutiveUnreliableAnalyses =
                0

            steeringEvidenceHoldAnalysesRemaining =
                STEERING_EVIDENCE_HOLD_ANALYSES

            if (
                lagHistoryCount >=
                    MINIMUM_LAG_HISTORY_FOR_TARGET
            ) {
                val currentMedian =
                    medianLagHistory()

                val jump =
                    abs(
                        estimate.fractionalLagSamples -
                            currentMedian
                    )

                if (
                    jump >
                        MAXIMUM_ACCEPTED_TRACKING_JUMP_SAMPLES &&
                    estimate.confidence <
                        HIGH_CONFIDENCE_REQUIRED_FOR_LARGE_JUMP
                ) {
                    return
                }
            }

            lagHistory[
                lagHistoryWriteIndex
            ] =
                estimate.fractionalLagSamples

            lagHistoryWriteIndex =
                (
                    lagHistoryWriteIndex +
                        1
                    ) %
                    LAG_HISTORY_SIZE

            if (
                lagHistoryCount <
                LAG_HISTORY_SIZE
            ) {
                lagHistoryCount +=
                    1
            }

            if (
                lagHistoryCount >=
                MINIMUM_LAG_HISTORY_FOR_TARGET
            ) {
                val median =
                    medianLagHistory()

                val spread =
                    lagHistorySpread()

                if (
                    spread <=
                    MAXIMUM_LAG_HISTORY_SPREAD_SAMPLES
                ) {
                    targetSteeringLagSamples =
                        median
                            .coerceIn(
                                -maximumTrustedSteeringLagSamples.toFloat(),
                                maximumTrustedSteeringLagSamples.toFloat(),
                            )
                }
            }
        } else {
            consecutiveUnreliableAnalyses +=
                1

            if (
                steeringEvidenceHoldAnalysesRemaining >
                0
            ) {
                steeringEvidenceHoldAnalysesRemaining -=
                    1
            }

            if (
                consecutiveUnreliableAnalyses >=
                UNRELIABLE_ANALYSES_BEFORE_LAG_HISTORY_RESET
            ) {
                lagHistoryCount =
                    0

                lagHistoryWriteIndex =
                    0

                targetSteeringLagSamples =
                    0.0f
            }
        }
    }

    private fun medianLagHistory():
        Float {
        for (
            index in
            0 until lagHistoryCount
        ) {
            lagHistoryScratch[index] =
                lagHistory[index]
        }

        insertionSort(
            lagHistoryScratch,
            lagHistoryCount,
        )

        return if (
            lagHistoryCount %
            2 ==
            1
        ) {
            lagHistoryScratch[
                lagHistoryCount /
                    2
            ]
        } else {
            val upper =
                lagHistoryCount /
                    2

            (
                lagHistoryScratch[
                    upper - 1
                ] +
                    lagHistoryScratch[
                        upper
                    ]
                ) /
                2.0f
        }
    }

    private fun lagHistorySpread():
        Float {
        if (
            lagHistoryCount <=
            1
        ) {
            return 0.0f
        }

        var minimum =
            Float.POSITIVE_INFINITY

        var maximum =
            Float.NEGATIVE_INFINITY

        for (
            index in
            0 until lagHistoryCount
        ) {
            minimum =
                min(
                    minimum,
                    lagHistory[index],
                )

            maximum =
                max(
                    maximum,
                    lagHistory[index],
                )
        }

        return maximum -
            minimum
    }

    private fun updateSmoothedSteering() {
        if (
            !runtimeSteeringAllowed.get() ||
            maximumTrustedSteeringLagSamples <= 0
        ) {
            targetSteeringLagSamples =
                0.0f
            currentSteeringLagSamples =
                0.0f
            steeringEvidenceHoldAnalysesRemaining =
                0
            return
        }

        val difference =
            targetSteeringLagSamples -
                currentSteeringLagSamples

        val boundedStep =
            difference
                .coerceIn(
                    -MAXIMUM_STEERING_STEP_PER_ANALYSIS,
                    MAXIMUM_STEERING_STEP_PER_ANALYSIS,
                )

        currentSteeringLagSamples =
            (
                currentSteeringLagSamples +
                    boundedStep
                )
                .coerceIn(
                    -maximumTrustedSteeringLagSamples.toFloat(),
                    maximumTrustedSteeringLagSamples.toFloat(),
                )
    }

    /* --------------------------------------------------------------------- */
    /* Decision engine                                                       */
    /* --------------------------------------------------------------------- */

    private fun chooseSpatialDecision(
        left: ChannelMetrics,
        right: ChannelMetrics,
        lag: LagEstimate,
    ): SpatialDecision {

        if (
            left.usable &&
            !right.usable
        ) {
            return SpatialDecision(
                route = Route.REFERENCE_LEFT,
                leftWeight = 1.0f,
                rightWeight = 0.0f,
            )
        }

        if (
            right.usable &&
            !left.usable
        ) {
            return SpatialDecision(
                route = Route.REFERENCE_RIGHT,
                leftWeight = 0.0f,
                rightWeight = 1.0f,
            )
        }

        if (
            !left.usable &&
            !right.usable
        ) {
            return SpatialDecision(
                route = Route.SAFE_FALLBACK,
                leftWeight = 0.5f,
                rightWeight = 0.5f,
            )
        }

        val qualityDifference =
            left.quality -
                right.quality

        /*
         * Une corrélation négative forte peut indiquer une inversion de phase
         * ou une scène dominée par des réflexions. On évite alors de sommer
         * les deux canaux, ce qui pourrait provoquer une annulation.
         */
        if (
            lag.coherence <=
            NEGATIVE_COHERENCE_REFERENCE_THRESHOLD
        ) {
            return referenceDecisionForBetterChannel(
                left,
                right,
            )
        }

        if (
            isLagTrustedForSteering(
                lag,
            ) &&
            min(
                left.quality,
                right.quality,
            ) >=
            MINIMUM_CHANNEL_QUALITY_FOR_STEERING
        ) {
            val weights =
                qualityWeights(
                    left.quality,
                    right.quality,
                )

            return SpatialDecision(
                route = Route.STEERED_WEIGHTED_MIX,
                leftWeight = weights.first,
                rightWeight = weights.second,
            )
        }

        if (
            abs(qualityDifference) >=
            REFERENCE_CHANNEL_QUALITY_ADVANTAGE
        ) {
            return referenceDecisionForBetterChannel(
                left,
                right,
            )
        }

        if (
            max(
                left.quality,
                right.quality,
            ) >=
            MINIMUM_PRIMARY_QUALITY_FOR_WEIGHTED_MIX &&
            min(
                left.quality,
                right.quality,
            ) >=
            MINIMUM_SECONDARY_QUALITY_FOR_WEIGHTED_MIX
        ) {
            val weights =
                qualityWeights(
                    left.quality,
                    right.quality,
                )

            return SpatialDecision(
                route = Route.WEIGHTED_MIX,
                leftWeight = weights.first,
                rightWeight = weights.second,
            )
        }

        return referenceDecisionForBetterChannel(
            left,
            right,
        )
    }

    private fun referenceDecisionForBetterChannel(
        left: ChannelMetrics,
        right: ChannelMetrics,
    ): SpatialDecision {
        return if (
            left.quality >=
            right.quality
        ) {
            SpatialDecision(
                route = Route.REFERENCE_LEFT,
                leftWeight = 1.0f,
                rightWeight = 0.0f,
            )
        } else {
            SpatialDecision(
                route = Route.REFERENCE_RIGHT,
                leftWeight = 0.0f,
                rightWeight = 1.0f,
            )
        }
    }

    private fun qualityWeights(
        leftQuality: Float,
        rightQuality: Float,
    ): Pair<Float, Float> {
        val leftScore =
            QUALITY_WEIGHT_FLOOR +
                leftQuality *
                leftQuality

        val rightScore =
            QUALITY_WEIGHT_FLOOR +
                rightQuality *
                rightQuality

        val total =
            leftScore +
                rightScore

        if (
            total <=
            RMS_EPSILON
        ) {
            return Pair(
                0.5f,
                0.5f,
            )
        }

        var leftWeight =
            leftScore /
                total

        leftWeight =
            leftWeight
                .coerceIn(
                    MINIMUM_MIX_CHANNEL_WEIGHT,
                    MAXIMUM_MIX_CHANNEL_WEIGHT,
                )

        val rightWeight =
            1.0f -
                leftWeight

        return Pair(
            leftWeight,
            rightWeight,
        )
    }

    private fun updateDecisionTracker(
        decision: SpatialDecision,
    ) {
        if (
            decision.route ==
            pendingRoute
        ) {
            pendingRouteCount +=
                1
        } else {
            pendingRoute =
                decision.route

            pendingRouteCount =
                1
        }

        var routeCommittedNow =
            false

        if (
            activeRoute ==
                Route.SAFE_FALLBACK &&
            processedFrameCount <=
                STARTUP_FAST_DECISION_FRAMES
        ) {
            activeRoute =
                decision.route

            routeCommittedNow =
                true
        } else if (
            pendingRouteCount >=
            REQUIRED_DECISION_CONFIRMATIONS &&
            activeRoute !=
                decision.route
        ) {
            activeRoute =
                decision.route

            routeCommittedNow =
                true
        }

        /*
         * Une décision candidate non encore confirmée ne doit pas modifier
         * les poids de la route actuellement active. Les poids ne suivent
         * que la route active ou une transition qui vient d'être validée.
         */
        val decisionMatchesActiveRoute =
            decision.route ==
                activeRoute

        if (
            !decisionMatchesActiveRoute &&
            !routeCommittedNow
        ) {
            return
        }

        when (
            activeRoute
        ) {
            Route.REFERENCE_LEFT -> {
                targetLeftWeight =
                    1.0f

                targetRightWeight =
                    0.0f
            }

            Route.REFERENCE_RIGHT -> {
                targetLeftWeight =
                    0.0f

                targetRightWeight =
                    1.0f
            }

            Route.WEIGHTED_MIX,
            Route.STEERED_WEIGHTED_MIX -> {
                targetLeftWeight =
                    decision.leftWeight

                targetRightWeight =
                    decision.rightWeight
            }

            Route.SAFE_FALLBACK -> {
                targetLeftWeight =
                    0.5f

                targetRightWeight =
                    0.5f
            }
        }
    }

    private fun updateSmoothedWeights() {
        currentLeftWeight =
            moveTowards(
                current = currentLeftWeight,
                target = targetLeftWeight,
                maximumStep = MAXIMUM_WEIGHT_STEP_PER_ANALYSIS,
            )
                .coerceIn(
                    0.0f,
                    1.0f,
                )

        currentRightWeight =
            1.0f -
                currentLeftWeight
    }

    /* --------------------------------------------------------------------- */
    /* Render mono                                                           */
    /* --------------------------------------------------------------------- */

    private fun renderOutput(
        outputMono: ShortArray,
        sampleCount: Int,
    ) {
        val applySteering =
            runtimeSteeringAllowed.get() &&
                maximumTrustedSteeringLagSamples > 0 &&
                activeRoute ==
                Route.STEERED_WEIGHTED_MIX &&
                steeringEvidenceHoldAnalysesRemaining >
                0 &&
                abs(
                    currentSteeringLagSamples
                ) >=
                MINIMUM_STEERING_LAG_TO_APPLY

        if (
            applySteering
        ) {
            createFractionalSteeredMix(
                outputMono = outputMono,
                sampleCount = sampleCount,
                lagSamples = currentSteeringLagSamples,
                leftWeight = currentLeftWeight,
                rightWeight = currentRightWeight,
            )
        } else {
            createWeightedMix(
                outputMono = outputMono,
                sampleCount = sampleCount,
                leftWeight = currentLeftWeight,
                rightWeight = currentRightWeight,
            )
        }
    }

    private fun createWeightedMix(
        outputMono: ShortArray,
        sampleCount: Int,
        leftWeight: Float,
        rightWeight: Float,
    ) {
        for (
            index in
            0 until sampleCount
        ) {
            outputMono[index] =
                weightedPcm16(
                    first = leftPcm[index].toFloat(),
                    second = rightPcm[index].toFloat(),
                    firstWeight = leftWeight,
                    secondWeight = rightWeight,
                )
        }
    }

    private fun createFractionalSteeredMix(
        outputMono: ShortArray,
        sampleCount: Int,
        lagSamples: Float,
        leftWeight: Float,
        rightWeight: Float,
    ) {
        if (
            lagSamples >
            0.0f
        ) {
            /* RIGHT est en retard -> on retarde LEFT. */
            for (
                index in
                0 until sampleCount
            ) {
                val delayedLeft =
                    delayedSample(
                        current = leftPcm,
                        history = leftHistory,
                        currentIndex = index,
                        delaySamples = lagSamples,
                    )

                outputMono[index] =
                    weightedPcm16(
                        first = delayedLeft,
                        second = rightPcm[index].toFloat(),
                        firstWeight = leftWeight,
                        secondWeight = rightWeight,
                    )
            }
        } else if (
            lagSamples <
            0.0f
        ) {
            /* LEFT est en retard -> on retarde RIGHT. */
            val delay =
                -lagSamples

            for (
                index in
                0 until sampleCount
            ) {
                val delayedRight =
                    delayedSample(
                        current = rightPcm,
                        history = rightHistory,
                        currentIndex = index,
                        delaySamples = delay,
                    )

                outputMono[index] =
                    weightedPcm16(
                        first = leftPcm[index].toFloat(),
                        second = delayedRight,
                        firstWeight = leftWeight,
                        secondWeight = rightWeight,
                    )
            }
        } else {
            createWeightedMix(
                outputMono = outputMono,
                sampleCount = sampleCount,
                leftWeight = leftWeight,
                rightWeight = rightWeight,
            )
        }
    }

    private fun delayedSample(
        current: ShortArray,
        history: ShortArray,
        currentIndex: Int,
        delaySamples: Float,
    ): Float {
        val boundedDelay =
            delaySamples
                .coerceIn(
                    0.0f,
                    maximumLagSamples.toFloat(),
                )

        val sourcePosition =
            currentIndex.toFloat() -
                boundedDelay

        val lowerIndex =
            floor(
                sourcePosition.toDouble()
            )
                .toInt()

        val fraction =
            sourcePosition -
                lowerIndex.toFloat()

        val lower =
            sampleAtRelativeIndex(
                current = current,
                history = history,
                relativeIndex = lowerIndex,
            )

        val upper =
            sampleAtRelativeIndex(
                current = current,
                history = history,
                relativeIndex =
                    lowerIndex + 1,
            )

        return lower +
            (
                upper -
                    lower
                ) *
                fraction
    }

    private fun sampleAtRelativeIndex(
        current: ShortArray,
        history: ShortArray,
        relativeIndex: Int,
    ): Float {
        return if (
            relativeIndex >=
            0
        ) {
            current[
                relativeIndex
                    .coerceAtMost(
                        current.lastIndex,
                    )
            ]
                .toFloat()
        } else {
            val historyIndex =
                (
                    history.size +
                        relativeIndex
                    )
                    .coerceIn(
                        0,
                        history.lastIndex,
                    )

            history[
                historyIndex
            ]
                .toFloat()
        }
    }

    private fun weightedPcm16(
        first: Float,
        second: Float,
        firstWeight: Float,
        secondWeight: Float,
    ): Short =
        (
            first *
                firstWeight +
                second *
                secondWeight
            )
            .roundToInt()
            .coerceIn(
                Short.MIN_VALUE.toInt(),
                Short.MAX_VALUE.toInt(),
            )
            .toShort()

    private fun updateHistory(
        sampleCount: Int,
    ) {
        updateSingleHistory(
            history = leftHistory,
            current = leftPcm,
            sampleCount = sampleCount,
        )

        updateSingleHistory(
            history = rightHistory,
            current = rightPcm,
            sampleCount = sampleCount,
        )
    }

    private fun updateSingleHistory(
        history: ShortArray,
        current: ShortArray,
        sampleCount: Int,
    ) {
        if (
            sampleCount >=
            history.size
        ) {
            val sourceStart =
                sampleCount -
                    history.size

            for (
                index in
                history.indices
            ) {
                history[index] =
                    current[
                        sourceStart +
                            index
                    ]
            }

            return
        }

        val retainedCount =
            history.size -
                sampleCount

        for (
            index in
            0 until retainedCount
        ) {
            history[index] =
                history[
                    index +
                        sampleCount
                ]
        }

        for (
            index in
            0 until sampleCount
        ) {
            history[
                retainedCount +
                    index
            ] =
                current[index]
        }
    }

    /* --------------------------------------------------------------------- */
    /* Analysis filter                                                       */
    /* --------------------------------------------------------------------- */

    private fun filterLeft(
        input: Float,
    ): Float {
        val highPassed =
            highPassAlpha *
                (
                    leftHighPassPreviousOutput +
                        input -
                        leftHighPassPreviousInput
                    )

        leftHighPassPreviousInput =
            input

        leftHighPassPreviousOutput =
            highPassed

        val lowPassed =
            leftLowPassPreviousOutput +
                lowPassAlpha *
                (
                    highPassed -
                        leftLowPassPreviousOutput
                    )

        leftLowPassPreviousOutput =
            lowPassed

        return lowPassed
    }

    private fun filterRight(
        input: Float,
    ): Float {
        val highPassed =
            highPassAlpha *
                (
                    rightHighPassPreviousOutput +
                        input -
                        rightHighPassPreviousInput
                    )

        rightHighPassPreviousInput =
            input

        rightHighPassPreviousOutput =
            highPassed

        val lowPassed =
            rightLowPassPreviousOutput +
                lowPassAlpha *
                (
                    highPassed -
                        rightLowPassPreviousOutput
                    )

        rightLowPassPreviousOutput =
            lowPassed

        return lowPassed
    }

    /* --------------------------------------------------------------------- */
    /* FFT                                                                   */
    /* --------------------------------------------------------------------- */

    private fun ensureAnalysisWindow(
        sampleCount: Int,
    ) {
        if (
            analysisWindowSampleCount ==
            sampleCount
        ) {
            return
        }

        if (
            sampleCount <=
            1
        ) {
            analysisWindow[0] =
                1.0f

            analysisWindowSampleCount =
                sampleCount

            return
        }

        for (
            index in
            0 until sampleCount
        ) {
            analysisWindow[index] =
                (
                    0.5 -
                        0.5 *
                        cos(
                            2.0 *
                                PI *
                                index.toDouble() /
                                (
                                    sampleCount -
                                        1
                                    ).toDouble()
                        )
                    )
                    .toFloat()
        }

        analysisWindowSampleCount =
            sampleCount
    }

    private fun fft(
        real: FloatArray,
        imag: FloatArray,
        inverse: Boolean,
    ) {
        val n =
            real.size

        var j =
            0

        for (
            i in
            1 until n
        ) {
            var bit =
                n shr 1

            while (
                j and bit !=
                0
            ) {
                j =
                    j xor bit

                bit =
                    bit shr 1
            }

            j =
                j xor bit

            if (
                i <
                j
            ) {
                val temporaryReal =
                    real[i]

                real[i] =
                    real[j]

                real[j] =
                    temporaryReal

                val temporaryImag =
                    imag[i]

                imag[i] =
                    imag[j]

                imag[j] =
                    temporaryImag
            }
        }

        var length =
            2

        while (
            length <=
            n
        ) {
            val angle =
                2.0 *
                    PI /
                    length.toDouble() *
                    if (inverse) {
                        1.0
                    } else {
                        -1.0
                    }

            val wLengthReal =
                cos(angle)
                    .toFloat()

            val wLengthImag =
                sin(angle)
                    .toFloat()

            var blockStart =
                0

            while (
                blockStart <
                n
            ) {
                var weightReal =
                    1.0f

                var weightImag =
                    0.0f

                val halfLength =
                    length /
                        2

                for (
                    offset in
                    0 until halfLength
                ) {
                    val evenIndex =
                        blockStart +
                            offset

                    val oddIndex =
                        evenIndex +
                            halfLength

                    val oddReal =
                        real[oddIndex] *
                            weightReal -
                            imag[oddIndex] *
                            weightImag

                    val oddImag =
                        real[oddIndex] *
                            weightImag +
                            imag[oddIndex] *
                            weightReal

                    val evenReal =
                        real[evenIndex]

                    val evenImag =
                        imag[evenIndex]

                    real[evenIndex] =
                        evenReal +
                            oddReal

                    imag[evenIndex] =
                        evenImag +
                            oddImag

                    real[oddIndex] =
                        evenReal -
                            oddReal

                    imag[oddIndex] =
                        evenImag -
                            oddImag

                    val nextWeightReal =
                        weightReal *
                            wLengthReal -
                            weightImag *
                            wLengthImag

                    val nextWeightImag =
                        weightReal *
                            wLengthImag +
                            weightImag *
                            wLengthReal

                    weightReal =
                        nextWeightReal

                    weightImag =
                        nextWeightImag
                }

                blockStart +=
                    length
            }

            length =
                length shl 1
        }

        if (inverse) {
            val inverseN =
                1.0f /
                    n.toFloat()

            for (
                index in
                0 until n
            ) {
                real[index] *=
                    inverseN

                imag[index] *=
                    inverseN
            }
        }
    }

    /* --------------------------------------------------------------------- */
    /* Diagnostics                                                           */
    /* --------------------------------------------------------------------- */

    private fun publishDiagnosticsIfNeeded(
        left: ChannelMetrics,
        right: ChannelMetrics,
        lag: LagEstimate,
    ) {
        val steeringBucket =
            (
                currentSteeringLagSamples /
                    DIAGNOSTIC_STEERING_BUCKET_SAMPLES
                )
                .roundToInt()

        val routeChanged =
            activeRoute !=
                lastLoggedRoute
        val focusViewNow = focusProcessor?.getSnapshot()?.selected?.name ?: "OFF"
        val focusChanged = focusViewNow != lastLoggedFocusView

        val steeringChanged =
            steeringBucket !=
                lastLoggedSteeringBucket

        val periodic =
            processedFrameCount %
                DIAGNOSTIC_INTERVAL_FRAMES ==
                0L

        if (
            !routeChanged &&
            !focusChanged &&
            !steeringChanged &&
            !periodic
        ) {
            return
        }

        val alignmentApplied =
            runtimeSteeringAllowed.get() &&
                maximumTrustedSteeringLagSamples > 0 &&
                activeRoute ==
                Route.STEERED_WEIGHTED_MIX &&
                steeringEvidenceHoldAnalysesRemaining >
                0

        val snapshot =
            Snapshot(
                instantaneousLagSamples =
                    lag.integerLagSamples,
                stableLagSamples =
                    currentSteeringLagSamples
                        .roundToInt(),
                lagMilliseconds =
                    currentSteeringLagSamples
                        .toDouble() *
                        1_000.0 /
                        sampleRateHz.toDouble(),
                correlation =
                    lag.coherence,
                confidence =
                    lag.confidence,
                peakMargin =
                    lag.peakSharpness,
                leftRms =
                    left.rawRms,
                rightRms =
                    right.rawRms,
                reliable =
                    lag.reliable,
                alignmentApplied =
                    alignmentApplied,
                instantaneousFractionalLagSamples =
                    lag.fractionalLagSamples,
                steeringLagSamples =
                    currentSteeringLagSamples,
                gccPeak =
                    lag.gccPeak,
                leftSpeechBandRms =
                    left.speechBandRms,
                rightSpeechBandRms =
                    right.speechBandRms,
                leftNoiseFloorRms =
                    left.noiseFloorRms,
                rightNoiseFloorRms =
                    right.noiseFloorRms,
                leftSnrDb =
                    left.snrDb,
                rightSnrDb =
                    right.snrDb,
                leftQuality =
                    left.quality,
                rightQuality =
                    right.quality,
                leftWeight =
                    currentLeftWeight,
                rightWeight =
                    currentRightWeight,
                leftClipRatio =
                    left.clippingRatio,
                rightClipRatio =
                    right.clippingRatio,
                route =
                    activeRoute,
            )

        lastSnapshot =
            snapshot

        lastLoggedRoute =
            activeRoute
        lastLoggedFocusView = focusViewNow

        lastLoggedSteeringBucket =
            steeringBucket

        Log.i(
            TAG,
            "SPATIAL V3 : " +
                "routeV2=${snapshot.route}, " +
                "actualVoiceFocus=$focusViewNow, " +
                "focusMode=$voiceFocusMode, " +
                "lag=${"%.2f".format(snapshot.instantaneousFractionalLagSamples)}, " +
                "steer=${"%.2f".format(snapshot.steeringLagSamples)}, " +
                "corr=${"%.3f".format(snapshot.correlation)}, " +
                "conf=${"%.3f".format(snapshot.confidence)}, " +
                "sharp=${"%.3f".format(snapshot.peakMargin)}, " +
                "Lq=${"%.3f".format(snapshot.leftQuality)}, " +
                "Rq=${"%.3f".format(snapshot.rightQuality)}, " +
                "Lw=${"%.2f".format(snapshot.leftWeight)}, " +
                "Rw=${"%.2f".format(snapshot.rightWeight)}, " +
                "Lsnr=${"%.1f".format(snapshot.leftSnrDb)}dB, " +
                "Rsnr=${"%.1f".format(snapshot.rightSnrDb)}dB, " +
                "reliable=${snapshot.reliable}, " +
                "steeringAllowedInitial=$steeringAllowed, " +
                "steeringAllowedRuntime=${runtimeSteeringAllowed.get()}, " +
                "runtimeInvalidation=${runtimeSteeringInvalidationReason}, " +
                "trustedLag=${isLagTrustedForSteering(lag)}, " +
                "trustedMaxLag=$maximumTrustedSteeringLagSamples, " +
                "inputTrust=$inputTrustName"
        )
    }

    /* --------------------------------------------------------------------- */
    /* Generic helpers                                                       */
    /* --------------------------------------------------------------------- */

    private fun rmsFromSquaredSum(
        squaredSum: Double,
        sampleCount: Int,
    ): Float =
        if (
            sampleCount <=
            0
        ) {
            0.0f
        } else {
            sqrt(
                squaredSum /
                    sampleCount.toDouble()
            )
                .toFloat()
        }

    private fun normalizeScore(
        value: Float,
        minimum: Float,
        maximum: Float,
    ): Float {
        if (
            maximum <=
            minimum
        ) {
            return 0.0f
        }

        return (
            (
                value -
                    minimum
                ) /
                (
                    maximum -
                        minimum
                    )
            )
            .coerceIn(
                0.0f,
                1.0f,
            )
    }

    private fun moveTowards(
        current: Float,
        target: Float,
        maximumStep: Float,
    ): Float {
        val delta =
            target -
                current

        return current +
            delta
                .coerceIn(
                    -maximumStep,
                    maximumStep,
                )
    }

    private fun insertionSort(
        values: FloatArray,
        count: Int,
    ) {
        for (
            index in
            1 until count
        ) {
            val value =
                values[index]

            var cursor =
                index -
                    1

            while (
                cursor >=
                0 &&
                values[cursor] >
                value
            ) {
                values[
                    cursor +
                        1
                ] =
                    values[cursor]

                cursor -=
                    1
            }

            values[
                cursor +
                    1
            ] =
                value
        }
    }

    private fun emptyLagEstimate():
        LagEstimate =
        LagEstimate(
            integerLagSamples = 0,
            fractionalLagSamples = 0.0f,
            gccPeak = 0.0f,
            peakSharpness = 0.0f,
            coherence = 0.0f,
            confidence = 0.0f,
            reliable = false,
        )

    private fun nextPowerOfTwo(
        value: Int,
    ): Int {
        var result =
            1

        while (
            result <
            value
        ) {
            result =
                result shl 1
        }

        return result
    }

    companion object {
        private const val TAG =
            "GuardianSpatialAudio"

        private const val PCM16_NORMALIZATION_FACTOR =
            32_768.0f

        private const val PCM16_CLIPPING_THRESHOLD =
            32_000

        private const val MAXIMUM_ANALYSIS_DELAY_SECONDS =
            0.0008

        private const val ABSOLUTE_MAXIMUM_LAG_SAMPLES =
            48

        private const val FRACTIONAL_DELAY_HISTORY_MARGIN =
            2

        private const val SPATIAL_HIGH_PASS_HZ =
            250.0

        private const val SPATIAL_LOW_PASS_HZ =
            3_600.0

        private const val GCC_MINIMUM_FREQUENCY_HZ =
            250.0

        private const val GCC_MAXIMUM_FREQUENCY_HZ =
            3_600.0

        private const val MINIMUM_FFT_SIZE =
            256

        private const val MINIMUM_CORRELATION_SAMPLES =
            160

        private const val GCC_PHAT_EPSILON =
            1.0e-9f

        private const val GCC_PHAT_REGULARIZATION =
            1.0e-8f

        private const val GCC_PEAK_EXCLUSION_RADIUS_SAMPLES =
            1

        private const val PARABOLIC_INTERPOLATION_EPSILON =
            1.0e-8f

        private const val CORRELATION_EPSILON =
            1.0e-12

        /* Channel validity / quality. */
        private const val MINIMUM_RAW_CHANNEL_RMS =
            0.00008f

        private const val TARGET_RAW_CHANNEL_RMS =
            0.0030f

        private const val MINIMUM_SPEECH_BAND_RMS =
            0.00005f

        private const val TARGET_SPEECH_BAND_RMS =
            0.0015f

        private const val MINIMUM_NOISE_FLOOR_RMS =
            0.00002f

        private const val QUALITY_SNR_MIN_DB =
            0.0f

        private const val QUALITY_SNR_TARGET_DB =
            15.0f

        private const val CLIPPING_PENALTY_START_RATIO =
            0.001f

        private const val MAXIMUM_USABLE_CLIPPING_RATIO =
            0.02f

        private const val QUALITY_WEIGHT_SNR =
            0.50f

        private const val QUALITY_WEIGHT_SPEECH_BAND_ENERGY =
            0.25f

        private const val QUALITY_WEIGHT_RAW_VALIDITY =
            0.15f

        private const val QUALITY_WEIGHT_CLIPPING =
            0.10f

        private const val NOISE_FLOOR_WARMUP_FRAMES =
            10L

        private const val NOISE_FLOOR_FALL_ALPHA =
            0.18f

        private const val NOISE_FLOOR_NEAR_RATIO =
            1.50f

        private const val NOISE_FLOOR_NEAR_RISE_ALPHA =
            0.01f

        private const val NOISE_FLOOR_SPEECH_GUARDED_RISE_ALPHA =
            0.001f

        /* GCC confidence. */
        private const val MINIMUM_USEFUL_COHERENCE =
            0.20f

        private const val TARGET_COHERENCE =
            0.80f

        private const val MINIMUM_RELIABLE_COHERENCE =
            0.35f

        private const val MINIMUM_USEFUL_PEAK_SHARPNESS =
            0.02f

        private const val TARGET_PEAK_SHARPNESS =
            0.20f

        private const val MINIMUM_RELIABLE_PEAK_SHARPNESS =
            0.04f

        private const val LAG_CONFIDENCE_WEIGHT_COHERENCE =
            0.55f

        private const val LAG_CONFIDENCE_WEIGHT_PEAK =
            0.30f

        private const val LAG_CONFIDENCE_WEIGHT_CHANNEL_QUALITY =
            0.15f

        private const val MINIMUM_RELIABLE_LAG_CONFIDENCE =
            0.40f

        /* Tracking. */
        private const val SPATIAL_ANALYSIS_INTERVAL_FRAMES =
            2L

        private const val LAG_HISTORY_SIZE =
            5

        private const val MINIMUM_LAG_HISTORY_FOR_TARGET =
            3

        private const val MAXIMUM_LAG_HISTORY_SPREAD_SAMPLES =
            2.5f

        private const val UNRELIABLE_ANALYSES_BEFORE_LAG_HISTORY_RESET =
            5

        private const val MAXIMUM_STEERING_STEP_PER_ANALYSIS =
            0.35f

        private const val MAXIMUM_ACCEPTED_TRACKING_JUMP_SAMPLES =
            3.0f

        private const val HIGH_CONFIDENCE_REQUIRED_FOR_LARGE_JUMP =
            0.90f

        private const val STEERING_EVIDENCE_HOLD_ANALYSES =
            2

        private const val MINIMUM_STEERING_LAG_TO_APPLY =
            0.05f

        /* Decision engine. */
        private const val MINIMUM_CHANNEL_QUALITY_FOR_STEERING =
            0.25f

        private const val MINIMUM_PRIMARY_QUALITY_FOR_WEIGHTED_MIX =
            0.30f

        private const val MINIMUM_SECONDARY_QUALITY_FOR_WEIGHTED_MIX =
            0.20f

        private const val REFERENCE_CHANNEL_QUALITY_ADVANTAGE =
            0.22f

        private const val NEGATIVE_COHERENCE_REFERENCE_THRESHOLD =
            -0.25f

        private const val QUALITY_WEIGHT_FLOOR =
            0.04f

        private const val MINIMUM_MIX_CHANNEL_WEIGHT =
            0.15f

        private const val MAXIMUM_MIX_CHANNEL_WEIGHT =
            0.85f

        private const val REQUIRED_DECISION_CONFIRMATIONS =
            3

        private const val STARTUP_FAST_DECISION_FRAMES =
            6L

        private const val MAXIMUM_WEIGHT_STEP_PER_ANALYSIS =
            0.08f

        /* Diagnostic. */
        private const val DIAGNOSTIC_INTERVAL_FRAMES =
            40L

        private const val DIAGNOSTIC_STEERING_BUCKET_SAMPLES =
            0.50f

        private const val RMS_EPSILON =
            1.0e-9f
    }
}

/* ============================================================================
 * Guardian Voice Focus V3 — classes locales intégrées au même fichier source.
 * Ce regroupement NE modifie PAS la séparation fonctionnelle :
 * GuardianSpatialAudio orchestre le PCM ; le sélecteur et le moteur Focus
 * restent des classes internes au package pour éviter toute modification des
 * appels GuardianAudioEngine / GuardianProtectionService / QA.
 * Les quatre fichiers métier GuardianVoiceEngine, GuardianVoiceEvent,
 * GuardianVoiceUnderstandingEngine et GuardianVoiceFusionEngine restent intacts.
 * ============================================================================ */

/** Mode explicite. OBSERVE ne modifie jamais le PCM fourni aux moteurs. */
enum class GuardianVoiceFocusMode { OFF, OBSERVE, ACTIVE }

/**
 * Guardian Voice Focus V3: choix temporel d'une vue PCM.
 * Aucun nom de canal n'implique une position de microphone physique.
 * Les scores sont des mesures acoustiques sur LA MEME fenêtre, pas des preuves ASR.
 * La classe ne déclenche aucune alerte et ne déduit aucun angle physique.
 */
internal class GuardianVoiceFocusSelector(
    private val confirmations: Int = 3,
    private val improvementMarginDb: Float = 1.25f,
    private val switchMarginDb: Float = 0.9f,
    private val emergencyDropDb: Float = 5.0f,
    private val holdDuringSpeech: Boolean = false,
) {
    enum class View { CHANNEL_0, CHANNEL_1, WEIGHTED_FUSION, ALIGNED_FUSION }
    enum class State { SCANNING, VOICE_CANDIDATE, VOICE_LOCKED, TRACKING, TRANSITION, UNSTABLE }

    data class Observation(
        val channel0Db: Float,
        val channel1Db: Float,
        val weightedDb: Float = Float.NEGATIVE_INFINITY,
        val alignedDb: Float = Float.NEGATIVE_INFINITY,
        val channel0Valid: Boolean = true,
        val channel1Valid: Boolean = true,
        val weightedValid: Boolean = false,
        val alignedValid: Boolean = false,
        val speechEvidence: Boolean = false,
        val alignmentStable: Boolean = false,
        val confirmedSpeech: Boolean = false,
    )

    data class Decision(
        val view: View,
        val state: State,
        val transitioned: Boolean,
        val bestSingleDb: Float,
        val selectedDb: Float,
        val fusionAdvantageDb: Float,
        val reliableSpeech: Boolean,
    )

    private var active = View.CHANNEL_0
    private var pending = View.CHANNEL_0
    private var pendingCount = 0
    private var speechFrames = 0
    private var scansSinceSpeech = 0
    private var lockAcquired = false
    private var transitions = 0L
    private var confirmedLock = false

    fun reset() {
        active = View.CHANNEL_0
        pending = active
        pendingCount = 0
        speechFrames = 0
        scansSinceSpeech = 0
        lockAcquired = false
        transitions = 0L
        confirmedLock = false
    }

    fun currentView(): View = active
    fun transitionCount(): Long = transitions

    fun update(o: Observation): Decision {
        val score = floatArrayOf(o.channel0Db, o.channel1Db, o.weightedDb, o.alignedDb)
        val valid = booleanArrayOf(o.channel0Valid, o.channel1Valid, o.weightedValid,
            o.alignedValid && o.alignmentStable)
        for (i in valid.indices) {
            valid[i] = valid[i] && score[i].isFinite()
        }
        if (!valid[0] && !valid[1]) {
            pendingCount = 0
            lockAcquired = false
            confirmedLock = false
            return Decision(active, State.UNSTABLE, false, Float.NEGATIVE_INFINITY,
                Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY, false)
        }

        val bestSingle = when {
            !valid[0] -> View.CHANNEL_1
            !valid[1] -> View.CHANNEL_0
            score[1] > score[0] -> View.CHANNEL_1
            else -> View.CHANNEL_0
        }
        val bestSingleDb = score[bestSingle.ordinal]
        var winner = bestSingle
        for (candidate in listOf(View.WEIGHTED_FUSION, View.ALIGNED_FUSION)) {
            val i = candidate.ordinal
            // Une fusion n'a aucun droit acquis : elle doit dépasser le meilleur canal.
            if (valid[i] && score[i] >= bestSingleDb + improvementMarginDb &&
                score[i] > score[winner.ordinal] + if (winner.ordinal > 1) switchMarginDb else 0f) {
                winner = candidate
            }
        }
        // Experimental: acquire from voice/noise quality once per neural
        // utterance, retain until its end. Invalid/clipped channels can fail over.
        if (holdDuringSpeech) {
            if (!o.confirmedSpeech) confirmedLock = false
            if (o.confirmedSpeech && valid[active.ordinal]) {
                val changed = !confirmedLock && winner != active
                if (changed) { active = winner; transitions++ }
                confirmedLock = true
                pendingCount = 0
                lockAcquired = true
                return Decision(active, if (changed) State.TRANSITION else State.TRACKING,
                    changed, bestSingleDb, score[active.ordinal],
                    score[winner.ordinal] - bestSingleDb, true)
            }
        }
        // Ne pas apprendre de direction à partir du silence, des frottements ou du bruit.
        if (!o.speechEvidence) {
            scansSinceSpeech++
            if (scansSinceSpeech >= 5) {
                speechFrames = 0
                lockAcquired = false
                pendingCount = 0
            }
            // Seule une panne réelle autorise un basculement hors parole.
            if (!valid[active.ordinal]) {
                active = bestSingle
            }
            return Decision(active, State.SCANNING, false, bestSingleDb,
                score[active.ordinal], score[winner.ordinal] - bestSingleDb, false)
        }
        scansSinceSpeech = 0
        speechFrames++

        if (!valid[active.ordinal]) {
            val changed = active != winner
            active = winner
            pendingCount = 0
            if (changed) transitions++
            lockAcquired = true
            return Decision(active, State.TRANSITION, changed, bestSingleDb,
                score[active.ordinal], score[winner.ordinal] - bestSingleDb, true)
        }

        // Maintenir la vue active en quasi-égalité. Une fusion active perd sa
        // priorité si elle ne dépasse plus réellement le meilleur canal.
        val currentDb = score[active.ordinal]
        val fusionNoLongerUseful = active.ordinal > 1 &&
            currentDb < bestSingleDb + improvementMarginDb * 0.45f
        val shouldConsiderSwitch = (winner != active) &&
            (fusionNoLongerUseful || score[winner.ordinal] >= currentDb + switchMarginDb)
        if (shouldConsiderSwitch) {
            if (pending == winner) pendingCount++ else {
                pending = winner
                pendingCount = 1
            }
        } else {
            pending = active
            pendingCount = 0
        }
        val urgent = winner != active &&
            (bestSingleDb - currentDb >= emergencyDropDb || !valid[active.ordinal])
        val changed = (pendingCount >= confirmations || urgent) && winner != active
        if (changed) {
            active = winner
            transitions++
            pendingCount = 0
            lockAcquired = true
        }
        val state = when {
            changed -> State.TRANSITION
            !lockAcquired && speechFrames < confirmations -> State.VOICE_CANDIDATE
            !lockAcquired -> { lockAcquired = true; State.VOICE_LOCKED }
            else -> State.TRACKING
        }
        return Decision(active, state, changed, bestSingleDb,
            score[active.ordinal], score[winner.ordinal] - bestSingleDb, true)
    }
}

/**
 * Voice Focus V3, sans dépendance Android :
 * - observe quatre vues SIMULTANÉES du même PCM stéréo ;
 * - évalue le gain sur bruit calme précédemment observé ;
 * - suit une vue, pas une position physique ni une identité vocale ;
 * - refuse la fusion lorsqu'elle n'améliore pas la meilleure voie seule ;
 * - traite le décalage PROCESSED comme une synchronisation inter-canaux
 *   et jamais comme un angle de provenance ;
 * - garde une mémoire PCM de 2 s pour pré-roll / diagnostic (aucun envoi réseau).
 * Les valeurs mesurées ne sont PAS une preuve de reconnaissance correcte.
 */
internal class GuardianVoiceFocusEngine(
    private val sampleRate: Int,
    private val frameCapacity: Int,
    private val allowRelativeAlignment: Boolean,
    private val alignmentLimitSamples: Float = 13.0f,
    private val holdDuringSpeech: Boolean = false,
) {
    enum class Mode { OBSERVE, ACTIVE }
    data class FocusSnapshot(
        val selected: GuardianVoiceFocusSelector.View,
        val state: GuardianVoiceFocusSelector.State,
        val scoresDb: List<Float>,
        val activeScoreDb: Float,
        val bestSingleScoreDb: Float,
        val acceptedFusionGainDb: Float,
        val relativeLagSamples: Float,
        val relativeLagStable: Boolean,
        val relativeLagConfidence: Float,
        val speechEvidence: Boolean,
        val transitionCount: Long,
        val likelyObstructedChannel: Int?,
        val readyForEvidence: Boolean,
        val selectedSpeechFrameCounts: List<Long>,
        val acousticBestViewFrameCounts: List<Long>,
        val alignedSpeechFrameCount: Long,
    )
    /** 12 s maximum, 3 s de pré-roll, aucun disque/réseau. */
    data class ShadowUtterance(
        val views: List<FloatArray>,
        val sampleRateHz: Int,
        val sampleCount: Int,
        val truncated: Boolean,
        val alignedMeaningful: Boolean,
    )

    private val shadowCapacity = sampleRate * 14 // 12 s + marge de pré-roll
    private val shadowRings = Array(4) { FloatArray(shadowCapacity) }
    private var shadowWrite = 0
    private var shadowTotalSamples = 0L
    private var shadowUtteranceStart: Long? = null
    private var shadowAlignedSamples: Long = 0L

    @Synchronized
    fun beginShadowUtterance() {
        if (alignmentInvalidated || shadowUtteranceStart != null) return
        shadowUtteranceStart = (shadowTotalSamples - sampleRate * 3L).coerceAtLeast(0L)
        shadowAlignedSamples = 0L
    }

    @Synchronized
    fun endShadowUtterance(): ShadowUtterance? {
        val start = shadowUtteranceStart ?: return null
        shadowUtteranceStart = null
        if (alignmentInvalidated) return null
        val earliest = (shadowTotalSamples - shadowCapacity).coerceAtLeast(0L)
        val actualStart = max(start, max(earliest, shadowTotalSamples - sampleRate * 12L))
        val truncated = start < actualStart
        val count = (shadowTotalSamples - actualStart).toInt()
        if (count < sampleRate || count > sampleRate * 12) return null
        val views = List(4) { v ->
            FloatArray(count) { index ->
                shadowRings[v][((actualStart + index) % shadowCapacity).toInt()]
            }
        }
        return ShadowUtterance(
            views, sampleRate, count, truncated,
            alignedMeaningful = shadowAlignedSamples >= sampleRate / 3L,
        )
    }

    private val selector = GuardianVoiceFocusSelector(holdDuringSpeech = holdDuringSpeech)
    private val sampleViews = Array(4) { FloatArray(frameCapacity) }
    private val bandViews = Array(4) { FloatArray(frameCapacity) }
    private val quietFloor = FloatArray(4)
    private val quietFrames = IntArray(4)
    private val lagHistory = FloatArray(5)
    private val lagScratch = FloatArray(5)
    private var lagHistoryCount = 0
    private var lagHistoryIndex = 0
    private var lagFramesWithoutProof = 0
    private var smoothedLag = 0f
    private var recentSpeechFrames = 0
    private var tentativeFramesLeft = 0
    private var tentativeCooldownFrames = 0
    private var activeSpeechFrames = 0
    private var previousSelection = GuardianVoiceFocusSelector.View.CHANNEL_0
    private var transitionFrom = previousSelection
    private var transitionFramesLeft = 0
    @Volatile private var voiceHint = false
    private var alignmentInvalidated = false
    private var confidence = 0f
    private var framesSeen = 0L
    private val selectedSpeechFrames = LongArray(4)
    private val acousticWinnerSpeechFrames = LongArray(4)
    private var alignedSpeechFrames = 0L
    // Le pré-roll est une mémoire locale bornée, PAS un enregistrement permanent.
    private val history = ShortArray((sampleRate * 2).coerceAtLeast(frameCapacity * 2) * 2)
    private var historyWrite = 0
    private var historySize = 0
    private val bandHistory0 = FloatArray(18)
    private val bandHistory1 = FloatArray(18)
    private val rawHistory0 = FloatArray(18)
    private val rawHistory1 = FloatArray(18)
    private var bandHistorySize = 0
    @Volatile private var snapshot: FocusSnapshot? = null

    fun getSnapshot(): FocusSnapshot? = snapshot
    fun setVoiceHint(active: Boolean) { voiceHint = active }
    fun invalidateRuntimeRoute() {
        shadowUtteranceStart = null
        shadowAlignedSamples = 0L
        alignmentInvalidated = true
        lagHistoryCount = 0
        lagFramesWithoutProof = 0
        smoothedLag = 0f
        quietFloor.fill(0f)
        quietFrames.fill(0)
        selector.reset()
        snapshot = null
    }
    fun reset() {
        shadowUtteranceStart = null
        shadowAlignedSamples = 0L
        shadowTotalSamples = 0L
        shadowWrite = 0
        selector.reset()
        quietFloor.fill(0f)
        quietFrames.fill(0)
        lagHistory.fill(0f)
        lagHistoryCount = 0
        lagHistoryIndex = 0
        lagFramesWithoutProof = 0
        smoothedLag = 0f
        recentSpeechFrames = 0
        tentativeFramesLeft = 0
        tentativeCooldownFrames = 0
        activeSpeechFrames = 0
        transitionFramesLeft = 0
        previousSelection = GuardianVoiceFocusSelector.View.CHANNEL_0
        transitionFrom = previousSelection
        voiceHint = false
        alignmentInvalidated = false
        confidence = 0f
        framesSeen = 0
        selectedSpeechFrames.fill(0L)
        acousticWinnerSpeechFrames.fill(0L)
        alignedSpeechFrames = 0L
        historyWrite = 0
        historySize = 0
        bandHistorySize = 0
        bandHistory0.fill(0f)
        bandHistory1.fill(0f)
        rawHistory0.fill(0f)
        rawHistory1.fill(0f)
        snapshot = null
    }
    /** Copie les plus récents échantillons stéréo entrelacés, jamais plus de 2 s. */
    fun copyPreRoll(destination: ShortArray): Int {
        val count = (min(destination.size, historySize) / 2) * 2
        if (count <= 0) return 0
        var p = (historyWrite - count + history.size) % history.size
        for (i in 0 until count) {
            destination[i] = history[p]
            p = (p + 1) % history.size
        }
        return count
    }

    /** Le lag reçu vient de GCC-PHAT ; son signe est celui de GuardianSpatialAudio. */
    @Synchronized
    fun process(
        ch0: ShortArray, ch1: ShortArray,
        band0: FloatArray, band1: FloatArray,
        count: Int, output: ShortArray,
        lagSamples: Float, lagReliable: Boolean,
        lagConfidence: Float, coherence: Float,
        lagMeasurementFresh: Boolean,
    ): Int {
        val n = min(count, min(frameCapacity, output.size))
        if (n <= 0) return 0
        framesSeen++
        // Toujours préserver les deux sources, même quand la sortie est mono.
        for (i in 0 until n) {
            history[historyWrite] = ch0[i]
            history[(historyWrite + 1) % history.size] = ch1[i]
            historyWrite = (historyWrite + 2) % history.size
            historySize = min(historySize + 2, history.size)
        }
        val lagIsCredible = allowRelativeAlignment && !alignmentInvalidated &&
            lagReliable && lagConfidence >= 0.78f && coherence >= 0.70f &&
            lagSamples.isFinite() && abs(lagSamples) <= alignmentLimitSamples
        if (lagMeasurementFresh && lagIsCredible) {
            lagFramesWithoutProof = 0
            confidence = lagConfidence
            lagHistory[lagHistoryIndex] = lagSamples
            lagHistoryIndex = (lagHistoryIndex + 1) % lagHistory.size
            lagHistoryCount = min(lagHistoryCount + 1, lagHistory.size)
        } else if (lagMeasurementFresh) {
            lagFramesWithoutProof++
            if (lagFramesWithoutProof >= 4) lagHistoryCount = 0
        }
        var alignmentStable = false
        if (lagHistoryCount >= 3) {
            for (i in 0 until lagHistoryCount) lagScratch[i] = lagHistory[i]
            for (i in 1 until lagHistoryCount) {
                val value = lagScratch[i]
                var j = i - 1
                while (j >= 0 && lagScratch[j] > value) {
                    lagScratch[j + 1] = lagScratch[j]
                    j--
                }
                lagScratch[j + 1] = value
            }
            val spread = lagScratch[lagHistoryCount - 1] - lagScratch[0]
            if (spread <= 1.0f && lagFramesWithoutProof < 3) {
                val target = lagScratch[lagHistoryCount / 2]
                smoothedLag += (target - smoothedLag).coerceIn(-0.8f, 0.8f)
                alignmentStable = abs(smoothedLag - target) <= 1.0f
            }
        }
        if (!alignmentStable) smoothedLag *= 0.7f
        val floor0 = quietFloor[0]
        val floor1 = quietFloor[1]
        val weight0 = if (floor0 > 0f && floor1 > 0f) {
            val a = floor1 * floor1
            val b = floor0 * floor0
            (a / (a + b + 1e-12f)).coerceIn(0.20f, 0.80f)
        } else 0.5f
        val weight1 = 1f - weight0
        var squares0 = 0.0
        var squares1 = 0.0
        for (i in 0 until n) {
            val a = ch0[i].toFloat() / 32768f
            val b = ch1[i].toFloat() / 32768f
            sampleViews[0][i] = a
            sampleViews[1][i] = b
            bandViews[0][i] = band0[i]
            bandViews[1][i] = band1[i]
            squares0 += band0[i].toDouble() * band0[i].toDouble()
            squares1 += band1[i].toDouble() * band1[i].toDouble()
            sampleViews[2][i] = weight0 * a + weight1 * b
            bandViews[2][i] = weight0 * band0[i] + weight1 * band1[i]
            if (alignmentStable && abs(smoothedLag) >= 0.05f) {
                if (smoothedLag > 0) {
                    sampleViews[3][i] = weight0 * delayed(raw = sampleViews[0],
                        history = rawHistory0, index = i, delay = smoothedLag,
                        prior = bandHistorySize) + weight1 * b
                    bandViews[3][i] = weight0 * delayed(raw = bandViews[0],
                        history = bandHistory0, index = i, delay = smoothedLag,
                        prior = bandHistorySize) + weight1 * band1[i]
                } else {
                    sampleViews[3][i] = weight0 * a + weight1 * delayed(raw = sampleViews[1],
                        history = rawHistory1, index = i, delay = -smoothedLag,
                        prior = bandHistorySize)
                    bandViews[3][i] = weight0 * band0[i] + weight1 * delayed(raw = bandViews[1],
                        history = bandHistory1, index = i, delay = -smoothedLag,
                        prior = bandHistorySize)
                }
            } else {
                sampleViews[3][i] = sampleViews[2][i]
                bandViews[3][i] = bandViews[2][i]
            }
        }
        // Ne pas traiter une fusion non alignée comme une quatrième preuve
        // indépendante. La disponibilité d'un vrai alignement est par utterance.
        if (shadowUtteranceStart != null && alignmentStable &&
            abs(smoothedLag) >= 0.05f) shadowAlignedSamples += n.toLong()

        // Une seule capture, quatre vues exactes sur la même timeline.
        // Aucun décodage ASR dans le thread de capture.
        for (i in 0 until n) {
            for (v in 0..3) shadowRings[v][shadowWrite] = sampleViews[v][i]
            shadowWrite = (shadowWrite + 1) % shadowCapacity
            shadowTotalSamples++
        }
        val bands = FloatArray(4) // 4 agrégats par fenêtre ; pas d'allocation par échantillon.
        for (view in 0..3) {
            var sum = 0.0
            for (i in 0 until n) sum += bandViews[view][i].toDouble() * bandViews[view][i].toDouble()
            bands[view] = sqrt(sum / n).toFloat()
        }
        // On ne met jamais le noise floor à jour après détection d'un début vocal,
        // ni pendant un COMMIT neural : le modèle ne doit pas apprendre la voix comme bruit.
        val ratios = floatArrayOf(
            if (floor0 > 1e-7f) bands[0] / floor0 else 1f,
            if (floor1 > 1e-7f) bands[1] / floor1 else 1f,
        )
        val onset = quietFrames[0] >= 6 && quietFrames[1] >= 6 &&
            max(ratios[0], ratios[1]) >= 1.45f
        if (tentativeCooldownFrames > 0) tentativeCooldownFrames--
        if (!voiceHint && tentativeFramesLeft == 0 && tentativeCooldownFrames == 0) {
            if (onset) recentSpeechFrames = min(2, recentSpeechFrames + 1)
            else recentSpeechFrames = 0
            if (recentSpeechFrames >= 2) {
                tentativeFramesLeft = 14 // maximum 700 ms sans confirmation neurale.
                tentativeCooldownFrames = 30 // 1,5 s : pas de re-verrouillage de bruit.
                recentSpeechFrames = 0
            }
        }
        val tentative = !voiceHint && tentativeFramesLeft > 0
        if (tentativeFramesLeft > 0) tentativeFramesLeft--
        val provisionalSpeech = voiceHint || tentative
        // Une montée acoustique n'est JAMAIS une preuve de voix pour AUTO.
        val confirmedSpeech = voiceHint
        if (confirmedSpeech) activeSpeechFrames++ else activeSpeechFrames = 0
        if (!provisionalSpeech) {
            for (v in 0..3) {
                val floor = quietFloor[v]
                // Same startup rule as the mono tracker in the candidate:
                // no usable measurement in an all-zero hardware startup block.
                if (holdDuringSpeech && floor == 0f && bands[v] == 0f) continue
                val candidate = bands[v].coerceAtLeast(0.00002f)
                quietFloor[v] = if (floor <= 0f) candidate else floor +
                    (candidate - floor) * (if (candidate < floor) 0.12f else 0.012f)
                quietFrames[v]++
            }
        }
        val scores = FloatArray(4)
        val modelReady = quietFrames[0] >= 6 && quietFrames[1] >= 6
        for (v in 0..3) {
            val noise = quietFloor[v].coerceAtLeast(0.00002f)
            val voicePower = max(0f, bands[v] * bands[v] - noise * noise)
            // Continu, non saturé à 1.0 : un écart réel doit rester visible.
            scores[v] = (20.0 * log10((sqrt(voicePower) + 1e-7f) / noise)).toFloat()
                .coerceIn(-60f, 120f)
        }
        var clip0 = 0
        var clip1 = 0
        for (i in 0 until n) {
            if (kotlin.math.abs(ch0[i].toInt()) >= 32000) clip0++
            if (kotlin.math.abs(ch1[i].toInt()) >= 32000) clip1++
        }
        val valid0 = bands[0] >= 0.00004f && clip0 <= n / 50
        val valid1 = bands[1] >= 0.00004f && clip1 <= n / 50
        val bestBand = max(bands[0], bands[1])
        // Protection additionnelle : préserver l'enveloppe vocale, pas seulement
        // optimiser un ratio énergie/bruit qui peut se tromper sur un transitoire.
        val weightedAllowed = modelReady && valid0 && valid1 && bestBand > 0f &&
            bands[2] >= bestBand * 0.90f
        val alignedAllowed = modelReady && valid0 && valid1 &&
            alignmentStable && bestBand > 0f &&
            bands[3] >= bestBand * 0.90f
        val observation = GuardianVoiceFocusSelector.Observation(
            channel0Db = scores[0], channel1Db = scores[1], weightedDb = scores[2],
            alignedDb = scores[3], channel0Valid = valid0, channel1Valid = valid1,
            weightedValid = weightedAllowed, alignedValid = alignedAllowed,
            speechEvidence = provisionalSpeech && modelReady,
            confirmedSpeech = confirmedSpeech,
            alignmentStable = alignmentStable,
        )
        val selection = selector.update(observation)
        val chosen = selection.view
        if (confirmedSpeech && modelReady) {
            selectedSpeechFrames[chosen.ordinal]++
            if (alignmentStable) alignedSpeechFrames++
            var best = if (scores[0] >= scores[1]) 0 else 1
            for (i in 2..3) {
                if (i == 2 && !weightedAllowed || i == 3 && !alignedAllowed) continue
                if (scores[i] > scores[best]) best = i
            }
            acousticWinnerSpeechFrames[best]++
        }
        if (chosen != previousSelection) {
            transitionFrom = previousSelection
            transitionFramesLeft = if (
                previousSelection == GuardianVoiceFocusSelector.View.CHANNEL_0 && !valid0 ||
                previousSelection == GuardianVoiceFocusSelector.View.CHANNEL_1 && !valid1
            ) 1 else 3
            previousSelection = chosen
        }
        // Crossfade temporel 3*50 ms : ni commutation frame-à-frame, ni perte
        // de syllabe au déplacement de la source.
        val from = transitionFrom.ordinal
        val to = chosen.ordinal
        val transitionLength = if (transitionFramesLeft == 1) 1f else 3f
        for (i in 0 until n) {
            val interpolation = if (transitionFramesLeft > 0) {
                ((transitionLength - transitionFramesLeft).toFloat() + (i.toFloat() / n)) / transitionLength
            } else 1f
            val alpha = interpolation.coerceIn(0f, 1f)
            val value = sampleViews[from][i] * (1f - alpha) + sampleViews[to][i] * alpha
            output[i] = (value * 32768f).toInt().coerceIn(-32768, 32767).toShort()
        }
        if (transitionFramesLeft > 0) transitionFramesLeft--
        if (transitionFramesLeft == 0) transitionFrom = chosen
        // Historique court pour interpolation causale aux frontières de frames.
        for (k in bandHistory0.indices) {
            val fromIndex = n - bandHistory0.size + k
            if (fromIndex >= 0) {
                bandHistory0[k] = band0[fromIndex]
                bandHistory1[k] = band1[fromIndex]
                rawHistory0[k] = sampleViews[0][fromIndex]
                rawHistory1[k] = sampleViews[1][fromIndex]
            }
        }
        bandHistorySize = min(n, bandHistory0.size)
        val likelyOccluded = when {
            confirmedSpeech && modelReady && bands[0] * 2.0f < bands[1] -> 0
            confirmedSpeech && modelReady && bands[1] * 2.0f < bands[0] -> 1
            else -> null
        } // soupçon de masque, jamais diagnostic matériel certain.
        snapshot = FocusSnapshot(
            selected = chosen,
            state = if (tentative && !confirmedSpeech) {
                GuardianVoiceFocusSelector.State.VOICE_CANDIDATE
            } else selection.state,
            scoresDb = scores.toList(), activeScoreDb = selection.selectedDb,
            bestSingleScoreDb = selection.bestSingleDb,
            acceptedFusionGainDb = if (chosen.ordinal >= 2) selection.selectedDb - selection.bestSingleDb else 0f,
            relativeLagSamples = smoothedLag, relativeLagStable = alignmentStable,
            relativeLagConfidence = confidence, speechEvidence = confirmedSpeech,
            transitionCount = selector.transitionCount(),
            likelyObstructedChannel = likelyOccluded,
            readyForEvidence = modelReady && activeSpeechFrames >= 2,
            selectedSpeechFrameCounts = selectedSpeechFrames.toList(),
            acousticBestViewFrameCounts = acousticWinnerSpeechFrames.toList(),
            alignedSpeechFrameCount = alignedSpeechFrames,
        )
        return n
    }

    private fun delayed(raw: FloatArray, history: FloatArray, index: Int,
        delay: Float, prior: Int): Float {
        val pos = index.toFloat() - delay.coerceAtMost(13f)
        val lo = floor(pos.toDouble()).toInt()
        val fraction = pos - lo
        fun sample(i: Int): Float = if (i >= 0) raw[i] else {
            val h = history.size + i
            if (h in history.indices && -i <= prior) history[h] else 0f
        }
        return sample(lo) * (1f - fraction) + sample(lo + 1) * fraction
    }
}
