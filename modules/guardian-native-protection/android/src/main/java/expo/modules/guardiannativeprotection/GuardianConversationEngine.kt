package expo.modules.guardiannativeprotection

import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Regroupe plusieurs segments Sherpa proches dans le temps
 * pour reconstruire un contexte conversationnel cohérent.
 *
 * IMPORTANT :
 * - ne capture aucun son ;
 * - ne remplace pas Sherpa ;
 * - ne bloque jamais l'analyse immédiate d'un segment ;
 * - ne persiste pas durablement le texte ;
 * - prépare l'arrivée future du speaker verification.
 */
class GuardianConversationEngine(
    private val configuration: Configuration =
        Configuration(),

    private val listener: Listener? =
        null,
) {

    data class Configuration(
        val continuationWindowMs: Long =
            DEFAULT_CONTINUATION_WINDOW_MS,

        val maxConversationDurationMs: Long =
            DEFAULT_MAX_CONVERSATION_DURATION_MS,

        val maxSegments: Int =
            DEFAULT_MAX_SEGMENTS,
    ) {
        init {
            require(
                continuationWindowMs in
                    500L..5_000L
            ) {
                "continuationWindowMs invalide."
            }

            require(
                maxConversationDurationMs in
                    2_000L..30_000L
            ) {
                "maxConversationDurationMs invalide."
            }

            require(
                maxSegments in
                    2..20
            ) {
                "maxSegments invalide."
            }
        }
    }

    interface Listener {

        fun onConversationFinalized(
            assembledText: String,
            segmentCount: Int,
        )
    }

    data class Segment(
        val text: String,

        // Identité du FINAL Sherpa. Sert à remplacer une correction tardive
        // sans créer un second segment dans la conversation.
        val originId: Long? =
            null,

        val receivedAtMs: Long =
            SystemClock.elapsedRealtime(),

        val confidence: Float? =
            null,

        /*
         * Préparé pour le futur Speaker Engine.
         */
        val speakerId: String? =
            null,

        val speakerSimilarity: Float? =
            null,
    )

    data class Snapshot(
        val segmentCount: Int,
        val assembledText: String,
        val startedAtMs: Long?,
        val lastSegmentAtMs: Long?,
        val ageMs: Long?,
    )

    private data class RecentlyFinalizedConversation(
        val segments: List<Segment>,
        val finalizedAtMs: Long,
    )

    /*
     * Scope interne dédié à la finalisation temporelle
     * des conversations.
     */
    private val scope =
        CoroutineScope(
            SupervisorJob() +
                Dispatchers.Default
        )

    /*
     * Un seul timer de finalisation à la fois.
     *
     * Chaque nouveau segment annule l'ancien timer
     * et repart pour continuationWindowMs.
     */
    private var finalizationJob:
        Job? = null

    private val lock =
        Any()

    private val segments =
        ArrayDeque<Segment>()

    private var conversationStartedAtMs: Long? =
        null

    private var lastSegmentAtMs: Long? =
        null

    private var speechActive =
        false

    /*
     * Lot 6G : une review Qwen peut arriver ~2 s après la finalisation
     * conversationnelle. On conserve brièvement les segments finalisés afin
     * de pouvoir corriger le même originId sans recréer un nouveau segment.
     */
    private var recentlyFinalizedConversation:
        RecentlyFinalizedConversation? =
        null

    /*
     * Lot 6L : une FINAL_REVIEW possède maintenant un vrai cycle de vie.
     *
     * Tant qu'un originId est présent ici, son contexte finalisé ne dépend
     * plus de la petite fenêtre temporelle du Lot 6G. Le TTL n'est qu'un
     * garde-fou si un worker secondaire est interrompu avant son callback de
     * fin ; il ne pilote jamais le fonctionnement normal.
     */
    private val pendingFinalReviews =
        mutableMapOf<Long, Long>()

    private val reviewProtectedConversations =
        mutableMapOf<Long, RecentlyFinalizedConversation>()

    fun onFinalReviewStarted(
        originId: Long,
    ) {
        synchronized(lock) {
            val nowMs =
                SystemClock.elapsedRealtime()

            cleanupExpiredFinalReviewsLocked(
                nowMs
            )

            pendingFinalReviews[originId] =
                nowMs

            Log.i(
                TAG,
                "Conversation FINAL_REVIEW protégée : " +
                    "originId=$originId, state=STARTED"
            )
        }
    }

    fun onFinalReviewCompleted(
        originId: Long,
    ) {
        synchronized(lock) {
            val nowMs =
                SystemClock.elapsedRealtime()

            cleanupExpiredFinalReviewsLocked(
                nowMs
            )

            val hadPending =
                pendingFinalReviews.remove(originId) != null

            val hadProtectedConversation =
                reviewProtectedConversations.remove(originId) != null

            Log.i(
                TAG,
                "Conversation FINAL_REVIEW protégée : " +
                    "originId=$originId, state=COMPLETED, " +
                    "pending=$hadPending, protected=$hadProtectedConversation"
            )
        }
    }

    fun onSpeechStarted() {
    synchronized(lock) {

        speechActive =
            true

        finalizationJob?.cancel()

        finalizationJob =
            null

        Log.d(
            TAG,
            "Conversation : parole active, " +
                "finalisation suspendue"
        )
    }
}

fun onSpeechEnded() {
    synchronized(lock) {

        speechActive =
            false

        if (segments.isNotEmpty()) {
            scheduleFinalizationLocked()
        }

        Log.d(
            TAG,
            "Conversation : parole terminée, " +
                "segments=${segments.size}"
        )
    }
}

    /**
     * Ajoute un segment FINAL provenant de Sherpa.
     *
     * Retourne le contexte assemblé courant.
     */
    fun addSegment(
        segment: Segment,
    ): String {

        val normalizedText =
            normalizeText(
                segment.text
            )

        if (
            normalizedText.isBlank()
        ) {
            return getAssembledText()
        }

        synchronized(lock) {

            val nowMs =
                segment.receivedAtMs

            /*
             * Protection :
             * aucune conversation ne doit grossir
             * indéfiniment.
             */
            val startedAt =
                conversationStartedAtMs

            if (
                startedAt != null &&
                nowMs -
                    startedAt >
                    configuration
                        .maxConversationDurationMs
            ) {
                clearLocked()
            }

            if (
                conversationStartedAtMs ==
                    null
            ) {
                conversationStartedAtMs =
                    nowMs
            }

            segments.addLast(
                segment.copy(
                    text =
                        normalizedText
                )
            )

            lastSegmentAtMs =
                nowMs

            /*
             * Protection supplémentaire :
             * on conserve au maximum quelques segments.
             */
            while (
                segments.size >
                    configuration.maxSegments
            ) {
                segments.removeFirst()
            }

            val assembled =
                buildAssembledTextLocked()

            /*
             * Chaque nouveau segment repousse
             * la finalisation de la conversation.
             */
            if (!speechActive) {
                scheduleFinalizationLocked()
            }

            Log.d(
                TAG,
                "Conversation : " +
                    "segments=${segments.size}, " +
                    "assembled=\"$assembled\""
            )

            return assembled
        }
    }

    /**
     * Remplace le texte d'un FINAL déjà présent dans la conversation.
     *
     * Une correction Qwen/Rescue est une révision de la même utterance,
     * jamais un nouveau segment. Si l'originId n'est plus présent (conversation
     * déjà finalisée), on n'ajoute rien : cela évite toute duplication tardive.
     */
    fun correctSegment(
        originId: Long?,
        correctedText: String,
        confidence: Float? = null,
    ): Boolean {
        val normalizedText =
            normalizeText(correctedText)

        if (
            originId == null ||
            normalizedText.isBlank()
        ) {
            return false
        }

        var finalizedCorrectionText: String? =
            null

        var finalizedCorrectionCount =
            0

        synchronized(lock) {
            val current =
                segments.toList()

            val index =
                current.indexOfFirst { segment ->
                    segment.originId == originId
                }

            if (index >= 0) {
                segments.clear()

                current.forEachIndexed { currentIndex, segment ->
                    segments.addLast(
                        if (currentIndex == index) {
                            segment.copy(
                                text = normalizedText,
                                confidence = confidence ?: segment.confidence,
                            )
                        } else {
                            segment
                        }
                    )
                }

                val assembled =
                    buildAssembledTextLocked()

                if (!speechActive) {
                    scheduleFinalizationLocked()
                }

                Log.i(
                    TAG,
                    "Conversation segment corrigé : " +
                        "originId=$originId, assembled=\"$assembled\""
                )

                return true
            }

            val nowMs =
                SystemClock.elapsedRealtime()

            cleanupExpiredFinalReviewsLocked(
                nowMs
            )

            val protected =
                reviewProtectedConversations[originId]

            if (protected != null) {
                val protectedIndex =
                    protected.segments.indexOfFirst { segment ->
                        segment.originId == originId
                    }

                if (protectedIndex >= 0) {
                    val correctedSegments =
                        protected.segments.mapIndexed { currentIndex, segment ->
                            if (currentIndex == protectedIndex) {
                                segment.copy(
                                    text = normalizedText,
                                    confidence = confidence ?: segment.confidence,
                                )
                            } else {
                                segment
                            }
                        }

                    val correctedSnapshot =
                        RecentlyFinalizedConversation(
                            segments = correctedSegments,
                            finalizedAtMs = protected.finalizedAtMs,
                        )

                    /*
                     * Plusieurs originId d'une même conversation peuvent avoir
                     * leur review en vol. Ils doivent tous observer la même
                     * version corrigée du snapshot, pas des copies divergentes.
                     */
                    correctedSegments
                        .mapNotNull { it.originId }
                        .filter { pendingFinalReviews.containsKey(it) }
                        .forEach { pendingOriginId ->
                            reviewProtectedConversations[pendingOriginId] =
                                correctedSnapshot
                        }

                    recentlyFinalizedConversation =
                        correctedSnapshot

                    finalizedCorrectionText =
                        buildAssembledText(
                            correctedSegments
                        )

                    finalizedCorrectionCount =
                        correctedSegments.size

                    Log.i(
                        TAG,
                        "Conversation finalisée corrigée (review protégée) : " +
                            "originId=$originId, " +
                            "assembled=\"$finalizedCorrectionText\""
                    )
                }
            }

            if (finalizedCorrectionText != null) {
                // Le callback produit est émis hors du synchronized ci-dessous.
            } else {
                val recent =
                    recentlyFinalizedConversation

            val recentAgeMs =
                recent
                    ?.let { nowMs - it.finalizedAtMs }
                    ?: Long.MAX_VALUE

            if (
                recent == null ||
                recentAgeMs > RECENT_FINAL_CORRECTION_WINDOW_MS
            ) {
                if (
                    recent != null &&
                    recentAgeMs > RECENT_FINAL_CORRECTION_WINDOW_MS
                ) {
                    recentlyFinalizedConversation =
                        null
                }

                Log.d(
                    TAG,
                    "Conversation correction ignorée : originId=$originId absent"
                )
                return false
            }

            val recentIndex =
                recent.segments.indexOfFirst { segment ->
                    segment.originId == originId
                }

            if (recentIndex < 0) {
                Log.d(
                    TAG,
                    "Conversation correction ignorée : originId=$originId absent"
                )
                return false
            }

            val correctedSegments =
                recent.segments.mapIndexed { currentIndex, segment ->
                    if (currentIndex == recentIndex) {
                        segment.copy(
                            text = normalizedText,
                            confidence = confidence ?: segment.confidence,
                        )
                    } else {
                        segment
                    }
                }

            recentlyFinalizedConversation =
                RecentlyFinalizedConversation(
                    segments = correctedSegments,
                    finalizedAtMs = recent.finalizedAtMs,
                )

            finalizedCorrectionText =
                buildAssembledText(
                    correctedSegments
                )

            finalizedCorrectionCount =
                correctedSegments.size

                Log.i(
                    TAG,
                    "Conversation finalisée corrigée : " +
                        "originId=$originId, age=${recentAgeMs}ms, " +
                        "assembled=\"$finalizedCorrectionText\""
                )
            }
        }

        val correctedFinalText =
            finalizedCorrectionText

        if (
            correctedFinalText != null &&
            correctedFinalText.isNotBlank()
        ) {
            listener
                ?.onConversationFinalized(
                    assembledText = correctedFinalText,
                    segmentCount = finalizedCorrectionCount,
                )

            return true
        }

        return false
    }

    /**
     * Programme la finalisation du contexte courant.
     *
     * Si un nouveau segment arrive avant la fin du délai,
     * ce job est annulé et remplacé par un nouveau.
     */
    private fun scheduleFinalizationLocked() {

        finalizationJob?.cancel()

        finalizationJob =
            scope.launch {

                delay(
                    configuration
                        .continuationWindowMs
                )

                val finalizedText:
                    String

                val finalizedSegmentCount:
                    Int

                val finalizedSegments:
                    List<Segment>

                synchronized(lock) {

                    if (speechActive) {
                        return@launch
                    }

                    if (
                        segments.isEmpty()
                    ) {
                        return@launch
                    }

                    finalizedText =
                        buildAssembledTextLocked()

                    finalizedSegmentCount =
                        segments.size

                    finalizedSegments =
                        segments.toList()

                    val finalizedAtMs =
                        SystemClock.elapsedRealtime()

                    cleanupExpiredFinalReviewsLocked(
                        finalizedAtMs
                    )

                    val finalizedSnapshot =
                        RecentlyFinalizedConversation(
                            segments = finalizedSegments,
                            finalizedAtMs = finalizedAtMs,
                        )

                    recentlyFinalizedConversation =
                        finalizedSnapshot

                    finalizedSegments
                        .mapNotNull { it.originId }
                        .filter { pendingFinalReviews.containsKey(it) }
                        .forEach { pendingOriginId ->
                            reviewProtectedConversations[pendingOriginId] =
                                finalizedSnapshot

                            Log.i(
                                TAG,
                                "Conversation FINAL_REVIEW snapshot conservé : " +
                                    "originId=$pendingOriginId"
                            )
                        }

                    /*
                     * On vide le contexte après avoir
                     * capturé son contenu final.
                     *
                     * IMPORTANT :
                     * ici on ne rappelle pas clearLocked(),
                     * car clearLocked() annule aussi
                     * finalizationJob, c'est-à-dire
                     * le job actuellement en train
                     * de finaliser.
                     */
                    segments.clear()

                    conversationStartedAtMs =
                        null

                    lastSegmentAtMs =
                        null

                    finalizationJob =
                        null
                }

                if (
                    finalizedText.isNotBlank()
                ) {

                    Log.d(
                        TAG,
                        "Conversation finalisée : " +
                            "segments=$finalizedSegmentCount, " +
                            "text=\"$finalizedText\""
                    )

                    listener
                        ?.onConversationFinalized(
                            assembledText =
                                finalizedText,

                            segmentCount =
                                finalizedSegmentCount,
                        )
                }
            }
    }

    fun getAssembledText():
        String {

        synchronized(lock) {
            return buildAssembledTextLocked()
        }
    }

    fun getSnapshot():
        Snapshot {

        synchronized(lock) {

            val nowMs =
                SystemClock.elapsedRealtime()

            return Snapshot(
                segmentCount =
                    segments.size,

                assembledText =
                    buildAssembledTextLocked(),

                startedAtMs =
                    conversationStartedAtMs,

                lastSegmentAtMs =
                    lastSegmentAtMs,

                ageMs =
                    lastSegmentAtMs
                        ?.let {
                            nowMs -
                                it
                        },
            )
        }
    }

    /**
     * Vide manuellement le contexte courant.
     */
    fun clear() {

        synchronized(lock) {
            clearLocked()
        }
    }

    /**
     * Arrêt définitif du moteur.
     *
     * À appeler depuis GuardianProtectionService.onDestroy().
     */
    fun destroy() {

        synchronized(lock) {
            clearLocked()
        }

        scope.cancel()
    }

    private fun clearLocked() {

        finalizationJob?.cancel()

        finalizationJob =
            null

        segments.clear()

        conversationStartedAtMs =
            null

        lastSegmentAtMs =
            null

        recentlyFinalizedConversation =
            null

        pendingFinalReviews.clear()
        reviewProtectedConversations.clear()
    }

    private fun cleanupExpiredFinalReviewsLocked(
        nowMs: Long,
    ) {
        val expiredOriginIds =
            pendingFinalReviews
                .filterValues { startedAtMs ->
                    nowMs - startedAtMs >
                        FINAL_REVIEW_SAFETY_TTL_MS
                }
                .keys
                .toList()

        expiredOriginIds.forEach { originId ->
            pendingFinalReviews.remove(originId)
            reviewProtectedConversations.remove(originId)

            Log.w(
                TAG,
                "Conversation FINAL_REVIEW expirée par sécurité : " +
                    "originId=$originId"
            )
        }
    }

    private fun buildAssembledTextLocked():
        String {

        return buildAssembledText(
            segments.toList()
        )
    }

    private fun buildAssembledText(
        sourceSegments: List<Segment>,
    ): String {
        return sourceSegments
            .asSequence()
            .map {
                normalizeText(
                    it.text
                )
            }
            .filter {
                it.isNotBlank()
            }
            .joinToString(
                separator = " "
            )
            .trim()
    }

    private fun normalizeText(
        text: String,
    ): String {

        return text
            .trim()
            .replace(
                WHITESPACE_REGEX,
                " "
            )
    }

    companion object {

        private const val TAG =
            "GuardianConversation"

        /*
         * Après 1,8 seconde sans nouveau segment Sherpa,
         * le contexte conversationnel est finalisé.
         */
        private const val DEFAULT_CONTINUATION_WINDOW_MS =
            1_800L

        /*
         * Une unité conversationnelle Guardian
         * ne peut pas dépasser 10 secondes.
         */
        private const val DEFAULT_MAX_CONVERSATION_DURATION_MS =
            10_000L

        /*
         * Protection contre une accumulation infinie.
         */
        private const val DEFAULT_MAX_SEGMENTS =
            6

        /*
         * Lot 6G : fenêtre courte pendant laquelle une review Qwen peut
         * corriger le dernier contexte déjà finalisé sans créer un segment.
         */
        private const val RECENT_FINAL_CORRECTION_WINDOW_MS =
            5_000L

        /*
         * Filet de sécurité uniquement : le chemin normal est libéré par
         * onFinalReviewCompleted(). 15 s couvre largement un chargement Qwen
         * à froid observé sur téléphone sans conserver le texte durablement.
         */
        private const val FINAL_REVIEW_SAFETY_TTL_MS =
            15_000L

        private val WHITESPACE_REGEX =
            Regex("\\s+")
    }
}