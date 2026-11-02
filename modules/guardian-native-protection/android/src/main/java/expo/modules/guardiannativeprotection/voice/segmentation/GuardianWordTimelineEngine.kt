package expo.modules.guardiannativeprotection

import java.text.Normalizer
import java.util.Locale
import kotlin.math.min

/**
 * Guardian Voice Lexical Timeline V3.
 *
 * Principe :
 * - l'audio et Nemotron restent continus ;
 * - les OnlineStream Sherpa ne définissent plus une phrase produit ;
 * - chaque hypothèse est ramenée à des mots chronologiques ;
 * - une fenêtre active de [maxActiveWords] mots sert à reconstruire les phrases
 *   et le contexte ;
 * - Qwen corrige les mêmes mots via l'originId du segment technique ;
 * - le temps n'est qu'un watchdog ultime, jamais la définition normale d'une
 *   phrase.
 *
 * Cette classe ne capture aucun son et ne lance aucun modèle ONNX.
 */
class GuardianWordTimelineEngine(
    private val configuration: Configuration = Configuration(),
    private val listener: Listener? = null,
) {

    data class Configuration(
        // Garde-fou lexical principal : la mémoire active est bornée en mots, pas en secondes.
        val maxActiveWords: Int = 24,
        val correctionHistoryWords: Int = 96,
        val correctionHistorySegments: Int = 32,
        val correctionHistoryUnits: Int = 32,
        val stableTrailingWords: Int = 2,
        val maxOpenContextAgeMs: Long = 45_000L,
        val uncertainWordLogProbThreshold: Float = -0.45f,
    ) {
        init {
            require(maxActiveWords in 8..64)
            require(correctionHistoryWords >= maxActiveWords)
            require(correctionHistoryWords <= 256)
            require(correctionHistorySegments in 8..128)
            require(correctionHistoryUnits in 8..128)
            require(stableTrailingWords in 1..4)
            require(maxOpenContextAgeMs in 15_000L..120_000L)
            require(uncertainWordLogProbThreshold.isFinite())
        }
    }

    enum class WordState {
        PROVISIONAL,
        STABLE,
        RESOLVING,
        RESOLVED,
        CORRECTED,
        COMMITTED,
    }

    enum class WordSource {
        QWEN_PRIMARY_PARTIAL,
        QWEN_PRIMARY_FINAL,
        QWEN_LOCAL_RESOLUTION,
        QWEN_CORRECTION,
    }

    enum class UnitType {
        NATURAL_PHRASE,
        CONTEXT_CHUNK,
    }

    enum class BoundaryKind {
        PUNCTUATION,
        SILENCE,
        WORD_WINDOW,
        TEMPORAL_WATCHDOG,
        TECHNICAL_ROLLOVER,
        UNKNOWN,
    }


    /**
     * Preuve acoustique d'un vrai mot reconstruite à partir des tokens Sherpa.
     * audioStart/End sont sur la même horloge elapsedRealtime que les frames
     * AudioRecord. Les séquences sont des ancres vers le ring PCM, jamais des
     * copies audio stockées dans le mot.
     */
    data class AcousticWordEvidence(
        val wordIndex: Int,
        val text: String,
        val normalizedText: String,
        val audioStartMs: Long,
        val audioEndMs: Long,
        val startSequence: Long = 0L,
        val endSequence: Long = 0L,
        val averageLogProb: Float? = null,
    )

    data class WordResolutionRequest(
        val wordId: Long,
        val segmentId: Long,
        val wordIndex: Int,
        val originalWord: String,
        val normalizedWord: String,
        val leftAnchor: String,
        val rightAnchor: String,
        val contextStartMs: Long,
        val contextEndMs: Long,
        val startSequence: Long,
        val endSequence: Long,
        val averageLogProb: Float,
        val observationCount: Int,
        val doubtReason: String,
    )

    data class Word(
        val wordId: Long,
        val text: String,
        val normalizedText: String,
        val state: WordState,
        val source: WordSource,
        val segmentId: Long,
        val originId: Long?,
        val firstSeenAtMs: Long,
        val lastUpdatedAtMs: Long,
        val confidence: Float?,
        // Nombre d'observations Nemotron cohérentes de CE mot dans les PARTIAL.
        // Qwen ne doit jamais être réveillé sur une hypothèse apparue une seule fois.
        val primaryObservationCount: Int = 1,
        val audioStartMs: Long = 0L,
        val audioEndMs: Long = 0L,
        val startSequence: Long = 0L,
        val endSequence: Long = 0L,
        val acousticLogProb: Float? = null,
        val primaryNormalizedText: String = "",
    )

    data class Unit(
        val unitId: Long,
        val type: UnitType,
        val boundary: BoundaryKind,
        val wordIds: List<Long>,
        val text: String,
        val createdAtMs: Long,
        val confidence: Float? = null,
        val corrected: Boolean = false,
    )

    data class ContextSnapshot(
        val words: List<Word>,
        val text: String,
        val wordCount: Int,
        val openWordCount: Int,
        val lastNaturalPhrase: String?,
    )

    data class CorrectionResult(
        val applied: Boolean,
        val changedWords: Int,
        val insertedWords: Int,
        val deletedWords: Int,
        val correctedUnits: List<Unit>,
    )

    interface Listener {
        fun onUnitReady(unit: Unit) = Unit
        fun onUnitCorrected(unit: Unit) = Unit
        fun onContextUpdated(snapshot: ContextSnapshot) = Unit
        fun onWordNeedsResolution(request: WordResolutionRequest) = Unit
    }

    private data class SegmentState(
        val segmentId: Long,
        val wordIds: MutableList<Long> = mutableListOf(),
        var originId: Long? = null,
        var finalized: Boolean = false,
        var boundary: BoundaryKind = BoundaryKind.UNKNOWN,
    )

    private sealed class Edit {
        data class Keep(val oldIndex: Int, val newIndex: Int) : Edit()
        data class Substitute(val oldIndex: Int, val newIndex: Int) : Edit()
        data class Delete(val oldIndex: Int) : Edit()
        data class Insert(val newIndex: Int) : Edit()
    }

    private val lock = Any()
    private var nextWordId = 1L
    private var nextUnitId = 1L

    /*
     * Historique lexical court. Il peut dépasser 24 mots uniquement pour
     * permettre à une correction Qwen tardive de retrouver son originId.
     * La mémoire CONTEXTUELLE publiée, elle, reste strictement bornée à 24.
     */
    private val timeline = mutableListOf<Word>()
    private val segments = linkedMapOf<Long, SegmentState>()
    private val originToSegmentId = mutableMapOf<Long, Long>()

    /* Mots RESOLVED/CORRECTED appartenant à l'unité linguistique en cours. */
    private val openUnitWordIds = mutableListOf<Long>()
    private var openUnitStartedAtMs = 0L

    private val emittedUnits = linkedMapOf<Long, Unit>()
    private val wordToUnitIds = mutableMapOf<Long, MutableSet<Long>>()

    private var lastNaturalPhrase: String? = null
    private val resolutionRequestedWordIds = mutableSetOf<Long>()

    fun reset() {
        synchronized(lock) {
            timeline.clear()
            segments.clear()
            originToSegmentId.clear()
            openUnitWordIds.clear()
            emittedUnits.clear()
            wordToUnitIds.clear()
            nextWordId = 1L
            nextUnitId = 1L
            openUnitStartedAtMs = 0L
            lastNaturalPhrase = null
            resolutionRequestedWordIds.clear()
        }
    }

    /**
     * Observe l'hypothèse streaming courante d'un OnlineStream technique.
     * Les révisions du suffixe Sherpa ne créent pas de doublons : le préfixe
     * lexical commun conserve ses wordId, le suffixe est remplacé.
     */
    fun observePartial(
        segmentId: Long,
        text: String,
        observedAtMs: Long,
        confidence: Float? = null,
        evidence: List<AcousticWordEvidence> = emptyList(),
        source: WordSource = WordSource.QWEN_PRIMARY_PARTIAL,
    ): ContextSnapshot {
        synchronized(lock) {
            val tokens = tokenizeWords(text)
            if (tokens.isEmpty()) return snapshotLocked()

            val segment = segments.getOrPut(segmentId) { SegmentState(segmentId) }
            if (segment.finalized) return snapshotLocked()

            reconcileDraftLocked(
                segment = segment,
                tokens = tokens,
                observedAtMs = observedAtMs,
                confidence = confidence,
                evidence = evidence,
                primarySource = source,
            )

            collectWordResolutionRequestsLocked(segment)
            publishContextLocked()
            return snapshotLocked()
        }
    }

    /**
     * Fige le segment technique Qwen. Un FINAL technique n'est PAS
     * nécessairement une phrase : un TECHNICAL_STREAM_WATCHDOG_ROLLOVER
     * reste un simple découpage moteur et la timeline continue ensuite.
     */
    fun observeFinal(
        segmentId: Long,
        originId: Long?,
        text: String,
        boundaryReason: String?,
        observedAtMs: Long,
        confidence: Float? = null,
        evidence: List<AcousticWordEvidence> = emptyList(),
        recovered: Boolean = false,
        finalSource: WordSource = WordSource.QWEN_PRIMARY_FINAL,
    ): List<Unit> {
        synchronized(lock) {
            val tokens = tokenizeWords(text)
            if (tokens.isEmpty()) return emptyList()

            val segment = segments.getOrPut(segmentId) { SegmentState(segmentId) }

            reconcileDraftLocked(
                segment = segment,
                tokens = tokens,
                observedAtMs = observedAtMs,
                confidence = confidence,
                evidence = evidence,
                primarySource = WordSource.QWEN_PRIMARY_PARTIAL,
            )

            val boundary = classifyBoundary(boundaryReason)

            segment.originId = originId
            segment.finalized = true
            segment.boundary = boundary
            if (originId != null) originToSegmentId[originId] = segmentId

            val resolvedIds = mutableListOf<Long>()
            segment.wordIds.forEach { wordId ->
                val index = indexOfWordLocked(wordId)
                if (index < 0) return@forEach
                val current = timeline[index]
                val belongsToCurrentSegment = current.segmentId == segmentId
                val updated = current.copy(
                    state = WordState.RESOLVED,
                    source = if (belongsToCurrentSegment) {
                        finalSource
                    } else {
                        current.source
                    },
                    originId = if (belongsToCurrentSegment) originId else current.originId,
                    lastUpdatedAtMs = observedAtMs,
                    confidence = confidence ?: current.confidence,
                )
                timeline[index] = updated
                resolvedIds += wordId
                if (wordId !in openUnitWordIds) {
                    if (openUnitWordIds.isEmpty()) openUnitStartedAtMs = observedAtMs
                    openUnitWordIds += wordId
                }
            }

            val emitted = mutableListOf<Unit>()

            /*
             * Une ponctuation peut exister au milieu d'un FINAL. On la traite
             * mot par mot au lieu de croire que le segment technique == phrase.
             */
            emitted += closeAtTerminalPunctuationLocked(observedAtMs)

            if (
                boundary == BoundaryKind.SILENCE &&
                openUnitWordIds.isNotEmpty()
            ) {
                emitted += emitOpenUnitLocked(
                    type = UnitType.NATURAL_PHRASE,
                    boundary = BoundaryKind.SILENCE,
                    nowMs = observedAtMs,
                )
            }

            /*
             * Le garde-fou lexical : 24 mots résolus sans frontière naturelle
             * deviennent un CONTEXT_CHUNK. Aucun mot n'est coupé et aucune
             * conclusion linguistique artificielle n'est inventée.
             */
            emitted += enforceWordWindowGuardLocked(observedAtMs)

            trimHistoryLocked()
            publishContextLocked()
            return emitted
        }
    }

    /**
     * Résolution locale d'un mot douteux. Qwen reçoit le contexte acoustique
     * mais n'a le droit de modifier que ce wordId.
     */
    fun completeWordResolution(
        wordId: Long,
        originalWord: String,
        replacementWord: String?,
        observedAtMs: Long,
    ): Boolean {
        synchronized(lock) {
            val index = indexOfWordLocked(wordId)
            if (index < 0) return false
            val current = timeline[index]
            if (normalizeLexical(current.text) != normalizeLexical(originalWord)) return false

            val replacement = replacementWord?.trim().orEmpty()
            if (replacement.isBlank() || normalizeLexical(replacement) == current.normalizedText) {
                timeline[index] = current.copy(
                    state = if (current.state == WordState.RESOLVING) WordState.STABLE else current.state,
                    lastUpdatedAtMs = observedAtMs,
                )
                publishContextLocked()
                return true
            }

            timeline[index] = current.copy(
                text = replacement,
                normalizedText = normalizeLexical(replacement),
                state = WordState.CORRECTED,
                source = WordSource.QWEN_LOCAL_RESOLUTION,
                lastUpdatedAtMs = observedAtMs,
            )

            wordToUnitIds[wordId]?.toList()?.forEach { unitId ->
                val old = emittedUnits[unitId] ?: return@forEach
                val updated = old.copy(
                    text = buildTextForIdsLocked(old.wordIds),
                    corrected = true,
                )
                emittedUnits[unitId] = updated
                listener?.onUnitCorrected(updated)
            }
            publishContextLocked()
            return true
        }
    }

    /** Si le worker Qwen refuse le job, le mot redevient éligible plus tard. */
    fun releaseWordResolutionRequest(wordId: Long) {
        synchronized(lock) {
            resolutionRequestedWordIds.remove(wordId)
            val index = indexOfWordLocked(wordId)
            if (index >= 0 && timeline[index].state == WordState.RESOLVING) {
                timeline[index] = timeline[index].copy(state = WordState.STABLE)
            }
        }
    }

    /**
     * Applique une correction Qwen au même originId. L'alignement mot-à-mot
     * préserve les wordId sur les KEEP/SUBSTITUTE, crée un id sur INSERT et
     * retire uniquement le mot concerné sur DELETE.
     */
    fun applyCorrection(
        originId: Long?,
        correctedText: String,
        observedAtMs: Long,
        confidence: Float? = null,
    ): CorrectionResult {
        if (originId == null) return CorrectionResult(false, 0, 0, 0, emptyList())

        synchronized(lock) {
            val segmentId = originToSegmentId[originId]
                ?: return CorrectionResult(false, 0, 0, 0, emptyList())
            val segment = segments[segmentId]
                ?: return CorrectionResult(false, 0, 0, 0, emptyList())

            val originalWords = segment.wordIds.mapNotNull { id -> wordByIdLocked(id) }
            val correctedTokens = tokenizeWords(correctedText)
            if (originalWords.isEmpty() || correctedTokens.isEmpty()) {
                return CorrectionResult(false, 0, 0, 0, emptyList())
            }

            val edits = align(
                originalWords.map { it.normalizedText },
                correctedTokens.map { normalizeLexical(it) },
            )

            val replacement = mutableListOf<Word>()
            var changed = 0
            var inserted = 0
            var deleted = 0

            edits.forEach { edit ->
                when (edit) {
                    is Edit.Keep -> {
                        replacement += originalWords[edit.oldIndex]
                    }

                    is Edit.Substitute -> {
                        val old = originalWords[edit.oldIndex]
                        val newText = correctedTokens[edit.newIndex]
                        replacement += old.copy(
                            text = newText,
                            normalizedText = normalizeLexical(newText),
                            state = WordState.CORRECTED,
                            source = WordSource.QWEN_CORRECTION,
                            lastUpdatedAtMs = observedAtMs,
                            confidence = confidence ?: old.confidence,
                        )
                        changed++
                    }

                    is Edit.Delete -> {
                        deleted++
                    }

                    is Edit.Insert -> {
                        val text = correctedTokens[edit.newIndex]
                        replacement += Word(
                            wordId = nextWordId++,
                            text = text,
                            normalizedText = normalizeLexical(text),
                            state = WordState.CORRECTED,
                            source = WordSource.QWEN_CORRECTION,
                            segmentId = segmentId,
                            originId = originId,
                            firstSeenAtMs = observedAtMs,
                            lastUpdatedAtMs = observedAtMs,
                            confidence = confidence,
                            primaryNormalizedText = normalizeLexical(text),
                        )
                        inserted++
                    }
                }
            }

            replaceSegmentWordsLocked(segment, originalWords, replacement)

            val affectedUnits = linkedSetOf<Long>()
            originalWords.forEach { word ->
                wordToUnitIds[word.wordId]?.let { affectedUnits += it }
            }
            replacement.forEach { word ->
                wordToUnitIds[word.wordId]?.let { affectedUnits += it }
            }

            val correctedUnits = affectedUnits.mapNotNull { unitId ->
                val oldUnit = emittedUnits[unitId] ?: return@mapNotNull null
                val newText = buildTextForIdsLocked(oldUnit.wordIds)
                if (newText.isBlank() || newText == oldUnit.text) return@mapNotNull null
                val updated = oldUnit.copy(text = newText, corrected = true)
                emittedUnits[unitId] = updated
                listener?.onUnitCorrected(updated)
                updated
            }

            trimHistoryLocked()
            publishContextLocked()

            return CorrectionResult(
                applied = true,
                changedWords = changed,
                insertedWords = inserted,
                deletedWords = deleted,
                correctedUnits = correctedUnits,
            )
        }
    }

    /**
     * Garde-fou temporel ultime. Il ne définit pas une phrase : il publie un
     * CONTEXT_CHUNK si une unité ouverte reste vivante anormalement longtemps.
     */
    fun tick(nowMs: Long): Unit? {
        synchronized(lock) {
            if (
                openUnitWordIds.isEmpty() ||
                openUnitStartedAtMs <= 0L ||
                nowMs - openUnitStartedAtMs < configuration.maxOpenContextAgeMs
            ) {
                return null
            }

            val unit = emitOpenUnitLocked(
                type = UnitType.CONTEXT_CHUNK,
                boundary = BoundaryKind.TEMPORAL_WATCHDOG,
                nowMs = nowMs,
            )
            publishContextLocked()
            return unit
        }
    }

    fun getSnapshot(): ContextSnapshot = synchronized(lock) { snapshotLocked() }

    private fun reconcileDraftLocked(
        segment: SegmentState,
        tokens: List<String>,
        observedAtMs: Long,
        confidence: Float?,
        evidence: List<AcousticWordEvidence>,
        primarySource: WordSource,
    ) {
        val evidenceByIndex = evidence.associateBy { it.wordIndex }

        if (segment.wordIds.isEmpty()) {
            seedCrossSegmentOverlapLocked(
                segment = segment,
                tokens = tokens,
                evidenceByIndex = evidenceByIndex,
            )
        }

        val existing = segment.wordIds.mapNotNull { wordByIdLocked(it) }
        val normalizedNew = tokens.map { normalizeLexical(it) }

        var commonPrefix = 0
        while (
            commonPrefix < existing.size &&
            commonPrefix < normalizedNew.size &&
            primaryComparisonText(existing[commonPrefix]) == normalizedNew[commonPrefix]
        ) {
            commonPrefix++
        }

        /* Un suffixe PROVISIONAL/STABLE peut être révisé par Sherpa. */
        val removableIds = existing.drop(commonPrefix).map { it.wordId }.toSet()
        if (removableIds.isNotEmpty()) {
            timeline.removeAll { it.wordId in removableIds && it.state <= WordState.RESOLVING }
            segment.wordIds.removeAll { it in removableIds }
            removableIds.forEach { resolutionRequestedWordIds.remove(it) }
        }

        /* Met à jour le préfixe commun et stabilise les mots ayant du recul. */
        segment.wordIds.take(commonPrefix).forEachIndexed { index, wordId ->
            val timelineIndex = indexOfWordLocked(wordId)
            if (timelineIndex < 0) return@forEachIndexed
            val current = timeline[timelineIndex]
            val shouldBeStable = index <= tokens.lastIndex - configuration.stableTrailingWords
            val acoustic = evidenceByIndex[index]
            val preserveResolvedCorrection =
                current.source == WordSource.QWEN_LOCAL_RESOLUTION ||
                    current.source == WordSource.QWEN_CORRECTION
            timeline[timelineIndex] = current.copy(
                text = if (preserveResolvedCorrection) current.text else tokens[index],
                normalizedText = if (preserveResolvedCorrection) current.normalizedText else normalizedNew[index],
                primaryNormalizedText = normalizedNew[index],
                source = if (preserveResolvedCorrection) current.source else primarySource,
                state = if (shouldBeStable && current.state == WordState.PROVISIONAL) {
                    WordState.STABLE
                } else {
                    current.state
                },
                lastUpdatedAtMs = observedAtMs,
                confidence = confidence ?: current.confidence,
                primaryObservationCount =
                    (current.primaryObservationCount + 1)
                        .coerceAtMost(MAX_PRIMARY_OBSERVATION_COUNT),
                audioStartMs = acoustic?.audioStartMs?.takeIf { it > 0L } ?: current.audioStartMs,
                audioEndMs = acoustic?.audioEndMs?.takeIf { it > 0L } ?: current.audioEndMs,
                startSequence = acoustic?.startSequence?.takeIf { it > 0L } ?: current.startSequence,
                endSequence = acoustic?.endSequence?.takeIf { it > 0L } ?: current.endSequence,
                acousticLogProb = acoustic?.averageLogProb ?: current.acousticLogProb,
            )
        }

        for (index in commonPrefix until tokens.size) {
            val token = tokens[index]
            val state = if (index <= tokens.lastIndex - configuration.stableTrailingWords) {
                WordState.STABLE
            } else {
                WordState.PROVISIONAL
            }

            val acoustic = evidenceByIndex[index]
            val word = Word(
                wordId = nextWordId++,
                text = token,
                normalizedText = normalizeLexical(token),
                state = state,
                source = primarySource,
                segmentId = segment.segmentId,
                originId = segment.originId,
                firstSeenAtMs = observedAtMs,
                lastUpdatedAtMs = observedAtMs,
                confidence = confidence,
                primaryObservationCount = 1,
                audioStartMs = acoustic?.audioStartMs ?: 0L,
                audioEndMs = acoustic?.audioEndMs ?: 0L,
                startSequence = acoustic?.startSequence ?: 0L,
                endSequence = acoustic?.endSequence ?: 0L,
                acousticLogProb = acoustic?.averageLogProb,
                primaryNormalizedText = normalizeLexical(token),
            )
            timeline += word
            segment.wordIds += word.wordId
        }
    }

    /**
     * Temporal/Lexical V3.2 - déduplication du handoff A -> B.
     *
     * Un rollover technique rejoue volontairement quelques centaines de
     * millisecondes dans le OnlineStream suivant. Nemotron peut donc réémettre
     * un ou plusieurs mots déjà présents à la fin du segment précédent.
     *
     * On réutilise les mêmes wordId lorsque :
     * - le segment précédent est réellement un TECHNICAL_ROLLOVER ;
     * - son suffixe lexical est identique au préfixe du nouveau segment ;
     * - lorsque les timestamps existent, les deux hypothèses pointent vers la
     *   même zone acoustique.
     *
     * Une frontière naturelle (ponctuation/silence) n'est jamais dédupliquée
     * avec la phrase suivante : une répétition volontaire reste donc intacte.
     */
    private fun seedCrossSegmentOverlapLocked(
        segment: SegmentState,
        tokens: List<String>,
        evidenceByIndex: Map<Int, AcousticWordEvidence>,
    ) {
        if (tokens.isEmpty() || segment.wordIds.isNotEmpty()) return

        val orderedSegments = segments.values.toList()
        val currentIndex = orderedSegments.indexOfFirst { it.segmentId == segment.segmentId }
        if (currentIndex <= 0) return

        val previous = orderedSegments
            .subList(0, currentIndex)
            .lastOrNull { it.finalized }
            ?: return

        if (previous.boundary != BoundaryKind.TECHNICAL_ROLLOVER) return

        val previousWords = previous.wordIds.mapNotNull { wordByIdLocked(it) }
        if (previousWords.isEmpty()) return

        val normalizedNew = tokens.map { normalizeLexical(it) }
        val maximumOverlap = minOf(
            MAX_CROSS_SEGMENT_OVERLAP_WORDS,
            previousWords.size,
            normalizedNew.size,
        )

        for (overlapSize in maximumOverlap downTo 1) {
            val previousSuffix = previousWords.takeLast(overlapSize)
            val lexicalMatch = previousSuffix.indices.all { index ->
                primaryComparisonText(previousSuffix[index]) == normalizedNew[index]
            }
            if (!lexicalMatch) continue

            val temporalMatch = crossSegmentTemporalMatch(
                previousSuffix = previousSuffix,
                evidenceByIndex = evidenceByIndex,
            )

            /*
             * Avec des timestamps acoustiques, ils doivent confirmer le replay.
             * Sans timestamps (tests/fallback), on exige au moins deux mots afin
             * de ne pas effacer une répétition naturelle d'un mot fréquent.
             */
            if (temporalMatch == false) continue
            if (temporalMatch == null && overlapSize < 2) continue

            segment.wordIds += previousSuffix.map { it.wordId }
            return
        }
    }

    /**
     * true  = mêmes instants acoustiques ; false = instants incompatibles ;
     * null  = pas assez de timestamps pour conclure.
     */
    private fun crossSegmentTemporalMatch(
        previousSuffix: List<Word>,
        evidenceByIndex: Map<Int, AcousticWordEvidence>,
    ): Boolean? {
        var comparable = 0
        var matching = 0

        previousSuffix.forEachIndexed { index, previousWord ->
            val currentEvidence = evidenceByIndex[index] ?: return@forEachIndexed
            if (
                previousWord.audioStartMs <= 0L ||
                previousWord.audioEndMs < previousWord.audioStartMs ||
                currentEvidence.audioStartMs <= 0L ||
                currentEvidence.audioEndMs < currentEvidence.audioStartMs
            ) {
                return@forEachIndexed
            }

            comparable++

            val startsClose =
                kotlin.math.abs(previousWord.audioStartMs - currentEvidence.audioStartMs) <=
                    CROSS_SEGMENT_OVERLAP_TIME_TOLERANCE_MS

            val intervalsOverlap =
                currentEvidence.audioStartMs <=
                    previousWord.audioEndMs + CROSS_SEGMENT_OVERLAP_INTERVAL_MARGIN_MS &&
                    previousWord.audioStartMs <=
                    currentEvidence.audioEndMs + CROSS_SEGMENT_OVERLAP_INTERVAL_MARGIN_MS

            if (startsClose || intervalsOverlap) matching++
        }

        if (comparable == 0) return null
        return matching == comparable
    }

    private fun collectWordResolutionRequestsLocked(segment: SegmentState) {
        val words = segment.wordIds.mapNotNull { wordByIdLocked(it) }
        if (words.size < 3) return

        for (index in 1 until words.lastIndex) {
            val word = words[index]
            val logProb = word.acousticLogProb ?: continue
            if (
                word.state != WordState.STABLE ||
                word.primaryObservationCount < MIN_WORD_RESOLUTION_OBSERVATIONS ||
                logProb > configuration.uncertainWordLogProbThreshold ||
                word.wordId in resolutionRequestedWordIds ||
                word.audioStartMs <= 0L ||
                word.audioEndMs < word.audioStartMs
            ) continue

            val left = words[index - 1]
            val right = words[index + 1]
            if (left.normalizedText.isBlank() || right.normalizedText.isBlank()) continue

            val contextStart =
                left.audioStartMs.takeIf { it > 0L }
                    ?: (word.audioStartMs - 900L).coerceAtLeast(0L)
            val contextEnd =
                right.audioEndMs.takeIf { it >= word.audioEndMs }
                    ?: word.audioEndMs + 1_100L

            val timelineIndex = indexOfWordLocked(word.wordId)
            if (timelineIndex >= 0) {
                timeline[timelineIndex] = word.copy(state = WordState.RESOLVING)
            }
            resolutionRequestedWordIds += word.wordId

            listener?.onWordNeedsResolution(
                WordResolutionRequest(
                    wordId = word.wordId,
                    segmentId = segment.segmentId,
                    wordIndex = index,
                    originalWord = word.text,
                    normalizedWord = word.normalizedText,
                    leftAnchor = left.normalizedText,
                    rightAnchor = right.normalizedText,
                    contextStartMs = contextStart,
                    contextEndMs = contextEnd,
                    startSequence = left.startSequence.takeIf { it > 0L } ?: word.startSequence,
                    endSequence = right.endSequence.takeIf { it > 0L } ?: word.endSequence,
                    averageLogProb = logProb,
                    observationCount = word.primaryObservationCount,
                    doubtReason = "LOW_WORD_LOGPROB_STABLE",
                )
            )
        }
    }

    private fun closeAtTerminalPunctuationLocked(nowMs: Long): List<Unit> {
        val emitted = mutableListOf<Unit>()

        while (true) {
            val boundaryIndex = openUnitWordIds.indexOfFirst { wordId ->
                val word = wordByIdLocked(wordId) ?: return@indexOfFirst false
                hasTerminalPunctuation(word.text)
            }
            if (boundaryIndex < 0) break

            val ids = openUnitWordIds.take(boundaryIndex + 1)
            emitted += emitSpecificIdsLocked(
                ids = ids,
                type = UnitType.NATURAL_PHRASE,
                boundary = BoundaryKind.PUNCTUATION,
                nowMs = nowMs,
            )
            repeat(boundaryIndex + 1) { openUnitWordIds.removeAt(0) }
            openUnitStartedAtMs = if (openUnitWordIds.isEmpty()) 0L else nowMs
        }

        return emitted
    }

    private fun enforceWordWindowGuardLocked(nowMs: Long): List<Unit> {
        val emitted = mutableListOf<Unit>()
        while (openUnitWordIds.size >= configuration.maxActiveWords) {
            val ids = openUnitWordIds.take(configuration.maxActiveWords)
            emitted += emitSpecificIdsLocked(
                ids = ids,
                type = UnitType.CONTEXT_CHUNK,
                boundary = BoundaryKind.WORD_WINDOW,
                nowMs = nowMs,
            )
            repeat(configuration.maxActiveWords) { openUnitWordIds.removeAt(0) }
            openUnitStartedAtMs = if (openUnitWordIds.isEmpty()) 0L else nowMs
        }
        return emitted
    }

    private fun emitOpenUnitLocked(
        type: UnitType,
        boundary: BoundaryKind,
        nowMs: Long,
    ): Unit {
        val ids = openUnitWordIds.toList()
        val unit = emitSpecificIdsLocked(ids, type, boundary, nowMs)
        openUnitWordIds.clear()
        openUnitStartedAtMs = 0L
        return unit
    }

    private fun emitSpecificIdsLocked(
        ids: List<Long>,
        type: UnitType,
        boundary: BoundaryKind,
        nowMs: Long,
    ): Unit {
        ids.forEach { wordId ->
            val index = indexOfWordLocked(wordId)
            if (index >= 0) {
                val word = timeline[index]
                if (word.state != WordState.CORRECTED) {
                    timeline[index] = word.copy(state = WordState.COMMITTED)
                }
            }
        }

        val unitConfidence =
            ids.mapNotNull { wordId ->
                wordByIdLocked(wordId)
                    ?.confidence
                    ?.takeIf { it.isFinite() }
            }
                .takeIf { it.isNotEmpty() }
                ?.average()
                ?.toFloat()
                ?.coerceIn(0.0f, 1.0f)

        val unit = Unit(
            unitId = nextUnitId++,
            type = type,
            boundary = boundary,
            wordIds = ids,
            text = buildTextForIdsLocked(ids),
            createdAtMs = nowMs,
            confidence = unitConfidence,
        )
        emittedUnits[unit.unitId] = unit
        ids.forEach { wordId ->
            wordToUnitIds.getOrPut(wordId) { linkedSetOf() }.add(unit.unitId)
        }

        if (type == UnitType.NATURAL_PHRASE) lastNaturalPhrase = unit.text
        listener?.onUnitReady(unit)
        return unit
    }

    private fun replaceSegmentWordsLocked(
        segment: SegmentState,
        originalWords: List<Word>,
        replacement: List<Word>,
    ) {
        if (originalWords.isEmpty()) return

        val firstIndex = timeline.indexOfFirst { it.wordId == originalWords.first().wordId }
        if (firstIndex < 0) return

        val oldIds = originalWords.map { it.wordId }.toSet()
        timeline.removeAll { it.wordId in oldIds }
        timeline.addAll(firstIndex.coerceAtMost(timeline.size), replacement)

        segment.wordIds.clear()
        segment.wordIds += replacement.map { it.wordId }

        /* Réécrit les références ouvertes et celles des unités déjà émises. */
        replaceIdsInList(openUnitWordIds, originalWords.map { it.wordId }, replacement.map { it.wordId })

        emittedUnits.entries.toList().forEach { (unitId, unit) ->
            if (unit.wordIds.none { it in oldIds }) return@forEach
            val newIds = replaceIds(unit.wordIds, originalWords.map { it.wordId }, replacement.map { it.wordId })
            oldIds.forEach { oldId -> wordToUnitIds[oldId]?.remove(unitId) }
            newIds.forEach { newId -> wordToUnitIds.getOrPut(newId) { linkedSetOf() }.add(unitId) }
            emittedUnits[unitId] = unit.copy(wordIds = newIds)
        }
    }

    private fun replaceIdsInList(
        target: MutableList<Long>,
        oldIds: List<Long>,
        newIds: List<Long>,
    ) {
        val replaced = replaceIds(target, oldIds, newIds)
        target.clear()
        target += replaced
    }

    private fun replaceIds(
        source: List<Long>,
        oldIds: List<Long>,
        newIds: List<Long>,
    ): List<Long> {
        if (oldIds.isEmpty()) return source
        val first = source.indexOf(oldIds.first())
        if (first < 0) return source
        val last = source.indexOfLast { it in oldIds }
        if (last < first) return source
        return buildList {
            addAll(source.take(first))
            addAll(newIds)
            addAll(source.drop(last + 1))
        }
    }

    private fun publishContextLocked() {
        listener?.onContextUpdated(snapshotLocked())
    }

    private fun snapshotLocked(): ContextSnapshot {
        val visible = timeline
            .filter { it.state != WordState.PROVISIONAL }
            .takeLast(configuration.maxActiveWords)

        return ContextSnapshot(
            words = visible,
            text = joinWords(visible.map { it.text }),
            wordCount = visible.size,
            openWordCount = openUnitWordIds.size,
            lastNaturalPhrase = lastNaturalPhrase,
        )
    }

    private fun trimHistoryLocked() {
        val protected = buildSet {
            addAll(openUnitWordIds)
            segments.values.toList().takeLast(8).forEach { addAll(it.wordIds) }
        }

        var index = 0
        while (timeline.size > configuration.correctionHistoryWords && index < timeline.size) {
            val word = timeline[index]
            if (word.wordId !in protected) {
                timeline.removeAt(index)
                wordToUnitIds.remove(word.wordId)
                resolutionRequestedWordIds.remove(word.wordId)
            } else {
                index++
            }
        }

        while (segments.size > configuration.correctionHistorySegments) {
            val first = segments.entries.firstOrNull() ?: break
            val segment = first.value
            if (!segment.finalized || segment.wordIds.any { it in protected }) break
            segments.remove(first.key)
            segment.originId?.let { originToSegmentId.remove(it) }
        }

        while (emittedUnits.size > configuration.correctionHistoryUnits) {
            val first = emittedUnits.entries.firstOrNull() ?: break
            emittedUnits.remove(first.key)
            first.value.wordIds.forEach { wordId ->
                wordToUnitIds[wordId]?.let { ids ->
                    ids.remove(first.key)
                    if (ids.isEmpty()) wordToUnitIds.remove(wordId)
                }
            }
        }
    }

    private fun primaryComparisonText(word: Word): String =
        word.primaryNormalizedText.ifBlank { word.normalizedText }

    private fun buildTextForIdsLocked(ids: List<Long>): String =
        joinWords(ids.mapNotNull { wordByIdLocked(it)?.text })

    private fun wordByIdLocked(wordId: Long): Word? =
        timeline.firstOrNull { it.wordId == wordId }

    private fun indexOfWordLocked(wordId: Long): Int =
        timeline.indexOfFirst { it.wordId == wordId }

    private fun classifyBoundary(reason: String?): BoundaryKind {
        val value = reason.orEmpty().uppercase(Locale.ROOT)
        return when {
            "PUNCTUATION" in value -> BoundaryKind.PUNCTUATION
            "SILENCE" in value || "PAUSE" in value || "ENDPOINT" in value -> BoundaryKind.SILENCE
            "HARD" in value || "ROLLOVER" in value -> BoundaryKind.TECHNICAL_ROLLOVER
            "WATCHDOG" in value -> BoundaryKind.TEMPORAL_WATCHDOG
            else -> BoundaryKind.UNKNOWN
        }
    }

    private fun tokenizeWords(text: String): List<String> =
        text.trim()
            .split(WHITESPACE_REGEX)
            .map { it.trim() }
            .filter { it.any(Char::isLetterOrDigit) }

    private fun normalizeLexical(text: String): String {
        val decomposed = Normalizer.normalize(text, Normalizer.Form.NFD)
        return decomposed
            .replace(DIACRITICS_REGEX, "")
            .lowercase(Locale.ROOT)
            .replace(NON_LEXICAL_REGEX, "")
            .trim()
    }

    private fun hasTerminalPunctuation(text: String): Boolean {
        val trimmed = text.trim()
        return trimmed.endsWith(".") || trimmed.endsWith("!") ||
            trimmed.endsWith("?") || trimmed.endsWith("…")
    }

    private fun joinWords(words: List<String>): String =
        words.joinToString(" ")
            .replace(SPACE_BEFORE_PUNCTUATION_REGEX, "$1")
            .replace(MULTISPACE_REGEX, " ")
            .trim()

    /** Levenshtein + backtracking. Le nombre de mots est <= 24/segment typique. */
    private fun align(oldWords: List<String>, newWords: List<String>): List<Edit> {
        val n = oldWords.size
        val m = newWords.size
        val dp = Array(n + 1) { IntArray(m + 1) }
        for (i in 0..n) dp[i][0] = i
        for (j in 0..m) dp[0][j] = j

        for (i in 1..n) {
            for (j in 1..m) {
                val substitutionCost = if (oldWords[i - 1] == newWords[j - 1]) 0 else 1
                dp[i][j] = min(
                    min(dp[i - 1][j] + 1, dp[i][j - 1] + 1),
                    dp[i - 1][j - 1] + substitutionCost,
                )
            }
        }

        val reversed = mutableListOf<Edit>()
        var i = n
        var j = m
        while (i > 0 || j > 0) {
            when {
                i > 0 && j > 0 && oldWords[i - 1] == newWords[j - 1] &&
                    dp[i][j] == dp[i - 1][j - 1] -> {
                    reversed += Edit.Keep(i - 1, j - 1)
                    i--; j--
                }

                i > 0 && j > 0 && dp[i][j] == dp[i - 1][j - 1] + 1 -> {
                    reversed += Edit.Substitute(i - 1, j - 1)
                    i--; j--
                }

                i > 0 && dp[i][j] == dp[i - 1][j] + 1 -> {
                    reversed += Edit.Delete(i - 1)
                    i--
                }

                j > 0 -> {
                    reversed += Edit.Insert(j - 1)
                    j--
                }
            }
        }

        return reversed.asReversed()
    }

    companion object {
        private const val MAX_CROSS_SEGMENT_OVERLAP_WORDS = 6
        private const val MIN_WORD_RESOLUTION_OBSERVATIONS = 2
        private const val MAX_PRIMARY_OBSERVATION_COUNT = 32
        private const val CROSS_SEGMENT_OVERLAP_TIME_TOLERANCE_MS = 1_200L
        private const val CROSS_SEGMENT_OVERLAP_INTERVAL_MARGIN_MS = 250L

        /**
         * Regroupe les tokens/sub-tokens Sherpa en vrais mots du texte courant.
         * Le mapping reste conservateur : si les fragments ne reconstituent pas
         * exactement le mot, ce mot reste simplement sans ancre acoustique.
         */
        fun buildAcousticWordEvidence(
            text: String,
            tokens: Array<String>,
            timestamps: FloatArray,
            ysProbs: FloatArray,
            streamStartMs: Long,
            observedAtMs: Long,
            streamStartSequence: Long = 0L,
            frameDurationMs: Long = 50L,
        ): List<AcousticWordEvidence> {
            if (text.isBlank() || tokens.isEmpty() || timestamps.isEmpty() || streamStartMs <= 0L) {
                return emptyList()
            }
            val displayWords = text.trim().split(WHITESPACE_REGEX).filter { it.isNotBlank() }
            val normalizedWords = displayWords.map { normalizeLexicalStatic(it) }
            val out = ArrayList<AcousticWordEvidence>()
            var tokenCursor = 0

            for (wordIndex in normalizedWords.indices) {
                val target = normalizedWords[wordIndex]
                if (target.isBlank()) continue
                var startToken = -1
                var endToken = -1
                var joined = ""
                var cursor = tokenCursor

                while (cursor < tokens.size) {
                    val part = normalizeLexicalStatic(tokens[cursor])
                    if (part.isBlank()) { cursor++; continue }
                    if (startToken < 0) startToken = cursor
                    joined += part
                    endToken = cursor
                    if (joined == target) break
                    if (!target.startsWith(joined)) {
                        startToken = -1
                        endToken = -1
                        joined = ""
                    }
                    cursor++
                }

                if (startToken < 0 || endToken < startToken || joined != target) continue
                val startSec = timestamps.getOrNull(startToken)?.takeIf { it.isFinite() && it >= 0f } ?: continue
                val nextSec = (endToken + 1 until timestamps.size)
                    .asSequence()
                    .mapNotNull { timestamps.getOrNull(it) }
                    .firstOrNull { it.isFinite() && it > startSec }
                val startMs = streamStartMs + (startSec * 1000f).toLong()
                val endMs = (nextSec?.let { streamStartMs + (it * 1000f).toLong() - 1L }
                    ?: minOf(observedAtMs, startMs + 350L)).coerceAtLeast(startMs)
                val probs = (startToken..endToken).mapNotNull { ysProbs.getOrNull(it)?.takeIf(Float::isFinite) }
                val avg = probs.takeIf { it.isNotEmpty() }?.average()?.toFloat()
                val startSeq = if (streamStartSequence > 0L && frameDurationMs > 0L) {
                    streamStartSequence + ((startMs - streamStartMs).coerceAtLeast(0L) / frameDurationMs)
                } else 0L
                val endSeq = if (startSeq > 0L && frameDurationMs > 0L) {
                    streamStartSequence + ((endMs - streamStartMs).coerceAtLeast(0L) / frameDurationMs)
                } else 0L

                out += AcousticWordEvidence(
                    wordIndex = wordIndex,
                    text = displayWords[wordIndex],
                    normalizedText = target,
                    audioStartMs = startMs,
                    audioEndMs = endMs,
                    startSequence = startSeq,
                    endSequence = endSeq,
                    averageLogProb = avg,
                )
                tokenCursor = endToken + 1
            }
            return out
        }

        private fun normalizeLexicalStatic(text: String): String {
            val decomposed = Normalizer.normalize(text, Normalizer.Form.NFD)
            return decomposed
                .replace(Regex("\\p{Mn}+"), "")
                .lowercase(Locale.ROOT)
                .replace(Regex("[^\\p{L}\\p{N}']+"), "")
                .trim()
        }

        private val WHITESPACE_REGEX = Regex("\\s+")
        private val DIACRITICS_REGEX = Regex("\\p{Mn}+")
        private val NON_LEXICAL_REGEX = Regex("[^\\p{L}\\p{N}']+")
        private val SPACE_BEFORE_PUNCTUATION_REGEX = Regex("\\s+([.!?,;:…])")
        private val MULTISPACE_REGEX = Regex("\\s+")
    }
}
