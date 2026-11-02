package expo.modules.guardiannativeprotection
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.lang.management.ManagementFactory
/** Real private capture input, no audio or paths emitted in results. Run on a quiet host. */
internal object GuardianCpuBench {
    private fun read(path:String):FloatArray {
        val bytes=File(path).readBytes();require(bytes.size%4==0)
        val buffer=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        return FloatArray(buffer.remaining()).also { buffer.get(it) }
    }
    @JvmStatic fun main(args:Array<String>) {
        require(args.size==2) { "expected two synchronous raw channels at 16 kHz" }
        val a=read(args[0]);val b=read(args[1]);require(a.size==b.size && a.isNotEmpty())
        val input=ShortArray(a.size*4)
        for(i in a.indices) { input[i*4]=(a[i]*32768f).toInt().coerceIn(-32768,32767).toShort();input[i*4+1]=(b[i]*32768f).toInt().coerceIn(-32768,32767).toShort() }
        val bean=ManagementFactory.getThreadMXBean();check(bean.isCurrentThreadCpuTimeSupported)
        bean.isThreadCpuTimeEnabled=true
        for(mode in listOf("PREPROCESS_4_CHANNELS","PREPROCESS_PLUS_LONG")) {
            for(trial in 0..3) {
                val pre=GuardianChannelPreprocessor(4)
                val long=if(mode.endsWith("LONG")) GuardianReferenceNoiseEnhancer(GuardianReferenceNoiseEnhancer.Profile.LONG,true) else null
                val cpu=bean.currentThreadCpuTime;val start=System.nanoTime();var checksum=0.0;var emitted=0
                var offset=0
                while(offset<a.size) {
                    val n=minOf(800,a.size-offset)
                    val chunk=input.copyOfRange(offset*4,(offset+n)*4)
                    val frame=pre.process(chunk,chunk.size,1,0,listOf(true,true,false,false))
                    val x=frame.channels.firstOrNull{it.index==0}?.samples ?: FloatArray(n)
                    val y=frame.channels.firstOrNull{it.index==1}?.samples ?: FloatArray(n)
                    val output=long?.process(x,y) ?: x
                    emitted+=output.size
                    if(output.isNotEmpty())checksum+=output[output.size/2]
                    offset+=n
                }
                val cpuMs=(bean.currentThreadCpuTime-cpu)/1e6;val wallMs=(System.nanoTime()-start)/1e6
                check(checksum.isFinite())
                if(trial>0)println("{\"stage\":\"$mode\",\"trial\":$trial,\"audioSeconds\":${a.size/16000.0},\"cpuMs\":$cpuMs,\"wallMs\":$wallMs,\"outputSamples\":$emitted}")
            }
        }
    }
}
