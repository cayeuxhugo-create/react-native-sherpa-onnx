package expo.modules.guardiannativeprotection

fun main() {
    val requests = mutableListOf<GuardianWordTimelineEngine.WordResolutionRequest>()
    val units = mutableListOf<GuardianWordTimelineEngine.Unit>()

    val timeline = GuardianWordTimelineEngine(
        configuration = GuardianWordTimelineEngine.Configuration(
            maxActiveWords = 24,
            correctionHistoryWords = 96,
            stableTrailingWords = 2,
            maxOpenContextAgeMs = 45_000L,
            uncertainWordLogProbThreshold = -0.45f,
        ),
        listener = object : GuardianWordTimelineEngine.Listener {
            override fun onWordNeedsResolution(request: GuardianWordTimelineEngine.WordResolutionRequest) {
                requests += request
                println("WORD_REVIEW_REQUEST id=${request.wordId} word=${request.originalWord} observations=${request.observationCount} reason=${request.doubtReason} audio=${request.contextStartMs}-${request.contextEndMs} logProb=${request.averageLogProb}")
            }
            override fun onUnitReady(unit: GuardianWordTimelineEngine.Unit) {
                units += unit
                println("UNIT ${unit.unitId} ${unit.type} ${unit.boundary} text=\"${unit.text}\"")
            }
        }
    )

    val startMs = 10_000L
    val partialText = "Je crois que quelqu'un me suis depuis la gare"
    val partialTokens = arrayOf("Je", "crois", "que", "quelqu'un", "me", "su", "is", "depuis", "la", "gare")
    val timestamps = floatArrayOf(0f, .35f, .65f, .95f, 1.25f, 1.55f, 1.72f, 2.0f, 2.3f, 2.6f)
    val probs = floatArrayOf(-.08f, -.10f, -.08f, -.12f, -.10f, -.70f, -.60f, -.12f, -.10f, -.12f)
    val evidence = GuardianWordTimelineEngine.buildAcousticWordEvidence(
        text = partialText,
        tokens = partialTokens,
        timestamps = timestamps,
        ysProbs = probs,
        streamStartMs = startMs,
        observedAtMs = 13_200L,
        streamStartSequence = 1000L,
    )

    check(evidence.size == 9) { "word mapping failed: $evidence" }
    val suitEvidence = evidence.first { it.normalizedText == "suis" }
    check(suitEvidence.averageLogProb != null && suitEvidence.averageLogProb!! <= -0.45f)
    check(suitEvidence.audioStartMs > 0 && suitEvidence.startSequence > 0)

    // Première observation : le mot est encore trop jeune pour réveiller Qwen.
    timeline.observePartial(
        segmentId = 77L,
        text = partialText,
        observedAtMs = 13_200L,
        evidence = evidence,
    )
    check(requests.isEmpty()) { "Qwen should not trigger on first observation" }

    // Deuxième observation cohérente : le doute lexical devient autoritaire.
    timeline.observePartial(
        segmentId = 77L,
        text = partialText,
        observedAtMs = 13_300L,
        evidence = evidence,
    )

    val request = requests.firstOrNull { it.originalWord.equals("suis", ignoreCase = true) }
        ?: error("No local review request for uncertain word")

    check(timeline.completeWordResolution(
        wordId = request.wordId,
        originalWord = request.originalWord,
        replacementWord = "suit",
        observedAtMs = 13_300L,
    ))

    val finalText = "Je crois que quelqu'un me suis depuis la gare."
    val finalEvidence = GuardianWordTimelineEngine.buildAcousticWordEvidence(
        text = finalText,
        tokens = arrayOf("Je", "crois", "que", "quelqu'un", "me", "su", "is", "depuis", "la", "gare."),
        timestamps = timestamps,
        ysProbs = probs,
        streamStartMs = startMs,
        observedAtMs = 13_500L,
        streamStartSequence = 1000L,
    )

    timeline.observeFinal(
        segmentId = 77L,
        originId = 991L,
        text = finalText,
        boundaryReason = "TERMINAL_PUNCTUATION",
        observedAtMs = 13_500L,
        evidence = finalEvidence,
    )

    val afterFinal = timeline.getSnapshot()
    check("suit" in afterFinal.text) { "local correction was overwritten by Nemotron FINAL: ${afterFinal.text}" }
    check("suis" !in afterFinal.text) { afterFinal.text }
    check(units.last().text.contains("suit"))

    // A technical recovered FINAL can still enter the lexical timeline.
    timeline.observeFinal(
        segmentId = 88L,
        originId = 992L,
        text = "aide moi.",
        boundaryReason = "RECOVERY_FINAL_EMPTY_AFTER_COMMIT",
        observedAtMs = 20_000L,
        recovered = true,
    )
    check(timeline.getSnapshot().text.contains("aide moi."))

    // Ultimate temporal watchdog remains technical, not a definition of a sentence.
    timeline.observeFinal(
        segmentId = 99L,
        originId = 993L,
        text = "encore quelques mots sans ponctuation",
        boundaryReason = "HARD_SEGMENT_ROLLOVER",
        observedAtMs = 30_000L,
    )
    val watchdog = timeline.tick(76_000L)
    check(watchdog != null && watchdog.boundary == GuardianWordTimelineEngine.BoundaryKind.TEMPORAL_WATCHDOG)

    println("OK Lexical V3.1")
    println("FINAL_CONTEXT=\"${timeline.getSnapshot().text}\"")
}
