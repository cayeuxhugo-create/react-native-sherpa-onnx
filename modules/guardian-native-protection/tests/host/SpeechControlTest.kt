package expo.modules.guardiannativeprotection

fun speechControlTests() {
    var floor = 0f
    for (rms in floatArrayOf(0f,0f,.013648f)) {
        if (floor == 0f) floor = GuardianNoiseFloorSeed.initial(rms,.001f,.1f)
    }
    check(floor == .013648f) // startup zeros must not create a fictitious 22dB SNR
    check(GuardianNoiseFloorSeed.initial(1e-9f,.001f,.1f)==.001f)
    check(GuardianNoiseFloorSeed.initial(.2f,.001f,.1f)==.1f)
    check(runCatching { GuardianNoiseFloorSeed.initial(Float.NaN,.001f,.1f) }.isFailure)
    val voice=floatArrayOf(.001f,-.002f,.003f,0f);val original=voice.copyOf()
    val noise=GuardianSpeechSegmentGain.apply(voice,false)
    check(noise.gain==1f && noise.pcm.contentEquals(voice))
    val speech=GuardianSpeechSegmentGain.apply(voice,true)
    check(speech.gain==4f && speech.pcm.indices.all { speech.pcm[it]==voice[it]*4f })
    check(voice.contentEquals(original))
    check(GuardianSpeechSegmentGain.apply(FloatArray(40),true).gain==1f)
    check(GuardianSpeechSegmentGain.apply(floatArrayOf(.8f,-.01f),true).gain==1f)
    val selector=GuardianVoiceFocusSelector(holdDuringSpeech=true)
    fun obs(a:Float,b:Float,confirmed:Boolean=true,valid0:Boolean=true,valid1:Boolean=true)=
        GuardianVoiceFocusSelector.Observation(a,b,channel0Valid=valid0,channel1Valid=valid1,
            speechEvidence=confirmed,confirmedSpeech=confirmed)
    check(selector.update(obs(2f,8f)).view==GuardianVoiceFocusSelector.View.CHANNEL_1)
    repeat(40) { check(selector.update(obs(14f,1f)).view==GuardianVoiceFocusSelector.View.CHANNEL_1) }
    // A dead/clipped active channel still permits immediate safe fallback.
    check(selector.update(obs(9f,1f,valid1=false)).view==GuardianVoiceFocusSelector.View.CHANNEL_0)
    selector.update(obs(1f,10f,confirmed=false))
    check(selector.update(obs(1f,10f)).view==GuardianVoiceFocusSelector.View.CHANNEL_1)
    selector.reset()
    check(selector.transitionCount()==0L)
    println("PASS startup zero calibration, subthreshold nonzero input, speech-only constant gain and utterance channel lock/failure/release")
}
