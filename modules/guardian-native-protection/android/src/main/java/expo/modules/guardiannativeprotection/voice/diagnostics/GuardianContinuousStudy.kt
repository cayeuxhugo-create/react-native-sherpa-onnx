package expo.modules.guardiannativeprotection

import android.content.Context
import android.os.SystemClock
import java.io.File
import java.util.ArrayDeque
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Opt-in shadow lane with continuous source-specific DSP, no alert/listener callbacks.
 * Native objects live only on this worker. Queue overflow invalidates the study
 * rather than joining unrelated samples or slowing the capture worker. */
internal class GuardianContinuousStudy(
    private val context: Context,
    private val directory: File,
    private val diagnostics: GuardianVoiceDiagnostics?,
    private val decoder: GuardianQwenProgressiveDecoder,
    private val model: String = "GTCRN",
) : AutoCloseable {
    private data class Frame(val pcm: FloatArray, val sequence: Long, val captured: Long, val speech:Boolean, val channelRevision:Long,
        val captureChannels: GuardianCaptureChannelFrame?)
    private data class Window(val id: Long, val pcm: FloatArray, val end: Long, val rawAccepted: Boolean)
    private val sessionRoot = File(directory, "continuous-study/" + (diagnostics?.sessionId ?: java.util.UUID.randomUUID().toString()))
    private val queue = ArrayBlockingQueue<Any>(64)
    private val closed = AtomicBoolean()
    private val invalid = AtomicBoolean()
    private val thread = Thread({ run() }, "Guardian-Continuous-Study").apply {
        isDaemon=true; priority=Thread.MIN_PRIORITY; start()
    }
    fun frame(pcm: FloatArray, sequence: Long, captured: Long, speech:Boolean=false, channelRevision:Long=0,
        captureChannels: GuardianCaptureChannelFrame?=null) =
        offer(Frame(pcm.copyOf(), sequence, captured,speech,channelRevision,captureChannels?.copyOwned()))
    fun window(id: Long, pcm: FloatArray, endSample: Long, rawAccepted: Boolean) =
        offer(Window(id, pcm.copyOf(), endSample, rawAccepted))
    private fun offer(value: Any) {
        if (!closed.get() && !invalid.get() && !queue.offer(value)) invalidate("QUEUE_OVERFLOW")
    }
    fun invalidate(reason: String) {
        if (invalid.compareAndSet(false,true)) diagnostics?.event("CONTINUOUS_STUDY_INVALID", "reason" to reason)
    }
    override fun close() { closed.set(true) }
    private fun run() {
        if (model == "WIENER_DD_ADAPTIVE") runWienerChannels() else runLegacy()
    }

    /** Physical channel truths and filters share one capture clock, without selected-mono substitution. */
    private fun runWienerChannels() {
        val bank = GuardianWienerChannelBank()
        val windows = ArrayDeque<Window>()
        val writers = linkedMapOf<Int, GuardianDiagnosticWavWriter>()
        val fileSamples = mutableMapOf<Pair<Int, String>, Long>()
        val modes = ArrayList<Triple<Long, Long, Boolean>>()
        var confirmation: GuardianFireRedConfirmation? = null
        var timeline: java.io.BufferedWriter? = null
        var mono: GuardianWienerStudyStream? = null
        var monoOutput: GuardianStudyBuffer? = null
        var monoOrigin = 0L
        var inputSamples = 0L
        var frames = 0L
        var processingNs = 0L
        var cpuMs = 0L
        var pairedWindows = 0
        var channelPairs = 0
        var filteredCandidates = 0
        var diagnosticOffers = 0
        val diagnosticOrder = GuardianQwenProgressiveDecoder.DiagnosticChannelOrder()
        var outputPeak = 0f
        var outputClips = 0L
        var monoUsed = false
        var multichannelExpected = false
        var missingChannelPayloads = 0
        fun totalOutput() = fileSamples.filterKeys { it.second == "asr" }.values.sum()
        fun writeFull(channel: Int, stage: String, pcm: FloatArray, start: Long,
                      revision: Long, origin: Long, sequence: Long, captured: Long) {
            if (pcm.isEmpty()) return
            val writer = writers.getOrPut(channel) {
                val target = if (channel >= 0) File(sessionRoot, "channel-$channel") else sessionRoot
                GuardianDiagnosticWavWriter(target, "CONTINUOUS", diagnostics?.sessionId ?: "study",
                    diagnostics, emitLifecycleEvents = false)
            }
            writer.write(stage, pcm, sequence, captured)
            check(!writer.isClosed) { "Channel recording failed" }
            val key = channel to stage
            val offset = fileSamples[key] ?: 0L
            checkNotNull(timeline).write("$channel\t$stage\t$revision\t$origin\t$start\t${start + pcm.size}\t$offset\t${pcm.size}\t$sequence\t$captured\n")
            fileSamples[key] = offset + pcm.size
            if (stage == "asr") for (sample in pcm) {
                outputPeak = maxOf(outputPeak, kotlin.math.abs(sample))
                if (kotlin.math.abs(sample) >= .979f) outputClips++
            }
        }
        fun processPair(w: Window, channel: Int, truth: FloatArray, filtered: FloatArray,
                        status: GuardianWienerStudyStream.Status, usable: Boolean) {
            val native = checkNotNull(confirmation)
            // Re-evaluate the coherent original channel. Primary's selected-mono
            // result is metadata only and cannot stand in for this evidence.
            val original = if (channel >= 0) native.confirm(truth) else null
            val originalAccepted = original?.decision?.accepted ?: w.rawAccepted
            val bypass = status.startupBypass
            val clean = if (bypass) truth.copyOf() else filtered
            val enhanced = if (bypass) null else native.confirm(clean)
            val enhancedAccepted = enhanced?.decision?.accepted ?: originalAccepted
            val rawRejectedFilteredAccepted = !originalAccepted && enhancedAccepted && !bypass
            if (!bypass) filteredCandidates++
            val base = if (channel >= 0) File(sessionRoot, "channel-$channel") else sessionRoot
            val pair = GuardianDiagnosticWavWriter(File(base, "segment-${w.id}"), "PAIR", "study",
                diagnostics, emitLifecycleEvents = false)
            try {
                pair.write("truth", truth, w.id, 0)
                pair.write("asr", clean, w.id, 0)
                check(!pair.isClosed) { "Pair export failed" }
            } finally { pair.close() }
            channelPairs++
            check(!invalid.get())
            val evidence = if (channel < 0) {
                if (bypass) "PRIMARY_MONO_FALLBACK_STARTUP_BYPASS" else "PRIMARY_MONO_FALLBACK_FILTERED_NEURAL"
            } else if (bypass) "CHANNEL_ORIGINAL_STARTUP_BYPASS" else "CHANNEL_ORIGINAL_AND_FILTERED_NEURAL"
            diagnostics?.event("CONTINUOUS_STUDY_WINDOW", "segment" to w.id,
                "channelIndex" to channel, "processingScope" to "PER_CHANNEL_COHERENT",
                "sourceEvidence" to evidence, "channelUsable" to usable,
                "channelUsableEvidence" to "LATEST_FRAME_QUALITY_ONLY_NOT_SPEECH",
                "availableAtInputSample" to inputSamples, "startSample" to w.end - w.pcm.size,
                "endSample" to w.end, "samples" to clean.size,
                "primaryAccepted" to w.rawAccepted, "channelOriginalAccepted" to originalAccepted,
                "originalAccepted" to originalAccepted, "enhancedAccepted" to enhancedAccepted,
                "rawRejectedFilteredAccepted" to rawRejectedFilteredAccepted,
                "channelOriginalPeakProbability" to original?.decision?.peakProbability,
                "channelOriginalLongestPositiveFrames" to original?.decision?.longestPositiveFrames,
                "enhancedPeakProbability" to enhanced?.decision?.peakProbability,
                "enhancedLongestPositiveFrames" to enhanced?.decision?.longestPositiveFrames,
                "model" to model, "startupBypass" to bypass, "bypassReason" to status.reason,
                "calibrationReady" to status.calibrationReady, "calibrationValid" to status.calibrationValid,
                "calibrationEpochStart" to status.epochStart, "captureChannelRevision" to status.channelRevision,
                "originalConfirmationMs" to original?.elapsedMs, "confirmationMs" to enhanced?.elapsedMs,
                "enhancedNearClipSamples" to clean.count { kotlin.math.abs(it) >= .979f },
                "enhancedPeak" to clean.maxOfOrNull { kotlin.math.abs(it) }, "role" to "SHADOW_NO_ALERT")
            if (enhancedAccepted && !bypass) {
                val lane = if (channel >= 0) "WIENER_DD_ADAPTIVE_CHANNEL_$channel" else "WIENER_DD_ADAPTIVE_MONO"
                val studyClosed = closed.get()
                val offerStatus = if (studyClosed) GuardianQwenProgressiveDecoder.DiagnosticOfferStatus.CLOSED
                    else decoder.offerDiagnosticStatus(w.id, clean, lane)
                val offered = offerStatus == GuardianQwenProgressiveDecoder.DiagnosticOfferStatus.QUEUED
                if (offered) {
                    diagnosticOffers++
                    if (channel >= 0) diagnosticOrder.accepted(channel)
                }
                diagnostics?.event("CONTINUOUS_STUDY_ASR_OFFER", "segment" to w.id, "channelIndex" to channel,
                    "lane" to lane, "accepted" to offered, "channelOriginalAccepted" to originalAccepted,
                    "rawRejectedFilteredAccepted" to rawRejectedFilteredAccepted,
                    "status" to offerStatus.name, "reason" to if (studyClosed) "STUDY_CLOSED" else offerStatus.name,
                    "pendingDiagnostics" to decoder.pendingDiagnosticCount())
            }
        }
        try {
            confirmation = GuardianFireRedConfirmation.load { name -> context.applicationContext.assets.open(name).use { it.readBytes() } }
            check(sessionRoot.isDirectory || sessionRoot.mkdirs())
            timeline = File(sessionRoot, "channel-timeline.tsv").bufferedWriter()
            timeline.write("channelIndex\tstage\trouteRevision\toriginSample\tglobalStart\tglobalEnd\tfileSampleStart\tsampleCount\tsequence\tcapturedAtNs\n")
            timeline.flush()
            diagnostics?.event("CONTINUOUS_STUDY_READY", "model" to model, "role" to "SHADOW_NO_ALERT",
                "directory" to sessionRoot.absolutePath, "processingScope" to "PER_CHANNEL_COHERENT",
                "alignment" to "WIENER_PER_CHANNEL_FINALIZED_SAMPLE_CLOCK", "frameShift" to 256,
                "calibrationSamples" to 48000, "stableStartSamples" to 49600, "lookaheadSamples" to 512,
                "maxChannels" to 30, "maxGlobalWindows" to 64,
                "maxPendingDiagnostics" to 2, "diagnosticChannelOrder" to "AFTER_LAST_ACCEPTED_PHYSICAL_CHANNEL",
                "calibrationSpeechGuard" to "RAW_FRAME_PROBABILITY_GE_0.5_OR_ACTIVE",
                "recalibration" to "ROUTE_OR_MISSING_CHANNEL_OR_TAINTED_QUIET_BOUNDARY",
                "selectionChangesResetFilter" to false)
            while ((!closed.get() || queue.isNotEmpty()) && !invalid.get()) {
                when (val message = queue.poll(50, TimeUnit.MILLISECONDS)) {
                    is Frame -> {
                        check(inputSamples + message.pcm.size <= 16000L * 180) { "Study recording limit" }
                        val capture = message.captureChannels
                        if (capture != null) multichannelExpected = capture.openedChannels > 1
                        val useBank = multichannelExpected
                        if (useBank && capture == null) {
                            missingChannelPayloads++
                            diagnostics?.event("CONTINUOUS_STUDY_CHANNEL_PAYLOAD_MISSING", "sequence" to message.sequence,
                                "startSample" to inputSamples, "samples" to message.pcm.size,
                                "reason" to "MULTICHANNEL_PAYLOAD_REQUIRED", "processingScope" to "PER_CHANNEL_COHERENT")
                        }
                        val started = System.nanoTime()
                        val cpuStarted = SystemClock.currentThreadTimeMillis()
                        val start = inputSamples
                        val blocks = bank.process(if (useBank) capture else null, message.pcm.size, message.speech)
                        for (block in blocks) {
                            writeFull(block.channelIndex, "truth", block.truth, block.inputStart,
                                block.routeRevision, block.originSample, message.sequence, message.captured)
                            writeFull(block.channelIndex, "asr", block.filtered, block.outputStart,
                                block.routeRevision, block.originSample, message.sequence, message.captured)
                        }
                        if (useBank) {
                            mono = null
                            monoOutput = null
                        } else {
                            if (mono == null) {
                                mono = GuardianWienerStudyStream()
                                monoOutput = GuardianStudyBuffer()
                                monoOrigin = start
                            }
                            monoUsed = true
                            val revision = capture?.routeRevision ?: message.channelRevision
                            val stream = checkNotNull(mono)
                            val outputStart = monoOrigin + stream.outputSamples
                            val clean = stream.process(message.pcm, revision, message.speech)
                            checkNotNull(monoOutput).append(clean)
                            writeFull(-1, "truth", message.pcm, start, revision, monoOrigin,
                                message.sequence, message.captured)
                            writeFull(-1, "asr", clean, outputStart, revision, monoOrigin,
                                message.sequence, message.captured)
                        }
                        inputSamples += message.pcm.size
                        check(bank.inputSamples == inputSamples)
                        if (modes.lastOrNull()?.third == useBank) {
                            val old = modes.removeAt(modes.lastIndex)
                            modes.add(Triple(old.first, inputSamples, useBank))
                        } else modes.add(Triple(start, inputSamples, useBank))
                        while (modes.size > 1 && modes[1].first < inputSamples - 16000 * 16) modes.removeAt(0)
                        frames++
                        val elapsed = System.nanoTime() - started
                        processingNs += elapsed
                        cpuMs += SystemClock.currentThreadTimeMillis() - cpuStarted
                        if (frames % 20L == 0L) {
                            timeline.flush()
                            for (block in blocks.filter { it.truth.isNotEmpty() }) {
                                diagnostics?.event("CONTINUOUS_STUDY_CHANNEL_FRAME", "channelIndex" to block.channelIndex,
                                    "processingScope" to "PER_CHANNEL_COHERENT", "originSample" to block.originSample,
                                    "inputSamples" to block.inputStart + block.truth.size,
                                    "outputSamples" to block.outputStart + block.filtered.size,
                                    "calibrationReady" to block.calibrationReady, "calibrationValid" to block.calibrationValid,
                                    "calibrationResets" to block.resets, "routeRevision" to block.routeRevision,
                                    "channelUsable" to block.usable)
                            }
                            diagnostics?.event("CONTINUOUS_STUDY_FRAME", "model" to model,
                                "processingScope" to "PER_CHANNEL_COHERENT", "inputSamples" to inputSamples,
                                "outputSamples" to totalOutput(), "outputSamplesScope" to "SUM_OF_CHANNEL_OUTPUTS",
                                "channelCount" to bank.channelIndices.size, "activeChannels" to blocks.filter { it.truth.isNotEmpty() }.map { it.channelIndex },
                                "captureRouteRevision" to capture?.routeRevision, "selectedChannel" to capture?.selectedChannel,
                                "multichannelExpected" to multichannelExpected, "missingChannelPayloads" to missingChannelPayloads,
                                "calibrationResets" to bank.resetCount, "processingWallMs" to elapsed / 1e6,
                                "processingRtf" to processingNs.toDouble() / (inputSamples * 62500.0),
                                "enhancerThreadCpuMs" to cpuMs, "pendingTasks" to queue.size,
                                "captureAgeMs" to (SystemClock.elapsedRealtime() - message.captured / 1000000).coerceAtLeast(0),
                                "outputPeak" to outputPeak, "outputNearClipSamples" to outputClips,
                                "javaHeapUsedBytes" to Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory())
                        }
                    }
                    is Window -> { check(windows.size < 4) { "Study windows backlog" }; windows.add(message) }
                }
                while (windows.isNotEmpty()) {
                    val w = windows.first
                    val start = w.end - w.pcm.size
                    if (w.end > inputSamples) break
                    val mode = modes.firstOrNull { it.first <= start && it.second >= w.end }
                    if (mode?.third == true && !bank.canFinalize(start, w.end)) break
                    if (mode?.third == false && mono != null && start >= monoOrigin &&
                        w.end > monoOrigin + checkNotNull(mono).outputSamples) break
                    windows.removeFirst()
                    check(pairedWindows < 64) { "Study global window limit" }
                    pairedWindows++
                    if (mode == null) {
                        diagnostics?.event("CONTINUOUS_STUDY_WINDOW_SKIPPED", "segment" to w.id,
                            "reason" to "MODE_OR_CHANNEL_COVERAGE_BOUNDARY", "processingScope" to "PER_CHANNEL_COHERENT")
                        continue
                    }
                    if (mode.third) {
                        var found = false
                        for (channel in diagnosticOrder.ordered(bank.channelIndices)) {
                            val candidate = bank.window(channel, start, w.end)
                            if (candidate == null) {
                                diagnostics?.event("CONTINUOUS_STUDY_CHANNEL_WINDOW_SKIPPED", "segment" to w.id,
                                    "channelIndex" to channel, "reason" to "CHANNEL_COVERAGE_GAP")
                                continue
                            }
                            found = true
                            processPair(w, channel, candidate.truth, candidate.filtered, candidate.status, candidate.usable)
                        }
                        if (!found) diagnostics?.event("CONTINUOUS_STUDY_WINDOW_SKIPPED", "segment" to w.id,
                            "reason" to "CHANNEL_COVERAGE_GAP", "processingScope" to "PER_CHANNEL_COHERENT")
                    } else {
                        val stream = mono
                        val originalOnly = stream == null || start < monoOrigin
                        val relativeStart = start - monoOrigin
                        val relativeEnd = w.end - monoOrigin
                        val relativeStatus = if (!originalOnly) checkNotNull(stream).status(relativeStart, relativeEnd) else null
                        val status = relativeStatus?.copy(epochStart = relativeStatus.epochStart + monoOrigin)
                            ?: GuardianWienerStudyStream.Status(true, "MONO_OR_CHANNEL_COVERAGE_BOUNDARY", false, false, start, -1)
                        val clean = if (status.startupBypass) w.pcm.copyOf() else
                            monoOutput?.window(relativeEnd, w.pcm.size)
                        if (clean == null) {
                            diagnostics?.event("CONTINUOUS_STUDY_WINDOW_SKIPPED", "segment" to w.id, "reason" to "EVICTED")
                        } else processPair(w, -1, w.pcm, clean, status, true)
                    }
                }
            }
        } catch (error: Throwable) {
            invalidate(error.javaClass.simpleName)
        } finally {
            queue.clear()
            for (writer in writers.values) try { writer.close() } catch (error: Throwable) { invalidate("RELEASE_" + error.javaClass.simpleName) }
            try { timeline?.close() } catch (error: Throwable) { invalidate("RELEASE_" + error.javaClass.simpleName) }
            try { confirmation?.close() } catch (error: Throwable) { invalidate("RELEASE_" + error.javaClass.simpleName) }
            diagnostics?.event("CONTINUOUS_STUDY_END", "valid" to !invalid.get(), "incompleteWindows" to windows.size,
                "pairedWindows" to pairedWindows, "channelPairs" to channelPairs, "filteredCandidates" to filteredCandidates,
                "diagnosticOffers" to diagnosticOffers, "channelCount" to bank.channelIndices.size, "monoFallbackUsed" to monoUsed,
                "missingChannelPayloads" to missingChannelPayloads,
                "inputSamples" to inputSamples, "outputSamples" to totalOutput(), "outputSamplesScope" to "SUM_OF_CHANNEL_OUTPUTS",
                "processingWallMs" to processingNs / 1e6, "enhancerThreadCpuMs" to cpuMs, "model" to model,
                "processingScope" to "PER_CHANNEL_COHERENT", "calibrationResets" to bank.resetCount,
                "tailPolicy" to "NO_SYNTHETIC_PADDING_NO_FINAL_FLUSH")
        }
    }

    private fun runLegacy() {
        var enhancer: GuardianGtcrnEnhancer? = null
        val wiener=if(model=="WIENER_DD_ADAPTIVE") GuardianWienerStudyStream() else null
        var confirmation: GuardianFireRedConfirmation? = null
        var writer: GuardianDiagnosticWavWriter? = null
        val output = GuardianStudyBuffer()
        val windows = ArrayDeque<Window>()
        var inputSamples=0L; var frames=0L; var processingNs=0L; var cpuMs=0L; var outputPeak=0f; var outputClips=0L; var pairedWindows=0
        try {
            require(model=="GTCRN" || model=="WIENER_DD_ADAPTIVE")
            if(wiener==null) enhancer=GuardianGtcrnEnhancer(context)
            confirmation=GuardianFireRedConfirmation.load { name -> context.applicationContext.assets.open(name).use { it.readBytes() } }
            writer=GuardianDiagnosticWavWriter(sessionRoot, "CONTINUOUS", diagnostics?.sessionId ?: "study", diagnostics, emitLifecycleEvents=false)
            diagnostics?.event("CONTINUOUS_STUDY_READY", "model" to model, "role" to "SHADOW_NO_ALERT",
                "directory" to sessionRoot.absolutePath,
                "alignment" to if(wiener!=null) "WIENER_FINALIZED_SAMPLE_CLOCK" else "SHERPA_STFT_OUTPUT_SAMPLE_CLOCK",
                "frameShift" to if(wiener!=null) 256 else enhancer?.frameShiftInSamples,
                "calibrationSamples" to if(wiener!=null) 48000 else 0,
                "stableStartSamples" to if(wiener!=null) 49600 else 0,
                "lookaheadSamples" to if(wiener!=null) 512 else null,
                "calibrationSpeechGuard" to if(wiener!=null) "RAW_FRAME_PROBABILITY_GE_0.5_OR_ACTIVE" else "ACTIVE",
                "recalibration" to "ORIGINAL_DURING_CHANNEL_OR_SPEECH_BOUNDARY")
            while ((!closed.get() || queue.isNotEmpty()) && !invalid.get()) {
                when (val message=queue.poll(50,TimeUnit.MILLISECONDS)) {
                    is Frame -> {
                        check(inputSamples+message.pcm.size<=16000L*180) { "Study recording limit" }
                        val started=System.nanoTime()
                        val cpuStarted=SystemClock.currentThreadTimeMillis()
                        val clean=if(wiener!=null) wiener.process(message.pcm,message.channelRevision,message.speech) else {
                            val result=checkNotNull(enhancer).process(message.pcm,message.pcm.size,16000)
                            check(result.applied) { "Enhancer bypass is not a valid study" }
                            if(result.outputReady && result.sampleCount>0) result.samples.copyOf(result.sampleCount) else FloatArray(0)
                        }
                        val elapsed=System.nanoTime()-started; processingNs+=elapsed
                        cpuMs+=SystemClock.currentThreadTimeMillis()-cpuStarted
                        inputSamples+=message.pcm.size; frames++
                        writer.write("truth",message.pcm,message.sequence,message.captured)
                        if (clean.isNotEmpty()) {
                            output.append(clean)
                            for (sample in clean) { outputPeak=maxOf(outputPeak,kotlin.math.abs(sample)); if (kotlin.math.abs(sample)>=.979f) outputClips++ }
                            check(output.end<=inputSamples) { "Enhancer output exceeds capture timeline" }
                            writer.write("asr",clean,message.sequence,message.captured)
                        }
                        check(!writer.isClosed) { "Study recording failed" }
                        if (frames%20L==0L) diagnostics?.event("CONTINUOUS_STUDY_FRAME",
                            "inputSamples" to inputSamples,"outputSamples" to output.end,
                            "bufferedAudioMs" to (inputSamples-output.end)*1000/16000,
                            "processingWallMs" to elapsed/1000000.0,
                            "processingRtf" to processingNs.toDouble()/(inputSamples*62500.0),
                            "enhancerThreadCpuMs" to cpuMs,
                            "outputPeak" to outputPeak,"outputNearClipSamples" to outputClips,
                            "captureAgeMs" to (SystemClock.elapsedRealtime()-message.captured/1000000).coerceAtLeast(0),
                            "pendingTasks" to queue.size,
                            "model" to model,"calibrationReady" to wiener?.calibrationReady,
                            "calibrationValid" to wiener?.calibrationValid,
                            "calibrationEpochStart" to wiener?.currentEpochStart,
                            "calibrationResets" to wiener?.resetCount,
                            "captureChannelRevision" to message.channelRevision,
                            "peak" to message.pcm.maxOfOrNull { kotlin.math.abs(it) },
                            "nearClipSamples" to message.pcm.count { kotlin.math.abs(it)>=.979f },
                            "javaHeapUsedBytes" to Runtime.getRuntime().totalMemory()-Runtime.getRuntime().freeMemory())
                    }
                    is Window -> { check(windows.size<4) { "Study windows backlog" }; windows.add(message) }
                }
                while (windows.isNotEmpty() && windows.first.end<=output.end) {
                    val w=windows.removeFirst()
                    val filtered=output.window(w.end,w.pcm.size)
                    if (filtered==null) {
                        diagnostics?.event("CONTINUOUS_STUDY_WINDOW_SKIPPED","segment" to w.id,"reason" to "EVICTED"); continue
                    }
                    check(pairedWindows<64) { "Study pair limit" }
                    pairedWindows++
                    val status=wiener?.status(w.end-w.pcm.size,w.end)
                    val bypass=status?.startupBypass==true
                    val clean=if(bypass) w.pcm.copyOf() else filtered
                    // A startup fallback must not reopen a rejected original window.
                    val result=if(bypass) null else confirmation.confirm(clean)
                    val enhancedAccepted=if(bypass) w.rawAccepted else checkNotNull(result).decision.accepted
                    val pair=GuardianDiagnosticWavWriter(File(sessionRoot,"segment-${w.id}"),"PAIR", "study",diagnostics,emitLifecycleEvents=false)
                    try { pair.write("truth",w.pcm,w.id,0); pair.write("asr",clean,w.id,0); check(!pair.isClosed) { "Pair export failed" } } finally { pair.close() }
                    check(!invalid.get())
                    diagnostics?.event("CONTINUOUS_STUDY_WINDOW","segment" to w.id,
                        "availableAtInputSample" to inputSamples,
                        "startSample" to w.end-w.pcm.size,"endSample" to w.end,"samples" to clean.size,
                        "originalAccepted" to w.rawAccepted,"enhancedAccepted" to enhancedAccepted,
                        "enhancedPeakProbability" to result?.decision?.peakProbability,
                        "enhancedLongestPositiveFrames" to result?.decision?.longestPositiveFrames,
                        "model" to model,"startupBypass" to bypass,
                        "bypassReason" to status?.reason,"calibrationReady" to status?.calibrationReady,
                        "calibrationValid" to status?.calibrationValid,
                        "calibrationEpochStart" to status?.epochStart,
                        "captureChannelRevision" to status?.channelRevision,
                        "confirmationMs" to result?.elapsedMs,"enhancedNearClipSamples" to clean.count { kotlin.math.abs(it)>=.979f },
                        "enhancedPeak" to clean.maxOfOrNull { kotlin.math.abs(it) },"role" to "SHADOW_NO_ALERT")
                    if (enhancedAccepted && !bypass) {
                        val lane = if(wiener!=null) "WIENER_DD_ADAPTIVE" else "GTCRN_CONTINUOUS"
                        val studyClosed = closed.get()
                        val status = if (studyClosed) GuardianQwenProgressiveDecoder.DiagnosticOfferStatus.CLOSED
                            else decoder.offerDiagnosticStatus(w.id, clean, lane)
                        diagnostics?.event("CONTINUOUS_STUDY_ASR_OFFER", "segment" to w.id,
                            "channelIndex" to -1, "lane" to lane,
                            "accepted" to (status == GuardianQwenProgressiveDecoder.DiagnosticOfferStatus.QUEUED),
                            "status" to status.name, "reason" to if (studyClosed) "STUDY_CLOSED" else status.name,
                            "pendingDiagnostics" to decoder.pendingDiagnosticCount())
                    }
                }
            }
        } catch (error: Throwable) {
            invalidate(error.javaClass.simpleName)
        } finally {
            queue.clear()
            for (release in listOf<() -> Unit>({ writer?.close() }, { confirmation?.close() }, { enhancer?.release() })) {
                try { release() } catch (error: Throwable) { invalidate("RELEASE_"+error.javaClass.simpleName) }
            }
            diagnostics?.event("CONTINUOUS_STUDY_END","valid" to !invalid.get(),"incompleteWindows" to windows.size,
                "pairedWindows" to pairedWindows,
                "inputSamples" to inputSamples,"outputSamples" to output.end,"processingWallMs" to processingNs/1000000.0,
                "enhancerThreadCpuMs" to cpuMs,"model" to model,
                "calibrationReady" to wiener?.calibrationReady,"calibrationValid" to wiener?.calibrationValid,
                "calibrationResets" to wiener?.resetCount,"tailPolicy" to "NO_SYNTHETIC_PADDING_NO_FINAL_FLUSH")
        }
    }
}
