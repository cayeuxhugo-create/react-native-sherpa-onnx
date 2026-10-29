package expo.modules.guardiannativeprotection

import android.content.Context
import android.os.SystemClock
import java.io.File
import java.util.ArrayDeque
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Opt-in measurements only. Native validators and writers are owned by this
 * worker. The shared decoder's diagnostic API cannot publish product results. */
internal class GuardianReferenceNoiseStudy(
    private val context: Context,
    directory: File,
    private val diagnostics: GuardianVoiceDiagnostics?,
    private val decoder: GuardianQwenProgressiveDecoder,
) : AutoCloseable {
    private data class Frame(val pcm: FloatArray, val sequence: Long, val captured: Long,
        val speech: Boolean, val revision: Long, val channels: GuardianCaptureChannelFrame?)
    private data class Window(val id: Long, val start: Long, val end: Long,
        val originalAccepted: Boolean?, val variant: String, val source: String = "PRIMARY_ORIGINAL")
    private class Samples(val origin: Long) {
        private val ring = FloatArray(CAPACITY)
        var end = origin; private set
        fun append(start: Long, values: FloatArray) {
            check(start == end && values.all { it.isFinite() })
            for (i in values.indices) ring[((start + i) % CAPACITY).toInt()] = values[i]
            end += values.size
        }
        fun get(start: Long, stop: Long): FloatArray? {
            if (start < maxOf(origin, end - CAPACITY) || stop > end || stop <= start || stop-start > CAPACITY) return null
            return FloatArray((stop-start).toInt()) { ring[((start+it) % CAPACITY).toInt()] }
        }
    }
    private class Epoch(val id: Int, val origin: Long, val route: Long, val first: Int, val second: Int,
        val folder: File, val writer: GuardianDiagnosticWavWriter, val vad: GuardianFireRedStreaming) {
        val short = GuardianReferenceNoiseEnhancer(GuardianReferenceNoiseEnhancer.Profile.SHORT)
        val long = GuardianReferenceNoiseEnhancer(GuardianReferenceNoiseEnhancer.Profile.LONG)
        val buffers = listOf(Samples(origin), Samples(origin), Samples(origin), Samples(origin))
        var stop = Long.MAX_VALUE
        var writerClosed = false
        val vadBuffer = FloatArray(800)
        var vadBufferSize = 0
        var vadSamples = 0L
        var segmentOpen = false
        var detectorClosed = false
        lateinit var segmenter: GuardianSpeechSegmenter
    }
    private val root = File(directory, "reference-study/" + (diagnostics?.sessionId ?: java.util.UUID.randomUUID().toString()))
    private val queue = ArrayBlockingQueue<Any>(64)
    private val closed = AtomicBoolean()
    private val invalid = AtomicBoolean()
    private val thread = Thread({ runStudy() }, "Guardian-Reference-Study").apply {
        isDaemon = true; priority = Thread.MIN_PRIORITY; start()
    }
    fun frame(pcm: FloatArray, sequence: Long, captured: Long, speech: Boolean = false,
        channelRevision: Long = 0, captureChannels: GuardianCaptureChannelFrame? = null) =
        offer(Frame(pcm.copyOf(), sequence, captured, speech, channelRevision, captureChannels?.copyOwned()))
    fun window(id: Long, pcm: FloatArray, endSample: Long, rawAccepted: Boolean) =
        offer(Window(id, endSample-pcm.size, endSample, rawAccepted, "EXACT"))
    private fun offer(value: Any) {
        if (!closed.get() && !invalid.get() && !queue.offer(value)) invalidate("QUEUE_OVERFLOW")
    }
    fun invalidate(reason: String) {
        if (invalid.compareAndSet(false, true)) diagnostics?.event("REFERENCE_STUDY_INVALID", "reason" to reason,
            "role" to "SHADOW_NO_ALERT")
    }
    override fun close() { closed.set(true) }

    private fun runStudy() {
        val epochs = ArrayList<Epoch>()
        val windows = ArrayDeque<Window>()
        val deferredExact = HashMap<Long, Window>()
        var active: Epoch? = null
        var epochCount = 0
        var input = 0L; var frames = 0L; var lastSequence: Long? = null
        var lastCaptured: Long? = null
        var zeroOrigin = 0L; var knownZeroPrefix = true; var lastRoute: Long? = null
        var nativeFire: GuardianFireRedConfirmation? = null
        var nativeFsmn: GuardianFsmnConfirmation? = null
        var timeline: java.io.BufferedWriter? = null
        var files: java.io.BufferedWriter? = null
        var exactPairs = 0; var contextPairs = 0; var skipped = 0; var offers = 0
        var windowCount = 0; var filteredCount = 0; var filteredOffers = 0; var filteredFrames = 0L
        var processingNs = 0L; var cpuMs = 0L
        val stages = listOf("truth", "raw", "pregain", "asr")
        val lanes = listOf("primary", "reference", "short", "long")
        fun addWindow(w: Window) {
            if (w.source == "PRIMARY_ORIGINAL") check(windowCount++ < 64) { "Reference primary window limit" }
            else check(filteredCount++ < 64) { "Reference filtered window limit" }
            check(windows.size + 2 <= 12) { "Reference window backlog" }
            windows.add(w)
            windows.add(w.copy(end=w.end+CONTEXT,variant="END_PLUS_2S"))
        }
        fun detectBlock(epoch: Epoch, count: Int) {
            val block=epoch.vadBuffer.copyOf(count)
            val decision=epoch.vad.accept(block)
            epoch.vadSamples+=count
            filteredFrames++
            epoch.segmenter.accept(block,decision.speech)
            if (filteredFrames % 20L == 0L) diagnostics?.event("REFERENCE_FILTERED_FRAME",
                "epoch" to epoch.id, "endSample" to epoch.origin+epoch.vadSamples,
                "speech" to decision.speech, "peak" to decision.peak, "posteriorFrames" to decision.frames, "streamInferenceMs" to decision.elapsedMs,
                "windowSource" to "FILTERED_STREAM_LONG", "role" to "SHADOW_NO_ALERT")
        }
        fun detect(epoch: Epoch, pcm: FloatArray) {
            var offset=0
            while(offset<pcm.size) {
                val count=minOf(pcm.size-offset,800-epoch.vadBufferSize)
                pcm.copyInto(epoch.vadBuffer,epoch.vadBufferSize,offset,offset+count)
                epoch.vadBufferSize+=count;offset+=count
                if(epoch.vadBufferSize==800) { detectBlock(epoch,800);epoch.vadBufferSize=0 }
            }
        }
        fun finishDetector(epoch: Epoch) {
            if(epoch.detectorClosed)return
            try {
                // Only genuine retained samples, never padding or an invented endpoint.
                if(epoch.vadBufferSize>0) { detectBlock(epoch,epoch.vadBufferSize);epoch.vadBufferSize=0 }
                diagnostics?.event("REFERENCE_FILTERED_END", "epoch" to epoch.id,
                    "consumedSamples" to epoch.vadSamples, "openSegment" to epoch.segmentOpen,
                    "syntheticFlush" to false, "windowSource" to "FILTERED_STREAM_LONG", "role" to "SHADOW_NO_ALERT")
            } finally { epoch.vad.close();epoch.detectorClosed=true }
        }
        fun finishWriter(epoch: Epoch) {
            if (epoch.writerClosed) return
            epoch.writer.close(); epoch.writerClosed = true
            for ((index, stage) in stages.withIndex()) for (extension in listOf("wav", "f32")) {
                val file = File(epoch.folder, epoch.writer.filePrefix + "-$stage.$extension")
                if (file.isFile) files?.write("${file.relativeTo(root).invariantSeparatorsPath},full,${epoch.id},0,FULL,${lanes[index]},${epoch.origin},${epoch.buffers[index].end-epoch.origin}\n")
            }
            val index = File(epoch.folder, epoch.writer.filePrefix + "-index.tsv")
            if (index.isFile) files?.write("${index.relativeTo(root).invariantSeparatorsPath},index,${epoch.id},0,FULL,index,${epoch.origin},0\n")
        }
        fun endEpoch() {
            active?.let { it.stop = input; finishDetector(it); finishWriter(it) }
            active = null
        }
        fun feed(epoch: Epoch, first: FloatArray, second: FloatArray, sequence: Long, captured: Long, speech: Boolean) {
            val relative = epoch.short.inputSamples
            if (speech) {
                epoch.short.markCalibrationSpeech(relative+first.size)
                epoch.long.markCalibrationSpeech(relative+first.size)
            }
            val short = epoch.short.process(first, second)
            val long = epoch.long.process(first, second)
            check(short.size == long.size && epoch.short.outputSamples == epoch.long.outputSamples)
            val data = listOf(first, second, short, long)
            for (i in data.indices) {
                if (data[i].isEmpty()) continue
                val start = epoch.buffers[i].end
                epoch.buffers[i].append(start, data[i])
                epoch.writer.write(stages[i], data[i], sequence, captured)
                check(!epoch.writer.isClosed) { "Reference full WAV write failed" }
                timeline?.write("${epoch.id},${lanes[i]},${epoch.route},${epoch.origin},$start,${start+data[i].size},${start-epoch.origin},${data[i].size},$sequence,$captured\n")
            }
            detect(epoch,long)
        }
        fun fallbackExact(id: Long, reason: String) {
            val w=deferredExact.remove(id) ?: return
            val epoch=epochs.lastOrNull { it.origin<=w.start && w.end<=it.stop }
            val clean=epoch?.buffers?.get(3)?.get(w.start,w.end)
            val fire=clean?.let { checkNotNull(nativeFire).confirm(it) }
            val fsmn=clean?.let { checkNotNull(nativeFsmn).confirm(it) }
            val lane="REFERENCE_NOISE_LONG_EXACT"
            val offer=if(clean!=null && fire?.decision?.accepted==true && filteredOffers<16)
                decoder.offerDiagnosticStatus(id,clean,lane) else null
            if(offer==GuardianQwenProgressiveDecoder.DiagnosticOfferStatus.QUEUED) { offers++;filteredOffers++ }
            diagnostics?.event("REFERENCE_FILTERED_FALLBACK", "segment" to id,
                "windowSource" to "FILTERED_STREAM_LONG", "variant" to "EXACT", "reason" to reason,
                "startSample" to w.start, "endSample" to w.end, "available" to (clean!=null),
                "filteredFireRedAccepted" to fire?.decision?.accepted,
                "filteredFsmnAccepted" to fsmn?.decision?.accepted,
                "diagnosticLane" to lane, "diagnosticOfferStatus" to (offer?.name ?: "NOT_OFFERED"),
                "diagnosticForced" to true, "productionEligible" to false, "role" to "SHADOW_NO_ALERT")
        }
        fun skip(w: Window, reason: String) {
            if(w.source=="FILTERED_STREAM_LONG" && w.variant=="END_PLUS_2S")fallbackExact(w.id,reason)
            skipped++
            diagnostics?.event("REFERENCE_STUDY_WINDOW", "segment" to w.id, "variant" to w.variant, "windowSource" to w.source,
                "available" to false, "status" to reason, "startSample" to w.start, "endSample" to w.end,
                "primaryOriginalAccepted" to w.originalAccepted, "diagnosticForced" to true,
                "productionEligible" to false, "role" to "SHADOW_NO_ALERT")
        }
        fun finalizeWindows(final: Boolean) {
            val count = windows.size
            repeat(count) {
                val w = windows.removeFirst()
                if (w.start < 0 || w.end-w.start > MAX_ASR || w.end <= w.start) {
                    skip(w, "CONTEXT_TOO_LONG_OR_INVALID"); return@repeat
                }
                if (w.end > input && !final) { windows.addLast(w); return@repeat }
                val epoch = epochs.lastOrNull { it.origin <= w.start && w.end <= it.stop }
                if (epoch == null) { skip(w, if (w.end>input) "END_CONTEXT_UNAVAILABLE" else "PAIR_OR_ROUTE_BOUNDARY"); return@repeat }
                if (epoch.buffers[3].end < w.end && epoch === active && !final) { windows.addLast(w); return@repeat }
                val values = epoch.buffers.map { it.get(w.start,w.end) }
                if (values.any { it == null }) {
                    skip(w, if (w.end>input) "END_CONTEXT_UNAVAILABLE" else "REAL_SAMPLES_UNAVAILABLE"); return@repeat
                }
                val folder = File(epoch.folder,"segment-${w.id}/${w.variant}")
                val writer = GuardianDiagnosticWavWriter(folder,"REFERENCE","pair",diagnostics,
                    emitLifecycleEvents=false)
                try {
                    for (i in values.indices) writer.write(stages[i],checkNotNull(values[i]),w.id,0)
                    check(!writer.isClosed) { "Reference pair WAV write failed" }
                } finally { writer.close() }
                for (i in values.indices) for (ext in listOf("wav","f32")) {
                    val file=File(folder,writer.filePrefix+"-${stages[i]}.$ext")
                    files?.write("${file.relativeTo(root).invariantSeparatorsPath},pair,${epoch.id},${w.id},${w.variant},${lanes[i]},${w.start},${w.end-w.start}\n")
                }
                val index=File(folder,writer.filePrefix+"-index.tsv")
                files?.write("${index.relativeTo(root).invariantSeparatorsPath},index,${epoch.id},${w.id},${w.variant},index,${w.start},0\n")
                if(w.variant=="EXACT")exactPairs++ else contextPairs++
                val ready = w.start >= epoch.origin + GuardianReferenceNoiseEnhancer.Profile.LONG.stableStartSample &&
                    epoch.long.calibrationReady && epoch.long.profileFinite && !epoch.long.degenerateReference
                val clean = checkNotNull(values[3])
                val fire = if (ready) checkNotNull(nativeFire).confirm(clean) else null
                val fsmn = if (ready) checkNotNull(nativeFsmn).confirm(clean) else null
                val lane = "REFERENCE_NOISE_LONG_${w.variant}"
                val contextPreferred = w.source == "FILTERED_STREAM_LONG" &&
                    w.variant == "EXACT" && w.end-w.start+CONTEXT <= MAX_ASR
                if(ready && contextPreferred && fire?.decision?.accepted==true) {
                    check(deferredExact.size<12) { "Reference deferred exact limit" }
                    deferredExact[w.id]=w
                }
                val filteredEligible = w.source != "FILTERED_STREAM_LONG" ||
                    (!contextPreferred && fire?.decision?.accepted == true && filteredOffers < 16)
                val offer = if (ready && filteredEligible) decoder.offerDiagnosticStatus(w.id, clean, lane) else null
                if (offer == GuardianQwenProgressiveDecoder.DiagnosticOfferStatus.QUEUED) {
                    offers++
                    if(w.source == "FILTERED_STREAM_LONG")filteredOffers++
                }
                if(w.source=="FILTERED_STREAM_LONG" && w.variant=="END_PLUS_2S") {
                    if(offer==GuardianQwenProgressiveDecoder.DiagnosticOfferStatus.QUEUED)deferredExact.remove(w.id)
                    else fallbackExact(w.id,offer?.name ?: "CONTEXT_NOT_ADMITTED")
                }
                val offerReason = offer?.name ?: when {
                    !ready -> "NOT_READY"
                    contextPreferred -> "CONTEXT_PREFERRED"
                    fire?.decision?.accepted != true -> "FIRERED_REJECT"
                    filteredOffers >= 16 -> "FILTERED_OFFER_LIMIT"
                    else -> "NOT_OFFERED"
                }
                diagnostics?.event("REFERENCE_STUDY_WINDOW", "segment" to w.id, "variant" to w.variant, "windowSource" to w.source,
                    "epoch" to epoch.id, "available" to true, "status" to if(ready) "FILTERED" else "PREFIX_OR_DEGENERATE_REFERENCE",
                    "startSample" to w.start, "endSample" to w.end, "samples" to clean.size,
                    "primaryChannel" to epoch.first, "referenceChannel" to epoch.second,
                    "primaryOriginalAccepted" to w.originalAccepted,
                    "shortReady" to epoch.short.calibrationReady, "longReady" to epoch.long.calibrationReady,
                    "shortSpeechObservedDuringProfile" to epoch.short.observedSpeechDuringProfile,
                    "longSpeechObservedDuringProfile" to epoch.long.observedSpeechDuringProfile,
                    "calibrationAssumption" to "PROTOCOL_NO_SPEECH_PREFIX_UNVERIFIED",
                    "filteredFireRedAccepted" to fire?.decision?.accepted,
                    "filteredFireRedPeak" to fire?.decision?.peakProbability,
                    "filteredFsmnAccepted" to fsmn?.decision?.accepted,
                    "filteredFsmnSpeechFrames" to fsmn?.decision?.speechFrames,
                    "filteredCombinedAccepted" to if(ready) fire!!.decision.accepted && fsmn!!.decision.accepted else null,
                    "diagnosticLane" to lane, "diagnosticOfferStatus" to offerReason,
                    "diagnosticForced" to true, "productionEligible" to false, "role" to "SHADOW_NO_ALERT")
            }
            files?.flush(); timeline?.flush()
        }
        try {
            check(root.isDirectory || root.mkdirs())
            timeline=File(root,"reference-timeline.csv").bufferedWriter()
            timeline.write("epoch,lane,routeRevision,originSample,globalStart,globalEnd,fileSampleStart,sampleCount,sequence,capturedAtNs\n")
            files=File(root,"reference-files.csv").bufferedWriter()
            files.write("path,kind,epoch,segment,variant,lane,globalStart,samples\n")
            nativeFire=GuardianFireRedConfirmation.load { context.applicationContext.assets.open(it).use { stream->stream.readBytes() } }
            nativeFsmn=GuardianFsmnConfirmation.load { context.applicationContext.assets.open(it).use { stream->stream.readBytes() } }
            diagnostics?.event("REFERENCE_STUDY_READY", "model" to "WIENER_CROSS_CHANNEL_REFERENCE",
                "role" to "SHADOW_NO_ALERT", "executionBackend" to GuardianFsmnConfirmation.EXECUTION_BACKEND,
                "profiles" to "SHORT_0P5_3_AND_LONG_2_15", "contextSamples" to CONTEXT,
                "maxAsrSamples" to MAX_ASR, "calibrationAssumption" to "PROTOCOL_NO_SPEECH_PREFIX_UNVERIFIED",
                "diagnosticOfferPolicy" to "LONG_EXACT_AND_LONG_CONTEXT", "maxPendingDiagnostics" to 2,
                "maxRetainedEpochs" to 4, "maxRouteEpochs" to 32,
                "pairSelection" to "FIRST_TWO_AVAILABLE_INDICES_STABLE", "geometryAssumed" to false,
                "windowSources" to "PRIMARY_ORIGINAL_AND_FILTERED_STREAM_LONG", "fixedVadBlockSamples" to 800,
                "filteredOfferPolicy" to "FIRERED_CONTEXT_PREFERRED_FSMN_LOGGED_ONLY", "maxFilteredDiagnosticOffers" to 16)
            while ((!closed.get() || queue.isNotEmpty()) && !invalid.get()) {
                when(val message=queue.poll(50,TimeUnit.MILLISECONDS)) {
                    is Frame -> {
                        val n=message.pcm.size
                        check(n>0 && input+n<=16000L*180) { "Reference recording limit" }
                        check(lastSequence==null || message.sequence==lastSequence!!+1) { "Reference sequence gap" }
                        check(lastCaptured==null || message.captured>lastCaptured!!) { "Reference clock discontinuity" }
                        lastSequence=message.sequence;lastCaptured=message.captured
                        val started=System.nanoTime();val cpuStart=SystemClock.currentThreadTimeMillis()
                        val capture=message.channels;capture?.validate(n)
                        if(capture?.routeRevision!=lastRoute) { endEpoch();zeroOrigin=input;knownZeroPrefix=true;lastRoute=capture?.routeRevision }
                        if(active!=null && (capture==null || capture.channels.none{it.index==active!!.first} || capture.channels.none{it.index==active!!.second})) {
                            endEpoch();zeroOrigin=input;knownZeroPrefix=false
                        }
                        if(active==null && capture!=null) {
                            val available=capture.channels.filter{it.usable || it.samples.any{v->v!=0f}}.sortedBy{it.index}
                            if(available.size>=2) {
                                val origin=if(knownZeroPrefix && input-zeroOrigin<=CAPACITY)zeroOrigin else input
                                check(epochCount < 32) { "Reference route epoch limit" }
                                val folder=File(root,"epoch-${epochCount+1}")
                                val streamingVad=GuardianFireRedStreaming.load { context.applicationContext.assets.open(it).use { s->s.readBytes() } }
                                val e=try { Epoch(++epochCount,origin,capture.routeRevision,available[0].index,available[1].index,folder,
                                    GuardianDiagnosticWavWriter(folder,"REFERENCE","full",diagnostics,emitLifecycleEvents=false),streamingVad)
                                } catch(error:Throwable) { streamingVad.close();throw error }
                                e.segmenter=GuardianSpeechSegmenter(object:GuardianSpeechSegmenter.Sink {
                                    override fun onSpeechStarted() { e.segmentOpen=true }
                                    override fun onSpeechEnded() { e.segmentOpen=false }
                                    override fun onWindow(id:Long,pcm:FloatArray,final:Boolean,reason:String) = error("Source clock required")
                                    override fun onWindowAt(id:Long,pcm:FloatArray,final:Boolean,reason:String,endSample:Long) {
                                        val end=origin+endSample
                                        val start=end-pcm.size
                                        check(e.buffers[3].get(start,end)?.contentEquals(pcm)==true) { "Filtered window source clock differs" }
                                        addWindow(Window(1_000_000_000L+filteredCount,start,end,null,"EXACT","FILTERED_STREAM_LONG"))
                                        diagnostics?.event("REFERENCE_FILTERED_CANDIDATE", "segment" to (1_000_000_000L+filteredCount-1),
                                            "epoch" to e.id, "startSample" to start, "endSample" to end,
                                            "reason" to reason, "windowSource" to "FILTERED_STREAM_LONG", "productionEligible" to false,
                                            "role" to "SHADOW_NO_ALERT")
                                    }
                                })
                                epochs.add(e);active=e
                                // Bound total retained PCM across rapid route changes.
                                while(epochs.size>4)epochs.removeAt(0)
                                var zeros=input-origin
                                while(zeros>0) { val count=minOf(800L,zeros).toInt();feed(e,FloatArray(count),FloatArray(count),0,0,false);zeros-=count }
                                diagnostics?.event("REFERENCE_STUDY_PAIR", "epoch" to e.id, "originSample" to origin,
                                    "primaryChannel" to e.first,"referenceChannel" to e.second,
                                    "routeRevision" to e.route,"knownRecordedZeroPrefixSamples" to input-origin,
                                    "availableChannels" to available.map{it.index},"pairLimitedToTwo" to true,
                                    "role" to "SHADOW_NO_ALERT")
                            } else if(capture.channels.isNotEmpty())knownZeroPrefix=false
                        } else if(capture==null)knownZeroPrefix=false
                        active?.let {e->
                            feed(e,capture!!.channels.single{it.index==e.first}.samples,
                                capture.channels.single{it.index==e.second}.samples,message.sequence,message.captured,message.speech)
                        }
                        input+=n;frames++
                        processingNs+=System.nanoTime()-started;cpuMs+=SystemClock.currentThreadTimeMillis()-cpuStart
                        if(frames%20L==0L) diagnostics?.event("REFERENCE_STUDY_FRAME", "inputSamples" to input,
                            "pairAvailable" to (active!=null), "epochCount" to epochCount,
                            "shortReady" to active?.short?.calibrationReady,"longReady" to active?.long?.calibrationReady,
                            "processingWallMs" to processingNs/1e6,"threadCpuMs" to cpuMs,"pendingTasks" to queue.size,
                            "role" to "SHADOW_NO_ALERT")
                        while(epochs.size>1 && epochs.first().stop<input-CAPACITY)epochs.removeAt(0)
                    }
                    is Window -> {
                        addWindow(message)
                    }
                }
                finalizeWindows(false)
            }
            for(epoch in epochs)finishDetector(epoch)
            finalizeWindows(true)
        } catch(error:Throwable) {
            diagnostics?.event("REFERENCE_STUDY_ERROR", "error" to error.javaClass.simpleName,
                "detail" to error.message?.take(180), "role" to "SHADOW_NO_ALERT")
            invalidate(error.javaClass.simpleName)
        }
        finally {
            queue.clear()
            for(epoch in epochs) {
                try { finishDetector(epoch) } catch(error:Throwable) { invalidate("DETECTOR_"+error.javaClass.simpleName) }
                try { finishWriter(epoch) } catch(error:Throwable) { invalidate("WRITE_"+error.javaClass.simpleName) }
            }
            try{timeline?.close();files?.close()}catch(error:Throwable){invalidate("INDEX_"+error.javaClass.simpleName)}
            try{nativeFsmn?.close()}finally{nativeFire?.close()}
            diagnostics?.event("REFERENCE_STUDY_END", "valid" to !invalid.get(),"inputSamples" to input,
                "pairEverAvailable" to (epochCount>0),"epochCount" to epochCount,
                "exactPairs" to exactPairs,"contextPairs" to contextPairs,"skippedWindows" to skipped,
                "diagnosticOffers" to offers,"filteredCandidates" to filteredCount,"filteredDiagnosticOffers" to filteredOffers,
                "windowSources" to "PRIMARY_ORIGINAL_AND_FILTERED_STREAM_LONG","processingWallMs" to processingNs/1e6,"threadCpuMs" to cpuMs,
                "filesManifest" to "reference-files.csv","timeline" to "reference-timeline.csv",
                "role" to "SHADOW_NO_ALERT")
        }
    }
    companion object {
        private const val CAPACITY=16000*16
        private const val CONTEXT=32000L
        private const val MAX_ASR=128000L
    }
}
