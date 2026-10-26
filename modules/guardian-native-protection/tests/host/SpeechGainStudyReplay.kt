/** Private-manifest replay of production spatial selection, FireRed and segment gain.
 * TSV fields: anonymous ID, mono|stereo, input path, ORIGINAL|CANDIDATE[_GAIN2|_GAIN4]|LEGACY|LOCKED.
 * Mono is Float32 LE; stereo is interleaved PCM16 LE without WAV header.
 * Closed-loop neural hints are synchronous on the host; Android is asynchronous. */
package expo.modules.guardiannativeprotection
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
fun main(args:Array<String>) {
 val assets=File(args[0]);val read:(String)->ByteArray={File(assets,it).readBytes()}
 val stream=GuardianFireRedStreaming.load(read);val confirmation=GuardianFireRedConfirmation.load(read)
 fun save(f:File,x:FloatArray){val b=ByteBuffer.allocate(x.size*4).order(ByteOrder.LITTLE_ENDIAN);x.forEach{b.putFloat(it)};f.writeBytes(b.array())}
 try {for(line in File(args[1]).readLines()) {
  val f=line.split('\t');val dir=File(args[2],f[0]);check(!dir.exists());dir.mkdirs()
  val stereo=f[1]=="stereo";val candidate=f[3]!="ORIGINAL";val maximumGain=when { f[3].endsWith("GAIN4")->4f;f[3].endsWith("GAIN2")->2f;else->1f }
  val spatial=if(stereo) GuardianSpatialAudio(16000,800,inputTrustName="INTER_CHANNEL_ONLY",voiceFocusMode=GuardianVoiceFocusMode.ACTIVE,holdDuringSpeech=f[3].startsWith("LOCKED")) else null
  val bytes=File(f[2]).readBytes();val buffer=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
  var previousInput=0f;var previousOutput=0f;var delivered=0L
  val events=StringBuilder("id\taccepted\tsamples\tavailableAtSample\tgain\treason\n");val selections=StringBuilder("sequence\tview\ttransitions\n")
  val output=ArrayList<Float>();val flags=StringBuilder();stream.reset()
  val segmenter=GuardianSpeechSegmenter(object:GuardianSpeechSegmenter.Sink{
   override fun onSpeechStarted(){spatial?.setVoiceActivityHint(true)}
   override fun onSpeechEnded(){spatial?.setVoiceActivityHint(false)}
   override fun onWindow(id:Long,pcm:FloatArray,final:Boolean,reason:String){
    check(final);val v=confirmation.confirm(pcm).decision;val g=GuardianSpeechSegmentGain.apply(pcm,confirmed=candidate&&v.accepted,maximumGain=maximumGain)
    save(File(dir,"segment-$id-input.f32"),pcm);save(File(dir,"segment-$id.f32"),g.pcm)
    events.append("$id\t${v.accepted}\t${pcm.size}\t$delivered\t${g.gain}\t$reason\n")
   }
  })
  while(buffer.hasRemaining()) {
   val n=minOf(800,buffer.remaining()/4);if(n==0)break
   val x=if(stereo){val interleaved=ShortArray(n*2){buffer.short};val mono=ShortArray(n);check(spatial!!.processInterleavedPcm16(interleaved,interleaved.size,mono)==n);FloatArray(n){i->
    val input=mono[i]/32768f;val y=.97f*(previousOutput+input-previousInput);previousInput=input;previousOutput=y;y.coerceIn(-1f,1f)
   }}else FloatArray(n){buffer.float}
   x.forEach{output.add(it)};val result=stream.accept(x);delivered+=n;flags.append(if(result.speech)'1' else '0');segmenter.accept(x,result.speech)
   spatial?.getVoiceFocusSnapshot()?.let{selections.append("${delivered/800}\t${it.selected}\t${it.transitionCount}\n")}
  }
  File(dir,"events.tsv").writeText(events.toString());File(dir,"selections.tsv").writeText(selections.toString());File(dir,"flags.txt").writeText(flags.toString());save(File(dir,"output.f32"),output.toFloatArray());println("PASS replay ${f[0]}")
 }}finally{stream.close();confirmation.close()}
}
