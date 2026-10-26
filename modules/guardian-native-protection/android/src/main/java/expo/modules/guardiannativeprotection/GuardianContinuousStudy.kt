package expo.modules.guardiannativeprotection

import android.content.Context
import android.os.SystemClock
import java.io.File
import java.util.ArrayDeque
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Opt-in shadow lane. A single continuous enhancer, no alert/listener callbacks.
 * Native objects live only on this worker. Queue overflow invalidates the study
 * rather than joining unrelated samples or slowing the capture worker. */
internal class GuardianContinuousStudy(
    private val context: Context,
    private val directory: File,
    private val diagnostics: GuardianVoiceDiagnostics?,
    private val decoder: GuardianQwenProgressiveDecoder,
) : AutoCloseable {
    private data class Frame(val pcm: FloatArray, val sequence: Long, val captured: Long)
    private data class Window(val id: Long, val pcm: FloatArray, val end: Long, val rawAccepted: Boolean)
    private val sessionRoot = File(directory, "continuous-study/" + (diagnostics?.sessionId ?: java.util.UUID.randomUUID().toString()))
    private val queue = ArrayBlockingQueue<Any>(64)
    private val closed = AtomicBoolean()
    private val invalid = AtomicBoolean()
    private val thread = Thread({ run() }, "Guardian-Continuous-Study").apply {
        isDaemon=true; priority=Thread.MIN_PRIORITY; start()
    }
    fun frame(pcm: FloatArray, sequence: Long, captured: Long) = offer(Frame(pcm.copyOf(), sequence, captured))
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
        var enhancer: GuardianGtcrnEnhancer? = null
        var confirmation: GuardianFireRedConfirmation? = null
        var writer: GuardianDiagnosticWavWriter? = null
        val output = GuardianStudyBuffer()
        val windows = ArrayDeque<Window>()
        var inputSamples=0L; var frames=0L; var processingNs=0L; var cpuMs=0L; var outputPeak=0f; var outputClips=0L; var pairedWindows=0
        try {
            enhancer=GuardianGtcrnEnhancer(context)
            confirmation=GuardianFireRedConfirmation.load { name -> context.applicationContext.assets.open(name).use { it.readBytes() } }
            writer=GuardianDiagnosticWavWriter(sessionRoot, "CONTINUOUS", diagnostics?.sessionId ?: "study", diagnostics, emitLifecycleEvents=false)
            diagnostics?.event("CONTINUOUS_STUDY_READY", "model" to "GTCRN", "role" to "SHADOW_NO_ALERT",
                "directory" to sessionRoot.absolutePath,
                "alignment" to "SHERPA_STFT_OUTPUT_SAMPLE_CLOCK", "frameShift" to enhancer.frameShiftInSamples)
            while ((!closed.get() || queue.isNotEmpty()) && !invalid.get()) {
                when (val message=queue.poll(50,TimeUnit.MILLISECONDS)) {
                    is Frame -> {
                        check(inputSamples+message.pcm.size<=16000L*180) { "Study recording limit" }
                        val started=System.nanoTime()
                        val cpuStarted=SystemClock.currentThreadTimeMillis()
                        val result=enhancer.process(message.pcm,message.pcm.size,16000)
                        val elapsed=System.nanoTime()-started; processingNs+=elapsed
                        cpuMs+=SystemClock.currentThreadTimeMillis()-cpuStarted
                        inputSamples+=message.pcm.size; frames++
                        writer.write("truth",message.pcm,message.sequence,message.captured)
                        if (result.outputReady && result.applied && result.sampleCount>0) {
                            val clean=result.samples.copyOf(result.sampleCount)
                            output.append(clean)
                            for (sample in clean) { outputPeak=maxOf(outputPeak,kotlin.math.abs(sample)); if (kotlin.math.abs(sample)>=.979f) outputClips++ }
                            check(output.end<=inputSamples) { "Enhancer output exceeds capture timeline" }
                            writer.write("asr",clean,message.sequence,message.captured)
                        } else check(result.applied) { "Enhancer bypass is not a valid study" }
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
                            "peak" to message.pcm.maxOfOrNull { kotlin.math.abs(it) },
                            "nearClipSamples" to message.pcm.count { kotlin.math.abs(it)>=.979f },
                            "javaHeapUsedBytes" to Runtime.getRuntime().totalMemory()-Runtime.getRuntime().freeMemory())
                    }
                    is Window -> { check(windows.size<4) { "Study windows backlog" }; windows.add(message) }
                }
                while (windows.isNotEmpty() && windows.first.end<=output.end) {
                    val w=windows.removeFirst()
                    val clean=output.window(w.end,w.pcm.size)
                    if (clean==null) {
                        diagnostics?.event("CONTINUOUS_STUDY_WINDOW_SKIPPED","segment" to w.id,"reason" to "EVICTED"); continue
                    }
                    check(pairedWindows<64) { "Study pair limit" }
                    pairedWindows++
                    val result=confirmation.confirm(clean)
                    val pair=GuardianDiagnosticWavWriter(File(sessionRoot,"segment-${w.id}"),"PAIR", "study",diagnostics,emitLifecycleEvents=false)
                    try { pair.write("truth",w.pcm,w.id,0); pair.write("asr",clean,w.id,0); check(!pair.isClosed) { "Pair export failed" } } finally { pair.close() }
                    check(!invalid.get())
                    diagnostics?.event("CONTINUOUS_STUDY_WINDOW","segment" to w.id,
                        "availableAtInputSample" to inputSamples,
                        "startSample" to w.end-w.pcm.size,"endSample" to w.end,"samples" to clean.size,
                        "originalAccepted" to w.rawAccepted,"enhancedAccepted" to result.decision.accepted,
                        "enhancedPeakProbability" to result.decision.peakProbability,
                        "confirmationMs" to result.elapsedMs,"enhancedNearClipSamples" to clean.count { kotlin.math.abs(it)>=.979f },
                        "enhancedPeak" to clean.maxOfOrNull { kotlin.math.abs(it) },"role" to "SHADOW_NO_ALERT")
                    if (result.decision.accepted && !closed.get()) {
                        val offered=decoder.offerDiagnostic(w.id,clean,"GTCRN_CONTINUOUS")
                        diagnostics?.event("CONTINUOUS_STUDY_ASR_OFFER","segment" to w.id,"accepted" to offered)
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
                "enhancerThreadCpuMs" to cpuMs,"tailPolicy" to "NO_SYNTHETIC_PADDING_NO_FINAL_FLUSH")
        }
    }
}
