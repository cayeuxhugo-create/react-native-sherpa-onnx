package expo.modules.guardiannativeprotection

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaRecorder
import android.media.MicrophoneInfo
import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.os.SystemClock
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.util.Log
import kotlin.math.abs
import kotlin.math.sin

/**
 * Détecte les capacités audio réellement disponibles
 * sur le téléphone utilisé par Guardian.
 *
 * Cette classe :
 * - ne capture aucun son ;
 * - ne démarre aucun moteur ;
 * - ne décide d'aucune urgence ;
 * - ne dépend ni de Sherpa ni du VoiceEngine.
 *
 * Son rôle est uniquement de caractériser l'appareil
 * et de proposer un profil audio adapté.
 */
object GuardianAudioCapabilities {

    data class MicrophoneCapability(
    val id: Int,
    val description: String,
    val deviceType: Int,
    val deviceTypeName: String,

    val location: Int,
    val locationName: String,

    val group: Int,
    val indexInGroup: Int,

    val directionality: Int,
    val directionalityName: String,

    val sensitivity: Float?,

    val positionX: Float?,
    val positionY: Float?,
    val positionZ: Float?,

    val orientationX: Float?,
    val orientationY: Float?,
    val orientationZ: Float?,

    val frequencyResponsePointCount: Int,

    /*
     * Ce champ signifie uniquement :
     * microphone physiquement intégré au terminal.
     *
     * Il ne signifie PAS que Guardian l'utilise.
     */
    val builtIn: Boolean,
)

    enum class PerformanceProfile {
        HIGH,
        BALANCED,
        FALLBACK,
    }

    enum class CaptureTopology {
        /*
         * Une seule information acoustique indépendante exploitable.
         */
        MONO_ONLY,

        /*
         * Deux informations acoustiques indépendantes exploitables.
         *
         * Compatible avec GuardianSpatialAudio V1/V2.
         */
        DUAL_MIC,

        /*
         * Plus de deux informations acoustiques indépendantes exploitables.
         *
         * Attention : ce niveau ne dépend plus du simple nombre de canaux
         * qu'Android accepte d'ouvrir. Les canaux silencieux ou dupliqués
         * ne comptent plus comme canaux acoustiquement indépendants.
         */
        MULTI_MIC_N,
    }

    data class AdaptiveCaptureProfile(
        /*
         * Topologie acoustique réellement exploitable.
         */
        val topology: CaptureTopology,

        /*
         * Compatibilité historique.
         *
         * Ce champ représente désormais le nombre maximal de canaux
         * qu'Android a effectivement ouvert et que Guardian a pu analyser.
         * Il ne doit PAS être interprété comme le nombre de micros utiles.
         */
        val maximumVerifiedChannelCount: Int,

        /*
         * Nombre maximal de canaux réellement ouverts par AudioRecord.
         */
        val maximumOpenedChannelCount: Int,

        /*
         * Nombre maximal de canaux contenant réellement un signal PCM
         * exploitable pendant le probe.
         */
        val maximumUsableChannelCount: Int,

        /*
         * Nombre maximal d'informations acoustiques indépendantes après
         * élimination des canaux silencieux et des duplications évidentes.
         */
        val maximumIndependentChannelCount: Int,

        /*
         * Vrai uniquement si MIC + STEREO expose deux canaux utilisables,
         * indépendants, avec au moins deux microphones actifs déclarés.
         */
        val dualMicSpatialAvailable: Boolean,

        /*
         * Nombre de canaux que le runtime actuel doit effectivement utiliser.
         *
         * GuardianSpatialAudio reste limité à deux canaux pour l'instant.
         */
        val runtimeChannelCount: Int,

        /*
         * Explication destinée aux diagnostics.
         */
        val reason: String,
    )

    data class Capabilities(
        val sdkInt: Int,
        val supportedAbis: List<String>,

        /*
 * Inventaire matériel déclaré par Android.
 *
 * ATTENTION :
 * ce n'est pas encore la liste des micros réellement
 * utilisés par GuardianAudioEngine.
 */
val microphones: List<MicrophoneCapability>,
val microphoneCount: Int,
val builtInMicrophoneCount: Int,

/*
 * Plusieurs micros présents physiquement.
 *
 * Cela rend certaines stratégies spatiales possibles
 * en théorie, mais ne garantit PAS plusieurs canaux PCM.
 */
val multiMicHardwareCandidate: Boolean,

/*
 * Android expose les API permettant à AudioRecord
 * de demander une orientation / focalisation.
 *
 * "API disponible" ne signifie pas encore que
 * le constructeur acceptera notre demande.
 */
val microphoneDirectionApiAvailable: Boolean,
val microphoneFieldDimensionApiAvailable: Boolean,

        val noiseSuppressorAvailable: Boolean,
        val automaticGainControlAvailable: Boolean,

        val unprocessedSupported: Boolean,
        val voiceRecognitionSupported: Boolean,

        val recommendedAudioSource: Int,
        val recommendedAudioSourceName: String,

        /*
         * Benchmark synthétique local.
         */
        val benchmarkAverageMs: Double,
        val benchmarkP95Ms: Double,
        val realtimeHeadroomRatio: Double,

        val performanceProfile: PerformanceProfile,

        /*
         * Ces deux champs préparent les futures briques.
         * Ils ne les activent pas encore.
         */
        val neuralEnhancementAllowed: Boolean,
        val speakerVerificationAllowed: Boolean,
    )


    enum class ChannelMappingType {
        DIRECT,
        PROCESSED,
        UNKNOWN,
    }

    data class ChannelMappingDetail(
        val microphoneId: Int,
        val microphoneDescription: String,
        val microphoneGroup: Int,
        val microphoneIndexInGroup: Int,
        val channelIndex: Int,
        val mappingType: ChannelMappingType,
    ) {
        val microphoneIdentity: String
            get() =
                "$microphoneId|$microphoneDescription|" +
                    "$microphoneGroup|$microphoneIndexInGroup"
    }

    enum class CaptureRouteClassification {
        UNAVAILABLE,
        UNUSABLE,
        MONO_DIRECT,
        MONO_PROCESSED,
        MONO_MIXED_OR_UNKNOWN,
        DUPLICATED_MULTICHANNEL,
        SPATIAL_DIRECT,
        SPATIAL_PROCESSED,
        SPATIAL_MIXED_OR_UNKNOWN,
    }

    enum class CaptureRouteMappingNature {
        NONE,
        DIRECT,
        PROCESSED,
        MIXED,
        UNKNOWN,
    }

    data class CaptureRouteQualification(
        val openedChannelCount: Int,
        val usableChannelCount: Int,
        val independentChannelCount: Int,
        val activeMicrophoneCount: Int,
        val mappingNature: CaptureRouteMappingNature,
        val classification: CaptureRouteClassification,
        val spatialEligible: Boolean,
        val physicalMappingReliable: Boolean,
        val reason: String,
    )

    enum class SpatialDelayTrust {
        NONE,

        /*
         * Le décalage entre canaux peut être observé pour diagnostic,
         * mais il ne doit pas être interprété comme un TDOA acoustique
         * physique. C'est le cas des routes PROCESSED / ambiguës.
         */
        INTER_CHANNEL_ONLY,

        /*
         * Chaque canal indépendant est relié DIRECTEMENT à un microphone
         * physique distinct et leur géométrie est exploitable.
         */
        PHYSICAL_TDOA,
    }

    data class SpatialCaptureConstraints(
        val routeClassification: CaptureRouteClassification,
        val delayTrust: SpatialDelayTrust,
        val steeringAllowed: Boolean,
        val physicalGeometryReliable: Boolean,
        val physicalMicrophoneDistanceMeters: Double?,
        val maximumPhysicalTdoaSeconds: Double?,
        val reason: String,
    )


    enum class AutomaticCaptureRouteMode {
        MONO,
        SPATIAL_TWO_CHANNEL,
    }

    enum class AutomaticCaptureRouteConfidence {
        HIGH,
        MEDIUM,
        FALLBACK,
    }

    data class AutomaticCaptureRouteCandidate(
        val source: Int,
        val sourceName: String,
        val channelMask: Int,
        val channelIndexMask: Int?,
        val mode: AutomaticCaptureRouteMode,
        val qualification: CaptureRouteQualification?,
        val structurallyEligible: Boolean,
        val performanceEvidenceRequired: Boolean,
        val label: String,
        val reason: String,
        /* Identité de la VUE dans la route ; LEFT/RIGHT ne sont jamais physiques. */
        val outputSelection: Int? = null,
    ) {
        val multiChannelEnabled: Boolean
            get() =
                mode ==
                    AutomaticCaptureRouteMode.SPATIAL_TWO_CHANNEL
    }

    data class AutomaticCaptureRoutePlan(
        val selected: AutomaticCaptureRouteCandidate,
        val challengers: List<AutomaticCaptureRouteCandidate>,
        val confidence: AutomaticCaptureRouteConfidence,
        val candidates: List<AutomaticCaptureRouteCandidate>,
        val reason: String,
    ) {
        val challenger: AutomaticCaptureRouteCandidate?
            get() =
                challengers.firstOrNull()
    }

    data class CaptureProbeResult(
    val source: Int,
    val sourceName: String,

    val channelMask: Int,
    val channelModeName: String,
    /*
 * null pour les modes positionnels MONO / STEREO.
 *
 * Pour les probes INDEX_N :
 * INDEX_1 -> 0x1
 * INDEX_2 -> 0x3
 * INDEX_3 -> 0x7
 * INDEX_4 -> 0xF
 */
val channelIndexMask: Int? =
    null,

    val initialized: Boolean,
    val recordingStarted: Boolean,

    val channelCount: Int?,

    val activeMicrophoneCount: Int?,
    val activeMicrophoneDescriptions: List<String>,
    val channelMappings: List<String>,

    /*
     * Version structurée des mappings Android.
     *
     * channelMappings est conservé pour les logs et la compatibilité.
     * Toute décision Guardian doit utiliser channelMappingDetails.
     */
    val channelMappingDetails: List<ChannelMappingDetail> =
        emptyList(),

    /*
     * Qualification structurelle complète de la route.
     *
     * Null uniquement si le probe n'a pas pu aller assez loin pour
     * caractériser le flux.
     */
    val routeQualification: CaptureRouteQualification? =
        null,

    val directionRequested: Boolean,
    val directionApplied: Boolean,

    val fieldRequested: Boolean,
    val fieldApplied: Boolean,

/*
 * Analyse historique du vrai mode STEREO.
 * On la conserve pour ne rien casser.
 */
val stereoAnalysis: StereoAnalysis?,

/*
 * Analyse générique du PCM réellement retourné
 * par AudioRecord, de 1 à N canaux.
 *
 * Valeur par défaut afin que tous les anciens
 * CaptureProbeResult restent compatibles.
 */
val multiChannelAnalysis: MultiChannelAnalysis? =
    null,

val error: String?,
)

    private data class BenchmarkResult(
        val averageMs: Double,
        val p95Ms: Double,
        val headroomRatio: Double,
    )
    
