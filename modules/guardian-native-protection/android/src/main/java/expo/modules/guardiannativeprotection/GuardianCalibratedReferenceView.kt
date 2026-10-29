package expo.modules.guardiannativeprotection

/** Single input-worker owner. Optional candidate pipeline, never a microphone
 * merger: one stable primary and one synchronous noise reference per epoch.
 * Missing sources, selected-source changes and route changes restart calibration.
 * Original PCM and this view use the same source clock; the retained FFT tail is
 * unavailable, never zero-padded. A VAD result is held only over that bounded tail. */
internal class GuardianCalibratedReferenceView(private val vad: GuardianFireRedStreaming) : AutoCloseable {
    private var enhancer: GuardianReferenceNoiseEnhancer? = null
    private var route: Long? = null
    private var second: Int? = null
    var primary: Int? = null; private set
    private var clock = 0L
    private var origin = 0L
    private var produced = 0L
    private val ring = FloatArray(16_000 * 16)
    private val block = FloatArray(800)
    private var filled = 0
    private var lastSpeech = false
    private var knownSources = emptySet<Int>()

    data class Window(val pcm: FloatArray, val primary: Int, val calibrationClean: Boolean,
        val acceptedProfileFrames: Int, val rejectedProfileFrames: Int)

    fun accept(original: FloatArray, capture: GuardianCaptureChannelFrame?, speech: Boolean): Boolean {
        val start=clock; clock+=original.size
        val valid=capture != null && runCatching { capture.validate(original.size) }.isSuccess
        val p=if(valid) capture!!.channels.firstOrNull { it.index==capture.selectedChannel } else null
        val r=if(valid) capture!!.channels.firstOrNull { it.index!=capture.selectedChannel &&
            (it.usable || it.samples.any { v->v!=0f }) } else null
        if (capture == null || !valid || p == null || !p.samples.contentEquals(original)) {
            abandon(clock); return false
        }
        // An omitted, previously nonzero source is a loss, not recorded silence.
        if (knownSources.any { seen -> capture.channels.none { it.index==seen } }) {
            abandon(start)
        }
        if (enhancer != null && (route!=capture.routeRevision || primary!=p.index ||
                capture.channels.none { it.index==second })) abandon(start)
        if (enhancer == null && r == null) {
            // Allow a genuine digital-zero startup prefix. Otherwise a later
            // reference starts a fresh epoch with its own full calibration.
            if (original.any { it!=0f } || capture.channels.any { it.samples.any { v->v!=0f } }) origin=clock
            return false
        }
        if (enhancer == null) {
            primary=p.index; second=checkNotNull(r).index; route=capture.routeRevision
            enhancer=GuardianReferenceNoiseEnhancer(GuardianReferenceNoiseEnhancer.Profile.LONG, true)
            var zeros=start-origin
            while(zeros>0) { val n=minOf(zeros,800L).toInt(); append(enhancer!!.process(FloatArray(n),FloatArray(n)));zeros-=n }
        }
        knownSources=capture.channels.filter { it.samples.any { v->v!=0f } }.map { it.index }.toSet()+knownSources
        val noise=capture.channels.single { it.index==second }.samples
        val e=checkNotNull(enhancer)
        if(speech)e.markCalibrationSpeech(e.inputSamples+original.size)
        append(e.process(original,noise))
        return lastSpeech
    }
    private fun append(pcm: FloatArray) {
        for(value in pcm) {
            ring[(produced%ring.size).toInt()]=value;produced++
            block[filled++]=value
            if(filled==block.size) { lastSpeech=vad.accept(block).speech;filled=0 }
        }
    }
    fun window(start: Long, end: Long): Window? {
        val e=enhancer ?: return null
        val a=start-origin;val b=end-origin
        if(!e.calibrationReady || !e.profileFinite || e.degenerateReference ||
            a<GuardianReferenceNoiseEnhancer.Profile.LONG.stableStartSample ||
            b>produced || a<produced-ring.size || b<=a || b-a>GuardianAsrWindowPolicy.MAX_SAMPLES) return null
        return Window(FloatArray((b-a).toInt()) { ring[((a+it)%ring.size).toInt()] },
            checkNotNull(primary), !e.observedSpeechDuringProfile,
            e.acceptedProfileFrames,e.rejectedProfileFrames)
    }
    private fun abandon(at: Long) {
        enhancer=null;route=null;primary=null;second=null;origin=at;produced=0
        filled=0;lastSpeech=false;knownSources=emptySet();vad.reset()
    }
    fun reset() { clock=0;abandon(0) }
    override fun close() { vad.close() }
}
