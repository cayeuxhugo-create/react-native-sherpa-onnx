package expo.modules.guardiannativeprotection

import java.util.ArrayDeque

/** Original per-channel PCM on the segmenter's sample clock. Single worker only.
 * The caller owns each payload. Missing multichannel data never becomes mono.
 * Silence does not break a channel: usable is evidence from any part of a window.
 */
internal class GuardianChannelWindowHistory(private val capacity: Int = 16000 * 16) {
    data class Channel(val index: Int, val pcm: FloatArray, val contributedSamples: Int, val usableSamples: Int)
    data class Window(val required: Boolean, val valid: Boolean, val reason: String,
        val routeRevision: Long?, val openedChannels: Int, val channels: List<Channel> = emptyList())
    private data class Block(val start: Long, val end: Long, val payload: GuardianCaptureChannelFrame?,
        val multichannelExpected: Boolean, val invalid: Boolean, val knownBefore: Set<Int>)
    private val blocks = ArrayDeque<Block>()
    private var multichannelExpected = false
    private var sourceRoute: Long? = null
    private var sourceChannels = 0
    private val seenChannels = mutableSetOf<Int>()
    var end: Long = 0L; private set

    init { require(capacity > 0) }

    fun append(sampleCount: Int, captureChannels: GuardianCaptureChannelFrame?, startSample: Long = end) {
        require(sampleCount > 0 && startSample >= end)
        val invalid = captureChannels?.let {
            try { it.validate(sampleCount); false } catch (_: IllegalArgumentException) { true }
        } ?: false
        if (captureChannels != null) {
            // Invalid metadata must not clear previously established obligations.
            if (!invalid) {
                multichannelExpected = captureChannels.openedChannels > 1
                if (captureChannels.routeRevision != sourceRoute || captureChannels.openedChannels != sourceChannels) {
                    seenChannels.clear()
                    sourceRoute = captureChannels.routeRevision
                    sourceChannels = captureChannels.openedChannels
                }
            }
            else if (captureChannels.openedChannels > 1) multichannelExpected = true
        }
        end = Math.addExact(startSample, sampleCount.toLong())
        blocks.addLast(Block(startSample, end, captureChannels, multichannelExpected, invalid, seenChannels.toSet()))
        if (!invalid && captureChannels != null) seenChannels.addAll(captureChannels.channels.map { it.index })
        val firstRetained = maxOf(0L, end - capacity)
        while (blocks.isNotEmpty() && blocks.first.end <= firstRetained) blocks.removeFirst()
        if (blocks.isNotEmpty() && blocks.first.start < firstRetained) {
            val first = blocks.removeFirst()
            val offset = (firstRetained - first.start).toInt()
            val payload = first.payload?.let { p ->
                if (first.invalid) null else p.copy(channels = p.channels.map { c ->
                    c.copy(samples = c.samples.copyOfRange(offset, c.samples.size))
                })
            }
            blocks.addFirst(first.copy(start = firstRetained, payload = payload))
        }
    }

    fun window(endSample: Long, count: Int): Window {
        val start = endSample - count
        fun unavailable(reason: String, route: Long? = null, opened: Int = 0) =
            Window(true, false, reason, route, opened)
        if (count <= 0 || count > capacity || start < 0 || endSample > end || start < end - capacity)
            return unavailable("CHANNEL_HISTORY_UNAVAILABLE")
        val relevant = blocks.filter { it.end > start && it.start < endSample }
        var position = start
        for (block in relevant) {
            if (block.start > position) return unavailable("CHANNEL_HISTORY_GAP")
            position = minOf(endSample, block.end)
        }
        if (position != endSample) return unavailable("CHANNEL_HISTORY_GAP")
        if (relevant.any { it.invalid }) return unavailable("INVALID_CHANNEL_PAYLOAD")
        val explicit = relevant.mapNotNull { it.payload }
        val firstExplicit = explicit.firstOrNull()
        // An explicit physical-route boundary also invalidates a mono window.
        // Legacy all-null producers still have no additional admission rule.
        if (firstExplicit != null && explicit.any { it.routeRevision != firstExplicit.routeRevision })
            return unavailable("CHANNEL_ROUTE_BOUNDARY", firstExplicit.routeRevision, firstExplicit.openedChannels)
        if (relevant.none { it.multichannelExpected })
            return Window(false, true, "MONO_COMPATIBILITY", relevant.firstOrNull()?.payload?.routeRevision, 1)
        if (relevant.any { it.payload == null }) return unavailable("MISSING_CHANNEL_PAYLOAD")
        val first = checkNotNull(relevant.first().payload)
        val route = first.routeRevision
        val opened = first.openedChannels
        if (relevant.any { it.payload?.routeRevision != route })
            return unavailable("CHANNEL_ROUTE_BOUNDARY", route, opened)
        if (opened <= 1 || relevant.any { it.payload?.openedChannels != opened })
            return unavailable("CHANNEL_COUNT_BOUNDARY", route, opened)
        val indices = relevant.flatMap { checkNotNull(it.payload).channels.map { channel -> channel.index } }.toSet()
        val candidates = indices.mapNotNull { index ->
            val pcm = FloatArray(count)
            var written = 0; var contributed = 0; var usable = 0
            for (block in relevant) {
                val payload = checkNotNull(block.payload)
                val channel = payload.channels.firstOrNull { it.index == index }
                // Producer omission before first nonzero is an explicit zero
                // channel. Omission after appearance is a loss, never invented PCM.
                if (channel == null && index in block.knownBefore) return@mapNotNull null
                val from = maxOf(start, block.start)
                val to = minOf(endSample, block.end)
                val length = (to - from).toInt()
                channel?.samples?.copyInto(pcm, written, (from - block.start).toInt(), (to - block.start).toInt())
                written += length
                if (payload.selectedChannel == index) contributed += length
                if (channel?.usable == true) usable += length
            }
            if (usable == 0) null else Channel(index, pcm, contributed, usable)
        }.sortedWith(compareByDescending<Channel> { it.contributedSamples }.thenByDescending { it.usableSamples }.thenBy { it.index })
        if (candidates.isEmpty()) return unavailable("NO_COHERENT_CHANNEL", route, opened)
        return Window(true, true, "COHERENT_ORIGINAL_CHANNELS", route, opened, candidates)
    }

    fun reset() {
        blocks.clear()
        end = 0L
        // A sequence gap says nothing about how many microphones remain open.
        // Preserve the obligation until a valid explicit mono payload replaces it.
    }
}
