package expo.modules.guardiannativeprotection

import java.util.Random
import kotlin.math.abs
import kotlin.math.sqrt

fun wienerEnhancerTests() {
    val readiness = GuardianWienerEnhancer()
    check(!readiness.calibrationReady && readiness.calibrationValid)
    readiness.process(FloatArray(47_999))
    check(!readiness.calibrationReady && readiness.calibrationValid)
    readiness.process(floatArrayOf(0f))
    check(readiness.calibrationReady && readiness.calibrationValid)
    val tainted = GuardianWienerEnhancer()
    tainted.markCalibrationSpeech(800)
    check(!tainted.calibrationReady && !tainted.calibrationValid)
    tainted.process(FloatArray(48_000))
    check(tainted.calibrationReady && !tainted.calibrationValid)

    fun stream(source: FloatArray, chunks: IntArray, speechAtEnd: Long? = null): FloatArray {
        val enhancer = GuardianWienerEnhancer()
        val output = ArrayList<Float>()
        var position = 0
        var chunkIndex = 0
        var marked = false
        while (position < source.size) {
            val end = minOf(source.size, position + chunks[chunkIndex++ % chunks.size])
            if (!marked && speechAtEnd != null && end >= speechAtEnd) {
                enhancer.markCalibrationSpeech(speechAtEnd)
                marked = true
            }
            val block = source.copyOfRange(position, end)
            val unchanged = block.copyOf()
            val enhanced = enhancer.process(block)
            check(block.contentEquals(unchanged)) { "Source PCM changed" }
            check(enhanced.all { it.isFinite() })
            output.addAll(enhanced.toList())
            check(enhancer.inputSamples == end.toLong())
            check(enhancer.outputSamples == output.size.toLong())
            check(enhancer.inputSamples - enhancer.outputSamples in 0L..511L) { "Lookahead exceeds 512 samples" }
            position = end
        }
        check(enhancer.calibrationReady == (source.size >= 48_000))
        check(enhancer.calibrationValid == (speechAtEnd == null))
        check(enhancer.process(FloatArray(0)).isEmpty()) { "Empty input fabricated a flush" }
        return output.toFloatArray()
    }

    val silence = stream(FloatArray(80_000), intArrayOf(400))
    check(silence.size == 79_616 && silence.all { it == 0f })
    println("PASS streaming Wiener silence remains finite and retains the true unflushed tail")

    val random = Random(98L)
    val source = FloatArray(144_139) { (random.nextGaussian() * .025).toFloat() }
    val sourceCopy = source.copyOf()
    val block400 = stream(source, intArrayOf(400))
    val block800 = stream(source, intArrayOf(800))
    val irregular = stream(source, intArrayOf(1, 257, 17, 901, 63, 800, 511))
    check(block400.contentEquals(block800) && block400.contentEquals(irregular)) { "Chunk boundaries alter PCM" }
    check(source.contentEquals(sourceCopy))
    check(block400.copyOfRange(0, 48_000).contentEquals(source.copyOfRange(0, 48_000)))
    var originalPower = 0.0
    var enhancedPower = 0.0
    for (i in 49_600 until block400.size) {
        originalPower += source[i].toDouble() * source[i]
        enhancedPower += block400[i].toDouble() * block400[i]
    }
    check(sqrt(enhancedPower / originalPower) in .49..0.9) { "Noise was not attenuated with the fixed floor" }
    println("PASS Wiener output is chronological, chunk invariant, source preserving, with exact 3 s passthrough")

    for (speechEnd in longArrayOf(800L, 47_900L, 48_000L, 48_800L)) {
        val bypass = stream(source, intArrayOf(800), speechEnd)
        check(bypass.contentEquals(source.copyOfRange(0, bypass.size))) { "Contaminated calibration changed audio" }
    }
    val laterSpeech = GuardianWienerEnhancer()
    laterSpeech.process(source.copyOfRange(0, 50_000))
    laterSpeech.markCalibrationSpeech(50_000)
    check(laterSpeech.calibrationValid) { "Normal speech after calibration invalidated the profile" }
    println("PASS calibration speech including a boundary-spanning VAD block permanently selects original audio")

    val timing = FloatArray(64_000)
    timing[52_123] = .5f
    val aligned = stream(timing, intArrayOf(400, 800))
    check(aligned.indices.maxBy { abs(aligned[it]) } == 52_123) { "Enhanced audio changed sample origin" }
    check(runCatching { GuardianWienerEnhancer().process(floatArrayOf(Float.NaN)) }.isFailure)
    println("PASS enhanced impulse retains original sample origin and non-finite input is rejected")
}
