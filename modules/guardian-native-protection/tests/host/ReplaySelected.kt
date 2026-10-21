package expo.modules.guardiannativeprotection
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
fun main(args: Array<String>) {
 val input = ByteBuffer.wrap(File(args[0]).readBytes()).order(ByteOrder.LITTLE_ENDIAN)
 val out = File(args[1]); out.mkdirs()
 val segmenter = GuardianSpeechSegmenter(object : GuardianSpeechSegmenter.Sink {
  override fun onSpeechStarted() {}
  override fun onSpeechEnded() {}
  override fun onWindow(id: Long, pcm: FloatArray, final: Boolean, reason: String) {
   check(final) { "An interim decode was scheduled" }
   val b = ByteBuffer.allocate(pcm.size*4).order(ByteOrder.LITTLE_ENDIAN)
   pcm.forEach { b.putFloat(it) }
   File(out,"segment-$id.f32").writeBytes(b.array())
   println("$id\t${pcm.size}\t$reason")
  }
 })
 while(input.hasRemaining()) {
  val n=input.int; val speech=input.get().toInt()!=0
  val pcm=FloatArray(n) { input.float }; segmenter.accept(pcm,speech)
 }
}
