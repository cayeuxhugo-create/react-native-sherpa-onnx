package expo.modules.guardiannativeprotection

import kotlin.math.roundToInt

/**
 * Fusion déterministe des preuves Guardian Voice.
 *
 * Responsabilités :
 * - combiner la confiance ASR Sherpa ;
 * - combiner les intentions produites par Understanding ;
 * - renforcer les signaux concordants ;
 * - empêcher une preuve lexicale faible d'être traitée comme un SOS ;
 * - neutraliser les phrases rassurantes ;
 * - produire un score et une décision structurés.
 *
 * Cette classe ne :
 * - déclenche aucun GuardianVoiceEvent ;
 * - ne gère ni cooldown ni wake window ;
 * - ne possède ni microphone ni recognizer ;
 * - ne contacte aucun service réseau.
 *
 * Une future couche NLU IA pourra alimenter cette même Fusion
 * avec de nouvelles preuves sans changer GuardianVoiceEngine.
 */
class GuardianVoiceFusionEngine {

    enum class Level {
        NONE,
        WAKE_ONLY,
        WATCH,
        EMERGENCY,
    }

    data class FusionResult(
        val level: Level,
        val confidenceScore: Int,
        val emergencyDetected: Boolean,
        val wakeDetected: Boolean,
        val strongestIntent:
            GuardianVoiceUnderstandingEngine.VoiceIntent?,
        val supportingEmergencyIntentCount: Int,
        val reasons: List<String>,
    )

    companion object {
        /**
         * Seuil de preuve individuelle suffisante.
         */
        private const val STRONG_INTENT_SCORE =
            88

        /**
         * Seuil à partir duquel une preuve faible peut contribuer
         * à une corroboration multi-intentions.
         */
        private const val SUPPORTING_INTENT_SCORE =
            70

        /**
         * Une urgence issue uniquement de plusieurs preuves moyennes
         * doit dépasser ce seuil final.
         */
        private const val CORROBORATED_EMERGENCY_SCORE =
            82

        private const val MAX_MULTI_INTENT_BONUS =
            12

        private const val CRITICAL_INTENT_BONUS =
            6

        private const val WAKE_EMERGENCY_BONUS =
            6
    }

    @Volatile
    private var lastResult: FusionResult? = null

    private val emergencyIntents =
        setOf(
            GuardianVoiceUnderstandingEngine.VoiceIntent.HELP_REQUEST,
            GuardianVoiceUnderstandingEngine.VoiceIntent.DISTRESS,
            GuardianVoiceUnderstandingEngine.VoiceIntent.FEAR,
            GuardianVoiceUnderstandingEngine.VoiceIntent.POLICE_REQUEST,
            GuardianVoiceUnderstandingEngine.VoiceIntent.EMERGENCY_SERVICES_REQUEST,
            GuardianVoiceUnderstandingEngine.VoiceIntent.FOLLOWED,
            GuardianVoiceUnderstandingEngine.VoiceIntent.THREAT,
            GuardianVoiceUnderstandingEngine.VoiceIntent.ATTACK,
            GuardianVoiceUnderstandingEngine.VoiceIntent.INJURY,
            GuardianVoiceUnderstandingEngine.VoiceIntent.MEDICAL_EMERGENCY,
            GuardianVoiceUnderstandingEngine.VoiceIntent.TRAPPED,
        )

    /**
     * Intentions décrivant directement l'état ou la situation
     * de la personne protégée.
     *
     * Elles sont nécessaires pour qu'un ensemble de preuves faibles
     * puisse être promu en urgence.
     */
    private val personCenteredEmergencyIntents =
        setOf(
            GuardianVoiceUnderstandingEngine.VoiceIntent.HELP_REQUEST,
            GuardianVoiceUnderstandingEngine.VoiceIntent.DISTRESS,
            GuardianVoiceUnderstandingEngine.VoiceIntent.FEAR,
            GuardianVoiceUnderstandingEngine.VoiceIntent.FOLLOWED,
            GuardianVoiceUnderstandingEngine.VoiceIntent.THREAT,
            GuardianVoiceUnderstandingEngine.VoiceIntent.ATTACK,
            GuardianVoiceUnderstandingEngine.VoiceIntent.INJURY,
            GuardianVoiceUnderstandingEngine.VoiceIntent.MEDICAL_EMERGENCY,
            GuardianVoiceUnderstandingEngine.VoiceIntent.TRAPPED,
        )

