package expo.modules.guardiannativeprotection

import android.util.Log
import java.io.File
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Explicit WAV diagnostics only. Disk IO never runs on the capture thread. */
internal class GuardianChannelDiagnostics(private val directory: File,private val channels: Int) : AutoCloseable {
    private data class Frame(val pcm: ShortArray,val sequence: Long,val time: Long,val result: GuardianAdaptiveChannels.Result)
    private val queue=ArrayBlockingQueue<Frame>(32)
    private val closed=AtomicBoolean()
    private val dropped=AtomicInteger()
    private val worker=Thread({ run() },"Guardian-Channel-WAV").apply { isDaemon=true;priority=Thread.MIN_PRIORITY;start() }
    fun offer(pcm: ShortArray,count: Int,sequence: Long,time: Long,result: GuardianAdaptiveChannels.Result) {
        if (!closed.get() && !queue.offer(Frame(pcm.copyOf(count),sequence,time,result))) dropped.incrementAndGet()
    }
    override fun close() { closed.set(true) }
    private fun run() {
        val writers=mutableListOf<GuardianDiagnosticWavWriter>()
        var frames=0L;var limited=false
        try {
            check(directory.isDirectory || directory.mkdirs())
            repeat(channels) { c -> writers.add(GuardianDiagnosticWavWriter(File(directory,"channel-$c"),"CHANNEL","capture",null,emitLifecycleEvents=false)) }
            File(directory,"selection.tsv").bufferedWriter().use { trace ->
                trace.write("sequence\tcapturedAtNs\tsamples\tselected\ttransition\tscores\tvalid\n")
                var samples=0L
                while (!closed.get() || queue.isNotEmpty()) {
                    val f=queue.poll(50,TimeUnit.MILLISECONDS) ?: continue
                    val n=f.pcm.size/channels
                    if (samples+n>16000L*180) { limited=true;break }
                    writers.forEachIndexed { c,w ->
                        w.write("raw",FloatArray(n) { f.pcm[it*channels+c]/32768f },f.sequence,f.time)
                        check(!w.isClosed)
                    }
                    trace.write("${f.sequence}\t${f.time}\t$n\t${f.result.selected}\t${f.result.transition}\t${f.result.scores}\t${f.result.valid}\n")
                    frames++;samples+=n
                }
            }
        } catch (error: Throwable) { dropped.incrementAndGet();Log.w("GuardianAudioEngine","ADAPTIVE_CHANNELS_WAV_ERROR",error) }
        finally {
            closed.set(true);queue.clear();writers.forEach { it.close() }
            Log.i("GuardianAudioEngine","ADAPTIVE_CHANNELS_WAV_SAVED path=${directory.absolutePath} frames=$frames dropped=${dropped.get()} limited=$limited")
        }
    }
}