    private data class CaptureProbeConfiguration(
    val source: Int,

    /*
     * Masque positionnel Android historique.
     *
     * Utilisé pour MONO / STEREO.
     * CHANNEL_INVALID lorsque le probe utilise
     * un channel index mask.
     */
    val channelMask: Int =
        AudioFormat.CHANNEL_INVALID,

    /*
     * Android M+ :
     * sélection des canaux par leur index réel.
     *
     * Exemples :
     * 1 canal  -> 0x1
     * 2 canaux -> 0x3
     * 3 canaux -> 0x7
     * 4 canaux -> 0xF
     *
     * null = utiliser channelMask.
     */
    val channelIndexMask: Int? =
        null,

    val channelModeName: String,
)

    data class StereoAnalysis(
    val samplesPerChannel: Int,
    val leftRms: Double,
    val rightRms: Double,
    val leftPeak: Double,
    val rightPeak: Double,
    val correlation: Double,
    val differenceRms: Double,
)

    data class ChannelPairAnalysis(
    val firstChannel: Int,
    val secondChannel: Int,

    /*
     * Corrélation normalisée entre -1 et +1.
     *
     * Une corrélation très proche de +1 peut indiquer
     * deux canaux quasi identiques, mais elle ne suffit
     * pas à elle seule pour décider qu'ils sont dupliqués.
     */
    val correlation: Double,

    /*
     * Énergie RMS de la différence entre les deux canaux.
     *
     * Cela nous permettra de distinguer un vrai couple
     * spatial de deux copies presque identiques.
     */
    val differenceRms: Double,
)

    data class MultiChannelAnalysis(
        /*
         * Nombre de canaux effectivement ouvert par AudioRecord.
         */
        val channelCount: Int,

        /*
         * Nombre de frames PCM réellement analysées.
         */
        val samplesPerChannel: Int,

        /*
         * Niveau RMS individuel de chaque canal.
         */
        val rmsPerChannel: List<Double>,

        /*
         * Pic absolu individuel de chaque canal.
         */
        val peakPerChannel: List<Double>,

        /*
         * Comparaison de toutes les paires.
         */
        val pairAnalyses: List<ChannelPairAnalysis>,

        /*
         * Canaux qui transportent réellement un signal PCM.
         *
         * Le seuil est volontairement très bas : son rôle principal est
         * d'écarter les canaux structurellement silencieux retournés par
         * certaines HAL Android, pas d'évaluer la qualité de la parole.
         */
        val usableChannelIndices: List<Int>,

        /*
         * Sous-ensemble des canaux utilisables après suppression des
         * duplications évidentes.
         */
        val independentChannelIndices: List<Int>,
    ) {
        val usableChannelCount: Int
            get() = usableChannelIndices.size

        val independentChannelCount: Int
            get() = independentChannelIndices.size
    }

    /**
     * Analyse le téléphone actuel.
     *
     * À appeler une seule fois lors de l'initialisation
     * du moteur Voice.
     */
    fun detect(
        context: Context,
    ): Capabilities {

        val applicationContext =
            context.applicationContext

        val audioManager =
            applicationContext.getSystemService(
                Context.AUDIO_SERVICE
            ) as AudioManager

            // =====================================================
// Inventaire matériel des microphones
// =====================================================

val microphones =
    detectMicrophones(
        audioManager
    )

val microphoneCount =
    microphones.size

val builtInMicrophoneCount =
    microphones.count {
        it.builtIn
    }

val multiMicHardwareCandidate =
    builtInMicrophoneCount > 1

val microphoneDirectionApiAvailable =
    Build.VERSION.SDK_INT >=
        Build.VERSION_CODES.Q

val microphoneFieldDimensionApiAvailable =
    Build.VERSION.SDK_INT >=
        Build.VERSION_CODES.Q

        // =====================================================
        // Effets Android disponibles
        // =====================================================

        val noiseSuppressorAvailable =
            runCatching {
                NoiseSuppressor.isAvailable()
            }.getOrDefault(false)

        val automaticGainControlAvailable =
            runCatching {
                AutomaticGainControl.isAvailable()
            }.getOrDefault(false)

        // =====================================================
        // Source UNPROCESSED
        // =====================================================

        val unprocessedSupported =
            if (
                Build.VERSION.SDK_INT >=
                    Build.VERSION_CODES.N
            ) {
                audioManager
                    .getProperty(
                        AudioManager
                            .PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED
                    )
                    ?.equals(
                        "true",
                        ignoreCase = true
                    )
                    ?: false
            } else {
                false
            }

        /*
         * VOICE_RECOGNITION existe depuis une très ancienne
         * version Android et Guardian cible des versions
         * largement plus récentes.
         */
        val voiceRecognitionSupported =
            Build.VERSION.SDK_INT >=
                Build.VERSION_CODES.ECLAIR_MR1

        // =====================================================
        // Source recommandée
        // =====================================================

        /*
         * Guardian C5 - Baseline de capture.
         *
         * IMPORTANT :
         * UNPROCESSED est conservé comme candidat de benchmark,
         * mais n'est plus sélectionné automatiquement simplement
         * parce que le téléphone le déclare disponible.
         *
         * Pour la chaîne principale Guardian Voice, la baseline
         * reste VOICE_RECOGNITION, qui correspond directement
         * au cas d'usage ASR.
         *
         * UNPROCESSED pourra être comparé plus tard sur le même
         * corpus (voix proche, voix douce 5 m, bruit, GTCRN, etc.)
         * avant toute promotion comme source principale.
         */
        val recommendedAudioSource =
            when {
                voiceRecognitionSupported ->
                    MediaRecorder.AudioSource.VOICE_RECOGNITION

                unprocessedSupported ->
                    MediaRecorder.AudioSource.UNPROCESSED

                else ->
                    MediaRecorder.AudioSource.MIC
            }

        val recommendedAudioSourceName =
            when (
                recommendedAudioSource
            ) {
                MediaRecorder.AudioSource.UNPROCESSED ->
                    "UNPROCESSED"

                MediaRecorder.AudioSource.VOICE_RECOGNITION ->
                    "VOICE_RECOGNITION"

                MediaRecorder.AudioSource.MIC ->
                    "MIC"

                else ->
                    "UNKNOWN"
            }

        // =====================================================
        // ABI
        // =====================================================

        val supportedAbis =
            Build.SUPPORTED_ABIS
                ?.toList()
                ?: emptyList()

        // =====================================================
        // Benchmark Guardian
        // =====================================================

        val benchmark =
            runAudioBenchmark()

        /*
         * On classe le téléphone à partir de sa capacité
         * à traiter des blocs audio en temps réel.
         *
         * Un headroom de 5x signifie par exemple :
         *
         * bloc disponible : 50 ms
         * coût calcul P95 : 10 ms
         *
         * 50 / 10 = 5
         */
        val performanceProfile =
            when {
                benchmark.headroomRatio >=
                    HIGH_PROFILE_MIN_HEADROOM ->

                    PerformanceProfile.HIGH

                benchmark.headroomRatio >=
                    BALANCED_PROFILE_MIN_HEADROOM ->

                    PerformanceProfile.BALANCED

                else ->
                    PerformanceProfile.FALLBACK
            }

        /*
         * Pour l'instant, ces règles sont prudentes.
         *
         * Le véritable modèle Neural Enhancement sera
         * benchmarké plus tard séparément.
         */
        val neuralEnhancementAllowed =
            performanceProfile !=
                PerformanceProfile.FALLBACK

        val speakerVerificationAllowed =
            performanceProfile !=
                PerformanceProfile.FALLBACK

        val capabilities =
            Capabilities(
                sdkInt =
                    Build.VERSION.SDK_INT,

                supportedAbis =
                    supportedAbis,

                microphones =
                    microphones,

                microphoneCount =
                    microphoneCount,

                builtInMicrophoneCount =
                    builtInMicrophoneCount,

                multiMicHardwareCandidate =
                    multiMicHardwareCandidate,

                microphoneDirectionApiAvailable =
                    microphoneDirectionApiAvailable,

                microphoneFieldDimensionApiAvailable =
                    microphoneFieldDimensionApiAvailable,

                noiseSuppressorAvailable =
                    noiseSuppressorAvailable,

                automaticGainControlAvailable =
                    automaticGainControlAvailable,

                unprocessedSupported =
                    unprocessedSupported,

                voiceRecognitionSupported =
                    voiceRecognitionSupported,

                recommendedAudioSource =
                    recommendedAudioSource,

                recommendedAudioSourceName =
                    recommendedAudioSourceName,

                benchmarkAverageMs =
                    benchmark.averageMs,

                benchmarkP95Ms =
                    benchmark.p95Ms,

                realtimeHeadroomRatio =
                    benchmark.headroomRatio,

                performanceProfile =
                    performanceProfile,

                neuralEnhancementAllowed =
                    neuralEnhancementAllowed,

                speakerVerificationAllowed =
                    speakerVerificationAllowed,
            )

        Log.i(
            TAG,
            "Guardian Audio Capabilities : " +
                "sdk=${capabilities.sdkInt}, " +
                "abis=${capabilities.supportedAbis}, " +
                "microphones=${capabilities.microphoneCount}, " +
                "builtInMicrophones=${capabilities.builtInMicrophoneCount}, " +
                "multiMicCandidate=${capabilities.multiMicHardwareCandidate}, " +
                "directionApi=${capabilities.microphoneDirectionApiAvailable}, " +
                "fieldApi=${capabilities.microphoneFieldDimensionApiAvailable}, " +
                "NS=${capabilities.noiseSuppressorAvailable}, " +
                "AGC=${capabilities.automaticGainControlAvailable}, " +
                "unprocessed=${capabilities.unprocessedSupported}, " +
                "source=${capabilities.recommendedAudioSourceName}, " +
                "benchmarkAvg=" +
                "%.3f".format(capabilities.benchmarkAverageMs) +
                "ms, " +
                "benchmarkP95=" +
                "%.3f".format(capabilities.benchmarkP95Ms) +
                "ms, " +
                "headroom=" +
                "%.2f".format(capabilities.realtimeHeadroomRatio) +
                "x, " +
                "profile=${capabilities.performanceProfile}, " +
                "enhancement=${capabilities.neuralEnhancementAllowed}"
        )

            capabilities.microphones.forEach { microphone ->
    Log.i(
        TAG,
        "Microphone matériel : " +
            "id=${microphone.id}, " +
            "description=${microphone.description}, " +
            "type=${microphone.deviceTypeName}, " +
            "location=${microphone.locationName}, " +
            "group=${microphone.group}, " +
            "index=${microphone.indexInGroup}, " +
            "directionality=${microphone.directionalityName}, " +
            "sensitivity=${microphone.sensitivity}, " +
            "position=(" +
            "${microphone.positionX}," +
            "${microphone.positionY}," +
            "${microphone.positionZ}), " +
            "orientation=(" +
            "${microphone.orientationX}," +
            "${microphone.orientationY}," +
            "${microphone.orientationZ}), " +
            "frequencyPoints=${microphone.frequencyResponsePointCount}, " +
            "builtIn=${microphone.builtIn}"
    )
}

        return capabilities
    }

    /**
     * Petit benchmark DSP synthétique.
     *
     * Il ne benchmarke PAS encore GTCRN ou DPDFNet.
     *
     * Il mesure simplement si ce téléphone dispose
     * d'une bonne marge pour effectuer du traitement
     * audio continu sur des blocs de 50 ms.
     */

