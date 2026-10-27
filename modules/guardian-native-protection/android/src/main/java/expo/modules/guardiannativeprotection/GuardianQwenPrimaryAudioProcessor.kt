package expo.modules.guardiannativeprotection

import android.content.Context
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** One capture, FireRed streaming segmentation, non-stream confirmation, one Qwen decoder.
 * Neither acoustic level labels nor ASR output can declare speech present. */
internal class GuardianQwenPrimaryAudioProcessor(
    context: Context,
    private val decoder: GuardianQwenProgressiveDecoder,
    private val listener: Listener,
    private val profile: GuardianVoiceProfile = GuardianVoiceProfile.DISTANT,
    private val diagnostics: GuardianVoiceDiagnostics? = null,
    diagnosticWavDirectory: java.io.File? = null,
    private val speechOnlyGain: Boolean = false,
    private val confirmedGainMaximum: Float = 1f,
    continuousStudy: Boolean = false,
    studyModel: String = "GTCRN",
) : GuardianAudioEngine.AudioProcessor {
    interface Listener {
        fun onSpeechStarted()
        fun onSpeechEnded()
        fun onWarning(message: String)
    }

    // Load before capture. Missing/incompatible VAD must fail explicitly, never
    // silently fall back to RMS-only admission (the original hallucination bug).
    private val streamingVad = GuardianFireRedStreaming.load { name -> context.applicationContext.assets.open(name).use { it.readBytes() } }
    private val fireRed = try {
        GuardianFireRedConfirmation.load { name -> context.applicationContext.assets.open(name).use { it.readBytes() } }
    } catch (error: Throwable) { streamingVad.close(); throw error }
    private val wav = diagnosticWavDirectory?.let { directory ->
        try {
            GuardianDiagnosticWavWriter(directory, profile.name,
                diagnostics?.sessionId ?: java.util.UUID.randomUUID().toString(), diagnostics)
        } catch (error: Exception) {
            diagnostics?.event("WAV_UNAVAILABLE", "error" to error.javaClass.simpleName)
            null
        }
    }
    private val comparison = if (!continuousStudy && diagnosticWavDirectory != null && profile == GuardianVoiceProfile.NOISY)
        GuardianGtcrnComparison(java.io.File(diagnosticWavDirectory, "gtcrn-comparison"), profile, diagnostics) else null
    private val study = if (continuousStudy && diagnosticWavDirectory != null)
        GuardianContinuousStudy(context.applicationContext, diagnosticWavDirectory, diagnostics, decoder, studyModel) else null
    private val earlyStudyCalibrationGuard = studyModel == "WIENER_DD_ADAPTIVE"
    private val closed = AtomicBoolean(false)
    private val discontinuity = AtomicBoolean(false)
    private val pendingTasks = AtomicInteger(0)
    private val worker = Executors.newSingleThreadExecutor { task ->
        Thread(task, "Guardian-Speech-Input").apply { isDaemon = true }
    }
    private var previousKey: GuardianVoiceInputRouter.Key? = null
    private var warningAtMs = Long.MIN_VALUE
    private var truthFrames = 0L
    private var enhancedFrames = 0L
    private var lastMetricsAtMs = 0L
    private var lastDiagnosticFrameMs = Long.MIN_VALUE
    private var previousVad: Boolean? = null
    private val droppedInputTasks = AtomicInteger(0)
    private val channelHistory = GuardianChannelWindowHistory()
    private val segmenter = GuardianSpeechSegmenter(object : GuardianSpeechSegmenter.Sink {
        override fun onSpeechStarted() {
            diagnostics?.event("SPEECH_START")
            listener.onSpeechStarted()
        }
        override fun onSpeechEnded() {
            diagnostics?.event("SPEECH_END")
            listener.onSpeechEnded()
        }
        override fun onWindow(id: Long, pcm: FloatArray, final: Boolean, reason: String) =
            error("Segmenter must supply sample clock")
        override fun onWindowAt(id: Long, pcm: FloatArray, final: Boolean, reason: String, endSample: Long) {
            if (closed.get()) return
            // Only completed windows are validated/decoded. A partial must never
            // bypass the new acoustic admission gate.
            if (!final) {
                diagnostics?.event("FIRERED_PARTIAL_SKIPPED", "segment" to id)
                return
            }
            val validation = try { fireRed.confirm(pcm) } catch (error: Throwable) {
                diagnostics?.event("FIRERED_ERROR", "segment" to id, "error" to error.javaClass.simpleName)
                Log.e(TAG, "FireRed indisponible : segment=$id, Qwen non appelé", error)
                warn("FireRed indisponible : aucune validation pour ce segment")
                return
            }
            val coherent = if (validation.decision.accepted) confirmCoherentChannel(id, pcm, endSample, validation)
                else ChannelAdmission(false, false, "ORIGINAL_FIRERED_REJECT")
            val finalAdmission = validation.decision.accepted && coherent.accepted
            diagnostics?.event("FIRERED_RESULT", "segment" to id, "model" to GuardianFireRedConfirmation.MODEL_ID,
                "accepted" to finalAdmission, "originalAccepted" to validation.decision.accepted,
                "elapsedMs" to validation.elapsedMs + coherent.elapsedMs,
                "originalElapsedMs" to validation.elapsedMs,
                "channelGateRequired" to coherent.required, "channelGateReason" to coherent.reason,
                "channelAcceptedIndex" to coherent.channel,
                "peakProbability" to validation.decision.peakProbability,
                "longestPositiveFrames" to validation.decision.longestPositiveFrames,
                "audioMs" to pcm.size.toLong() * 1000 / 16000,
                "threshold" to GuardianFireRedPolicy.THRESHOLD,
                "maxInputGain" to GuardianFireRedPolicy.MAX_INPUT_GAIN)
            Log.i(TAG, "FIRERED_RESULT segment=$id accepted=$finalAdmission originalAccepted=${validation.decision.accepted} channelGate=${coherent.reason}")
            study?.window(id, pcm, endSample, finalAdmission)
            if (!finalAdmission) {
                diagnostics?.event("SEGMENT_WINDOW", "segment" to id, "final" to final,
                    "reason" to reason, "samples" to pcm.size, "accepted" to false,
                    "admission" to if (validation.decision.accepted) "COHERENT_CHANNEL_REJECT" else "FIRERED_REJECT")
                return
            }
            val segmentGain = if (speechOnlyGain) GuardianSpeechSegmentGain.apply(pcm, confirmed=true, maximumGain=confirmedGainMaximum) else null
            val asrPcm = segmentGain?.pcm ?: pcm
            if (segmentGain != null) diagnostics?.event("SEGMENT_GAIN", "segment" to id,
                "gain" to segmentGain.gain, "samples" to pcm.size, "evidence" to "NEURAL_CONFIRMED",
                "inputRmsDbfs" to GuardianVoiceDiagnostics.dbfs(GuardianVoiceDiagnostics.rms(pcm)))
            val evidence = GuardianQwenProgressiveDecoder.AcousticEvidence.NEURAL_CONFIRMED
            val accepted = if (final) {
                decoder.offerFinal(id, asrPcm, reason, evidence)
            } else {
                decoder.offerPartial(id, asrPcm, evidence)
            }
            diagnostics?.event("SEGMENT_WINDOW", "segment" to id, "final" to final,
                "reason" to reason, "samples" to pcm.size,
                "audioMs" to pcm.size.toLong() * 1000 / 16000,
                "rmsDbfs" to GuardianVoiceDiagnostics.dbfs(GuardianVoiceDiagnostics.rms(pcm)),
                "accepted" to accepted)
            if (!accepted && final) warn("Fenêtre Qwen finale refusée : segment=$id")
        }
    })
    private val router = GuardianVoiceInputRouter(
        noisy = false, // FireRed reads original PCM immediately; never wait for GTCRN.
        emit = { frame, pcm, enhanced ->
            val previous = previousKey
            if (previous != null && (frame.key.sequence != previous.sequence + 1 ||
                frame.key.capturedAtNanos <= previous.capturedAtNanos)) {
                resetSpeech()
                warn("Discontinuité audio : segment abandonné, VAD réinitialisée")
            }
            previousKey = frame.key
            truthFrames++
            if (enhanced) enhancedFrames++
            // Qwen is more robust on the original noisy consonants than on
            // aggressively denoised speech. Both FireRed passes use original PCM.
            val decoderPcm = frame.pcm
            channelHistory.append(decoderPcm.size, frame.captureChannels)
            wav?.write("asr", decoderPcm, frame.key.sequence, frame.key.capturedAtNanos)
            wav?.write("vad", GuardianNeuralInputGain.forVad(decoderPcm, 64f), frame.key.sequence, frame.key.capturedAtNanos)
            val streamResult = streamingVad.accept(decoderPcm)
            val speech = streamResult.speech
            // A noise profile must not wait for the segmenter's 8-frame start
            // hysteresis before noticing voice at the calibration boundary.
            // This conservative guard affects only shadow calibration.
            val studyCalibrationSpeech = speech || (earlyStudyCalibrationGuard && streamResult.peak >= .5f)
            val now = SystemClock.elapsedRealtime()
            if (diagnostics?.enabled == true) {
                if (previousVad != speech) {
                    diagnostics.event("VAD_CHANGE", "sequence" to frame.key.sequence, "speech" to speech,
                        "model" to GuardianFireRedStreaming.MODEL_ID, "peakProbability" to streamResult.peak,
                        "neuralFrames" to streamResult.frames)
                }
                if (lastDiagnosticFrameMs == Long.MIN_VALUE || now - lastDiagnosticFrameMs >= 250L || previousVad != speech) {
                    diagnostics.event("AUDIO_FRAME", "sequence" to frame.key.sequence,
                        "capturedAtNs" to frame.key.capturedAtNanos,
                        "captureChannelRevision" to frame.captureChannelRevision,
                        "captureRouteRevision" to frame.captureChannels?.routeRevision,
                        "openedCaptureChannels" to frame.captureChannels?.openedChannels,
                        "selectedCaptureChannel" to frame.captureChannels?.selectedChannel,
                        "captureChannelPayloadPresent" to (frame.captureChannels != null),
                        "captureChannelIndices" to frame.captureChannels?.channels?.map { it.index },
                        "usableCaptureChannels" to frame.captureChannels?.channels?.filter { it.usable }?.map { it.index },
                        "captureAgeMs" to (now - frame.key.capturedAtNanos / 1000000L).coerceAtLeast(0),
                        "inputWaitMs" to (now - frame.queuedAtMs).coerceAtLeast(0),
                        "route" to "TRUTH",
                        "vadRoute" to "FIRERED_STREAM_ORIGINAL",
                        "streamVadPeakProbability" to streamResult.peak,
                        "streamVadFrames" to streamResult.frames,
                        "streamVadElapsedMs" to streamResult.elapsedMs,
                        "studyCalibrationSpeech" to studyCalibrationSpeech,
                        "rawVadMaxInputGain" to 64,
                        "enhancementExpected" to frame.enhancementExpected,
                        "observedAtElapsedRealtimeMs" to now,
                        "vadSpeech" to speech, "gain" to frame.gain,
                        "preGainDbfs" to frame.preGainRms?.let { GuardianVoiceDiagnostics.dbfs(it) },
                        "noiseFloorDbfs" to frame.preGainNoiseRms?.let { GuardianVoiceDiagnostics.dbfs(it) },
                        "estimatedSnrDb" to frame.estimatedSnrDb,
                        "selectedDbfs" to GuardianVoiceDiagnostics.dbfs(GuardianVoiceDiagnostics.rms(decoderPcm)),
                        "vadViewDbfs" to GuardianVoiceDiagnostics.dbfs(GuardianVoiceDiagnostics.rms(GuardianNeuralInputGain.forVad(decoderPcm,64f))),
                        "peak" to decoderPcm.maxOfOrNull { kotlin.math.abs(it) },
                        "nearClipSamples" to decoderPcm.count { kotlin.math.abs(it) >= 0.979f },
                        "pendingInputTasks" to pendingTasks.get(),
                        "droppedInputTasks" to droppedInputTasks.get(),
                        "truthFrames" to truthFrames, "gtcrnFrames" to enhancedFrames)
                    lastDiagnosticFrameMs = now
                }
            }
            previousVad = speech
            study?.frame(decoderPcm, frame.key.sequence, frame.key.capturedAtNanos,studyCalibrationSpeech,
                frame.captureChannelRevision,frame.captureChannels)
            segmenter.accept(decoderPcm, speech)
            if (now - lastMetricsAtMs >= 5_000L) {
                Log.i(TAG, "VOICE_INPUT profile=$profile truthFrames=$truthFrames gtcrnFrames=$enhancedFrames")
                lastMetricsAtMs = now
            }
        },
        warning = { warn(it) },
    )

    init {
        Log.i(TAG, "FIRERED_READY model=${GuardianFireRedConfirmation.MODEL_ID} threshold=${GuardianFireRedPolicy.THRESHOLD}")
        diagnostics?.event("FIRERED_READY", "model" to GuardianFireRedConfirmation.MODEL_ID,
            "threshold" to GuardianFireRedPolicy.THRESHOLD, "maxInputGain" to GuardianFireRedPolicy.MAX_INPUT_GAIN,
            "candidateVad" to GuardianFireRedStreaming.MODEL_ID)
    }

    override fun onAudioFrame(frame: GuardianAudioEngine.AudioFrame) {
        if (closed.get()) return
        if (frame.sampleRateHz != 16_000) {
            discontinuity.set(true)
            return
        }
        val count = frame.sampleCount.coerceIn(0, frame.samples.size)
        if (count == 0) return
        // The engine reuses its arrays immediately after this callback.
        if (speechOnlyGain && (frame.detectorSampleCount != count || frame.detectorSamples.size < count)) {
            discontinuity.set(true)
            diagnostics?.event("PREGAIN_UNAVAILABLE", "sequence" to frame.sequenceNumber)
            return
        }
        val selected = if (speechOnlyGain) frame.detectorSamples else frame.samples
        val input = GuardianVoiceInputRouter.Input(
            key = GuardianVoiceInputRouter.Key(frame.sequenceNumber, frame.capturedAtElapsedRealtimeNanos),
            pcm = selected.copyOf(count),
            gain = if (speechOnlyGain) 1f else frame.adaptiveGain,
            enhancementExpected = frame.enhancementExpected,
            queuedAtMs = SystemClock.elapsedRealtime(),
            preGainRms = frame.acousticRms,
            preGainNoiseRms = frame.acousticNoiseFloorRms,
            estimatedSnrDb = frame.snrDb,
            captureChannelRevision = frame.captureChannelRevision,
            captureChannels = frame.captureChannels?.copyOwned(),
        )
        val preGain = if (wav != null) frame.detectorSamples.copyOf(
            frame.detectorSampleCount.coerceIn(0, frame.detectorSamples.size)) else null
        val raw = if (wav != null) frame.rawSamples.copyOf(
            frame.rawSampleCount.coerceIn(0, frame.rawSamples.size)) else null
        comparison?.primary(input)
        enqueue {
            if (raw != null) wav?.write("raw", raw, input.key.sequence, input.key.capturedAtNanos)
            if (preGain != null) wav?.write("pregain", preGain, input.key.sequence, input.key.capturedAtNanos)
            wav?.write("truth", input.pcm, input.key.sequence, input.key.capturedAtNanos)
            router.primary(input, SystemClock.elapsedRealtime())
        }
    }

    // No delayed enhancer output can enter the streaming detector or Qwen.
    override fun onEnhancedAudioFrame(frame: GuardianAudioEngine.EnhancedAudioFrame) {
        val target = comparison ?: return
        if (closed.get() || frame.source != "GTCRN") return
        val count = frame.sampleCount.coerceIn(0, frame.samples.size)
        if (count > 0) target.enhanced(GuardianVoiceInputRouter.Key(frame.sequenceNumber,
            frame.capturedAtElapsedRealtimeNanos), frame.samples.copyOf(count))
    }

    private fun enqueue(action: () -> Unit) {
        if (pendingTasks.incrementAndGet() > 32) {
            pendingTasks.decrementAndGet()
            droppedInputTasks.incrementAndGet()
            discontinuity.set(true)
            return
        }
        try {
            worker.execute {
                try { guarded(action) } finally { pendingTasks.decrementAndGet() }
            }
        } catch (_: RejectedExecutionException) {
            pendingTasks.decrementAndGet()
        }
    }

    private fun guarded(action: () -> Unit) {
        if (closed.get()) return
        try {
            if (discontinuity.getAndSet(false)) {
                router.reset()
                previousKey = null
                resetSpeech()
                warn("Saturation entrée audio : état réinitialisé")
            }
            action()
        } catch (error: Throwable) {
            Log.e(TAG, "Erreur du cœur vocal, aucune hypothèse publiée pour cette frame", error)
            discontinuity.set(true)
            warn("Erreur validation vocale : ${error.javaClass.simpleName}")
        }
    }

    private fun resetSpeech() {
        diagnostics?.event("PIPELINE_RESET")
        previousVad = null
        study?.invalidate("PRIMARY_DISCONTINUITY")
        decoder.reset()
        streamingVad.reset()
        segmenter.reset()
        channelHistory.reset()
    }

    private data class ChannelAdmission(val accepted: Boolean, val required: Boolean, val reason: String,
        val channel: Int? = null, val elapsedMs: Long = 0L)

    private fun confirmCoherentChannel(segment: Long, original: FloatArray, endSample: Long,
        originalResult: GuardianFireRedConfirmation.Result): ChannelAdmission {
        val window = channelHistory.window(endSample, original.size)
        var attempted = 0
        var elapsedMs = 0L
        fun result(accepted: Boolean, reason: String, channel: Int? = null): ChannelAdmission {
            diagnostics?.event("CHANNEL_COHERENCE_RESULT", "segment" to segment,
                "accepted" to accepted, "required" to window.required, "reason" to reason,
                "startSample" to endSample-original.size, "endSample" to endSample,
                "routeRevision" to window.routeRevision, "openedChannels" to window.openedChannels,
                "candidateChannels" to window.channels.map { it.index }, "attemptedChannels" to attempted,
                "acceptedChannel" to channel, "elapsedMs" to elapsedMs,
                "source" to "CONTINUOUS_HP_ORIGINAL_CHANNEL", "asrSource" to "UNCHANGED_PRIMARY_PCM")
            return ChannelAdmission(accepted, window.required, reason, channel, elapsedMs)
        }
        if (!window.valid) return result(false, window.reason)
        if (!window.required) return result(true, window.reason)
        for (channel in window.channels) {
            attempted++
            val reused = channel.pcm.contentEquals(original)
            val peak = channel.pcm.maxOf { kotlin.math.abs(it) }
            val gain = GuardianNeuralInputGain.gain(channel.pcm, GuardianFireRedPolicy.MAX_INPUT_GAIN)
            val confirmation = try {
                if (reused) originalResult else fireRed.confirm(channel.pcm)
            } catch (error: Throwable) {
                diagnostics?.event("FIRERED_CHANNEL_RESULT", "segment" to segment, "channel" to channel.index,
                    "accepted" to false, "reason" to "CONFIRMATION_ERROR", "error" to error.javaClass.simpleName,
                    "inputPeak" to peak, "inputGain" to gain, "conditionedPeak" to peak*gain,
                    "routeRevision" to window.routeRevision, "source" to "CONTINUOUS_HP_ORIGINAL_CHANNEL")
                continue
            }
            if (!reused) elapsedMs += confirmation.elapsedMs
            diagnostics?.event("FIRERED_CHANNEL_RESULT", "segment" to segment, "channel" to channel.index,
                "accepted" to confirmation.decision.accepted,
                "reason" to if (reused) "IDENTICAL_PRIMARY_PCM" else "COHERENT_ORIGINAL_CHANNEL",
                "reusedOriginalDecision" to reused,
                "peakProbability" to confirmation.decision.peakProbability,
                "longestPositiveFrames" to confirmation.decision.longestPositiveFrames,
                "elapsedMs" to if (reused) 0L else confirmation.elapsedMs,
                "inputPeak" to peak, "inputGain" to gain, "conditionedPeak" to peak*gain,
                "contributedSamples" to channel.contributedSamples, "usableSamples" to channel.usableSamples,
                "startSample" to endSample-original.size, "endSample" to endSample,
                "routeRevision" to window.routeRevision, "source" to "CONTINUOUS_HP_ORIGINAL_CHANNEL")
            if (confirmation.decision.accepted) return result(true, "COHERENT_CHANNEL_CONFIRMED", channel.index)
        }
        return result(false, "NO_COHERENT_CHANNEL_CONFIRMED")
    }

    private fun warn(message: String) {
        val now = SystemClock.elapsedRealtime()
        if (warningAtMs == Long.MIN_VALUE || now - warningAtMs >= 5_000L) {
            warningAtMs = now
            diagnostics?.event("WARNING", "message" to message)
            listener.onWarning(message)
        }
    }

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        diagnostics?.event("SESSION_END", "droppedInputTasks" to droppedInputTasks.get())
        study?.close()
        comparison?.close()
        decoder.close()
        // Same worker as VAD inference: no native release during a VAD call.
        worker.execute {
            try {
                router.reset()
                try { streamingVad.close() } finally { try { fireRed.close() } finally { wav?.close() } }
            } finally { worker.shutdownNow() }
        }
    }

    companion object { private const val TAG = "GuardianSpeechInput" }
}