    fun fuse(
        recognizerConfidence: Int?,
        understanding:
            GuardianVoiceUnderstandingEngine.UnderstandingResult,
        wakeAlreadyArmed: Boolean,
    ): FusionResult {
        val safeRecognizerConfidence =
            recognizerConfidence
                ?.coerceIn(
                    0,
                    100
                )

        if (understanding.normalizedText.isBlank()) {
    val result =
        FusionResult(
            level =
                Level.NONE,

            confidenceScore =
                0,

            emergencyDetected =
                false,

            wakeDetected =
                false,

            strongestIntent =
                null,

            supportingEmergencyIntentCount =
                0,

            reasons =
                listOf(
                    "empty_text"
                ),
        )

    lastResult =
        result

    return result
}

        /*
         * Une phrase explicitement rassurante est une preuve négative.
         *
         * Understanding a déjà protégé les cas critiques explicites.
         * Si reassuranceDetected=true ici, on ne réélève jamais
         * artificiellement la phrase par la Fusion.
         */
        if (
    understanding.reassuranceDetected &&
    !understanding.emergencyDetected
) {
    val result =
        FusionResult(
            level =
                if (understanding.wakeDetected) {
                    Level.WAKE_ONLY
                } else {
                    Level.NONE
                },

            confidenceScore =
                if (understanding.wakeDetected) {
                    understanding.confidenceScore
                } else {
                    0
                },

            emergencyDetected =
                false,

            wakeDetected =
                understanding.wakeDetected,

            strongestIntent =
                understanding.primaryIntent,

            supportingEmergencyIntentCount =
                0,

            reasons =
                listOf(
                    "reassurance"
                ),
        )

    lastResult =
        result

    return result
}

        val emergencyEvidence =
            understanding.detectedIntents
                .filter {
                    it.intent in emergencyIntents
                }
                .sortedByDescending {
                    it.score
                }

        val supportingEmergencyEvidence =
            emergencyEvidence
                .filter {
                    it.score >=
                        SUPPORTING_INTENT_SCORE
                }

        val strongestEmergency =
            emergencyEvidence
                .firstOrNull()

        val strongestSemanticScore =
            strongestEmergency?.score
                ?: understanding.confidenceScore

        var fusedScore =
            when {
                safeRecognizerConfidence != null ->
                    (
                        safeRecognizerConfidence *
                            0.55 +
                            strongestSemanticScore *
                            0.45
                        ).roundToInt()

                else ->
                    strongestSemanticScore
            }

        val reasons =
            mutableListOf<String>()

        if (
            understanding.wakeDetected ||
            wakeAlreadyArmed
        ) {
            reasons +=
                if (wakeAlreadyArmed) {
                    "wake_window"
                } else {
                    "wake_detected"
                }
        }

        if (understanding.emergencyDetected) {
            reasons +=
                "strong_understanding"
        }

        if (
            understanding.emergencyDetected &&
            (
                understanding.wakeDetected ||
                    wakeAlreadyArmed
                )
        ) {
            fusedScore +=
                WAKE_EMERGENCY_BONUS

            reasons +=
                "wake_plus_emergency"
        }

        if (
            strongestEmergency != null &&
            strongestEmergency.score >= 98
        ) {
            fusedScore +=
                CRITICAL_INTENT_BONUS

            reasons +=
                "critical_intent"
        }

        val additionalSupportingCount =
            (
                supportingEmergencyEvidence.size -
                    1
                )
                .coerceAtLeast(0)

        if (additionalSupportingCount > 0) {
            val multiIntentBonus =
                (
                    additionalSupportingCount *
                        4
                    )
                    .coerceAtMost(
                        MAX_MULTI_INTENT_BONUS
                    )

            fusedScore +=
                multiIntentBonus

            reasons +=
                "multi_intent:${
                    supportingEmergencyEvidence.size
                }"
        }

        fusedScore =
            fusedScore.coerceIn(
                0,
                100
            )

        val hasPersonCenteredEvidence =
            supportingEmergencyEvidence.any {
                it.intent in
                    personCenteredEmergencyIntents
            }

        /*
         * Deux intentions moyennes peuvent devenir significatives
         * ensemble, mais uniquement si au moins l'une décrit réellement
         * la situation de la personne.
         *
         * Exemple utile :
         * FEAR 74 + FOLLOWED 70
         *
         * Exemple volontairement non promu :
         * POLICE 76 + POMPIERS 78 dans une conversation banale.
         */
        val corroboratedEmergency =
            !understanding.emergencyDetected &&
                supportingEmergencyEvidence.size >= 2 &&
                hasPersonCenteredEvidence &&
                fusedScore >=
                    CORROBORATED_EMERGENCY_SCORE

        if (corroboratedEmergency) {
            reasons +=
                "corroborated_emergency"
        }

        val emergencyDetected =
            understanding.emergencyDetected ||
                corroboratedEmergency

        val level =
            when {
                emergencyDetected ->
                    Level.EMERGENCY

                understanding.wakeDetected ->
                    Level.WAKE_ONLY

                supportingEmergencyEvidence.isNotEmpty() ->
                    Level.WATCH

                else ->
                    Level.NONE
            }

        if (
            level == Level.WATCH &&
            reasons.none {
                it.startsWith(
                    "multi_intent:"
                )
            }
        ) {
            reasons +=
                "weak_safety_signal"
        }

        val result =
    FusionResult(
        level =
            level,

        confidenceScore =
            fusedScore,

        emergencyDetected =
            emergencyDetected,

        wakeDetected =
            understanding.wakeDetected,

        strongestIntent =
            strongestEmergency?.intent
                ?: understanding.primaryIntent,

        supportingEmergencyIntentCount =
            supportingEmergencyEvidence.size,

        reasons =
            reasons.distinct(),
    )

        lastResult =
            result

        return result
    }

    fun getLastResult(): FusionResult? =
        lastResult
}