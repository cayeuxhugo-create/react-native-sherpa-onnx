package expo.modules.guardiannativeprotection

fun wienerStudyTests() {
    val stream=GuardianWienerStudyStream()
    val x=FloatArray(16000*10) { kotlin.math.sin(it*.013).toFloat()*.005f }
    for (start in 0 until 16000*5 step 800) stream.process(x.copyOfRange(start,start+800),1,false)
    check(!stream.status(52000,64000).startupBypass)
    check(stream.status(0,32000).startupBypass)
    val oldEnd=stream.outputSamples;val inputEnd=stream.inputSamples
    val switched=stream.process(x.copyOfRange(80000,80800),2,true)
    val tail=(inputEnd-oldEnd).toInt()
    check(switched.copyOfRange(0,tail).contentEquals(x.copyOfRange(oldEnd.toInt(),inputEnd.toInt())))
    check(stream.status(64000,80000).reason=="ABANDONED_FILTER_TAIL")
    check(stream.status(70000,80500).startupBypass)
    check(stream.status(80000,80500).startupBypass)
    check(!stream.calibrationValid)
    // Quiet restarts calibration after its contaminated beginning; source clock never resets.
    stream.process(x.copyOfRange(80800,81600),2,false)
    for (start in 81600 until 160000 step 800) stream.process(x.copyOfRange(start,start+800),2,false)
    check(stream.calibrationReady && stream.calibrationValid && stream.resetCount==2)
    check(!stream.status(132000,144000).startupBypass)
    check(stream.inputSamples==160000L && stream.outputSamples<=stream.inputSamples)
    println("PASS Wiener channel epochs, exact original tail, full-window guards and quiet recalibration")
}
