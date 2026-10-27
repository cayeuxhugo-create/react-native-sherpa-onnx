package expo.modules.guardiannativeprotection

/** Pure host checks for exact, continuous original-channel admission windows. */
internal object ChannelWindowHistoryTest {
    @JvmStatic
    fun main(args: Array<String>) {
        legacyMonoRemainsCompatible()
        exactCutsPreserveChannelsAcrossSelectionChanges()
        silenceDoesNotBreakAnOtherwiseUsefulChannel()
        omittedChannelsCannotBeSpliced()
        initiallyAbsentChannelsHaveKnownZeroHistory()
        routeBoundariesAreNeverCrossed()
        explicitMonoRouteBoundariesAreNeverCrossed()
        missingMultichannelPayloadFailsClosed()
        resetPreservesTheCaptureContext()
        explicitMonoCanEndTheMultichannelContext()
        gapsAndOverlapsAreDetected()
        capacityTrimsPartialBlocks()
        allThirtyOpenedChannelsRemainBounded()
        payloadCopiesOwnTheirArrays()
        println("PASS 14 original-channel history checks: exact cuts, continuous channels, sparse startup, mono/multichannel route boundaries, missing payload, reset, 30-channel bounded retention and cloning")
    }

    private fun frame(
        route: Long = 1L,
        selected: Int = 0,
        opened: Int = 3,
        vararg channels: GuardianCaptureChannelFrame.Channel,
    ) = GuardianCaptureChannelFrame(route, opened, selected, channels.toList())

    private fun channel(index: Int, vararg values: Float, usable: Boolean = true) =
        GuardianCaptureChannelFrame.Channel(index, values, usable)

    private fun reject(window: GuardianChannelWindowHistory.Window) {
        check(window.required && !window.valid) { "A required original-channel guard unexpectedly passed" }
        check(window.reason.isNotBlank()) { "A rejected window needs a diagnostic reason" }
    }

    private fun legacyMonoRemainsCompatible() {
        val history = GuardianChannelWindowHistory()
        history.append(4, null)
        history.append(4, null)
        val window = history.window(7, 5)
        check(window.valid && !window.required && window.channels.isEmpty())
        check(history.end == 8L)
    }

    private fun exactCutsPreserveChannelsAcrossSelectionChanges() {
        val history = GuardianChannelWindowHistory()
        history.append(4, frame(selected = 0,
            channels = arrayOf(channel(0, 0f, 1f, 2f, 3f), channel(1, 10f, 11f, 12f, 13f))))
        history.append(4, frame(selected = 1,
            channels = arrayOf(channel(0, 4f, 5f, 6f, 7f), channel(1, 14f, 15f, 16f, 17f))))
        val window = history.window(7, 5)
        check(window.required && window.valid && window.routeRevision == 1L && window.openedChannels == 3)
        check(window.channels.map { it.index } == listOf(1, 0)) { "Prioritize contributors over the exact requested interval" }
        val byIndex = window.channels.associateBy { it.index }
        check(byIndex.getValue(0).pcm.contentEquals(floatArrayOf(2f, 3f, 4f, 5f, 6f)))
        check(byIndex.getValue(1).pcm.contentEquals(floatArrayOf(12f, 13f, 14f, 15f, 16f)))
        check(byIndex.getValue(0).contributedSamples == 2 && byIndex.getValue(1).contributedSamples == 3)
        check(window.channels.all { it.usableSamples == 5 })
        // A second extraction must not inherit changes to the first result.
        byIndex.getValue(1).pcm.fill(Float.NaN)
        check(history.window(7, 5).channels.all { c -> c.pcm.all { it.isFinite() } })
    }

    private fun silenceDoesNotBreakAnOtherwiseUsefulChannel() {
        val history = GuardianChannelWindowHistory()
        history.append(3, frame(channels = arrayOf(
            channel(0, 1f, 2f, 3f), channel(1, 0f, 0f, 0f, usable = false))))
        history.append(3, frame(channels = arrayOf(
            channel(0, 0f, 0f, 0f, usable = false), channel(1, 0f, 0f, 0f, usable = false))))
        val window = history.window(6, 6)
        check(window.required && window.valid)
        check(window.channels.map { it.index } == listOf(0)) { "Never-useful zero placeholders must remain ineligible" }
        check(window.channels.single().pcm.contentEquals(floatArrayOf(1f, 2f, 3f, 0f, 0f, 0f)))
        check(window.channels.single().usableSamples == 3)
        reject(history.window(6, 3))
    }

