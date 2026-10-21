package expo.modules.guardiannativeprotection

import android.util.Log
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sqrt
import java.util.ArrayDeque

/*
 * Guardian ASR Input Normalizer V2 - ASR Level Adapter.
 *
 * Responsabilité :
 *
 * Adapter uniquement le niveau nécessaire à Sherpa APRÈS
 * le traitement principal réalisé par GuardianAudioEngine.
 *
 * Chaîne :
 *
 * capture
 *   -> éventuel GTCRN
 *   -> Adaptive Gain
 *   -> Soft Limiter
 *   -> GuardianAsrInputNormalizer
 *   -> Sherpa ASR
 *
 * Le Normalizer utilise un seul contrôle adaptatif.
 *
 * Il mesure directement le PCM reçu et calcule le boost
 * réellement nécessaire à partir :
 * - du niveau de parole estimé ;
 * - du niveau de bruit estimé ;
 * - du headroom disponible ;
 * - des limites de sécurité ASR.
 *
 * Il NE fait PAS :
 * - de VAD ;
 * - de Far Voice ;
 * - de réduction de bruit ;
 * - de speech enhancement ;
 * - de GTCRN ;
 * - de modification spectrale.
 */
internal class GuardianAsrInputNormalizer(
    private val sampleRate: Int,
) {

    
    data class Diagnostics(
        val active: Boolean,
        

        val currentGainDb: Float,

        val estimatedSpeechDbfs: Float?,
        val estimatedNoiseDbfs: Float?,

        val lastInputRmsDbfs: Float?,
        val lastOutputRmsDbfs: Float?,

        val lastInputPeakDbfs: Float?,
        val lastOutputPeakDbfs: Float?,

        val totalProcessedFrames: Long,
        val totalLimitedSamples: Long,
    )

    private var active =
        false

   
    /*
     * Gain réellement appliqué à la dernière frame.
     *
     * Stocké en dB afin que les transitions soient
     * perceptuellement et mathématiquement propres.
     */
    private var currentGainDb =
        0.0f

    /*
     * Estimation du niveau de la parole utile.
     */
    private var estimatedSpeechDbfs =
        DEFAULT_SPEECH_LEVEL_DBFS

    private var hasSpeechEstimate =
        false

    /*
     * Estimation du bruit ambiant.
     */
    private var estimatedNoiseDbfs =
        DEFAULT_NOISE_LEVEL_DBFS

    private var hasNoiseEstimate =
        false

    private var lastInputRmsDbfs: Float? =
        null

    private var lastOutputRmsDbfs: Float? =
        null

    private var lastInputPeakDbfs: Float? =
        null

    private var lastOutputPeakDbfs: Float? =
        null

    private var totalProcessedFrames =
        0L

    private var totalLimitedSamples =
        0L

    /**
     * Prépare le normalizer pour une nouvelle utterance distante.
     *
     * Le pré-roll permet de connaître le contexte acoustique
     * AVANT d'envoyer le premier mot à Sherpa.
     *
     * C'est volontaire :
     *
     * on ne veut pas démarrer à 0 dB puis attendre plusieurs
     * centaines de millisecondes avant d'obtenir le bon gain.
     */
    fun startDistantUtterance(
    preRollFrames: List<FloatArray>,
) {
    reset()

    active =
        true

        if (preRollFrames.isEmpty()) {
            return
        }

        val frameRmsLevels =
            ArrayList<Float>(
                preRollFrames.size
            )

        for (frame in preRollFrames) {

            if (frame.isEmpty()) {
                continue
            }

            val rms =
                calculateRms(
                    frame
                )

            if (
                rms <=
                MIN_MEASURABLE_LINEAR
            ) {
                continue
            }

            val dbfs =
                linearToDbfs(
                    rms
                )

            if (
                dbfs.isFinite()
            ) {
                frameRmsLevels.add(
                    dbfs
                )
            }
        }

        if (frameRmsLevels.isEmpty()) {
            return
        }

        /*
         * Noise :
         *
         * percentile bas du pré-roll.
         *
         * On évite de prendre simplement la frame la plus faible,
         * qui pourrait être un silence exceptionnel.
         */
        estimatedNoiseDbfs =
            percentile(
                values =
                    frameRmsLevels,

                percentile =
                    PRE_ROLL_NOISE_PERCENTILE,
            )

        hasNoiseEstimate =
            true

        /*
         * Speech :
         *
         * percentile haut mais pas maximum.
         *
         * Cela permet d'ignorer un choc ou une manipulation
         * unique du téléphone.
         */
        val speechCandidateDbfs =
            percentile(
                values =
                    frameRmsLevels,

                percentile =
                    PRE_ROLL_SPEECH_PERCENTILE,
            )

        estimatedSpeechDbfs =
            maxOf(
                speechCandidateDbfs,

                estimatedNoiseDbfs +
                    MIN_SPEECH_TO_NOISE_DB
            )

        hasSpeechEstimate =
            true

        /*
         * Le premier gain est calculé immédiatement.
         *
         * Le pré-roll sera donc déjà correctement normalisé
         * lorsque Sherpa le recevra.
         */
        val maximumGainDb =
            MAX_ASR_GAIN_DB

        currentGainDb =
            calculateBaseDesiredGainDb()
                .coerceIn(
                    MIN_NORMALIZER_GAIN_DB,
                    maximumGainDb,
                )
    }

    /**
     * Traite une frame PCM Float32.
     *
     * Retourne :
     *
     * - le buffer original si le normalizer est inactif ;
     * - un nouveau buffer normalisé si l'utterance est distante.
     */
    fun process(
        samples: FloatArray,
    ): FloatArray {

        if (
            !active ||
            samples.isEmpty()
        ) {
            return samples
        }

        val inputRms =
            calculateRms(
                samples
            )

        val inputPeak =
            calculatePeak(
                samples
            )

        val inputRmsDbfs =
            linearToDbfs(
                inputRms
            )

        val inputPeakDbfs =
            linearToDbfs(
                inputPeak
            )

        lastInputRmsDbfs =
            inputRmsDbfs

        lastInputPeakDbfs =
            inputPeakDbfs

        /*
         * Mise à jour des estimations parole / bruit.
         */
        updateLevelEstimators(
            frameRmsDbfs =
                inputRmsDbfs
        )

        /*
         * Gain demandé par le niveau de parole
         * et par la limite de bruit.
         */
        val baseDesiredGainDb =
            calculateBaseDesiredGainDb()

        val maximumGainDb =
            MAX_ASR_GAIN_DB

        /*
         * Le Normalizer ne peut jamais appliquer de gain négatif.
         *
         * Le Headroom Guard limite seulement le BOOST supplémentaire
         * encore autorisé au-dessus du PCM fourni par AudioEngine.
         */
        val peakAllowedGainDb =
            if (
                inputPeak >
                MIN_MEASURABLE_LINEAR
            ) {
                maxOf(
                    MIN_NORMALIZER_GAIN_DB,
                    TARGET_PEAK_DBFS -
                        inputPeakDbfs,
                )
            } else {
                maximumGainDb
            }

        val desiredGainDb =
            minOf(
                baseDesiredGainDb,
                peakAllowedGainDb,
                maximumGainDb,
            )
                .coerceAtLeast(
                    MIN_NORMALIZER_GAIN_DB
                )

        /*
         * Durée réelle de la frame.
         */
        val frameDurationSeconds =
            samples.size.toFloat() /
                sampleRate.toFloat()

        /*
         * Le gain monte doucement.
         *
         * Il redescend plus rapidement si la voix devient forte.
         */
        var startGainDb =
    currentGainDb

var targetGainDb: Float

/*
 * Si le headroom diminue, on retire immédiatement tout
 * ou partie du BOOST ajouté par le Normalizer.
 *
 * Le plancher à 0 dB garantit que le PCM amont n'est
 * jamais atténué par cette couche.
 */
if (
    peakAllowedGainDb <
    startGainDb
) {
    startGainDb =
        peakAllowedGainDb

    targetGainDb =
        peakAllowedGainDb

} else {

    targetGainDb =
        smoothGainDb(
            currentDb =
                currentGainDb,

            targetDb =
                desiredGainDb,

            frameDurationSeconds =
                frameDurationSeconds,
        )
}

        startGainDb =
            startGainDb.coerceIn(
                MIN_NORMALIZER_GAIN_DB,
                maximumGainDb,
            )

        targetGainDb =
            targetGainDb.coerceIn(
                MIN_NORMALIZER_GAIN_DB,
                maximumGainDb,
            )

        val output =
            FloatArray(
                samples.size
            )

        var limitedSamplesInFrame =
            0L

        /*
         * Interpolation du gain sur toute la frame.
         *
         * On évite :
         *
         * frame N   = x4
         * frame N+1 = x12
         *
         * avec une cassure brutale de la forme d'onde.
         */
        val denominator =
            maxOf(
                1,
                samples.size - 1
            )

        for (
            index in
            samples.indices
        ) {

            val interpolation =
                index.toFloat() /
                    denominator.toFloat()

            val interpolatedGainDb =
                startGainDb +
                    (
                        targetGainDb -
                            startGainDb
                    ) *
                    interpolation

            val linearGain =
                dbToLinear(
                    interpolatedGainDb
                )

            val inputSample =
                samples[index]
                    .takeIf {
                        it.isFinite()
                    }
                    ?: 0.0f

            val amplified =
                inputSample *
                    linearGain

            /*
             * Hard safety numérique uniquement.
             *
             * Ce clamp n'est pas un deuxième compresseur ;
             * il protège seulement le domaine Float32 envoyé à Sherpa.
             */
            val limited =
                amplified.coerceIn(
                    -ABSOLUTE_LIMITER_LINEAR,
                    ABSOLUTE_LIMITER_LINEAR,
                )

            if (
                limited !=
                amplified
            ) {
                limitedSamplesInFrame +=
                    1L
            }

            output[index] =
                limited
        }

        currentGainDb =
            targetGainDb

        totalProcessedFrames +=
            1L

        totalLimitedSamples +=
            limitedSamplesInFrame

        lastOutputRmsDbfs =
            linearToDbfs(
                calculateRms(
                    output
                )
            )

        lastOutputPeakDbfs =
            linearToDbfs(
                calculatePeak(
                    output
                )
            )

        return output
    }

    /**
     * Met à jour les niveaux acoustiques.
     *
     * La logique est volontairement asymétrique :
     *
     * - la parole ne doit pas contaminer rapidement
     *   l'estimation du bruit ;
     *
     * - le bruit ne doit pas faire varier brutalement
     *   le niveau de parole estimé.
     */
    private fun updateLevelEstimators(
        frameRmsDbfs: Float,
    ) {

        if (!frameRmsDbfs.isFinite()) {
            return
        }

        if (!hasNoiseEstimate) {

            estimatedNoiseDbfs =
                frameRmsDbfs

            hasNoiseEstimate =
                true
        }

        val estimatedSnrDb =
            frameRmsDbfs -
                estimatedNoiseDbfs

        val speechCandidate =
            frameRmsDbfs >=
                MIN_SPEECH_LEVEL_DBFS &&
                estimatedSnrDb >=
                    MIN_SPEECH_TO_NOISE_DB

        if (speechCandidate) {

            if (!hasSpeechEstimate) {

                estimatedSpeechDbfs =
                    frameRmsDbfs

                hasSpeechEstimate =
                    true

            } else {

                estimatedSpeechDbfs =
                    interpolate(
                        previous =
                            estimatedSpeechDbfs,

                        current =
                            frameRmsDbfs,

                        alpha =
                            SPEECH_ESTIMATOR_ALPHA,
                    )
            }

            /*
             * Pendant une frame considérée parole,
             * on ne remonte pas le noise floor.
             */
            return
        }

        /*
         * Si la frame est plus faible que notre noise floor,
         * on descend assez rapidement.
         */
        val noiseAlpha =
            if (
                frameRmsDbfs <
                estimatedNoiseDbfs
            ) {
                NOISE_ESTIMATOR_FAST_ALPHA
            } else {
                NOISE_ESTIMATOR_SLOW_ALPHA
            }

        estimatedNoiseDbfs =
            interpolate(
                previous =
                    estimatedNoiseDbfs,

                current =
                    frameRmsDbfs,

                alpha =
                    noiseAlpha,
            )
    }

    /**
     * Calcule le gain réellement souhaitable
     * avant protection contre les pics.
     */
    private fun calculateBaseDesiredGainDb():
        Float {

        if (!hasSpeechEstimate) {
            return 0.0f
        }

        val targetSpeechDbfs =
            ASR_TARGET_SPEECH_DBFS

            /*
 * Niveau de bruit maximal autorisé en sortie ASR.
 *
 * Ce garde empêche C10 d'amplifier une voix faible
 * au prix d'une remontée excessive du bruit vers Nemotron.
 */
val maximumOutputNoiseDbfs =
    MAX_ASR_OUTPUT_NOISE_DBFS

        val maximumGainDb =
            MAX_ASR_GAIN_DB

        /*
 * Gain nécessaire pour rapprocher la parole
 * de la cible ASR.
 */
        val speechGainDb =
            targetSpeechDbfs -
                estimatedSpeechDbfs

        /*
 * Limite de gain imposée par le bruit.
 *
 * Exemple :
 *
 * bruit = -60 dBFS
 * bruit maximum voulu = -52 dBFS
 *
 * gain autorisé = +8 dB
 */
        val noiseAllowedGainDb =
            if (hasNoiseEstimate) {

                maximumOutputNoiseDbfs -
                     estimatedNoiseDbfs

            } else {
                maximumGainDb
            }

        /*
 * C10 :
 *
 * Le gain final est le plus prudent entre :
 * - le gain demandé par la parole ;
 * - le gain encore autorisé par le bruit ;
 * - le plafond absolu ASR.
 *
 * Le Normalizer n'atténue jamais le PCM amont.
 */
        return minOf(
            speechGainDb,
            noiseAllowedGainDb,
            maximumGainDb,
        )
            .coerceIn(
                MIN_NORMALIZER_GAIN_DB,
                maximumGainDb,
            )
    }

    
    /**
     * Contrôle la vitesse de variation du gain.
     */
    private fun smoothGainDb(
        currentDb: Float,
        targetDb: Float,
        frameDurationSeconds: Float,
    ): Float {

        if (
            frameDurationSeconds <=
            0.0f
        ) {
            return targetDb
        }

        return if (
            targetDb >
            currentDb
        ) {

            val maximumIncrease =
                GAIN_RISE_DB_PER_SECOND *
                    frameDurationSeconds

            minOf(
                targetDb,
                currentDb +
                    maximumIncrease,
            )

        } else {

            val maximumDecrease =
                GAIN_FALL_DB_PER_SECOND *
                    frameDurationSeconds

            maxOf(
                targetDb,
                currentDb -
                    maximumDecrease,
            )
        }
    }

    fun getDiagnostics():
        Diagnostics {

        return Diagnostics(
            active =
                active,

            
            currentGainDb =
                currentGainDb,

            estimatedSpeechDbfs =
                estimatedSpeechDbfs
                    .takeIf {
                        hasSpeechEstimate
                    },

            estimatedNoiseDbfs =
                estimatedNoiseDbfs
                    .takeIf {
                        hasNoiseEstimate
                    },

            lastInputRmsDbfs =
                lastInputRmsDbfs,

            lastOutputRmsDbfs =
                lastOutputRmsDbfs,

            lastInputPeakDbfs =
                lastInputPeakDbfs,

            lastOutputPeakDbfs =
                lastOutputPeakDbfs,

            totalProcessedFrames =
                totalProcessedFrames,

            totalLimitedSamples =
                totalLimitedSamples,
        )
    }

    fun reset() {

        active =
            false

        
        currentGainDb =
            0.0f

        estimatedSpeechDbfs =
            DEFAULT_SPEECH_LEVEL_DBFS

        estimatedNoiseDbfs =
            DEFAULT_NOISE_LEVEL_DBFS

        hasSpeechEstimate =
            false

        hasNoiseEstimate =
            false

        lastInputRmsDbfs =
            null

        lastOutputRmsDbfs =
            null

        lastInputPeakDbfs =
            null

        lastOutputPeakDbfs =
            null

        totalProcessedFrames =
            0L

        totalLimitedSamples =
            0L
    }

    private fun calculateRms(
        samples: FloatArray,
    ): Float {

        if (samples.isEmpty()) {
            return 0.0f
        }

        var squaredSum =
            0.0

        for (sample in samples) {

            val safeSample =
                if (
                    sample.isFinite()
                ) {
                    sample.toDouble()
                } else {
                    0.0
                }

            squaredSum +=
                safeSample *
                    safeSample
        }

        return sqrt(
            squaredSum /
                samples.size.toDouble()
        )
            .toFloat()
    }

    private fun calculatePeak(
        samples: FloatArray,
    ): Float {

        var peak =
            0.0f

        for (sample in samples) {

            if (!sample.isFinite()) {
                continue
            }

            peak =
                maxOf(
                    peak,
                    abs(sample)
                )
        }

        return peak
    }

    private fun linearToDbfs(
        value: Float,
    ): Float {

        val safeValue =
            maxOf(
                value,
                MIN_MEASURABLE_LINEAR
            )

        return (
            20.0 *
                log10(
                    safeValue.toDouble()
                )
            )
            .toFloat()
    }

    private fun dbToLinear(
        db: Float,
    ): Float {

        return 10.0
            .pow(
                db.toDouble() /
                    20.0
            )
            .toFloat()
    }

    private fun interpolate(
        previous: Float,
        current: Float,
        alpha: Float,
    ): Float {

        return previous +
            (
                current -
                    previous
            ) *
            alpha
    }

    private fun percentile(
        values: List<Float>,
        percentile: Float,
    ): Float {

        require(
            values.isNotEmpty()
        )

        val sorted =
            values.sorted()

        if (sorted.size == 1) {
            return sorted[0]
        }

        val clampedPercentile =
            percentile.coerceIn(
                0.0f,
                1.0f
            )

        val position =
            clampedPercentile *
                (
                    sorted.size -
                        1
                    )

        val lowerIndex =
            position.toInt()

        val upperIndex =
            minOf(
                lowerIndex + 1,
                sorted.lastIndex
            )

        if (
            lowerIndex ==
            upperIndex
        ) {
            return sorted[
                lowerIndex
            ]
        }

        val fraction =
            position -
                lowerIndex.toFloat()

        return sorted[
            lowerIndex
        ] +
            (
                sorted[
                    upperIndex
                ] -
                    sorted[
                        lowerIndex
                    ]
                ) *
                fraction
    }

    companion object {

       
        /*
 * C10 - cible de niveau parole pour Nemotron.
 *
 * Cette valeur ne représente pas un gain fixe.
 * C10 calcule dynamiquement le boost nécessaire
 * pour rapprocher la parole estimée de cette cible.
 */
        private const val ASR_TARGET_SPEECH_DBFS =
            -48.0f

        /*
 * Plafond absolu du boost ASR.
 *
 * Il ne constitue jamais une cible.
 * Les gardes parole, bruit et peak déterminent
 * le gain réellement appliqué.
 */
        private const val MAX_ASR_GAIN_DB =
            24.0f

        /*
         * Règle structurante C10 :
         * le Normalizer ne peut jamais réduire le niveau amont.
         */
        private const val MIN_NORMALIZER_GAIN_DB =
            0.0f

/*
 * Niveau maximal de bruit autorisé après boost ASR.
 *
 * Exemple mesuré :
 * noise ~= -66 dBFS
 * plafond = -52 dBFS
 * -> boost bruit maximal ~= +14 dB.
 */
private const val MAX_ASR_OUTPUT_NOISE_DBFS =
    -52.0f

        /*
         * Headroom réservé au boost additionnel.
         *
         * Le calcul ne peut plus produire de gain négatif.
         */
        private const val TARGET_PEAK_DBFS =
            -1.0f

        /*
         * Hard safety numérique seulement.
         * Aligné avec l'OUTPUT_LIMIT de GuardianAudioEngine.
         */
        private const val ABSOLUTE_LIMITER_LINEAR =
            0.98f

        /*
         * Variation du boost ASR.
         *
         * Montée progressive ; retrait plus rapide.
         */
        private const val GAIN_RISE_DB_PER_SECOND =
            6.0f

        private const val GAIN_FALL_DB_PER_SECOND =
            18.0f

        /*
         * Une frame doit dépasser suffisamment le bruit
         * pour influencer l'estimation de parole.
         */
        private const val MIN_SPEECH_TO_NOISE_DB =
            6.0f

        /*
         * Évite qu'un bruit microscopique soit considéré
         * comme une vraie parole.
         */
        private const val MIN_SPEECH_LEVEL_DBFS =
            -65.0f

        /*
         * Pré-roll :
         *
         * 20 % → estimation bruit
         * 85 % → estimation parole robuste
         */
        private const val PRE_ROLL_NOISE_PERCENTILE =
            0.20f

        private const val PRE_ROLL_SPEECH_PERCENTILE =
            0.85f

        /*
         * Lissage des estimateurs.
         */
        private const val SPEECH_ESTIMATOR_ALPHA =
            0.20f

        private const val NOISE_ESTIMATOR_FAST_ALPHA =
            0.20f

        private const val NOISE_ESTIMATOR_SLOW_ALPHA =
            0.02f

        private const val DEFAULT_SPEECH_LEVEL_DBFS =
            -48.0f

        private const val DEFAULT_NOISE_LEVEL_DBFS =
            -70.0f

        private const val MIN_MEASURABLE_LINEAR =
            0.000001f
    }
}

 /*
 * ============================================================================
 * Guardian ASR Admission Controller V2
 * Temporal Event Fusion
 * ============================================================================
 */


 internal enum class GuardianAsrAdmissionDecision {
    WAIT,

    /*
     * V3-D.2 :
     *
     * Le Scout peut autoriser deux types de PROBE internes :
     *
     * - PROBE_WEAK   : signal faible -> Normalizer actif ;
     * - PROBE_DIRECT : signal déjà robuste -> chemin ASR direct.
     *
     * Aucun des deux ne valide la parole : Sherpa doit encore COMMIT.
     */
    PROBE_WEAK,
    PROBE_DIRECT,

    OPEN_NEAR,
    OPEN_DISTANT,
}

