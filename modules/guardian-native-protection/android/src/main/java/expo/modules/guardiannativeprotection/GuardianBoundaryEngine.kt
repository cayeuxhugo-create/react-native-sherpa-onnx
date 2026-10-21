package expo.modules.guardiannativeprotection

import java.text.Normalizer
import java.util.Locale
import kotlin.math.sqrt

/**
 * Guardian Boundary Engine V3.3.
 *
 * Contrat de frontière V3.3 :
 * 1. ponctuation terminale = frontière linguistique prioritaire ;
 * 2. à défaut, silence acoustique crédible = frontière linguistique secondaire ;
 * 3. le nombre de mots est géré par GuardianWordTimelineEngine (24 mots actifs) ;
 * 4. le temps ne définit plus une phrase. Il ne sert ici qu'à un watchdog
 *    technique long pour renouveler un OnlineStream anormalement ancien ;
 * 5. cette classe ne coupe jamais la capture microphone.
 *
 * replayFromMs indique au recognizer à partir de quel instant une petite zone
 * de chevauchement doit être conservée pour amorcer le stream suivant. Ce
 * chevauchement protège le début d'une phrase B qui aurait déjà été capturé
 * pendant que la frontière de A devenait stable.
 */
internal class GuardianBoundaryEngine(
    private val configuration: Configuration,
) {
    data class Configuration(
        val minimumPauseMs: Long = 250L,
        val normalUnpunctuatedPauseMs: Long = 900L,
        val suspendedPauseMs: Long = 1_400L,
        val noLexicalProgressMs: Long = 6_000L,
        val technicalStreamWatchdogMs: Long = 45_000L,
        val speakerChangeMinimumConfidence: Float = 0.78f,
        val speakerChangeConfirmations: Int = 3,
        val speakerEvidenceMaximumGapMs: Long = 700L,
        val overlapReleaseHoldMs: Long = 500L,
    ) {
        init {
            require(minimumPauseMs in 100L..3_000L)
            require(normalUnpunctuatedPauseMs >= minimumPauseMs)
            require(suspendedPauseMs >= normalUnpunctuatedPauseMs)
            require(noLexicalProgressMs in 2_000L..30_000L)
            require(technicalStreamWatchdogMs in 15_000L..120_000L)
            require(speakerChangeMinimumConfidence in 0.0f..1.0f)
            require(speakerChangeConfirmations in 2..8)
            require(speakerEvidenceMaximumGapMs in 100L..2_000L)
        }
    }

    enum class BoundaryPriority {
        PUNCTUATION,
        SILENCE,
        TECHNICAL_WATCHDOG,
    }

    enum class SpeakerEvidenceType {
        SAME_SPEAKER,
        SPEAKER_CHANGE,
        OVERLAP_STARTED,
        OVERLAP_ENDED,
    }

    data class SpeakerEvidence(
        val type: SpeakerEvidenceType,
        val confidence: Float,
        val observedAtMs: Long,
    )

    sealed class Decision {
        data object Continue : Decision()

        data class Commit(
            val reason: String,
            val boundaryAtMs: Long,
            val replayFromMs: Long,
            val priority: BoundaryPriority,
        ) : Decision()

        data class ResetWithoutFinal(
            val reason: String,
        ) : Decision()
    }

    data class Snapshot(
        val currentText: String,
        val terminalPunctuationCandidate: String,
        val terminalPunctuationCandidateAgeMs: Long,
        val nativeSoftEndpointAgeMs: Long,
        val speakerChangeConfirmations: Int,
        val speakerChangeConfidence: Float,
        val overlapActive: Boolean,
    )

    private var lastText = ""
    private var lastLexicalProgressAtMs = 0L

    private var terminalCandidateText = ""
    private var terminalCandidateSinceMs = 0L
    private var terminalCandidateAmbiguous = false

    private var nativeSoftEndpointSinceMs = 0L

    private var speakerChangeCount = 0
    private var speakerChangeConfidence = 0.0f
    private var lastSpeakerChangeEvidenceAtMs = 0L

    private var overlapActive = false
    private var overlapEndedAtMs = 0L

    fun reset() {
        lastText = ""
        lastLexicalProgressAtMs = 0L
        terminalCandidateText = ""
        terminalCandidateSinceMs = 0L
        terminalCandidateAmbiguous = false
        nativeSoftEndpointSinceMs = 0L
        speakerChangeCount = 0
        speakerChangeConfidence = 0.0f
        lastSpeakerChangeEvidenceAtMs = 0L
        overlapActive = false
        overlapEndedAtMs = 0L
    }

    fun observeLexical(
        text: String,
        observedAtMs: Long,
    ) {
        val trimmed = text.trim()
        if (trimmed.isBlank()) return

        if (trimmed != lastText) {
            lastText = trimmed
            lastLexicalProgressAtMs = observedAtMs
            nativeSoftEndpointSinceMs = 0L
        }

        if (!hasTerminalSentencePunctuation(trimmed)) {
            clearTerminalCandidate()
            return
        }

        val ambiguous = isAmbiguousSentenceBoundary(trimmed)

        if (
            trimmed != terminalCandidateText ||
            ambiguous != terminalCandidateAmbiguous
        ) {
            terminalCandidateText = trimmed
            terminalCandidateSinceMs = observedAtMs
            terminalCandidateAmbiguous = ambiguous
        }
    }

    /** Le endpoint natif Sherpa est une preuve, jamais une autorité de commit. */
    fun noteNativeSoftEndpoint(observedAtMs: Long) {
        if (nativeSoftEndpointSinceMs <= 0L) {
            nativeSoftEndpointSinceMs = observedAtMs
        }
    }

    fun reportSpeakerEvidence(evidence: SpeakerEvidence) {
        when (evidence.type) {
            SpeakerEvidenceType.SAME_SPEAKER -> {
                speakerChangeCount = 0
                speakerChangeConfidence = 0.0f
                lastSpeakerChangeEvidenceAtMs = evidence.observedAtMs
            }

            SpeakerEvidenceType.SPEAKER_CHANGE -> {
                if (evidence.confidence < configuration.speakerChangeMinimumConfidence) {
                    return
                }

                val contiguous =
                    lastSpeakerChangeEvidenceAtMs > 0L &&
                        evidence.observedAtMs - lastSpeakerChangeEvidenceAtMs <=
                        configuration.speakerEvidenceMaximumGapMs

                speakerChangeCount = if (contiguous) speakerChangeCount + 1 else 1
                speakerChangeConfidence = maxOf(speakerChangeConfidence, evidence.confidence)
                lastSpeakerChangeEvidenceAtMs = evidence.observedAtMs
            }

            SpeakerEvidenceType.OVERLAP_STARTED -> {
                overlapActive = true
                overlapEndedAtMs = 0L
                speakerChangeCount = 0
                speakerChangeConfidence = 0.0f
            }

            SpeakerEvidenceType.OVERLAP_ENDED -> {
                overlapActive = false
                overlapEndedAtMs = evidence.observedAtMs
                speakerChangeCount = 0
                speakerChangeConfidence = 0.0f
            }
        }
    }

    fun evaluate(
        nowMs: Long,
        streamStartedAtMs: Long,
        lastAcousticSpeechAtMs: Long,
        currentText: String,
    ): Decision {
        val text = currentText.trim()

        val streamAgeMs =
            if (streamStartedAtMs > 0L) {
                (nowMs - streamStartedAtMs).coerceAtLeast(0L)
            } else {
                0L
            }

        val lexicalAgeMs =
            if (lastLexicalProgressAtMs > 0L) {
                (nowMs - lastLexicalProgressAtMs).coerceAtLeast(0L)
            } else {
                Long.MAX_VALUE
            }

        val acousticSilenceMs =
            if (lastAcousticSpeechAtMs > 0L) {
                (nowMs - lastAcousticSpeechAtMs).coerceAtLeast(0L)
            } else {
                Long.MAX_VALUE
            }

        val nativeEndpointAgeMs =
            if (nativeSoftEndpointSinceMs > 0L) {
                (nowMs - nativeSoftEndpointSinceMs).coerceAtLeast(0L)
            } else {
                0L
            }

        /*
         * PRIORITÉ 1 : ponctuation terminale.
         *
         * Aucun chronomètre n'est associé à la ponctuation. Dès que le texte
         * courant se termine par une ponctuation de phrase non ambiguë, cette
         * structure linguistique gagne. Le silence et le garde-fou ne sont
         * consultés que si cette règle ne peut pas conclure.
         */
        if (
            terminalCandidateText.isNotBlank() &&
            terminalCandidateText == text &&
            !terminalCandidateAmbiguous
        ) {
            val boundaryAtMs =
                terminalCandidateSinceMs
                    .takeIf { it > 0L }
                    ?: nowMs

            return Decision.Commit(
                reason = "TERMINAL_PUNCTUATION",
                boundaryAtMs = boundaryAtMs,
                replayFromMs =
                    (boundaryAtMs - PUNCTUATION_HANDOFF_MS)
                        .coerceAtLeast(0L),
                priority = BoundaryPriority.PUNCTUATION,
            )
        }

        /*
         * Les preuves de locuteur restent collectées pour le futur moteur
         * conversationnel, mais elles ne constituent plus un quatrième
         * décideur de frontière. Le contrat produit reste strictement :
         * ponctuation -> silence -> garde-fou.
         */

        /*
         * PRIORITÉ 2 : silence acoustique.
         *
         * Le délai est volontairement variable :
         * - texte suspendu/ellipse => pause longue ;
         * - endpoint Sherpa concordant => pause plus courte ;
         * - sinon pause normale.
         *
         * Le silence ne bat jamais une ponctuation terminale : cette branche
         * est évaluée seulement après la priorité 1.
         */
        if (
            text.isNotBlank() &&
            lastAcousticSpeechAtMs > 0L
        ) {
            val suspended =
                terminalCandidateAmbiguous ||
                    isLexicallySuspendedText(text)

            val nativeEndpointSupportsBoundary =
                nativeSoftEndpointSinceMs > 0L &&
                    nativeEndpointAgeMs >= configuration.minimumPauseMs

            val requiredPauseMs =
                when {
                    suspended ->
                        configuration.suspendedPauseMs

                    nativeEndpointSupportsBoundary ->
                        maxOf(
                            configuration.minimumPauseMs,
                            minOf(
                                configuration.normalUnpunctuatedPauseMs,
                                NATIVE_ENDPOINT_ACCELERATED_PAUSE_MS,
                            ),
                        )

                    else ->
                        configuration.normalUnpunctuatedPauseMs
                }

            val requiredLexicalStabilityMs =
                if (suspended) {
                    SUSPENDED_LEXICAL_STABILITY_MS
                } else {
                    NORMAL_LEXICAL_STABILITY_MS
                }

            if (
                acousticSilenceMs >= requiredPauseMs &&
                lexicalAgeMs >= requiredLexicalStabilityMs
            ) {
                val reason =
                    when {
                        suspended ->
                            "SUSPENDED_TEXT_PLUS_LONG_PAUSE"

                        nativeEndpointSupportsBoundary ->
                            "NATIVE_ENDPOINT_PLUS_SILENCE"

                        else ->
                            "UNPUNCTUATED_TEXT_PLUS_PAUSE"
                    }

                return Decision.Commit(
                    reason = reason,
                    boundaryAtMs = lastAcousticSpeechAtMs,
                    replayFromMs =
                        (lastAcousticSpeechAtMs - FRAME_EDGE_SAFETY_MS)
                            .coerceAtLeast(0L),
                    priority = BoundaryPriority.SILENCE,
                )
            }
        }

        /*
         * Nemotron V6 - burst acoustique sans lexical.
         *
         * Un OnlineStream qui a réellement observé de la parole mais n'a
         * produit aucun mot ne doit pas absorber les prises de parole suivantes
         * pendant 45 s. Après un silence confirmé suffisamment long, on ouvre
         * simplement un stream propre, sans FINAL et sans Rescue/Qwen.
         *
         * On réutilise suspendedPauseMs : aucun nouveau seuil n'est introduit,
         * et une respiration normale reste protégée.
         */
        if (
            text.isBlank() &&
            lastAcousticSpeechAtMs > 0L &&
            acousticSilenceMs >= configuration.suspendedPauseMs
        ) {
            return Decision.ResetWithoutFinal(
                "ACOUSTIC_BURST_WITHOUT_LEXICAL"
            )
        }

        /*
         * WATCHDOG TECHNIQUE ULTIME.
         *
         * Ce délai ne signifie JAMAIS « fin de phrase ». La taille linguistique
         * est contrôlée par GuardianWordTimelineEngine (maxActiveWords=24).
         * Ici nous renouvelons seulement un OnlineStream devenu anormalement
         * ancien afin de borner son état interne. WordTimeline doit traiter
         * cette frontière comme TECHNICAL_ROLLOVER et continuer le même contexte.
         */
        if (streamAgeMs >= configuration.technicalStreamWatchdogMs) {
            /*
             * Soft Voice V3 : le watchdog protège la santé du OnlineStream,
             * jamais la grammaire. Une voix douce peut avancer lentement ;
             * tant que du lexical Nemotron a progressé récemment, on diffère
             * le rollover. On réutilise noLexicalProgressMs : aucun nouveau
             * seuil acoustique ou temporel n'est introduit.
             */
            val lexicalActivityProtected =
                text.isNotBlank() &&
                    lastLexicalProgressAtMs > 0L &&
                    lexicalAgeMs < configuration.noLexicalProgressMs

            if (lexicalActivityProtected) {
                return Decision.Continue
            }

            return if (text.isNotBlank()) {
                Decision.Commit(
                    reason = "TECHNICAL_STREAM_WATCHDOG_ROLLOVER",
                    boundaryAtMs = nowMs,
                    replayFromMs =
                        (nowMs - TECHNICAL_WATCHDOG_HANDOFF_MS)
                            .coerceAtLeast(0L),
                    priority = BoundaryPriority.TECHNICAL_WATCHDOG,
                )
            } else {
                Decision.ResetWithoutFinal("TECHNICAL_EMPTY_STREAM_WATCHDOG")
            }
        }

        return Decision.Continue
    }

    fun snapshot(nowMs: Long): Snapshot {
        return Snapshot(
            currentText = lastText,
            terminalPunctuationCandidate = terminalCandidateText,
            terminalPunctuationCandidateAgeMs =
                if (terminalCandidateSinceMs > 0L) {
                    (nowMs - terminalCandidateSinceMs).coerceAtLeast(0L)
                } else 0L,
            nativeSoftEndpointAgeMs =
                if (nativeSoftEndpointSinceMs > 0L) {
                    (nowMs - nativeSoftEndpointSinceMs).coerceAtLeast(0L)
                } else 0L,
            speakerChangeConfirmations = speakerChangeCount,
            speakerChangeConfidence = speakerChangeConfidence,
            overlapActive = overlapActive,
        )
    }

    private fun clearTerminalCandidate() {
        terminalCandidateText = ""
        terminalCandidateSinceMs = 0L
        terminalCandidateAmbiguous = false
    }

    private fun hasTerminalSentencePunctuation(text: String): Boolean {
        if (text.isBlank()) return false
        return text.endsWith(".") || text.endsWith("!") ||
            text.endsWith("?") || text.endsWith("…")
    }

    private fun isAmbiguousSentenceBoundary(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.endsWith("...") || trimmed.endsWith("…")) return true
        return isLexicallySuspendedText(
            trimmed.trimEnd('.', '!', '?', '…').trim()
        )
    }

    private fun isLexicallySuspendedText(text: String): Boolean {
        val words =
            normalizeForComparison(text.trimEnd('.', '!', '?', '…'))
                .split(' ')
                .filter { it.isNotBlank() }

        if (words.isEmpty()) return true

        val lastWord = words.last()
        val lastBigram = if (words.size >= 2) words.takeLast(2).joinToString(" ") else ""

        return lastWord in SUSPENDED_TRAILING_WORDS ||
            lastBigram in SUSPENDED_TRAILING_BIGRAMS
    }

    private fun normalizeForComparison(text: String): String {
        val normalized = Normalizer.normalize(text, Normalizer.Form.NFD)
        val withoutDiacritics = normalized.replace(DIACRITICS_REGEX, "")

        return withoutDiacritics
            .lowercase(Locale.ROOT)
            .replace(NON_LEXICAL_REGEX, " ")
            .trim()
            .replace(WHITESPACE_REGEX, " ")
    }

    companion object {
        private const val FRAME_EDGE_SAFETY_MS = 75L
        private const val PUNCTUATION_HANDOFF_MS = 400L
        private const val TECHNICAL_WATCHDOG_HANDOFF_MS = 400L
        private const val NATIVE_ENDPOINT_ACCELERATED_PAUSE_MS = 650L
        private const val NORMAL_LEXICAL_STABILITY_MS = 450L
        private const val SUSPENDED_LEXICAL_STABILITY_MS = 900L

        private val SUSPENDED_TRAILING_WORDS =
            setOf(
                "et", "ou", "mais", "donc", "or", "ni", "car",
                "que", "qui", "dont", "de", "du", "des",
                "le", "la", "les", "un", "une",
                "mon", "ma", "mes", "ton", "ta", "tes",
                "son", "sa", "ses", "notre", "nos",
                "votre", "vos", "leur", "leurs",
                "ce", "cet", "cette", "ces",
                "au", "aux", "a", "en", "dans", "sur", "sous",
                "avec", "sans", "pour", "vers", "chez",
                "je", "tu", "parce", "puisque", "lorsque",
            )

        private val SUSPENDED_TRAILING_BIGRAMS =
            setOf(
                "et je", "et tu", "et il", "et elle", "et on",
                "ou je", "ou tu", "mais je", "mais tu", "parce que",
            )

        private val DIACRITICS_REGEX = Regex("\\p{Mn}+")
        private val NON_LEXICAL_REGEX = Regex("[^\\p{L}\\p{N}']+")
        private val WHITESPACE_REGEX = Regex("\\s+")
    }
}