     fun probeCaptureConfigurations(
    context: Context,
): List<CaptureProbeResult> {

    val applicationContext =
        context.applicationContext

    if (
        applicationContext.checkSelfPermission(
            Manifest.permission.RECORD_AUDIO
        ) != PackageManager.PERMISSION_GRANTED
    ) {
        Log.w(
            TAG,
            "Audio Probe impossible : permission RECORD_AUDIO absente."
        )

        return emptyList()
    }

    val audioManager =
        applicationContext.getSystemService(
            Context.AUDIO_SERVICE
        ) as AudioManager

    val unprocessedSupported =
        if (
            Build.VERSION.SDK_INT >=
                Build.VERSION_CODES.N
        ) {
            audioManager
                .getProperty(
                    AudioManager
                        .PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED
                )
                ?.equals(
                    "true",
                    ignoreCase = true
                )
                ?: false
        } else {
            false
        }

    /*
     * Guardian C5 - Probe de sources.
     *
     * Le Probe caractérise les sources sans forcer de
     * direction microphone ni de zoom.
     *
     * Il doit observer une configuration comparable à
     * la baseline runtime C4.
     *
     * UNPROCESSED est ajouté comme candidat lorsque
     * Android déclare réellement son support, mais le
     * Probe ne le promeut pas automatiquement.
     */
    val configurations =
        buildList {
            add(
                CaptureProbeConfiguration(
                    source =
                        MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    channelMask =
                        AudioFormat.CHANNEL_IN_MONO,
                    channelModeName =
                        "MONO",
                )
            )

            add(
                CaptureProbeConfiguration(
                    source =
                        MediaRecorder.AudioSource.MIC,
                    channelMask =
                        AudioFormat.CHANNEL_IN_MONO,
                    channelModeName =
                        "MONO",
                )
            )

            add(
                CaptureProbeConfiguration(
                    source =
                        MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                    channelMask =
                        AudioFormat.CHANNEL_IN_MONO,
                    channelModeName =
                        "MONO",
                )
            )

            if (unprocessedSupported) {
                add(
                    CaptureProbeConfiguration(
                        source =
                            MediaRecorder.AudioSource.UNPROCESSED,
                        channelMask =
                            AudioFormat.CHANNEL_IN_MONO,
                        channelModeName =
                            "MONO",
                    )
                )
            }

            add(
                CaptureProbeConfiguration(
                    source =
                        MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    channelMask =
                        AudioFormat.CHANNEL_IN_STEREO,
                    channelModeName =
                        "STEREO",
                )
            )

            add(
                CaptureProbeConfiguration(
                    source =
                        MediaRecorder.AudioSource.MIC,
                    channelMask =
                        AudioFormat.CHANNEL_IN_STEREO,
                    channelModeName =
                        "STEREO",
                )
            )


                      /*
 * Guardian Spatial Capability Probe.
 *
 * Contrairement aux masques positionnels MONO/STEREO,
 * ces configurations demandent explicitement N canaux
 * par leur index Android.
 *
 * Le téléphone reste libre de refuser la configuration :
 * c'est précisément ce que le Probe doit mesurer.
 */
if (
    Build.VERSION.SDK_INT >=
    Build.VERSION_CODES.M
) {
    for (channelCount in 1..4) {

        val channelIndexMask =
            (1 shl channelCount) - 1

        add(
            CaptureProbeConfiguration(
                source =
                    MediaRecorder.AudioSource.MIC,

                channelIndexMask =
                    channelIndexMask,

                channelModeName =
                    "INDEX_$channelCount",
            )
        )
    }
}
}

  

    return configurations.map { configuration ->
        probeSingleCaptureConfiguration(
            applicationContext =
                applicationContext,
            configuration =
                configuration,
        )
    }
}

    fun resolveAutomaticCaptureRoutePlan(
        capabilities: Capabilities,
        probeResults: List<CaptureProbeResult>,
    ): AutomaticCaptureRoutePlan {

        fun findPositionalRoute(
            source: Int,
            channelMask: Int,
        ): CaptureProbeResult? =
            probeResults.firstOrNull { result ->
                result.source ==
                    source &&
                    result.channelIndexMask ==
                    null &&
                    result.channelMask ==
                    channelMask
            }

        fun buildCandidate(
            source: Int,
            sourceName: String,
            channelMask: Int,
            mode: AutomaticCaptureRouteMode,
            label: String,
        ): AutomaticCaptureRouteCandidate {
            val route =
                findPositionalRoute(
                    source = source,
                    channelMask = channelMask,
                )

            val qualification =
                route?.routeQualification

            val structurallyEligible =
                when (mode) {
                    AutomaticCaptureRouteMode.MONO ->
                        qualification != null &&
                            qualification.usableChannelCount >= 1 &&
                            qualification.independentChannelCount >= 1 &&
                            qualification.classification !=
                            CaptureRouteClassification.UNAVAILABLE &&
                            qualification.classification !=
                            CaptureRouteClassification.UNUSABLE

                    AutomaticCaptureRouteMode.SPATIAL_TWO_CHANNEL ->
                        qualification?.spatialEligible ==
                            true &&
                            qualification.independentChannelCount >= 2
                }

            /*
             * VOICE_RECOGNITION + MONO est la baseline historique.
             * Toute autre route doit prouver sa performance vocale avant
             * de pouvoir la détrôner, y compris MIC + MONO.
             */
            val performanceEvidenceRequired =
                label !=
                    "VOICE_RECOGNITION_MONO"

            val reason =
                when {
                    route == null ->
                        "Route non sondée."

                    qualification == null ->
                        "Route ouverte mais non qualifiée."

                    !structurallyEligible ->
                        qualification.reason

                    performanceEvidenceRequired ->
                        qualification.reason +
                            if (
                                mode ==
                                    AutomaticCaptureRouteMode.SPATIAL_TWO_CHANNEL
                            ) {
                                " Route stéréo qualifiée : Voice Focus comparera " +
                                    "les vues simultanées de cette capture. " +
                                    "Une preuve de performance vocale reste requise " +
                                    "avant promotion AUTO."
                            } else {
                                " Candidate structurellement saine; " +
                                    "une preuve de performance vocale est requise " +
                                    "avant promotion AUTO."
                            }

                    else ->
                        qualification.reason
                }

            return AutomaticCaptureRouteCandidate(
                source = source,
                sourceName = sourceName,
                channelMask = channelMask,
                channelIndexMask = null,
                mode = mode,
                qualification = qualification,
                structurallyEligible = structurallyEligible,
                performanceEvidenceRequired =
                    performanceEvidenceRequired,
                label = label,
                reason = reason,
            )
        }

        val voiceRecognitionMono =
            buildCandidate(
                source =
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                sourceName =
                    "VOICE_RECOGNITION",
                channelMask =
                    AudioFormat.CHANNEL_IN_MONO,
                mode =
                    AutomaticCaptureRouteMode.MONO,
                label =
                    "VOICE_RECOGNITION_MONO",
            )

        val voiceRecognitionStereo =
            buildCandidate(
                source =
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                sourceName =
                    "VOICE_RECOGNITION",
                channelMask =
                    AudioFormat.CHANNEL_IN_STEREO,
                mode =
                    AutomaticCaptureRouteMode.SPATIAL_TWO_CHANNEL,
                label =
                    "VOICE_RECOGNITION_STEREO_ADAPTIVE",
            )
                .copy(
                    /*
                     * Une route stéréo AUTO n'est jamais figée sur un canal.
                     * GuardianVoiceFocusEngine compare les vues dérivables de
                     * la même capture : CHANNEL_0, CHANNEL_1, WEIGHTED_FUSION
                     * et ALIGNED_FUSION. Le meilleur flux mono est publié à
                     * Sherpa sans ouvrir un second AudioRecord.
                     */
                    outputSelection =
                        GuardianAudioEngine.STEREO_CHANNEL_VOICE_FOCUS,
                )

        val micMono =
            buildCandidate(
                source =
                    MediaRecorder.AudioSource.MIC,
                sourceName =
                    "MIC",
                channelMask =
                    AudioFormat.CHANNEL_IN_MONO,
                mode =
                    AutomaticCaptureRouteMode.MONO,
                label =
                    "MIC_MONO",
            )

        val micStereo =
            buildCandidate(
                source =
                    MediaRecorder.AudioSource.MIC,
                sourceName =
                    "MIC",
                channelMask =
                    AudioFormat.CHANNEL_IN_STEREO,
                mode =
                    AutomaticCaptureRouteMode.SPATIAL_TWO_CHANNEL,
                label =
                    "MIC_STEREO",
            )

        /*
         * MIC_STEREO est UNE famille de capture Android.
         *
         * CHANNEL_0 / CHANNEL_1 / WEIGHTED_FUSION / ALIGNED_FUSION sont des
         * VUES de cette même capture, pas quatre routes matérielles séparées.
         * En AUTO, Guardian active donc Voice Focus sur la famille stéréo et
         * laisse le sélecteur choisir la meilleure vue pendant chaque phrase.
         *
         * Les overrides manuels LEFT/RIGHT/MIX/ALIGNED restent disponibles
         * dans GuardianProtectionService pour les diagnostics terrain.
         */
        val micStereoAdaptive =
            micStereo.copy(
                label =
                    "MIC_STEREO_ADAPTIVE",
                outputSelection =
                    GuardianAudioEngine.STEREO_CHANNEL_VOICE_FOCUS,
            )

        /*
         * Le plan AUTO ne contient que des FAMILLES DE CAPTURE comparables.
         * Les vues internes de MIC_STEREO sont mesurées par Voice Focus sur
         * exactement le même PCM et ne doivent pas polluer l'apprentissage
         * long terme comme si elles étaient des AudioRecord indépendants.
         */
        val candidates =
            listOf(
                voiceRecognitionMono,
                voiceRecognitionStereo,
                micMono,
                micStereoAdaptive,
            )

        /*
         * AUTO V1 - règle de sécurité.
         *
         * Les probes de démarrage caractérisent parfaitement la structure
         * d'une route, mais ils ne contiennent pas encore une preuve de
         * reconnaissance vocale comparable entre routes.
         *
         * Tant que cette preuve n'existe pas, Guardian conserve la baseline
         * ASR VOICE_RECOGNITION + MONO lorsqu'elle est saine. Une route
         * multi-canal saine est conservée comme challenger et pourra être
         * promue par la couche d'évidence vocale sans modifier ce contrat.
         */
        if (
            voiceRecognitionMono.structurallyEligible
        ) {
            val challengers =
                listOf(
                    micStereoAdaptive,
                    micMono,
                    voiceRecognitionStereo,
                )
                    .filter { candidate ->
                        candidate.structurallyEligible
                    }

            return AutomaticCaptureRoutePlan(
                selected =
                    voiceRecognitionMono,
                challengers =
                    challengers,
                confidence =
                    AutomaticCaptureRouteConfidence.HIGH,
                candidates =
                    candidates,
                reason =
                    "Baseline structurelle VOICE_RECOGNITION_MONO (performance comparative non démontrée). " +
                        if (challengers.isNotEmpty()) {
                            "Routes alternatives conservées pour validation vocale : " +
                                challengers.joinToString { it.label } +
                                "."
                        } else {
                            "Aucune route alternative saine détectée."
                        },
            )
        }

        /*
         * Si la baseline ASR n'est pas exploitable, une vraie route DIRECT
         * multi-canal possède la meilleure garantie structurelle disponible.
         */
        val directSpatialFallback =
            listOf(
                voiceRecognitionStereo,
                micStereoAdaptive,
            )
                .firstOrNull { candidate ->
                    candidate.structurallyEligible &&
                        candidate.qualification
                            ?.classification ==
                            CaptureRouteClassification.SPATIAL_DIRECT
                }

        if (directSpatialFallback != null) {
            val challengers =
                candidates.filter { candidate ->
                    candidate.structurallyEligible &&
                        candidate.label !=
                        directSpatialFallback.label
                }

            return AutomaticCaptureRoutePlan(
                selected =
                    directSpatialFallback,
                challengers =
                    challengers,
                confidence =
                    AutomaticCaptureRouteConfidence.MEDIUM,
                candidates =
                    candidates,
                reason =
                    "Baseline VOICE_RECOGNITION + MONO indisponible; " +
                        "fallback spatial DIRECT structurellement fiable.",
            )
        }

        if (micMono.structurallyEligible) {
            val challengers =
                candidates.filter { candidate ->
                    candidate.structurallyEligible &&
                        candidate.label !=
                        micMono.label
                }

            return AutomaticCaptureRoutePlan(
                selected =
                    micMono,
                challengers =
                    challengers,
                confidence =
                    AutomaticCaptureRouteConfidence.MEDIUM,
                candidates =
                    candidates,
                reason =
                    "Fallback MIC + MONO : baseline VOICE_RECOGNITION mono non exploitable.",
            )
        }

        val processedSpatialFallback =
            listOf(
                voiceRecognitionStereo,
                micStereoAdaptive,
            )
                .firstOrNull { candidate ->
                    candidate.structurallyEligible
                }

        if (processedSpatialFallback != null) {
            val challengers =
                candidates.filter { candidate ->
                    candidate.structurallyEligible &&
                        candidate.label !=
                        processedSpatialFallback.label
                }

            return AutomaticCaptureRoutePlan(
                selected =
                    processedSpatialFallback,
                challengers =
                    challengers,
                confidence =
                    AutomaticCaptureRouteConfidence.FALLBACK,
                candidates =
                    candidates,
                reason =
                    "Aucune route mono saine; utilisation du meilleur fallback multi-canal disponible. " +
                        "Les contraintes spatiales détermineront si le steering est autorisé.",
            )
        }

        val fallbackSource =
            capabilities.recommendedAudioSource

        val fallbackSourceName =
            capabilities.recommendedAudioSourceName

        val fallbackCandidate =
            AutomaticCaptureRouteCandidate(
                source =
                    fallbackSource,
                sourceName =
                    fallbackSourceName,
                channelMask =
                    AudioFormat.CHANNEL_IN_MONO,
                channelIndexMask =
                    null,
                mode =
                    AutomaticCaptureRouteMode.MONO,
                qualification =
                    null,
                structurallyEligible =
                    false,
                performanceEvidenceRequired =
                    false,
                label =
                    "${fallbackSourceName}_MONO_UNVERIFIED",
                reason =
                    "Aucune route qualifiée exploitable; fallback source recommandée non vérifiée.",
            )

        return AutomaticCaptureRoutePlan(
            selected =
                fallbackCandidate,
            challengers =
                emptyList(),
            confidence =
                AutomaticCaptureRouteConfidence.FALLBACK,
            candidates =
                candidates + fallbackCandidate,
            reason =
                fallbackCandidate.reason,
        )
    }

