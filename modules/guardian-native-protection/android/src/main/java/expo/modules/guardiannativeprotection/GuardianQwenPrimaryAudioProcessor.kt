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
    private val diagnosticWavDirectory: java.io.File? = null,
    private val speechOnlyGain: Boolean = false,
    private val confirmedGainMaximum: Float = 1f,
    continuousStudy: Boolean = false,
    studyModel: String = "GTCRN",
    private val requireFsmnConfirmation: Boolean = false,
    referenceNoiseStudy: Boolean = false,
    private val adaptiveTranscription: Boolean = false,
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
    private val fsmn = if (requireFsmnConfirmation) try {
        GuardianFsmnConfirmation.load { name -> context.applicationContext.assets.open(name).use { it.readBytes() } }
    } catch (error: Throwable) {
        diagnostics?.event("FSMN_START_FAILED", "model" to GuardianFsmnConfirmation.MODEL_ID,
            "required" to true, "error" to error.javaClass.simpleName)
        try { fireRed.close() } catch (_: Throwable) { }
        try { streamingVad.close() } catch (_: Throwable) { }
        throw error
    } else null
    private val calibratedView = if (adaptiveTranscription) {
        require(requireFsmnConfirmation) { "Adaptive candidate requires both native validators" }
        GuardianCalibratedReferenceView(GuardianFireRedStreaming.load { name ->
            context.applicationContext.assets.open(name).use { it.readBytes() } })
    } else null
    private val wav = diagnosticWavDirectory?.let { directory ->
        try {
            GuardianDiagnosticWavWriter(directory, profile.name,
                diagnostics?.sessionId ?: java.util.UUID.randomUUID().toString(), diagnostics)
        } catch (error: Exception) {
            diagnostics?.event("WAV_UNAVAILABLE", "error" to error.javaClass.simpleName)
            null
        }
    }
    private val comparison = if (!continuousStudy && !referenceNoiseStudy && diagnosticWavDirectory != null && profile == GuardianVoiceProfile.NOISY)
        GuardianGtcrnComparison(java.io.File(diagnosticWavDirectory, "gtcrn-comparison"), profile, diagnostics) else null
    private val study = if (continuousStudy && diagnosticWavDirectory != null)
        GuardianContinuousStudy(context.applicationContext, diagnosticWavDirectory, diagnostics, decoder, studyModel) else null
    private val referenceStudy = if (referenceNoiseStudy && diagnosticWavDirectory != null)
        GuardianReferenceNoiseStudy(context.applicationContext, diagnosticWavDirectory, diagnostics, decoder) else null
    private val earlyStudyCalibrationGuard = studyModel == "WIENER_DD_ADAPTIVE" || referenceNoiseStudy
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
    private var previousAsrChannel: Int? = null
    private var previousAsrRoute: Long? = null
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
            if (adaptiveTranscription && final) {
                check(pendingAdaptiveWindows.size < 2) { "Adaptive window backlog" }
                pendingAdaptiveWindows.addLast(PendingWindow(id,pcm,reason,endSample))
            } else processCompletedWindow(id,pcm,final,reason,endSample)

        }
    })
    private data class PendingWindow(val id: Long, val pcm: FloatArray, val reason: String, val end: Long)
    private val pendingAdaptiveWindows = java.util.ArrayDeque<PendingWindow>()
    private fun processCompletedWindow(id: Long, pcm: FloatArray, final: Boolean, reason: String, endSample: Long) {
            if (closed.get()) return
            // Only completed windows are validated/decoded. A partial must never
            // bypass the new acoustic admission gate.
            if (!final) {
                diagnostics?.event("FIRERED_PARTIAL_SKIPPED", "segment" to id)
                return
            }
            val admissionStarted = SystemClock.elapsedRealtime()
            val validation = try { fireRed.confirm(pcm) } catch (error: Throwable) {
                diagnostics?.event("FIRERED_ERROR", "segment" to id, "error" to error.javaClass.simpleName)
                Log.e(TAG, "FireRed indisponible : segment=$id, Qwen non appelé", error)
                warn("FireRed indisponible : aucune validation pour ce segment")
                return
            }
            val priorRejection = if (!validation.decision.accepted) "SKIPPED_ORIGINAL_REJECT" else null
            val fsmnAdmission = confirmFsmn(id, pcm, priorRejection)
            val coherent = confirmCoherentChannel(id, pcm, endSample, validation, fsmnAdmission)
            val filtered = calibratedView?.window(endSample-pcm.size,endSample)
            var filteredFire: GuardianFireRedConfirmation.Result? = null
            var filteredFsmn: GuardianFsmnConfirmation.Result? = null
            val filteredConfirmationStarted = SystemClock.elapsedRealtime()
            try {
                filteredFire = filtered?.let { fireRed.confirm(it.pcm) }
                filteredFsmn = if (filteredFire?.decision?.accepted==true) fsmn?.confirm(checkNotNull(filtered).pcm) else null
            } catch(error: Throwable) {
                filteredFire=null; filteredFsmn=null
                diagnostics?.event("ADAPTIVE_VALIDATION_ERROR", "segment" to id, "error" to error.javaClass.simpleName)
            }
            val coherentWindow = if(filtered!=null) channelHistory.window(endSample,pcm.size) else null
            val filteredConfirmationMs = SystemClock.elapsedRealtime()-filteredConfirmationStarted
            // A suspect calibration cannot rescue an original refusal or
            // replace an admitted original. Both native checks still apply;
            // neither attenuation nor ASR words can admit this view.
            val useFiltered = filtered!=null && GuardianAdaptiveAdmissionPolicy.useFiltered(
                filtered.calibrationClean,coherentWindow?.valid==true,
                filteredFire?.decision?.accepted==true,filteredFsmn?.decision?.accepted==true,
                coherent.accepted,coherent.channel,filtered.primary)
            val finalAdmission = coherent.accepted || useFiltered
            val asrChannel = if(useFiltered) filtered?.primary else coherent.channel
            if (adaptiveTranscription) diagnostics?.event("ADAPTIVE_ADMISSION", "segment" to id,
                "originalAccepted" to coherent.accepted, "filteredAvailable" to (filtered!=null),
                "calibrationClean" to filtered?.calibrationClean,
                "acceptedProfileFrames" to filtered?.acceptedProfileFrames,
                "rejectedProfileFrames" to filtered?.rejectedProfileFrames,
                "filteredFireRedAccepted" to filteredFire?.decision?.accepted,
                "filteredFsmnAccepted" to filteredFsmn?.decision?.accepted,
                "filteredFsmnSpeechFrames" to filteredFsmn?.decision?.speechFrames,
                "usedFiltered" to useFiltered, "accepted" to finalAdmission,
                "policy" to "CLEAN_CALIBRATION_DUAL_NEURAL_CANDIDATE")
            val admissionReason = when {
                useFiltered -> "CALIBRATED_FILTERED_DUAL_NEURAL"
                finalAdmission -> "NEURAL_CONFIRMED"
                !coherent.required && !validation.decision.accepted -> "FIRERED_REJECT"
                !coherent.required && !fsmnAdmission.accepted -> if (fsmnAdmission.error) "FSMN_ERROR" else "FSMN_REJECT"
                else -> coherent.reason
            }
            diagnostics?.event("FIRERED_RESULT", "segment" to id, "model" to GuardianFireRedConfirmation.MODEL_ID,
                "accepted" to finalAdmission, "originalAccepted" to validation.decision.accepted,
                "confirmationMs" to validation.elapsedMs,
                "coherentGateMs" to coherent.elapsedMs,
                "fsmnConfirmationMs" to fsmnAdmission.confirmationMs,
                "filteredConfirmationMs" to filteredConfirmationMs,
                "admissionConfirmationMs" to (SystemClock.elapsedRealtime()-admissionStarted),
                "channelGateRequired" to coherent.required, "channelGateReason" to coherent.reason,
                "channelAcceptedIndex" to asrChannel,
                "coherentEvaluated" to coherent.evaluated,
                "coherentAccepted" to if (coherent.evaluated) coherent.accepted else null,
                "fsmnConfirmationRequired" to requireFsmnConfirmation,
                "fsmnAccepted" to if(useFiltered) filteredFsmn?.decision?.accepted else if (requireFsmnConfirmation) coherent.fsmnAccepted else null,
                "originalFsmnAccepted" to if (requireFsmnConfirmation && priorRejection == null) fsmnAdmission.accepted else null,
                "asrChannel" to asrChannel,
                "admissionReason" to admissionReason,
                "peakProbability" to validation.decision.peakProbability,
                "longestPositiveFrames" to validation.decision.longestPositiveFrames,
                "audioMs" to pcm.size.toLong() * 1000 / 16000,
                "threshold" to GuardianFireRedPolicy.THRESHOLD,
                "maxInputGain" to GuardianFireRedPolicy.MAX_INPUT_GAIN)
            Log.i(TAG, "FIRERED_RESULT segment=$id accepted=$finalAdmission originalAccepted=${validation.decision.accepted} admission=$admissionReason")
            study?.window(id, pcm, endSample, finalAdmission)
            // The reference-channel lane records its own decisions and forced
            // diagnostic decodes. It cannot replace this original admission.
            referenceStudy?.window(id, pcm, endSample, finalAdmission)
            if (!finalAdmission) {
                diagnostics?.event("SEGMENT_WINDOW", "segment" to id, "final" to final,
                    "reason" to reason, "samples" to pcm.size, "accepted" to false,
                    "admission" to admissionReason)
                return
            }
            val selectedPcm = if(useFiltered) checkNotNull(filtered).pcm else coherent.pcm ?: pcm
            val segmentGain = if (speechOnlyGain) GuardianSpeechSegmentGain.apply(selectedPcm, confirmed=true, maximumGain=confirmedGainMaximum) else null
            val asrPcm = segmentGain?.pcm ?: selectedPcm
            val exportedPrefix = if(adaptiveTranscription) exportAdaptiveWindow(id,pcm,asrPcm) else null
            diagnostics?.event("ASR_INPUT", "segment" to id, "channel" to asrChannel,
                "samples" to asrPcm.size, "sampleRateHz" to 16000,
                "startSample" to endSample-pcm.size, "endSample" to endSample,
                "windowExportPrefix" to exportedPrefix,
                "pcmSha256" to GuardianPcmIdentity.sha256(asrPcm),
                "source" to if(useFiltered) "CALIBRATED_REFERENCE_LONG" else if (coherent.channel == null) "PRIMARY_ORIGINAL_PCM" else "SELECTED_COHERENT_ORIGINAL_CHANNEL")
            if (segmentGain != null) diagnostics?.event("SEGMENT_GAIN", "segment" to id,
                "gain" to segmentGain.gain, "samples" to pcm.size, "evidence" to "NEURAL_CONFIRMED",
                "inputRmsDbfs" to GuardianVoiceDiagnostics.dbfs(GuardianVoiceDiagnostics.rms(selectedPcm)))
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
                "asrChannel" to asrChannel,
                "asrRmsDbfs" to GuardianVoiceDiagnostics.dbfs(GuardianVoiceDiagnostics.rms(asrPcm)),
                "accepted" to accepted)
            if (!accepted && final) warn("Fenêtre Qwen finale refusée : segment=$id")
    }
    private fun exportAdaptiveWindow(id: Long, original: FloatArray, asr: FloatArray): String? {
        val directory=diagnosticWavDirectory ?: return null
        val relative="adaptive-asr/${diagnostics?.sessionId ?: "unknown"}/segment-$id"
        return try {
            val writer=GuardianDiagnosticWavWriter(java.io.File(directory,relative),profile.name,"window",diagnostics,
                emitLifecycleEvents=false)
            try {
                writer.write("truth",original,id,0);writer.write("asr",asr,id,0)
                check(!writer.isClosed) { "Adaptive ASR window export failed" }
            } finally { writer.close() }
            "$relative/${writer.filePrefix}"
        } catch(error: Exception) {
            diagnostics?.event("ASR_EXPORT_ERROR", "segment" to id, "error" to error.javaClass.simpleName)
            null
        }
    }
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
            val filteredViewStarted = SystemClock.elapsedRealtime()
            val filteredSpeech = calibratedView?.accept(decoderPcm,frame.captureChannels,
                speech || streamResult.peak>=.5f) ?: false
            val filteredViewMs = SystemClock.elapsedRealtime()-filteredViewStarted
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
                        "filteredViewElapsedMs" to filteredViewMs,
                        "filteredVadSpeech" to filteredSpeech,
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
            referenceStudy?.frame(decoderPcm, frame.key.sequence, frame.key.capturedAtNanos,
                studyCalibrationSpeech, frame.captureChannelRevision, frame.captureChannels)
            // One real capture block covers the FFT retained tail. Never trim
            // a completed phrase or synthesize samples to make it available.
            while(pendingAdaptiveWindows.isNotEmpty() && channelHistory.end >= pendingAdaptiveWindows.first.end+800L) {
                val pending=pendingAdaptiveWindows.removeFirst()
                processCompletedWindow(pending.id,pending.pcm,true,pending.reason,pending.end)
            }
            segmenter.accept(decoderPcm, speech || filteredSpeech)
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
        if (requireFsmnConfirmation) Log.i(TAG,
            "FSMN_READY model=${GuardianFsmnConfirmation.MODEL_ID} executionBackend=${GuardianFsmnConfirmation.EXECUTION_BACKEND}")
        if (requireFsmnConfirmation) diagnostics?.event("FSMN_READY",
            "model" to GuardianFsmnConfirmation.MODEL_ID, "required" to true,
            "executionBackend" to GuardianFsmnConfirmation.EXECUTION_BACKEND,
            "source" to "PRIMARY_ORIGINAL_PCM", "targetPeak" to GuardianFsmnConfirmation.TARGET_PEAK,
            "maxInputGain" to GuardianFsmnConfirmation.MAX_INPUT_GAIN, "minInputGain" to 1f,
            "frameLength" to GuardianFsmnConfirmation.FRAME_LENGTH,
            "frameShift" to GuardianFsmnConfirmation.FRAME_SHIFT)
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
        referenceStudy?.invalidate("PRIMARY_DISCONTINUITY")
        decoder.reset()
        streamingVad.reset()
        segmenter.reset()
        calibratedView?.reset()
        pendingAdaptiveWindows.clear()
        channelHistory.reset()
        previousAsrChannel = null
        previousAsrRoute = null
    }

    private data class ChannelAdmission(val accepted: Boolean, val required: Boolean, val reason: String,
        val channel: Int? = null, val elapsedMs: Long = 0L, val pcm: FloatArray? = null,
        val fsmnAccepted: Boolean? = null, val evaluated: Boolean = false)

    private fun confirmCoherentChannel(segment: Long, original: FloatArray, endSample: Long,
        originalResult: GuardianFireRedConfirmation.Result, originalFsmn: FsmnAdmission): ChannelAdmission {
        val selectionStarted = SystemClock.elapsedRealtime()
        val window = channelHistory.window(endSample, original.size)
        if (window.routeRevision != previousAsrRoute) {
            previousAsrChannel = null
            previousAsrRoute = window.routeRevision
        }
        var attempted = 0
        var elapsedMs = 0L
        fun result(accepted: Boolean, reason: String, channel: Int? = null,
            pcm: FloatArray? = null, fsmnAccepted: Boolean? = null): ChannelAdmission {
            val gateMs = SystemClock.elapsedRealtime() - selectionStarted
            diagnostics?.event("CHANNEL_COHERENCE_RESULT", "segment" to segment,
                "accepted" to accepted, "required" to window.required, "reason" to reason,
                "startSample" to endSample-original.size, "endSample" to endSample,
                "routeRevision" to window.routeRevision, "openedChannels" to window.openedChannels,
                "candidateChannels" to window.channels.map { it.index }, "attemptedChannels" to attempted,
                "acceptedChannel" to channel, "asrChannel" to channel, "coherentGateMs" to gateMs,
                "nativeChannelConfirmationMs" to elapsedMs,
                "source" to "CONTINUOUS_HP_ORIGINAL_CHANNEL",
                "asrSource" to if (channel == null) "PRIMARY_ORIGINAL_PCM" else "SELECTED_COHERENT_ORIGINAL_CHANNEL")
            return ChannelAdmission(accepted, window.required, reason, channel, gateMs, pcm, fsmnAccepted, attempted > 0)
        }
        if (!window.valid) return result(false, window.reason)
        if (!window.required) return result(originalResult.decision.accepted && originalFsmn.accepted,
            window.reason, pcm=original, fsmnAccepted=originalFsmn.accepted)
        // A different microphone must not override an explicit original rejection.
        // The 5 m recording contains exactly this counterexample: the alternate
        // mic passes both models on a disturbance rejected by original FSMN.
        if (!originalResult.decision.accepted) return result(false, "FIRERED_REJECT")
        if (!originalFsmn.accepted) return result(false,
            if (originalFsmn.error) "FSMN_ERROR" else "FSMN_REJECT", fsmnAccepted=false)
        val candidates = window.channels.map { channel ->
            attempted++
            val reused = channel.pcm.contentEquals(original)
            val confirmation = try {
                if (reused) originalResult else fireRed.confirm(channel.pcm).also { elapsedMs += it.elapsedMs }
            } catch (error: Throwable) {
                diagnostics?.event("FIRERED_CHANNEL_RESULT", "segment" to segment, "channel" to channel.index,
                    "accepted" to false, "reason" to "CONFIRMATION_ERROR", "error" to error.javaClass.simpleName)
                return@map GuardianMicrophoneSelection.Candidate(channel.index, channel.pcm, false,
                    BooleanArray(1 + (original.size - 400) / 160), channel.contributedSamples)
            }
            val fsmnAccepted = when {
                !confirmation.decision.accepted -> false
                !requireFsmnConfirmation -> true
                reused -> originalFsmn.accepted
                else -> try {
                    val checked = checkNotNull(fsmn).confirm(channel.pcm)
                    elapsedMs += checked.confirmationMs
                    diagnostics?.event("FSMN_CHANNEL_RESULT", "segment" to segment, "channel" to channel.index,
                        "evaluated" to true, "accepted" to checked.decision.accepted,
                        "speechFrames" to checked.decision.speechFrames, "detectedSegments" to checked.decision.detectedSegments,
                        "inputGain" to checked.inputGain, "confirmationMs" to checked.confirmationMs)
                    checked.decision.accepted
                } catch (error: Throwable) {
                    diagnostics?.event("FSMN_CHANNEL_RESULT", "segment" to segment, "channel" to channel.index,
                        "evaluated" to true, "accepted" to false, "error" to error.javaClass.simpleName)
                    false
                }
            }
            diagnostics?.event("FIRERED_CHANNEL_RESULT", "segment" to segment, "channel" to channel.index,
                "accepted" to confirmation.decision.accepted, "fsmnAccepted" to fsmnAccepted,
                "reusedOriginalDecision" to reused, "peakProbability" to confirmation.decision.peakProbability,
                "longestPositiveFrames" to confirmation.decision.longestPositiveFrames,
                "contributedSamples" to channel.contributedSamples, "usableSamples" to channel.usableSamples,
                "startSample" to endSample-original.size, "endSample" to endSample,
                "routeRevision" to window.routeRevision, "source" to "CONTINUOUS_HP_ORIGINAL_CHANNEL")
            GuardianMicrophoneSelection.Candidate(channel.index, channel.pcm,
                confirmation.decision.accepted && fsmnAccepted, confirmation.speechSupport, channel.contributedSamples)
        }
        val selected = GuardianMicrophoneSelection.select(candidates, previousAsrChannel)
            ?: return result(false, "NO_NEURALLY_CONFIRMED_MICROPHONE", fsmnAccepted=false)
        for (candidate in candidates) {
            val quality = selected.qualities.getValue(candidate.index)
            diagnostics?.event("ASR_MICROPHONE_QUALITY", "segment" to segment, "channel" to candidate.index,
                "confirmed" to candidate.confirmed, "contrastDb" to quality.contrastDb,
                "clippedFraction" to quality.clippedFraction, "selected" to (candidate.index == selected.candidate.index),
                "routeRevision" to window.routeRevision)
        }
        previousAsrChannel = selected.candidate.index
        diagnostics?.event("ASR_MICROPHONE_SELECTION", "segment" to segment,
            "routeRevision" to window.routeRevision, "selectedChannel" to selected.candidate.index,
            "reason" to selected.reason, "comparedChannels" to candidates.map { it.index },
            "confirmedChannels" to candidates.filter { it.confirmed }.map { it.index },
            "contrastDb" to candidates.map { selected.qualities.getValue(it.index).contrastDb },
            "clippedFraction" to candidates.map { selected.qualities.getValue(it.index).clippedFraction },
            "startSample" to endSample-original.size, "endSample" to endSample,
            "scope" to "WHOLE_UTTERANCE_SPEECH_BAND_CONTRAST_NOT_ASR_ACCURACY")
        return result(true, "COHERENT_CHANNEL_CONFIRMED", selected.candidate.index, selected.candidate.pcm, true)
    }

    private data class FsmnAdmission(val accepted: Boolean, val confirmationMs: Long = 0L, val error: Boolean = false)

    private fun confirmFsmn(segment: Long, original: FloatArray, priorRejection: String?): FsmnAdmission {
        if (!requireFsmnConfirmation) return FsmnAdmission(true)
        if (priorRejection != null) {
            diagnostics?.event("FSMN_RESULT", "segment" to segment,
                "model" to GuardianFsmnConfirmation.MODEL_ID, "source" to "PRIMARY_ORIGINAL_PCM",
                "evaluated" to false, "success" to false, "accepted" to false, "reason" to priorRejection)
            return FsmnAdmission(false)
        }
        val started = System.nanoTime()
        val cpuStarted = SystemClock.currentThreadTimeMillis()
        val result = try { checkNotNull(fsmn) { "Required FSMN unavailable" }.confirm(original) }
        catch (error: Throwable) {
            val confirmationMs = (System.nanoTime() - started) / 1_000_000L
            diagnostics?.event("FSMN_ERROR", "segment" to segment, "model" to GuardianFsmnConfirmation.MODEL_ID,
                "source" to "PRIMARY_ORIGINAL_PCM", "error" to error.javaClass.simpleName)
            diagnostics?.event("FSMN_RESULT", "segment" to segment,
                "model" to GuardianFsmnConfirmation.MODEL_ID, "source" to "PRIMARY_ORIGINAL_PCM",
                "evaluated" to true, "success" to false, "accepted" to false, "reason" to "INFERENCE_ERROR",
                "confirmationMs" to confirmationMs,
                "confirmationCpuMs" to SystemClock.currentThreadTimeMillis() - cpuStarted,
                "error" to error.javaClass.simpleName)
            warn("FSMN indisponible : aucune admission pour ce segment")
            return FsmnAdmission(false, confirmationMs, true)
        }
        diagnostics?.event("FSMN_RESULT", "segment" to segment,
            "model" to GuardianFsmnConfirmation.MODEL_ID, "source" to "PRIMARY_ORIGINAL_PCM",
            "evaluated" to true, "success" to true, "accepted" to result.decision.accepted,
            "reason" to if (result.decision.accepted) "CONFIRMED" else "REJECTED",
            "inputGain" to result.inputGain, "inputPeak" to result.inputPeak,
            "conditionedPeak" to result.conditionedPeak, "posteriorFrames" to result.posteriorFrames,
            "silencePosteriorMin" to result.silencePosteriorMin,
            "silencePosteriorMax" to result.silencePosteriorMax,
            "silencePosteriorMean" to result.silencePosteriorMean,
            "speechFrames" to result.decision.speechFrames, "detectedSegments" to result.decision.detectedSegments,
            "confirmationMs" to result.confirmationMs, "inferenceMs" to result.inferenceMs,
            "confirmationCpuMs" to result.confirmationCpuMs)
        return FsmnAdmission(result.decision.accepted, result.confirmationMs)
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
        referenceStudy?.close()
        comparison?.close()
        decoder.close()
        // Same worker as VAD inference: no native release during a VAD call.
        worker.execute {
            try {
                router.reset()
                try { fsmn?.close() } finally {
                    try { calibratedView?.close() } finally {
                        try { streamingVad.close() } finally { try { fireRed.close() } finally { wav?.close() } }
                    }
                }
            } finally { worker.shutdownNow() }
        }
    }

    companion object { private const val TAG = "GuardianSpeechInput" }
}
