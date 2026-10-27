package expo.modules.guardiannativeprotection

/** Independent Wiener timelines for actual physical channels. Selection never resets DSP.
 * All positions use the primary capture's global sample clock. Missing channel data
 * remains missing: late origins and gaps are never padded or copied from primary PCM. */
internal class GuardianWienerChannelBank {
    data class Block(
        val channelIndex: Int, val routeRevision: Long, val originSample: Long,
        val inputStart: Long, val truth: FloatArray, val outputStart: Long,
        val filtered: FloatArray, val usable: Boolean,
        val calibrationReady: Boolean, val calibrationValid: Boolean, val resets: Int,
    )
    data class Candidate(
        val channelIndex: Int, val truth: FloatArray, val filtered: FloatArray,
        val status: GuardianWienerStudyStream.Status, val usable: Boolean,
    )
    private data class Epoch(
        val start: Long, val revision: Long, var end: Long = Long.MAX_VALUE,
        var finalizedEnd: Long = Long.MAX_VALUE, var ready: Boolean = false,
        var valid: Boolean = true,
    )
    private class Channel(val index: Int) {
        val truth = TimelineBuffer()
        val filtered = TimelineBuffer()
        val epochs = ArrayList<Epoch>()
        var core: GuardianWienerEnhancer? = null
        var previousSpeech = false
        var usable = false
        var resets = 0
        var inputEnd = 0L
    }
    private val channels = linkedMapOf<Int, Channel>()
    var inputSamples = 0L
        private set
    var routeRevision: Long? = null
        private set
    val channelIndices: List<Int> get() = channels.keys.sorted()
    val resetCount: Int get() = channels.values.sumOf { it.resets }

    /** Call for every primary block, including blocks without an eligible payload. */
    fun process(frame: GuardianCaptureChannelFrame?, sampleCount: Int, speech: Boolean): List<Block> {
        require(sampleCount > 0)
        frame?.validate(sampleCount)
        val result = ArrayList<Block>()
        val start = inputSamples
        val present = frame?.channels?.filter {
            it.usable || it.samples.any { sample -> sample != 0f } ||
                (routeRevision == frame.routeRevision && channels[it.index]?.epochs?.lastOrNull()?.revision == frame.routeRevision)
        }?.associateBy { it.index }.orEmpty()
        for (channel in channels.values) {
            if (channel.core != null && (frame == null || routeRevision != frame.routeRevision || channel.index !in present)) {
                close(channel, result)
            }
        }
        if (frame != null) routeRevision = frame.routeRevision
        for ((index, payload) in present) {
            val channel = channels.getOrPut(index) { Channel(index) }
            if (channel.core != null && channel.previousSpeech && !speech && !checkNotNull(channel.core).calibrationValid) {
                close(channel, result)
            }
            if (channel.core == null) {
                if (channel.epochs.isNotEmpty()) channel.resets++
                channel.core = GuardianWienerEnhancer()
                channel.epochs.add(Epoch(start, checkNotNull(frame).routeRevision))
            }
            val core = checkNotNull(channel.core)
            val epoch = channel.epochs.last()
            channel.truth.append(start, payload.samples)
            channel.inputEnd = start + sampleCount
            channel.usable = payload.usable
            // Quality/usable flags never substitute for neural speech evidence.
            // Even a temporarily unusable present channel advances its own state.
            if (speech) core.markCalibrationSpeech(core.inputSamples + sampleCount)
            val outputStart = epoch.start + core.outputSamples
            val filtered = core.process(payload.samples)
            channel.filtered.append(outputStart, filtered)
            epoch.ready = core.calibrationReady
            epoch.valid = core.calibrationValid
            channel.previousSpeech = speech
            result.add(Block(index, epoch.revision, epoch.start, start, payload.samples,
                outputStart, filtered, payload.usable, epoch.ready, epoch.valid, channel.resets))
            while (channel.epochs.size > 1 && channel.epochs[1].start < start - CAPACITY) channel.epochs.removeAt(0)
        }
        inputSamples += sampleCount
        return result
    }