    fun resolveAdaptiveCaptureProfile(
        probeResults: List<CaptureProbeResult>,
    ): AdaptiveCaptureProfile {

        /*
         * Une route est considérée comme ouverte uniquement si AudioRecord
         * a été initialisé, a réellement démarré et n'a remonté aucune erreur.
         */
        val openedResults =
            probeResults.filter { result ->
                result.initialized &&
                    result.recordingStarted &&
                    result.error == null &&
                    (result.channelCount ?: 0) > 0
            }

        val monoVerified =
            openedResults.any { result ->
                result.channelIndexMask == null &&
                    result.channelMask ==
                    AudioFormat.CHANNEL_IN_MONO &&
                    (result.channelCount ?: 0) >= 1
            }

        /*
         * Mesure 1 : combien de canaux Android sait réellement ouvrir.
         *
         * Cette valeur est purement structurelle. Elle ne dit rien de leur
         * contenu acoustique.
         */
        val maximumOpenedChannelCount =
            openedResults
                .mapNotNull { result ->
                    result.channelCount
                }
                .maxOrNull()
                ?: 0

        /*
         * Mesure 2/3 : parmi les canaux ouverts, combien transportent un
         * signal et combien représentent une information acoustique réellement
         * indépendante.
         */
        val analyzedResults =
            openedResults.filter { result ->
                result.multiChannelAnalysis != null
            }

        val maximumUsableChannelCount =
            analyzedResults
                .map { result ->
                    result.multiChannelAnalysis
                        ?.usableChannelCount
                        ?: 0
                }
                .maxOrNull()
                ?: 0

        val maximumIndependentChannelCount =
            analyzedResults
                .map { result ->
                    result.multiChannelAnalysis
                        ?.independentChannelCount
                        ?: 0
                }
                .maxOrNull()
                ?: 0

        /*
         * Route exacte actuellement utilisée par GuardianSpatialAudio :
         * MIC + CHANNEL_IN_STEREO.
         *
         * Deux canaux ouverts ne suffisent plus. On exige maintenant :
         * - deux canaux PCM réellement utilisables ;
         * - deux informations acoustiques indépendantes ;
         * - au moins deux microphones actifs déclarés par Android.
         */
        val micStereoResult =
            openedResults.firstOrNull { result ->
                result.source ==
                    MediaRecorder.AudioSource.MIC &&
                    result.channelIndexMask == null &&
                    result.channelMask ==
                    AudioFormat.CHANNEL_IN_STEREO &&
                    (result.channelCount ?: 0) >= 2 &&
                    result.stereoAnalysis != null &&
                    result.multiChannelAnalysis != null
            }

        val dualMicSpatialAvailable =
            micStereoResult
                ?.routeQualification
                ?.spatialEligible
                ?: false

        /*
         * La topologie représente maintenant ce que Guardian peut réellement
         * exploiter acoustiquement, pas simplement ce qu'Android accepte
         * d'ouvrir.
         */
        val topology =
            when {
                maximumIndependentChannelCount >= 3 ->
                    CaptureTopology.MULTI_MIC_N

                maximumIndependentChannelCount >= 2 ->
                    CaptureTopology.DUAL_MIC

                else ->
                    CaptureTopology.MONO_ONLY
            }

        /*
         * GuardianSpatialAudio traite au maximum deux canaux actuellement.
         */
        val runtimeChannelCount =
            if (dualMicSpatialAvailable) {
                2
            } else {
                1
            }

        /*
         * Compatibilité avec les consommateurs existants du champ historique.
         */
        val maximumVerifiedChannelCount =
            maximumOpenedChannelCount

        val reason =
            when (topology) {
                CaptureTopology.MULTI_MIC_N ->
                    "Android ouvre jusqu'à " +
                        "$maximumOpenedChannelCount canaux; " +
                        "$maximumUsableChannelCount transportent un signal; " +
                        "$maximumIndependentChannelCount sont acoustiquement indépendants; " +
                        "GuardianSpatialAudio utilise actuellement " +
                        "$runtimeChannelCount canal(aux)."

                CaptureTopology.DUAL_MIC ->
                    "Android ouvre jusqu'à " +
                        "$maximumOpenedChannelCount canaux; " +
                        "$maximumUsableChannelCount transportent un signal; " +
                        "$maximumIndependentChannelCount sont acoustiquement indépendants; " +
                        "MIC + STEREO spatial=${dualMicSpatialAvailable}."

                CaptureTopology.MONO_ONLY ->
                    if (monoVerified) {
                        "Capture mono vérifiée; Android ouvre jusqu'à " +
                            "$maximumOpenedChannelCount canal(aux), mais seulement " +
                            "$maximumIndependentChannelCount information(s) acoustique(s) " +
                            "indépendante(s) ont été validées."
                    } else {
                        "Aucune capture audio exploitable vérifiée; fallback mono de sécurité."
                    }
            }

        val profile =
            AdaptiveCaptureProfile(
                topology =
                    topology,

                maximumVerifiedChannelCount =
                    maximumVerifiedChannelCount,

                maximumOpenedChannelCount =
                    maximumOpenedChannelCount,

                maximumUsableChannelCount =
                    maximumUsableChannelCount,

                maximumIndependentChannelCount =
                    maximumIndependentChannelCount,

                dualMicSpatialAvailable =
                    dualMicSpatialAvailable,

                runtimeChannelCount =
                    runtimeChannelCount,

                reason =
                    reason,
            )

        Log.i(
            TAG,
            "Adaptive Capture Profile : " +
                "topology=${profile.topology}, " +
                "opened=${profile.maximumOpenedChannelCount}, " +
                "usable=${profile.maximumUsableChannelCount}, " +
                "independent=${profile.maximumIndependentChannelCount}, " +
                "dualMicSpatial=${profile.dualMicSpatialAvailable}, " +
                "runtimeChannels=${profile.runtimeChannelCount}, " +
                "reason=${profile.reason}"
        )

        return profile
    }

