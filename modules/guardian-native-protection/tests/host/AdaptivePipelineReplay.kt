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
internal object AdaptivePipelineReplay {
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
            }, diagnostics=diag, requireFsmnConfirmation=true, referenceNoiseStudy=false, adaptiveTranscription=true, diagnosticWavDirectory=File(output,"case-${count+1}-wav"))
            try {
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
                                !(fault.startsWith("MISSING_AT=") && start>=fault.substringAfter('=').toInt() && it.index==1)
                            })
                    ))
                    if (sequence%8==0L) { drain(primary) }
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
