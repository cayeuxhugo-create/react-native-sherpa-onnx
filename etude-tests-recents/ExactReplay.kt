package expo.modules.guardiannativeprotection
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Private replay: id, full float path, actual phone WAV index TSV.
 * Same phone block lengths; no synthetic final padding or timing heuristics. */
fun main(args:Array<String>) {
 val assets=File(args[0]);val read:(String)->ByteArray={File(assets,it).readBytes()}
 val stream=GuardianFireRedStreaming.load(read);val confirmation=GuardianFireRedConfirmation.load(read)
 try {
  for (line in File(args[1]).readLines()) {
   val f=line.split('\t');val samples=ByteBuffer.wrap(File(f[1]).readBytes()).order(ByteOrder.LITTLE_ENDIAN)
   val dir=File(args[2],f[0]);check(!dir.exists());dir.mkdirs();var delivered=0L
   val events=StringBuilder("id\taccepted\tsamples\tavailableAtSample\tstartSample\tendSample\treason\n")
   val segmenter=GuardianSpeechSegmenter(object:GuardianSpeechSegmenter.Sink {
    override fun onSpeechStarted()=Unit
    override fun onSpeechEnded()=Unit
    override fun onWindow(id:Long,pcm:FloatArray,final:Boolean,reason:String)=error("Missing sample clock")
    override fun onWindowAt(id:Long,pcm:FloatArray,final:Boolean,reason:String,endSample:Long) {
     check(final);val before=pcm.copyOf();val result=confirmation.confirm(pcm);check(pcm.contentEquals(before))
     val b=ByteBuffer.allocate(pcm.size*4).order(ByteOrder.LITTLE_ENDIAN);pcm.forEach{b.putFloat(it)};File(dir,"segment-$id.f32").writeBytes(b.array())
     events.append("$id\t${result.decision.accepted}\t${pcm.size}\t$delivered\t${endSample-pcm.size}\t$endSample\t$reason\n")
    }
   })
   stream.reset();var previousSequence:Long?=null
   for (row in File(f[2]).readLines().drop(1)) {
    val c=row.split('\t');if(c[0]!="asr")continue
    val sequence=c[1].toLong();check(previousSequence==null || sequence==previousSequence!!+1);previousSequence=sequence
    val n=c[4].toInt();check(n in 1..800 && c[3].toLong()==delivered && samples.remaining()>=n*4)
    val pcm=FloatArray(n){samples.float};val speech=stream.accept(pcm).speech;delivered+=n;segmenter.accept(pcm,speech)
   }
   check(samples.remaining()==0);File(dir,"events.tsv").writeText(events.toString());println("PASS exact replay ${f[0]} samples=$delivered")
  }
 } finally { stream.close();confirmation.close() }
}
