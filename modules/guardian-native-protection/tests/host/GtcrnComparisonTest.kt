package expo.modules.guardiannativeprotection
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

fun gtcrnComparisonTests() {
    val directory=Files.createTempDirectory("gtcrn-paired-").toFile()
    val closed=CountDownLatch(1);val events=CopyOnWriteArrayList<String>()
    val diagnostic=GuardianVoiceDiagnostics(true,output={events+=it;if(it.contains("GTCRN_COMPARISON_SAVED"))closed.countDown()})
    val lane=GuardianGtcrnComparison(directory,GuardianVoiceProfile.NOISY,diagnostic)
    val original=floatArrayOf(.01f,-.01f,.02f,-.02f);val before=original.copyOf()
    val key=GuardianVoiceInputRouter.Key(1,100)
    lane.enhanced(key,floatArrayOf(.02f,-.02f,.03f,-.03f))
    lane.primary(GuardianVoiceInputRouter.Input(key,original,2f,true,0))
    // Wrong timestamp must never be joined to sequence 2.
    lane.primary(GuardianVoiceInputRouter.Input(GuardianVoiceInputRouter.Key(2,200),original,2f,true,0))
    lane.enhanced(GuardianVoiceInputRouter.Key(2,201),floatArrayOf(.8f))
    lane.close();lane.close();check(closed.await(5,TimeUnit.SECONDS))
    try {
        check(original.contentEquals(before))
        check(events.any{it.contains("\"pairs\":1")});check(events.none{it.contains("GTCRN_COMPARISON_ERROR")})
        val wav=directory.listFiles()!!.filter{it.extension=="wav"};check(wav.size==2)
        check(wav.all{it.length()==44L+original.size*2})
        val index=directory.listFiles()!!.single{it.extension=="tsv"}.readText()
        check(index.contains("truth\t1\t100\t0\t4"));check(index.contains("asr\t1\t100\t0\t4"));check(!index.contains("\t2\t"))
        println("PASS independent GTCRN pairing: enhanced-before-original, exact timestamp, immutable PCM, bounded close and two aligned WAVs")
    } finally { directory.deleteRecursively() }
}