    fun resolveSpatialCaptureConstraints(
        capabilities: Capabilities,
        probeResults: List<CaptureProbeResult>,
        source: Int,
        channelMask: Int,
        channelIndexMask: Int? = null,
    ): SpatialCaptureConstraints {

        val route =
            probeResults.firstOrNull { result ->
                result.source ==
                    source &&
                    result.channelIndexMask ==
                    channelIndexMask &&
                    (
                        channelIndexMask != null ||
                            result.channelMask ==
                            channelMask
                    )
            }

        val qualification =
            route?.routeQualification

        if (
            route == null ||
            qualification == null ||
            !qualification.spatialEligible
        ) {
            return SpatialCaptureConstraints(
                routeClassification =
                    qualification?.classification
                        ?: CaptureRouteClassification.UNAVAILABLE,
                delayTrust =
                    SpatialDelayTrust.NONE,
                steeringAllowed =
                    false,
                physicalGeometryReliable =
                    false,
                physicalMicrophoneDistanceMeters =
                    null,
                maximumPhysicalTdoaSeconds =
                    null,
                reason =
                    qualification?.reason
                        ?: "Route spatiale indisponible ou non qualifiée.",
            )
        }

        /*
         * Une route PROCESSED peut transporter plusieurs informations
         * acoustiques utiles, mais Android autorise le constructeur à avoir
         * déjà mélangé / filtré / retardé les contributions avant Guardian.
         *
         * Guardian conserve donc GCC-PHAT comme diagnostic inter-canaux,
         * mais n'utilise pas ce lag pour appliquer un retard fractionnaire.
         */
        if (
            qualification.classification !=
            CaptureRouteClassification.SPATIAL_DIRECT ||
            !qualification.physicalMappingReliable
        ) {
            return SpatialCaptureConstraints(
                routeClassification =
                    qualification.classification,
                delayTrust =
                    SpatialDelayTrust.INTER_CHANNEL_ONLY,
                steeringAllowed =
                    false,
                physicalGeometryReliable =
                    false,
                physicalMicrophoneDistanceMeters =
                    null,
                maximumPhysicalTdoaSeconds =
                    null,
                reason =
                    qualification.reason +
                        " Lag utilisable uniquement comme information inter-canaux; " +
                        "steering physique désactivé.",
            )
        }

        val analysis =
            route.multiChannelAnalysis

        if (analysis == null) {
            return SpatialCaptureConstraints(
                routeClassification =
                    qualification.classification,
                delayTrust =
                    SpatialDelayTrust.INTER_CHANNEL_ONLY,
                steeringAllowed =
                    false,
                physicalGeometryReliable =
                    false,
                physicalMicrophoneDistanceMeters =
                    null,
                maximumPhysicalTdoaSeconds =
                    null,
                reason =
                    "Mapping DIRECT détecté mais analyse multicanale absente; " +
                        "steering désactivé.",
            )
        }

        val independentChannels =
            analysis.independentChannelIndices

        if (independentChannels.size < 2) {
            return SpatialCaptureConstraints(
                routeClassification =
                    qualification.classification,
                delayTrust =
                    SpatialDelayTrust.NONE,
                steeringAllowed =
                    false,
                physicalGeometryReliable =
                    false,
                physicalMicrophoneDistanceMeters =
                    null,
                maximumPhysicalTdoaSeconds =
                    null,
                reason =
                    "Moins de deux canaux acoustiquement indépendants; " +
                        "steering désactivé.",
            )
        }

        val directMappings =
            independentChannels
                .take(2)
                .mapNotNull { channel ->
                    route.channelMappingDetails
                        .singleOrNull { detail ->
                            detail.channelIndex ==
                                channel &&
                                detail.mappingType ==
                                ChannelMappingType.DIRECT
                        }
                }

        if (directMappings.size != 2) {
            return SpatialCaptureConstraints(
                routeClassification =
                    qualification.classification,
                delayTrust =
                    SpatialDelayTrust.INTER_CHANNEL_ONLY,
                steeringAllowed =
                    false,
                physicalGeometryReliable =
                    false,
                physicalMicrophoneDistanceMeters =
                    null,
                maximumPhysicalTdoaSeconds =
                    null,
                reason =
                    "Mapping DIRECT incomplet pour les deux canaux indépendants; " +
                        "steering désactivé.",
            )
        }

        fun findMicrophone(
            mapping: ChannelMappingDetail,
        ): MicrophoneCapability? =
            capabilities.microphones.firstOrNull { microphone ->
                microphone.id ==
                    mapping.microphoneId &&
                    microphone.description ==
                    mapping.microphoneDescription &&
                    microphone.group ==
                    mapping.microphoneGroup &&
                    microphone.indexInGroup ==
                    mapping.microphoneIndexInGroup
            }

        val firstMicrophone =
            findMicrophone(
                directMappings[0],
            )

        val secondMicrophone =
            findMicrophone(
                directMappings[1],
            )

        if (
            firstMicrophone == null ||
            secondMicrophone == null
        ) {
            return SpatialCaptureConstraints(
                routeClassification =
                    qualification.classification,
                delayTrust =
                    SpatialDelayTrust.INTER_CHANNEL_ONLY,
                steeringAllowed =
                    false,
                physicalGeometryReliable =
                    false,
                physicalMicrophoneDistanceMeters =
                    null,
                maximumPhysicalTdoaSeconds =
                    null,
                reason =
                    "Mapping DIRECT présent mais microphones physiques non résolus; " +
                        "steering désactivé.",
            )
        }

        val firstX =
            firstMicrophone.positionX
        val firstY =
            firstMicrophone.positionY
        val firstZ =
            firstMicrophone.positionZ

        val secondX =
            secondMicrophone.positionX
        val secondY =
            secondMicrophone.positionY
        val secondZ =
            secondMicrophone.positionZ

        if (
            firstX == null ||
            firstY == null ||
            firstZ == null ||
            secondX == null ||
            secondY == null ||
            secondZ == null
        ) {
            return SpatialCaptureConstraints(
                routeClassification =
                    qualification.classification,
                delayTrust =
                    SpatialDelayTrust.INTER_CHANNEL_ONLY,
                steeringAllowed =
                    false,
                physicalGeometryReliable =
                    false,
                physicalMicrophoneDistanceMeters =
                    null,
                maximumPhysicalTdoaSeconds =
                    null,
                reason =
                    "Mapping DIRECT fiable mais géométrie microphone incomplète; " +
                        "steering désactivé.",
            )
        }

        val deltaX =
            firstX.toDouble() -
                secondX.toDouble()
        val deltaY =
            firstY.toDouble() -
                secondY.toDouble()
        val deltaZ =
            firstZ.toDouble() -
                secondZ.toDouble()

        val microphoneDistanceMeters =
            kotlin.math.sqrt(
                deltaX *
                    deltaX +
                    deltaY *
                    deltaY +
                    deltaZ *
                    deltaZ
            )

        if (
            !microphoneDistanceMeters.isFinite() ||
            microphoneDistanceMeters <=
            0.0 ||
            microphoneDistanceMeters >
            MAXIMUM_PLAUSIBLE_DEVICE_MICROPHONE_DISTANCE_METERS
        ) {
            return SpatialCaptureConstraints(
                routeClassification =
                    qualification.classification,
                delayTrust =
                    SpatialDelayTrust.INTER_CHANNEL_ONLY,
                steeringAllowed =
                    false,
                physicalGeometryReliable =
                    false,
                physicalMicrophoneDistanceMeters =
                    microphoneDistanceMeters
                        .takeIf {
                            it.isFinite()
                        },
                maximumPhysicalTdoaSeconds =
                    null,
                reason =
                    "Géométrie microphone DIRECT incohérente; steering désactivé.",
            )
        }

        /*
         * On utilise une vitesse du son volontairement conservative (330 m/s)
         * afin de ne pas sous-estimer la borne physique par temps froid.
         * GuardianSpatialAudio arrondira ensuite vers le haut en échantillons.
         */
        val maximumPhysicalTdoaSeconds =
            microphoneDistanceMeters /
                MINIMUM_SPEED_OF_SOUND_METERS_PER_SECOND

        return SpatialCaptureConstraints(
            routeClassification =
                qualification.classification,
            delayTrust =
                SpatialDelayTrust.PHYSICAL_TDOA,
            steeringAllowed =
                true,
            physicalGeometryReliable =
                true,
            physicalMicrophoneDistanceMeters =
                microphoneDistanceMeters,
            maximumPhysicalTdoaSeconds =
                maximumPhysicalTdoaSeconds,
            reason =
                "Route DIRECT physiquement résolue : distance=" +
                    "${"%.4f".format(microphoneDistanceMeters)}m, " +
                    "TDOA max=${"%.6f".format(maximumPhysicalTdoaSeconds)}s.",
        )
    }

