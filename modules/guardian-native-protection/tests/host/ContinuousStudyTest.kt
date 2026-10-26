package expo.modules.guardiannativeprotection

fun continuousStudyTests() {
    val quality=GuardianChannelQualityScores()
    check(quality.update(0,12f,0f)>quality.update(1,3f,0f))
    check(quality.update(2,12f,.02f)<quality.update(3,12f,0f))
    check(quality.update(0,-20f,0f)>-20f) // One transient cannot instantly reverse a view.
    quality.reset(); check(quality.update(0,-20f,0f)==-20f)
    val buffer=GuardianStudyBuffer(10)
    buffer.append(floatArrayOf(0f,1f,2f,3f))
    check(buffer.window(5,3)==null) // Future output must wait, not zero-fill.
    check(buffer.window(4,3)!!.contentEquals(floatArrayOf(1f,2f,3f)))
    buffer.append(FloatArray(12) { (it+4).toFloat() })
    check(buffer.window(7,2)==null) // Evicted interval, never splice.
    check(buffer.window(14,8)!!.contentEquals(FloatArray(8) { (it+6).toFloat() }))
    check(runCatching { buffer.append(floatArrayOf(Float.NaN)) }.isFailure)
    val source=FloatArray(16000*24) { it.toFloat() }
    val ends=mutableListOf<Long>()
    val segmenter=GuardianSpeechSegmenter(object: GuardianSpeechSegmenter.Sink {
        override fun onSpeechStarted() {}
        override fun onSpeechEnded() {}
        override fun onWindow(id: Long,pcm: FloatArray,final: Boolean,reason: String) = error("Missing clock")
        override fun onWindowAt(id: Long,pcm: FloatArray,final: Boolean,reason: String,endSample: Long) {
            check(pcm.contentEquals(source.copyOfRange((endSample-pcm.size).toInt(),endSample.toInt())))
            ends.add(endSample)
        }
    })
    // A pause-aligned rollover can end well BEFORE the frame causing emission.
    for (offset in source.indices step 800) {
        val sec=offset/16000.0
        val speech=sec>=2 && sec<21 && !(sec>=6 && sec<7)
        segmenter.accept(source.copyOfRange(offset,offset+800),speech)
    }
    check(ends.size>=3)
    println("PASS continuous sample clock, future/evicted windows, pause rollover and natural end")
}