    /** A candidate waits for every covered channel's actual finalized samples. */
    fun canFinalize(start: Long, end: Long): Boolean {
        require(start >= 0 && end >= start)
        if (end > inputSamples) return false
        if (start < maxOf(0L, inputSamples - CAPACITY)) return true
        return channels.values.all { channel ->
            !channel.truth.covers(start, end) || channel.filtered.covers(start, end)
        }
    }

    fun window(channelIndex: Int, start: Long, end: Long): Candidate? {
        require(start >= 0 && end > start)
        if (start < maxOf(0L, inputSamples - CAPACITY)) return null
        val channel = channels[channelIndex] ?: return null
        val truth = channel.truth.window(start, end) ?: return null
        val index = channel.epochs.indexOfLast { it.start <= start }
        if (index < 0) return null
        val epoch = channel.epochs[index]
        val crossed = index < channel.epochs.lastIndex && end > channel.epochs[index + 1].start
        val reason = when {
            crossed -> "ROUTE_OR_CALIBRATION_BOUNDARY"
            end > epoch.finalizedEnd -> "ABANDONED_FILTER_TAIL"
            !epoch.valid -> "SPEECH_DURING_CALIBRATION"
            !epoch.ready || start < epoch.start + GuardianWienerEnhancer.STABLE_START_SAMPLE -> "CALIBRATION_STARTUP"
            else -> "FILTERED"
        }
        val bypass = reason != "FILTERED"
        val filtered = if (bypass) truth.copyOf() else channel.filtered.window(start, end) ?: return null
        return Candidate(channelIndex, truth, filtered,
            GuardianWienerStudyStream.Status(bypass, reason, epoch.ready, epoch.valid, epoch.start, epoch.revision),
            channel.usable)
    }

    private fun close(channel: Channel, result: MutableList<Block>) {
        val core = channel.core ?: return
        val epoch = channel.epochs.last()
        val finalizedEnd = epoch.start + core.outputSamples
        epoch.end = channel.inputEnd
        epoch.finalizedEnd = finalizedEnd
        val tail = channel.truth.window(finalizedEnd, channel.inputEnd) ?: FloatArray(0)
        if (tail.isNotEmpty()) {
            // Real recorded samples only. Guard any entire window touching this tail.
            channel.filtered.append(finalizedEnd, tail)
            result.add(Block(channel.index, epoch.revision, epoch.start, channel.inputEnd,
                FloatArray(0), finalizedEnd, tail, channel.usable, epoch.ready, epoch.valid, channel.resets))
        }
        channel.core = null
        channel.previousSpeech = false
    }

    /** Absolute-index ring plus compact coverage spans; no per-sample tags or zero prefix. */
    private class TimelineBuffer {
        private data class Span(val start: Long, var end: Long)
        private val ring = FloatArray(CAPACITY)
        private val spans = ArrayList<Span>()
        private var end = 0L
        fun append(start: Long, pcm: FloatArray) {
            if (pcm.isEmpty()) return
            require(start >= end && pcm.all { it.isFinite() })
            for (i in pcm.indices) ring[((start + i) % CAPACITY).toInt()] = pcm[i]
            val nextEnd = start + pcm.size
            if (spans.lastOrNull()?.end == start) spans.last().end = nextEnd else spans.add(Span(start, nextEnd))
            end = nextEnd
            while (spans.isNotEmpty() && spans.first().end <= end - CAPACITY) spans.removeAt(0)
        }
        fun covers(start: Long, stop: Long): Boolean = stop >= start && stop <= end &&
            start >= maxOf(0L, end - CAPACITY) && spans.any { it.start <= start && it.end >= stop }
        fun window(start: Long, stop: Long): FloatArray? {
            if (stop == start) return FloatArray(0)
            if (!covers(start, stop) || stop - start > CAPACITY) return null
            return FloatArray((stop - start).toInt()) { ring[((start + it) % CAPACITY).toInt()] }
        }
    }

    companion object { private const val CAPACITY = 16_000 * 16 }
}