internal enum class GuardianAsrAdmissionState {
    IDLE,
    CANDIDATE,
    LATCHED,
}

internal data class GuardianAsrAdmissionResult(
    val decision: GuardianAsrAdmissionDecision,
    val reason: String,
    val state: GuardianAsrAdmissionState,

    val preRollSpeechDbfs: Float?,
    val preRollNoiseDbfs: Float?,
    val preRollPeakDbfs: Float?,
    val speechToNoiseDb: Float?,

    val acousticRms: Float,
    val acousticNoiseFloorRms: Float,
    val snrDb: Float,

    val farVoiceEventId: Long,
    val farVoiceSource: GuardianAudioEngine.FarVoiceSource,
    val farVoicePhase: GuardianAudioEngine.FarVoicePhase,

    val neuralFusionScore: Float?,

    val acousticEvidenceAgeMs: Long?,
    val neuralEvidenceAgeMs: Long?,
    val timeSinceLastFinalMs: Long?,

    /*
     * Guardian Speech Capture V3-C - Neural Speech Scout.
     *
     * Le Scout n'ouvre jamais Sherpa directement.
     * Il expose uniquement une recommandation destinée
     * au futur chemin ASR PROBE -> COMMIT.
     */
    val speechScoutScore: Float,
    val speechScoutEvidenceCount: Int,
    val speechScoutPeakProbability: Float?,
    val speechScoutRecommended: Boolean,
)

