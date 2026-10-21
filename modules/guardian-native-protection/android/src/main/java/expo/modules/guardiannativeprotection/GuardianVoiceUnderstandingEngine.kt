package expo.modules.guardiannativeprotection

import java.text.Normalizer
import java.util.Locale

/**
 * Couche de compréhension déterministe de Guardian Voice.
 *
 * Responsabilités :
 * - normaliser le texte reconnu par Sherpa ;
 * - reconnaître les wake phrases configurées ;
 * - conserver le matcher historique d'urgence ;
 * - détecter les intentions Voice ;
 * - analyser les familles lexicales ;
 * - gérer les négations simples ;
 * - reconnaître les constructions complètes ;
 * - gérer les formulations rassurantes ;
 * - retourner un résultat structuré au GuardianVoiceEngine.
 *
 * Cette classe ne :
 * - possède pas le microphone ;
 * - possède pas AudioRecord ;
 * - possède pas GuardianSherpaRecognizer ;
 * - déclenche pas de GuardianVoiceEvent ;
 * - gère pas la wake window ;
 * - gère pas le cooldown.
 *
 * Une future couche NLU IA pourra produire des preuves complémentaires
 * sans remplacer ce moteur déterministe.
 */
class GuardianVoiceUnderstandingEngine(
    wakePhrases: List<String>,
    emergencyPhrases: List<String>,
) {

    enum class VoiceIntent {
        NONE,

        WAKE_GUARDIAN,
        WAKE_PARENT,

        HELP_REQUEST,
        DISTRESS,
        FEAR,

        POLICE_REQUEST,
        EMERGENCY_SERVICES_REQUEST,

        FOLLOWED,
        THREAT,
        ATTACK,

        INJURY,
        MEDICAL_EMERGENCY,

        TRAPPED,
        LOST,
    }

    data class IntentEvidence(
        val intent: VoiceIntent,
        val score: Int,
        val evidence: List<String>,
    )

    data class UnderstandingResult(
        val normalizedText: String,
        val primaryIntent: VoiceIntent,
        val confidenceScore: Int,
        val emergencyDetected: Boolean,
        val wakeDetected: Boolean,
        val reassuranceDetected: Boolean,
        val wakePhrase: String?,
        val emergencyPhrase: String?,
        val evidence: List<String>,
        val detectedIntents: List<IntentEvidence>,
    )

    private data class PhraseMatch(
        val phrase: String,
        val score: Int,
    )

    companion object {
        private val DIACRITICS_REGEX =
            Regex("\\p{M}+")

        private val NON_TEXT_REGEX =
            Regex("[^a-z0-9 ]")

        private val WHITESPACE_REGEX =
            Regex("\\s+")
    }

    @Volatile
    private var lastResult: UnderstandingResult? = null

    init {
        require(
            wakePhrases.none {
                it.isBlank()
            }
        ) {
            "Phrase de réveil Understanding vide"
        }

        require(
            emergencyPhrases.none {
                it.isBlank()
            }
        ) {
            "Phrase d'urgence Understanding vide"
        }
    }

    private val normalizedWakePhrases =
        wakePhrases
            .mapNotNull {
                normalizePhrase(it)
                    .takeIf { normalized ->
                        normalized.isNotBlank()
                    }
            }
            .distinct()

    private val normalizedEmergencyPhrases =
        emergencyPhrases
            .mapNotNull {
                normalizePhrase(it)
                    .takeIf { normalized ->
                        normalized.isNotBlank()
                    }
            }
            .distinct()

    // ============================================================
    // Familles lexicales extraites du GuardianVoiceEngine actuel
    // ============================================================

    private val helpWords =
        setOf(
            "aide",
            "aidez",
            "secours",
            "sos",
        )

    private val fearWords =
        setOf(
            "peur",
            "effraye",
            "effrayee",
            "terrifie",
            "terrifiee",
            "panique",
        )

    private val dangerWords =
        setOf(
            "danger",
            "dangereux",
            "menace",
            "menacee",
            "menacer",
        )

    private val followingWords =
        setOf(
            "suit",
            "suivi",
            "suivie",
            "poursuit",
            "poursuivi",
            "poursuivie",
        )

    private val attackWords =
        setOf(
            "attaque",
            "agresse",
            "agressee",
            "frappe",
            "frapper",
        )

    private val injuryWords =
        setOf(
            "blesse",
            "blessee",
            "saigne",
            "sang",
        )

    private val trappedWords =
        setOf(
            "bloque",
            "bloquee",
            "coince",
            "coincee",
            "enferme",
            "enfermee",
        )

    private val lostWords =
        setOf(
            "perdu",
            "perdue",
            "retrouve",
            "retrouver",
        )

    private val policeWords =
        setOf(
            "police",
            "gendarmerie",
            "gendarmes",
        )

    private val emergencyServiceWords =
        setOf(
            "secours",
            "ambulance",
            "pompiers",
            "samu",
        )

    private val negationWords =
        setOf(
            "pas",
            "plus",
            "jamais",
            "aucun",
            "aucune",
            "rien",
        )

    private val reassuranceWords =
        setOf(
            "ca va",
            "tout va bien",
            "je vais bien",
            "pas de probleme",
            "aucun probleme",
        )

    private val emergencyIntents =
        setOf(
            VoiceIntent.HELP_REQUEST,
            VoiceIntent.DISTRESS,
            VoiceIntent.FEAR,
            VoiceIntent.POLICE_REQUEST,
            VoiceIntent.EMERGENCY_SERVICES_REQUEST,
            VoiceIntent.FOLLOWED,
            VoiceIntent.THREAT,
            VoiceIntent.ATTACK,
            VoiceIntent.INJURY,
            VoiceIntent.MEDICAL_EMERGENCY,
            VoiceIntent.TRAPPED,
        )

    private val wakeIntents =
        setOf(
            VoiceIntent.WAKE_GUARDIAN,
            VoiceIntent.WAKE_PARENT,
        )

    /**
     * Point d'entrée unique de la compréhension Voice.
     */
    fun analyze(
        text: String,
    ): UnderstandingResult {
        val normalizedText =
            normalizePhrase(
                text
            )

        if (normalizedText.isBlank()) {
            return emptyResult(
                normalizedText
            )
        }

        val tokens =
            tokenize(
                normalizedText
            )

        // ========================================================
        // Constructions complètes présentes dans ton moteur actuel
        // ========================================================

        val saysSomeoneFollowsMe =
            containsAffirmativeWholePhrase(
                normalizedText,
                "quelqu un me suit"
            ) ||
                containsAffirmativeWholePhrase(
                    normalizedText,
                    "on me suit"
                ) ||
                containsAffirmativeWholePhrase(
                    normalizedText,
                    "il me suit"
                ) ||
                containsAffirmativeWholePhrase(
                    normalizedText,
                    "elle me suit"
                )

        val saysSomeoneThreatensMe =
            containsAffirmativeWholePhrase(
                normalizedText,
                "on me menace"
            ) ||
                containsAffirmativeWholePhrase(
                    normalizedText,
                    "il me menace"
                ) ||
                containsAffirmativeWholePhrase(
                    normalizedText,
                    "elle me menace"
                ) ||
                containsAffirmativeWholePhrase(
                    normalizedText,
                    "quelqu un me menace"
                )

        val saysCallPolice =
            containsAffirmativeWholePhrase(
                normalizedText,
                "appelle la police"
            ) ||
                containsAffirmativeWholePhrase(
                    normalizedText,
                    "appelez la police"
                ) ||
                containsAffirmativeWholePhrase(
                    normalizedText,
                    "appelle les gendarmes"
                ) ||
                containsAffirmativeWholePhrase(
                    normalizedText,
                    "appelez les gendarmes"
                )

        val saysCallEmergencyServices =
            containsAffirmativeWholePhrase(
                normalizedText,
                "appelle les secours"
            ) ||
                containsAffirmativeWholePhrase(
                    normalizedText,
                    "appelez les secours"
                ) ||
                containsAffirmativeWholePhrase(
                    normalizedText,
                    "appelle les pompiers"
                ) ||
                containsAffirmativeWholePhrase(
                    normalizedText,
                    "appelle une ambulance"
                )

        val saysSeverePain =
            containsAffirmativeWholePhrase(
                normalizedText,
                "j ai tres mal"
            ) ||
                containsAffirmativeWholePhrase(
                    normalizedText,
                    "j ai vraiment mal"
                ) ||
                containsAffirmativeWholePhrase(
                    normalizedText,
                    "je suis blesse"
                ) ||
                containsAffirmativeWholePhrase(
                    normalizedText,
                    "je suis blessee"
                )

        val saysTrapped =
            containsAffirmativeWholePhrase(
                normalizedText,
                "je suis bloque"
            ) ||
                containsAffirmativeWholePhrase(
                    normalizedText,
                    "je suis bloquee"
                ) ||
                containsAffirmativeWholePhrase(
                    normalizedText,
                    "je suis coince"
                ) ||
                containsAffirmativeWholePhrase(
                    normalizedText,
                    "je suis coincee"
                )

        val saysLost =
            containsAffirmativeWholePhrase(
                normalizedText,
                "je suis perdu"
            ) ||
                containsAffirmativeWholePhrase(
                    normalizedText,
                    "je suis perdue"
                ) ||
                containsAffirmativeWholePhrase(
                    normalizedText,
                    "je ne sais pas ou je suis"
                )

        val reassuranceDetected =
            reassuranceWords.any {
                containsAffirmativeWholePhrase(
                    normalizedText,
                    it
                )
            }

        /*
         * LinkedHashMap :
         * une intention n'apparaît qu'une fois.
         * Si plusieurs preuves existent pour la même intention,
         * on garde le meilleur score et toutes les preuves.
         */
        val detected =
            linkedMapOf<
                VoiceIntent,
                IntentEvidence
                >()

        fun register(
            intent: VoiceIntent,
            score: Int,
            evidence: List<String>,
        ) {
            val cleanEvidence =
                evidence
                    .filter {
                        it.isNotBlank()
                    }
                    .distinct()

            if (cleanEvidence.isEmpty()) {
                return
            }

            val safeScore =
                score.coerceIn(
                    0,
                    100
                )

            val previous =
                detected[intent]

            if (previous == null) {
                detected[intent] =
                    IntentEvidence(
                        intent =
                            intent,

                        score =
                            safeScore,

                        evidence =
                            cleanEvidence,
                    )

                return
            }

            detected[intent] =
                IntentEvidence(
                    intent =
                        intent,

                    score =
                        maxOf(
                            previous.score,
                            safeScore
                        ),

                    evidence =
                        (
                            previous.evidence +
                                cleanEvidence
                            )
                            .distinct(),
                )
        }

        // ========================================================
        // Wake phrase historique
        // ========================================================

        val wakeMatch =
            findBestPhraseMatch(
                recognizedText =
                    normalizedText,

                phrases =
                    normalizedWakePhrases,
            )

        if (wakeMatch != null) {
            register(
                intent =
                    if (
                        wakeMatch.phrase == "maman" ||
                        wakeMatch.phrase == "papa"
                    ) {
                        VoiceIntent.WAKE_PARENT
                    } else {
                        VoiceIntent.WAKE_GUARDIAN
                    },

                score =
                    wakeMatch.score,

                evidence =
                    listOf(
                        wakeMatch.phrase
                    ),
            )
        }

        /*
         * Matcher d'urgence historique.
         * Dans ton moteur actuel il est encore exécuté à l'extérieur
         * de understandText(). On le déplace ici pour que toute la
         * compréhension soit réellement centralisée dans ce fichier.
         */
        val emergencyMatch =
            findBestPhraseMatch(
                recognizedText =
                    normalizedText,

                phrases =
                    normalizedEmergencyPhrases,
            )

        // ========================================================
        // Constructions explicites fortes
        // ========================================================

        val saysDirectHelp =
            containsAffirmativeWholePhrase(
                normalizedText,
                "aide moi"
            ) ||
                containsAffirmativeWholePhrase(
                    normalizedText,
                    "aidez moi"
                ) ||
                containsAffirmativeWholePhrase(
                    normalizedText,
                    "au secours"
                ) ||
                containsAffirmativeWholePhrase(
                    normalizedText,
                    "viens m aider"
                )

        val saysExplicitFear =
            containsAffirmativeWholePhrase(
                normalizedText,
                "j ai peur"
            ) ||
                containsAffirmativeWholePhrase(
                    normalizedText,
                    "je suis terrifie"
                ) ||
                containsAffirmativeWholePhrase(
                    normalizedText,
                    "je suis terrifiee"
                )

        val saysExplicitDanger =
            containsAffirmativeWholePhrase(
                normalizedText,
                "je suis en danger"
            ) ||
                containsAffirmativeWholePhrase(
                    normalizedText,
                    "je me sens en danger"
                )

        // ========================================================
        // Familles lexicales
        // ========================================================

        val helpEvidence =
            filterPositiveEvidence(
                normalizedText =
                    normalizedText,

                evidence =
                    collectEvidence(
                        tokens,
                        helpWords
                    )
            )

        register(
            VoiceIntent.HELP_REQUEST,
            when {
                "sos" in helpEvidence ->
                    100

                "secours" in helpEvidence ->
                    78

                else ->
                    68
            },
            helpEvidence
        )

        if (saysDirectHelp) {
            register(
                intent =
                    VoiceIntent.HELP_REQUEST,

                score =
                    100,

                evidence =
                    listOf(
                        "direct_help_request"
                    ),
            )
        }

        val fearEvidence =
            filterPositiveEvidence(
                normalizedText =
                    normalizedText,

                evidence =
                    collectEvidence(
                        tokens,
                        fearWords
                    )
            )

        register(
            VoiceIntent.FEAR,
            74,
            fearEvidence
        )

        if (saysExplicitFear) {
            register(
                intent =
                    VoiceIntent.FEAR,

                score =
                    94,

                evidence =
                    listOf(
                        "explicit_fear"
                    ),
            )
        }

        val dangerEvidence =
            filterPositiveEvidence(
                normalizedText =
                    normalizedText,

                evidence =
                    collectEvidence(
                        tokens,
                        dangerWords
                    )
            )

        if (saysSomeoneThreatensMe) {
            register(
                intent =
                    VoiceIntent.THREAT,

                score =
                    100,

                evidence =
                    listOf(
                        "someone_threatens_me"
                    ),
            )
        }

        register(
            VoiceIntent.DISTRESS,
            76,
            dangerEvidence
        )

        if (saysExplicitDanger) {
            register(
                intent =
                    VoiceIntent.DISTRESS,

                score =
                    100,

                evidence =
                    listOf(
                        "explicit_danger"
                    ),
            )
        }

        val followingEvidence =
            filterPositiveEvidence(
                normalizedText =
                    normalizedText,

                evidence =
                    collectEvidence(
                        tokens,
                        followingWords
                    )
            )

        if (saysSomeoneFollowsMe) {
            register(
                intent =
                    VoiceIntent.FOLLOWED,

                score =
                    100,

                evidence =
                    listOf(
                        "someone_follows_me"
                    ),
            )
        }

        register(
            VoiceIntent.FOLLOWED,
            70,
            followingEvidence
        )

        val attackEvidence =
            filterPositiveEvidence(
                normalizedText =
                    normalizedText,

                evidence =
                    collectEvidence(
                        tokens,
                        attackWords
                    )
            )

        register(
            VoiceIntent.ATTACK,
            78,
            attackEvidence
        )

        val injuryEvidence =
            filterPositiveEvidence(
                normalizedText =
                    normalizedText,

                evidence =
                    collectEvidence(
                        tokens,
                        injuryWords
                    )
            )

        if (saysSeverePain) {
            register(
                intent =
                    VoiceIntent.MEDICAL_EMERGENCY,

                score =
                    96,

                evidence =
                    listOf(
                        "severe_pain"
                    ),
            )
        }

        register(
            VoiceIntent.INJURY,
            76,
            injuryEvidence
        )

        val trappedEvidence =
            filterPositiveEvidence(
                normalizedText =
                    normalizedText,

                evidence =
                    collectEvidence(
                        tokens,
                        trappedWords
                    )
            )

        if (saysTrapped) {
            register(
                intent =
                    VoiceIntent.TRAPPED,

                score =
                    100,

                evidence =
                    listOf(
                        "trapped_context"
                    ),
            )
        }

        register(
            VoiceIntent.TRAPPED,
            74,
            trappedEvidence
        )

        val lostEvidence =
            filterPositiveEvidence(
                normalizedText =
                    normalizedText,

                evidence =
                    collectEvidence(
                        tokens,
                        lostWords
                    )
            )

        if (saysLost) {
            register(
                intent =
                    VoiceIntent.LOST,

                score =
                    94,

                evidence =
                    listOf(
                        "lost_context"
                    ),
            )
        }

        register(
            VoiceIntent.LOST,
            68,
            lostEvidence
        )

        val policeEvidence =
            filterPositiveEvidence(
                normalizedText =
                    normalizedText,

                evidence =
                    collectEvidence(
                        tokens,
                        policeWords
                    )
            )

        if (saysCallPolice) {
            register(
                intent =
                    VoiceIntent.POLICE_REQUEST,

                score =
                    100,

                evidence =
                    listOf(
                        "call_police"
                    ),
            )
        }

        register(
            VoiceIntent.POLICE_REQUEST,
            64,
            policeEvidence
        )

        val emergencyServicesEvidence =
            filterPositiveEvidence(
                normalizedText =
                    normalizedText,

                evidence =
                    collectEvidence(
                        tokens,
                        emergencyServiceWords
                    )
            )

        if (saysCallEmergencyServices) {
            register(
                intent =
                    VoiceIntent.EMERGENCY_SERVICES_REQUEST,

                score =
                    100,

                evidence =
                    listOf(
                        "call_emergency_services"
                    ),
            )
        }

        register(
            VoiceIntent.EMERGENCY_SERVICES_REQUEST,
            66,
            emergencyServicesEvidence
        )

        /*
         * L'ancien emergencyMatch reste une preuve déterministe.
         * On ne crée pas une nouvelle catégorie : on le rattache
         * à DISTRESS, ce qui permet au résultat de conserver
         * emergencyPhrase pour le GuardianVoiceEvent.
         */
        if (
            emergencyMatch != null &&
            !isPhraseNegated(
                normalizedText =
                    normalizedText,

                phrase =
                    emergencyMatch.phrase,
            )
        ) {
            register(
                intent =
                    VoiceIntent.DISTRESS,

                score =
                    emergencyMatch.score,

                evidence =
                    listOf(
                        emergencyMatch.phrase
                    ),
            )
        }

        val orderedIntents =
            detected.values
                .sortedByDescending {
                    it.score
                }

        val wakeDetected =
            orderedIntents.any {
                it.intent in
                    wakeIntents
            }

        val strongestEmergency =
            orderedIntents
                .filter {
                    it.intent in
                        emergencyIntents
                }
                .maxByOrNull {
                    it.score
                }

        val strongEmergencyPresent =
            strongestEmergency?.score
                ?.let {
                    it >= 88
                }
                ?: false

        /*
         * Une rassurance explicite neutralise les indices faibles.
         * Une preuve critique explicite (98+) reste disponible pour Fusion.
         */
        val emergencyDetected =
            when {
                // Une vraie construction explicite forte ne doit pas être
                // annulée par une phrase rassurante située ailleurs dans le texte.
                // Les signaux seulement faibles restent neutralisés.
                reassuranceDetected &&
                    !strongEmergencyPresent ->
                    false

                else ->
                    strongEmergencyPresent
            }

        val best =
            orderedIntents
                .firstOrNull {
                    it.intent !=
                        VoiceIntent.WAKE_GUARDIAN &&
                        it.intent !=
                        VoiceIntent.WAKE_PARENT
                }
                ?: orderedIntents.firstOrNull()

        val confidenceScore =
            when {
                reassuranceDetected &&
                    !emergencyDetected ->
                    0

                else ->
                    best?.score
                        ?: 0
            }

        val result =
    UnderstandingResult(
        normalizedText =
            normalizedText,

        primaryIntent =
            best?.intent
                ?: VoiceIntent.NONE,

        confidenceScore =
            confidenceScore,

        emergencyDetected =
            emergencyDetected,

        wakeDetected =
            wakeDetected,

        reassuranceDetected =
            reassuranceDetected,

        wakePhrase =
            wakeMatch?.phrase,

        emergencyPhrase =
            emergencyMatch
                ?.takeIf {
                    !isPhraseNegated(
                        normalizedText =
                            normalizedText,

                        phrase =
                            it.phrase,
                    )
                }
                ?.phrase,

        evidence =
            orderedIntents
                .flatMap {
                    it.evidence
                }
                .distinct(),

        detectedIntents =
            orderedIntents,
    )

lastResult =
    result

return result

    }

    fun getLastResult(): UnderstandingResult? =
        lastResult

    private fun emptyResult(
    normalizedText: String,
): UnderstandingResult {
    val result =
        UnderstandingResult(
            normalizedText =
                normalizedText,

            primaryIntent =
                VoiceIntent.NONE,

            confidenceScore =
                0,

            emergencyDetected =
                false,

            wakeDetected =
                false,

            reassuranceDetected =
                false,

            wakePhrase =
                null,

            emergencyPhrase =
                null,

            evidence =
                emptyList(),

            detectedIntents =
                emptyList(),
        )

    lastResult =
        result

    return result
}

    private fun tokenize(
        normalizedText: String,
    ): Set<String> =
        normalizedText
            .split(" ")
            .asSequence()
            .map {
                it.trim()
            }
            .filter {
                it.isNotBlank()
            }
            .toSet()

    private fun collectEvidence(
        tokens: Set<String>,
        vocabulary: Set<String>,
    ): List<String> =
        tokens
            .intersect(
                vocabulary
            )
            .toList()

    private fun isNegated(
        normalizedText: String,
        evidence: String,
    ): Boolean {
        val tokens = normalizedText.split(" ").filter { it.isNotBlank() }
        val indexes = tokens.indices.filter { tokens[it] == evidence }
        if (indexes.isEmpty()) return false

        // La présence d'une occurrence négative ne doit pas effacer une
        // occurrence positive ultérieure : « je n'ai plus peur, puis j'ai peur ».
        return indexes.all { index ->
            val prefix = tokens.subList((index - 3).coerceAtLeast(0), index)
            val nextToken = tokens.getOrNull(index + 1)
            prefix.any { it in negationWords } || nextToken in negationWords
        }
    }

    private fun filterPositiveEvidence(
        normalizedText: String,
        evidence: List<String>,
    ): List<String> =
        evidence.filterNot {
            isNegated(
                normalizedText =
                    normalizedText,

                evidence =
                    it,
            )
        }

    private data class PhraseSpan(val start: Int, val endExclusive: Int)

    // La similarité en sac de mots pouvait transformer « je ne suis pas en
    // danger » en correspondance forte avec « je suis en danger ». Une phrase
    // reconnue doit respecter l'ORDRE des mots : seule une hésitation orale
    // neutre peut être tolérée entre deux termes, pas une négation.
    private val harmlessFillers = setOf("euh", "heu", "hum")

    private fun findPhraseSpans(
        textTokens: List<String>,
        phraseTokens: List<String>,
        allowOneFiller: Boolean,
    ): List<PhraseSpan> {
        if (phraseTokens.isEmpty()) return emptyList()
        val result = mutableListOf<PhraseSpan>()
        for (start in textTokens.indices) {
            if (textTokens[start] != phraseTokens[0]) continue
            var cursor = start
            var usedFiller = false
            var matched = true
            for (target in phraseTokens.drop(1)) {
                when {
                    textTokens.getOrNull(cursor + 1) == target -> cursor++
                    allowOneFiller && !usedFiller &&
                        textTokens.getOrNull(cursor + 1) in harmlessFillers &&
                        textTokens.getOrNull(cursor + 2) == target -> {
                        cursor += 2
                        usedFiller = true
                    }
                    else -> { matched = false; break }
                }
            }
            if (matched) result.add(PhraseSpan(start, cursor + 1))
        }
        return result
    }

    private fun isSpanNegated(
        tokens: List<String>,
        span: PhraseSpan,
    ): Boolean {
        val phraseEnd = tokens.getOrNull(span.endExclusive - 1)
        val following = tokens.getOrNull(span.endExclusive)
        // Ne pas appliquer le « pas » de « ça va pas, j'ai peur » à la
        // proposition suivante. Un négateur placé avant une phrase ne la
        // neutralise ici que si l'énoncé COMMENCE par cette négation.
        val negatedAtStart = span.start == 1 && tokens.firstOrNull() in negationWords
        // « il me suit pas », « j'ai peur, pas du tout » et « ça va pas ».
        // Un « pas » après « danger » peut introduire « pas loin » :
        // l'étendre à toutes les phrases ferait manquer une vraie urgence.
        val negativeAfter = following in negationWords &&
            phraseEnd in setOf("va", "vais", "bien", "suit", "peur", "moi")
        return negatedAtStart || negativeAfter
    }

    private fun containsAffirmativeWholePhrase(
        text: String,
        phrase: String,
    ): Boolean {
        if (!containsWholePhrase(text, phrase)) return false
        val textTokens = text.split(" ").filter { it.isNotBlank() }
        val phraseTokens = phrase.split(" ").filter { it.isNotBlank() }
        return findPhraseSpans(textTokens, phraseTokens, false).any {
            !isSpanNegated(textTokens, it)
        }
    }

    private fun isPhraseNegated(
        normalizedText: String,
        phrase: String,
    ): Boolean {
        val tokens = normalizedText.split(" ").filter { it.isNotBlank() }
        val phraseTokens = phrase.split(" ").filter { it.isNotBlank() }
        val spans = findPhraseSpans(tokens, phraseTokens, allowOneFiller = true)
        // Tous les usages sont précédés d'une détection de match : si aucun
        // span sûr n'existe, la phrase ne donne pas de preuve positive.
        return spans.isEmpty() || spans.none { !isSpanNegated(tokens, it) }
    }

    private fun findBestPhraseMatch(
        recognizedText: String,
        phrases: List<String>,
    ): PhraseMatch? {
        var bestMatch:
            PhraseMatch? = null

        phrases.forEach { phrase ->
            val score =
                phraseMatchScore(
                    recognizedText =
                        recognizedText,

                    configuredPhrase =
                        phrase,
                )

            if (
                score > 0 &&
                (
                    bestMatch == null ||
                        score > bestMatch!!.score
                    )
            ) {
                bestMatch =
                    PhraseMatch(
                        phrase =
                            phrase,

                        score =
                            score,
                    )
            }
        }

        return bestMatch
    }

    private fun phraseMatchScore(
        recognizedText: String,
        configuredPhrase: String,
    ): Int {
        if (recognizedText.isBlank() || configuredPhrase.isBlank()) return 0
        if (recognizedText == configuredPhrase) return 100

        val textTokens = recognizedText.split(" ").filter { it.isNotBlank() }
        val phraseTokens = configuredPhrase.split(" ").filter { it.isNotBlank() }
        val spans = findPhraseSpans(textTokens, phraseTokens, allowOneFiller = true)
        if (spans.isEmpty() || spans.none { !isSpanNegated(textTokens, it) }) return 0

        if (containsWholePhrase(recognizedText, configuredPhrase)) {
            return 96
        }
        // Un « euh » oral est toléré une fois, sans pouvoir réordonner les
        // mots ni traverser une négation ou une autre proposition.
        return 90
    }

    private fun containsWholePhrase(
        text: String,
        phrase: String,
    ): Boolean {
        val paddedText =
            " $text "

        val paddedPhrase =
            " $phrase "

        return paddedText.contains(
            paddedPhrase
        )
    }

    private fun normalizePhrase(
        value: String,
    ): String {
        val decomposed =
            Normalizer.normalize(
                value.lowercase(
                    Locale.ROOT
                ),
                Normalizer.Form.NFD,
            )

        return decomposed
            .replace(
                DIACRITICS_REGEX,
                ""
            )
            .replace(
                NON_TEXT_REGEX,
                " "
            )
            .replace(
                WHITESPACE_REGEX,
                " "
            )
            .trim()
    }
}