    private fun probeSingleCaptureConfiguration(
    applicationContext: Context,
    configuration: CaptureProbeConfiguration,
): CaptureProbeResult {

    val sourceName =
        probeAudioSourceName(
            configuration.source
        )

    var recorder: AudioRecord? =
        null

    var recordingStarted =
        false

    try {
        val requestedChannelCount =
    configuration.channelIndexMask
        ?.let { mask ->
            Integer.bitCount(mask)
        }
        ?: when (
            configuration.channelMask
        ) {
            AudioFormat.CHANNEL_IN_MONO ->
                1

            AudioFormat.CHANNEL_IN_STEREO ->
                2

            else ->
                1
        }

/*
 * getMinBufferSize() ne permet pas de demander
 * directement un channel index mask 3/4 canaux.
 *
 * On utilise donc MONO/STEREO comme référence,
 * puis on dimensionne le buffer proportionnellement
 * au nombre de canaux demandé.
 */
val referenceChannelMask =
    if (requestedChannelCount <= 1) {
        AudioFormat.CHANNEL_IN_MONO
    } else {
        AudioFormat.CHANNEL_IN_STEREO
    }

val referenceChannelCount =
    if (requestedChannelCount <= 1) {
        1
    } else {
        2
    }

val referenceMinimumBufferSize =
    AudioRecord.getMinBufferSize(
        PROBE_SAMPLE_RATE_HZ,
        referenceChannelMask,
        AudioFormat.ENCODING_PCM_16BIT,
    )

val minimumBufferSize =
    if (referenceMinimumBufferSize > 0) {
        (
            referenceMinimumBufferSize.toLong() *
                requestedChannelCount /
                referenceChannelCount
            )
            .coerceAtMost(Int.MAX_VALUE.toLong())
            .toInt()
    } else {
        referenceMinimumBufferSize
    }

        if (minimumBufferSize <= 0) {
            return CaptureProbeResult(
                source =
                    configuration.source,

                sourceName =
                    sourceName,

                channelMask =
                    configuration.channelMask,

                channelModeName =
                    configuration.channelModeName,

                channelIndexMask =
                    configuration.channelIndexMask,

                initialized =
                    false,

                recordingStarted =
                    false,

                channelCount =
                    null,

                activeMicrophoneCount =
                    null,

                activeMicrophoneDescriptions =
                    emptyList(),

                channelMappings =
                    emptyList(),

                directionRequested =
                    false,

                directionApplied =
                    false,

                fieldRequested =
                    false,

                fieldApplied =
                    false,

                stereoAnalysis =
                    null,

                error =
                    "getMinBufferSize=$minimumBufferSize",
            )
        }

      
                /*
 * Construit le format demandé.
 *
 * - MONO / STEREO historiques :
 *     channelMask positionnel.
 *
 * - Probe multicanal :
 *     channelIndexMask.
 */
val audioFormatBuilder =
    AudioFormat.Builder()
        .setEncoding(
            AudioFormat.ENCODING_PCM_16BIT
        )
        .setSampleRate(
            PROBE_SAMPLE_RATE_HZ
        )

val channelIndexMask =
    configuration.channelIndexMask

if (channelIndexMask != null) {

    if (
        Build.VERSION.SDK_INT <
        Build.VERSION_CODES.M
    ) {
        throw IllegalStateException(
            "Channel index mask nécessite Android 6.0 / API 23+"
        )
    }

    audioFormatBuilder
        .setChannelIndexMask(
            channelIndexMask
        )

} else {

    audioFormatBuilder
        .setChannelMask(
            configuration.channelMask
        )
}

val requestedAudioFormat =
    audioFormatBuilder.build()

recorder =
    AudioRecord.Builder()
        .setAudioSource(
            configuration.source
        )
        .setAudioFormat(
            requestedAudioFormat
        )
        .setBufferSizeInBytes(
            minimumBufferSize *
                PROBE_BUFFER_MULTIPLIER
        )
        .build()

        if (
            recorder.state !=
            AudioRecord.STATE_INITIALIZED
        ) {
            return CaptureProbeResult(
                source =
                    configuration.source,

                sourceName =
                    sourceName,

                channelMask =
                    configuration.channelMask,

                channelModeName =
                    configuration.channelModeName,

                channelIndexMask =
                    configuration.channelIndexMask,

                initialized =
                    false,

                recordingStarted =
                    false,

                channelCount =
                    null,

                activeMicrophoneCount =
                    null,

                activeMicrophoneDescriptions =
                    emptyList(),

                channelMappings =
                    emptyList(),

                directionRequested =
                    false,

                directionApplied =
                    false,

                fieldRequested =
                    false,

                fieldApplied =
                    false,
                    
                stereoAnalysis =
                    null,

                error =
                    "AudioRecord non initialisé",
            )
        }

        /*
         * Guardian C5 :
         * le Probe ne force plus de direction ni de zoom.
         *
         * Ces paramètres étaient capables de modifier le
         * front-end constructeur et rendaient le Probe
         * différent de la baseline runtime C4.
         */
        val directionRequested =
            false

        val directionApplied =
            false

        val fieldRequested =
            false

        val fieldApplied =
            false

        recorder.startRecording()

        recordingStarted =
            recorder.recordingState ==
                AudioRecord.RECORDSTATE_RECORDING

        if (!recordingStarted) {
            throw IllegalStateException(
                "AudioRecord n'est pas passé à RECORDSTATE_RECORDING"
            )
        }

        /*
         * Laisse quelques dizaines de ms au routing audio
         * pour devenir réellement actif.
         */
        SystemClock.sleep(
            PROBE_ROUTE_SETTLE_MS
        )

        val stereoAnalysis =
    if (
        configuration.channelMask ==
            AudioFormat.CHANNEL_IN_STEREO
    ) {
        analyzeStereoCapture(
            recorder = recorder
        )
    } else {
        null
    }

        /*
 * Les probes utilisant un channelIndexMask doivent
 * analyser le PCM réellement retourné par AudioRecord.
 *
 * On utilise recorder.channelCount afin de mesurer
 * ce qu'Android a effectivement ouvert.
 */
val multiChannelAnalysis =
    analyzeMultiChannelCapture(
        recorder = recorder
    )

        val activeMicrophones =
            if (
                Build.VERSION.SDK_INT >=
                Build.VERSION_CODES.P
            ) {
                runCatching {
                    recorder.activeMicrophones
                }.getOrDefault(
                    emptyList()
                )
            } else {
                emptyList()
            }

        val descriptions =
            activeMicrophones.map { microphone ->
                microphone.description
                    ?.toString()
                    ?.takeIf {
                        it.isNotBlank()
                    }
                    ?: "Microphone ${microphone.id}"
            }

        val mappingDetails =
            activeMicrophones.flatMap { microphone ->
                microphone.channelMapping.map { mapping ->

                    val mappingType =
                        when (mapping.second) {
                            MicrophoneInfo
                                .CHANNEL_MAPPING_DIRECT ->
                                ChannelMappingType.DIRECT

                            MicrophoneInfo
                                .CHANNEL_MAPPING_PROCESSED ->
                                ChannelMappingType.PROCESSED

                            else ->
                                ChannelMappingType.UNKNOWN
                        }

                    ChannelMappingDetail(
                        microphoneId =
                            microphone.id,

                        microphoneDescription =
                            microphone.description
                                ?.toString()
                                ?.takeIf {
                                    it.isNotBlank()
                                }
                                ?: "Microphone ${microphone.id}",

                        microphoneGroup =
                            microphone.group,

                        microphoneIndexInGroup =
                            microphone.indexInTheGroup,

                        channelIndex =
                            mapping.first,

                        mappingType =
                            mappingType,
                    )
                }
            }

        val mappings =
            mappingDetails.map { detail ->
                "${detail.microphoneDescription}" +
                    ":channel=${detail.channelIndex}" +
                    ":${detail.mappingType}"
            }

        val routeQualification =
            qualifyCaptureRoute(
                openedChannelCount =
                    recorder.channelCount,

                analysis =
                    multiChannelAnalysis,

                activeMicrophoneCount =
                    activeMicrophones.size,

                mappingDetails =
                    mappingDetails,
            )

        val result =
            CaptureProbeResult(
                source =
                    configuration.source,

                sourceName =
                    sourceName,

                channelMask =
                    configuration.channelMask,

                channelModeName =
                    configuration.channelModeName,

                channelIndexMask =
                    configuration.channelIndexMask,

                initialized =
                    true,

                recordingStarted =
                    true,

                channelCount =
                    recorder.channelCount,

                activeMicrophoneCount =
                    activeMicrophones.size,

                activeMicrophoneDescriptions =
                    descriptions,

                channelMappings =
                    mappings,

                channelMappingDetails =
                    mappingDetails,

                routeQualification =
                    routeQualification,

                directionRequested =
                    directionRequested,

                directionApplied =
                    directionApplied,

                fieldRequested =
                    fieldRequested,

                fieldApplied =
                    fieldApplied,

                stereoAnalysis =
                    stereoAnalysis,

                multiChannelAnalysis =
                    multiChannelAnalysis,

                error =
                    null,
            )

        Log.i(
            TAG,
            "Audio Probe : " +
                "source=${result.sourceName}, " +
                "mode=${result.channelModeName}, " +
                "initialized=${result.initialized}, " +
                "recording=${result.recordingStarted}, " +
                "channels=${result.channelCount}, " +
                "activeMics=${result.activeMicrophoneCount}, " +
                "mappings=${result.channelMappings}, " +
                "route=${result.routeQualification?.classification}, " +
                "mappingNature=${result.routeQualification?.mappingNature}, " +
                "usable=${result.routeQualification?.usableChannelCount}, " +
                "independent=${result.routeQualification?.independentChannelCount}, " +
                "spatialEligible=${result.routeQualification?.spatialEligible}, " +
                "physicalMappingReliable=${result.routeQualification?.physicalMappingReliable}, " +
                "direction=${result.directionApplied}, " +
                "field=${result.fieldApplied}"
        )

        return result

    } catch (error: Throwable) {

        Log.w(
            TAG,
            "Audio Probe échec : " +
                "source=$sourceName, " +
                "mode=${configuration.channelModeName}, " +
                "error=${error.message}",
            error
        )

        return CaptureProbeResult(
            source =
                configuration.source,

            sourceName =
                sourceName,

            channelMask =
                configuration.channelMask,

            channelModeName =
                configuration.channelModeName,

            channelIndexMask =
                configuration.channelIndexMask,

            initialized =
                recorder?.state ==
                    AudioRecord.STATE_INITIALIZED,

            recordingStarted =
                recordingStarted,

            channelCount =
                recorder
                    ?.takeIf {
                        it.state ==
                            AudioRecord.STATE_INITIALIZED
                    }
                    ?.channelCount,

            activeMicrophoneCount =
                null,

            activeMicrophoneDescriptions =
                emptyList(),

            channelMappings =
                emptyList(),

            directionRequested =
                false,

            directionApplied =
                false,

            fieldRequested =
                false,

            fieldApplied =
                false,

                stereoAnalysis =
                    null,

            error =
                error.message
                    ?: error.javaClass.simpleName,
        )

    } finally {

        try {
            if (
                recorder?.recordingState ==
                AudioRecord.RECORDSTATE_RECORDING
            ) {
                recorder?.stop()
            }
        } catch (_: Throwable) {
        }

        try {
            recorder?.release()
        } catch (_: Throwable) {
        }
    }
}

    private fun analyzeStereoCapture(
    recorder: AudioRecord,
): StereoAnalysis? {

    val frameCount =
        PROBE_STEREO_ANALYSIS_FRAMES

    /*
     * PCM stéréo interlacé :
     *
     * L R L R L R ...
     */
    val buffer =
        ShortArray(
            frameCount * 2
        )

    var totalRead =
        0

    while (totalRead < buffer.size) {

        val read =
            recorder.read(
                buffer,
                totalRead,
                buffer.size - totalRead,
                AudioRecord.READ_BLOCKING,
            )

        if (read <= 0) {
            Log.w(
                TAG,
                "Stereo Probe : lecture PCM impossible, code=$read"
            )

            return null
        }

        totalRead +=
            read
    }

    val actualFrames =
        totalRead / 2

    if (actualFrames <= 0) {
        return null
    }

    var leftSquareSum =
        0.0

    var rightSquareSum =
        0.0

    var differenceSquareSum =
        0.0

    var crossSum =
        0.0

    var leftPeak =
        0.0

    var rightPeak =
        0.0

    for (frame in 0 until actualFrames) {

        val left =
            buffer[frame * 2]
                .toDouble() /
                Short.MAX_VALUE.toDouble()

        val right =
            buffer[frame * 2 + 1]
                .toDouble() /
                Short.MAX_VALUE.toDouble()

        leftSquareSum +=
            left * left

        rightSquareSum +=
            right * right

        val difference =
            left - right

        differenceSquareSum +=
            difference * difference

        crossSum +=
            left * right

        leftPeak =
            maxOf(
                leftPeak,
                kotlin.math.abs(left)
            )

        rightPeak =
            maxOf(
                rightPeak,
                kotlin.math.abs(right)
            )
    }

    val leftRms =
        kotlin.math.sqrt(
            leftSquareSum /
                actualFrames
        )

    val rightRms =
        kotlin.math.sqrt(
            rightSquareSum /
                actualFrames
        )

    val differenceRms =
        kotlin.math.sqrt(
            differenceSquareSum /
                actualFrames
        )

    val denominator =
        kotlin.math.sqrt(
            leftSquareSum *
                rightSquareSum
        )

    val correlation =
        if (denominator > 1e-12) {
            (
                crossSum /
                    denominator
            ).coerceIn(
                -1.0,
                1.0
            )
        } else {
            0.0
        }

    val result =
        StereoAnalysis(
            samplesPerChannel =
                actualFrames,

            leftRms =
                leftRms,

            rightRms =
                rightRms,

            leftPeak =
                leftPeak,

            rightPeak =
                rightPeak,

            correlation =
                correlation,

            differenceRms =
                differenceRms,
        )

    Log.i(
        "GuardianStereoProbe",
        "PCM : " +
            "frames=${result.samplesPerChannel}, " +
            "leftRms=${"%.6f".format(result.leftRms)}, " +
            "rightRms=${"%.6f".format(result.rightRms)}, " +
            "leftPeak=${"%.6f".format(result.leftPeak)}, " +
            "rightPeak=${"%.6f".format(result.rightPeak)}, " +
            "correlation=${"%.6f".format(result.correlation)}, " +
            "differenceRms=${"%.6f".format(result.differenceRms)}"
    )

    return result
}