/**
 * Détecteur A -> B basé sur des embeddings déjà calculés par un worker.
 * Il ne calcule aucun embedding lui-même et ne détecte pas l'overlap.
 */
internal class GuardianSpeakerBoundaryTracker(
    private val configuration: Configuration = Configuration(),
) {
    data class Configuration(
        val sameSpeakerSimilarity: Float = 0.76f,
        val strongChangeSimilarity: Float = 0.58f,
        val candidateConfirmations: Int = 3,
        val centroidLearningRate: Float = 0.12f,
    ) {
        init {
            require(strongChangeSimilarity in 0.0f..1.0f)
            require(sameSpeakerSimilarity in 0.0f..1.0f)
            require(strongChangeSimilarity < sameSpeakerSimilarity)
            require(candidateConfirmations in 2..8)
            require(centroidLearningRate in 0.01f..0.5f)
        }
    }

    data class Observation(
        val speakerChanged: Boolean,
        val confidence: Float,
        val similarity: Float,
    )

    private var activeSpeaker: FloatArray? = null
    private var candidateSpeaker: FloatArray? = null
    private var candidateCount = 0

    fun reset() {
        activeSpeaker = null
        candidateSpeaker = null
        candidateCount = 0
    }

    fun observe(embedding: FloatArray): Observation {
        if (embedding.isEmpty()) {
            return Observation(false, 0.0f, 1.0f)
        }

        val normalized = normalizeEmbedding(embedding)
        val active = activeSpeaker

        if (active == null) {
            activeSpeaker = normalized
            return Observation(false, 1.0f, 1.0f)
        }

        val similarity = cosineSimilarity(active, normalized)

        if (similarity >= configuration.sameSpeakerSimilarity) {
            activeSpeaker = blendAndNormalize(
                active,
                normalized,
                configuration.centroidLearningRate,
            )
            candidateSpeaker = null
            candidateCount = 0
            return Observation(false, similarity, similarity)
        }

        if (similarity > configuration.strongChangeSimilarity) {
            candidateSpeaker = null
            candidateCount = 0
            return Observation(false, changeConfidence(similarity), similarity)
        }

        val candidate = candidateSpeaker
        candidateSpeaker =
            if (candidate == null) normalized
            else blendAndNormalize(candidate, normalized, 0.5f)

        candidateCount += 1

        if (candidateCount < configuration.candidateConfirmations) {
            return Observation(false, changeConfidence(similarity), similarity)
        }

        activeSpeaker = candidateSpeaker
        candidateSpeaker = null
        candidateCount = 0

        return Observation(
            speakerChanged = true,
            confidence = changeConfidence(similarity),
            similarity = similarity,
        )
    }

    private fun changeConfidence(similarity: Float): Float {
        val span =
            (configuration.sameSpeakerSimilarity -
                configuration.strongChangeSimilarity)
                .coerceAtLeast(0.01f)

        return (
            (configuration.sameSpeakerSimilarity - similarity) / span
            )
            .coerceIn(0.0f, 1.0f)
    }

    private fun normalizeEmbedding(source: FloatArray): FloatArray {
        var squareSum = 0.0
        for (value in source) squareSum += value.toDouble() * value.toDouble()
        val norm = sqrt(squareSum).toFloat()
        if (norm <= 1e-8f) return source.copyOf()
        return FloatArray(source.size) { index -> source[index] / norm }
    }

    private fun blendAndNormalize(
        active: FloatArray,
        incoming: FloatArray,
        alpha: Float,
    ): FloatArray {
        if (active.size != incoming.size) return incoming.copyOf()
        val mixed = FloatArray(active.size) { index ->
            active[index] * (1.0f - alpha) + incoming[index] * alpha
        }
        return normalizeEmbedding(mixed)
    }

    private fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        if (a.isEmpty() || b.isEmpty() || a.size != b.size) return 0.0f

        var dot = 0.0f
        var normA = 0.0f
        var normB = 0.0f
        for (index in a.indices) {
            val x = a[index]
            val y = b[index]
            dot += x * y
            normA += x * x
            normB += y * y
        }

        val denominator = sqrt(normA) * sqrt(normB)
        if (denominator <= 1e-8f) return 0.0f
        return (dot / denominator).coerceIn(-1.0f, 1.0f)
    }
}
