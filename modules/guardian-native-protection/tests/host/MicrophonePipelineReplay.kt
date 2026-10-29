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
internal object MicrophonePipelineReplay {
    private fun read(p: String): FloatArray {
        val b = ByteBuffer.wrap(File(p).readBytes()).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        return FloatArray(b.remaining()).also { b.get(it) }
    }
    private fun drain(owner: Any) {
        val f = owner.javaClass.getDeclaredField("worker").apply { isAccessible=true }
        (f.get(owner) as ExecutorService).submit {}.get(30, TimeUnit.SECONDS)
    }
    private fun value(line: String, key: String): String? =
        Regex("\\\"$key\\\":([^,}]+)").find(line)?.groupValues?.get(1)
    @JvmStatic fun main(args: Array<String>) {
        val output = File(args[1]).apply { mkdirs() }
        var count = 0
        for (line in File(args[0]).readLines().filter { it.isNotBlank() }) {
            val row = line.split('\t'); require(row.size in 4..5)
            val originals = listOf(read(row[1]), read(row[2])); check(originals[0].size==originals[1].size)
            val expected = row[3].split(',').filter { it.isNotBlank() }.map(String::toLong)
            val events = CopyOnWriteArrayList<String>(); val inputs=CopyOnWriteArrayList<FloatArray>()
            val finals = CopyOnWriteArrayList<Long>()
            val qwen = GuardianQwenRescueRecognizer { pcm -> inputs.add(pcm.copyOf()); GuardianQwenRescueRecognizer.Result("TEST_DOUBLE_ONLY") }
            val diag = GuardianVoiceDiagnostics(true, output={ events.add(it) })
            val decoder = GuardianQwenProgressiveDecoder(qwen, object: GuardianQwenProgressiveDecoder.Listener {
                override fun onPartial(hypothesis: GuardianQwenProgressiveDecoder.Hypothesis) = error("Unexpected partial")
                override fun onFinal(hypothesis: GuardianQwenProgressiveDecoder.Hypothesis) { finals.add(hypothesis.segmentId) }
                override fun onDecoderUnavailable(segmentId: Long, isFinal: Boolean) = error("Decoder failure")
                override fun onBackpressure(segmentId: Long, pendingFinalCount: Int) = error("Decoder saturation")
            }, diagnostics=diag)
            val primary = GuardianQwenPrimaryAudioProcessor(Context(), decoder, object: GuardianQwenPrimaryAudioProcessor.Listener {
                override fun onSpeechStarted() {}
                override fun onSpeechEnded() {}
                override fun onWarning(message: String) = error("Unexpected input warning")
            }, diagnostics=diag, requireFsmnConfirmation=true)
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
                        captureChannels=GuardianCaptureChannelFrame(1,4,0,listOf(
                            GuardianCaptureChannelFrame.Channel(0,x,x.any { it!=0f }),
                            GuardianCaptureChannelFrame.Channel(1,y,y.any { it!=0f })))
                    ))
                    if (sequence%8==0L) drain(primary)
                }
                drain(primary); drain(decoder)
                File(output,"case-${++count}-events.jsonl").writeText(events.joinToString("\n"))
                check(finals.toList()==expected) { "Admitted segment IDs differ from the private control manifest" }
                check(events.none { it.contains("PIPELINE_RESET") || it.contains("FSMN_ERROR") || it.contains("FIRERED_ERROR") })
                val selections=events.filter { it.contains("\"event\":\"ASR_MICROPHONE_SELECTION\"") }
                check(selections.size==expected.size && inputs.size==expected.size)
                selections.forEachIndexed { i, event ->
                    val channel=checkNotNull(value(event,"selectedChannel")).toInt()
                    if (row.size==5) check(channel==row[4].toInt()) { "Expected the clearer alternate microphone" }
                    val start=checkNotNull(value(event,"startSample")).toInt()
                    val end=checkNotNull(value(event,"endSample")).toInt()
                    check(inputs[i].contentEquals(originals[channel].copyOfRange(start,end))) { "Qwen did not receive the selected continuous microphone" }
                    check(event.contains("\"comparedChannels\":\"[0, 1]\""))
                    val sourceEvent = events.single { it.contains("\"event\":\"ASR_INPUT\"") &&
                        value(it,"segment") == expected[i].toString() }
                    check(sourceEvent.contains(GuardianPcmIdentity.sha256(inputs[i])))
                }
                println("PASS private pipeline case $count: ${expected.size} final(s), two microphones checked, selected source PCM exact, no extra admitted segment")
            } finally { primary.close(); check(qwen.released.await(10,TimeUnit.SECONDS)) }
        }
        check(count>0)
    }
}