internal enum class GuardianNeuralVadProfile {
    FUSION,
}

internal data class GuardianNeuralFusionThresholds(
    val directEvidenceThreshold: Float,
    val ultraWeakEvidenceThreshold: Float,
    val speechScoutMinEvidenceProbability: Float,
    val speechScoutMediumEvidenceProbability: Float,
    val speechScoutStrongEvidenceProbability: Float,
    val speechScoutVeryStrongEvidenceProbability: Float,
) {
    companion object {
        /*
         * Score commun du Neural Fusion Ensemble.
         *
         * Ce score n'est PAS la sortie d'un moteur unique :
         * il est construit en amont à partir de :
         * - Silero phase A ;
         * - Silero phase B ;
         * - TEN ;
         * puis combiné à Far Voice et à la temporalité ici.
         *
         * Les seuils ci-dessous ne "décident" donc jamais seuls.
         * Ils servent uniquement à qualifier le niveau de preuve
         * nécessaire pour autoriser soit une fusion forte, soit un
         * ASR PROBE qui sera encore validé lexicalement par Sherpa.
         */
        val PRODUCT =
            GuardianNeuralFusionThresholds(
                directEvidenceThreshold = 0.30f,
                ultraWeakEvidenceThreshold = 0.30f,
                speechScoutMinEvidenceProbability = 0.18f,
                speechScoutMediumEvidenceProbability = 0.28f,
                speechScoutStrongEvidenceProbability = 0.45f,
                speechScoutVeryStrongEvidenceProbability = 0.65f,
            )
    }
}

