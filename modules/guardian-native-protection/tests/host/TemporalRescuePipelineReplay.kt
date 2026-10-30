package expo.modules.guardiannativeprotection

import android.content.Context
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

/** Real capture-clock replay through production routing and both native VADs.
 * Private TSV, no header: anonymous ID, original.f32, alternate.f32, expected IDs.
 * The recognizer is a test double: only admission and PCM routing are tested. */
internal object TemporalRescuePipelineReplay {
    /** Real public CPU model, using binary IPC only in the host test. Android
     * uses the compile-checked Sherpa Kotlin API directly, without Python. */
    private class PythonGtcrnBackend(private val fault: String) : GuardianTemporalConfirmationView.Backend {
        private val child = ProcessBuilder(
            System.getenv("GUARDIAN_GTCRN_PYTHON") ?: "python3",
            checkNotNull(System.getenv("GUARDIAN_GTCRN_SERVER")),
            File(System.getProperty("guardian.test.assets"), "gtcrn_simple.onnx").absolutePath,
        ).redirectError(ProcessBuilder.Redirect.INHERIT).start()
        private val input = java.io.DataOutputStream(child.outputStream.buffered())
        private val output = java.io.DataInputStream(child.inputStream.buffered())
        override val frameShiftInSamples = output.readInt().also { check(it==256) }
        private var samples=0L
        override fun run(pcm: FloatArray): FloatArray {
            if(fault.startsWith("GTCRN_ERROR_AT=") && samples>=fault.substringAfter('=').toLong())
                error("Injected GTCRN failure")
            samples+=pcm.size
            input.writeInt(pcm.size)
            val bytes=ByteBuffer.allocate(pcm.size*4).order(ByteOrder.LITTLE_ENDIAN)
            pcm.forEach { bytes.putFloat(it) };input.write(bytes.array());input.flush()
            val count=output.readInt();check(count in 0..4096)
            val result=ByteArray(count*4);output.readFully(result)
            val floats=ByteBuffer.wrap(result).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
            return FloatArray(count).also { floats.get(it) }
        }
        override fun reset() { input.writeInt(-1);input.flush();check(output.readInt()==0) }
        override fun close() {
            try { input.writeInt(-2);input.flush() } finally {
                input.close();output.close()
                check(child.waitFor(10,TimeUnit.SECONDS)) { "GTCRN test server did not stop" }
                check(child.exitValue()==0) { "GTCRN test server failed" }
            }
        }
    }
    private fun read(p: String): FloatArray {
        val b = ByteBuffer.wrap(File(p).readBytes()).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        return FloatArray(b.remaining()).also { b.get(it) }
    }
    private fun drain(owner: Any) {
        val f = owner.javaClass.getDeclaredField("worker").apply { isAccessible=true }
        (f.get(owner) as ExecutorService).submit {}.get(30, TimeUnit.SECONDS)
    }
    private fun sha(x: FloatArray): String {
        val b=ByteBuffer.allocate(x.size*4).order(ByteOrder.LITTLE_ENDIAN)
        x.forEach { b.putFloat(it) }
        return java.security.MessageDigest.getInstance("SHA-256").digest(b.array()).joinToString("") { "%02x".format(it.toInt() and 255) }
    }
    private fun value(line: String, key: String): String? =
        Regex("\\\"$key\\\":([^,}]+)").find(line)?.groupValues?.get(1)
    @JvmStatic fun main(args: Array<String>) {
        val output = File(args[1]).apply { mkdirs() }
        var count = 0
        for (line in File(args[0]).readLines().filter { it.isNotBlank() }) {
            val row = line.split('\t'); require(row.size in 4..5)
            val originals = listOf(read(row[1]), read(row[2])); check(originals[0].size==originals[1].size)
            val fault = row.getOrNull(4).orEmpty()
            val expected = row[3].split(',').filter { it.isNotBlank() }.map(String::toLong)
            val events = CopyOnWriteArrayList<String>(); val inputs=CopyOnWriteArrayList<FloatArray>()
            val finals = CopyOnWriteArrayList<Long>()
            val labels = CopyOnWriteArrayList<String>()
            val qwen = GuardianQwenRescueRecognizer { pcm -> inputs.add(pcm.copyOf()); GuardianQwenRescueRecognizer.Result("AUDIT_CALL_${inputs.size}") }
            val diag = GuardianVoiceDiagnostics(true, output={ events.add(it) })
            val decoder = GuardianQwenProgressiveDecoder(qwen, object: GuardianQwenProgressiveDecoder.Listener {
                override fun onPartial(hypothesis: GuardianQwenProgressiveDecoder.Hypothesis) = error("Unexpected partial")
                override fun onFinal(hypothesis: GuardianQwenProgressiveDecoder.Hypothesis) { finals.add(hypothesis.segmentId); labels.add(hypothesis.text) }
                override fun onDecoderUnavailable(segmentId: Long, isFinal: Boolean) = error("Decoder failure")
                override fun onBackpressure(segmentId: Long, pendingFinalCount: Int) = error("Decoder saturation")
            }, diagnostics=diag)
            val primary = GuardianQwenPrimaryAudioProcessor(Context(), decoder, object: GuardianQwenPrimaryAudioProcessor.Listener {
                override fun onSpeechStarted() {}
                override fun onSpeechEnded() {}
                override fun onWarning(message: String) = error("Unexpected input warning")
            }, diagnostics=diag, requireFsmnConfirmation=true, referenceNoiseStudy=false, adaptiveTranscription=true,
                temporalRescueEnabled=true,
                temporalConfirmationFactory={ GuardianTemporalConfirmationView(PythonGtcrnBackend(fault)) },
                diagnosticWavDirectory=File(output,"case-${count+1}-wav"))
            try {
                val known=mutableSetOf<Int>()
                val pendingField=primary.javaClass.getDeclaredField("pendingAdaptiveWindows").apply { isAccessible=true }
                val view=primary.javaClass.getDeclaredField("calibratedView").apply { isAccessible=true }.get(primary) as GuardianCalibratedReferenceView
                val candidateWindows=linkedMapOf<Long,Pair<Long,FloatArray>>()
                val saved=mutableSetOf<Long>()
                fun savePcm(path: File, pcm: FloatArray) {
                    val b=ByteBuffer.allocate(pcm.size*4).order(ByteOrder.LITTLE_ENDIAN)
                    pcm.forEach { b.putFloat(it) };path.writeBytes(b.array())
                }
                var sequence=0L
                for (start in originals[0].indices step 800) {
                    val end=minOf(start+800, originals[0].size); val x=originals[0].copyOfRange(start,end)
                    val y=originals[1].copyOfRange(start,end); sequence++
                    val captured=sequence*50_000_000
                    primary.onAudioFrame(GuardianAudioEngine.AudioFrame(
                        samples=x, sampleCount=x.size, sampleRateHz=16000, capturedAtElapsedRealtimeNanos=captured,
                        sequenceNumber=sequence, enhancedSamples=floatArrayOf(), enhancedSampleCount=0,
                        enhancedCapturedAtElapsedRealtimeNanos=0, enhancedSequenceNumber=0,
                        enhancementApplied=false, enhancementSource="NONE", rawSamples=x, rawSampleCount=x.size,
                        detectorSamples=x, detectorSampleCount=x.size, detectorCapturedAtElapsedRealtimeNanos=captured,
                        detectorSequenceNumber=sequence, acousticRms=0f, acousticNoiseFloorRms=0f, snrDb=0f,
                        speechLevel="UNKNOWN", farVoiceEventId=0, farVoiceSource=GuardianAudioEngine.FarVoiceSource.NONE,
                        farVoicePhase=GuardianAudioEngine.FarVoicePhase.NONE, adaptiveGain=1f,
                        captureChannels=GuardianCaptureChannelFrame(
                            if(fault.startsWith("ROUTE_AT=") && start>=fault.substringAfter('=').toInt())2 else 1,4,0,listOf(
                            GuardianCaptureChannelFrame.Channel(0,x,x.any { it!=0f }),
                            GuardianCaptureChannelFrame.Channel(1,y,y.any { it!=0f })).filter {
                                run {
                                    if(it.samples.any { v->v!=0f })known.add(it.index)
                                    it.index in known && !(fault.startsWith("MISSING_AT=") && start>=fault.substringAfter('=').toInt() && it.index==1)
                                }
                            })
                    ))
                    drain(primary)
                    for(window in pendingField.get(primary) as java.util.ArrayDeque<*>) {
                        val item=checkNotNull(window)
                        fun field(key: String): Any? = item.javaClass.getDeclaredField(key).apply { isAccessible=true }.get(item)
                        val id=field("id") as Long
                        candidateWindows.putIfAbsent(id, (field("end") as Long) to (field("pcm") as FloatArray))
                    }
                    for((id,pair) in candidateWindows) {
                        if(id in saved)continue
                        val (end,pcm)=pair
                        val filtered=view.window(end-pcm.size,end) ?: continue
                        savePcm(File(output,"case-${count+1}-candidate-$id-original.f32"),pcm)
                        savePcm(File(output,"case-${count+1}-candidate-$id-filtered.f32"),filtered.pcm)
                        File(output,"case-${count+1}-candidate-$id-window.tsv").writeText("$id\t${end-pcm.size}\t$end\t${filtered.calibrationClean}\t${filtered.acceptedProfileFrames}\t${filtered.rejectedProfileFrames}\n")
                        saved.add(id)
                    }
                }
                drain(primary); drain(decoder)
                File(output,"case-${++count}-events.jsonl").writeText(events.joinToString("\n"))
                check(finals.toList()==expected) { "Admitted segment IDs differ from the private control manifest" }
                check(events.none { it.contains("PIPELINE_RESET") || it.contains("FSMN_ERROR") || it.contains("FIRERED_ERROR") })
                check(events.none { it.contains("ASR_EXPORT_ERROR") || it.contains("ADAPTIVE_VALIDATION_ERROR") })
                check(events.none { it.contains("REFERENCE_STUDY_INVALID") || it.contains("REFERENCE_STUDY_ERROR") })
                val primaryInputs=finals.indices.map { i ->
                    val index=labels[i].removePrefix("AUDIT_CALL_").toInt()-1
                    run { val data=java.nio.ByteBuffer.allocate(inputs[index].size*4).order(java.nio.ByteOrder.LITTLE_ENDIAN); inputs[index].forEach { data.putFloat(it) }; File(output,"case-$count-segment-${finals[i]}.f32").writeBytes(data.array()); "${finals[i]}\t${sha(inputs[index])}" }
                }
                File(output,"case-$count-primary-inputs.tsv").writeText(primaryInputs.joinToString("\n"))
                check(inputs.size == finals.size) { "Candidate must decode once per admitted event" }
                check(events.count { it.contains("\"event\":\"ASR_INPUT\"") }==finals.size)
                val results=events.count { it.contains("\"event\":\"STUDY_QWEN_RESULT\"") }
                val duplicates=events.count { it.contains("\"event\":\"STUDY_QWEN_OFFER\"") && it.contains("\"status\":\"DUPLICATE_PCM\"") }
                File(output,"case-$count-counts.tsv").writeText("primary\tdiagnostics\tduplicateOffers\tallCalls\n${finals.size}\t$results\t$duplicates\t${inputs.size}\n")
                println("PASS audited case $count: primary=${finals.size}, diagnostics=$results, duplicateOffers=$duplicates, allCalls=${inputs.size}")
            } finally { primary.close(); check(qwen.released.await(10,TimeUnit.SECONDS)) }
        }
        check(count>0)
    }
}