    private fun omittedChannelsCannotBeSpliced() {
        val history = GuardianChannelWindowHistory()
        history.append(3, frame(channels = arrayOf(channel(0, 1f, 2f, 3f), channel(1, 11f, 12f, 13f))))
        history.append(3, frame(selected = 1, channels = arrayOf(channel(1, 14f, 15f, 16f))))
        val window = history.window(6, 6)
        check(window.valid && window.channels.map { it.index } == listOf(1))
        check(window.channels.single().pcm.contentEquals(floatArrayOf(11f, 12f, 13f, 14f, 15f, 16f)))

        val disconnected = GuardianChannelWindowHistory()
        disconnected.append(3, frame(channels = arrayOf(channel(0, 1f, 2f, 3f))))
        disconnected.append(3, frame(channels = emptyArray()))
        reject(disconnected.window(6, 6))
    }

    private fun initiallyAbsentChannelsHaveKnownZeroHistory() {
        val history = GuardianChannelWindowHistory()
        history.append(3, frame(channels = emptyArray()))
        history.append(3, frame(channels = arrayOf(channel(0, 1f, 2f, 3f))))
        val window = history.window(6, 6)
        check(window.required && window.valid && window.channels.map { it.index } == listOf(0))
        check(window.channels.single().pcm.contentEquals(floatArrayOf(0f, 0f, 0f, 1f, 2f, 3f)))
        check(window.channels.single().usableSamples == 3)

        // A physical channel first becoming active is coherent even when the
        // selected stream previously came from another channel.
        val switched = GuardianChannelWindowHistory()
        switched.append(3, frame(channels = arrayOf(channel(0, 4f, 5f, 6f))))
        switched.append(3, frame(selected = 1, channels = arrayOf(channel(1, 7f, 8f, 9f))))
        val other = switched.window(6, 6)
        check(other.valid && other.channels.map { it.index } == listOf(1))
        check(other.channels.single().pcm.contentEquals(floatArrayOf(0f, 0f, 0f, 7f, 8f, 9f)))
    }

    private fun routeBoundariesAreNeverCrossed() {
        val history = GuardianChannelWindowHistory()
        history.append(4, frame(route = 1, channels = arrayOf(channel(0, 0f, 1f, 2f, 3f))))
        history.append(4, frame(route = 2, channels = arrayOf(channel(0, 4f, 5f, 6f, 7f))))
        check(history.window(4, 4).valid && history.window(4, 4).routeRevision == 1L)
        check(history.window(8, 4).valid && history.window(8, 4).routeRevision == 2L)
        reject(history.window(5, 2))
    }

    private fun missingMultichannelPayloadFailsClosed() {
        val history = GuardianChannelWindowHistory()
        history.append(3, frame(channels = arrayOf(channel(0, 1f, 2f, 3f))))
        history.append(3, null)
        reject(history.window(6, 6))
        reject(history.window(6, 3))
        history.append(3, frame(channels = arrayOf(channel(0, 7f, 8f, 9f))))
        check(history.window(9, 3).valid)
        reject(history.window(9, 6))
    }

    private fun explicitMonoRouteBoundariesAreNeverCrossed() {
        val history = GuardianChannelWindowHistory()
        history.append(3, frame(route = 1, opened = 1, channels = arrayOf(channel(0, 1f, 2f, 3f))))
        history.append(3, frame(route = 1, opened = 1, channels = arrayOf(channel(0, 4f, 5f, 6f))))
        check(history.window(6, 6).let { it.valid && !it.required && it.routeRevision == 1L })
        history.append(3, frame(route = 2, opened = 1, channels = arrayOf(channel(0, 7f, 8f, 9f))))
        val crossed = history.window(9, 9)
        reject(crossed)
        check(crossed.reason == "CHANNEL_ROUTE_BOUNDARY")
        check(history.window(6, 3).let { it.valid && !it.required && it.routeRevision == 1L })
        check(history.window(9, 3).let { it.valid && !it.required && it.routeRevision == 2L })

        // Missing legacy metadata between two explicit routes does not erase
        // their known boundary or manufacture a route for all-null producers.
        val mixed = GuardianChannelWindowHistory()
        mixed.append(3, frame(route = 1, opened = 1, channels = emptyArray()))
        mixed.append(3, null)
        mixed.append(3, frame(route = 2, opened = 1, channels = emptyArray()))
        reject(mixed.window(9, 9))
        check(mixed.window(6, 3).let { it.valid && !it.required })
    }

