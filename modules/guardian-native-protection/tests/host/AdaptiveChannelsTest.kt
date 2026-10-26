package expo.modules.guardiannativeprotection

import kotlin.math.*

fun adaptiveChannelsTests() {
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
