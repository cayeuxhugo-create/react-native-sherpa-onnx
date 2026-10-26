package expo.modules.guardiannativeprotection
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

private fun testFrame(x: FloatArray, n: Long) = GuardianAudioEngine.AudioFrame(
    samples=x, sampleCount=x.size, sampleRateHz=16000,
    capturedAtElapsedRealtimeNanos=n*50_000_000, sequenceNumber=n,
    enhancedSamples=floatArrayOf(), enhancedSampleCount=0,
    enhancedCapturedAtElapsedRealtimeNanos=0, enhancedSequenceNumber=0,
    enhancementApplied=false, enhancementSource="NONE",
    rawSamples=x, rawSampleCount=x.size, detectorSamples=x, detectorSampleCount=x.size,
    detectorCapturedAtElapsedRealtimeNanos=n*50_000_000, detectorSequenceNumber=n,
    acousticRms=0f, acousticNoiseFloorRms=0f, snrDb=0f, speechLevel="UNKNOWN",
    farVoiceEventId=0, farVoiceSource=GuardianAudioEngine.FarVoiceSource.NONE,
    farVoicePhase=GuardianAudioEngine.FarVoicePhase.NONE, adaptiveGain=1f,
)

private fun pcm(file:File):FloatArray {
    val b=ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
    return FloatArray(b.remaining()).also{b.get(it)}
}
fun main(args:Array<String>) {
    val candidate = args.getOrNull(3) == "CANDIDATE"
    val maximumGain = args.getOrNull(4)?.toFloat() ?: 4f
    System.setProperty("guardian.test.assets",args[0])
    val full=pcm(File(args[1]));val expected=File(args[2]).listFiles()!!.filter{it.name.startsWith("segment-")}.sortedBy{it.name}.map{pcm(it)}
    val calls=AtomicInteger();val ended=CountDownLatch(3);val decoded=CountDownLatch(3)
    val events=CopyOnWriteArrayList<String>();val warnings=CopyOnWriteArrayList<String>()
    val qwen=GuardianQwenRescueRecognizer { x ->
        val i=calls.getAndIncrement();val reference=if(candidate) GuardianSpeechSegmentGain.apply(expected[i],true,maximumGain).pcm else expected[i];check(i<expected.size && x.contentEquals(reference)){"Qwen PCM differs from real native reference"}
        decoded.countDown();GuardianQwenRescueRecognizer.Result("test")
    }
    val saved=CountDownLatch(5);val comparisonSaved=CountDownLatch(1)
    val directory=java.nio.file.Files.createTempDirectory("processor-paired-").toFile()
    val diagnostic=GuardianVoiceDiagnostics(true,output={events+=it;if(it.contains("\"event\":\"WAV_SAVED\""))saved.countDown();if(it.contains("GTCRN_COMPARISON_SAVED"))comparisonSaved.countDown()})
    val decoder=GuardianQwenProgressiveDecoder(qwen,object:GuardianQwenProgressiveDecoder.Listener {
        override fun onPartial(hypothesis:GuardianQwenProgressiveDecoder.Hypothesis)=error("partial")
        override fun onFinal(hypothesis:GuardianQwenProgressiveDecoder.Hypothesis)=Unit
        override fun onDecoderUnavailable(segmentId:Long,isFinal:Boolean){warnings+="decoder"}
        override fun onBackpressure(segmentId:Long,pendingFinalCount:Int){warnings+="backpressure"}
    },diagnostics=diagnostic)
    val processor=GuardianQwenPrimaryAudioProcessor(android.content.Context(),decoder,object:GuardianQwenPrimaryAudioProcessor.Listener {
        override fun onSpeechStarted()=Unit
        override fun onSpeechEnded(){ended.countDown()}
        override fun onWarning(message:String){warnings+=message}
    },profile=GuardianVoiceProfile.NOISY,diagnostics=diagnostic,diagnosticWavDirectory=directory,speechOnlyGain=candidate,confirmedGainMaximum=maximumGain)
    try {
        var sequence=1L
        for(i in 0 until full.size-799 step 800) {
            val x=full.copyOfRange(i,i+800)
            val number=sequence++
            val input=testFrame(x,number).copy(enhancementExpected=true)
            processor.onAudioFrame(if(candidate) input.copy(samples=FloatArray(x.size){x[it]*3f},adaptiveGain=3f) else input)
            processor.onEnhancedAudioFrame(GuardianAudioEngine.EnhancedAudioFrame(FloatArray(800){.9f},800,number*50_000_000,number,"GTCRN"))
            x.fill(.73f) // capture pool is reused immediately after its callback
            Thread.sleep(5)
        }
        check(ended.await(10,TimeUnit.SECONDS));check(decoded.await(10,TimeUnit.SECONDS))
        check(calls.get()==3);check(warnings.isEmpty()){warnings.joinToString()}
        check(events.none{it.contains("FIRERED_ERROR") || it.contains("PIPELINE_RESET")})
        check(events.any{it.contains(GuardianFireRedStreaming.MODEL_ID)})
    }finally{processor.close();check(qwen.released.await(5,TimeUnit.SECONDS));check(saved.await(5,TimeUnit.SECONDS));check(comparisonSaved.await(5,TimeUnit.SECONDS));directory.deleteRecursively()}
    println("PASS candidate=$candidate real streaming processor: GTCRN comparison preserves original, original capture copied, three exact PCM windows, no partial decode")
}
