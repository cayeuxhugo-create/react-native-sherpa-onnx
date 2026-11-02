package expo.modules.guardiannativeprotection

import android.content.Context
import kotlin.math.max
import java.text.Normalizer
import java.util.Locale

/**
 * Guardian Adaptive Audio - Evidence V1.
 *
 * Ce store ne décide jamais à partir du nombre de microphones ou d'une
 * intuition matérielle. Il conserve uniquement des observations vocales
 * réellement produites par une route de capture Guardian.
 *
 * Les données restent locales au téléphone et sont versionnées. Une nouvelle
 * version du contrat d'évidence doit utiliser un nouveau nom de préférences
 * afin de ne pas mélanger des résultats obtenus avec des algorithmes différents.
 */
internal class GuardianAudioRouteEvidenceStore(
    context: Context,
) {

    enum class ComparisonDecision {
        INSUFFICIENT_BASELINE,
        INSUFFICIENT_CHALLENGER,
        KEEP_BASELINE,
        PROMOTE_CHALLENGER,
    }

    enum class ExplorationDecisionType {
        KEEP_BASELINE,
        EXPLORE_CHALLENGER,
        SUSPEND_EXPLORATION,
    }

    enum class PromotionReviewDecision {
        NOT_PROMOTED,
        HOLD_PROMOTED,
        DEMOTE_TO_BASELINE,
    }

    data class Snapshot(
        val routeKey: String,
        val sessionCount: Long,
        val speechCommitCount: Long,
        val finalCount: Long,
        val qwenReviewCount: Long,
        val qwenCorrectionCount: Long,
        val recognizerErrorCount: Long,
        val confidenceSampleCount: Long,
        val confidenceSumScaled: Long,
        val distantFinalCount: Long,
        val clearFinalCount: Long,
        val firstObservedAtMs: Long,
        val lastObservedAtMs: Long,
    ) {
        val finalYield: Double
            get() =
                if (speechCommitCount <= 0L) {
                    0.0
                } else {
                    (
                        finalCount.toDouble() /
                            speechCommitCount.toDouble()
                        )
                        .coerceIn(0.0, 1.0)
                }

        val qwenCorrectionRate: Double
            get() =
                if (qwenReviewCount <= 0L) {
                    0.0
                } else {
                    (
                        qwenCorrectionCount.toDouble() /
                            qwenReviewCount.toDouble()
                        )
                        .coerceIn(0.0, 1.0)
                }

        val qwenReviewCoverage: Double
            get() =
                if (finalCount <= 0L) {
                    0.0
                } else {
                    (
                        qwenReviewCount.toDouble() /
                            finalCount.toDouble()
                        )
                        .coerceIn(0.0, 1.0)
                }

        val recognizerErrorRate: Double
            get() =
                if (speechCommitCount <= 0L) {
                    0.0
                } else {
                    (
                        recognizerErrorCount.toDouble() /
                            speechCommitCount.toDouble()
                        )
                        .coerceAtLeast(0.0)
                }

        val averageConfidence: Double?
            get() =
                if (confidenceSampleCount <= 0L) {
                    null
                } else {
                    confidenceSumScaled.toDouble() /
                        confidenceSampleCount.toDouble() /
                        CONFIDENCE_SCALE.toDouble()
                }

        val confidenceCoverage: Double
            get() =
                if (finalCount <= 0L) {
                    0.0
                } else {
                    (
                        confidenceSampleCount.toDouble() /
                            finalCount.toDouble()
                        )
                        .coerceIn(0.0, 1.0)
                }

        val mature: Boolean
            get() =
                sessionCount >= MINIMUM_SESSIONS_FOR_COMPARISON &&
                    speechCommitCount >= MINIMUM_SPEECH_COMMITS_FOR_COMPARISON &&
                    finalCount >= MINIMUM_FINALS_FOR_COMPARISON
    }

    data class Comparison(
        val baseline: Snapshot,
        val challenger: Snapshot,
        val decision: ComparisonDecision,
        val positiveSignals: Int,
        val reason: String,
    ) {
        val promoteChallenger: Boolean
            get() =
                decision ==
                    ComparisonDecision.PROMOTE_CHALLENGER
    }

    data class ExplorationDecision(
        val baseline: Snapshot,
        val challenger: Snapshot,
        val decision: ExplorationDecisionType,
        val explorationAttemptCount: Long,
        val baselineSessionsSinceLastExploration: Long,
        val elapsedSinceLastExplorationMs: Long?,
        val reason: String,
    ) {
        val exploreChallenger: Boolean
            get() =
                decision ==
                    ExplorationDecisionType.EXPLORE_CHALLENGER
    }

    data class PromotionReview(
        val baselineReference: Snapshot,
        val challengerCurrent: Snapshot,
        val challengerPostPromotion: Snapshot,
        val decision: PromotionReviewDecision,
        val promotionActive: Boolean,
        val promotionStartedAtMs: Long?,
        val lastDemotedAtMs: Long?,
        val cooldownActive: Boolean,
        val repromotionEvidenceReady: Boolean,
        val repromotionBlocked: Boolean,
        val challengerSinceDemotion: Snapshot,
        val negativeSignals: Int,
        val severeRegression: Boolean,
        val reason: String,
    ) {
        val keepPromotedChallenger: Boolean
            get() =
                promotionActive &&
                    decision ==
                    PromotionReviewDecision.HOLD_PROMOTED

        val demoteToBaseline: Boolean
            get() =
                promotionActive &&
                    decision ==
                    PromotionReviewDecision.DEMOTE_TO_BASELINE
    }

    private val preferences =
        context.applicationContext.getSharedPreferences(
            PREFERENCES_NAME,
            Context.MODE_PRIVATE,
        )

    private val lock =
        Any()

    fun markSessionStarted(
        routeKey: String,
    ): Snapshot =
        update(routeKey) { values, now ->
            values.copy(
                sessionCount =
                    values.sessionCount + 1L,
                firstObservedAtMs =
                    values.firstObservedAtMs
                        .takeIf { it > 0L }
                        ?: now,
                lastObservedAtMs =
                    now,
            )
        }

    fun recordSpeechCommit(
        routeKey: String,
    ): Snapshot =
        update(routeKey) { values, now ->
            values.copy(
                speechCommitCount =
                    values.speechCommitCount + 1L,
                firstObservedAtMs =
                    values.firstObservedAtMs
                        .takeIf { it > 0L }
                        ?: now,
                lastObservedAtMs =
                    now,
            )
        }

    fun recordFinal(
        routeKey: String,
        confidence: Float?,
        speechLevel: String?,
    ): Snapshot =
        update(routeKey) { values, now ->
            val normalizedConfidence =
                confidence
                    ?.takeIf {
                        it.isFinite() &&
                            it in 0.0f..1.0f
                    }

            val confidenceIncrement =
                normalizedConfidence
                    ?.let {
                        (it.toDouble() * CONFIDENCE_SCALE)
                            .toLong()
                    }
                    ?: 0L

            val normalizedSpeechLevel =
                speechLevel
                    ?.trim()
                    ?.uppercase()

            values.copy(
                finalCount =
                    values.finalCount + 1L,
                confidenceSampleCount =
                    values.confidenceSampleCount +
                        if (normalizedConfidence != null) {
                            1L
                        } else {
                            0L
                        },
                confidenceSumScaled =
                    values.confidenceSumScaled +
                        confidenceIncrement,
                distantFinalCount =
                    values.distantFinalCount +
                        if (
                            normalizedSpeechLevel ==
                            "DISTANT_SPEECH"
                        ) {
                            1L
                        } else {
                            0L
                        },
                clearFinalCount =
                    values.clearFinalCount +
                        if (
                            normalizedSpeechLevel ==
                            "CLEAR_SPEECH"
                        ) {
                            1L
                        } else {
                            0L
                        },
                firstObservedAtMs =
                    values.firstObservedAtMs
                        .takeIf { it > 0L }
                        ?: now,
                lastObservedAtMs =
                    now,
            )
        }

    /**
     * Une review Qwen terminée est comptée qu'elle corrige ou non le FINAL
     * primaire. Cela évite de confondre "Qwen indisponible/non exécuté" avec
     * "Qwen a vérifié et n'a rien corrigé".
     */
    fun recordQwenReview(
        routeKey: String,
        corrected: Boolean,
    ): Snapshot =
        update(routeKey) { values, now ->
            values.copy(
                qwenReviewCount =
                    values.qwenReviewCount + 1L,
                qwenCorrectionCount =
                    values.qwenCorrectionCount +
                        if (corrected) 1L else 0L,
                firstObservedAtMs =
                    values.firstObservedAtMs
                        .takeIf { it > 0L }
                        ?: now,
                lastObservedAtMs =
                    now,
            )
        }

    /**
     * Compatibilité avec les anciens appelants. Une correction implique
     * nécessairement qu'une review Qwen a été menée.
     */
    fun recordQwenCorrection(
        routeKey: String,
    ): Snapshot =
        recordQwenReview(
            routeKey = routeKey,
            corrected = true,
        )

    fun recordRecognizerError(
        routeKey: String,
    ): Snapshot =
        update(routeKey) { values, now ->
            values.copy(
                recognizerErrorCount =
                    values.recognizerErrorCount + 1L,
                firstObservedAtMs =
                    values.firstObservedAtMs
                        .takeIf { it > 0L }
                        ?: now,
                lastObservedAtMs =
                    now,
            )
        }

    fun snapshot(
        routeKey: String,
    ): Snapshot =
        synchronized(lock) {
            readSnapshot(
                normalizeRouteKey(routeKey)
            )
        }

    /**
     * Comparaison volontairement conservatrice.
     *
     * Un challenger n'est promu que si :
     * - les deux routes possèdent assez de données ;
     * - le challenger n'est sensiblement pire sur aucun garde-fou ;
     * - au moins deux signaux indépendants sont réellement meilleurs.
     *
     * Qwen est ici un indicateur de correction secondaire, pas une vérité
     * terrain. Il ne peut donc jamais, seul, promouvoir une route.
     */
    fun compare(
        baselineRouteKey: String,
        challengerRouteKey: String,
    ): Comparison =
        synchronized(lock) {
            val baseline =
                readSnapshot(
                    normalizeRouteKey(
                        baselineRouteKey
                    )
                )

            val challenger =
                readSnapshot(
                    normalizeRouteKey(
                        challengerRouteKey
                    )
                )

            if (!baseline.mature) {
                return@synchronized Comparison(
                    baseline = baseline,
                    challenger = challenger,
                    decision =
                        ComparisonDecision
                            .INSUFFICIENT_BASELINE,
                    positiveSignals = 0,
                    reason =
                        "Baseline insuffisamment observée : " +
                            "sessions=${baseline.sessionCount}, " +
                            "commits=${baseline.speechCommitCount}, " +
                            "finals=${baseline.finalCount}.",
                )
            }

            if (!challenger.mature) {
                return@synchronized Comparison(
                    baseline = baseline,
                    challenger = challenger,
                    decision =
                        ComparisonDecision
                            .INSUFFICIENT_CHALLENGER,
                    positiveSignals = 0,
                    reason =
                        "Challenger insuffisamment observé : " +
                            "sessions=${challenger.sessionCount}, " +
                            "commits=${challenger.speechCommitCount}, " +
                            "finals=${challenger.finalCount}.",
                )
            }

            /*
             * Si la baseline a déjà été éprouvée sur de la voix distante,
             * le challenger doit lui aussi posséder un minimum de preuves
             * DISTANT_SPEECH avant toute promotion.
             */
            if (
                baseline.distantFinalCount >=
                    BASELINE_DISTANT_FINALS_REQUIRING_COVERAGE &&
                challenger.distantFinalCount <
                    MINIMUM_CHALLENGER_DISTANT_FINALS
            ) {
                return@synchronized Comparison(
                    baseline = baseline,
                    challenger = challenger,
                    decision =
                        ComparisonDecision
                            .INSUFFICIENT_CHALLENGER,
                    positiveSignals = 0,
                    reason =
                        "Couverture voix distante insuffisante sur le challenger : " +
                            "baselineDistant=${baseline.distantFinalCount}, " +
                            "challengerDistant=${challenger.distantFinalCount}.",
                )
            }

            val yieldDelta =
                challenger.finalYield -
                    baseline.finalYield

            val qwenComparable =
                baseline.qwenReviewCount >=
                    MINIMUM_QWEN_REVIEWS_FOR_COMPARISON &&
                    challenger.qwenReviewCount >=
                        MINIMUM_QWEN_REVIEWS_FOR_COMPARISON &&
                    baseline.qwenReviewCoverage >=
                        MINIMUM_QWEN_REVIEW_COVERAGE &&
                    challenger.qwenReviewCoverage >=
                        MINIMUM_QWEN_REVIEW_COVERAGE

            val correctionDelta =
                if (qwenComparable) {
                    baseline.qwenCorrectionRate -
                        challenger.qwenCorrectionRate
                } else {
                    null
                }

            val errorDelta =
                baseline.recognizerErrorRate -
                    challenger.recognizerErrorRate

            val baselineConfidence =
                baseline.averageConfidence
                    ?.takeIf {
                        baseline.confidenceSampleCount >=
                            MINIMUM_CONFIDENCE_SAMPLES &&
                            baseline.confidenceCoverage >=
                            MINIMUM_CONFIDENCE_COVERAGE
                    }

            val challengerConfidence =
                challenger.averageConfidence
                    ?.takeIf {
                        challenger.confidenceSampleCount >=
                            MINIMUM_CONFIDENCE_SAMPLES &&
                            challenger.confidenceCoverage >=
                            MINIMUM_CONFIDENCE_COVERAGE
                    }

            val confidenceDelta =
                if (
                    baselineConfidence != null &&
                    challengerConfidence != null
                ) {
                    challengerConfidence -
                        baselineConfidence
                } else {
                    null
                }

            /*
             * Qwen reste un diagnostic secondaire.
             *
             * onFinalCorrection() indique les corrections effectivement
             * remontées, mais le recognizer ne fournit pas encore un événement
             * symétrique "review terminée sans correction". Le taux Qwen n'a
             * donc pas aujourd'hui une couverture assez mesurable pour prendre
             * une décision automatique de promotion ou de démotion.
             */
            val materiallyWorse =
                yieldDelta <
                    -MAXIMUM_ALLOWED_YIELD_REGRESSION ||
                    errorDelta <
                    -MAXIMUM_ALLOWED_ERROR_REGRESSION ||
                    (
                        confidenceDelta != null &&
                            confidenceDelta <
                            -MAXIMUM_ALLOWED_CONFIDENCE_REGRESSION
                        ) ||
                    (
                        correctionDelta != null &&
                            correctionDelta <
                            -MAXIMUM_ALLOWED_QWEN_CORRECTION_REGRESSION
                        )

            var positiveSignals =
                0

            if (
                yieldDelta >=
                MINIMUM_YIELD_IMPROVEMENT
            ) {
                positiveSignals +=
                    1
            }

            if (
                errorDelta >=
                MINIMUM_ERROR_RATE_IMPROVEMENT
            ) {
                positiveSignals +=
                    1
            }

            if (
                confidenceDelta != null &&
                confidenceDelta >=
                MINIMUM_CONFIDENCE_IMPROVEMENT
            ) {
                positiveSignals +=
                    1
            }

            if (
                correctionDelta != null &&
                correctionDelta >=
                    MINIMUM_QWEN_CORRECTION_RATE_IMPROVEMENT
            ) {
                positiveSignals +=
                    1
            }

            val promote =
                !materiallyWorse &&
                    positiveSignals >=
                    MINIMUM_POSITIVE_SIGNALS_FOR_PROMOTION

            Comparison(
                baseline = baseline,
                challenger = challenger,
                decision =
                    if (promote) {
                        ComparisonDecision
                            .PROMOTE_CHALLENGER
                    } else {
                        ComparisonDecision
                            .KEEP_BASELINE
                    },
                positiveSignals =
                    positiveSignals,
                reason =
                    "yieldDelta=${formatDelta(yieldDelta)}, " +
                        "correctionDelta=${correctionDelta?.let(::formatDelta) ?: "n/a"}, " +
                        "errorDelta=${formatDelta(errorDelta)}, " +
                        "confidenceDelta=${confidenceDelta?.let(::formatDelta) ?: "n/a"}, " +
                        "positiveSignals=$positiveSignals, " +
                        "materiallyWorse=$materiallyWorse.",
            )
        }


    /**
     * Réévalue une route déjà promue à partir des seules observations
     * réalisées APRÈS sa promotion.
     *
     * Le snapshot baseline est gelé au moment de la promotion. Cela évite
     * qu'une ancienne moyenne favorable du challenger masque une dégradation
     * récente et évite également de devoir faire tourner deux AudioRecord
     * simultanément.
     */
    fun reviewPromotion(
        baselineRouteKey: String,
        challengerRouteKey: String,
    ): PromotionReview =
        synchronized(lock) {
            val baselineKey =
                normalizeRouteKey(
                    baselineRouteKey
                )

            val challengerKey =
                normalizeRouteKey(
                    challengerRouteKey
                )

            val pairPrefix =
                promotionPairPrefix(
                    baselineKey =
                        baselineKey,
                    challengerKey =
                        challengerKey,
                )

            val promotionActive =
                preferences.getBoolean(
                    "$pairPrefix.active",
                    false,
                )

            val promotionStartedAtMs =
                preferences.getLong(
                    "$pairPrefix.started_at_ms",
                    0L,
                )
                    .takeIf { it > 0L }

            val lastDemotedAtMs =
                preferences.getLong(
                    "$pairPrefix.last_demoted_at_ms",
                    0L,
                )
                    .takeIf { it > 0L }

            val now =
                System.currentTimeMillis()

            val cooldownActive =
                lastDemotedAtMs != null &&
                    now - lastDemotedAtMs <
                    REPROMOTION_COOLDOWN_MS

            val demotionCheckpoint =
                if (lastDemotedAtMs != null) {
                    readCheckpoint(
                        checkpointPrefix =
                            "$pairPrefix.demotion_challenger_checkpoint",
                        routeKey =
                            challengerKey,
                    )
                } else {
                    null
                }

            val challengerSnapshotNow =
                readSnapshot(
                    challengerKey
                )

            val challengerSinceDemotion =
                if (
                    lastDemotedAtMs != null &&
                    demotionCheckpoint != null
                ) {
                    subtractSnapshots(
                        current =
                            challengerSnapshotNow,
                        checkpoint =
                            demotionCheckpoint,
                        routeKey =
                            "${challengerKey}_SINCE_DEMOTION",
                    )
                } else {
                    challengerSnapshotNow
                }

            val repromotionEvidenceReady =
                lastDemotedAtMs == null ||
                    (
                        challengerSinceDemotion.sessionCount >=
                            MINIMUM_REPROMOTION_SESSIONS &&
                            challengerSinceDemotion.speechCommitCount >=
                            MINIMUM_REPROMOTION_COMMITS &&
                            challengerSinceDemotion.finalCount >=
                            MINIMUM_REPROMOTION_FINALS
                        )

            val repromotionBlocked =
                cooldownActive ||
                    !repromotionEvidenceReady

            val baselineReference =
                if (promotionActive) {
                    readCheckpoint(
                        checkpointPrefix =
                            "$pairPrefix.baseline_checkpoint",
                        routeKey =
                            baselineKey,
                    )
                        ?: readSnapshot(
                            baselineKey
                        )
                } else {
                    readSnapshot(
                        baselineKey
                    )
                }

            val challengerCurrent =
                challengerSnapshotNow

            val challengerCheckpoint =
                if (promotionActive) {
                    readCheckpoint(
                        checkpointPrefix =
                            "$pairPrefix.challenger_checkpoint",
                        routeKey =
                            challengerKey,
                    )
                } else {
                    null
                }

            val challengerPostPromotion =
                if (
                    promotionActive &&
                    challengerCheckpoint != null
                ) {
                    subtractSnapshots(
                        current =
                            challengerCurrent,
                        checkpoint =
                            challengerCheckpoint,
                        routeKey =
                            "${challengerKey}_POST_PROMOTION",
                    )
                } else {
                    emptySnapshot(
                        routeKey =
                            "${challengerKey}_POST_PROMOTION",
                    )
                }

            if (!promotionActive) {
                return@synchronized PromotionReview(
                    baselineReference =
                        baselineReference,
                    challengerCurrent =
                        challengerCurrent,
                    challengerPostPromotion =
                        challengerPostPromotion,
                    decision =
                        PromotionReviewDecision.NOT_PROMOTED,
                    promotionActive =
                        false,
                    promotionStartedAtMs =
                        promotionStartedAtMs,
                    lastDemotedAtMs =
                        lastDemotedAtMs,
                    cooldownActive =
                        cooldownActive,
                    repromotionEvidenceReady =
                        repromotionEvidenceReady,
                    repromotionBlocked =
                        repromotionBlocked,
                    challengerSinceDemotion =
                        challengerSinceDemotion,
                    negativeSignals =
                        0,
                    severeRegression =
                        false,
                    reason =
                        when {
                            cooldownActive ->
                                "Route non promue; cooldown de re-promotion actif."

                            !repromotionEvidenceReady ->
                                "Route non promue; nouvelles preuves challenger requises après démotion : " +
                                    "sessions=${challengerSinceDemotion.sessionCount}, " +
                                    "commits=${challengerSinceDemotion.speechCommitCount}, " +
                                    "finals=${challengerSinceDemotion.finalCount}."

                            else ->
                                "Route non promue et éligible à une nouvelle comparaison."
                        },
                )
            }

            /*
             * Garde-fou de sécurité précoce.
             *
             * La promotion durable exige toujours 30 commits / 3 sessions,
             * mais une route devenue franchement mauvaise ne doit pas être
             * protégée par l'hystérésis pendant 15 commits. Dès 5 commits,
             * une chute sévère de yield ou une rafale d'erreurs suffit à
             * revenir à la baseline. Les mouvements rapides entre micros sont,
             * eux, gérés par Voice Focus à l'intérieur de la route stéréo.
             */
            if (
                challengerPostPromotion.speechCommitCount >=
                    EARLY_PROMOTION_GUARD_MINIMUM_COMMITS
            ) {
                val earlyYieldRegression =
                    baselineReference.finalYield -
                        challengerPostPromotion.finalYield

                val earlyErrorRegression =
                    challengerPostPromotion.recognizerErrorRate -
                        baselineReference.recognizerErrorRate

                if (
                    earlyYieldRegression >=
                        SEVERE_DEMOTION_YIELD_REGRESSION ||
                    earlyErrorRegression >=
                        SEVERE_DEMOTION_ERROR_REGRESSION
                ) {
                    return@synchronized PromotionReview(
                        baselineReference = baselineReference,
                        challengerCurrent = challengerCurrent,
                        challengerPostPromotion = challengerPostPromotion,
                        decision = PromotionReviewDecision.DEMOTE_TO_BASELINE,
                        promotionActive = true,
                        promotionStartedAtMs = promotionStartedAtMs,
                        lastDemotedAtMs = lastDemotedAtMs,
                        cooldownActive = false,
                        repromotionEvidenceReady = true,
                        repromotionBlocked = false,
                        challengerSinceDemotion = challengerSinceDemotion,
                        negativeSignals = 1,
                        severeRegression = true,
                        reason =
                            "Démotion précoce : régression sévère après " +
                                "${challengerPostPromotion.speechCommitCount} commits, " +
                                "yieldRegression=${formatDelta(earlyYieldRegression)}, " +
                                "errorRegression=${formatDelta(earlyErrorRegression)}.",
                    )
                }
            }

            /*
             * Hystérésis minimale : aucune démotion statistique tant que
             * l'échantillon post-promotion est trop petit.
             */
            if (
                challengerPostPromotion.sessionCount <
                    MINIMUM_POST_PROMOTION_SESSIONS ||
                challengerPostPromotion.speechCommitCount <
                    MINIMUM_POST_PROMOTION_COMMITS ||
                challengerPostPromotion.finalCount <
                    MINIMUM_POST_PROMOTION_FINALS
            ) {
                return@synchronized PromotionReview(
                    baselineReference =
                        baselineReference,
                    challengerCurrent =
                        challengerCurrent,
                    challengerPostPromotion =
                        challengerPostPromotion,
                    decision =
                        PromotionReviewDecision.HOLD_PROMOTED,
                    promotionActive =
                        true,
                    promotionStartedAtMs =
                        promotionStartedAtMs,
                    lastDemotedAtMs =
                        lastDemotedAtMs,
                    cooldownActive =
                        false,
                    repromotionEvidenceReady =
                        true,
                    repromotionBlocked =
                        false,
                    challengerSinceDemotion =
                        challengerSinceDemotion,
                    negativeSignals =
                        0,
                    severeRegression =
                        false,
                    reason =
                        "Promotion maintenue pendant collecte post-promotion : " +
                            "sessions=${challengerPostPromotion.sessionCount}, " +
                            "commits=${challengerPostPromotion.speechCommitCount}, " +
                            "finals=${challengerPostPromotion.finalCount}.",
                )
            }

            val yieldRegression =
                baselineReference.finalYield -
                    challengerPostPromotion.finalYield

            val qwenPromotionComparable =
                baselineReference.qwenReviewCount >=
                    MINIMUM_QWEN_REVIEWS_FOR_COMPARISON &&
                    challengerPostPromotion.qwenReviewCount >=
                        MINIMUM_QWEN_REVIEWS_FOR_COMPARISON &&
                    baselineReference.qwenReviewCoverage >=
                        MINIMUM_QWEN_REVIEW_COVERAGE &&
                    challengerPostPromotion.qwenReviewCoverage >=
                        MINIMUM_QWEN_REVIEW_COVERAGE

            val correctionRegression =
                if (qwenPromotionComparable) {
                    challengerPostPromotion.qwenCorrectionRate -
                        baselineReference.qwenCorrectionRate
                } else {
                    null
                }

            val errorRegression =
                challengerPostPromotion.recognizerErrorRate -
                    baselineReference.recognizerErrorRate

            val baselineConfidence =
                baselineReference.averageConfidence
                    ?.takeIf {
                        baselineReference.confidenceSampleCount >=
                            MINIMUM_CONFIDENCE_SAMPLES &&
                            baselineReference.confidenceCoverage >=
                            MINIMUM_CONFIDENCE_COVERAGE
                    }

            val postPromotionConfidence =
                challengerPostPromotion.averageConfidence
                    ?.takeIf {
                        challengerPostPromotion.confidenceSampleCount >=
                            MINIMUM_POST_PROMOTION_CONFIDENCE_SAMPLES &&
                            challengerPostPromotion.confidenceCoverage >=
                            MINIMUM_CONFIDENCE_COVERAGE
                    }

            val confidenceRegression =
                if (
                    baselineConfidence != null &&
                    postPromotionConfidence != null
                ) {
                    baselineConfidence -
                        postPromotionConfidence
                } else {
                    null
                }

            var negativeSignals =
                0

            if (
                yieldRegression >=
                DEMOTION_YIELD_REGRESSION
            ) {
                negativeSignals +=
                    1
            }

            if (
                errorRegression >=
                DEMOTION_ERROR_REGRESSION
            ) {
                negativeSignals +=
                    1
            }

            if (
                confidenceRegression != null &&
                confidenceRegression >=
                DEMOTION_CONFIDENCE_REGRESSION
            ) {
                negativeSignals +=
                    1
            }

            if (
                correctionRegression != null &&
                correctionRegression >=
                    DEMOTION_QWEN_CORRECTION_REGRESSION
            ) {
                negativeSignals +=
                    1
            }

            val severeRegression =
                yieldRegression >=
                    SEVERE_DEMOTION_YIELD_REGRESSION ||
                    errorRegression >=
                    SEVERE_DEMOTION_ERROR_REGRESSION ||
                    (
                        confidenceRegression != null &&
                            confidenceRegression >=
                            SEVERE_DEMOTION_CONFIDENCE_REGRESSION
                        ) ||
                    (
                        correctionRegression != null &&
                            correctionRegression >=
                            SEVERE_DEMOTION_QWEN_CORRECTION_REGRESSION
                        )

            val demote =
                severeRegression ||
                    negativeSignals >=
                    MINIMUM_NEGATIVE_SIGNALS_FOR_DEMOTION

            PromotionReview(
                baselineReference =
                    baselineReference,
                challengerCurrent =
                    challengerCurrent,
                challengerPostPromotion =
                    challengerPostPromotion,
                decision =
                    if (demote) {
                        PromotionReviewDecision
                            .DEMOTE_TO_BASELINE
                    } else {
                        PromotionReviewDecision
                            .HOLD_PROMOTED
                    },
                promotionActive =
                    true,
                promotionStartedAtMs =
                    promotionStartedAtMs,
                lastDemotedAtMs =
                    lastDemotedAtMs,
                cooldownActive =
                    false,
                repromotionEvidenceReady =
                    true,
                repromotionBlocked =
                    false,
                challengerSinceDemotion =
                    challengerSinceDemotion,
                negativeSignals =
                    negativeSignals,
                severeRegression =
                    severeRegression,
                reason =
                    "postPromotionYieldRegression=${formatDelta(yieldRegression)}, " +
                        "correctionRegression=${correctionRegression?.let(::formatDelta) ?: "n/a"}, " +
                        "errorRegression=${formatDelta(errorRegression)}, " +
                        "confidenceRegression=${confidenceRegression?.let(::formatDelta) ?: "n/a"}, " +
                        "negativeSignals=$negativeSignals, " +
                        "severeRegression=$severeRegression.",
            )
        }

    /**
     * Appelé uniquement après démarrage réussi du challenger promu.
     * Le checkpoint fige la référence baseline et le compteur challenger
     * à partir duquel les observations post-promotion seront calculées.
     */
    fun markPromotionStarted(
        baselineRouteKey: String,
        challengerRouteKey: String,
    ) {
        synchronized(lock) {
            val baselineKey =
                normalizeRouteKey(
                    baselineRouteKey
                )

            val challengerKey =
                normalizeRouteKey(
                    challengerRouteKey
                )

            val pairPrefix =
                promotionPairPrefix(
                    baselineKey =
                        baselineKey,
                    challengerKey =
                        challengerKey,
                )

            val baseline =
                readSnapshot(
                    baselineKey
                )

            val challenger =
                readSnapshot(
                    challengerKey
                )

            writeCheckpoint(
                checkpointPrefix =
                    "$pairPrefix.baseline_checkpoint",
                snapshot =
                    baseline,
            )

            writeCheckpoint(
                checkpointPrefix =
                    "$pairPrefix.challenger_checkpoint",
                snapshot =
                    challenger,
            )

            preferences.edit()
                .putBoolean(
                    "$pairPrefix.active",
                    true,
                )
                .putLong(
                    "$pairPrefix.started_at_ms",
                    System.currentTimeMillis(),
                )
                .apply()
        }
    }

    /**
     * Appelé uniquement après que la baseline a réellement redémarré avec
     * succès. On ne désactive donc jamais une promotion tant que le fallback
     * n'a pas prouvé qu'il pouvait ouvrir sa capture.
     */
    fun markDemotionCompleted(
        baselineRouteKey: String,
        challengerRouteKey: String,
    ) {
        synchronized(lock) {
            val baselineKey =
                normalizeRouteKey(
                    baselineRouteKey
                )

            val challengerKey =
                normalizeRouteKey(
                    challengerRouteKey
                )

            val pairPrefix =
                promotionPairPrefix(
                    baselineKey =
                        baselineKey,
                    challengerKey =
                        challengerKey,
                )

            val baseline =
                readSnapshot(
                    baselineKey
                )

            val challenger =
                readSnapshot(
                    challengerKey
                )

            writeCheckpoint(
                checkpointPrefix =
                    "$pairPrefix.demotion_challenger_checkpoint",
                snapshot =
                    challenger,
            )

            val explorationPrefix =
                explorationPairPrefix(
                    baselineKey =
                        baselineKey,
                    challengerKey =
                        challengerKey,
                )

            val now =
                System.currentTimeMillis()

            val globalExplorationPrefix =
                "exploration_global.$baselineKey"

            preferences.edit()
                .putBoolean(
                    "$pairPrefix.active",
                    false,
                )
                .putLong(
                    "$pairPrefix.last_demoted_at_ms",
                    now,
                )
                /*
                 * Une démotion ouvre un nouveau cycle de validation.
                 * Les anciens essais d'exploration de cette paire ne doivent
                 * pas bloquer définitivement une revalidation future.
                 */
                .putLong(
                    "$explorationPrefix.attempts",
                    0L,
                )
                .putLong(
                    "$explorationPrefix.last_at_ms",
                    now,
                )
                .putLong(
                    "$explorationPrefix.baseline_sessions_at_last",
                    baseline.sessionCount,
                )
                /*
                 * Après une démotion, aucun autre challenger ne doit partir
                 * immédiatement : on réétablit d'abord une vraie fenêtre
                 * baseline commune à toutes les routes alternatives.
                 */
                .putLong(
                    "$globalExplorationPrefix.last_at_ms",
                    now,
                )
                .putLong(
                    "$globalExplorationPrefix.baseline_sessions_at_last",
                    baseline.sessionCount,
                )
                .apply()
        }
    }

    private fun promotionPairPrefix(
        baselineKey: String,
        challengerKey: String,
    ): String =
        "promotion.$baselineKey.$challengerKey"

    private fun emptySnapshot(
        routeKey: String,
    ): Snapshot =
        Snapshot(
            routeKey =
                routeKey,
            sessionCount =
                0L,
            speechCommitCount =
                0L,
            finalCount =
                0L,
            qwenReviewCount =
                0L,
            qwenCorrectionCount =
                0L,
            recognizerErrorCount =
                0L,
            confidenceSampleCount =
                0L,
            confidenceSumScaled =
                0L,
            distantFinalCount =
                0L,
            clearFinalCount =
                0L,
            firstObservedAtMs =
                0L,
            lastObservedAtMs =
                0L,
        )

    private fun subtractSnapshots(
        current: Snapshot,
        checkpoint: Snapshot,
        routeKey: String,
    ): Snapshot =
        Snapshot(
            routeKey =
                routeKey,
            sessionCount =
                (current.sessionCount - checkpoint.sessionCount)
                    .coerceAtLeast(0L),
            speechCommitCount =
                (current.speechCommitCount - checkpoint.speechCommitCount)
                    .coerceAtLeast(0L),
            finalCount =
                (current.finalCount - checkpoint.finalCount)
                    .coerceAtLeast(0L),
            qwenReviewCount =
                (current.qwenReviewCount - checkpoint.qwenReviewCount)
                    .coerceAtLeast(0L),
            qwenCorrectionCount =
                (current.qwenCorrectionCount - checkpoint.qwenCorrectionCount)
                    .coerceAtLeast(0L),
            recognizerErrorCount =
                (current.recognizerErrorCount - checkpoint.recognizerErrorCount)
                    .coerceAtLeast(0L),
            confidenceSampleCount =
                (current.confidenceSampleCount - checkpoint.confidenceSampleCount)
                    .coerceAtLeast(0L),
            confidenceSumScaled =
                (current.confidenceSumScaled - checkpoint.confidenceSumScaled)
                    .coerceAtLeast(0L),
            distantFinalCount =
                (current.distantFinalCount - checkpoint.distantFinalCount)
                    .coerceAtLeast(0L),
            clearFinalCount =
                (current.clearFinalCount - checkpoint.clearFinalCount)
                    .coerceAtLeast(0L),
            firstObservedAtMs =
                checkpoint.lastObservedAtMs,
            lastObservedAtMs =
                current.lastObservedAtMs,
        )

    private fun writeCheckpoint(
        checkpointPrefix: String,
        snapshot: Snapshot,
    ) {
        preferences.edit()
            .putLong(
                "$checkpointPrefix.sessions",
                snapshot.sessionCount,
            )
            .putLong(
                "$checkpointPrefix.speech_commits",
                snapshot.speechCommitCount,
            )
            .putLong(
                "$checkpointPrefix.finals",
                snapshot.finalCount,
            )
            .putLong(
                "$checkpointPrefix.qwen_reviews",
                snapshot.qwenReviewCount,
            )
            .putLong(
                "$checkpointPrefix.qwen_corrections",
                snapshot.qwenCorrectionCount,
            )
            .putLong(
                "$checkpointPrefix.recognizer_errors",
                snapshot.recognizerErrorCount,
            )
            .putLong(
                "$checkpointPrefix.confidence_samples",
                snapshot.confidenceSampleCount,
            )
            .putLong(
                "$checkpointPrefix.confidence_sum_scaled",
                snapshot.confidenceSumScaled,
            )
            .putLong(
                "$checkpointPrefix.distant_finals",
                snapshot.distantFinalCount,
            )
            .putLong(
                "$checkpointPrefix.clear_finals",
                snapshot.clearFinalCount,
            )
            .putLong(
                "$checkpointPrefix.first_observed_at",
                snapshot.firstObservedAtMs,
            )
            .putLong(
                "$checkpointPrefix.last_observed_at",
                snapshot.lastObservedAtMs,
            )
            .apply()
    }

    private fun readCheckpoint(
        checkpointPrefix: String,
        routeKey: String,
    ): Snapshot? {
        if (
            !preferences.contains(
                "$checkpointPrefix.sessions"
            )
        ) {
            return null
        }

        return Snapshot(
            routeKey =
                routeKey,
            sessionCount =
                preferences.getLong(
                    "$checkpointPrefix.sessions",
                    0L,
                ),
            speechCommitCount =
                preferences.getLong(
                    "$checkpointPrefix.speech_commits",
                    0L,
                ),
            finalCount =
                preferences.getLong(
                    "$checkpointPrefix.finals",
                    0L,
                ),
            qwenReviewCount =
                preferences.getLong(
                    "$checkpointPrefix.qwen_reviews",
                    0L,
                ),
            qwenCorrectionCount =
                preferences.getLong(
                    "$checkpointPrefix.qwen_corrections",
                    0L,
                ),
            recognizerErrorCount =
                preferences.getLong(
                    "$checkpointPrefix.recognizer_errors",
                    0L,
                ),
            confidenceSampleCount =
                preferences.getLong(
                    "$checkpointPrefix.confidence_samples",
                    0L,
                ),
            confidenceSumScaled =
                preferences.getLong(
                    "$checkpointPrefix.confidence_sum_scaled",
                    0L,
                ),
            distantFinalCount =
                preferences.getLong(
                    "$checkpointPrefix.distant_finals",
                    0L,
                ),
            clearFinalCount =
                preferences.getLong(
                    "$checkpointPrefix.clear_finals",
                    0L,
                ),
            firstObservedAtMs =
                preferences.getLong(
                    "$checkpointPrefix.first_observed_at",
                    0L,
                ),
            lastObservedAtMs =
                preferences.getLong(
                    "$checkpointPrefix.last_observed_at",
                    0L,
                ),
        )
    }

    /**
     * Décide si le challenger peut être observé pendant la prochaine session
     * audio AUTO.
     *
     * Principes de sécurité :
     * - la baseline doit déjà être mature ;
     * - aucune exploration si le challenger est déjà mature ;
     * - jamais deux explorations rapprochées ;
     * - au moins deux sessions baseline entre deux explorations ;
     * - arrêt anticipé si les premières preuves du challenger montrent une
     *   régression importante.
     *
     * Une exploration ne change jamais de route au milieu d'une session :
     * cette décision est prise avant la construction de GuardianAudioEngine.
     */
    fun decideExploration(
        baselineRouteKey: String,
        challengerRouteKey: String,
    ): ExplorationDecision =
        synchronized(lock) {
            val baselineKey =
                normalizeRouteKey(
                    baselineRouteKey
                )

            val challengerKey =
                normalizeRouteKey(
                    challengerRouteKey
                )

            val baseline =
                readSnapshot(
                    baselineKey
                )

            val challenger =
                readSnapshot(
                    challengerKey
                )

            val pairPrefix =
                explorationPairPrefix(
                    baselineKey =
                        baselineKey,
                    challengerKey =
                        challengerKey,
                )

            val attemptCount =
                preferences.getLong(
                    "$pairPrefix.attempts",
                    0L,
                )

            val pairLastExplorationAtMs =
                preferences.getLong(
                    "$pairPrefix.last_at_ms",
                    0L,
                )

            val pairBaselineSessionsAtLastExploration =
                preferences.getLong(
                    "$pairPrefix.baseline_sessions_at_last",
                    0L,
                )

            /*
             * Plusieurs challengers peuvent exister sur un même téléphone.
             * Le cooldown doit donc être global à la baseline, sinon deux
             * challengers différents pourraient être explorés sur deux
             * redémarrages consécutifs.
             */
            val globalExplorationPrefix =
                "exploration_global.$baselineKey"

            val globalLastExplorationAtMs =
                preferences.getLong(
                    "$globalExplorationPrefix.last_at_ms",
                    0L,
                )

            val globalBaselineSessionsAtLastExploration =
                preferences.getLong(
                    "$globalExplorationPrefix.baseline_sessions_at_last",
                    0L,
                )

            val useGlobalGate =
                globalLastExplorationAtMs >=
                    pairLastExplorationAtMs

            val lastExplorationAtMs =
                if (useGlobalGate) {
                    globalLastExplorationAtMs
                } else {
                    pairLastExplorationAtMs
                }

            val baselineSessionsAtLastExploration =
                if (useGlobalGate) {
                    globalBaselineSessionsAtLastExploration
                } else {
                    pairBaselineSessionsAtLastExploration
                }

            val now =
                System.currentTimeMillis()

            val elapsedSinceLastExplorationMs =
                if (lastExplorationAtMs > 0L) {
                    (now - lastExplorationAtMs)
                        .coerceAtLeast(0L)
                } else {
                    null
                }

            val baselineSessionsSinceLastExploration =
                if (lastExplorationAtMs > 0L) {
                    (
                        baseline.sessionCount -
                            baselineSessionsAtLastExploration
                        )
                        .coerceAtLeast(0L)
                } else {
                    baseline.sessionCount
                }

            fun result(
                decision:
                    ExplorationDecisionType,
                reason:
                    String,
            ): ExplorationDecision =
                ExplorationDecision(
                    baseline =
                        baseline,
                    challenger =
                        challenger,
                    decision =
                        decision,
                    explorationAttemptCount =
                        attemptCount,
                    baselineSessionsSinceLastExploration =
                        baselineSessionsSinceLastExploration,
                    elapsedSinceLastExplorationMs =
                        elapsedSinceLastExplorationMs,
                    reason =
                        reason,
                )

            if (!baseline.mature) {
                return@synchronized result(
                    decision =
                        ExplorationDecisionType
                            .KEEP_BASELINE,
                    reason =
                        "Baseline non mature : exploration interdite.",
                )
            }

            val promotionPrefix =
                promotionPairPrefix(
                    baselineKey =
                        baselineKey,
                    challengerKey =
                        challengerKey,
                )

            val lastDemotedAtMs =
                preferences.getLong(
                    "$promotionPrefix.last_demoted_at_ms",
                    0L,
                )
                    .takeIf { it > 0L }

            val promotionCooldownActive =
                lastDemotedAtMs != null &&
                    now - lastDemotedAtMs <
                    REPROMOTION_COOLDOWN_MS

            if (promotionCooldownActive) {
                return@synchronized result(
                    decision =
                        ExplorationDecisionType
                            .KEEP_BASELINE,
                    reason =
                        "Cooldown post-démotion actif : aucune revalidation challenger.",
                )
            }

            val demotionCheckpoint =
                if (lastDemotedAtMs != null) {
                    readCheckpoint(
                        checkpointPrefix =
                            "$promotionPrefix.demotion_challenger_checkpoint",
                        routeKey =
                            challengerKey,
                    )
                } else {
                    null
                }

            val challengerSinceDemotion =
                if (
                    lastDemotedAtMs != null &&
                    demotionCheckpoint != null
                ) {
                    subtractSnapshots(
                        current =
                            challenger,
                        checkpoint =
                            demotionCheckpoint,
                        routeKey =
                            "${challengerKey}_SINCE_DEMOTION",
                    )
                } else {
                    challenger
                }

            val revalidationRequired =
                lastDemotedAtMs != null &&
                    (
                        challengerSinceDemotion.sessionCount <
                            MINIMUM_REPROMOTION_SESSIONS ||
                            challengerSinceDemotion.speechCommitCount <
                            MINIMUM_REPROMOTION_COMMITS ||
                            challengerSinceDemotion.finalCount <
                            MINIMUM_REPROMOTION_FINALS
                        )

            if (
                challenger.mature &&
                !revalidationRequired
            ) {
                return@synchronized result(
                    decision =
                        ExplorationDecisionType
                            .KEEP_BASELINE,
                    reason =
                        "Challenger mature avec preuves post-démotion suffisantes : " +
                            "la décision appartient à compare().",
                )
            }

            if (
                attemptCount >=
                MAXIMUM_EXPLORATION_ATTEMPTS
            ) {
                return@synchronized result(
                    decision =
                        ExplorationDecisionType
                            .SUSPEND_EXPLORATION,
                    reason =
                        "Quota d'exploration atteint : attempts=$attemptCount.",
                )
            }

            if (
                elapsedSinceLastExplorationMs != null &&
                elapsedSinceLastExplorationMs <
                    MINIMUM_EXPLORATION_INTERVAL_MS
            ) {
                return@synchronized result(
                    decision =
                        ExplorationDecisionType
                            .KEEP_BASELINE,
                    reason =
                        "Cooldown exploration actif : elapsedMs=$elapsedSinceLastExplorationMs.",
                )
            }

            if (
                lastExplorationAtMs > 0L &&
                baselineSessionsSinceLastExploration <
                    MINIMUM_BASELINE_SESSIONS_BETWEEN_EXPLORATIONS
            ) {
                return@synchronized result(
                    decision =
                        ExplorationDecisionType
                            .KEEP_BASELINE,
                    reason =
                        "Pas assez de sessions baseline depuis la dernière exploration : " +
                            "$baselineSessionsSinceLastExploration.",
                )
            }

            val evaluationChallenger =
                if (revalidationRequired) {
                    challengerSinceDemotion
                } else {
                    challenger
                }

            /*
             * Garde-fous précoces : dès que le challenger possède quelques
             * utterances, une forte régression suspend toute nouvelle
             * exposition automatique.
             */
            if (
                evaluationChallenger.speechCommitCount >=
                    EARLY_GUARD_MINIMUM_COMMITS
            ) {
                val yieldRegression =
                    baseline.finalYield -
                        evaluationChallenger.finalYield

                if (
                    yieldRegression >
                    EARLY_GUARD_MAXIMUM_YIELD_REGRESSION
                ) {
                    return@synchronized result(
                        decision =
                            ExplorationDecisionType
                                .SUSPEND_EXPLORATION,
                        reason =
                            "Régression précoce FINAL yield : " +
                                "baseline=${formatDelta(baseline.finalYield)}, " +
                                "challenger=${formatDelta(evaluationChallenger.finalYield)}.",
                    )
                }

                if (
                    evaluationChallenger.recognizerErrorCount >=
                        EARLY_GUARD_MINIMUM_ERRORS
                ) {
                    val errorRegression =
                        evaluationChallenger.recognizerErrorRate -
                            baseline.recognizerErrorRate

                    if (
                        errorRegression >
                        EARLY_GUARD_MAXIMUM_ERROR_REGRESSION
                    ) {
                        return@synchronized result(
                            decision =
                                ExplorationDecisionType
                                    .SUSPEND_EXPLORATION,
                            reason =
                                "Régression précoce erreurs recognizer : " +
                                    "baseline=${formatDelta(baseline.recognizerErrorRate)}, " +
                                    "challenger=${formatDelta(evaluationChallenger.recognizerErrorRate)}.",
                        )
                    }
                }

                val baselineConfidence =
                    baseline.averageConfidence
                        ?.takeIf {
                            baseline.confidenceSampleCount >=
                                EARLY_GUARD_MINIMUM_CONFIDENCE_SAMPLES
                        }

                val challengerConfidence =
                    evaluationChallenger.averageConfidence
                        ?.takeIf {
                            evaluationChallenger.confidenceSampleCount >=
                                EARLY_GUARD_MINIMUM_CONFIDENCE_SAMPLES
                        }

                if (
                    baselineConfidence != null &&
                    challengerConfidence != null &&
                    (
                        baselineConfidence -
                            challengerConfidence
                        ) >
                        EARLY_GUARD_MAXIMUM_CONFIDENCE_REGRESSION
                ) {
                    return@synchronized result(
                        decision =
                            ExplorationDecisionType
                                .SUSPEND_EXPLORATION,
                        reason =
                            "Régression précoce confidence : " +
                                "baseline=${formatDelta(baselineConfidence)}, " +
                                "challenger=${formatDelta(challengerConfidence)}.",
                    )
                }
            }

            result(
                decision =
                    ExplorationDecisionType
                        .EXPLORE_CHALLENGER,
                reason =
                    if (revalidationRequired) {
                        "Revalidation post-démotion requise et garde-fous OK. " +
                            "Exploration autorisée pour la prochaine session uniquement."
                    } else {
                        "Baseline mature, challenger immature et garde-fous OK. " +
                            "Exploration autorisée pour la prochaine session uniquement."
                    },
            )
        }

    /**
     * Appelé uniquement après démarrage réussi du GuardianAudioEngine
     * challenger. Une tentative qui n'a jamais ouvert la capture ne consomme
     * donc pas le quota.
     */
    fun markExplorationStarted(
        baselineRouteKey: String,
        challengerRouteKey: String,
    ) {
        synchronized(lock) {
            val baselineKey =
                normalizeRouteKey(
                    baselineRouteKey
                )

            val challengerKey =
                normalizeRouteKey(
                    challengerRouteKey
                )

            val baseline =
                readSnapshot(
                    baselineKey
                )

            val pairPrefix =
                explorationPairPrefix(
                    baselineKey =
                        baselineKey,
                    challengerKey =
                        challengerKey,
                )

            val previousAttempts =
                preferences.getLong(
                    "$pairPrefix.attempts",
                    0L,
                )

            val now =
                System.currentTimeMillis()

            val globalExplorationPrefix =
                "exploration_global.$baselineKey"

            preferences.edit()
                .putLong(
                    "$pairPrefix.attempts",
                    previousAttempts + 1L,
                )
                .putLong(
                    "$pairPrefix.last_at_ms",
                    now,
                )
                .putLong(
                    "$pairPrefix.baseline_sessions_at_last",
                    baseline.sessionCount,
                )
                .putLong(
                    "$globalExplorationPrefix.last_at_ms",
                    now,
                )
                .putLong(
                    "$globalExplorationPrefix.baseline_sessions_at_last",
                    baseline.sessionCount,
                )
                .apply()
        }
    }

    private fun explorationPairPrefix(
        baselineKey: String,
        challengerKey: String,
    ): String =
        "exploration.$baselineKey.$challengerKey"

    private fun update(
        routeKey: String,
        transform: (
            Snapshot,
            Long,
        ) -> Snapshot,
    ): Snapshot =
        synchronized(lock) {
            val normalizedKey =
                normalizeRouteKey(routeKey)

            val current =
                readSnapshot(normalizedKey)

            val updated =
                transform(
                    current,
                    System.currentTimeMillis(),
                )

            writeSnapshot(updated)

            updated
        }

    private fun readSnapshot(
        routeKey: String,
    ): Snapshot =
        Snapshot(
            routeKey = routeKey,
            sessionCount =
                preferences.getLong(
                    key(routeKey, "sessions"),
                    0L,
                ),
            speechCommitCount =
                preferences.getLong(
                    key(routeKey, "speech_commits"),
                    0L,
                ),
            finalCount =
                preferences.getLong(
                    key(routeKey, "finals"),
                    0L,
                ),
            qwenReviewCount =
                preferences.getLong(
                    key(routeKey, "qwen_reviews"),
                    0L,
                ),
            qwenCorrectionCount =
                preferences.getLong(
                    key(routeKey, "qwen_corrections"),
                    0L,
                ),
            recognizerErrorCount =
                preferences.getLong(
                    key(routeKey, "recognizer_errors"),
                    0L,
                ),
            confidenceSampleCount =
                preferences.getLong(
                    key(routeKey, "confidence_samples"),
                    0L,
                ),
            confidenceSumScaled =
                preferences.getLong(
                    key(routeKey, "confidence_sum_scaled"),
                    0L,
                ),
            distantFinalCount =
                preferences.getLong(
                    key(routeKey, "distant_finals"),
                    0L,
                ),
            clearFinalCount =
                preferences.getLong(
                    key(routeKey, "clear_finals"),
                    0L,
                ),
            firstObservedAtMs =
                preferences.getLong(
                    key(routeKey, "first_observed_at"),
                    0L,
                ),
            lastObservedAtMs =
                preferences.getLong(
                    key(routeKey, "last_observed_at"),
                    0L,
                ),
        )

    private fun writeSnapshot(
        snapshot: Snapshot,
    ) {
        preferences.edit()
            .putLong(
                key(snapshot.routeKey, "sessions"),
                snapshot.sessionCount,
            )
            .putLong(
                key(snapshot.routeKey, "speech_commits"),
                snapshot.speechCommitCount,
            )
            .putLong(
                key(snapshot.routeKey, "finals"),
                snapshot.finalCount,
            )
            .putLong(
                key(snapshot.routeKey, "qwen_reviews"),
                snapshot.qwenReviewCount,
            )
            .putLong(
                key(snapshot.routeKey, "qwen_corrections"),
                snapshot.qwenCorrectionCount,
            )
            .putLong(
                key(snapshot.routeKey, "recognizer_errors"),
                snapshot.recognizerErrorCount,
            )
            .putLong(
                key(snapshot.routeKey, "confidence_samples"),
                snapshot.confidenceSampleCount,
            )
            .putLong(
                key(snapshot.routeKey, "confidence_sum_scaled"),
                snapshot.confidenceSumScaled,
            )
            .putLong(
                key(snapshot.routeKey, "distant_finals"),
                snapshot.distantFinalCount,
            )
            .putLong(
                key(snapshot.routeKey, "clear_finals"),
                snapshot.clearFinalCount,
            )
            .putLong(
                key(snapshot.routeKey, "first_observed_at"),
                snapshot.firstObservedAtMs,
            )
            .putLong(
                key(snapshot.routeKey, "last_observed_at"),
                snapshot.lastObservedAtMs,
            )
            .apply()
    }

    /**
     * V3 SHADOW ACOUSTIQUE : données numériques confirmées par l'événement
     * neural onSpeechStarted()/onSpeechEnded(). Pas de texte, de PCM ni de
     * localisation enregistrés ici. Ne participe JAMAIS à une promotion AUTO :
     * une supériorité acoustique ne démontre pas une meilleure transcription.
     */
    data class FocusAcousticSummary(
        val selectedFrames: List<Long>,
        val acousticWinnerFrames: List<Long>,
        val stableAlignedFrames: Long,
        val transitions: Long,
    )

    fun recordConfirmedFocusAcoustics(
        routeKey: String,
        selectedFrameDelta: List<Long>,
        acousticWinnerDelta: List<Long>,
        stableAlignedDelta: Long,
        transitionsDelta: Long,
    ): FocusAcousticSummary = synchronized(lock) {
        require(selectedFrameDelta.size == 4 && acousticWinnerDelta.size == 4)
        require(selectedFrameDelta.all { it >= 0L } && acousticWinnerDelta.all { it >= 0L })
        require(stableAlignedDelta >= 0L && transitionsDelta >= 0L)
        val prefix = "focus_v3.${normalizeRouteKey(routeKey)}"
        val editor = preferences.edit()
        val selected = List(4) { i ->
            val name = "$prefix.selected_$i"
            val count = preferences.getLong(name, 0L) + selectedFrameDelta[i]
            editor.putLong(name, count)
            count
        }
        val winner = List(4) { i ->
            val name = "$prefix.best_$i"
            val count = preferences.getLong(name, 0L) + acousticWinnerDelta[i]
            editor.putLong(name, count)
            count
        }
        val stable = preferences.getLong("$prefix.stable", 0L) + stableAlignedDelta
        val transitions = preferences.getLong("$prefix.transitions", 0L) + transitionsDelta
        editor.putLong("$prefix.stable", stable)
        editor.putLong("$prefix.transitions", transitions)
        editor.apply()
        FocusAcousticSummary(selected, winner, stable, transitions)
    }

    /**
     * Référence lexicale EXPLICITE du protocole de test. Aucun texte/PCM n'est
     * stocké : uniquement des cumuls de mots et de substitutions/insertions/
     * suppressions. AUTO ne promeut pas directement avec cette seule famille
     * de preuves (modèles Qwen/Nemotron différents et orientations variables).
     */
    data class ReferenceViewMetric(
        val trials: Long,
        val referenceWords: Long,
        val editErrors: Long,
    ) {
        val wer: Double? get() = if (referenceWords > 0) editErrors.toDouble() /
            referenceWords.toDouble() else null
    }

    data class ReferenceShadowSummary(
        val scenario: String,
        val scores: Map<String, ReferenceViewMetric>,
    )

    fun recordControlledShadowReference(
        routeKey: String,
        scenario: String,
        referenceText: String,
        primaryText: String,
        shadowTextByView: Map<String, String>,
    ): ReferenceShadowSummary = synchronized(lock) {
        require(scenario in setOf("FRONT", "MOVING", "POCKET"))
        val reference = lexicalTokens(referenceText)
        require(reference.isNotEmpty() && reference.size <= 60)
        require(shadowTextByView.keys.containsAll(
            setOf("CHANNEL_0", "CHANNEL_1", "WEIGHTED_FUSION")))
        val prefix = "focus_reference_v1.${normalizeRouteKey(routeKey)}.$scenario"
        val candidates = linkedMapOf("PRIMARY" to primaryText)
        for (label in listOf("CHANNEL_0", "CHANNEL_1", "WEIGHTED_FUSION", "ALIGNED_FUSION")) {
            shadowTextByView[label]?.let { candidates[label] = it }
        }
        val edit = preferences.edit()
        val results = linkedMapOf<String, ReferenceViewMetric>()
        for ((label, text) in candidates) {
            val keyBase = "$prefix.$label"
            val previous = ReferenceViewMetric(
                trials = preferences.getLong("$keyBase.trials", 0L),
                referenceWords = preferences.getLong("$keyBase.words", 0L),
                editErrors = preferences.getLong("$keyBase.errors", 0L),
            )
            val next = ReferenceViewMetric(
                trials = previous.trials + 1,
                referenceWords = previous.referenceWords + reference.size,
                editErrors = previous.editErrors + lexicalEditDistance(reference,
                    lexicalTokens(text)),
            )
            edit.putLong("$keyBase.trials", next.trials)
            edit.putLong("$keyBase.words", next.referenceWords)
            edit.putLong("$keyBase.errors", next.editErrors)
            results[label] = next
        }
        edit.apply()
        ReferenceShadowSummary(scenario, results)
    }

    private fun lexicalTokens(value: String): List<String> =
        Normalizer.normalize(value.lowercase(Locale.ROOT), Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .replace(Regex("[^a-z0-9 ]"), " ")
            .trim().split(Regex("\\s+")).filter { it.isNotEmpty() }

    private fun lexicalEditDistance(a: List<String>, b: List<String>): Long {
        var prev = IntArray(b.size + 1) { it }
        var curr = IntArray(b.size + 1)
        for (i in a.indices) {
            curr[0] = i + 1
            for (j in b.indices) {
                curr[j + 1] = minOf(
                    prev[j + 1] + 1, curr[j] + 1,
                    prev[j] + if (a[i] == b[j]) 0 else 1,
                )
            }
            val tmp = prev; prev = curr; curr = tmp
        }
        return prev[b.size].toLong()
    }

    private fun key(
        routeKey: String,
        field: String,
    ): String =
        "$routeKey.$field"

    private fun normalizeRouteKey(
        routeKey: String,
    ): String {
        val normalized =
            routeKey
                .trim()
                .uppercase()
                .replace(
                    Regex("[^A-Z0-9_]+"),
                    "_",
                )
                .trim('_')

        require(normalized.isNotBlank()) {
            "routeKey audio vide"
        }

        return normalized
    }

    private fun formatDelta(
        value: Double,
    ): String =
        "%.4f".format(value)

    companion object {
        private const val PREFERENCES_NAME =
            "guardian_audio_route_evidence_v3"

        private const val CONFIDENCE_SCALE =
            1_000_000L

        private const val MINIMUM_SESSIONS_FOR_COMPARISON =
            3L

        private const val MINIMUM_SPEECH_COMMITS_FOR_COMPARISON =
            30L

        /*
         * 30 commits / 3 sessions restent le contrat de promotion durable.
         * Au moins 80 % de ces commits doivent aussi avoir produit un FINAL :
         * 8 FINAL sur 30 laissait une route trop peu observée devenir "mature".
         */
        private const val MINIMUM_FINALS_FOR_COMPARISON =
            24L

        private const val MINIMUM_CONFIDENCE_SAMPLES =
            8L

        private const val MINIMUM_QWEN_REVIEWS_FOR_COMPARISON =
            8L

        private const val MINIMUM_QWEN_REVIEW_COVERAGE =
            0.50

        private const val MINIMUM_CONFIDENCE_COVERAGE =
            0.50

        private const val BASELINE_DISTANT_FINALS_REQUIRING_COVERAGE =
            6L

        private const val MINIMUM_CHALLENGER_DISTANT_FINALS =
            4L

        private const val MAXIMUM_ALLOWED_YIELD_REGRESSION =
            0.02

        private const val MAXIMUM_ALLOWED_ERROR_REGRESSION =
            0.02

        private const val MAXIMUM_ALLOWED_CONFIDENCE_REGRESSION =
            0.02

        private const val MAXIMUM_ALLOWED_QWEN_CORRECTION_REGRESSION =
            0.10

        private const val MINIMUM_YIELD_IMPROVEMENT =
            0.03

        private const val MINIMUM_ERROR_RATE_IMPROVEMENT =
            0.02

        private const val MINIMUM_CONFIDENCE_IMPROVEMENT =
            0.03

        private const val MINIMUM_QWEN_CORRECTION_RATE_IMPROVEMENT =
            0.10

        private const val MINIMUM_POSITIVE_SIGNALS_FOR_PROMOTION =
            2

        /*
         * Exploration contrôlée : la baseline doit d'abord être mature.
         * L'exploration ne sert ensuite qu'à collecter les preuves manquantes
         * du challenger.
         */
        private const val MAXIMUM_EXPLORATION_ATTEMPTS =
            8L

        private const val MINIMUM_BASELINE_SESSIONS_BETWEEN_EXPLORATIONS =
            1L

        /*
         * L'adaptation instantanée se fait dans Voice Focus, pas ici.
         * Ce cooldown protège seulement l'exploration d'une architecture de
         * capture différente. 30 min évitent les oscillations sans bloquer
         * l'apprentissage pendant 6 h après une simple session d'essai.
         */
        private const val MINIMUM_EXPLORATION_INTERVAL_MS =
            30L * 60L * 1_000L

        private const val EARLY_GUARD_MINIMUM_COMMITS =
            5L

        private const val EARLY_GUARD_MINIMUM_ERRORS =
            2L

        private const val EARLY_GUARD_MINIMUM_CONFIDENCE_SAMPLES =
            4L

        private const val EARLY_GUARD_MAXIMUM_YIELD_REGRESSION =
            0.15

        private const val EARLY_GUARD_MAXIMUM_ERROR_REGRESSION =
            0.10

        private const val EARLY_GUARD_MAXIMUM_CONFIDENCE_REGRESSION =
            0.10

        /*
         * Réévaluation d'une route promue.
         *
         * Ces seuils sont volontairement plus larges que les seuils de
         * promotion : il faut une dégradation claire pour revenir en arrière.
         * Cette asymétrie constitue l'hystérésis anti-oscillation.
         */
        private const val EARLY_PROMOTION_GUARD_MINIMUM_COMMITS =
            5L

        private const val MINIMUM_POST_PROMOTION_SESSIONS =
            3L

        private const val MINIMUM_POST_PROMOTION_COMMITS =
            15L

        private const val MINIMUM_POST_PROMOTION_FINALS =
            4L

        private const val MINIMUM_POST_PROMOTION_CONFIDENCE_SAMPLES =
            4L

        private const val DEMOTION_YIELD_REGRESSION =
            0.08

        private const val DEMOTION_ERROR_REGRESSION =
            0.05

        private const val DEMOTION_CONFIDENCE_REGRESSION =
            0.08

        private const val DEMOTION_QWEN_CORRECTION_REGRESSION =
            0.15

        private const val SEVERE_DEMOTION_YIELD_REGRESSION =
            0.20

        private const val SEVERE_DEMOTION_ERROR_REGRESSION =
            0.12

        private const val SEVERE_DEMOTION_CONFIDENCE_REGRESSION =
            0.15

        private const val SEVERE_DEMOTION_QWEN_CORRECTION_REGRESSION =
            0.25

        private const val MINIMUM_NEGATIVE_SIGNALS_FOR_DEMOTION =
            2

        private const val REPROMOTION_COOLDOWN_MS =
            24L * 60L * 60L * 1_000L

        private const val MINIMUM_REPROMOTION_SESSIONS =
            3L

        private const val MINIMUM_REPROMOTION_COMMITS =
            15L

        private const val MINIMUM_REPROMOTION_FINALS =
            4L
    }
}
