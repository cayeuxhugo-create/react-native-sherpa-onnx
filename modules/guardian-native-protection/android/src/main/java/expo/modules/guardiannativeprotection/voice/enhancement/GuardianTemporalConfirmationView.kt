package expo.modules.guardiannativeprotection

/** Continuous unity-gain confirmation audio, owned by the ordered input worker.
 * This view never detects an utterance and its samples never become ASR input.
 * Native output keeps its source-sample clock: unavailable output and source
 * discontinuities are not repaired with zero padding or an original-PCM copy.
 */
internal class GuardianTemporalConfirmationView(private val backend: Backend) : AutoCloseable {
    interface Backend : AutoCloseable {
        val frameShiftInSamples: Int
        /** Complete native input hop, unmodified and consumed synchronously. */
        fun run(pcm: FloatArray): FloatArray
        fun reset()
    }

    data class Window(
        val pcm: FloatArray,
        val primary: Int,
        val routeRevision: Long,
        val sourceStartSample: Long,
        val sourceEndSample: Long,
    )

    private val scratch = FloatArray(backend.frameShiftInSamples.also {
        require(it in 1..16_000) { "Invalid temporal GTCRN frame shift" }
    })
    private val ring = FloatArray(16_000 * 16)
    private var scratchSize = 0
    private var clock = 0L
    private var origin = 0L
    private var nativeInputSamples = 0L
    private var produced = 0L
    private var primary: Int? = null
    private var route: Long? = null
    private var openedChannels: Int? = null
    private val knownChannels = mutableSetOf<Int>()
    private var closed = false

    fun accept(original: FloatArray, capture: GuardianCaptureChannelFrame?) {
        check(!closed) { "Temporal confirmation view closed" }
        require(original.isNotEmpty())
        val start = clock
        clock = Math.addExact(clock, original.size.toLong())
        val valid = original.all(Float::isFinite) && capture != null &&
            runCatching { capture.validate(original.size) }.isSuccess
        if (!valid) {
            abandon(clock)
            return
        }
        val source = checkNotNull(capture)
        if (primary != null && (primary != source.selectedChannel || route != source.routeRevision ||
                openedChannels != source.openedChannels)) {
            abandon(start)
        }
        if (knownChannels.any { known -> source.channels.none { it.index == known } }) {
            abandon(clock)
            return
        }
        val selected = source.channels.firstOrNull { it.index == source.selectedChannel }
        // The capture contract permits omission only before a source's first
        // nonzero sample. Feed that genuine zero prefix, so native streaming
        // state matches a continuous capture from session sample zero.
        if ((selected == null && (source.selectedChannel in knownChannels || original.any { it != 0f })) ||
            (selected != null && !selected.samples.contentEquals(original))) {
            abandon(clock)
            return
        }
        if (primary == null) {
            primary = source.selectedChannel
            route = source.routeRevision
            openedChannels = source.openedChannels
        }
        knownChannels.addAll(source.channels.map { it.index })
        try {
            var offset = 0
            while (offset < original.size) {
                val count = minOf(scratch.size - scratchSize, original.size - offset)
                original.copyInto(scratch, scratchSize, offset, offset + count)
                scratchSize += count
                offset += count
                if (scratchSize == scratch.size) {
                    nativeInputSamples += scratch.size
                    val output = backend.run(scratch)
                    check(output.all(Float::isFinite)) { "Temporal GTCRN output is not finite" }
                    check(produced + output.size <= nativeInputSamples) { "Temporal GTCRN output exceeds input clock" }
                    for (value in output) {
                        ring[(produced % ring.size).toInt()] = value
                        produced++
                    }
                    scratchSize = 0
                }
            }
        } catch (error: Throwable) {
            // Invalidate all retained samples before any caller can recover.
            abandon(clock)
            throw error
        }
    }

    fun window(start: Long, end: Long, expectedChannel: Int, expectedRoute: Long?): Window? {
        if (closed || primary != expectedChannel || route != expectedRoute || expectedRoute == null) return null
        val a = start - origin
        val b = end - origin
        if (a < WARMUP_SAMPLES || b <= a || b - a > GuardianAsrWindowPolicy.MAX_SAMPLES ||
            b > produced || a < produced - ring.size) return null
        return Window(
            FloatArray((b - a).toInt()) { ring[((a + it) % ring.size).toInt()] },
            expectedChannel, expectedRoute, start, end,
        )
    }

    private fun abandon(at: Long) {
        origin = at
        nativeInputSamples = 0L
        produced = 0L
        scratchSize = 0
        primary = null
        route = null
        openedChannels = null
        knownChannels.clear()
        backend.reset()
    }

    fun reset() {
        check(!closed)
        clock = 0L
        abandon(0L)
    }

    override fun close() {
        if (closed) return
        closed = true
        scratchSize = 0
        produced = 0L
        primary = null
        route = null
        knownChannels.clear()
        // No native flush: no incomplete window can escape at stop/release.
        backend.close()
    }

    companion object {
        const val WARMUP_SAMPLES = 16_000L
    }
}
