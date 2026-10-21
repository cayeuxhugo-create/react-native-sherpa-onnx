package expo.modules.guardiannativeprotection

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

private fun frame(x: FloatArray, n: Long) = GuardianAudioEngine.AudioFrame(
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

fun main(args: Array<String>) {
    System.setProperty("guardian.test.assets", args[0])
    for ((path, expectVoice) in listOf(args[1] to false, args[2] to true)) {
        val fb=ByteBuffer.wrap(File(path).readBytes()).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        val original=FloatArray(fb.remaining()).also { fb.get(it) }
        val calls=AtomicInteger();val events=CopyOnWriteArrayList<String>();val ended=CountDownLatch(1);val decoded=CountDownLatch(1)
        val qwen=GuardianQwenRescueRecognizer { pcm ->
            check(pcm.size >= original.size)
            check(original.indices.all { pcm[it] == original[it] }) { "Qwen received modified PCM" }
            calls.incrementAndGet();decoded.countDown();GuardianQwenRescueRecognizer.Result("test")
        }
        val diagnostics=GuardianVoiceDiagnostics(true,output={events+=it})
        val decoder=GuardianQwenProgressiveDecoder(qwen,object:GuardianQwenProgressiveDecoder.Listener {
            override fun onPartial(hypothesis:GuardianQwenProgressiveDecoder.Hypothesis) = error("Unexpected partial")
            override fun onFinal(hypothesis:GuardianQwenProgressiveDecoder.Hypothesis) = Unit
            override fun onDecoderUnavailable(segmentId:Long,isFinal:Boolean) = error("Unexpected decoder error")
            override fun onBackpressure(segmentId:Long,pendingFinalCount:Int) = error("Unexpected saturation")
        },diagnostics=diagnostics)
        val processor=GuardianQwenPrimaryAudioProcessor(android.content.Context(),decoder,
            object:GuardianQwenPrimaryAudioProcessor.Listener {
                override fun onSpeechStarted() = Unit
                override fun onSpeechEnded() { ended.countDown() }
                override fun onWarning(message:String) = error(message)
            },diagnostics=diagnostics)
        try {
            var n=1L
            for (start in original.indices step 800) {
                val x=FloatArray(800);original.copyInto(x,0,start,minOf(start+800,original.size))
                processor.onAudioFrame(frame(x,n++));Thread.sleep(8)
            }
            repeat(26) { processor.onAudioFrame(frame(FloatArray(800),n++));Thread.sleep(8) }
            check(ended.await(5,TimeUnit.SECONDS)) { "No completed segment" }
            if(expectVoice)check(decoded.await(5,TimeUnit.SECONDS))
            else Thread.sleep(100)
            check(calls.get()==if(expectVoice)1 else 0) { "Wrong number of Qwen calls: ${calls.get()}" }
            check(events.any { it.contains("\"event\":\"FIRERED_RESULT\"") && it.contains("\"accepted\":$expectVoice") })
            if(!expectVoice)check(events.none { it.contains("\"event\":\"QWEN_START\"") })
            check(events.none { it.contains("\"event\":\"FIRERED_ERROR\"") || it.contains("\"event\":\"PIPELINE_RESET\"") })
            println("PASS candidate speech=${expectVoice}: native FireRed gate, Qwen calls=${calls.get()}, original PCM preserved")
        } finally { processor.close();check(qwen.released.await(5,TimeUnit.SECONDS)) }
    }
}