    private fun qualifyCaptureRoute(
        openedChannelCount: Int,
        analysis: MultiChannelAnalysis?,
        activeMicrophoneCount: Int,
        mappingDetails: List<ChannelMappingDetail>,
    ): CaptureRouteQualification {

        if (
            openedChannelCount <= 0 ||
            analysis == null
        ) {
            return CaptureRouteQualification(
                openedChannelCount =
                    openedChannelCount.coerceAtLeast(0),

                usableChannelCount =
                    0,

                independentChannelCount =
                    0,

                activeMicrophoneCount =
                    activeMicrophoneCount.coerceAtLeast(0),

                mappingNature =
                    CaptureRouteMappingNature.NONE,

                classification =
                    CaptureRouteClassification.UNAVAILABLE,

                spatialEligible =
                    false,

                physicalMappingReliable =
                    false,

                reason =
                    "Route non caractérisable : capture ou analyse PCM indisponible.",
            )
        }

        val usableChannelCount =
            analysis.usableChannelCount

        val independentChannelCount =
            analysis.independentChannelCount

        val usableChannelSet =
            analysis.usableChannelIndices.toSet()

        /*
         * Seuls les mappings qui ciblent un canal PCM réellement utilisable
         * participent à la qualification de la route.
         */
        val relevantMappings =
            mappingDetails.filter { detail ->
                detail.channelIndex in
                    usableChannelSet
            }

        val hasDirectMapping =
            relevantMappings.any { detail ->
                detail.mappingType ==
                    ChannelMappingType.DIRECT
            }

        val hasProcessedMapping =
            relevantMappings.any { detail ->
                detail.mappingType ==
                    ChannelMappingType.PROCESSED
            }

        val hasUnknownMapping =
            relevantMappings.any { detail ->
                detail.mappingType ==
                    ChannelMappingType.UNKNOWN
            }

        val mappingNature =
            when {
                relevantMappings.isEmpty() ->
                    CaptureRouteMappingNature.NONE

                hasUnknownMapping ->
                    if (
                        hasDirectMapping ||
                        hasProcessedMapping
                    ) {
                        CaptureRouteMappingNature.MIXED
                    } else {
                        CaptureRouteMappingNature.UNKNOWN
                    }

                hasDirectMapping &&
                    hasProcessedMapping ->
                    CaptureRouteMappingNature.MIXED

                hasDirectMapping ->
                    CaptureRouteMappingNature.DIRECT

                hasProcessedMapping ->
                    CaptureRouteMappingNature.PROCESSED

                else ->
                    CaptureRouteMappingNature.UNKNOWN
            }

        /*
         * Un mapping physique est considéré fiable uniquement si chaque canal
         * acoustiquement indépendant possède exactement un mapping DIRECT vers
         * un microphone distinct, sans mapping PROCESSED/UNKNOWN concurrent.
         *
         * Cette règle est volontairement stricte : lorsqu'Android ne garantit
         * pas la correspondance canal -> microphone physique, Guardian ne doit
         * pas utiliser la géométrie matérielle comme une vérité.
         */
        val physicalMappingReliable =
            if (
                independentChannelCount >= 2 &&
                mappingNature ==
                    CaptureRouteMappingNature.DIRECT
            ) {
                val directMappingsByChannel =
                    analysis.independentChannelIndices
                        .associateWith { channel ->
                            relevantMappings.filter { detail ->
                                detail.channelIndex ==
                                    channel &&
                                    detail.mappingType ==
                                    ChannelMappingType.DIRECT
                            }
                        }

                val exactlyOneDirectMappingPerChannel =
                    directMappingsByChannel.values.all { mappings ->
                        mappings.size == 1
                    }

                val microphoneIdentities =
                    directMappingsByChannel.values
                        .mapNotNull { mappings ->
                            mappings.singleOrNull()
                                ?.microphoneIdentity
                        }

                exactlyOneDirectMappingPerChannel &&
                    microphoneIdentities.size ==
                    independentChannelCount &&
                    microphoneIdentities.distinct().size ==
                    independentChannelCount
            } else {
                false
            }

        val spatialEligible =
            usableChannelCount >= 2 &&
                independentChannelCount >= 2 &&
                activeMicrophoneCount >= 2

        val classification =
            when {
                usableChannelCount <= 0 ->
                    CaptureRouteClassification.UNUSABLE

                openedChannelCount >= 2 &&
                    independentChannelCount <= 1 ->
                    CaptureRouteClassification.DUPLICATED_MULTICHANNEL

                spatialEligible &&
                    physicalMappingReliable ->
                    CaptureRouteClassification.SPATIAL_DIRECT

                spatialEligible &&
                    mappingNature ==
                    CaptureRouteMappingNature.PROCESSED ->
                    CaptureRouteClassification.SPATIAL_PROCESSED

                spatialEligible ->
                    CaptureRouteClassification.SPATIAL_MIXED_OR_UNKNOWN

                mappingNature ==
                    CaptureRouteMappingNature.DIRECT ->
                    CaptureRouteClassification.MONO_DIRECT

                mappingNature ==
                    CaptureRouteMappingNature.PROCESSED ->
                    CaptureRouteClassification.MONO_PROCESSED

                else ->
                    CaptureRouteClassification.MONO_MIXED_OR_UNKNOWN
            }

        val reason =
            when (classification) {
                CaptureRouteClassification.UNAVAILABLE ->
                    "Route indisponible."

                CaptureRouteClassification.UNUSABLE ->
                    "Capture ouverte mais aucun canal PCM exploitable."

                CaptureRouteClassification.MONO_DIRECT ->
                    "Une seule information acoustique indépendante avec mapping DIRECT."

                CaptureRouteClassification.MONO_PROCESSED ->
                    "Une seule information acoustique indépendante issue d'un mapping PROCESSED."

                CaptureRouteClassification.MONO_MIXED_OR_UNKNOWN ->
                    "Une seule information acoustique indépendante avec mapping incomplet ou ambigu."

                CaptureRouteClassification.DUPLICATED_MULTICHANNEL ->
                    "Plusieurs canaux sont ouverts mais ils ne fournissent pas plusieurs informations acoustiques indépendantes."

                CaptureRouteClassification.SPATIAL_DIRECT ->
                    "Plusieurs canaux indépendants avec mapping DIRECT canal-vers-micro physiquement fiable."

                CaptureRouteClassification.SPATIAL_PROCESSED ->
                    "Plusieurs canaux indépendants exploitables, mais Android les expose comme PROCESSED ; la géométrie physique ne doit pas être supposée fiable."

                CaptureRouteClassification.SPATIAL_MIXED_OR_UNKNOWN ->
                    "Plusieurs canaux indépendants exploitables avec mapping Android mixte ou incomplet ; utilisation spatiale possible mais géométrie physique non fiable."
            }

        return CaptureRouteQualification(
            openedChannelCount =
                openedChannelCount,

            usableChannelCount =
                usableChannelCount,

            independentChannelCount =
                independentChannelCount,

            activeMicrophoneCount =
                activeMicrophoneCount,

            mappingNature =
                mappingNature,

            classification =
                classification,

            spatialEligible =
                spatialEligible,

            physicalMappingReliable =
                physicalMappingReliable,

            reason =
                reason,
        )
    }

      private fun analyzeMultiChannelCapture(
    recorder: AudioRecord,
): MultiChannelAnalysis? {

    val channelCount =
        recorder.channelCount

    if (channelCount <= 0) {
        Log.w(
            TAG,
            "MultiChannel Probe : channelCount invalide=$channelCount"
        )

        return null
    }

    val frameCount =
        PROBE_MULTICHANNEL_ANALYSIS_FRAMES

    /*
     * PCM interlacé :
     *
     * 2 canaux :
     * C0 C1 C0 C1 ...
     *
     * 3 canaux :
     * C0 C1 C2 C0 C1 C2 ...
     *
     * etc.
     */
    val buffer =
        ShortArray(
            frameCount * channelCount
        )

    var totalRead =
        0

    while (totalRead < buffer.size) {

        val read =
            recorder.read(
                buffer,
                totalRead,
                buffer.size - totalRead,
                AudioRecord.READ_BLOCKING,
            )

        if (read <= 0) {
            Log.w(
                TAG,
                "MultiChannel Probe : lecture PCM impossible, code=$read"
            )

            return null
        }

        totalRead +=
            read
    }

    /*
     * Par sécurité, on ne conserve que des frames
     * entièrement constituées.
     */
    val alignedSampleCount =
        totalRead -
            (totalRead % channelCount)

    val actualFrames =
        alignedSampleCount /
            channelCount

    if (actualFrames <= 0) {
        return null
    }

    val squareSums =
        DoubleArray(
            channelCount
        )

    val peaks =
        DoubleArray(
            channelCount
        )

    /*
     * Premier passage :
     * énergie et pic de chaque canal.
     */
    for (frame in 0 until actualFrames) {

        val frameOffset =
            frame * channelCount

        for (channel in 0 until channelCount) {

            val sample =
                buffer[
                    frameOffset + channel
                ].toDouble() /
                    Short.MAX_VALUE.toDouble()

            squareSums[channel] +=
                sample * sample

            peaks[channel] =
                maxOf(
                    peaks[channel],
                    kotlin.math.abs(sample),
                )
        }
    }

    val rmsPerChannel =
        squareSums.map { squareSum ->
            kotlin.math.sqrt(
                squareSum /
                    actualFrames
            )
        }

    /*
     * Deuxième passage :
     * comparaison de toutes les paires de canaux.
     */
    val pairAnalyses =
        ArrayList<ChannelPairAnalysis>()

    for (
        firstChannel in
        0 until channelCount
    ) {
        for (
            secondChannel in
            firstChannel + 1 until channelCount
        ) {

            var crossSum =
                0.0

            var differenceSquareSum =
                0.0

            for (frame in 0 until actualFrames) {

                val frameOffset =
                    frame * channelCount

                val first =
                    buffer[
                        frameOffset +
                            firstChannel
                    ].toDouble() /
                        Short.MAX_VALUE.toDouble()

                val second =
                    buffer[
                        frameOffset +
                            secondChannel
                    ].toDouble() /
                        Short.MAX_VALUE.toDouble()

                crossSum +=
                    first * second

                val difference =
                    first - second

                differenceSquareSum +=
                    difference *
                        difference
            }

            val denominator =
                kotlin.math.sqrt(
                    squareSums[firstChannel] *
                        squareSums[secondChannel]
                )

            val correlation =
                if (
                    denominator >
                    1e-12
                ) {
                    (
                        crossSum /
                            denominator
                    ).coerceIn(
                        -1.0,
                        1.0,
                    )
                } else {
                    0.0
                }

            val differenceRms =
                kotlin.math.sqrt(
                    differenceSquareSum /
                        actualFrames
                )

            pairAnalyses.add(
                ChannelPairAnalysis(
                    firstChannel =
                        firstChannel,

                    secondChannel =
                        secondChannel,

                    correlation =
                        correlation,

                    differenceRms =
                        differenceRms,
                )
            )
        }
    }

    /*
     * Troisième passage : qualification structurelle des canaux.
     *
     * Un canal "usable" transporte réellement un signal PCM. Le seuil
     * relatif est volontairement très permissif (-60 dB par rapport au canal
     * le plus énergique) afin d'écarter les canaux morts sans éliminer un
     * microphone simplement plus faible.
     */
    val maximumRms =
        rmsPerChannel.maxOrNull()
            ?: 0.0

    val peakList =
        peaks.toList()

    val maximumPeak =
        peakList.maxOrNull()
            ?: 0.0

    val usableRmsThreshold =
        maxOf(
            MINIMUM_USABLE_CHANNEL_RMS,
            maximumRms *
                MINIMUM_USABLE_CHANNEL_RELATIVE_LEVEL,
        )

    val usablePeakThreshold =
        maxOf(
            MINIMUM_USABLE_CHANNEL_PEAK,
            maximumPeak *
                MINIMUM_USABLE_CHANNEL_RELATIVE_LEVEL,
        )

    val usableChannelIndices =
        (0 until channelCount)
            .filter { channel ->
                rmsPerChannel[channel] >=
                    usableRmsThreshold &&
                    peakList[channel] >=
                    usablePeakThreshold
            }

    /*
     * Parmi les canaux utilisables, on élimine les duplications évidentes.
     *
     * Deux canaux ne sont considérés comme dupliqués que si :
     * - leur corrélation est quasiment parfaite ;
     * - ET leur différence RMS est négligeable relativement à leur énergie.
     *
     * Cela évite de confondre deux vrais micros très corrélés avec deux copies
     * numériques du même flux.
     */
    val independentChannelIndices =
        ArrayList<Int>()

    usableChannelIndices.forEach { candidate ->

        val duplicated =
            independentChannelIndices.any { reference ->

                val pair =
                    pairAnalyses.firstOrNull { analysis ->
                        (
                            analysis.firstChannel == reference &&
                                analysis.secondChannel == candidate
                            ) ||
                            (
                                analysis.firstChannel == candidate &&
                                    analysis.secondChannel == reference
                                )
                    }

                if (pair == null) {
                    false
                } else {
                    val referenceLevel =
                        maxOf(
                            rmsPerChannel[reference],
                            rmsPerChannel[candidate],
                            CHANNEL_ANALYSIS_EPSILON,
                        )

                    val normalizedDifference =
                        pair.differenceRms /
                            referenceLevel

                    abs(pair.correlation) >=
                        DUPLICATED_CHANNEL_MIN_ABS_CORRELATION &&
                        normalizedDifference <=
                        DUPLICATED_CHANNEL_MAX_NORMALIZED_DIFFERENCE
                }
            }

        if (!duplicated) {
            independentChannelIndices.add(
                candidate
            )
        }
    }

    val result =
        MultiChannelAnalysis(
            channelCount =
                channelCount,

            samplesPerChannel =
                actualFrames,

            rmsPerChannel =
                rmsPerChannel,

            peakPerChannel =
                peakList,

            pairAnalyses =
                pairAnalyses,

            usableChannelIndices =
                usableChannelIndices,

            independentChannelIndices =
                independentChannelIndices,
        )

    Log.i(
        "GuardianMultiChannelProbe",
        "PCM : " +
            "channels=${result.channelCount}, " +
            "usable=${result.usableChannelCount}${result.usableChannelIndices}, " +
            "independent=${result.independentChannelCount}${result.independentChannelIndices}, " +
            "frames=${result.samplesPerChannel}, " +
            "rms=${result.rmsPerChannel.map { "%.6f".format(it) }}, " +
            "peaks=${result.peakPerChannel.map { "%.6f".format(it) }}, " +
            "pairs=${
                result.pairAnalyses.map {
                    "${it.firstChannel}/${it.secondChannel}" +
                        ":corr=${"%.6f".format(it.correlation)}" +
                        ":diff=${"%.6f".format(it.differenceRms)}"
                }
            }"
    )

