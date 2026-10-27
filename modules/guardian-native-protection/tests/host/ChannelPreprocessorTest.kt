package expo.modules.guardiannativeprotection

/** Source-channel histories must stay coherent while capture selection and queues advance. */
internal object ChannelPreprocessorTest {
    @JvmStatic
    fun main(args: Array<String>) {
        continuousPrimaryParityAcrossUnevenBlocksAndSelectionChanges()
        routeChangeStartsFreshHistories()
        temporarySilenceKeepsObservedChannelHistoryAndClock()
        queuedSnapshotsAndOwnedCopiesDoNotAlias()
        println("PASS 4 channel preprocessor tests")
    }

    private fun continuousPrimaryParityAcrossUnevenBlocksAndSelectionChanges() {
        val sources = listOf(
            ShortArray(97) { index ->
                when (index % 7) {
                    0 -> Short.MAX_VALUE
                    1 -> Short.MIN_VALUE
                    2 -> 0
                    else -> (index * 419 - 15000).toShort()
                }
            },
            ShortArray(97) { index -> if (index < 40) 1200 else (-1600 + index * 13).toShort() },
            ShortArray(97) { index -> if (index % 9 == 0) 9000 else -40 },
        )
        val expected = sources.map(::primaryHighPass)
        val captured = List(sources.size) { mutableListOf<Float>() }
        val processor = GuardianChannelPreprocessor(sources.size)
        val chunkSizes = listOf(1, 3, 17, 2, 23, 5, 31, 15)
        var offset = 0
        chunkSizes.forEachIndexed { block, count ->
            val selected = block % sources.size
            val result = processor.process(
                interleavedPcm = interleave(sources, offset, count),
                interleavedSampleCount = count * sources.size,
                routeRevision = 42L,
                selectedChannel = selected,
                usable = List(sources.size) { true },
            )
            result.validate(count)
            check(result.routeRevision == 42L && result.openedChannels == sources.size)
            check(result.selectedChannel == selected)
            sources.indices.forEach { channel ->
                captured[channel].addAll(result.channel(channel).samples.toList())
            }
            offset += count
        }
        check(offset == sources.first().size)
        sources.indices.forEach { channel ->
            checkSamples(captured[channel].toFloatArray(), expected[channel], "continuous source $channel")
        }
        check(expected.first().any { it == -1f || it == 1f }) { "Fixture must exercise output clamping" }
    }

    private fun routeChangeStartsFreshHistories() {
        val processor = GuardianChannelPreprocessor(2)
        processor.process(
            interleave(listOf(shortArrayOf(24000, 12000), shortArrayOf(-7000, 19000)), 0, 2),
            4, 4L, 0, listOf(true, true),
        )
        val next = interleave(listOf(shortArrayOf(200, 300, 400), shortArrayOf(-900, 0, 1800)), 0, 3)
        val sameRoute = processor.process(next, next.size, 4L, 1, listOf(true, true))
        val resetRoute = processor.process(next, next.size, 5L, 1, listOf(true, true))
        val fresh = GuardianChannelPreprocessor(2).process(next, next.size, 5L, 1, listOf(true, true))
        check(sameRoute.routeRevision == 4L && resetRoute.routeRevision == 5L)
        check(!sameRoute.channel(0).samples.contentEquals(fresh.channel(0).samples)) {
            "Selection change unexpectedly discarded source history"
        }
        for (channel in 0..1) {
            checkSamples(resetRoute.channel(channel).samples, fresh.channel(channel).samples, "route reset $channel")
        }
    }

    private fun temporarySilenceKeepsObservedChannelHistoryAndClock() {
        val processor = GuardianChannelPreprocessor(4)
        val source0 = shortArrayOf(12000, 8000, 4000, 0, 0, 0, 0, 0, 0, -7000, -3000, 0)
        val source2 = shortArrayOf(0, 0, 0, 0, 10000, 9000, 0, 0, 0, 0, 0, 0)
        val sources = listOf(source0, ShortArray(12), source2, ShortArray(12))
        val expected = sources.map(::primaryHighPass)
        val blocks = listOf(0 to 4, 4 to 3, 7 to 2, 9 to 3)
        blocks.forEachIndexed { block, (offset, count) ->
            val usable = listOf(block == 0 || block == 3, false, block == 1, false)
            val result = processor.process(
                interleave(sources, offset, count), count * 4, 8L,
                if (block == 1) 2 else 0, usable,
            )
            result.validate(count)
            checkSamples(result.channel(0).samples, expected[0].copyOfRange(offset, offset + count), "source 0 block $block")
            check(result.channel(0).usable == usable[0])
            if (block >= 1) {
                checkSamples(result.channel(2).samples, expected[2].copyOfRange(offset, offset + count), "source 2 block $block")
                check(result.channel(2).usable == usable[2])
            }
            result.channels.filter { it.index == 1 || it.index == 3 }.forEach {
                check(it.samples.all { sample -> sample == 0f })
            }
            if (block == 2) {
                check(result.channel(0).samples.any { it != 0f }) { "Temporary raw silence erased HP decay" }
                check(result.channel(2).samples.any { it != 0f }) { "Inactive source lost its continuous clock" }
            }
        }
    }

    private fun queuedSnapshotsAndOwnedCopiesDoNotAlias() {
        val processor = GuardianChannelPreprocessor(2)
        val input = shortArrayOf(1000, -2000, 3000, -4000, 5000, -6000)
        val queued = processor.process(input, input.size, 11L, 0, listOf(true, true))
        val before = queued.channels.associate { it.index to it.samples.copyOf() }
        input.fill(0)
        repeat(20) { block ->
            val following = ShortArray(18) { ((block + 1) * 200 + it).toShort() }
            processor.process(following, following.size, if (block < 10) 11L else 12L, block % 2, listOf(true, true))
        }
        check(queued.routeRevision == 11L && queued.selectedChannel == 0)
        queued.channels.forEach { checkSamples(it.samples, before.getValue(it.index), "queued source ${it.index}") }

        val owned = queued.copyOwned()
        queued.channel(0).samples.fill(0.8f)
        checkSamples(owned.channel(0).samples, before.getValue(0), "asynchronous owned copy")
        checkSamples(queued.channel(1).samples, before.getValue(1), "independent source allocation")
    }

    private fun GuardianCaptureChannelFrame.channel(index: Int) =
        channels.singleOrNull { it.index == index } ?: error("Observed source $index missing from capture payload")

    private fun interleave(sources: List<ShortArray>, offset: Int, count: Int) =
        ShortArray(count * sources.size) { at -> sources[at % sources.size][offset + at / sources.size] }

    /** Same arithmetic/order and unclamped history as preprocessMonoPcm(), independent per source. */
    private fun primaryHighPass(source: ShortArray): FloatArray {
        var previousInput = 0f
        var previousOutput = 0f
        return FloatArray(source.size) { index ->
            val input = source[index].toFloat() / 32768f
            val filtered = 0.97f * (previousOutput + input - previousInput)
            previousInput = input
            previousOutput = filtered
            filtered.coerceIn(-1f, 1f)
        }
    }

    private fun checkSamples(actual: FloatArray, expected: FloatArray, context: String) {
        check(actual.contentEquals(expected)) {
            val mismatch = actual.indices.firstOrNull { it >= expected.size || actual[it] != expected[it] }
            "$context differs at $mismatch (actual=${actual.size}, expected=${expected.size})"
        }
    }
}
