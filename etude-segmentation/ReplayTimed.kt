package expo.modules.guardiannativeprotection
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
fun main(args: Array<String>) {
 val input=ByteBuffer.wrap(File(args[0]).readBytes()).order(ByteOrder.LITTLE_ENDIAN)
 val out=File(args[1]);check(!out.exists());out.mkdirs()
 var delivered=0L
 val events=StringBuilder("id\tsamples\treason\tavailableAtSample\n")
 val segmenter=GuardianSpeechSegmenter(object:GuardianSpeechSegmenter.Sink {
  override fun onSpeechStarted() {}
  override fun onSpeechEnded() {}
  override fun onWindow(id:Long,pcm:FloatArray,final:Boolean,reason:String) {
   check(final)
   val b=ByteBuffer.allocate(pcm.size*4).order(ByteOrder.LITTLE_ENDIAN);pcm.forEach{b.putFloat(it)}
   File(out,"segment-$id.f32").writeBytes(b.array());events.append("$id\t${pcm.size}\t$reason\t$delivered\n")
  }
 })
 while(input.hasRemaining()) {
  val n=input.int;val speech=input.get().toInt()!=0;val pcm=FloatArray(n){input.float};delivered+=n;segmenter.accept(pcm,speech)
 }
 File(out,"events.tsv").writeText(events.toString())
}