    private fun resetPreservesTheCaptureContext() {
        val history = GuardianChannelWindowHistory()
        history.append(3, frame(channels = arrayOf(channel(0, 1f, 2f, 3f))))
        history.reset()
        check(history.end == 0L)
        history.append(3, null)
        reject(history.window(3, 3))
        history.append(3, frame(route = 2, channels = arrayOf(channel(0, 4f, 5f, 6f))))
        check(history.window(6, 3).valid && history.window(6, 3).routeRevision == 2L)

        // Resetting segmentation does not make a lost channel's earlier
        // existence disappear. An omitted previously seen index is a gap.
        history.reset()
        history.append(3, frame(route = 2, channels = emptyArray()))
        history.append(3, frame(route = 2, channels = arrayOf(channel(0, 7f, 8f, 9f))))
        reject(history.window(6, 6))
        check(history.window(6, 3).valid)
    }

    private fun explicitMonoCanEndTheMultichannelContext() {
        val history = GuardianChannelWindowHistory()
        history.append(3, frame(channels = arrayOf(channel(0, 1f, 2f, 3f))))
        history.append(3, frame(route = 2, opened = 1, channels = arrayOf(channel(0, 4f, 5f, 6f))))
        reject(history.window(6, 6))
        val mono = history.window(6, 3)
        check(mono.valid && !mono.required)
        history.append(3, null)
        check(history.window(9, 3).let { it.valid && !it.required })
    }

    private fun gapsAndOverlapsAreDetected() {
        val history = GuardianChannelWindowHistory()
        history.append(3, frame(channels = arrayOf(channel(0, 1f, 2f, 3f))))
        history.append(3, frame(channels = arrayOf(channel(0, 7f, 8f, 9f))), startSample = 6)
        check(history.end == 9L)
        reject(history.window(9, 9))
        check(history.window(9, 3).valid)
        check(runCatching {
            history.append(1, frame(channels = arrayOf(channel(0, 1f))), startSample = 8)
        }.isFailure) { "Overlapping source intervals must not overwrite existing history" }
    }

    private fun capacityTrimsPartialBlocks() {
        val history = GuardianChannelWindowHistory(capacity = 7)
        history.append(4, frame(channels = arrayOf(channel(0, 0f, 1f, 2f, 3f))))
        history.append(4, frame(channels = arrayOf(channel(0, 4f, 5f, 6f, 7f))))
        val retained = history.window(8, 7)
        check(retained.valid && retained.channels.single().pcm.contentEquals(floatArrayOf(1f, 2f, 3f, 4f, 5f, 6f, 7f)))
        reject(history.window(8, 8))
        reject(history.window(1, 1))
        reject(history.window(9, 1))
        check(history.window(8, 1).channels.single().pcm.contentEquals(floatArrayOf(7f)))
        repeat(20) { n -> history.append(1, frame(channels = arrayOf(channel(0, (8 + n).toFloat())))) }
        check(history.end == 28L)
        check(history.window(28, 7).channels.single().pcm.contentEquals(FloatArray(7) { (21 + it).toFloat() }))
        reject(history.window(21, 1))
    }

    private fun payloadCopiesOwnTheirArrays() {
        val original = frame(channels = arrayOf(channel(0, 1f, 2f, 3f), channel(1, 4f, 5f, 6f)))
        val owned = original.copyOwned()
        check(owned.channels.zip(original.channels).all { (copy, source) -> copy.samples !== source.samples })
        original.channels.forEach { it.samples.fill(Float.NaN) }
        owned.validate(3)
        check(owned.channels[0].samples.contentEquals(floatArrayOf(1f, 2f, 3f)))
        check(owned.channels[1].samples.contentEquals(floatArrayOf(4f, 5f, 6f)))
        val history = GuardianChannelWindowHistory()
        history.append(3, owned)
        check(history.window(3, 3).valid)
    }

    private fun allThirtyOpenedChannelsRemainBounded() {
        val history = GuardianChannelWindowHistory(capacity = 5)
        fun payload(start: Int, selected: Int) = frame(opened = 30, selected = selected,
            channels = Array(30) { index -> GuardianCaptureChannelFrame.Channel(index,
                FloatArray(3) { (index * 100 + start + it).toFloat() }, true) })
        history.append(3, payload(0, 29))
        history.append(3, payload(3, 28))
        val window = history.window(6, 5)
        check(window.valid && window.openedChannels == 30 && window.channels.size == 30)
        check(window.channels.take(2).map { it.index } == listOf(28, 29))
        check(window.channels.first { it.index == 29 }.pcm.contentEquals(FloatArray(5) { (2901 + it).toFloat() }))
        reject(history.window(6, 6))
    }
}
