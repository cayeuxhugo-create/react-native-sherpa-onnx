package expo.modules.guardiannativeprotection

import android.content.Context
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Collections
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Opt-in real-VAD integration. Inputs/output paths and PCM remain private.
 * TSV: case-id, primary HP float32, reference HP float32. No reference text. */
object ReferenceNoiseStudyIntegration {
    private data class Result(val finals: List<Pair<Long,FloatArray>>,val events:List<String>)
    @JvmStatic fun main(args:Array<String>) {
        require(args.size==2)
        val output=File(args[1]);check(output.isDirectory || output.mkdirs())
        var cases=0
        for(line in File(args[0]).readLines().filter{it.isNotBlank() && !it.startsWith('#')}) {
            val fields=line.split('\t');require(fields.size==3)
            val first=read(File(fields[1]));val second=read(File(fields[2]));check(first.size==second.size)
            val baseline=run(first,second,File(output,"case-$cases-off"),false)
            val candidate=run(first,second,File(output,"case-$cases-on"),true)
            check(baseline.finals.size==candidate.finals.size)
            for((a,b) in baseline.finals.zip(candidate.finals))check(a.first==b.first && a.second.contentEquals(b.second)) {
                "Reference study changed an original product callback or its PCM"
            }
            File(output,"case-$cases-summary.txt").writeText("finals=${candidate.finals.size};originalPcmByteExact=true\n")
            cases++
        }
        capabilityChecks(File(output,"capabilities"))
        println("PASS reference study integration: $cases real-VAD replays, unchanged original callbacks/PCM, diagnostic-only ASR")
    }
    private fun run(first:FloatArray,second:FloatArray,out:File,enabled:Boolean):Result {
        check(!out.exists() && out.mkdirs())
        val events=Collections.synchronizedList(mutableListOf<String>())
        val diagnostics=GuardianVoiceDiagnostics(true,"HOST",{events.add(it)},"reference-integration")
        val last=AtomicReference<FloatArray>()
        val finals=Collections.synchronizedList(mutableListOf<Pair<Long,FloatArray>>())
        val recognizer=GuardianQwenRescueRecognizer {pcm->last.set(pcm.copyOf());GuardianQwenRescueRecognizer.Result("integration fixture",audioDurationMs=pcm.size/16L)}
        val decoder=GuardianQwenProgressiveDecoder(recognizer,object:GuardianQwenProgressiveDecoder.Listener {
            override fun onPartial(h:GuardianQwenProgressiveDecoder.Hypothesis){error("Unexpected partial")}
            override fun onFinal(h:GuardianQwenProgressiveDecoder.Hypothesis){finals.add(h.segmentId to checkNotNull(last.get()).copyOf())}
            override fun onDecoderUnavailable(segmentId:Long,isFinal:Boolean){error("Unexpected decoder failure")}
            override fun onBackpressure(segmentId:Long,pendingFinalCount:Int){error("Primary backpressure")}
        },diagnostics=diagnostics)
        val processor=GuardianQwenPrimaryAudioProcessor(Context(),decoder,
            object:GuardianQwenPrimaryAudioProcessor.Listener {
                override fun onSpeechStarted(){}
                override fun onSpeechEnded(){}
                override fun onWarning(message:String){}
            },diagnostics=diagnostics,diagnosticWavDirectory=if(enabled)out else null,
            speechOnlyGain=true,requireFsmnConfirmation=true,referenceNoiseStudy=enabled)
        val primaryWorker=field(processor,"worker") as ExecutorService
        val reference=field(processor,"referenceStudy")
        val referenceThread=reference?.let{field(it,"thread") as Thread}
        val referenceQueue=reference?.let{field(it,"queue") as java.util.concurrent.BlockingQueue<*>}
        try {
            var start=0;var sequence=1L
            while(start<first.size) {
                val stop=minOf(first.size,start+800);val p=first.copyOfRange(start,stop);val r=second.copyOfRange(start,stop)
                val channels=if(sequence==1L && p.all{it==0f} && r.all{it==0f})emptyList() else
                    listOf(GuardianCaptureChannelFrame.Channel(0,p.copyOf(),true),GuardianCaptureChannelFrame.Channel(1,r.copyOf(),true))
                val payload=GuardianCaptureChannelFrame(1,2,0,channels)
                val post=p.copyOf();val raw=p.copyOf()
                processor.onAudioFrame(GuardianAudioEngine.AudioFrame(samples=post,sampleCount=p.size,sampleRateHz=16000,
                    capturedAtElapsedRealtimeNanos=sequence*50_000_000,sequenceNumber=sequence,
                    enhancedSamples=FloatArray(0),enhancedSampleCount=0,enhancedCapturedAtElapsedRealtimeNanos=0,enhancedSequenceNumber=0,
                    enhancementApplied=false,enhancementSource="NONE",rawSamples=raw,rawSampleCount=raw.size,
                    detectorSamples=p,detectorSampleCount=p.size,detectorCapturedAtElapsedRealtimeNanos=sequence*50_000_000,detectorSequenceNumber=sequence,
                    acousticRms=GuardianVoiceDiagnostics.rms(p),acousticNoiseFloorRms=.00001f,snrDb=0f,speechLevel="UNKNOWN",
                    farVoiceEventId=0,farVoiceSource=GuardianAudioEngine.FarVoiceSource.NONE,farVoicePhase=GuardianAudioEngine.FarVoicePhase.NONE,
                    adaptiveGain=1f,enhancementExpected=false,captureChannelRevision=1,captureChannels=payload))
                p.fill(Float.NaN);r.fill(Float.NaN);post.fill(Float.NaN);raw.fill(Float.NaN)
                payload.channels.forEach{it.samples.fill(Float.NaN)}
                primaryWorker.submit{}.get(30,TimeUnit.SECONDS)
                if(referenceQueue!=null)await{referenceQueue.size<4}
                start=stop;sequence++
            }
            if(referenceQueue!=null)await{referenceQueue.isEmpty()}
            (field(decoder,"worker") as ExecutorService).submit{}.get(30,TimeUnit.SECONDS)
            if(enabled) {
                val ref=reference as GuardianReferenceNoiseStudy
                // These diagnostics must never become primary callbacks. The
                // first cannot fit +2s under 8s; the second lacks future samples.
                ref.window(1001,FloatArray(112000),first.size-40000L,false)
                ref.window(1002,FloatArray(8000),first.size-800L,false)
            }
        } finally {
            processor.close()
            referenceThread?.join(30_000);check(referenceThread?.isAlive!=true)
            check(recognizer.released.await(30,TimeUnit.SECONDS))
            check(primaryWorker.awaitTermination(30,TimeUnit.SECONDS))
        }
        val snapshot=synchronized(events){events.toList()}
        if(enabled) {
            val end=snapshot.single{value(it,"event")=="REFERENCE_STUDY_END"}
            check(value(end,"valid")=="true" && value(end,"pairEverAvailable")=="true")
            check(snapshot.any{value(it,"event")=="REFERENCE_STUDY_WINDOW" && value(it,"status")=="CONTEXT_TOO_LONG_OR_INVALID"})
            check(snapshot.any{value(it,"event")=="REFERENCE_STUDY_WINDOW" && value(it,"segment")=="1002" && value(it,"variant")=="END_PLUS_2S" && value(it,"available")=="false"})
            check(snapshot.filter{value(it,"event")=="REFERENCE_STUDY_WINDOW"}.all{value(it,"productionEligible")=="false"})
            check(snapshot.none{value(it,"event")=="CONTINUOUS_STUDY_READY" || value(it,"event")=="GTCRN_COMPARISON_READY"})
        }
        check(snapshot.count{value(it,"event")=="QWEN_PUBLISH"}==finals.size)
        File(out,"events-private.jsonl").writeText(snapshot.joinToString("\n")+"\n")
        return Result(synchronized(finals){finals.toList()},snapshot)
    }
    private fun capabilityChecks(root:File) {
        check(root.mkdirs())
        for(mode in listOf("mono","missing-payload","route-change","invalidate")) {
            val out=File(root,mode);check(out.mkdirs())
            val records=Collections.synchronizedList(mutableListOf<String>())
            val callbacks=java.util.concurrent.atomic.AtomicInteger()
            val decodes=java.util.concurrent.atomic.AtomicInteger()
            val diagnostics=GuardianVoiceDiagnostics(true,"HOST",{records.add(it)},"capability-$mode")
            val recognizer=GuardianQwenRescueRecognizer {pcm->decodes.incrementAndGet();GuardianQwenRescueRecognizer.Result("fixture",audioDurationMs=pcm.size/16L)}
            val decoder=GuardianQwenProgressiveDecoder(recognizer,object:GuardianQwenProgressiveDecoder.Listener {
                override fun onPartial(h:GuardianQwenProgressiveDecoder.Hypothesis){callbacks.incrementAndGet()}
                override fun onFinal(h:GuardianQwenProgressiveDecoder.Hypothesis){callbacks.incrementAndGet()}
                override fun onDecoderUnavailable(segmentId:Long,isFinal:Boolean){callbacks.incrementAndGet()}
                override fun onBackpressure(segmentId:Long,pendingFinalCount:Int){callbacks.incrementAndGet()}
            },diagnostics=diagnostics)
            val study=GuardianReferenceNoiseStudy(Context(),out,diagnostics,decoder)
            repeat(4) { index->
                val p=FloatArray(800){.02f};val r=FloatArray(800){-.01f}
                val revision=if(mode=="route-change" && index>=2)2L else 1L
                val capture=when {
                    mode=="missing-payload" && index==1->null
                    mode=="mono"->GuardianCaptureChannelFrame(revision,1,0,listOf(GuardianCaptureChannelFrame.Channel(0,p.copyOf(),true)))
                    else->GuardianCaptureChannelFrame(revision,2,0,listOf(GuardianCaptureChannelFrame.Channel(0,p.copyOf(),true),GuardianCaptureChannelFrame.Channel(1,r.copyOf(),true)))
                }
                study.frame(p,index+1L,(index+1L)*50_000_000,false,revision,capture)
                p.fill(Float.NaN);r.fill(Float.NaN);capture?.channels?.forEach{it.samples.fill(Float.NaN)}
            }
            study.window(1,FloatArray(1600),2400,false)
            if(mode=="invalidate")study.invalidate("HOST_TEST_INVALIDATION")
            study.close();(field(study,"thread") as Thread).join(30_000)
            check(!(field(study,"thread") as Thread).isAlive)
            decoder.close();check(recognizer.released.await(30,TimeUnit.SECONDS))
            val snapshot=synchronized(records){records.toList()}
            val end=snapshot.single{value(it,"event")=="REFERENCE_STUDY_END"}
            check(value(end,"valid")==if(mode=="invalidate")"false" else "true")
            if(mode=="mono")check(value(end,"pairEverAvailable")=="false")
            if(mode in listOf("missing-payload","route-change"))check(snapshot.any{value(it,"event")=="REFERENCE_STUDY_WINDOW" && value(it,"available")=="false"})
            check(callbacks.get()==0 && decodes.get()==0) { "Startup/gap capability probes decoded or published invented data" }
            File(out,"events-private.jsonl").writeText(snapshot.joinToString("\n")+"\n")
        }
        println("PASS reference mono, missing payload, route change and invalidation capability checks")
    }
    private fun read(file:File):FloatArray {
        val bytes=file.readBytes();check(bytes.size%4==0)
        val buffer=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        return FloatArray(buffer.remaining()).also{buffer.get(it)}
    }
    private fun field(owner:Any,name:String):Any?=owner.javaClass.getDeclaredField(name).let{it.isAccessible=true;it.get(owner)}
    private fun value(raw:String,key:String):String?=Regex("\"${Regex.escape(key)}\":(\"([^\"]*)\"|[^,}]+)").find(raw)?.let{if(it.groupValues[2].isNotEmpty())it.groupValues[2]else it.groupValues[1].trim()}
    private fun await(condition:()->Boolean) {val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(30);while(!condition()){check(System.nanoTime()<deadline){"Reference worker timeout"};Thread.sleep(2)}}
}
