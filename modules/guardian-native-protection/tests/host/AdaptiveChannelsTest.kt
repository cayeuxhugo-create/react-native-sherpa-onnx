package expo.modules.guardiannativeprotection

import kotlin.math.*

fun adaptiveChannelsTests() {
    adaptiveChannelsRejectWeakRankFluctuations()
    adaptiveChannelsPreserveQuietShortVoiceAndReadTiming()
    val random=java.util.Random(123)
    val selector=GuardianAdaptiveChannels(4)
    var selected=0;var switched=false;var checked=0
    for (frame in 0 until 100) {
        val pcm=ShortArray(800*4)
        for (i in 0 until 800) {
            val t=(frame*800+i)/16000.0
            val speech=if (frame>=25) 1000*sin(2*PI*230*t)*(1+.4*sin(2*PI*4*t)) else 0.0
            val a=if (frame<55) 1.0 else .05
            val b=if (frame<55) .1 else 1.0
            pcm[i*4]=(speech*a+random.nextGaussian()*15).toInt().toShort()
            pcm[i*4+1]=(speech*b+random.nextGaussian()*15).toInt().toShort()
            pcm[i*4+2]=pcm[i*4+1] // duplicated third channel
            pcm[i*4+3]=0 // unusable fourth channel
        }
        val result=selector.process(pcm,pcm.size,frame>=25)
        check(result.pcm.size==800 && result.selected!=3)
        check(result.pcm.all { abs(it.toInt())<=2000 })
        if (frame==50) check(result.selected==0)
        if (frame>55 && result.transitioned) switched=true
        selected=result.selected;checked++
    }
    check(switched && selected in 1..2)
    val failover=GuardianAdaptiveChannels(3)
    val background=ShortArray(800*3) { i -> if (i%3==2) 0 else ((i/3)%100-50).toShort() }
    repeat(12) { failover.process(background,background.size,false) }
    val obstructed=ShortArray(800*3) { i -> if (i%3==1) ((i/3)%100-50).toShort() else 0 }
    val changed=failover.process(obstructed,obstructed.size,true)
    check(changed.selected==1 && changed.transitioned)
    check(changed.pcm.size==800 && changed.transition=="LOW_DISCONTINUITY_SPLICE")
    val clipped=ShortArray(800*3) { i -> if (i%3==1) 32767 else if (i%3==0) ((i/3)%100-50).toShort() else 0 }
    val recovered=failover.process(clipped,clipped.size,true)
    check(recovered.selected==0 && recovered.transitioned)
    // More than two channels: choose a working fourth input, not a fixed L/R pair.
    val four=GuardianAdaptiveChannels(4)
    val fourth=ShortArray(800*4) { if (it%4==3) 100 else 0 }
    check(four.process(fourth,fourth.size,false).selected==3)
    val mono=GuardianAdaptiveChannels(1)
    val x=shortArrayOf(4,-7,123,0)
    check(mono.process(x,x.size,false).pcm.contentEquals(x))
    check(runCatching { selector.process(shortArrayOf(1,2,3),3,false) }.isFailure)
    println("PASS adaptive N-channel selection, in-phrase change, duplicated/dead channels, exact mono and bounded output ($checked blocks)")
}

private fun adaptiveChannelsRejectWeakRankFluctuations() {
    // Both channels remain valid, but the near-floor ranking alternates every
    // 600 ms. A log score may differ by tens of dB when estimated excess power
    // is almost zero; these fluctuations must not keep changing the clock's
    // active physical source. No speech or amplitude admission is involved.
    val selector=GuardianAdaptiveChannels(2)
    val random=java.util.Random(919)
    var changes=0
    for (frame in 0 until 150) {
        val pcm=ShortArray(800*2)
        val alternate=((frame-30).coerceAtLeast(0)/12)%2
        for (i in 0 until 800) for(c in 0 until 2) {
            val gain=if(frame<30) 1.0 else if(c==alternate) .85 else 1.15
            pcm[i*2+c]=(18*gain*random.nextGaussian()).roundToInt().toShort()
        }
        val result=selector.process(pcm,pcm.size,frame>=30)
        check(result.valid.all { it })
        if(result.transitioned)changes++
    }
    check(changes==0) { "Weak alternating noise ranks caused $changes source changes" }
    println("PASS near-floor alternating rankings retain the active physical source")
}

private fun adaptiveChannelsPreserveQuietShortVoiceAndReadTiming() {
    // The SAME weak, short waveform is partitioned into several legal read
    // sizes. It must still select the better channel during the utterance,
    // without waiting for speech=false or a minimum absolute input level.
    val random=java.util.Random(311)
    val first=16000
    val last=first+11200 // 700 ms of voice, including its quiet consonant edges.
    val input=ShortArray(last*2)
    for(i in 0 until last) {
        val t=i/16000.0
        val voice=if(i>=first) sin(2*PI*185*t)+.3*sin(2*PI*370*t) else 0.0
        input[i*2]=(1.2*random.nextGaussian()+8*voice).roundToInt().toShort()
        input[i*2+1]=(1.2*random.nextGaussian()+28*voice).roundToInt().toShort()
    }
    val transitions=mutableListOf<Int>()
    for(read in listOf(160,400,800)) {
        val selector=GuardianAdaptiveChannels(2)
        var at=0;var firstChange=-1
        while(at<last) {
            val count=min(read,last-at)
            val pcm=input.copyOfRange(at*2,(at+count)*2)
            val result=selector.process(pcm,pcm.size,at>=first)
            if(result.transitioned && firstChange<0)firstChange=at+count
            check(result.pcm.size==count)
            at+=count
        }
        check(firstChange in first until last) { "Quiet short voice lost at read size $read" }
        transitions.add(firstChange)
    }
    check(transitions.max()-transitions.min()<=2400) { "Read-size-dependent evidence timing: $transitions" }
    println("PASS weak 700 ms voice changes channel during speech across 10/25/50 ms reads")
}
