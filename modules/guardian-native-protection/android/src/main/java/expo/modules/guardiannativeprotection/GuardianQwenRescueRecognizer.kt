package expo.modules.guardiannativeprotection

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineQwen3AsrModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import java.io.File
import java.util.Locale

/**
 * Guardian Deep Ear - Qwen3-ASR offline.
 *
 * Rôle : deuxième famille ASR, uniquement dans le Rescue asynchrone.
 * Le modèle n'est jamais chargé dans la boucle audio temps réel.
 *
 * Le modèle est volontairement chargé depuis le stockage de l'application
 * et non depuis l'APK. Cela permet de remplacer/tester un modèle lourd sans
 * reconstruire Guardian Voice.
 */
class GuardianQwenRescueRecognizer(
    context: Context,
    private val configuration: Configuration,
) {

    data class Configuration(
        val enabled: Boolean = true,
        val modelDirectoryName: String =
            "sherpa-onnx-qwen3-asr-0.6B-int8-2026-03-25",
        val sampleRate: Int = 16_000,
        val featureDimension: Int = 128,
        val numberOfThreads: Int = 1,
        val provider: String = "cpu",
        val forcedLanguage: String = "French",
        val hotwords: List<String> = emptyList(),
        val maxTotalLen: Int = 512,
        val maxNewTokens: Int = 256,
        val temperature: Float = 1e-6f,
        val topP: Float = 0.8f,
        val seed: Int = 42,
        val debug: Boolean = false,
    ) {
        init {
            require(modelDirectoryName.isNotBlank()) {
                "Le dossier Qwen3-ASR ne peut pas être vide."
            }
            require(sampleRate > 0)
            require(featureDimension > 0)
            require(numberOfThreads > 0)
            require(maxTotalLen > 0)
            require(maxNewTokens > 0)
            require(temperature >= 0f)
            require(topP in 0.0f..1.0f)
            require(hotwords.none { it.isBlank() })
        }
    }

    data class Result(
        val text: String,
        val language: String,
        val elapsedMs: Long,
        val audioDurationMs: Long,
        val realTimeFactor: Float?,
    )

    /**
     * Etat de santé du moteur Qwen vu par Guardian.
     *
     * AVAILABLE signifie : modèle structurellement valide, mais recognizer
     * pas encore chargé. Ce n'est pas une panne.
     */
    enum class HealthStatus {
        DISABLED,
        MODEL_MISSING,
        MODEL_INVALID,
        AVAILABLE,
        LOADING,
        READY,
        DEGRADED,
        ERROR,
        RELEASED,
    }

    enum class ModelIntegrityStatus {
        NOT_APPLICABLE,
        MISSING,
        INCOMPLETE,
        VALID,
    }

    data class HealthSnapshot(
        val timestampMs: Long,
        val status: HealthStatus,

        val configured: Boolean,
        val released: Boolean,
        val initialized: Boolean,
        val initializationInProgress: Boolean,

        val modelVersion: String,
        val modelPath: String?,
        val modelIntegrity: ModelIntegrityStatus,
        val modelIntegrityOk: Boolean,
        val modelIntegrityReason: String,

        val initializationAttempts: Long,
        val initializationSuccesses: Long,
        val initializationFailures: Long,

        val decodeAttempts: Long,
        val decodeSuccesses: Long,
        val decodeFailures: Long,
        val consecutiveDecodeFailures: Int,

        val lastDecodeAtMs: Long?,
        val lastSuccessfulDecodeAtMs: Long?,
        val lastError: String?,
        val lastErrorAtMs: Long?,

        val lastDecodeMs: Long?,
        val averageDecodeMs: Double?,
        val p95DecodeMs: Long?,

        val lastRealTimeFactor: Float?,
        val averageRealTimeFactor: Double?,
        val p95RealTimeFactor: Float?,
    )

    private data class ModelIntegrityInspection(
        val status: ModelIntegrityStatus,
        val root: File?,
        val reason: String,
    )

    private data class ModelPaths(
        val root: File,
        val convFrontend: File,
        val encoder: File,
        val decoder: File,
        val tokenizer: File,
    )

    private val applicationContext = context.applicationContext

    @Volatile
    private var recognizer: OfflineRecognizer? = null

    @Volatile
    private var resolvedModelRoot: File? = null

    @Volatile
    private var released = false

    private val inferenceLock =
        Any()

    private val initializationLock =
        Any()

    private val healthLock =
        Any()

    @Volatile
    private var initializationInProgress =
        false

    private var initializationAttempts =
        0L

    private var initializationSuccesses =
        0L

    private var initializationFailures =
        0L

    private var decodeAttempts =
        0L

    private var decodeSuccesses =
        0L

    private var decodeFailures =
        0L

    private var consecutiveDecodeFailures =
        0

    private var lastDecodeAtMs:
        Long? = null

    private var lastSuccessfulDecodeAtMs:
        Long? = null

    private var lastError:
        String? = null

    private var lastErrorAtMs:
        Long? = null

    private var lastDecodeMs:
        Long? = null

    private var lastRealTimeFactor:
        Float? = null

    private var decodeElapsedTotalMs =
        0L

    private var decodeRtfTotal =
        0.0

    private var decodeRtfCount =
        0L

    private val decodeTimingWindowMs =
        LongArray(HEALTH_TIMING_WINDOW_SIZE)

    private val decodeRtfWindow =
        FloatArray(HEALTH_TIMING_WINDOW_SIZE)

    private var decodeTimingWindowCount =
        0

    private var decodeTimingWindowIndex =
        0

    private var decodeRtfWindowCount =
        0

    private var decodeRtfWindowIndex =
        0

    fun isConfigured(): Boolean =
        configuration.enabled

    fun isInitialized(): Boolean =
        recognizer != null && !released

    fun isModelAvailable(): Boolean =
        resolveModelPathsWithRetry(
            logMissing = false,
        ) != null

    fun getResolvedModelDirectory(): File? =
        resolvedModelRoot
            ?: resolveModelPathsWithRetry(
                logMissing = false,
            )?.root

    /**
     * Snapshot diagnostic sans forcer le chargement du modèle.
     *
     * Important : MODEL_INTEGRITY est ici un contrôle structurel local
     * (présence et cohérence minimale des fichiers), pas un SHA-256.
     */
    fun getHealthSnapshot(): HealthSnapshot {
        val integrity =
            inspectModelIntegrity()

        synchronized(healthLock) {
            val initializedNow =
                recognizer != null &&
                    !released

            val status =
                when {
                    !configuration.enabled ->
                        HealthStatus.DISABLED

                    released ->
                        HealthStatus.RELEASED

                    initializationInProgress ->
                        HealthStatus.LOADING

                    integrity.status ==
                        ModelIntegrityStatus.MISSING ->
                        HealthStatus.MODEL_MISSING

                    integrity.status ==
                        ModelIntegrityStatus.INCOMPLETE ->
                        HealthStatus.MODEL_INVALID

                    initializedNow &&
                        (
                            consecutiveDecodeFailures >=
                                DEGRADED_CONSECUTIVE_FAILURES ||
                                integrity.status !=
                                ModelIntegrityStatus.VALID
                            ) ->
                        HealthStatus.DEGRADED

                    initializedNow ->
                        HealthStatus.READY

                    initializationFailures > 0L &&
                        lastError != null ->
                        HealthStatus.ERROR

                    integrity.status ==
                        ModelIntegrityStatus.VALID ->
                        HealthStatus.AVAILABLE

                    else ->
                        HealthStatus.ERROR
                }

            val averageDecodeMs =
                if (decodeSuccesses > 0L) {
                    decodeElapsedTotalMs.toDouble() /
                        decodeSuccesses.toDouble()
                } else {
                    null
                }

            val averageRtf =
                if (decodeRtfCount > 0L) {
                    decodeRtfTotal /
                        decodeRtfCount.toDouble()
                } else {
                    null
                }

            return HealthSnapshot(
                timestampMs =
                    System.currentTimeMillis(),
                status =
                    status,
                configured =
                    configuration.enabled,
                released =
                    released,
                initialized =
                    initializedNow,
                initializationInProgress =
                    initializationInProgress,
                modelVersion =
                    configuration.modelDirectoryName,
                modelPath =
                    (
                        resolvedModelRoot
                            ?: integrity.root
                        )
                        ?.absolutePath,
                modelIntegrity =
                    integrity.status,
                modelIntegrityOk =
                    integrity.status ==
                        ModelIntegrityStatus.VALID,
                modelIntegrityReason =
                    integrity.reason,
                initializationAttempts =
                    initializationAttempts,
                initializationSuccesses =
                    initializationSuccesses,
                initializationFailures =
                    initializationFailures,
                decodeAttempts =
                    decodeAttempts,
                decodeSuccesses =
                    decodeSuccesses,
                decodeFailures =
                    decodeFailures,
                consecutiveDecodeFailures =
                    consecutiveDecodeFailures,
                lastDecodeAtMs =
                    lastDecodeAtMs,
                lastSuccessfulDecodeAtMs =
                    lastSuccessfulDecodeAtMs,
                lastError =
                    lastError,
                lastErrorAtMs =
                    lastErrorAtMs,
                lastDecodeMs =
                    lastDecodeMs,
                averageDecodeMs =
                    averageDecodeMs,
                p95DecodeMs =
                    percentile95(
                        decodeTimingWindowMs,
                        decodeTimingWindowCount,
                    ),
                lastRealTimeFactor =
                    lastRealTimeFactor,
                averageRealTimeFactor =
                    averageRtf,
                p95RealTimeFactor =
                    percentile95(
                        decodeRtfWindow,
                        decodeRtfWindowCount,
                    ),
            )
        }
    }

    /**
     * Charge paresseusement Qwen3. Cette méthode doit être appelée depuis
     * GuardianSpeechRescue, jamais depuis la boucle audio principale.
     */
    // Un unique objet natif : pas de decode/release concurrrents.
    fun initializeIfAvailable(): Boolean = synchronized(inferenceLock) {
        initializeInternal()
    }

    private fun initializeInternal(): Boolean {
        if (!configuration.enabled || released) {
            return false
        }

        if (recognizer != null) {
            return true
        }

        synchronized(initializationLock) {
            if (!configuration.enabled || released) {
                return false
            }

            if (recognizer != null) {
                return true
            }

            val paths =
                resolveModelPathsWithRetry(
                    logMissing = true,
                )
                    ?: return false

            recordInitializationStarted()

            return try {
                val hotwordsText =
                    configuration.hotwords
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }
                        .distinct()
                        .joinToString(",")

                val qwenConfig =
                    OfflineQwen3AsrModelConfig(
                        convFrontend = paths.convFrontend.absolutePath,
                        encoder = paths.encoder.absolutePath,
                        decoder = paths.decoder.absolutePath,
                        tokenizer = paths.tokenizer.absolutePath,
                        maxTotalLen = configuration.maxTotalLen,
                        maxNewTokens = configuration.maxNewTokens,
                        temperature = configuration.temperature,
                        topP = configuration.topP,
                        seed = configuration.seed,
                        hotwords = hotwordsText,
                    )

                val recognizerConfig =
                    OfflineRecognizerConfig(
                        featConfig =
                            FeatureConfig(
                                sampleRate = configuration.sampleRate,
                                featureDim = configuration.featureDimension,
                                dither = 0.0f,
                            ),
                        modelConfig =
                            OfflineModelConfig(
                                qwen3Asr = qwenConfig,
                                // Le Kotlin officiel Qwen3 utilise tokens="" :
                                // le tokenizer Qwen porte le vocabulaire.
                                tokens = "",
                                numThreads = configuration.numberOfThreads,
                                debug = configuration.debug,
                                provider = configuration.provider,
                            ),
                        decodingMethod = "greedy_search",
                    )

                recognizer =
                    OfflineRecognizer(
                        assetManager = null,
                        config = recognizerConfig,
                    )

                resolvedModelRoot = paths.root

                recordInitializationSucceeded()

                Log.i(
                    TAG,
                    "QWEN DEEP EAR READY : " +
                        "model=${paths.root.absolutePath}, " +
                        "threads=${configuration.numberOfThreads}, " +
                        "language=${configuration.forcedLanguage}, " +
                        "hotwords=${configuration.hotwords.size}"
                )

                true
            } catch (error: Throwable) {
                recordInitializationFailed(error)

                Log.e(
                    TAG,
                    "Impossible d'initialiser Qwen3-ASR Rescue. " +
                        "Le modèle reste éligible à une nouvelle tentative.",
                    error,
                )

                releaseRecognizerOnly()
                false
            }
        }
    }

    /**
     * Décode une utterance complète. L'appel est bloquant par conception,
     * mais il est confiné au worker Rescue asynchrone.
     */
    fun decode(samples: FloatArray): Result? = synchronized(inferenceLock) {
        decodeInternal(samples)
    }

    private fun decodeInternal(samples: FloatArray): Result? {
        if (samples.isEmpty() || released) {
            return null
        }

        recordDecodeAttempt()

        if (!initializeIfAvailable()) {
            recordDecodeFailure(
                message =
                    when {
                        !configuration.enabled ->
                            "Qwen désactivé."

                        !isModelAvailable() ->
                            "Modèle Qwen indisponible ou incomplet."

                        else ->
                            "Initialisation Qwen indisponible."
                    }
            )
            return null
        }

        val currentRecognizer =
            recognizer
                ?: run {
                    recordDecodeFailure(
                        "Recognizer Qwen absent après initialisation."
                    )
                    return null
                }

        val stream =
            try {
                currentRecognizer.createStream()
            } catch (error: Throwable) {
                recordDecodeFailure(
                    message =
                        "Création du stream Qwen impossible : ${error.message ?: error.javaClass.simpleName}",
                    error =
                        error,
                )
                Log.e(TAG, "Qwen : création du stream impossible.", error)
                return null
            }

        return try {
            val language =
                configuration.forcedLanguage.trim()

            if (language.isNotEmpty()) {
                stream.setOption(
                    "language",
                    language,
                )
            }

            val startedAtNs =
                System.nanoTime()

            stream.acceptWaveform(
                samples = samples,
                sampleRate = configuration.sampleRate,
            )

            currentRecognizer.decode(stream)

            val rawResult =
                currentRecognizer.getResult(stream)

            val elapsedMs =
                (System.nanoTime() - startedAtNs) /
                    1_000_000L

            val audioDurationMs =
                samples.size.toLong() * 1_000L /
                    configuration.sampleRate.toLong()

            val rtf =
                if (audioDurationMs > 0L) {
                    elapsedMs.toFloat() /
                        audioDurationMs.toFloat()
                } else {
                    null
                }

            val text =
                normalizeText(rawResult.text)

            recordDecodeSuccess(
                elapsedMs = elapsedMs,
                realTimeFactor = rtf,
            )

            if (configuration.debug) {
                Log.i(
                    TAG,
                    "QWEN DEEP EAR RESULT : " +
                        "text=\"$text\", " +
                        "elapsed=${elapsedMs}ms, " +
                        "audio=${audioDurationMs}ms, " +
                        "rtf=${rtf?.let { "%.3f".format(Locale.ROOT, it) } ?: "--"}"
                )
            }

            Result(
                text = text,
                language = rawResult.lang.ifBlank { language },
                elapsedMs = elapsedMs,
                audioDurationMs = audioDurationMs,
                realTimeFactor = rtf,
            )
        } catch (error: Throwable) {
            recordDecodeFailure(
                message =
                    "Décodage Qwen impossible : ${error.message ?: error.javaClass.simpleName}",
                error =
                    error,
            )

            Log.e(
                TAG,
                "Erreur pendant le décodage Qwen3-ASR Rescue.",
                error,
            )
            null
        } finally {
            try {
                stream.release()
            } catch (_: Throwable) {
                // Stream temporaire Qwen uniquement.
            }
        }
    }

    fun release() {
        synchronized(inferenceLock) {
            if (released) return
            released = true
            releaseRecognizerOnly()
        }
    }

    private fun releaseRecognizerOnly() {
        try {
            recognizer?.release()
        } catch (error: Throwable) {
            Log.w(
                TAG,
                "Erreur libération recognizer Qwen3-ASR.",
                error,
            )
        } finally {
            recognizer =
                null
        }
    }

    private fun recordInitializationStarted() {
        synchronized(healthLock) {
            initializationAttempts +=
                1L

            initializationInProgress =
                true
        }
    }

    private fun recordInitializationSucceeded() {
        synchronized(healthLock) {
            initializationSuccesses +=
                1L

            initializationInProgress =
                false

            lastError =
                null

            lastErrorAtMs =
                null
        }
    }

    private fun recordInitializationFailed(
        error: Throwable,
    ) {
        synchronized(healthLock) {
            initializationFailures +=
                1L

            initializationInProgress =
                false

            lastError =
                "Initialisation Qwen : " +
                    (
                        error.message
                            ?: error.javaClass.simpleName
                        )

            lastErrorAtMs =
                System.currentTimeMillis()
        }
    }

    private fun recordDecodeAttempt() {
        synchronized(healthLock) {
            decodeAttempts +=
                1L

            lastDecodeAtMs =
                System.currentTimeMillis()
        }
    }

    private fun recordDecodeSuccess(
        elapsedMs: Long,
        realTimeFactor: Float?,
    ) {
        synchronized(healthLock) {
            val now =
                System.currentTimeMillis()

            decodeSuccesses +=
                1L

            consecutiveDecodeFailures =
                0

            lastSuccessfulDecodeAtMs =
                now

            lastDecodeMs =
                elapsedMs

            decodeElapsedTotalMs +=
                elapsedMs

            decodeTimingWindowMs[
                decodeTimingWindowIndex
            ] = elapsedMs

            decodeTimingWindowIndex =
                (
                    decodeTimingWindowIndex +
                        1
                    ) %
                    HEALTH_TIMING_WINDOW_SIZE

            decodeTimingWindowCount =
                (
                    decodeTimingWindowCount +
                        1
                    )
                    .coerceAtMost(
                        HEALTH_TIMING_WINDOW_SIZE
                    )

            lastRealTimeFactor =
                realTimeFactor

            if (
                realTimeFactor != null &&
                realTimeFactor.isFinite() &&
                realTimeFactor >= 0.0f
            ) {
                decodeRtfTotal +=
                    realTimeFactor.toDouble()

                decodeRtfCount +=
                    1L

                decodeRtfWindow[
                    decodeRtfWindowIndex
                ] = realTimeFactor

                decodeRtfWindowIndex =
                    (
                        decodeRtfWindowIndex +
                            1
                        ) %
                        HEALTH_TIMING_WINDOW_SIZE

                decodeRtfWindowCount =
                    (
                        decodeRtfWindowCount +
                            1
                        )
                        .coerceAtMost(
                            HEALTH_TIMING_WINDOW_SIZE
                        )
            }

            /*
             * Une réussite réelle remet l'état courant à sain. L'historique
             * des compteurs d'échec reste évidemment conservé.
             */
            lastError =
                null

            lastErrorAtMs =
                null
        }
    }

    private fun recordDecodeFailure(
        message: String,
        error: Throwable? = null,
    ) {
        synchronized(healthLock) {
            decodeFailures +=
                1L

            consecutiveDecodeFailures +=
                1

            lastError =
                if (error != null) {
                    "$message (${error.javaClass.simpleName})"
                } else {
                    message
                }

            lastErrorAtMs =
                System.currentTimeMillis()
        }
    }

    private fun inspectModelIntegrity():
        ModelIntegrityInspection {
        if (!configuration.enabled) {
            return ModelIntegrityInspection(
                status =
                    ModelIntegrityStatus.NOT_APPLICABLE,
                root =
                    null,
                reason =
                    "Qwen est désactivé dans la configuration.",
            )
        }

        var firstExistingRoot:
            File? = null

        for (root in candidateModelRoots()) {
            if (root.exists() && firstExistingRoot == null) {
                firstExistingRoot =
                    root
            }

            val paths =
                modelPathsForRoot(root)

            if (paths != null) {
                val requiredNonEmptyFiles =
                    listOf(
                        paths.convFrontend,
                        paths.encoder,
                        paths.decoder,
                        File(paths.tokenizer, "vocab.json"),
                        File(paths.tokenizer, "tokenizer_config.json"),
                    )

                val emptyRequiredFile =
                    requiredNonEmptyFiles
                        .firstOrNull { file ->
                            file.length() <= 0L
                        }

                if (emptyRequiredFile == null) {
                    return ModelIntegrityInspection(
                        status =
                            ModelIntegrityStatus.VALID,
                        root =
                            root,
                        reason =
                            "Structure Qwen complète. " +
                                "Contrôle local des fichiers requis OK.",
                    )
                }

                return ModelIntegrityInspection(
                    status =
                        ModelIntegrityStatus.INCOMPLETE,
                    root =
                        root,
                    reason =
                        "Fichier Qwen requis vide : ${emptyRequiredFile.name}.",
                )
            }
        }

        return if (firstExistingRoot != null) {
            ModelIntegrityInspection(
                status =
                    ModelIntegrityStatus.INCOMPLETE,
                root =
                    firstExistingRoot,
                reason =
                    "Dossier Qwen présent mais fichiers requis incomplets.",
            )
        } else {
            ModelIntegrityInspection(
                status =
                    ModelIntegrityStatus.MISSING,
                root =
                    null,
                reason =
                    "Aucun dossier modèle Qwen trouvé.",
            )
        }
    }

    private fun candidateModelRoots():
        List<File> =
        buildList {
            add(
                File(
                    File(applicationContext.filesDir, "models"),
                    configuration.modelDirectoryName,
                )
            )

            applicationContext
                .getExternalFilesDir(null)
                ?.let { externalFiles ->
                    add(
                        File(
                            File(externalFiles, "models"),
                            configuration.modelDirectoryName,
                        )
                    )
                }

            /*
             * Guardian Voice V4.2 - fallback FUSE explicite.
             *
             * Certains firmwares Android exposent correctement le dossier
             * app-specific à ADB/run-as mais getExternalFilesDir() peut être
             * brièvement incohérent lors de la découverte paresseuse. Ces deux
             * racines pointent vers le même stockage, sans copier le modèle.
             */
            add(
                File(
                    "/storage/emulated/0/Android/data/" +
                        applicationContext.packageName +
                        "/files/models/" +
                        configuration.modelDirectoryName
                )
            )

            add(
                File(
                    "/sdcard/Android/data/" +
                        applicationContext.packageName +
                        "/files/models/" +
                        configuration.modelDirectoryName
                )
            )

            add(
                File(
                    File(applicationContext.noBackupFilesDir, "models"),
                    configuration.modelDirectoryName,
                )
            )
        }
            .distinctBy {
                it.absolutePath
            }

    private fun modelPathsForRoot(
        root: File,
    ): ModelPaths? {
        val convFrontend =
            File(root, "conv_frontend.onnx")
        val encoder =
            File(root, "encoder.int8.onnx")
        val decoder =
            File(root, "decoder.int8.onnx")
        val tokenizer =
            File(root, "tokenizer")

        val tokenizerVocab =
            File(tokenizer, "vocab.json")
        val tokenizerMerges =
            File(tokenizer, "merges.txt")
        val tokenizerConfig =
            File(tokenizer, "tokenizer_config.json")

        /*
         * exists()+length() est volontairement préféré à isFile sur les
         * racines FUSE externes : certains appareils ont déjà montré une
         * divergence de metadata alors que le fichier était lisible par
         * l'UID de Guardian.
         */
        return if (
            convFrontend.exists() &&
            convFrontend.length() > 0L &&
            encoder.exists() &&
            encoder.length() > 0L &&
            decoder.exists() &&
            decoder.length() > 0L &&
            tokenizer.exists() &&
            tokenizerVocab.exists() &&
            tokenizerVocab.length() > 0L &&
            tokenizerMerges.exists() &&
            tokenizerMerges.length() > 0L &&
            tokenizerConfig.exists() &&
            tokenizerConfig.length() > 0L
        ) {
            ModelPaths(
                root = root,
                convFrontend = convFrontend,
                encoder = encoder,
                decoder = decoder,
                tokenizer = tokenizer,
            )
        } else {
            null
        }
    }

    private fun percentile95(
        values: LongArray,
        count: Int,
    ): Long? {
        if (count <= 0) {
            return null
        }

        val boundedCount =
            count.coerceAtMost(values.size)

        val sorted =
            values
                .copyOf(boundedCount)

        sorted.sort()

        val index =
            (
                (boundedCount * 95 + 99) /
                    100 -
                    1
                )
                .coerceIn(
                    0,
                    boundedCount - 1,
                )

        return sorted[index]
    }

    private fun percentile95(
        values: FloatArray,
        count: Int,
    ): Float? {
        if (count <= 0) {
            return null
        }

        val boundedCount =
            count.coerceAtMost(values.size)

        val sorted =
            values
                .copyOf(boundedCount)

        sorted.sort()

        val index =
            (
                (boundedCount * 95 + 99) /
                    100 -
                    1
                )
                .coerceIn(
                    0,
                    boundedCount - 1,
                )

        return sorted[index]
    }

    /**
     * Le modèle est placé sur le stockage applicatif externe pour ne pas
     * gonfler l'APK. Juste après un redémarrage du moteur Voice, certains
     * appareils/FUSE peuvent rendre la racine brièvement indisponible.
     *
     * Le worker Rescue peut attendre quelques centaines de ms sans toucher
     * à la boucle audio temps réel. On retente donc la découverte avant de
     * conclure à tort "modèle absent".
     */
    private fun resolveModelPathsWithRetry(
        logMissing: Boolean,
    ): ModelPaths? {
        var lastResult:
            ModelPaths? = null

        for (
            attempt in
            1..MODEL_DISCOVERY_ATTEMPTS
        ) {
            lastResult =
                resolveModelPaths(
                    logMissing =
                        false,
                )

            if (lastResult != null) {
                if (
                    configuration.debug &&
                    attempt > 1
                ) {
                    Log.i(
                        TAG,
                        "QWEN MODEL DISCOVERY RECOVERED : " +
                            "attempt=$attempt, " +
                            "root=${lastResult.root.absolutePath}"
                    )
                }

                return lastResult
            }

            if (
                attempt <
                    MODEL_DISCOVERY_ATTEMPTS
            ) {
                try {
                    Thread.sleep(
                        MODEL_DISCOVERY_RETRY_DELAY_MS
                    )
                } catch (_: InterruptedException) {
                    Thread.currentThread()
                        .interrupt()
                    break
                }
            }
        }

        if (
            logMissing &&
            configuration.enabled
        ) {
            val candidates =
                candidateModelRoots()

            Log.w(
                TAG,
                "QWEN DEEP EAR UNAVAILABLE : modèle introuvable après " +
                    "$MODEL_DISCOVERY_ATTEMPTS tentative(s). " +
                    "Racines=" +
                    candidates.joinToString(
                        separator = " | "
                    ) { root ->
                        describeModelRoot(root)
                    }
            )
        }

        return null
    }

    private fun describeModelRoot(
        root: File,
    ): String {
        val tokenizer =
            File(
                root,
                "tokenizer",
            )

        return buildString {
            append(root.absolutePath)
            append("{root=")
            append(root.exists())
            append(",conv=")
            append(File(root, "conv_frontend.onnx").length())
            append(",enc=")
            append(File(root, "encoder.int8.onnx").length())
            append(",dec=")
            append(File(root, "decoder.int8.onnx").length())
            append(",tokDir=")
            append(tokenizer.isDirectory)
            append(",vocab=")
            append(File(tokenizer, "vocab.json").length())
            append(",merges=")
            append(File(tokenizer, "merges.txt").length())
            append(",config=")
            append(File(tokenizer, "tokenizer_config.json").length())
            append("}")
        }
    }

    private fun resolveModelPaths(
        logMissing: Boolean,
    ): ModelPaths? {
        val candidates =
            candidateModelRoots()

        for (root in candidates) {
            val paths =
                modelPathsForRoot(root)

            if (paths != null) {
                return paths
            }
        }

        if (logMissing && configuration.enabled) {
            Log.w(
                TAG,
                "QWEN DEEP EAR UNAVAILABLE : modèle absent. " +
                    "Cherché dans ${candidates.joinToString { it.absolutePath }}"
            )
        }

        return null
    }

    private fun normalizeText(text: String): String {
        val cleaned = text
            // Qwen peut émettre des jetons de contrôle internes.
            .replace(Regex("<\\|[A-Za-z0-9_.]+\\|>"), " ")
            .replace(Regex("[\\u0000-\\u001F\\u007F]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        return if (cleaned.any { it.isLetterOrDigit() }) cleaned else ""
    }

    companion object {
        private const val TAG = "GuardianQwenRescue"

        private const val HEALTH_TIMING_WINDOW_SIZE =
            50

        private const val DEGRADED_CONSECUTIVE_FAILURES =
            2

        private const val MODEL_DISCOVERY_ATTEMPTS =
            4

        private const val MODEL_DISCOVERY_RETRY_DELAY_MS =
            150L
    }
}