internal class GuardianAsrAdmissionController(
    initialNeuralVadProfile: GuardianNeuralVadProfile =
        GuardianNeuralVadProfile.FUSION,
) {

    private var neuralVadProfile =
        initialNeuralVadProfile

    private val neuralFusionThresholds =
        GuardianNeuralFusionThresholds.PRODUCT

    /*
     * Compatibilité avec l'ancien switch terrain.
     * L'admission produit reste désormais toujours en mode FUSION.
     */
    fun setNeuralVadProfile(
        profile: GuardianNeuralVadProfile,
    ) {
        if (profile == neuralVadProfile) {
            return
        }

        neuralVadProfile =
            GuardianNeuralVadProfile.FUSION

        reset()
    }

    private var state =
        GuardianAsrAdmissionState.IDLE

    /*
     * Event Far Voice actuellement candidat.
     */
    private var candidateFarVoiceEventId =
        0L

    /*
     * Dernière preuve acoustique DIRECTE.
     *
     * HOLD et RELEASING ne mettent jamais
     * cette valeur à jour.
     */
    private var lastDirectFarVoiceEvidenceAtMs =
        0L

    private var lastDirectFarVoiceEvidenceEventId =
        0L

    /*
     * Dernière preuve de l'ensemble neuronal fusionné.
     *
     * neuralFusionScore et temporalSpeech
     * appartiennent volontairement à UNE seule famille.
     */
    private var lastNeuralEvidenceAtMs =
        0L

        /*
 * eventId Far Voice auquel appartient
 * la dernière preuve Neural Fusion.
 *
 * Une preuve neuronale provenant d'un ancien bruit
 * ou d'un ancien événement ne peut ainsi jamais
 * valider un nouvel événement Far Voice.
 */
private var lastNeuralEvidenceEventId =
    0L

    /*
     * eventId > 0 :
     * cet événement Far Voice a déjà ouvert Sherpa.
     *
     * eventId == 0 :
     * le latch correspond à une ouverture Temporal-VAD-only.
     */
    private var latchedFarVoiceEventId =
        0L

    private var latchedNeutralFrames =
        0

    /*
     * Une seule seconde chance par événement Far Voice après un PROBE échoué.
     * Aucun nouveau PROBE ne peut être ouvert sans une NOUVELLE frame
     * acoustique DIRECTE et une preuve neurale suffisante.
     */
    private var sameEventProbeRetryUsedForEventId = 0L

    // Le retry n'utilise jamais une preuve neurale enregistrée pendant HOLD.
    // Il attend une nouvelle paire acoustique DIRECTE + neurale sur la même frame.
    private var retryWaitingForFreshPairedEvidenceEventId = 0L

    /*
     * Diagnostic uniquement.
     *
     * Le FINAL ne contrôle plus le réarmement.
     */
    private var lastFinalizedAtMs =
        0L

    /*
     * ========================================================================
     * Guardian Speech Capture V3-C - Neural Speech Scout
     * ========================================================================
     *
     * Objectif :
     *
     * observer une PERSISTANCE neurale faible/modérée sur une fenêtre courte,
     * sans dépendre de Far Voice et sans ouvrir Sherpa.
     *
     * Une probabilité Neural Fusion isolée ne suffit jamais.
     * Le Scout exige plusieurs preuves temporellement cohérentes.
     */
    private data class SpeechScoutEvidence(
        val capturedAtMs: Long,
        val neuralFusionScore: Float,
    )

    private data class SpeechScoutSnapshot(
        val score: Float,
        val evidenceCount: Int,
        val mediumEvidenceCount: Int,
        val strongEvidenceCount: Int,
        val durationMs: Long,
        val maximumGapMs: Long,
        val peakProbability: Float?,
        val tracking: Boolean,

        /*
         * Route historique : au moins une preuve forte >= 0.35.
         */
        val strongRecommended: Boolean,

        /*
         * C12 - nouvelle route de récupération.
         *
         * Autorise uniquement un PROBE lorsque plusieurs preuves
         * faibles/modérées sont persistantes et temporellement cohérentes.
         * Elle ne valide JAMAIS la parole : le Validation Gate Sherpa
         * reste obligatoire après l'admission.
         */
        val sustainedWeakRecommended: Boolean,

        val recommended: Boolean,
    )

    private val speechScoutEvidence =
        ArrayDeque<SpeechScoutEvidence>()

    /*
     * C12 - diagnostic compact du Scout.
     *
     * On journalise les transitions et un état périodique pendant
     * TRACKING, sans écrire une ligne à chaque frame audio.
     */
    private var lastSpeechScoutDiagnosticLogAtMs =
        0L

    /*
 * C13 - mémoire temporelle courte de la dernière
 * recommandation Speech Scout.
 *
 * Objectif :
 *
 * permettre à une recommandation Scout obtenue quelques
 * dizaines / centaines de millisecondes AVANT Far Voice
 * de confirmer le même événement vocal.
 *
 * Cette mémoire :
 * - ne baisse aucun seuil ;
 * - n'ouvre jamais Sherpa seule ;
 * - n'est utilisée que avec une preuve Far Voice récente ;
 * - expire avec TEMPORAL_FUSION_WINDOW_MS.
 */
private var lastSpeechScoutRecommendedAtMs =
    0L

private var lastSpeechScoutRecommendationWasSustainedWeak =
    false

    private var lastSpeechScoutDiagnosticMode =
        "IDLE"

    /*
     * Les noms temporalSpeech/neuralFusionScore sont conservés pour
     * compatibilité interne, mais ils représentent désormais le moteur
     * fusionné par Guardian (Silero A/B + TEN).
     */
    fun evaluate(
        temporalSpeech: Boolean,
        neuralFusionScore: Float?,

        acousticRms: Float,
        acousticNoiseFloorRms: Float,
        snrDb: Float,

        farVoiceEventId: Long,
        farVoiceSource: GuardianAudioEngine.FarVoiceSource,
        farVoicePhase: GuardianAudioEngine.FarVoicePhase,

        preRollFrames: Collection<FloatArray>,
        nowMs: Long,
    ): GuardianAsrAdmissionResult {

        val safeNowMs =
            nowMs.coerceAtLeast(0L)

        val safeAcousticRms =
            acousticRms
                .takeIf { it.isFinite() }
                ?.coerceIn(
                    0.0f,
                    1.0f,
                )
                ?: 0.0f

        val safeNoiseFloorRms =
            acousticNoiseFloorRms
                .takeIf { it.isFinite() }
                ?.coerceIn(
                    0.0f,
                    1.0f,
                )
                ?: 0.0f

        val safeSnrDb =
            snrDb
                .takeIf {
                    it.isFinite()
                }
                ?: 0.0f

        val safeNeuralFusionScore =
            neuralFusionScore
                ?.takeIf {
                    it.isFinite()
                }
                ?.coerceIn(
                    0.0f,
                    1.0f,
                )

        val safeFarVoiceEventId =
            farVoiceEventId
                .coerceAtLeast(
                    0L
                )

        /*
         * Un événement Far Voice existe réellement.
         */
        val farVoiceAlive =
            safeFarVoiceEventId > 0L &&
                farVoicePhase !=
                    GuardianAudioEngine
                        .FarVoicePhase
                        .NONE

        /*
         * Preuve acoustique DIRECTE.
         *
         * ACQUIRED :
         * nouvelle acquisition.
         *
         * SUPPORTED :
         * la frame apporte encore une preuve réelle.
         *
         * HOLD / RELEASING :
         * mémoire temporelle uniquement.
         */
        val directFarVoiceEvidence =
            farVoiceAlive &&
                (
                    farVoicePhase ==
                        GuardianAudioEngine
                            .FarVoicePhase
                            .ACQUIRED ||
                    farVoicePhase ==
                        GuardianAudioEngine
                            .FarVoicePhase
                            .SUPPORTED
                )

                /*
 * V2.3 - Neural confidence by acoustic source.
 *
 * STRICT possède déjà une preuve acoustique forte :
 * Neural Fusion 0.20 reste suffisant pour la fusion.
 *
 * ULTRA_WEAK est beaucoup plus permissif acoustiquement.
 * Les mesures terrain ont montré un faux événement
 * à neuralFusion=0.228.
 *
 * UltraWeak exige donc une preuve neurale plus forte.
 */
val requiredNeuralFusionThreshold =
    when (farVoiceSource) {

        GuardianAudioEngine
            .FarVoiceSource
            .ULTRA_WEAK_V2_1 ->

            neuralFusionThresholds.ultraWeakEvidenceThreshold

        else ->

            neuralFusionThresholds.directEvidenceThreshold
    }

        /*
         * Score de fusion neuronale commun.
         *
         * Le score de fusion et le VAD temporel ne sont jamais
         * traités comme deux moteurs indépendants : le premier représente
         * l'ensemble A/B/TEN, le second apporte uniquement la continuité.
         */
        val neuralEvidence =
    temporalSpeech ||
        (
            safeNeuralFusionScore != null &&
                safeNeuralFusionScore >=
                    requiredNeuralFusionThreshold
        )

        /*
         * Mémorisation temporelle des preuves.
         */
        if (directFarVoiceEvidence) {

            lastDirectFarVoiceEvidenceAtMs =
                safeNowMs

            lastDirectFarVoiceEvidenceEventId =
                safeFarVoiceEventId
        }

        /*
 * Une preuve neurale n'est mémorisée pour
 * Temporal Fusion que lorsqu'un événement
 * Far Voice existe réellement.
 *
 * Cela interdit :
 *
 * bruit neuronal
 * → quelques centaines de ms
 * → nouveau Far Voice
 * → fausse fusion.
 */
if (
    neuralEvidence &&
    farVoiceAlive
) {

    lastNeuralEvidenceAtMs =
        safeNowMs

    lastNeuralEvidenceEventId =
        safeFarVoiceEventId
}

        /*
         * ================================================================
         * LATCH
         * ================================================================
         *
         * Un même événement acoustique ne peut ouvrir
         * Sherpa qu'une seule fois.
         */
        if (
            state ==
                GuardianAsrAdmissionState.LATCHED
        ) {

            /*
             * Utterance issue d'un vrai eventId Far Voice.
             */
            if (
    latchedFarVoiceEventId > 0L
) {

    val sameLatchedFarVoiceEvent =
        farVoiceAlive &&
            safeFarVoiceEventId ==
                latchedFarVoiceEventId

    /*
     * Le même eventId est encore vivant :
     * aucune deuxième ouverture possible.
     */
    if (sameLatchedFarVoiceEvent) {

        /*
         * Pendant RELEASING, on peut déjà compter
         * les frames devenues réellement neutres.
         *
         * Ainsi on n'ajoute pas artificiellement
         * 150 ms après la libération de l'eventId.
         */
        val neutralReleasingFrame =
            farVoicePhase ==
                GuardianAudioEngine
                    .FarVoicePhase
                    .RELEASING &&
                !temporalSpeech &&
                !neuralEvidence

        latchedNeutralFrames =
            if (neutralReleasingFrame) {

                (
                    latchedNeutralFrames + 1
                ).coerceAtMost(
                    NEAR_LATCH_RELEASE_FRAMES
                )

            } else {

                0
            }

        return buildResult(
            decision =
                GuardianAsrAdmissionDecision.WAIT,

            reason =
                "FAR_VOICE_EVENT_ALREADY_CONSUMED",

            metrics =
                PreRollMetrics.EMPTY,

            acousticRms =
                safeAcousticRms,

            acousticNoiseFloorRms =
                safeNoiseFloorRms,

            snrDb =
                safeSnrDb,

            farVoiceEventId =
                safeFarVoiceEventId,

            farVoiceSource =
                farVoiceSource,

            farVoicePhase =
                farVoicePhase,

            neuralFusionScore =
                safeNeuralFusionScore,

            nowMs =
                safeNowMs,
        )
    }

    /*
     * Un nouvel eventId est un véritable
     * nouvel événement acoustique.
     *
     * On peut donc l'évaluer immédiatement.
     */
    val newFarVoiceEvent =
        farVoiceAlive &&
            safeFarVoiceEventId > 0L &&
            safeFarVoiceEventId !=
                latchedFarVoiceEventId

    if (newFarVoiceEvent) {

        releaseLatch()

    } else {

        /*
         * L'ancien Far Voice est terminé.
         *
         * On exige aussi que le VAD temporel soit revenu
         * à un état réellement neutre avant
         * d'autoriser une ouverture Temporal-VAD-only.
         */
        val neutralFrame =
            !temporalSpeech &&
                !neuralEvidence &&
                !farVoiceAlive

        latchedNeutralFrames =
            if (neutralFrame) {

                (
                    latchedNeutralFrames + 1
                ).coerceAtMost(
                    NEAR_LATCH_RELEASE_FRAMES
                )

            } else {

                0
            }

        if (
            latchedNeutralFrames >=
                NEAR_LATCH_RELEASE_FRAMES
        ) {

            releaseLatch()

        } else {

            return buildResult(
                decision =
                    GuardianAsrAdmissionDecision.WAIT,

                reason =
                    "FAR_VOICE_RELEASE_WAITING_FOR_NEUTRAL",

                metrics =
                    PreRollMetrics.EMPTY,

                acousticRms =
                    safeAcousticRms,

                acousticNoiseFloorRms =
                    safeNoiseFloorRms,

                snrDb =
                    safeSnrDb,

                farVoiceEventId =
                    safeFarVoiceEventId,

                farVoiceSource =
                    farVoiceSource,

                farVoicePhase =
                    farVoicePhase,

                neuralFusionScore =
                    safeNeuralFusionScore,

                nowMs =
                    safeNowMs,
            )
        }
    }

} else {

                /*
                 * Ouverture précédente Temporal-VAD-only.
                 *
                 * On évite qu'un reset du VAD produise
                 * immédiatement une deuxième utterance.
                 */
                val neutralFrame =
                    !temporalSpeech &&
                        !neuralEvidence &&
                        !farVoiceAlive

                latchedNeutralFrames =
                    if (neutralFrame) {

                        (
                            latchedNeutralFrames +
                                1
                            )
                            .coerceAtMost(
                                NEAR_LATCH_RELEASE_FRAMES
                            )

                    } else {

                        0
                    }

                /*
                 * Un nouvel eventId Guardian est indépendant
                 * de l'ancien événement temporel :
                 * il peut immédiatement devenir candidat.
                 */
                if (
                    farVoiceAlive ||
                    latchedNeutralFrames >=
                        NEAR_LATCH_RELEASE_FRAMES
                ) {

                    releaseLatch()

                } else {

                    return buildResult(
                        decision =
                            GuardianAsrAdmissionDecision.WAIT,

                        reason =
                            "NEAR_EVENT_LATCHED",

                        metrics =
                            PreRollMetrics.EMPTY,

                        acousticRms =
                            safeAcousticRms,

                        acousticNoiseFloorRms =
                            safeNoiseFloorRms,

                        snrDb =
                            safeSnrDb,

                        farVoiceEventId =
                            safeFarVoiceEventId,

                        farVoiceSource =
                            farVoiceSource,

                        farVoicePhase =
                            farVoicePhase,

                        neuralFusionScore =
                            safeNeuralFusionScore,

                        nowMs =
                            safeNowMs,
                    )
                }
            }
        }

        /*
         * Seconde chance : interdire un réarmement fondé sur une preuve neurale
         * datant du HOLD de l'ancienne tentative. On exige une nouvelle frame
         * ayant à la fois support acoustique DIRECT et support neuronal.
         * Le score et les seuils restent exactement ceux du profil courant.
         */
        if (
            retryWaitingForFreshPairedEvidenceEventId > 0L &&
            retryWaitingForFreshPairedEvidenceEventId == safeFarVoiceEventId
        ) {
            if (!directFarVoiceEvidence || !neuralEvidence) {
                return buildResult(
                    decision = GuardianAsrAdmissionDecision.WAIT,
                    reason = "RETRY_WAITING_FOR_FRESH_PAIRED_EVIDENCE",
                    metrics = PreRollMetrics.EMPTY,
                    acousticRms = safeAcousticRms,
                    acousticNoiseFloorRms = safeNoiseFloorRms,
                    snrDb = safeSnrDb,
                    farVoiceEventId = safeFarVoiceEventId,
                    farVoiceSource = farVoiceSource,
                    farVoicePhase = farVoicePhase,
                    neuralFusionScore = safeNeuralFusionScore,
                    nowMs = safeNowMs,
                )
            }
            retryWaitingForFreshPairedEvidenceEventId = 0L
        } else if (
            retryWaitingForFreshPairedEvidenceEventId > 0L &&
            safeFarVoiceEventId != retryWaitingForFreshPairedEvidenceEventId
        ) {
            retryWaitingForFreshPairedEvidenceEventId = 0L
        }

        /*
         * ====================================================================
         * V3-C - NEURAL SPEECH SCOUT
         * ====================================================================
         *
         * Le Scout est indépendant de Far Voice.
         *
         * Il observe la probabilité Neural Fusion sur une fenêtre glissante
         * et recherche une accumulation cohérente de preuves faibles/modérées.
         *
         * IMPORTANT :
         * - il ne change PAS state ;
         * - il ne crée PAS de latch ;
         * - il ne retourne jamais OPEN_* à lui seul ;
         * - il prépare uniquement le PROBE Sherpa V3-D.
         */
        val speechScout =
            updateSpeechScout(
                neuralFusionScore =
                    safeNeuralFusionScore,

                nowMs =
                    safeNowMs,
            )

            /*
 * C13 - mémorisation de la dernière vraie recommandation Scout.
 *
 * On mémorise uniquement un état qui a déjà satisfait
 * tous les critères actuels du Scout.
 */
if (speechScout.recommended) {

    lastSpeechScoutRecommendedAtMs =
        safeNowMs

    lastSpeechScoutRecommendationWasSustainedWeak =
        speechScout.sustainedWeakRecommended
}

        /*
         * ================================================================
         * CANDIDATE FAR VOICE
         * ================================================================
         */
        if (directFarVoiceEvidence) {

            if (
                state !=
                    GuardianAsrAdmissionState.CANDIDATE ||
                candidateFarVoiceEventId !=
                    safeFarVoiceEventId
            ) {

                beginCandidate(
                    eventId =
                        safeFarVoiceEventId,
                )
            }

        } else if (
            state ==
                GuardianAsrAdmissionState.CANDIDATE &&
            (
                !farVoiceAlive ||
                    candidateFarVoiceEventId !=
                        safeFarVoiceEventId
                )
        ) {

            clearCandidate()
        }

        /*
         * Le tri des 40 frames de pré-roll n'est pas gratuit.
         *
         * En V2 on ne le fait donc PAS sur toutes les
         * frames silencieuses.
         */
        val needsPreRollAnalysis =
            temporalSpeech ||
                farVoiceAlive ||
                speechScout.tracking ||
                state ==
                    GuardianAsrAdmissionState.CANDIDATE

        val metrics =
            if (needsPreRollAnalysis) {

                analyzePreRoll(
                    frames =
                        preRollFrames,
                )

            } else {

                PreRollMetrics.EMPTY
            }

        /*
         * Âge de la dernière preuve acoustique
         * appartenant AU MÊME eventId.
         */
        val acousticEvidenceAgeMs =
            calculateEvidenceAge(
                nowMs =
                    safeNowMs,

                evidenceAtMs =
                    if (
                        lastDirectFarVoiceEvidenceEventId ==
                        safeFarVoiceEventId
                    ) {

                        lastDirectFarVoiceEvidenceAtMs

                    } else {

                        0L
                    },
            )

      val neuralEvidenceAgeMs =
    calculateEvidenceAge(
        nowMs =
            safeNowMs,

        evidenceAtMs =
            if (
                lastNeuralEvidenceEventId ==
                    safeFarVoiceEventId &&
                safeFarVoiceEventId > 0L
            ) {

                lastNeuralEvidenceAtMs

            } else {

                0L
            },
    )

        val acousticEvidenceRecent =
            farVoiceAlive &&
                lastDirectFarVoiceEvidenceEventId ==
                    safeFarVoiceEventId &&
                acousticEvidenceAgeMs != null &&
                acousticEvidenceAgeMs <=
                    TEMPORAL_FUSION_WINDOW_MS

        val neuralEvidenceRecent =
            neuralEvidenceAgeMs != null &&
                neuralEvidenceAgeMs <=
                    TEMPORAL_FUSION_WINDOW_MS

            /*
 * C13 - Temporal Scout Fusion.
 *
 * Le Scout peut avoir atteint recommended=true légèrement
 * avant l'apparition de l'eventId Far Voice.
 *
 * On conserve cette recommandation uniquement pendant
 * la même fenêtre temporelle que la fusion acoustique/neuronale.
 */
val speechScoutRecommendationAgeMs =
    calculateEvidenceAge(
        nowMs =
            safeNowMs,

        evidenceAtMs =
            lastSpeechScoutRecommendedAtMs,
    )

val speechScoutRecommendationRecent =
    speechScoutRecommendationAgeMs != null &&
        speechScoutRecommendationAgeMs <=
            TEMPORAL_FUSION_WINDOW_MS

val speechScoutRecommendedForFarVoice =
    speechScout.recommended ||
        speechScoutRecommendationRecent

val speechScoutSustainedWeakForFarVoice =
    if (speechScout.recommended) {

        speechScout.sustainedWeakRecommended

    } else {

        speechScoutRecommendationRecent &&
            lastSpeechScoutRecommendationWasSustainedWeak
    }

val usingRecentSpeechScoutRecommendation =
    !speechScout.recommended &&
        speechScoutRecommendationRecent

        /*
         * ================================================================
         * TEMPORAL EVENT FUSION
         * ================================================================
         *
         * C'est le cœur du V2.
         *
         * Guardian Far Voice :
         *
         *       t = 0 ms
         *
         * VAD temporel :
         *
         *       t = +200 / +400 ms
         *
         * peuvent maintenant confirmer le MÊME
         * événement vocal.
         */
        if (
            farVoiceAlive &&
            acousticEvidenceRecent &&
            neuralEvidenceRecent
        ) {

            return openAndLatch(
                decision =
                    GuardianAsrAdmissionDecision.OPEN_DISTANT,

                reason =
                    if (temporalSpeech) {

                        "TEMPORAL_FUSION_TEMPORAL_VAD_CONFIRMED"

                    } else {

                        "TEMPORAL_FUSION_NEURAL_ENSEMBLE"
                    },

                metrics =
                    metrics,

                acousticRms =
                    safeAcousticRms,

                acousticNoiseFloorRms =
                    safeNoiseFloorRms,

                snrDb =
                    safeSnrDb,

                farVoiceEventId =
                    safeFarVoiceEventId,

                farVoiceSource =
                    farVoiceSource,

                farVoicePhase =
                    farVoicePhase,

                neuralFusionScore =
                    safeNeuralFusionScore,

                nowMs =
                    safeNowMs,
            )
        }

        /*
 * C12 / V3-D.2 - Far Voice + Speech Scout rescue.
 *
 * Temporal Fusion reste la voie prioritaire.
 *
 * Mais lorsqu'un événement Far Voice possède encore
 * une preuve acoustique récente et que le Speech Scout
 * a accumulé une preuve neurale persistante suffisante,
 * Guardian autorise uniquement un PROBE Sherpa.
 *
 * Ce chemin :
 * - ne valide jamais directement la parole ;
 * - ne modifie aucun seuil Far Voice / Neural Fusion / Scout ;
 * - conserve le Validation Gate lexical V3-D ;
 * - n'autorise pas HOLD/RELEASING seuls à ouvrir Sherpa.
 */
val farVoiceScoutProbeCandidate =
    isSpeechScoutProbeCandidate(
        metrics = metrics,
    )

val farVoiceScoutNeedsNormalization =
    farVoiceScoutProbeCandidate &&
        shouldNormalizeSpeechScoutProbe(
            metrics = metrics,
        )

if (
    farVoiceAlive &&
    acousticEvidenceRecent &&
    farVoiceScoutProbeCandidate &&
    speechScoutRecommendedForFarVoice
) {
    return openAndLatch(
        decision =
            if (farVoiceScoutNeedsNormalization) {
                GuardianAsrAdmissionDecision.PROBE_WEAK
            } else {
                GuardianAsrAdmissionDecision.PROBE_DIRECT
            },

        reason =
    when {

        usingRecentSpeechScoutRecommendation &&
            speechScoutSustainedWeakForFarVoice &&
            farVoiceScoutNeedsNormalization ->
            "FAR_VOICE_SCOUT_RECENT_SUSTAINED_WEAK_RECOMMENDS_PROBE"

        usingRecentSpeechScoutRecommendation &&
            speechScoutSustainedWeakForFarVoice ->
            "FAR_VOICE_SCOUT_RECENT_SUSTAINED_DIRECT_RECOMMENDS_PROBE"

        usingRecentSpeechScoutRecommendation &&
            farVoiceScoutNeedsNormalization ->
            "FAR_VOICE_SCOUT_RECENT_WEAK_RECOMMENDS_PROBE"

        usingRecentSpeechScoutRecommendation ->
            "FAR_VOICE_SCOUT_RECENT_DIRECT_RECOMMENDS_PROBE"

        speechScoutSustainedWeakForFarVoice &&
            farVoiceScoutNeedsNormalization ->
            "FAR_VOICE_SCOUT_SUSTAINED_WEAK_RECOMMENDS_PROBE"

        speechScoutSustainedWeakForFarVoice ->
            "FAR_VOICE_SCOUT_SUSTAINED_DIRECT_RECOMMENDS_PROBE"

        farVoiceScoutNeedsNormalization ->
            "FAR_VOICE_SCOUT_WEAK_RECOMMENDS_PROBE"

        else ->
            "FAR_VOICE_SCOUT_DIRECT_RECOMMENDS_PROBE"
    },

        metrics =
            metrics,

        acousticRms =
            safeAcousticRms,

        acousticNoiseFloorRms =
            safeNoiseFloorRms,

        snrDb =
            safeSnrDb,

        farVoiceEventId =
            safeFarVoiceEventId,

        farVoiceSource =
            farVoiceSource,

        farVoicePhase =
            farVoicePhase,

        neuralFusionScore =
            safeNeuralFusionScore,

        nowMs =
            safeNowMs,
    )
}

        /*
         * ================================================================
         * V3-E - FAR VOICE -> LEXICAL PROBE
         * ================================================================
         *
         * Une acquisition Far Voice DIRECTE est déjà une preuve acoustique
         * persistante. Pour les voix très faibles, imposer en plus une preuve
         * Neural Fusion forte AVANT que Nemotron puisse écouter recrée le
         * problème historique que Far Voice devait justement résoudre.
         *
         * Cette voie ne déclare JAMAIS qu'une parole est reconnue :
         * - elle ouvre uniquement un PROBE interne ;
         * - le Validation Gate V3-D doit encore obtenir une preuve lexicale ;
         * - aucun onSpeechStarted(), FINAL ou SOS n'est produit ici ;
         * - HOLD / RELEASING seuls ne peuvent jamais ouvrir cette voie ;
         * - le latch garantit une seule ouverture par eventId.
         *
         * On exige malgré tout un pré-roll acoustiquement exploitable afin
         * qu'un événement Far Voice isolé sur un bruit sans contraste ne
         * déclenche pas inutilement l'ASR. Les seuils Far Voice, Neural Fusion
         * et Speech Scout restent inchangés.
         */
        val farVoiceLexicalProbeCandidate =
            directFarVoiceEvidence &&
                isSpeechScoutProbeCandidate(
                    metrics = metrics,
                )

        if (farVoiceLexicalProbeCandidate) {
            val needsNormalization =
                shouldNormalizeSpeechScoutProbe(
                    metrics = metrics,
                )

            return openAndLatch(
                decision =
                    if (needsNormalization) {
                        GuardianAsrAdmissionDecision.PROBE_WEAK
                    } else {
                        GuardianAsrAdmissionDecision.PROBE_DIRECT
                    },

                reason =
                    when {
                        farVoiceSource ==
                            GuardianAudioEngine.FarVoiceSource.STRICT_V2 &&
                            needsNormalization ->
                            "FAR_VOICE_STRICT_ACOUSTIC_LEXICAL_PROBE"

                        farVoiceSource ==
                            GuardianAudioEngine.FarVoiceSource.STRICT_V2 ->
                            "FAR_VOICE_STRICT_ACOUSTIC_DIRECT_PROBE"

                        needsNormalization ->
                            "FAR_VOICE_ULTRA_WEAK_ACOUSTIC_LEXICAL_PROBE"

                        else ->
                            "FAR_VOICE_ULTRA_WEAK_ACOUSTIC_DIRECT_PROBE"
                    },

                metrics = metrics,
                acousticRms = safeAcousticRms,
                acousticNoiseFloorRms = safeNoiseFloorRms,
                snrDb = safeSnrDb,
                farVoiceEventId = safeFarVoiceEventId,
                farVoiceSource = farVoiceSource,
                farVoicePhase = farVoicePhase,
                neuralFusionScore = safeNeuralFusionScore,
                nowMs = safeNowMs,
            )
        }

        /*
         * Guardian a un événement vivant mais il manque
         * encore une deuxième famille de preuve.
         *
         * Très important :
         *
         * HOLD et RELEASING ne peuvent JAMAIS
         * créer seuls une nouvelle admission.
         */
        if (farVoiceAlive) {

            return buildResult(
                decision =
                    GuardianAsrAdmissionDecision.WAIT,

                reason =
                    when (farVoicePhase) {

                        GuardianAudioEngine
                            .FarVoicePhase
                            .HOLD ->

                            "FAR_VOICE_HOLD_WAITING_FOR_NEURAL_EVIDENCE"

                        GuardianAudioEngine
                            .FarVoicePhase
                            .RELEASING ->

                            "FAR_VOICE_RELEASING_WAITING_FOR_RELEASE"

                        else ->

                            "FAR_VOICE_CANDIDATE_WAITING_FOR_NEURAL_EVIDENCE"
                    },

                metrics =
                    metrics,

                acousticRms =
                    safeAcousticRms,

                acousticNoiseFloorRms =
                    safeNoiseFloorRms,

                snrDb =
                    safeSnrDb,

                farVoiceEventId =
                    safeFarVoiceEventId,

                farVoiceSource =
                    farVoiceSource,

                farVoicePhase =
                    farVoicePhase,

                neuralFusionScore =
                    safeNeuralFusionScore,

                nowMs =
                    safeNowMs,
            )
        }

        /*
         * ================================================================
         * TEMPORAL-VAD-ONLY
         * ================================================================
         *
         * V3-C :
         *
         * 1. une parole faible MAIS déjà exploitable selon les critères
         *    historiques conserve OPEN_DISTANT ;
         *
         * 2. une parole réellement proche/robuste conserve OPEN_NEAR ;
         *
         * 3. une parole très faible qui échoue uniquement au critère
         *    historique de SNR ne doit plus tomber automatiquement
         *    sur OPEN_NEAR.
         *
         * Dans ce troisième cas, le Neural Speech Scout peut recommander
         * le PROBE Sherpa V3-D. Le Scout autorise l'écoute, jamais le COMMIT.
         */
        if (temporalSpeech) {

            val weakButUsableSpeech =
                isWeakButUsableSpeech(
                    metrics =
                        metrics,
                )

            if (weakButUsableSpeech) {

                return openAndLatch(
                    decision =
                        GuardianAsrAdmissionDecision
                            .OPEN_DISTANT,

                    reason =
                        "TEMPORAL_VAD_WEAK_DISTANT_SPEECH",

                    metrics =
                        metrics,

                    acousticRms =
                        safeAcousticRms,

                    acousticNoiseFloorRms =
                        safeNoiseFloorRms,

                    snrDb =
                        safeSnrDb,

                    farVoiceEventId =
                        0L,

                    farVoiceSource =
                        GuardianAudioEngine
                            .FarVoiceSource
                            .NONE,

                    farVoicePhase =
                        GuardianAudioEngine
                            .FarVoicePhase
                            .NONE,

                    neuralFusionScore =
                        safeNeuralFusionScore,

                    nowMs =
                        safeNowMs,
                )
            }

            val scoutProbeCandidate =
                isSpeechScoutProbeCandidate(
                    metrics =
                        metrics,
                )

            val scoutProbeNeedsNormalization =
                scoutProbeCandidate &&
                    shouldNormalizeSpeechScoutProbe(
                        metrics =
                            metrics,
                    )

            if (
                scoutProbeCandidate &&
                speechScout.recommended
            ) {

                /*
                 * V3-D.2 :
                 *
                 * Le Scout décide seulement si Sherpa mérite d'écouter.
                 * Le niveau acoustique décide séparément si ce PROBE doit
                 * passer par le Normalizer.
                 */
                return openAndLatch(
                    decision =
                        if (scoutProbeNeedsNormalization) {
                            GuardianAsrAdmissionDecision
                                .PROBE_WEAK
                        } else {
                            GuardianAsrAdmissionDecision
                                .PROBE_DIRECT
                        },

                    reason =
                        when {
                            speechScout.sustainedWeakRecommended &&
                                scoutProbeNeedsNormalization ->
                                "SPEECH_SCOUT_SUSTAINED_WEAK_RECOMMENDS_PROBE"

                            speechScout.sustainedWeakRecommended ->
                                "SPEECH_SCOUT_SUSTAINED_DIRECT_RECOMMENDS_PROBE"

                            scoutProbeNeedsNormalization ->
                                "SPEECH_SCOUT_WEAK_RECOMMENDS_PROBE"

                            else ->
                                "SPEECH_SCOUT_DIRECT_RECOMMENDS_PROBE"
                        },

                    metrics =
                        metrics,

                    acousticRms =
                        safeAcousticRms,

                    acousticNoiseFloorRms =
                        safeNoiseFloorRms,

                    snrDb =
                        safeSnrDb,

                    farVoiceEventId =
                        0L,

                    farVoiceSource =
                        GuardianAudioEngine
                            .FarVoiceSource
                            .NONE,

                    farVoicePhase =
                        GuardianAudioEngine
                            .FarVoicePhase
                            .NONE,

                    neuralFusionScore =
                        safeNeuralFusionScore,

                    nowMs =
                        safeNowMs,
                )
            }

            /*
             * Si le signal est réellement faible et que le Scout est encore
             * en train d'accumuler ses preuves, on attend au lieu de le
             * classer prématurément comme NEAR.
             *
             * Une voix déjà robuste conserve OPEN_NEAR si le Scout n'a pas
             * encore atteint son niveau de recommandation.
             */
            if (
                scoutProbeNeedsNormalization &&
                speechScout.tracking
            ) {

                return buildResult(
                    decision =
                        GuardianAsrAdmissionDecision.WAIT,

                    reason =
                        "SPEECH_SCOUT_TRACKING",

                    metrics =
                        metrics,

                    acousticRms =
                        safeAcousticRms,

                    acousticNoiseFloorRms =
                        safeNoiseFloorRms,

                    snrDb =
                        safeSnrDb,

                    farVoiceEventId =
                        0L,

                    farVoiceSource =
                        GuardianAudioEngine
                            .FarVoiceSource
                            .NONE,

                    farVoicePhase =
                        GuardianAudioEngine
                            .FarVoicePhase
                            .NONE,

                    neuralFusionScore =
                        safeNeuralFusionScore,

                    nowMs =
                        safeNowMs,
                )
            }

            return openAndLatch(
                decision =
                    GuardianAsrAdmissionDecision
                        .OPEN_NEAR,

                reason =
                    "TEMPORAL_VAD_NORMAL_SPEECH",

                metrics =
                    metrics,

                acousticRms =
                    safeAcousticRms,

                acousticNoiseFloorRms =
                    safeNoiseFloorRms,

                snrDb =
                    safeSnrDb,

                farVoiceEventId =
                    0L,

                farVoiceSource =
                    GuardianAudioEngine
                        .FarVoiceSource
                        .NONE,

                farVoicePhase =
                    GuardianAudioEngine
                        .FarVoicePhase
                        .NONE,

                neuralFusionScore =
                    safeNeuralFusionScore,

                nowMs =
                    safeNowMs,
            )
        }

        /*
         * V3-D.2 - Scout Neural Fusion -> PROBE.
         *
         * La recommandation Scout n'est plus limitée aux seules voix
         * <= -50 dBFS. Le Scout répond à une question : "faut-il écouter ?"
         *
         * Une seconde décision indépendante répond ensuite :
         * "ce PROBE a-t-il besoin du Normalizer ?"
         */
        val rawScoutCandidate =
            isSpeechScoutProbeCandidate(
                metrics = metrics,
            )

        val rawScoutNeedsNormalization =
            rawScoutCandidate &&
                shouldNormalizeSpeechScoutProbe(
                    metrics = metrics,
                )

        if (
            !temporalSpeech &&
            rawScoutCandidate &&
            speechScout.recommended
        ) {
            return openAndLatch(
                decision =
                    if (rawScoutNeedsNormalization) {
                        GuardianAsrAdmissionDecision
                            .PROBE_WEAK
                    } else {
                        GuardianAsrAdmissionDecision
                            .PROBE_DIRECT
                    },

                reason =
                    when {
                        speechScout.sustainedWeakRecommended &&
                            rawScoutNeedsNormalization ->
                            "SPEECH_SCOUT_RAW_SUSTAINED_WEAK_RECOMMENDS_PROBE"

                        speechScout.sustainedWeakRecommended ->
                            "SPEECH_SCOUT_RAW_SUSTAINED_DIRECT_RECOMMENDS_PROBE"

                        rawScoutNeedsNormalization ->
                            "SPEECH_SCOUT_RAW_WEAK_RECOMMENDS_PROBE"

                        else ->
                            "SPEECH_SCOUT_RAW_DIRECT_RECOMMENDS_PROBE"
                    },

                metrics =
                    metrics,

                acousticRms =
                    safeAcousticRms,

                acousticNoiseFloorRms =
                    safeNoiseFloorRms,

                snrDb =
                    safeSnrDb,

                farVoiceEventId =
                    0L,

                farVoiceSource =
                    GuardianAudioEngine
                        .FarVoiceSource
                        .NONE,

                farVoicePhase =
                    GuardianAudioEngine
                        .FarVoicePhase
                        .NONE,

                neuralFusionScore =
                    safeNeuralFusionScore,

                nowMs =
                    safeNowMs,
            )
        }

        /*
         * Sans validation Scout complète, Neural Fusion seul reste une preuve
         * insuffisante : WAIT.
         */
        return buildResult(
            decision =
                GuardianAsrAdmissionDecision.WAIT,

            reason =
                when {
                    speechScout.recommended ->
                        "SPEECH_SCOUT_RECOMMENDS_PROBE"

                    speechScout.tracking ->
                        "SPEECH_SCOUT_TRACKING"

                    neuralEvidence ->
                        "NEURAL_ENSEMBLE_EVIDENCE_ONLY"

                    else ->
                        "NO_OPENING_EVIDENCE"
                },

            metrics =
                metrics,

            acousticRms =
                safeAcousticRms,

            acousticNoiseFloorRms =
                safeNoiseFloorRms,

            snrDb =
                safeSnrDb,

            farVoiceEventId =
                safeFarVoiceEventId,

            farVoiceSource =
                farVoiceSource,

            farVoicePhase =
                farVoicePhase,

            neuralFusionScore =
                safeNeuralFusionScore,

            nowMs =
                safeNowMs,
        )
    }

    /*
     * Le FINAL n'effectue plus aucun réarmement.
     *
     * Le latch est libéré uniquement par la fin réelle
     * de l'événement acoustique.
     */
    fun onUtteranceFinalized(
    nowMs: Long,
) {

    if (nowMs > 0L) {

        lastFinalizedAtMs =
            nowMs

        lastNeuralEvidenceAtMs =
            0L

        lastNeuralEvidenceEventId =
            0L

        clearSpeechScout()
    }
}

    /**
     * PROBE interne abandonné pendant une acquisition Far Voice toujours
     * directement soutenue. Autorise AU PLUS une nouvelle tentative pour le
     * même eventId. Ne contourne aucun gate : une nouvelle frame ACQUIRED ou
     * SUPPORTED ainsi que la preuve neuronale restent requises au prochain
     * evaluate(). Aucun texte ni événement utilisateur n'est émis ici.
     *
     * Refusé pour les probes Scout (eventId=0), les événements déjà terminés,
     * les abandons AUDIO_STOP et tout deuxième abandon du même événement.
     */
    fun rearmOnceAfterFailedProbe(eventId: Long): Boolean {
        if (
            eventId <= 0L ||
            state != GuardianAsrAdmissionState.LATCHED ||
            latchedFarVoiceEventId != eventId ||
            sameEventProbeRetryUsedForEventId == eventId
        ) {
            return false
        }

        sameEventProbeRetryUsedForEventId = eventId
        retryWaitingForFreshPairedEvidenceEventId = eventId
        releaseLatch()

        // Interdire l'utilisation de preuves temporelles héritées du PROBE
        // abandonné. La seconde chance exige une acquisition nouvelle.
        lastDirectFarVoiceEvidenceAtMs = 0L
        lastDirectFarVoiceEvidenceEventId = 0L
        lastNeuralEvidenceAtMs = 0L
        lastNeuralEvidenceEventId = 0L
        clearSpeechScout()
        return true
    }

    /*
     * Reset global recognizer / release.
     */
    fun reset() {

        state =
            GuardianAsrAdmissionState.IDLE

        candidateFarVoiceEventId =
            0L

        lastDirectFarVoiceEvidenceAtMs =
            0L

        lastDirectFarVoiceEvidenceEventId =
            0L

        lastNeuralEvidenceAtMs =
            0L

        lastNeuralEvidenceEventId =
            0L

        latchedFarVoiceEventId =
            0L

        latchedNeutralFrames =
            0

        sameEventProbeRetryUsedForEventId = 0L
        retryWaitingForFreshPairedEvidenceEventId = 0L

        lastFinalizedAtMs =
            0L

        clearSpeechScout()
    }

    private fun beginCandidate(
        eventId: Long,
    ) {

        state =
            GuardianAsrAdmissionState.CANDIDATE

        candidateFarVoiceEventId =
            eventId
    }

    private fun clearCandidate() {

        if (
            state ==
                GuardianAsrAdmissionState.CANDIDATE
        ) {

            state =
                GuardianAsrAdmissionState.IDLE
        }

        candidateFarVoiceEventId =
            0L
    }

    private fun releaseLatch() {

        state =
            GuardianAsrAdmissionState.IDLE

        latchedFarVoiceEventId =
            0L

        latchedNeutralFrames =
            0

        candidateFarVoiceEventId =
            0L
    }

    private fun openAndLatch(
        decision: GuardianAsrAdmissionDecision,
        reason: String,
        metrics: PreRollMetrics,

        acousticRms: Float,
        acousticNoiseFloorRms: Float,
        snrDb: Float,

        farVoiceEventId: Long,
        farVoiceSource: GuardianAudioEngine.FarVoiceSource,
        farVoicePhase: GuardianAudioEngine.FarVoicePhase,

        neuralFusionScore: Float?,
        nowMs: Long,
    ): GuardianAsrAdmissionResult {

        state =
            GuardianAsrAdmissionState.LATCHED

        latchedFarVoiceEventId =
            farVoiceEventId

        latchedNeutralFrames =
            0

        candidateFarVoiceEventId =
            0L

        return buildResult(
            decision =
                decision,

            reason =
                reason,

            metrics =
                metrics,

            acousticRms =
                acousticRms,

            acousticNoiseFloorRms =
                acousticNoiseFloorRms,

            snrDb =
                snrDb,

            farVoiceEventId =
                farVoiceEventId,

            farVoiceSource =
                farVoiceSource,

            farVoicePhase =
                farVoicePhase,

            neuralFusionScore =
                neuralFusionScore,

            nowMs =
                nowMs,
        )
    }

    /*
     * ====================================================================
     * V3-C - NEURAL SPEECH SCOUT
     * ====================================================================
     */

    private fun updateSpeechScout(
        neuralFusionScore: Float?,
        nowMs: Long,
    ): SpeechScoutSnapshot {

        val safeNowMs =
            nowMs.coerceAtLeast(0L)

        /*
         * Retirer toutes les preuves trop anciennes.
         *
         * La fenêtre est volontairement courte :
         * le Scout doit suivre une construction vocale locale,
         * pas accumuler des bruits répartis sur plusieurs secondes.
         */
        while (
            speechScoutEvidence.isNotEmpty() &&
            (
                safeNowMs -
                    speechScoutEvidence
                        .first()
                        .capturedAtMs
                ) >
                SPEECH_SCOUT_WINDOW_MS
        ) {
            speechScoutEvidence.removeFirst()
        }

        val safeProbability =
            neuralFusionScore
                ?.takeIf {
                    it.isFinite()
                }
                ?.coerceIn(
                    0.0f,
                    1.0f,
                )

        /*
         * Le Scout n'enregistre que les preuves ayant au moins
         * un intérêt neural minimal.
         *
         * Les valeurs inférieures disparaissent naturellement
         * de la fenêtre sans ajouter de score.
         */
        if (
            safeProbability != null &&
            safeProbability >=
                neuralFusionThresholds.speechScoutMinEvidenceProbability
        ) {

            speechScoutEvidence.addLast(
                SpeechScoutEvidence(
                    capturedAtMs =
                        safeNowMs,

                    neuralFusionScore =
                        safeProbability,
                )
            )

            /*
             * Garde-fou mémoire.
             *
             * À 20 frames/s ce plafond représente largement
             * plus que notre fenêtre temporelle réelle.
             */
            while (
                speechScoutEvidence.size >
                SPEECH_SCOUT_MAX_EVIDENCE_ITEMS
            ) {
                speechScoutEvidence.removeFirst()
            }
        }

        val snapshot =
            buildSpeechScoutSnapshot()

        logSpeechScoutDiagnostic(
            snapshot =
                snapshot,

            currentNeuralFusionScore =
                safeProbability,

            nowMs =
                safeNowMs,
        )

        return snapshot
    }

    private fun buildSpeechScoutSnapshot():
        SpeechScoutSnapshot {

        if (speechScoutEvidence.isEmpty()) {

            return SpeechScoutSnapshot(
                score =
                    0.0f,

                evidenceCount =
                    0,

                mediumEvidenceCount =
                    0,

                strongEvidenceCount =
                    0,

                durationMs =
                    0L,

                maximumGapMs =
                    0L,

                peakProbability =
                    null,

                tracking =
                    false,

                strongRecommended =
                    false,

                sustainedWeakRecommended =
                    false,

                recommended =
                    false,
            )
        }

        var score =
            0.0f

        var evidenceCount =
            0

        var mediumEvidenceCount =
            0

        var strongEvidenceCount =
            0

        var peakProbability =
            0.0f

        var previousEvidenceAtMs: Long? =
            null

        var maximumGapMs =
            0L

        for (evidence in speechScoutEvidence) {

            val probability =
                evidence.neuralFusionScore

            peakProbability =
                maxOf(
                    peakProbability,
                    probability,
                )

            if (
                probability >=
                neuralFusionThresholds.speechScoutMinEvidenceProbability
            ) {
                evidenceCount +=
                    1
            }

            if (
                probability >=
                neuralFusionThresholds.speechScoutMediumEvidenceProbability
            ) {
                mediumEvidenceCount +=
                    1
            }

            if (
                probability >=
                neuralFusionThresholds.speechScoutStrongEvidenceProbability
            ) {
                strongEvidenceCount +=
                    1
            }

            previousEvidenceAtMs
                ?.let { previousAtMs ->
                    val gapMs =
                        (
                            evidence.capturedAtMs -
                                previousAtMs
                            )
                            .coerceAtLeast(
                                0L
                            )

                    maximumGapMs =
                        maxOf(
                            maximumGapMs,
                            gapMs,
                        )
                }

            previousEvidenceAtMs =
                evidence.capturedAtMs

            score +=
                when {
                    probability >=
                        neuralFusionThresholds.speechScoutVeryStrongEvidenceProbability ->
                        1.00f

                    probability >=
                        neuralFusionThresholds.speechScoutStrongEvidenceProbability ->
                        0.70f

                    probability >=
                        neuralFusionThresholds.speechScoutMediumEvidenceProbability ->
                        0.35f

                    else ->
                        0.15f
                }
        }

        val firstEvidenceAtMs =
            speechScoutEvidence
                .first()
                .capturedAtMs

        val lastEvidenceAtMs =
            speechScoutEvidence
                .last()
                .capturedAtMs

        val durationMs =
            (
                lastEvidenceAtMs -
                    firstEvidenceAtMs
                )
                .coerceAtLeast(
                    0L
                )

        /*
         * Route historique conservée à l'identique.
         *
         * Une preuve forte >= 0.35 continue d'autoriser la recommandation
         * selon les seuils V3-C existants.
         */
        val strongRecommended =
            score >=
                SPEECH_SCOUT_RECOMMEND_SCORE &&
                evidenceCount >=
                    SPEECH_SCOUT_MIN_EVIDENCE_COUNT &&
                strongEvidenceCount >=
                    SPEECH_SCOUT_MIN_STRONG_EVIDENCE_COUNT

        /*
         * C12 - SUSTAINED_WEAK.
         *
         * Les campagnes 5 m voix douce ont montré des séquences réelles
         * culminant vers 0.25-0.26 sans jamais atteindre 0.35.
         *
         * On ne baisse PAS le seuil fort historique.
         * On ajoute une deuxième route qui exige :
         *
         * - plusieurs preuves totales ;
         * - plusieurs preuves >= 0.20 ;
         * - un score cumulé supérieur ;
         * - une persistance minimale ;
         * - aucune rupture temporelle importante.
         *
         * Un pic isolé autour de 0.20-0.25 reste donc insuffisant.
         */
        val sustainedWeakRecommended =
            !strongRecommended &&
                score >=
                    SPEECH_SCOUT_SUSTAINED_RECOMMEND_SCORE &&
                evidenceCount >=
                    SPEECH_SCOUT_SUSTAINED_MIN_EVIDENCE_COUNT &&
                mediumEvidenceCount >=
                    SPEECH_SCOUT_SUSTAINED_MIN_MEDIUM_EVIDENCE_COUNT &&
                durationMs >=
                    SPEECH_SCOUT_SUSTAINED_MIN_DURATION_MS &&
                maximumGapMs <=
                    SPEECH_SCOUT_SUSTAINED_MAX_GAP_MS &&
                peakProbability >=
                    neuralFusionThresholds.speechScoutMediumEvidenceProbability

        val recommended =
            strongRecommended ||
                sustainedWeakRecommended

        val tracking =
            recommended ||
                (
                    evidenceCount >=
                        SPEECH_SCOUT_TRACKING_MIN_EVIDENCE_COUNT &&
                        score >=
                            SPEECH_SCOUT_TRACKING_MIN_SCORE
                    )

        return SpeechScoutSnapshot(
            score =
                score,

            evidenceCount =
                evidenceCount,

            mediumEvidenceCount =
                mediumEvidenceCount,

            strongEvidenceCount =
                strongEvidenceCount,

            durationMs =
                durationMs,

            maximumGapMs =
                maximumGapMs,

            peakProbability =
                peakProbability,

            tracking =
                tracking,

            strongRecommended =
                strongRecommended,

            sustainedWeakRecommended =
                sustainedWeakRecommended,

            recommended =
                recommended,
        )
    }

    private fun clearSpeechScout() {

        speechScoutEvidence.clear()

        lastSpeechScoutDiagnosticLogAtMs =
            0L

        lastSpeechScoutDiagnosticMode =
            "IDLE"

        lastSpeechScoutRecommendedAtMs =
            0L

        lastSpeechScoutRecommendationWasSustainedWeak =
            false
    }

    /*
     * C12 - logs de validation terrain.
     *
     * Format volontairement stable pour les prochaines campagnes :
     *
     * SPEECH SCOUT C12 :
     * mode=TRACKING|STRONG|SUSTAINED_WEAK,
     * raw=..., score=..., evidence=..., medium=..., strong=...,
     * duration=...ms, maxGap=...ms, peak=..., recommended=...
     */
    private fun logSpeechScoutDiagnostic(
        snapshot: SpeechScoutSnapshot,
        currentNeuralFusionScore: Float?,
        nowMs: Long,
    ) {

        val mode =
            when {
                snapshot.strongRecommended ->
                    "STRONG"

                snapshot.sustainedWeakRecommended ->
                    "SUSTAINED_WEAK"

                snapshot.tracking ->
                    "TRACKING"

                else ->
                    "IDLE"
            }

        val modeChanged =
            mode !=
                lastSpeechScoutDiagnosticMode

        val periodicTrackingLog =
            snapshot.tracking &&
                (
                    lastSpeechScoutDiagnosticLogAtMs <=
                        0L ||
                        nowMs -
                            lastSpeechScoutDiagnosticLogAtMs >=
                            SPEECH_SCOUT_DIAGNOSTIC_INTERVAL_MS
                    )

        val shouldLog =
            snapshot.recommended ||
                modeChanged ||
                periodicTrackingLog

        if (!shouldLog) {
            return
        }

        val rawText =
            currentNeuralFusionScore
                ?.let {
                    "%.3f".format(
                        java.util.Locale.ROOT,
                        it,
                    )
                }
                ?: "--"

        val peakText =
            snapshot.peakProbability
                ?.let {
                    "%.3f".format(
                        java.util.Locale.ROOT,
                        it,
                    )
                }
                ?: "--"

        Log.i(
            TAG,
            "SPEECH SCOUT C12 : " +
                "mode=$mode, " +
                "raw=$rawText, " +
                "score=${"%.2f".format(java.util.Locale.ROOT, snapshot.score)}, " +
                "evidence=${snapshot.evidenceCount}, " +
                "medium=${snapshot.mediumEvidenceCount}, " +
                "strong=${snapshot.strongEvidenceCount}, " +
                "duration=${snapshot.durationMs}ms, " +
                "maxGap=${snapshot.maximumGapMs}ms, " +
                "peak=$peakText, " +
                "recommended=${snapshot.recommended}"
        )

        lastSpeechScoutDiagnosticLogAtMs =
            nowMs

        lastSpeechScoutDiagnosticMode =
            mode
    }

    /*
     * V3-D.2 :
     *
     * Le Scout autorise un PROBE dès que son accumulation neurale est
     * suffisante ET que le pré-roll contient un signal acoustiquement
     * exploitable. Il n'existe volontairement plus de borne supérieure
     * de niveau : une voix à -40 dBFS peut avoir besoin du Scout si le
     * VAD temporel n'est pas encore passé à true.
     *
     * Cette fonction n'autorise jamais un COMMIT.
     */
    private fun isSpeechScoutProbeCandidate(
        metrics: PreRollMetrics,
    ): Boolean {

        val speechDbfs =
            metrics.speechDbfs
                ?: return false

        val speechToNoiseDb =
            metrics.speechToNoiseDb
                ?: return false

        return speechDbfs >=
            MIN_USABLE_DISTANT_SPEECH_DBFS &&
            speechToNoiseDb >=
                SPEECH_SCOUT_MIN_SPEECH_TO_NOISE_DB
    }

    /*
     * Décision séparée de l'admission :
     *
     * - <= seuil historique de parole faible : Normalizer ;
     * - au-dessus : chemin ASR direct.
     *
     * Le Scout décide "écouter ou non" ; ce helper décide uniquement
     * "normaliser ou non".
     */
    private fun shouldNormalizeSpeechScoutProbe(
        metrics: PreRollMetrics,
    ): Boolean {

        val speechDbfs =
            metrics.speechDbfs
                ?: return true

        return speechDbfs <=
            WEAK_SPEECH_MAX_DBFS
    }

    /*
     * ================================================================
     * PRE-ROLL
     * ================================================================
     */

    private data class PreRollMetrics(
        val speechDbfs: Float?,
        val noiseDbfs: Float?,
        val peakDbfs: Float?,
        val speechToNoiseDb: Float?,
    ) {

        companion object {

            val EMPTY =
                PreRollMetrics(
                    speechDbfs =
                        null,

                    noiseDbfs =
                        null,

                    peakDbfs =
                        null,

                    speechToNoiseDb =
                        null,
                )
        }
    }

    private fun analyzePreRoll(
        frames: Collection<FloatArray>,
        ): PreRollMetrics {

        if (frames.isEmpty()) {

            return PreRollMetrics.EMPTY
        }

        val frameLevelsDbfs =
            ArrayList<Float>(
                frames.size
            )

        var absolutePeak =
            0.0f

        for (frame in frames) {

            if (frame.isEmpty()) {
                continue
            }

            val rms =
                calculateRms(
                    samples =
                        frame,
                )

            val rmsDbfs =
                linearToDbfs(
                    value =
                        rms,
                )
                    .coerceAtLeast(
                        MIN_ANALYSIS_DBFS
                    )

            frameLevelsDbfs.add(
                rmsDbfs
            )

            for (sample in frame) {

                val absoluteValue =
                    kotlin.math.abs(
                        sample
                    )

                if (
                    absoluteValue >
                    absolutePeak
                ) {

                    absolutePeak =
                        absoluteValue
                }
            }
        }

        if (frameLevelsDbfs.isEmpty()) {

            return PreRollMetrics.EMPTY
        }

        val noiseDbfs =
            percentile(
                values =
                    frameLevelsDbfs,

                percentile =
                    PRE_ROLL_NOISE_PERCENTILE,
            )

        val speechDbfs =
            percentile(
                values =
                    frameLevelsDbfs,

                percentile =
                    PRE_ROLL_SPEECH_PERCENTILE,
            )

        val speechToNoiseDb =
            (
                speechDbfs -
                    noiseDbfs
            )
                .coerceAtLeast(
                    0.0f
                )

        val peakDbfs =
            linearToDbfs(
                value =
                    absolutePeak,
            )
                .coerceAtLeast(
                    MIN_ANALYSIS_DBFS
                )

        return PreRollMetrics(
            speechDbfs =
                speechDbfs,

            noiseDbfs =
                noiseDbfs,

            peakDbfs =
                peakDbfs,

            speechToNoiseDb =
                speechToNoiseDb,
        )
    }

    private fun isWeakButUsableSpeech(
        metrics: PreRollMetrics,
    ): Boolean {

        val speechDbfs =
            metrics.speechDbfs
                ?: return false

        val speechToNoiseDb =
            metrics.speechToNoiseDb
                ?: return false

        return speechDbfs >=
            MIN_USABLE_DISTANT_SPEECH_DBFS &&
            speechDbfs <=
                WEAK_SPEECH_MAX_DBFS &&
            speechToNoiseDb >=
                MIN_SPEECH_TO_NOISE_DB
    }

    /*
     * ================================================================
     * TIME
     * ================================================================
     */

    private fun calculateEvidenceAge(
        nowMs: Long,
        evidenceAtMs: Long,
    ): Long? {

        if (
            evidenceAtMs <= 0L ||
            nowMs < evidenceAtMs
        ) {

            return null
        }

        return nowMs -
            evidenceAtMs
    }

    private fun calculateTimeSinceLastFinal(
        nowMs: Long,
    ): Long? {

        val lastFinal =
            lastFinalizedAtMs

        if (
            lastFinal <= 0L ||
            nowMs < lastFinal
        ) {

            return null
        }

        return nowMs -
            lastFinal
    }

    /*
     * ================================================================
     * RESULT
     * ================================================================
     */

    private fun buildResult(
        decision: GuardianAsrAdmissionDecision,
        reason: String,
        metrics: PreRollMetrics,

        acousticRms: Float,
        acousticNoiseFloorRms: Float,
        snrDb: Float,

        farVoiceEventId: Long,
        farVoiceSource: GuardianAudioEngine.FarVoiceSource,
        farVoicePhase: GuardianAudioEngine.FarVoicePhase,

        neuralFusionScore: Float?,
        nowMs: Long,
    ): GuardianAsrAdmissionResult {

        val speechScoutSnapshot =
            buildSpeechScoutSnapshot()

        val acousticEvidenceAgeMs =
            calculateEvidenceAge(
                nowMs =
                    nowMs,

                evidenceAtMs =
                    if (
                        lastDirectFarVoiceEvidenceEventId ==
                        farVoiceEventId
                    ) {

                        lastDirectFarVoiceEvidenceAtMs

                    } else {

                        0L
                    },
            )

        val neuralEvidenceAgeMs =
    calculateEvidenceAge(
        nowMs =
            nowMs,

        evidenceAtMs =
            if (
                lastNeuralEvidenceEventId ==
                    farVoiceEventId &&
                farVoiceEventId > 0L
            ) {

                lastNeuralEvidenceAtMs

            } else {

                0L
            },
    )

        return GuardianAsrAdmissionResult(
            decision =
                decision,

            reason =
                reason,

            state =
                state,

            preRollSpeechDbfs =
                metrics.speechDbfs,

            preRollNoiseDbfs =
                metrics.noiseDbfs,

            preRollPeakDbfs =
                metrics.peakDbfs,

            speechToNoiseDb =
                metrics.speechToNoiseDb,

            acousticRms =
                acousticRms,

            acousticNoiseFloorRms =
                acousticNoiseFloorRms,

            snrDb =
                snrDb,

            farVoiceEventId =
                farVoiceEventId,

            farVoiceSource =
                farVoiceSource,

            farVoicePhase =
                farVoicePhase,

            neuralFusionScore =
                neuralFusionScore,

            acousticEvidenceAgeMs =
                acousticEvidenceAgeMs,

            neuralEvidenceAgeMs =
                neuralEvidenceAgeMs,

            timeSinceLastFinalMs =
                calculateTimeSinceLastFinal(
                    nowMs =
                        nowMs,
                ),

            speechScoutScore =
                speechScoutSnapshot.score,

            speechScoutEvidenceCount =
                speechScoutSnapshot.evidenceCount,

            speechScoutPeakProbability =
                speechScoutSnapshot.peakProbability,

            speechScoutRecommended =
                speechScoutSnapshot.recommended,
        )
    }

    /*
     * ================================================================
     * MATH
     * ================================================================
     */

    private fun calculateRms(
        samples: FloatArray,
    ): Float {

        if (samples.isEmpty()) {

            return 0.0f
        }

        var squaredSum =
            0.0

        for (sample in samples) {

            val value =
                sample.toDouble()

            squaredSum +=
                value * value
        }

        return kotlin.math.sqrt(
            squaredSum /
                samples.size.toDouble()
        )
            .toFloat()
    }

    private fun linearToDbfs(
        value: Float,
    ): Float {

        val safeValue =
            value
                .coerceAtLeast(
                    MIN_MEASURABLE_LINEAR
                )

        return (
            20.0 *
                kotlin.math.log10(
                    safeValue.toDouble()
                )
            )
            .toFloat()
    }

    private fun percentile(
        values: List<Float>,
        percentile: Float,
    ): Float {

        require(
            values.isNotEmpty()
        )

        val sortedValues =
            values.sorted()

        if (
            sortedValues.size ==
            1
        ) {

            return sortedValues[0]
        }

        val safePercentile =
            percentile.coerceIn(
                0.0f,
                1.0f,
            )

        val position =
            safePercentile *
                (
                    sortedValues.size -
                        1
                    )

        val lowerIndex =
            kotlin.math.floor(
                position.toDouble()
            )
                .toInt()

        val upperIndex =
            kotlin.math.ceil(
                position.toDouble()
            )
                .toInt()

        if (
            lowerIndex ==
            upperIndex
        ) {

            return sortedValues[
                lowerIndex
            ]
        }

        val fraction =
            position -
                lowerIndex.toFloat()

        val lowerValue =
            sortedValues[
                lowerIndex
            ]

        val upperValue =
            sortedValues[
                upperIndex
            ]

        return lowerValue +
            (
                upperValue -
                    lowerValue
                ) *
                fraction
    }

    companion object {

        /*
         * Seuils historiques du chemin
         * preuve neuronale faible/distante.
         */
        private const val WEAK_SPEECH_MAX_DBFS =
            -42.0f

        private const val MIN_USABLE_DISTANT_SPEECH_DBFS =
            -68.0f

        private const val MIN_SPEECH_TO_NOISE_DB =
            6.0f

        /*
         * Les seuils Raw Neural dépendent désormais du profil
         * Neural Fusion défini dans GuardianNeuralFusionThresholds.
         */

        /*
         * Les essais ont montré que Guardian Far Voice
         * et la preuve neuronale peuvent être décalés de plusieurs frames.
         *
         * Cette fenêtre reste très inférieure au pré-roll
         * maximum disponible.
         */
        private const val TEMPORAL_FUSION_WINDOW_MS =
            600L

        /*
         * Guardian Speech Capture V3-C - Neural Speech Scout.
         *
         * Fenêtre calibrée sur les campagnes V3-B.1 :
         *
         * T3 :
         * 0.173 -> 0.383 -> 0.551 sur ~1.1 s.
         *
         * T5 :
         * plusieurs preuves 0.18-0.25 puis 0.709
         * sur moins de 0.8 s.
         *
         * Un pic isolé ne suffit donc jamais.
         */
        private const val SPEECH_SCOUT_WINDOW_MS =
            1_250L

        /*
         * Les quatre seuils de probabilité du Speech Scout
         * sont fournis par GuardianNeuralFusionThresholds.
         */

        private const val SPEECH_SCOUT_RECOMMEND_SCORE =
            1.40f

        private const val SPEECH_SCOUT_MIN_EVIDENCE_COUNT =
            3

        private const val SPEECH_SCOUT_MIN_STRONG_EVIDENCE_COUNT =
            1

        /*
         * C12 - SUSTAINED_WEAK.
         *
         * Route additionnelle calibrée sur les campagnes 5 m voix douce.
         * Elle cible les séquences persistantes autour de 0.20-0.30 qui
         * échouaient uniquement parce qu'aucune frame n'atteignait 0.35.
         *
         * Les valeurs restent volontairement conservatrices :
         * un événement isolé à ~0.228, déjà observé comme faux positif,
         * ne peut pas satisfaire la persistance requise.
         */
        private const val SPEECH_SCOUT_SUSTAINED_RECOMMEND_SCORE =
            1.50f

        private const val SPEECH_SCOUT_SUSTAINED_MIN_EVIDENCE_COUNT =
            5

        private const val SPEECH_SCOUT_SUSTAINED_MIN_MEDIUM_EVIDENCE_COUNT =
            4

        private const val SPEECH_SCOUT_SUSTAINED_MIN_DURATION_MS =
            200L

        private const val SPEECH_SCOUT_SUSTAINED_MAX_GAP_MS =
            250L

        private const val SPEECH_SCOUT_TRACKING_MIN_EVIDENCE_COUNT =
            2

        private const val SPEECH_SCOUT_TRACKING_MIN_SCORE =
            0.50f

        private const val SPEECH_SCOUT_MAX_EVIDENCE_ITEMS =
            64

        /*
         * On autorise le Scout sous le seuil historique de 6 dB,
         * mais pas sur un contraste acoustique quasi nul.
         *
         * T3/T5 V3-B.1 étaient respectivement ~4.7 et ~5.0 dB.
         */
        private const val SPEECH_SCOUT_MIN_SPEECH_TO_NOISE_DB =
            2.0f

        private const val SPEECH_SCOUT_DIAGNOSTIC_INTERVAL_MS =
            200L

        private const val TAG =
            "GuardianAsrAdmission"

        /*
         * 3 frames Guardian ≈ 150 ms.
         *
         * Utilisé uniquement pour libérer un latch
         * Temporal-VAD-only.
         */
        private const val NEAR_LATCH_RELEASE_FRAMES =
            3

        private const val PRE_ROLL_NOISE_PERCENTILE =
            0.20f

        private const val PRE_ROLL_SPEECH_PERCENTILE =
            0.85f

        private const val MIN_ANALYSIS_DBFS =
            -90.0f

        private const val MIN_MEASURABLE_LINEAR =
            0.000001f
    }
}