package expo.modules.guardiannativeprotection

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import java.util.UUID
import kotlin.math.roundToInt

class GuardianVoiceEngine(
    context: Context,
    private val config: GuardianNativeConfig,
    private val settings: Settings,
    private val listener: Listener,
) {

    interface Listener {
        fun onVoiceEvent(event: GuardianVoiceEvent)
        fun onVoiceStatusChanged(status: Status) = Unit
        fun getLastKnownLocation(): LastKnownLocation? = null
    }

    data class Settings(
        val enabled: Boolean = true,
        val wakePhrases: List<String> = listOf(
            "guardian",
            "maman",
            "papa",
            "code rouge",
        ),
        val emergencyPhrases: List<String> = listOf(
            "au secours",
            "aide moi",
            "j ai peur",
            "je suis en danger",
            "appelle les secours",
            "sos",
        ),
        val minimumConfidenceScore: Int = 72,
        val requireWakePhrase: Boolean = false,
        val wakeWindowSeconds: Int = 12,
        val eventCooldownSeconds: Int = 30,
    ) {
        init {
            require(wakePhrases.none { it.isBlank() }) {
                "Phrase de réveil Voice vide"
            }
            require(emergencyPhrases.none { it.isBlank() }) {
                "Phrase d’urgence Voice vide"
            }
            require(minimumConfidenceScore in 0..100) {
                "Seuil de confiance Voice invalide"
            }
            require(wakeWindowSeconds in 1..120) {
                "Fenêtre de réveil Voice invalide"
            }
            require(eventCooldownSeconds in 0..600) {
                "Cooldown Voice invalide"
            }
        }
    }

    data class LastKnownLocation(
        val latitude: Double,
        val longitude: Double,
        val accuracy: Float?,
    ) {
        init {
            require(latitude.isFinite() && latitude in -90.0..90.0) {
                "Latitude Voice invalide"
            }
            require(longitude.isFinite() && longitude in -180.0..180.0) {
                "Longitude Voice invalide"
            }
            require(
                accuracy == null ||
                    (accuracy.isFinite() && accuracy >= 0f)
            ) {
                "Précision Voice invalide"
            }
        }
    }

    data class Status(
        val running: Boolean,
        val recognizerAvailable: Boolean,
        val microphonePermissionGranted: Boolean,
        val phase: Phase,
        val listening: Boolean,
        val wakePhraseArmed: Boolean,
        val lastRecognizedText: String?,
        val lastConfidenceScore: Int?,
        val lastAudioAt: Long?,
        val lastWakePhrase: String?,
        val lastEmergencyPhrase: String?,
        val lastErrorCode: Int?,
        val restartCount: Int,
    )

    enum class Phase {
        STOPPED,
        STARTING,
        LISTENING,
        PROCESSING,
        COOLDOWN,
        WAITING_RESTART,
        ERROR,
    }

    /*
     * Guardian Dual-ASR V1.4.
     *
     * Snapshot du contexte exact au moment où Nemotron publie un FINAL.
     * Qwen pouvant répondre plusieurs secondes plus tard, la correction ne
     * doit jamais dépendre de l'état wake/cooldown d'une utterance suivante.
     */
    private data class FinalReviewContext(
        val sequence: Long,
        val originId: Long?,
        val originalText: String,
        val wakeAlreadyArmed: Boolean,
        val confirmedEventProduced: Boolean,
        val createdAtElapsed: Long,
    )

    /*
     * Guardian Emergency Latch V1.
     *
     * Un signal d'urgence fiable vu dans un PARTIAL doit survivre :
     * - aux réécritures du PARTIAL suivant ;
     * - à un FINAL tronqué ;
     * - aux resets de stream Sherpa ;
     * - au délai de la review Qwen.
     *
     * DETECTED est émis immédiatement une seule fois. FINAL/Qwen/conversation
     * peuvent ensuite escalader en CONFIRMED sans dépendre de ce texte partiel.
     */
    private data class EmergencyLatch(
        val detectedAtElapsed: Long,
        var lastSeenAtElapsed: Long,
        var confidenceScore: Int,
        var recognizedText: String,
        var wakePhrase: String?,
        var emergencyPhrase: String?,
        var cause: GuardianVoiceCause,
    )

    companion object {
        private const val TAG = "GuardianVoiceEngine"
        private const val MAX_RECOGNIZED_TEXT_LENGTH = 500

        /*
         * Une review Qwen observée sur nos tests terrain peut arriver plusieurs
         * secondes après le FINAL primaire. On garde donc un contexte borné,
         * sans laisser vivre une ancienne utterance indéfiniment.
         */
        private const val FINAL_REVIEW_CONTEXT_TTL_MS = 20_000L
        private const val MAX_PENDING_FINAL_REVIEWS = 8

        /*
         * Suffisamment long pour couvrir FINAL + Rescue Qwen, mais borné afin
         * qu'une ancienne conversation ne contamine pas une nouvelle situation.
         */
        private const val EMERGENCY_LATCH_TTL_MS = 15_000L
    }

    private val appContext = context.applicationContext

    private val understandingEngine =
        GuardianVoiceUnderstandingEngine(
            wakePhrases = settings.wakePhrases,
            emergencyPhrases = settings.emergencyPhrases,
        )
    
    private val fusionEngine =
    GuardianVoiceFusionEngine()

    /*
     * Les reviews Qwen sont asynchrones et peuvent arriver pendant une
     * utterance suivante. Elles utilisent donc leurs propres instances
     * Understanding/Fusion afin de ne jamais écraser le diagnostic temps réel.
     */
    private val correctionUnderstandingEngine =
        GuardianVoiceUnderstandingEngine(
            wakePhrases = settings.wakePhrases,
            emergencyPhrases = settings.emergencyPhrases,
        )

    private val correctionFusionEngine =
        GuardianVoiceFusionEngine()

    private val handlerThread =
        HandlerThread("GuardianVoiceThread")

    private var handler: Handler? = null
    private var cooldownRunnable: Runnable? = null

    @Volatile
    private var running = false

    @Volatile
    private var listening = false

    @Volatile
    private var phase = Phase.STOPPED

    private var destroyed = false
    private var wakePhraseArmedAtElapsed: Long? = null
    private var lastEventCreatedAtElapsed = 0L

    /*
     * Contextes FINAL en attente d'une éventuelle review Qwen.
     * Tous les accès normaux se font sur GuardianVoiceThread.
     */
    private val pendingFinalReviews =
        ArrayList<FinalReviewContext>()

    private var primaryFinalSequence =
        0L

    private var emergencyLatch:
        EmergencyLatch? = null

    private var lastRecognizedText: String? = null
    private var lastConfidenceScore: Int? = null
    private var lastAudioAt: Long? = null
    private var lastWakePhrase: String? = null
    private var lastEmergencyPhrase: String? = null
    private var lastErrorCode: Int? = null
    private var restartCount = 0

    @Synchronized
    fun start() {
        if (destroyed) {
            Log.e(TAG, "Impossible de redémarrer un moteur Voice détruit")
            return
        }

        if (running) {
            Log.d(TAG, "Moteur Voice déjà actif")
            return
        }

        if (!settings.enabled) {
            phase = Phase.STOPPED
            publishStatus()
            return
        }

        if (!hasMicrophonePermission()) {
            Log.e(TAG, "Permission RECORD_AUDIO absente")
            phase = Phase.ERROR
            publishStatus(recognizerAvailable = true)
            return
        }

        if (!handlerThread.isAlive) {
            handlerThread.start()
        }

        handler = Handler(handlerThread.looper)

        running = true
        resetSessionState(keepDiagnostics = false)
        listening = true
        phase = Phase.LISTENING

        Log.d(TAG, "Logique Voice prête à recevoir les résultats Sherpa")
        publishStatus(recognizerAvailable = true)
    }

    @Synchronized
    fun stop() {
        if (!running && phase == Phase.STOPPED) {
            return
        }

        running = false
        listening = false
        removePendingCallbacks()
        phase = Phase.STOPPED
        resetSessionState(keepDiagnostics = true)

        Log.d(TAG, "Moteur Voice arrêté")
        publishStatus(recognizerAvailable = true)
    }

    @Synchronized
    fun destroy() {
        if (destroyed) {
            return
        }

        running = false
        listening = false
        removePendingCallbacks()
        phase = Phase.STOPPED
        destroyed = true

        publishStatus(recognizerAvailable = false)

        if (handlerThread.isAlive) {
            handlerThread.quitSafely()
        }

        handler = null
        Log.d(TAG, "Moteur Voice détruit")
    }

    fun isRunning(): Boolean = running

    fun getStatus(): Status =
        buildStatus(
            recognizerAvailable = !destroyed
        )

     fun getUnderstandingDiagnostics():
        GuardianVoiceUnderstandingEngine.UnderstandingResult? =
        understandingEngine.getLastResult()   

     fun getFusionDiagnostics():
        GuardianVoiceFusionEngine.FusionResult? =
        fusionEngine.getLastResult()

    fun emitManualAlert(
        recognizedText: String? = null,
        confidenceScore: Int = 100,
    ) {
        if (!running) {
            Log.w(TAG, "Alerte Voice manuelle ignorée : moteur arrêté")
            return
        }

        val safeScore = confidenceScore.coerceIn(0, 100)

        handler?.post {
            emitVoiceEvent(
                cause = GuardianVoiceCause.MANUAL,
                state = GuardianVoiceState.CONFIRMED,
                confidenceScore = safeScore,
                recognizedText =
                    recognizedText?.take(MAX_RECOGNIZED_TEXT_LENGTH),
                wakePhrase = null,
                emergencyPhrase = null,
            )
        }
    }

    fun onSherpaPartialResult(
        result: GuardianAsrContract.RecognitionResult,
    ) {
        submitSherpaResult(
            result = result,
            partial = true,
        )
    }

    fun onSherpaFinalResult(
        result: GuardianAsrContract.RecognitionResult,
    ) {
        submitSherpaResult(
            result = result,
            partial = false,
        )
    }

    /*
     * Guardian Dual-ASR V1.4.
     *
     * Canal de correction distinct du FINAL primaire.
     * Il ne rappelle jamais submitSherpaResult() : une correction Qwen ne doit
     * pas devenir artificiellement un deuxième FINAL Nemotron.
     *
     * Si le FINAL primaire a déjà créé une alerte CONFIRMED, Qwen ne peut pas
     * en créer une deuxième. Si le primaire n'avait créé aucune alerte critique
     * et que la correction révèle une urgence fiable, Qwen peut la récupérer.
     */
    fun onSherpaFinalCorrection(
        original: GuardianAsrContract.RecognitionResult,
        corrected: GuardianAsrContract.RecognitionResult,
    ) {
        if (!running || destroyed) {
            return
        }

        val originalText =
            original.text
                .trim()
                .take(MAX_RECOGNIZED_TEXT_LENGTH)

        val correctedText =
            corrected.text
                .trim()
                .take(MAX_RECOGNIZED_TEXT_LENGTH)

        if (
            originalText.isBlank() ||
            correctedText.isBlank() ||
            originalText == correctedText
        ) {
            return
        }

        val correctedConfidence =
            corrected.confidence
                ?.takeIf { it.isFinite() }
                ?.coerceIn(0f, 1f)
                ?.let {
                    (it * 100f)
                        .roundToInt()
                        .coerceIn(0, 100)
                }

        handler?.post {
            if (!running || destroyed) {
                return@post
            }

            val reviewContext =
                takeFinalReviewContext(
                    originId = original.originId,
                    originalText = originalText,
                )

            if (reviewContext == null) {
                Log.w(
                    TAG,
                    "Correction Qwen ignorée : " +
                        "contexte FINAL primaire expiré ou introuvable, " +
                        "original=\"$originalText\", " +
                        "corrected=\"$correctedText\""
                )
                return@post
            }

            val isLatestPrimary =
                reviewContext.sequence ==
                    primaryFinalSequence

            val decisionProduced =
                analyzeCorrectedFinal(
                    rawText = correctedText,
                    recognizerConfidence = correctedConfidence,
                    wakeAlreadyArmedAtPrimary =
                        reviewContext.wakeAlreadyArmed,
                    primaryAlreadyConfirmed =
                        reviewContext.confirmedEventProduced,
                    isLatestPrimary =
                        isLatestPrimary,
                )

            Log.i(
                TAG,
                "Correction Qwen traitée : " +
                    "decision=$decisionProduced, " +
                    "sequence=${reviewContext.sequence}, " +
                    "latest=$isLatestPrimary, " +
                    "primaryConfirmed=${reviewContext.confirmedEventProduced}, " +
                    "original=\"$originalText\", " +
                    "corrected=\"$correctedText\""
            )

            /*
             * Une review ancienne ne doit jamais écraser l'état visuel de la
             * dernière utterance. Une review du dernier FINAL peut en revanche
             * rafraîchir le texte/diagnostic affiché.
             */
            if (
                isLatestPrimary &&
                phase != Phase.COOLDOWN
            ) {
                publishStatus()
            }
        }
    }

    fun onConversationFinalized(
    text: String,
    segmentCount: Int,
) {
    if (!running || destroyed) {
        return
    }

    val rawText =
        text
            .trim()
            .take(MAX_RECOGNIZED_TEXT_LENGTH)

    if (rawText.isBlank()) {
        return
    }

    handler?.post {
        if (!running || destroyed) {
            return@post
        }

        phase =
            Phase.PROCESSING

        val decisionProduced =
            analyzeRecognizedText(
                rawText =
                    rawText,

                recognizerConfidence =
                    null,

                partial =
                    false,

                conversationContext =
                    true,
            )

        Log.d(
            TAG,
            "Contexte conversationnel traité : " +
                "segments=$segmentCount, " +
                "decision=$decisionProduced, " +
                "text=$rawText"
        )

        if (phase != Phase.COOLDOWN) {
            phase =
                Phase.LISTENING
        }

        publishStatus()
    }
}

    private fun submitSherpaResult(
        result: GuardianAsrContract.RecognitionResult,
        partial: Boolean,
    ) {
        if (!running || destroyed) {
            return
        }

        val rawText =
            result.text
                .trim()
                .take(MAX_RECOGNIZED_TEXT_LENGTH)

        if (rawText.isBlank()) {
            return
        }

        val recognizerConfidence =
            result.confidence
                ?.takeIf { it.isFinite() }
                ?.coerceIn(0f, 1f)
                ?.let {
                    (it * 100f)
                        .roundToInt()
                        .coerceIn(0, 100)
                }

        handler?.post {
            if (!running || destroyed) {
                return@post
            }

            lastAudioAt = result.timestampMs
            phase = Phase.PROCESSING

            /*
             * Le contexte est pris AVANT l'analyse du FINAL primaire.
             * Ainsi une correction Qwen tardive ne dépendra jamais d'un wake
             * éventuellement armé par une phrase suivante.
             */
            val wakeAlreadyArmedBeforePrimary =
                if (!partial) {
                    isWakePhraseArmed()
                } else {
                    false
                }

            val finalSequence =
                if (!partial) {
                    primaryFinalSequence += 1L
                    primaryFinalSequence
                } else {
                    0L
                }

            val confirmedEventAtBeforePrimary =
                lastEventCreatedAtElapsed

            val decisionProduced =
                analyzeRecognizedText(
                    rawText = rawText,
                    recognizerConfidence = recognizerConfidence,
                    partial = partial,
                )

            if (!partial) {
                rememberFinalReviewContext(
                    sequence = finalSequence,
                    originId = result.originId,
                    originalText = rawText,
                    wakeAlreadyArmed =
                        wakeAlreadyArmedBeforePrimary,
                    confirmedEventProduced =
                        lastEventCreatedAtElapsed >
                            confirmedEventAtBeforePrimary,
                )
            }

            Log.d(
                TAG,
                "Résultat Sherpa traité : " +
                    "partial=$partial, " +
                    "decision=$decisionProduced, " +
                    "confidence=$recognizerConfidence, " +
                    "text=$rawText"
            )

            if (!partial && phase != Phase.COOLDOWN) {
                phase = Phase.LISTENING
            }

            publishStatus()
        }
    }

    private fun rememberFinalReviewContext(
        sequence: Long,
        originId: Long?,
        originalText: String,
        wakeAlreadyArmed: Boolean,
        confirmedEventProduced: Boolean,
    ) {
        val now =
            SystemClock.elapsedRealtime()

        pendingFinalReviews.removeAll {
            now - it.createdAtElapsed >
                FINAL_REVIEW_CONTEXT_TTL_MS
        }

        pendingFinalReviews.add(
            FinalReviewContext(
                sequence = sequence,
                originId = originId,
                originalText = originalText,
                wakeAlreadyArmed = wakeAlreadyArmed,
                confirmedEventProduced =
                    confirmedEventProduced,
                createdAtElapsed = now,
            )
        )

        while (
            pendingFinalReviews.size >
                MAX_PENDING_FINAL_REVIEWS
        ) {
            pendingFinalReviews.removeAt(0)
        }
    }

    private fun takeFinalReviewContext(
        originId: Long?,
        originalText: String,
    ): FinalReviewContext? {
        val now =
            SystemClock.elapsedRealtime()

        pendingFinalReviews.removeAll {
            now - it.createdAtElapsed >
                FINAL_REVIEW_CONTEXT_TTL_MS
        }

        /*
         * Lot 6F : l'originId est l'identité autoritaire. Deux phrases
         * identiques ne doivent jamais être confondues parce que leur texte
         * est le même. Le fallback texte n'existe que pour les anciens appels
         * dépourvus d'originId.
         */
        if (originId != null) {
            for (index in pendingFinalReviews.lastIndex downTo 0) {
                val candidate = pendingFinalReviews[index]
                if (candidate.originId == originId) {
                    /*
                     * Lexical V3.2 : les originId négatifs identifient une
                     * NATURAL_PHRASE reconstruite. Plusieurs mots de cette même
                     * phrase peuvent être corrigés successivement (Qwen local,
                     * puis review complète). On garde donc le snapshot primaire
                     * réutilisable jusqu'au TTL au lieu de le consommer dès la
                     * première correction.
                     *
                     * Les originId techniques historiques restent one-shot.
                     */
                    if (originId >= 0L) {
                        pendingFinalReviews.removeAt(index)
                    }
                    return candidate
                }
            }
            return null
        }

        for (index in pendingFinalReviews.lastIndex downTo 0) {
            val candidate = pendingFinalReviews[index]
            if (
                candidate.originId == null &&
                candidate.originalText == originalText
            ) {
                pendingFinalReviews.removeAt(index)
                return candidate
            }
        }

        return null
    }

    /*
     * Analyse d'une correction Qwen déjà validée par le consensus Dual-ASR.
     *
     * Important :
     * - aucun armement/désarmement du wake courant ;
     * - aucun événement DETECTED tardif ;
     * - un CONFIRMED n'est permis que si le primaire n'en avait pas déjà créé.
     *
     * On évite ainsi qu'une review arrivée plusieurs secondes plus tard
     * contamine l'utterance qui est actuellement en cours.
     */
    private fun analyzeCorrectedFinal(
        rawText: String,
        recognizerConfidence: Int?,
        wakeAlreadyArmedAtPrimary: Boolean,
        primaryAlreadyConfirmed: Boolean,
        isLatestPrimary: Boolean,
    ): Boolean {
        expireEmergencyLatchIfNeeded()

        val understanding =
            correctionUnderstandingEngine.analyze(
                rawText
            )

        if (
            understanding.normalizedText
                .isBlank()
        ) {
            return false
        }

        val fusion =
            correctionFusionEngine.fuse(
                recognizerConfidence =
                    recognizerConfidence,

                understanding =
                    understanding,

                wakeAlreadyArmed =
                    wakeAlreadyArmedAtPrimary,
            )

        val confidenceScore =
            fusion.confidenceScore

        if (isLatestPrimary) {
            lastRecognizedText =
                rawText

            lastConfidenceScore =
                confidenceScore

            lastEmergencyPhrase =
                understanding.emergencyPhrase
                    ?: understanding.evidence
                        .firstOrNull()
        }

        Log.i(
            TAG,
            "Voice Fusion Qwen correction : " +
                "level=${fusion.level}, " +
                "score=$confidenceScore, " +
                "emergency=${fusion.emergencyDetected}, " +
                "wake=${fusion.wakeDetected}, " +
                "wakeAtPrimary=$wakeAlreadyArmedAtPrimary, " +
                "primaryConfirmed=$primaryAlreadyConfirmed, " +
                "latest=$isLatestPrimary, " +
                "strongest=${fusion.strongestIntent}, " +
                "text=\"$rawText\""
        )

        if (
            confidenceScore <
                settings.minimumConfidenceScore
        ) {
            return false
        }

        if (!fusion.emergencyDetected) {
            return false
        }

        val wakeRequirementSatisfied =
            !settings.requireWakePhrase ||
                fusion.wakeDetected ||
                wakeAlreadyArmedAtPrimary

        if (!wakeRequirementSatisfied) {
            Log.d(
                TAG,
                "Correction Qwen urgence ignorée : " +
                    "phrase de réveil requise"
            )
            return false
        }

        /*
         * Le FINAL primaire a déjà produit l'alerte critique :
         * la correction enrichit Understanding/Fusion mais ne crée jamais
         * un second événement.
         */
        if (primaryAlreadyConfirmed) {
            clearEmergencyLatch(
                reason = "PRIMARY_ALREADY_CONFIRMED",
            )
            Log.i(
                TAG,
                "Correction Qwen validée sans nouvel événement : " +
                    "le FINAL primaire avait déjà créé CONFIRMED"
            )
            return true
        }

        if (isInEventCooldown()) {
            Log.d(
                TAG,
                "Correction Qwen urgence ignorée pendant le cooldown"
            )
            return true
        }

        val cause =
            if (
                fusion.wakeDetected ||
                wakeAlreadyArmedAtPrimary
            ) {
                GuardianVoiceCause.DISTRESS_COMMAND
            } else {
                GuardianVoiceCause.EMERGENCY_PHRASE
            }

        emitVoiceEvent(
            cause =
                cause,

            state =
                GuardianVoiceState.CONFIRMED,

            confidenceScore =
                confidenceScore,

            recognizedText =
                rawText,

            wakePhrase =
                understanding.wakePhrase
                    ?: lastWakePhrase
                        .takeIf {
                            wakeAlreadyArmedAtPrimary
                        },

            emergencyPhrase =
                understanding.emergencyPhrase
                    ?: understanding.evidence
                        .firstOrNull(),
        )

        clearEmergencyLatch(
            reason = "QWEN_CONFIRMED",
        )
        enterCooldown()

        return true
    }

    private fun analyzeRecognizedText(
    rawText: String,
    recognizerConfidence: Int?,
    partial: Boolean,
    conversationContext: Boolean = false,
): Boolean {
    expireWakePhraseIfNeeded()
    expireEmergencyLatchIfNeeded()

    val understanding =
        understandingEngine.analyze(
            rawText
        )

    if (
        understanding.normalizedText
            .isBlank()
    ) {
        return false
    }

    /*
     * État du wake AVANT de traiter la phrase actuelle.
     *
     * Exemple :
     * "maman"
     * puis
     * "quelqu'un me suit"
     */
    val wakeAlreadyArmed =
        isWakePhraseArmed()

    val fusion =
        fusionEngine.fuse(
            recognizerConfidence =
                recognizerConfidence,

            understanding =
                understanding,

            wakeAlreadyArmed =
                wakeAlreadyArmed,
        )

    val wakeDetected =
        fusion.wakeDetected

    val emergencyDetected =
        fusion.emergencyDetected

    val confidenceScore =
        fusion.confidenceScore

    lastRecognizedText =
        rawText

    lastConfidenceScore =
        confidenceScore

    /*
     * Ne jamais effacer une ancienne wake phrase avec null.
     * Et surtout : une hypothèse PARTIELLE ne doit pas
     * mémoriser définitivement le wake.
     */
    if (
        !partial &&
        understanding.wakePhrase != null
    ) {
        lastWakePhrase =
            understanding.wakePhrase
    }

    lastEmergencyPhrase =
        understanding.emergencyPhrase
            ?: understanding.evidence
                .firstOrNull()

    Log.d(
        TAG,
        "Voice Fusion : " +
            "level=${fusion.level}, " +
            "score=${fusion.confidenceScore}, " +
            "emergency=${fusion.emergencyDetected}, " +
            "wake=${fusion.wakeDetected}, " +
            "wakeAlreadyArmed=$wakeAlreadyArmed, " +
            "strongest=${fusion.strongestIntent}, " +
            "support=${fusion.supportingEmergencyIntentCount}, " +
            "reasons=${fusion.reasons}, " +
            "understanding=${understanding.primaryIntent}"
    )

    /*
     * Le seuil minimum reste une politique du VoiceEngine.
     * Fusion calcule la qualité de décision ;
     * VoiceEngine décide si elle est assez fiable pour agir.
     */
    if (
        confidenceScore <
        settings.minimumConfidenceScore
    ) {
        Log.d(
            TAG,
            "Décision Voice écartée : " +
                "score=$confidenceScore, " +
                "minimum=${settings.minimumConfidenceScore}, " +
                "level=${fusion.level}, " +
                "text=$rawText"
        )

        return false
    }

    /*
     * Une transcription PARTIELLE Sherpa
     * ne peut jamais armer le wake.
     */
    if (
    wakeDetected &&
    !partial &&
    !conversationContext
    ) {
        armWakePhrase()

        Log.d(
            TAG,
            "Wake Voice confirmé : " +
                "${understanding.wakePhrase}, " +
                "score=$confidenceScore"
        )
    }

    /*
     * Pas d'urgence finale.
     */
    if (!emergencyDetected) {

        /*
         * Une vraie wake phrase finale peut produire
         * un événement DETECTED.
         */
        if (
            wakeDetected &&
            !partial &&
            !conversationContext
        ) {
            emitVoiceEvent(
                cause =
                    GuardianVoiceCause.WAKE_PHRASE,

                state =
                    GuardianVoiceState.DETECTED,

                confidenceScore =
                    confidenceScore,

                recognizedText =
                    rawText,

                wakePhrase =
                    understanding.wakePhrase,

                emergencyPhrase =
                    null,
            )
        }

        /*
         * WATCH reste volontairement interne.
         * Aucun SOS n'est créé à partir d'un signal faible.
         */
        return wakeDetected ||
            fusion.level ==
                GuardianVoiceFusionEngine.Level.WATCH
    }

    /*
     * Guardian Emergency Latch V1.
     *
     * Une urgence suffisamment fiable vue dans un PARTIAL déclenche tout de
     * suite un événement DETECTED (priorité HIGH). On ne déclenche pas encore
     * CONFIRMED : le FINAL/Qwen/contexte gardent ce rôle.
     *
     * Important : requireWakePhrase ne bloque PAS le DETECTED de sécurité.
     * Cette option continue de gouverner l'escalade CONFIRMED, mais une vraie
     * phrase d'urgence ne doit jamais disparaître silencieusement.
     */
    if (partial) {
        return latchEmergencyFromPartial(
            rawText = rawText,
            confidenceScore = confidenceScore,
            wakeDetected = wakeDetected,
            wakeAlreadyArmed = wakeAlreadyArmed,
            wakePhrase = understanding.wakePhrase,
            emergencyPhrase =
                understanding.emergencyPhrase
                    ?: understanding.evidence
                        .firstOrNull(),
        )
    }

    /*
     * Politique optionnelle Guardian pour la confirmation critique.
     */
    val wakeRequirementSatisfied =
        !settings.requireWakePhrase ||
            wakeDetected ||
            wakeAlreadyArmed

    if (!wakeRequirementSatisfied) {
        Log.d(
            TAG,
            "Urgence Voice non confirmée : " +
                "phrase de réveil requise; " +
                "un éventuel DETECTED partiel reste conservé par le latch"
        )

        return emergencyLatch != null
    }

    if (isInEventCooldown()) {
        Log.d(
            TAG,
            "Alerte Voice ignorée pendant le cooldown"
        )

        return true
    }

    val cause =
        if (
            wakeDetected ||
            wakeAlreadyArmed
        ) {
            GuardianVoiceCause.DISTRESS_COMMAND
        } else {
            GuardianVoiceCause.EMERGENCY_PHRASE
        }

    emitVoiceEvent(
        cause =
            cause,

        state =
            GuardianVoiceState.CONFIRMED,

        confidenceScore =
            confidenceScore,

        recognizedText =
            rawText,

        wakePhrase =
            understanding.wakePhrase
                ?: lastWakePhrase
                    .takeIf {
                        wakeAlreadyArmed
                    },

        emergencyPhrase =
            understanding.emergencyPhrase
                ?: understanding.evidence
                    .firstOrNull(),
    )

    clearEmergencyLatch(
        reason = "FINAL_OR_CONVERSATION_CONFIRMED",
    )
    disarmWakePhrase()
    enterCooldown()

    return true
}

    /*
     * Enregistre une urgence PARTIAL sans spammer les événements.
     *
     * - premier signal fiable -> DETECTED immédiat ;
     * - PARTIAL suivants -> rafraîchissent le latch ;
     * - aucun cooldown n'est démarré ici afin de laisser FINAL/Qwen escalader.
     */
    private fun latchEmergencyFromPartial(
        rawText: String,
        confidenceScore: Int,
        wakeDetected: Boolean,
        wakeAlreadyArmed: Boolean,
        wakePhrase: String?,
        emergencyPhrase: String?,
    ): Boolean {
        val now =
            SystemClock.elapsedRealtime()

        expireEmergencyLatchIfNeeded(
            nowElapsed = now,
        )

        val cause =
            if (
                wakeDetected ||
                wakeAlreadyArmed
            ) {
                GuardianVoiceCause.DISTRESS_COMMAND
            } else {
                GuardianVoiceCause.EMERGENCY_PHRASE
            }

        val existing =
            emergencyLatch

        if (existing != null) {
            existing.lastSeenAtElapsed =
                now
            existing.confidenceScore =
                maxOf(
                    existing.confidenceScore,
                    confidenceScore,
                )

            if (
                rawText.length >
                    existing.recognizedText.length
            ) {
                existing.recognizedText =
                    rawText
            }

            if (wakePhrase != null) {
                existing.wakePhrase =
                    wakePhrase
            }

            if (emergencyPhrase != null) {
                existing.emergencyPhrase =
                    emergencyPhrase
            }

            if (
                cause ==
                    GuardianVoiceCause.DISTRESS_COMMAND
            ) {
                existing.cause =
                    cause
            }

            Log.w(
                TAG,
                "Emergency Latch rafraîchi : " +
                    "score=${existing.confidenceScore}, " +
                    "cause=${existing.cause}, " +
                    "text=\"${existing.recognizedText}\""
            )

            return true
        }

        emergencyLatch =
            EmergencyLatch(
                detectedAtElapsed = now,
                lastSeenAtElapsed = now,
                confidenceScore = confidenceScore,
                recognizedText = rawText,
                wakePhrase = wakePhrase,
                emergencyPhrase = emergencyPhrase,
                cause = cause,
            )

        /*
         * Un CONFIRMED récent signifie qu'une alerte critique est déjà partie.
         * On conserve le latch pour le contexte mais on évite un DETECTED
         * redondant pendant le cooldown.
         */
        if (!isInEventCooldown()) {
            emitVoiceEvent(
                cause = cause,
                state = GuardianVoiceState.DETECTED,
                confidenceScore = confidenceScore,
                recognizedText = rawText,
                wakePhrase = wakePhrase
                    ?: lastWakePhrase
                        .takeIf { wakeAlreadyArmed },
                emergencyPhrase = emergencyPhrase,
            )

            Log.w(
                TAG,
                "EMERGENCY LATCH DETECTED : " +
                    "score=$confidenceScore, " +
                    "cause=$cause, " +
                    "text=\"$rawText\""
            )
        } else {
            Log.i(
                TAG,
                "Emergency Latch créé pendant cooldown : " +
                    "DETECTED non dupliqué, text=\"$rawText\""
            )
        }

        return true
    }

    private fun expireEmergencyLatchIfNeeded(
        nowElapsed: Long =
            SystemClock.elapsedRealtime(),
    ) {
        val current =
            emergencyLatch
                ?: return

        if (
            nowElapsed -
                current.lastSeenAtElapsed <=
                EMERGENCY_LATCH_TTL_MS
        ) {
            return
        }

        Log.i(
            TAG,
            "Emergency Latch expiré : " +
                "age=${nowElapsed - current.lastSeenAtElapsed}ms, " +
                "text=\"${current.recognizedText}\""
        )

        emergencyLatch =
            null
    }

    private fun clearEmergencyLatch(
        reason: String,
    ) {
        val current =
            emergencyLatch
                ?: return

        Log.i(
            TAG,
            "Emergency Latch confirmé/libéré : " +
                "reason=$reason, " +
                "text=\"${current.recognizedText}\""
        )

        emergencyLatch =
            null
    }

    private fun emitVoiceEvent(
        cause: GuardianVoiceCause,
        state: GuardianVoiceState,
        confidenceScore: Int,
        recognizedText: String?,
        wakePhrase: String?,
        emergencyPhrase: String?,
    ) {
        val now = System.currentTimeMillis()

        val location =
            runCatching {
                listener.getLastKnownLocation()
            }.getOrElse { error ->
                Log.e(
                    TAG,
                    "Lecture de la dernière position Voice impossible",
                    error
                )
                null
            }

        val event =
            GuardianVoiceEvent(
                eventId = UUID.randomUUID().toString(),
                priority =
                    when (state) {
                        GuardianVoiceState.CONFIRMED ->
                            GuardianEventPriority.CRITICAL

                        GuardianVoiceState.DETECTED,
                        GuardianVoiceState.CANCELLED ->
                            GuardianEventPriority.HIGH
                    },
                createdAt = now,
                recordedAt = now,
                childId = config.childId,
                familyId = config.familyId,
                familyCode = config.familyCode,
                deviceId = config.deviceId,
                cause = cause,
                state = state,
                confidenceScore = confidenceScore.coerceIn(0, 100),
                recognizedText =
                    recognizedText?.take(MAX_RECOGNIZED_TEXT_LENGTH),
                wakePhrase = wakePhrase,
                emergencyPhrase = emergencyPhrase,
                latitude = location?.latitude,
                longitude = location?.longitude,
                accuracy = location?.accuracy,
            )

        if (state == GuardianVoiceState.CONFIRMED) {
            lastEventCreatedAtElapsed = SystemClock.elapsedRealtime()
        }

        Log.w(
            TAG,
            "Événement Voice généré : " +
                "eventId=${event.eventId}, " +
                "type=${event.eventType}, " +
                "cause=${event.cause}, " +
                "score=${event.confidenceScore}"
        )

        runCatching {
            listener.onVoiceEvent(event)
        }.onFailure { error ->
            Log.e(
                TAG,
                "Échec de transmission locale de l’événement Voice",
                error
            )
        }
    }

    private fun enterCooldown() {
        cooldownRunnable?.let {
            handler?.removeCallbacks(it)
        }

        cooldownRunnable = null
        phase = Phase.COOLDOWN
        listening = true
        publishStatus()

        val cooldownMs =
            settings.eventCooldownSeconds
                .coerceAtLeast(0)
                .toLong() * 1_000L

        if (cooldownMs <= 0L) {
            phase = Phase.LISTENING
            publishStatus()
            return
        }

        val runnable =
            Runnable {
                if (!running || destroyed) {
                    return@Runnable
                }

                cooldownRunnable = null
                phase = Phase.LISTENING
                listening = true
                publishStatus()
            }

        cooldownRunnable = runnable

        handler?.postDelayed(
            runnable,
            cooldownMs
        )
    }

    private fun armWakePhrase() {
        wakePhraseArmedAtElapsed = SystemClock.elapsedRealtime()
    }

    private fun disarmWakePhrase() {
        wakePhraseArmedAtElapsed = null
    }

    private fun isWakePhraseArmed(): Boolean {
        expireWakePhraseIfNeeded()
        return wakePhraseArmedAtElapsed != null
    }

    private fun expireWakePhraseIfNeeded() {
        val armedAt = wakePhraseArmedAtElapsed ?: return

        val wakeWindowMs =
            settings.wakeWindowSeconds
                .coerceAtLeast(1)
                .toLong() * 1_000L

        if (
            SystemClock.elapsedRealtime() - armedAt >
            wakeWindowMs
        ) {
            disarmWakePhrase()
        }
    }

    private fun isInEventCooldown(): Boolean {
        if (settings.eventCooldownSeconds <= 0) {
            return false
        }

        if (lastEventCreatedAtElapsed <= 0L) {
            return false
        }

        val cooldownMs =
            settings.eventCooldownSeconds
                .toLong() * 1_000L

        return (
            SystemClock.elapsedRealtime() -
                lastEventCreatedAtElapsed
            ) < cooldownMs
    }

    private fun hasMicrophonePermission(): Boolean =
        ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

    private fun removePendingCallbacks() {
        cooldownRunnable?.let {
            handler?.removeCallbacks(it)
        }

        cooldownRunnable = null
    }

    private fun resetSessionState(
        keepDiagnostics: Boolean,
    ) {
        wakePhraseArmedAtElapsed = null
        listening = false

        pendingFinalReviews.clear()
        primaryFinalSequence = 0L
        emergencyLatch = null

        if (!keepDiagnostics) {
            lastRecognizedText = null
            lastConfidenceScore = null
            lastAudioAt = null
            lastWakePhrase = null
            lastEmergencyPhrase = null
            lastErrorCode = null
            restartCount = 0
        }
    }

    private fun publishStatus(
        recognizerAvailable: Boolean = !destroyed,
    ) {
        runCatching {
            listener.onVoiceStatusChanged(
                buildStatus(
                    recognizerAvailable = recognizerAvailable
                )
            )
        }.onFailure { error ->
            Log.e(
                TAG,
                "Échec de publication du statut Voice",
                error
            )
        }
    }

    private fun buildStatus(
        recognizerAvailable: Boolean,
    ): Status =
        Status(
            running = running,
            recognizerAvailable = recognizerAvailable,
            microphonePermissionGranted = hasMicrophonePermission(),
            phase = phase,
            listening = listening,
            wakePhraseArmed = isWakePhraseArmed(),
            lastRecognizedText = lastRecognizedText,
            lastConfidenceScore = lastConfidenceScore,
            lastAudioAt = lastAudioAt,
            lastWakePhrase = lastWakePhrase,
            lastEmergencyPhrase = lastEmergencyPhrase,
            lastErrorCode = lastErrorCode,
            restartCount = restartCount,
        )
}