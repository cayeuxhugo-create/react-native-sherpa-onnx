package expo.modules.guardiannativeprotection

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineSpeechDenoiserGtcrnModelConfig
import com.k2fsa.sherpa.onnx.OfflineSpeechDenoiserModelConfig
import com.k2fsa.sherpa.onnx.OnlineSpeechDenoiser
import com.k2fsa.sherpa.onnx.OnlineSpeechDenoiserConfig

/**
 * Phase C2 - Speech Enhancement neural streaming GTCRN.
 *
 * Responsabilités :
 * - charger et posséder l'instance OnlineSpeechDenoiser ;
 * - valider le modèle au runtime ;
 * - adapter le flux continu Guardian au frameShift natif de GTCRN ;
 * - relever les signaux faibles avant le modele, compenser chaque sortie ;
 * - conserver la continuité PCM entre les appels Guardian ;
 * - ne publier qu'une frame Guardian complète ;
 * - ne jamais compléter artificiellement une sortie avec des zéros ;
 * - réinitialiser proprement tout l'état streaming entre deux sessions ;
 * - exposer des diagnostics bornés pour la validation C2.
 *
 * Cette classe ne connaît ni le VAD, ni Sherpa ASR, ni la logique Voice.
 */
class GuardianGtcrnEnhancer(
    context: Context,
) : GuardianAudioEngine.AudioEnhancer {

    data class Diagnostics(
        val released: Boolean,
        val modelSampleRateHz: Int,
        val frameShiftInSamples: Int,
        val inputBufferedSamples: Int,
        val outputBufferedSamples: Int,
        val maximumInputBufferedSamples: Int,
        val maximumOutputBufferedSamples: Int,
        val totalGuardianInputSamples: Long,
        val totalGtcrnInputSamples: Long,
        val totalGtcrnOutputSamples: Long,
        val totalGuardianOutputSamples: Long,
        val runCallCount: Long,
        val outputReadyCount: Long,
        val outputNotReadyCount: Long,
    )

    private val modelLeveler = GuardianGtcrnLeveler()

    private val lock =
        Any()

    @Volatile
    private var released =
        false

    private var denoiser: OnlineSpeechDenoiser? =
        null

    val modelSampleRateHz: Int

    val frameShiftInSamples: Int

    /*
     * FIFO d'entrée et de sortie.
     *
     * Une seconde de capacité à 16 kHz constitue une marge de sécurité
     * importante sans créer une seconde de latence : seuls les samples
     * réellement présents dans les FIFO comptent dans le retard courant.
     */
    private val inputBuffer =
        FloatArray(
            STREAM_BUFFER_CAPACITY_SAMPLES
        )

    private var inputReadIndex =
        0

    private var inputWriteIndex =
        0

    private var inputSampleCount =
        0

    private val outputBuffer =
        FloatArray(
            STREAM_BUFFER_CAPACITY_SAMPLES
        )

    private var outputReadIndex =
        0

    private var outputWriteIndex =
        0

    private var outputSampleCount =
        0

    /*
     * Scratch transmis au runtime GTCRN.
     * Sa taille est déterminée par le modèle, jamais codée en dur.
     */
    private lateinit var gtcrnInputScratch:
        FloatArray

    /*
     * Scratch rendu à GuardianAudioEngine.
     * Il grandit uniquement si nécessaire puis est réutilisé.
     */
    private var guardianOutputScratch =
        FloatArray(0)

    /*
     * Diagnostics de continuité et de charge.
     */
    private var maximumInputBufferedSamples =
        0

    private var maximumOutputBufferedSamples =
        0

    private var totalGuardianInputSamples =
        0L

    private var totalGtcrnInputSamples =
        0L

    private var totalGtcrnOutputSamples =
        0L

    private var totalGuardianOutputSamples =
        0L

    private var runCallCount =
        0L

    private var outputReadyCount =
        0L

    private var outputNotReadyCount =
        0L

    init {
        val applicationContext =
            context.applicationContext

        validateModelAsset(
            context =
                applicationContext
        )

        val createdDenoiser =
            createDenoiser(
                context =
                    applicationContext
            )

        try {
            val detectedSampleRate =
                createdDenoiser.sampleRate

            val detectedFrameShift =
                createdDenoiser.frameShiftInSamples

            require(
                detectedSampleRate ==
                    EXPECTED_SAMPLE_RATE_HZ
            ) {
                "GTCRN sampleRate inattendu : " +
                    "$detectedSampleRate Hz au lieu de " +
                    "$EXPECTED_SAMPLE_RATE_HZ Hz."
            }

            require(
                detectedFrameShift > 0
            ) {
                "GTCRN frameShiftInSamples invalide : " +
                    "$detectedFrameShift."
            }

            require(
                detectedFrameShift <=
                    STREAM_BUFFER_CAPACITY_SAMPLES
            ) {
                "GTCRN frameShift trop grand pour le buffer Guardian : " +
                    "$detectedFrameShift samples."
            }

            modelSampleRateHz =
                detectedSampleRate

            frameShiftInSamples =
                detectedFrameShift

            gtcrnInputScratch =
                FloatArray(
                    frameShiftInSamples
                )

            denoiser =
                createdDenoiser

            Log.i(
                TAG,
                "GTCRN prêt : " +
                    "sampleRate=$modelSampleRateHz Hz, " +
                    "frameShift=$frameShiftInSamples samples, " +
                    "bufferCapacity=$STREAM_BUFFER_CAPACITY_SAMPLES samples, " +
                    "threads=$NUM_THREADS, " +
                    "provider=$PROVIDER",
            )
        } catch (error: Throwable) {
            try {
                createdDenoiser.release()
            } catch (releaseError: Throwable) {
                Log.w(
                    TAG,
                    "Erreur pendant la libération de GTCRN après échec d'initialisation.",
                    releaseError,
                )
            }

            throw error
        }
    }

    override fun process(
        samples: FloatArray,
        sampleCount: Int,
        sampleRateHz: Int,
    ): GuardianAudioEngine.EnhancementResult {
        synchronized(lock) {
            val boundedSampleCount =
                sampleCount.coerceIn(
                    0,
                    samples.size,
                )

            if (released) {
                return passThrough(
                    samples =
                        samples,
                    sampleCount =
                        boundedSampleCount,
                )
            }

            require(
                sampleRateHz ==
                    modelSampleRateHz
            ) {
                "Sample rate Guardian incompatible avec GTCRN : " +
                    "$sampleRateHz Hz au lieu de " +
                    "$modelSampleRateHz Hz."
            }

            if (boundedSampleCount == 0) {
                outputNotReadyCount +=
                    1L

                return noOutputReady()
            }

            appendInputSamples(
                samples =
                    samples,
                sampleCount =
                    boundedSampleCount,
            )

            val currentDenoiser =
                denoiser
                    ?: throw IllegalStateException(
                        "GTCRN n'est pas disponible."
                    )

            /*
             * On consomme uniquement des frameShift complets.
             * Le reliquat reste dans la FIFO d'entrée pour l'appel suivant.
             */
            while (
                hasCompleteGtcrnInput()
            ) {
                popGtcrnInputIntoScratch()
                modelLeveler.prepareInPlace(gtcrnInputScratch)

                val denoisedAudio =
                    currentDenoiser.run(
                        gtcrnInputScratch,
                        modelSampleRateHz,
                    )

                runCallCount +=
                    1L

                require(
                    denoisedAudio.sampleRate ==
                        modelSampleRateHz
                ) {
                    "GTCRN a retourné un sampleRate inattendu : " +
                        "${denoisedAudio.sampleRate} Hz."
                }

                if (
                    denoisedAudio.samples.isNotEmpty()
                ) {
                    modelLeveler.restoreInPlace(denoisedAudio.samples)
                    appendOutputSamples(
                        samples =
                            denoisedAudio.samples,
                        sampleCount =
                            denoisedAudio.samples.size,
                    )
                }
            }

            /*
             * Guardian ne publie jamais une sortie partielle.
             * Pendant l'amorçage neural, la capture continue mais
             * aucun AudioFrame incomplet/original n'est injecté.
             */
            if (
                !hasGuardianOutput(
                    boundedSampleCount
                )
            ) {
                outputNotReadyCount +=
                    1L

                return noOutputReady()
            }

            val enhancedSamples =
                popGuardianOutput(
                    requestedSampleCount =
                        boundedSampleCount
                )

            totalGuardianOutputSamples +=
                boundedSampleCount.toLong()

            outputReadyCount +=
                1L

            return GuardianAudioEngine.EnhancementResult(
                samples =
                    enhancedSamples,
                sampleCount =
                    boundedSampleCount,
                /*
                 * Le chronométrage officiel est effectué par
                 * GuardianAudioEngine autour de process().
                 */
                processingTimeNanos =
                    0L,
                applied =
                    true,
                outputReady =
                    true,
            )
        }
    }

    override fun reset() {
        synchronized(lock) {
            if (released) {
                return
            }

            resetStreamingStateLocked(
                resetDiagnostics =
                    true
            )

            try {
                denoiser?.reset()
            } catch (error: Throwable) {
                Log.e(
                    TAG,
                    "Erreur pendant le reset de GTCRN.",
                    error,
                )

                throw error
            }

            Log.d(
                TAG,
                "GTCRN reset complet : " +
                    "inputBuffered=$inputSampleCount, " +
                    "outputBuffered=$outputSampleCount",
            )
        }
    }

    override fun release() {
        synchronized(lock) {
            if (released) {
                return
            }

            val diagnosticsBeforeRelease =
                buildDiagnosticsLocked()

            released =
                true

            /*
             * Pas de flush à la destruction :
             * Guardian ne publie pas de frame partielle
             * à une frontière STOP/PAUSE/RELEASE.
             */
            resetStreamingStateLocked(
                resetDiagnostics =
                    false
            )

            val currentDenoiser =
                denoiser

            denoiser =
                null

            try {
                currentDenoiser?.release()
            } catch (error: Throwable) {
                Log.w(
                    TAG,
                    "Erreur pendant la libération de GTCRN.",
                    error,
                )
            }

            Log.i(
                TAG,
                "GTCRN libéré : " +
                    "input=${diagnosticsBeforeRelease.totalGuardianInputSamples}, " +
                    "gtcrnIn=${diagnosticsBeforeRelease.totalGtcrnInputSamples}, " +
                    "gtcrnOut=${diagnosticsBeforeRelease.totalGtcrnOutputSamples}, " +
                    "guardianOut=${diagnosticsBeforeRelease.totalGuardianOutputSamples}, " +
                    "runs=${diagnosticsBeforeRelease.runCallCount}, " +
                    "ready=${diagnosticsBeforeRelease.outputReadyCount}, " +
                    "notReady=${diagnosticsBeforeRelease.outputNotReadyCount}",
            )
        }
    }

    fun getDiagnostics(): Diagnostics =
        synchronized(lock) {
            buildDiagnosticsLocked()
        }

    private fun validateModelAsset(
        context: Context,
    ) {
        try {
            context.assets
                .open(MODEL_ASSET_PATH)
                .use {
                    // Vérification d'existence uniquement.
                }
        } catch (error: Throwable) {
            throw IllegalStateException(
                "Modèle GTCRN introuvable dans les assets : $MODEL_ASSET_PATH",
                error,
            )
        }
    }

    private fun createDenoiser(
        context: Context,
    ): OnlineSpeechDenoiser {
        val config =
            OnlineSpeechDenoiserConfig(
                model =
                    OfflineSpeechDenoiserModelConfig(
                        gtcrn =
                            OfflineSpeechDenoiserGtcrnModelConfig(
                                model =
                                    MODEL_ASSET_PATH,
                            ),
                        numThreads =
                            NUM_THREADS,
                        debug =
                            false,
                        provider =
                            PROVIDER,
                    ),
            )

        return try {
            OnlineSpeechDenoiser(
                assetManager =
                    context.assets,
                config =
                    config,
            )
        } catch (error: Throwable) {
            throw IllegalStateException(
                "Impossible d'initialiser GTCRN.",
                error,
            )
        }
    }

    private fun appendInputSamples(
        samples: FloatArray,
        sampleCount: Int,
    ) {
        val count =
            sampleCount.coerceIn(
                0,
                samples.size,
            )

        if (count == 0) {
            return
        }

        require(
            count <=
                inputBuffer.size -
                    inputSampleCount
        ) {
            "FIFO entrée GTCRN saturée : " +
                "incoming=$count, " +
                "buffered=$inputSampleCount, " +
                "capacity=${inputBuffer.size}."
        }

        copyIntoCircularBuffer(
            source =
                samples,
            sourceCount =
                count,
            destination =
                inputBuffer,
            initialWriteIndex =
                inputWriteIndex,
        ) { newWriteIndex ->
            inputWriteIndex =
                newWriteIndex
        }

        inputSampleCount +=
            count

        totalGuardianInputSamples +=
            count.toLong()

        maximumInputBufferedSamples =
            maxOf(
                maximumInputBufferedSamples,
                inputSampleCount,
            )
    }

    private fun hasCompleteGtcrnInput(): Boolean =
        inputSampleCount >=
            frameShiftInSamples

    private fun popGtcrnInputIntoScratch() {
        require(
            inputSampleCount >=
                frameShiftInSamples
        ) {
            "Pas assez de samples dans la FIFO GTCRN : " +
                "buffered=$inputSampleCount, " +
                "required=$frameShiftInSamples."
        }

        inputReadIndex =
            copyFromCircularBuffer(
                source =
                    inputBuffer,
                initialReadIndex =
                    inputReadIndex,
                destination =
                    gtcrnInputScratch,
                count =
                    frameShiftInSamples,
            )

        inputSampleCount -=
            frameShiftInSamples

        totalGtcrnInputSamples +=
            frameShiftInSamples.toLong()
    }

    private fun appendOutputSamples(
        samples: FloatArray,
        sampleCount: Int,
    ) {
        val count =
            sampleCount.coerceIn(
                0,
                samples.size,
            )

        if (count == 0) {
            return
        }

        require(
            count <=
                outputBuffer.size -
                    outputSampleCount
        ) {
            "FIFO sortie GTCRN saturée : " +
                "incoming=$count, " +
                "buffered=$outputSampleCount, " +
                "capacity=${outputBuffer.size}."
        }

        copyIntoCircularBuffer(
            source =
                samples,
            sourceCount =
                count,
            destination =
                outputBuffer,
            initialWriteIndex =
                outputWriteIndex,
        ) { newWriteIndex ->
            outputWriteIndex =
                newWriteIndex
        }

        outputSampleCount +=
            count

        totalGtcrnOutputSamples +=
            count.toLong()

        maximumOutputBufferedSamples =
            maxOf(
                maximumOutputBufferedSamples,
                outputSampleCount,
            )
    }

    private fun hasGuardianOutput(
        requestedSampleCount: Int,
    ): Boolean =
        requestedSampleCount > 0 &&
            outputSampleCount >=
                requestedSampleCount

    private fun popGuardianOutput(
        requestedSampleCount: Int,
    ): FloatArray {
        require(
            requestedSampleCount > 0
        ) {
            "requestedSampleCount doit être > 0."
        }

        require(
            outputSampleCount >=
                requestedSampleCount
        ) {
            "Sortie GTCRN insuffisante : " +
                "buffered=$outputSampleCount, " +
                "requested=$requestedSampleCount."
        }

        if (
            guardianOutputScratch.size <
                requestedSampleCount
        ) {
            guardianOutputScratch =
                FloatArray(
                    requestedSampleCount
                )
        }

        outputReadIndex =
            copyFromCircularBuffer(
                source =
                    outputBuffer,
                initialReadIndex =
                    outputReadIndex,
                destination =
                    guardianOutputScratch,
                count =
                    requestedSampleCount,
            )

        outputSampleCount -=
            requestedSampleCount

        return guardianOutputScratch
    }

    private fun copyIntoCircularBuffer(
        source: FloatArray,
        sourceCount: Int,
        destination: FloatArray,
        initialWriteIndex: Int,
        onWriteIndexChanged: (Int) -> Unit,
    ) {
        var writeIndex =
            initialWriteIndex

        var sourceOffset =
            0

        var remaining =
            sourceCount

        while (remaining > 0) {
            val contiguousCapacity =
                destination.size -
                    writeIndex

            val copyCount =
                minOf(
                    remaining,
                    contiguousCapacity,
                )

            source.copyInto(
                destination =
                    destination,
                destinationOffset =
                    writeIndex,
                startIndex =
                    sourceOffset,
                endIndex =
                    sourceOffset +
                        copyCount,
            )

            writeIndex =
                (
                    writeIndex +
                        copyCount
                ) %
                    destination.size

            sourceOffset +=
                copyCount

            remaining -=
                copyCount
        }

        onWriteIndexChanged(
            writeIndex
        )
    }

    private fun copyFromCircularBuffer(
        source: FloatArray,
        initialReadIndex: Int,
        destination: FloatArray,
        count: Int,
    ): Int {
        var readIndex =
            initialReadIndex

        var destinationOffset =
            0

        var remaining =
            count

        while (remaining > 0) {
            val contiguousSamples =
                minOf(
                    remaining,
                    source.size -
                        readIndex,
                )

            source.copyInto(
                destination =
                    destination,
                destinationOffset =
                    destinationOffset,
                startIndex =
                    readIndex,
                endIndex =
                    readIndex +
                        contiguousSamples,
            )

            readIndex =
                (
                    readIndex +
                        contiguousSamples
                ) %
                    source.size

            destinationOffset +=
                contiguousSamples

            remaining -=
                contiguousSamples
        }

        return readIndex
    }

    private fun resetStreamingStateLocked(
        resetDiagnostics: Boolean,
    ) {
        modelLeveler.reset()
        inputReadIndex =
            0

        inputWriteIndex =
            0

        inputSampleCount =
            0

        outputReadIndex =
            0

        outputWriteIndex =
            0

        outputSampleCount =
            0

        if (
            this::gtcrnInputScratch.isInitialized
        ) {
            gtcrnInputScratch.fill(
                0.0f
            )
        }

        guardianOutputScratch.fill(
            0.0f
        )

        if (resetDiagnostics) {
            maximumInputBufferedSamples =
                0

            maximumOutputBufferedSamples =
                0

            totalGuardianInputSamples =
                0L

            totalGtcrnInputSamples =
                0L

            totalGtcrnOutputSamples =
                0L

            totalGuardianOutputSamples =
                0L

            runCallCount =
                0L

            outputReadyCount =
                0L

            outputNotReadyCount =
                0L
        }
    }

    private fun buildDiagnosticsLocked(): Diagnostics =
        Diagnostics(
            released =
                released,
            modelSampleRateHz =
                modelSampleRateHz,
            frameShiftInSamples =
                frameShiftInSamples,
            inputBufferedSamples =
                inputSampleCount,
            outputBufferedSamples =
                outputSampleCount,
            maximumInputBufferedSamples =
                maximumInputBufferedSamples,
            maximumOutputBufferedSamples =
                maximumOutputBufferedSamples,
            totalGuardianInputSamples =
                totalGuardianInputSamples,
            totalGtcrnInputSamples =
                totalGtcrnInputSamples,
            totalGtcrnOutputSamples =
                totalGtcrnOutputSamples,
            totalGuardianOutputSamples =
                totalGuardianOutputSamples,
            runCallCount =
                runCallCount,
            outputReadyCount =
                outputReadyCount,
            outputNotReadyCount =
                outputNotReadyCount,
        )

    private fun noOutputReady():
        GuardianAudioEngine.EnhancementResult =
        GuardianAudioEngine.EnhancementResult(
            samples =
                guardianOutputScratch,
            sampleCount =
                0,
            processingTimeNanos =
                0L,
            applied =
                true,
            outputReady =
                false,
        )

    private fun passThrough(
        samples: FloatArray,
        sampleCount: Int,
    ): GuardianAudioEngine.EnhancementResult =
        GuardianAudioEngine.EnhancementResult(
            samples =
                samples,
            sampleCount =
                sampleCount,
            processingTimeNanos =
                0L,
            applied =
                false,
            outputReady =
                true,
        )

    companion object {
        private const val TAG =
            "GuardianGtcrnEnhancer"

        private const val MODEL_ASSET_PATH =
            "gtcrn_simple.onnx"

        private const val EXPECTED_SAMPLE_RATE_HZ =
            16_000

        private const val STREAM_BUFFER_CAPACITY_SAMPLES =
            EXPECTED_SAMPLE_RATE_HZ

        private const val NUM_THREADS =
            1

        private const val PROVIDER =
            "cpu"
    }
}