    return result
}  

    private fun probeAudioSourceName(
    source: Int,
): String =
    when (source) {
        MediaRecorder.AudioSource.VOICE_RECOGNITION ->
            "VOICE_RECOGNITION"

        MediaRecorder.AudioSource.MIC ->
            "MIC"

        MediaRecorder.AudioSource.VOICE_COMMUNICATION ->
            "VOICE_COMMUNICATION"

        MediaRecorder.AudioSource.UNPROCESSED ->
            "UNPROCESSED"

        else ->
            "SOURCE_$source"
    }

     private fun detectMicrophones(
    audioManager: AudioManager,
): List<MicrophoneCapability> {

    if (
        Build.VERSION.SDK_INT <
        Build.VERSION_CODES.P
    ) {
        return emptyList()
    }

    val androidMicrophones =
        runCatching {
            audioManager.microphones
        }.onFailure { error ->
            Log.w(
                TAG,
                "Inventaire des microphones Android impossible",
                error
            )
        }.getOrDefault(
            emptyList()
        )

    return androidMicrophones.map { microphone ->

        val sensitivity =
    microphone.sensitivity
        .takeIf {
            it.isFinite() &&
                it != MicrophoneInfo.SENSITIVITY_UNKNOWN
        }

        val position =
            microphone.position

        val orientation =
            microphone.orientation

        MicrophoneCapability(
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

            locationName =
                microphoneLocationName(
                    microphone.location
                ),

            group =
                microphone.group,

            indexInGroup =
                microphone.indexInTheGroup,

            directionality =
                microphone.directionality,

            directionalityName =
                microphoneDirectionalityName(
                    microphone.directionality
                ),

            sensitivity =
                sensitivity,

            positionX =
                position.x
                    .takeIf {
                        it.isFinite()
                    },

            positionY =
                position.y
                    .takeIf {
                        it.isFinite()
                    },

            positionZ =
                position.z
                    .takeIf {
                        it.isFinite()
                    },

            orientationX =
                orientation.x
                    .takeIf {
                        it.isFinite()
                    },

            orientationY =
                orientation.y
                    .takeIf {
                        it.isFinite()
                    },

            orientationZ =
                orientation.z
                    .takeIf {
                        it.isFinite()
                    },

            frequencyResponsePointCount =
                microphone.frequencyResponse
                    .size,

            builtIn =
                microphone.type ==
                    AudioDeviceInfo
                        .TYPE_BUILTIN_MIC,
        )
    }
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

        private fun microphoneLocationName(
    location: Int,
): String =
    when (location) {
        MicrophoneInfo.LOCATION_MAINBODY ->
            "MAINBODY"

        MicrophoneInfo.LOCATION_MAINBODY_MOVABLE ->
            "MAINBODY_MOVABLE"

        MicrophoneInfo.LOCATION_PERIPHERAL ->
            "PERIPHERAL"

        MicrophoneInfo.LOCATION_UNKNOWN ->
            "UNKNOWN"

        else ->
            "LOCATION_$location"
    }

        private fun microphoneDirectionalityName(
    directionality: Int,
): String =
    when (directionality) {
        MicrophoneInfo.DIRECTIONALITY_OMNI ->
            "OMNI"

        MicrophoneInfo.DIRECTIONALITY_BI_DIRECTIONAL ->
            "BI_DIRECTIONAL"

        MicrophoneInfo.DIRECTIONALITY_CARDIOID ->
            "CARDIOID"

        MicrophoneInfo.DIRECTIONALITY_HYPER_CARDIOID ->
            "HYPER_CARDIOID"

        MicrophoneInfo.DIRECTIONALITY_SUPER_CARDIOID ->
            "SUPER_CARDIOID"

        MicrophoneInfo.DIRECTIONALITY_UNKNOWN ->
            "UNKNOWN"

        else ->
            "DIRECTIONALITY_$directionality"
    }

    private fun runAudioBenchmark(): BenchmarkResult {

    val sampleCount =
        BENCHMARK_SAMPLE_RATE_HZ *
            BENCHMARK_FRAME_DURATION_MS /
            1_000

    /*
     * Signal artificiel.
     *
     * Aucun accès microphone n'est effectué.
     */
    val input =
        FloatArray(
            sampleCount
        ) { index ->

            sin(
                index.toDouble() *
                    0.017
            )
                .toFloat() *
                0.05f
        }

    val output =
        FloatArray(
            sampleCount
        )

    /*
     * Warmup :
     * évite de mesurer le coût du premier passage/JIT.
     */
    repeat(
        BENCHMARK_WARMUP_ITERATIONS
    ) {
        runSyntheticAudioWork(
            input = input,
            output = output,
        )
    }

    val durationsMs =
        ArrayList<Double>(
            BENCHMARK_MEASURE_ITERATIONS
        )

    repeat(
        BENCHMARK_MEASURE_ITERATIONS
    ) {

        val startedAtNanos =
            System.nanoTime()

        runSyntheticAudioWork(
            input = input,
            output = output,
        )

        val elapsedNanos =
            System.nanoTime() -
                startedAtNanos

        val elapsedMs =
            elapsedNanos /
                1_000_000.0

        durationsMs.add(
            elapsedMs
        )
    }

    val sortedDurations =
        durationsMs.sorted()

    val averageMs =
        if (
            sortedDurations.isEmpty()
        ) {
            0.0
        } else {
            sortedDurations.average()
        }

    val p95Ms =
        if (
            sortedDurations.isEmpty()
        ) {
            0.0
        } else {

            val p95Index =
                (
                    (
                        sortedDurations.size -
                            1
                        ) *
                        0.95
                    )
                    .toInt()
                    .coerceIn(
                        0,
                        sortedDurations.lastIndex
                    )

            sortedDurations[
                p95Index
            ]
        }

    val headroomRatio =
        if (
            p95Ms <=
                0.0
        ) {
            Double.POSITIVE_INFINITY
        } else {
            BENCHMARK_FRAME_DURATION_MS
                .toDouble() /
                p95Ms
        }

    return BenchmarkResult(
        averageMs =
            averageMs,

        p95Ms =
            p95Ms,

        headroomRatio =
            headroomRatio,
    )
}

    private fun runSyntheticAudioWork(
    input: FloatArray,
    output: FloatArray,
) {

    var previousSample =
        0.0f

    var noiseEstimate =
        0.001f

    for (
        index in
            input.indices
    ) {

        val sample =
            input[index]

        val absoluteSample =
            abs(
                sample
            )

        /*
         * Estimation progressive du bruit.
         */
        noiseEstimate +=
            (
                absoluteSample -
                    noiseEstimate
                ) *
                0.01f

        /*
         * Petit filtre passe-haut synthétique.
         */
        val highPass =
            sample -
                previousSample *
                    0.95f

        previousSample =
            sample

        /*
         * Atténuation synthétique des composantes
         * proches du bruit estimé.
         */
        val cleaned =
            if (
                abs(
                    highPass
                ) <
                noiseEstimate *
                    0.8f
            ) {
                highPass *
                    0.35f
            } else {
                highPass
            }

        output[index] =
            cleaned
    }
}

private const val TAG =
    "GuardianAudioCapabilities"

private const val PROBE_SAMPLE_RATE_HZ =
    16_000

private const val PROBE_BUFFER_MULTIPLIER =
    4

private const val PROBE_ROUTE_SETTLE_MS =
    120L

/*
 * Bornes de sécurité géométriques pour le TDOA physique.
 */
private const val MINIMUM_SPEED_OF_SOUND_METERS_PER_SECOND =
    330.0

private const val MAXIMUM_PLAUSIBLE_DEVICE_MICROPHONE_DISTANCE_METERS =
    0.35

private const val PROBE_STEREO_ANALYSIS_FRAMES =
    8_000

private const val PROBE_MULTICHANNEL_ANALYSIS_FRAMES =
    4_096

/*
 * Qualification structurelle des canaux.
 *
 * Le niveau relatif de 0,001 correspond à -60 dB par rapport au canal le
 * plus énergique du même probe. Ces seuils ne servent PAS à décider si une
 * parole est assez forte : ils servent uniquement à distinguer un canal
 * réellement alimenté d'un canal structurellement nul.
 */
private const val MINIMUM_USABLE_CHANNEL_RMS =
    1.0e-6

private const val MINIMUM_USABLE_CHANNEL_PEAK =
    1.0e-6

private const val MINIMUM_USABLE_CHANNEL_RELATIVE_LEVEL =
    1.0e-3

/*
 * Détection de duplication numérique évidente.
 *
 * On exige à la fois une corrélation quasi parfaite et une différence RMS
 * inférieure à 2 % du niveau du canal le plus énergique de la paire.
 */
private const val DUPLICATED_CHANNEL_MIN_ABS_CORRELATION =
    0.9995

private const val DUPLICATED_CHANNEL_MAX_NORMALIZED_DIFFERENCE =
    0.02

private const val CHANNEL_ANALYSIS_EPSILON =
    1.0e-12

private const val BENCHMARK_SAMPLE_RATE_HZ =
    16_000

private const val BENCHMARK_FRAME_DURATION_MS =
    50

private const val BENCHMARK_WARMUP_ITERATIONS =
    20

private const val BENCHMARK_MEASURE_ITERATIONS =
    80

private const val HIGH_PROFILE_MIN_HEADROOM =
    5.0

private const val BALANCED_PROFILE_MIN_HEADROOM =
    2.0
}