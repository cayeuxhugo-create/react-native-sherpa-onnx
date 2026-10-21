import java.util.ArrayDeque

data class Frame(
    val seq: Long,
    val samples: Int,
    val fusion: Float,
    val cross: Boolean = false,
    val far: Boolean = false,
)

fun main() {
    val maxSamples = 15_000
    val ring = ArrayDeque<Frame>()
    var sampleCount = 0L
    var crossCount = 0
    var farCount = 0
    var evidenceCount = 0
    var strongCount = 0
    var peak = 0f

    fun evict() {
        val r = ring.pollFirst() ?: return
        sampleCount = (sampleCount - r.samples).coerceAtLeast(0)
        if (r.cross) crossCount = (crossCount - 1).coerceAtLeast(0)
        if (r.far) farCount = (farCount - 1).coerceAtLeast(0)
        if (r.fusion >= .30f) evidenceCount = (evidenceCount - 1).coerceAtLeast(0)
        if (r.fusion >= .55f) strongCount = (strongCount - 1).coerceAtLeast(0)
        if (r.fusion >= peak) peak = ring.maxOfOrNull { it.fusion } ?: 0f
    }

    fun add(f: Frame) {
        while (ring.isNotEmpty() && sampleCount + f.samples > maxSamples) evict()
        ring.addLast(f)
        sampleCount += f.samples
        peak = maxOf(peak, f.fusion)
        if (f.cross) crossCount++
        if (f.far) farCount++
        if (f.fusion >= .30f) evidenceCount++
        if (f.fusion >= .55f) strongCount++
    }

    repeat(20) { i ->
        add(Frame(i.toLong(), 1_000, if (i == 2) .9f else .4f, cross = i % 3 == 0, far = i % 4 == 0))
    }

    check(ring.size == 15)
    check(ring.first.seq == 5L)
    check(ring.last.seq == 19L)
    check(sampleCount == 15_000L)
    check(peak == .4f) // seq=2 (0.9) a bien été évincée et le peak recalculé
    check(crossCount == ring.count { it.cross })
    check(farCount == ring.count { it.far })
    check(evidenceCount == ring.count { it.fusion >= .30f })
    check(strongCount == ring.count { it.fusion >= .55f })

    println("OK SpeechRescue rolling ring: first=${ring.first.seq}, last=${ring.last.seq}, size=${ring.size}, peak=$peak")
}
