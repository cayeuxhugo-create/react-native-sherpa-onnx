package expo.modules.guardiannativeprotection

/** Keeps a selected-channel DSP timeline aligned across safe recalibrations.
 * Never drops original samples when a filter epoch is abandoned. Every candidate
 * crossing a calibration/channel boundary must use its ENTIRE original PCM. */
internal class GuardianWienerStudyStream {
    data class Status(val startupBypass:Boolean,val reason:String,val calibrationReady:Boolean,
        val calibrationValid:Boolean,val epochStart:Long,val channelRevision:Long)
    private data class Epoch(val start:Long,val revision:Long,var ready:Boolean=false,var valid:Boolean=true,var finalizedEnd:Long=Long.MAX_VALUE)
    private var core=GuardianWienerEnhancer()
    private val raw=GuardianStudyBuffer()
    private val epochs=mutableListOf<Epoch>()
    private var previousSpeech=false
    var inputSamples=0L; private set
    var outputSamples=0L; private set
    var resetCount=0; private set
    val calibrationReady get()=core.calibrationReady
    val calibrationValid get()=core.calibrationValid
    val currentEpochStart get()=epochs.lastOrNull()?.start ?: 0L
    fun process(pcm:FloatArray,revision:Long,speech:Boolean):FloatArray {
        require(pcm.isNotEmpty() && pcm.all { it.isFinite() })
        val old=epochs.lastOrNull()
        val restart=old!=null && (old.revision!=revision || previousSpeech && !speech && !core.calibrationValid)
        var tail=FloatArray(0)
        if (restart) {
            checkNotNull(old).finalizedEnd=outputSamples
            val count=(inputSamples-outputSamples).toInt()
            if (count>0) tail=raw.window(inputSamples,count) ?: error("Original tail unavailable")
            outputSamples+=tail.size
            core=GuardianWienerEnhancer();resetCount++
        }
        if (old==null || restart) epochs.add(Epoch(inputSamples,revision))
        while (epochs.size>1 && epochs[1].start<inputSamples-16000*16) epochs.removeAt(0)
        raw.append(pcm)
        if (speech) core.markCalibrationSpeech(core.inputSamples+pcm.size)
        val clean=core.process(pcm)
        inputSamples+=pcm.size;outputSamples+=clean.size
        check(outputSamples<=inputSamples)
        epochs.last().ready=core.calibrationReady;epochs.last().valid=core.calibrationValid
        previousSpeech=speech
        return if (tail.isEmpty()) clean else tail+clean
    }
    fun status(start:Long,end:Long):Status {
        require(start>=0 && end>=start)
        val index=epochs.indexOfLast { it.start<=start }
        if (index<0) return Status(true,"EXPIRED_EPOCH",false,false,0,-1)
        val epoch=epochs[index]
        val crossed=index<epochs.lastIndex && end>epochs[index+1].start
        val ready=epoch.ready
        val reason=when {
            crossed -> "CHANNEL_OR_CALIBRATION_BOUNDARY"
            end>epoch.finalizedEnd -> "ABANDONED_FILTER_TAIL"
            !epoch.valid -> "SPEECH_DURING_CALIBRATION"
            !ready || start<epoch.start+GuardianWienerEnhancer.STABLE_START_SAMPLE -> "CALIBRATION_STARTUP"
            else -> "FILTERED"
        }
        return Status(reason!="FILTERED",reason,ready,epoch.valid,epoch.start,epoch.revision)
    }
}
