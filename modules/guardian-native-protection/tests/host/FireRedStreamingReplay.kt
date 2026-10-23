package expo.modules.guardiannativeprotection
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Real JNI -> production policy -> production segmenter -> real confirmation.
 * Private input TSV: anonymous ID, f32 path. Audio/output paths never published. */
fun main(args: Array<String>) {
    val assets=File(args[0]);val read:(String)->ByteArray={File(assets,it).readBytes()}
    val stream=GuardianFireRedStreaming.load(read);val confirm=GuardianFireRedConfirmation.load(read)
    try {
        check(runCatching { GuardianFireRedStreaming.load { ByteArray(8) } }.isFailure)
        for (line in File(args[1]).readLines()) {
            val fields=line.split('\t');val input=ByteBuffer.wrap(File(fields[1]).readBytes()).order(ByteOrder.LITTLE_ENDIAN)
            val dir=File(args[2],fields[0]);check(!dir.exists());dir.mkdirs()
            val events=StringBuilder("id\taccepted\tsamples\tavailableAtSample\treason\n")
            val flags=StringBuilder();var delivered=0L
            val segmenter=GuardianSpeechSegmenter(object:GuardianSpeechSegmenter.Sink {
                override fun onSpeechStarted()=Unit
                override fun onSpeechEnded()=Unit
                override fun onWindow(id:Long,pcm:FloatArray,final:Boolean,reason:String) {
                    check(final);val before=pcm.copyOf();val r=confirm.confirm(pcm);check(pcm.contentEquals(before))
                    val data=ByteBuffer.allocate(pcm.size*4).order(ByteOrder.LITTLE_ENDIAN);pcm.forEach{data.putFloat(it)}
                    File(dir,"segment-$id.f32").writeBytes(data.array())
                    events.append("$id\t${r.decision.accepted}\t${pcm.size}\t$delivered\t$reason\n")
                }
            })
            stream.reset()
            while(input.remaining()>=3200) {
                val pcm=FloatArray(800){input.float};val before=pcm.copyOf();val result=stream.accept(pcm)
                check(pcm.contentEquals(before));flags.append(if(result.speech)'1' else '0');delivered+=800;segmenter.accept(pcm,result.speech)
            }
            File(dir,"flags.txt").writeText(flags.toString());File(dir,"events.tsv").writeText(events.toString())
            println("PASS native streaming replay ${fields[0]} frames=${flags.length}")
        }
        val probe=FloatArray(64000){ kotlin.math.sin(it*.07).toFloat()*.001f }
        val expected=mutableListOf<GuardianFireRedStreaming.Result>();stream.reset()
        for(i in probe.indices step 800)expected+=stream.accept(probe.copyOfRange(i,i+800))
        stream.reset()
        for(i in probe.indices step 800) {
            val a=stream.accept(probe.copyOfRange(i,i+400));val b=stream.accept(probe.copyOfRange(i+400,i+800))
            check(a.frames+b.frames==expected[i/800].frames && b.speech==expected[i/800].speech){"Short reads lost frontend state"}
        }
        println("PASS short-read JNI frontend continuity (two 25ms reads vs one 50ms block)")
        stream.reset();val first=stream.accept(FloatArray(800));stream.reset();check(first.copy(elapsedMs=0)==stream.accept(FloatArray(800)).copy(elapsedMs=0))
    } finally { stream.close();stream.close();confirm.close() }
    check(runCatching { stream.accept(FloatArray(800)) }.isFailure)
}
