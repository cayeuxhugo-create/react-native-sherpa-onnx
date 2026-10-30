package expo.modules.guardiannativeprotection

/** Clock/ownership tests use a delayed identity backend, never a fake VAD. */
internal object TemporalConfirmationViewTest {
    private class Backend(private val delayHops: Int = 0) : GuardianTemporalConfirmationView.Backend {
        override val frameShiftInSamples = 256
        val fed = mutableListOf<Float>()
        private val delayed = java.util.ArrayDeque<FloatArray>()
        var resets = 0
        var closes = 0
        var runs = 0
        var nonfiniteNext = false
        var failNext = false
        override fun run(pcm: FloatArray): FloatArray {
            runs++
            check(pcm.size == frameShiftInSamples)
            if (failNext) { failNext = false; error("inference failed") }
            if (nonfiniteNext) { nonfiniteNext = false; return floatArrayOf(Float.NaN) }
            fed.addAll(pcm.toList())
            delayed.addLast(pcm.copyOf())
            return if (delayed.size > delayHops) delayed.removeFirst() else floatArrayOf()
        }
        override fun reset() { resets++; delayed.clear() }
        override fun close() { closes++; delayed.clear() }
    }

    @JvmStatic fun main(args: Array<String>) {
        continuousUnevenInputAndDelayedOutput()
        omittedInitialZerosKeepNativeEpoch()
        missingKnownSourceCannotBecomeSilence()
        routeSelectionAndCountChangesStartNewEpoch()
        invalidAndMixedPayloadsCannotSupplyConfirmation()
        warmupTailBoundsAndRetentionAreStrict()
        nativeErrorsAndNonfiniteOutputInvalidateRetainedAudio()
        ownedWindowsResetAndCloseHaveNoFlush()
        println("PASS 8 temporal confirmation view tests")
    }

    private fun audio(count: Int, offset: Int = 0) =
        FloatArray(count) { ((it + offset) % 997 - 498) / 2000f }

    private fun capture(pcm: FloatArray, route: Long = 1L, selected: Int = 0,
        opened: Int = 4, includeSelected: Boolean = true, includeReference: Boolean = true): GuardianCaptureChannelFrame {
        val alternate = if (selected == 0) 1 else 0
        return GuardianCaptureChannelFrame(route, opened, selected, buildList {
            if (includeSelected) add(GuardianCaptureChannelFrame.Channel(selected, pcm.copyOf(), true))
            if (includeReference) add(GuardianCaptureChannelFrame.Channel(alternate, FloatArray(pcm.size) { .01f }, true))
        })
    }

    private fun feed(view: GuardianTemporalConfirmationView, pcm: FloatArray,
        route: Long = 1L, selected: Int = 0, opened: Int = 4) {
        var offset = 0
        val sizes = intArrayOf(113, 800, 7, 2048, 301)
        var index = 0
        while (offset < pcm.size) {
            val end = minOf(pcm.size, offset + sizes[index++ % sizes.size])
            val block = pcm.copyOfRange(offset, end)
            view.accept(block, capture(block, route, selected, opened))
            offset = end
        }
    }

    private fun continuousUnevenInputAndDelayedOutput() {
        val backend = Backend(2)
        GuardianTemporalConfirmationView(backend).use { view ->
            val x = audio(40_123)
            feed(view, x)
            val window = checkNotNull(view.window(16_000, 39_000, 0, 1L))
            check(window.pcm.contentEquals(x.copyOfRange(16_000, 39_000)))
            check(window.sourceStartSample == 16_000L && window.sourceEndSample == 39_000L)
            check(window.primary == 0 && window.routeRevision == 1L)
            check(backend.fed.toFloatArray().contentEquals(x.copyOfRange(0, x.size / 256 * 256)))
            check(view.window(39_000, 40_000, 0, 1L) == null) { "Unproduced native tail must stay unavailable" }
            check(backend.resets == 0)
        }
    }

    private fun omittedInitialZerosKeepNativeEpoch() {
        val backend = Backend()
        GuardianTemporalConfirmationView(backend).use { view ->
            val prefix = FloatArray(20_000)
            view.accept(prefix, GuardianCaptureChannelFrame(1L, 4, 0, emptyList()))
            val speech = audio(12_000)
            feed(view, speech)
            val window = checkNotNull(view.window(16_000, 30_000, 0, 1L))
            check(window.pcm.contentEquals((prefix + speech).copyOfRange(16_000, 30_000)))
            check(backend.resets == 0) { "First nonzero source must retain genuine prefix state" }
        }
    }

    private fun missingKnownSourceCannotBecomeSilence() {
        val backend = Backend()
        GuardianTemporalConfirmationView(backend).use { view ->
            feed(view, audio(32_000))
            check(view.window(16_000, 20_000, 0, 1L) != null)
            val zero = FloatArray(800)
            view.accept(zero, capture(zero, includeSelected = false))
            check(view.window(16_000, 20_000, 0, 1L) == null)
            check(backend.resets == 1)
            feed(view, audio(32_000))
            val block = audio(800)
            view.accept(block, capture(block, includeReference = false))
            check(view.window(49_000, 50_000, 0, 1L) == null)
            check(backend.resets == 2)
        }
    }

    private fun routeSelectionAndCountChangesStartNewEpoch() {
        val backend = Backend()
        GuardianTemporalConfirmationView(backend).use { view ->
            feed(view, audio(32_000))
            feed(view, audio(32_000), route = 2L)
            check(view.window(31_000, 50_000, 0, 2L) == null)
            check(view.window(48_000, 60_000, 0, 1L) == null)
            check(view.window(48_000, 60_000, 0, 2L) != null)
            feed(view, audio(32_000), route = 2L, selected = 1)
            check(view.window(80_000, 90_000, 0, 2L) == null)
            check(view.window(80_000, 90_000, 1, 2L) != null)
            feed(view, audio(32_000), route = 2L, selected = 1, opened = 2)
            check(view.window(95_000, 120_000, 1, 2L) == null)
            check(view.window(112_000, 120_000, 1, 2L) != null)
            check(backend.resets == 3)
        }
    }

    private fun invalidAndMixedPayloadsCannotSupplyConfirmation() {
        GuardianTemporalConfirmationView(Backend()).use { view ->
            feed(view, audio(32_000))
            val pcm = audio(800)
            view.accept(pcm, capture(FloatArray(800)))
            check(view.window(16_000, 20_000, 0, 1L) == null)
            feed(view, audio(32_000))
            view.accept(pcm, null)
            check(view.window(49_000, 50_000, 0, 1L) == null)
            feed(view, audio(32_000))
            val malformed = capture(pcm).copy(channels = listOf(
                GuardianCaptureChannelFrame.Channel(0, FloatArray(799), true)))
            view.accept(pcm, malformed)
            check(view.window(81_000, 82_000, 0, 1L) == null)
            feed(view, audio(32_000))
            val bad = pcm.copyOf().apply { this[0] = Float.POSITIVE_INFINITY }
            view.accept(bad, capture(bad))
            check(view.window(113_000, 114_000, 0, 1L) == null)
        }
    }

    private fun warmupTailBoundsAndRetentionAreStrict() {
        GuardianTemporalConfirmationView(Backend()).use { view ->
            feed(view, audio(32_000))
            check(view.window(15_999, 20_000, 0, 1L) == null)
            check(view.window(16_000, 20_000, 0, 1L) != null)
            check(view.window(16_000, 20_000, 0, null) == null)
            check(view.window(20_000, 20_000, 0, 1L) == null)
            check(view.window(20_000, 19_999, 0, 1L) == null)
            check(view.window(-1, 20_000, 0, 1L) == null)
            feed(view, audio(320_000))
            check(view.window(16_000, 20_000, 0, 1L) == null) { "Retired ring samples must not be reused" }
            check(view.window(100_000, 228_001, 0, 1L) == null) { "Eight-second bound must be retained" }
            check(view.window(100_000, 228_000, 0, 1L) != null)
        }
    }

    private fun nativeErrorsAndNonfiniteOutputInvalidateRetainedAudio() {
        for (nonfinite in listOf(true, false)) {
            val backend = Backend()
            GuardianTemporalConfirmationView(backend).use { view ->
                feed(view, audio(32_000))
                backend.nonfiniteNext = nonfinite
                backend.failNext = !nonfinite
                val pcm = audio(800)
                check(runCatching { view.accept(pcm, capture(pcm)) }.isFailure)
                check(view.window(16_000, 20_000, 0, 1L) == null)
                check(backend.resets == 1)
            }
        }
    }

    private fun ownedWindowsResetAndCloseHaveNoFlush() {
        val backend = Backend()
        val view = GuardianTemporalConfirmationView(backend)
        feed(view, audio(32_000))
        val first = checkNotNull(view.window(16_000, 20_000, 0, 1L))
        val expected = first.pcm.copyOf()
        first.pcm.fill(99f)
        check(checkNotNull(view.window(16_000, 20_000, 0, 1L)).pcm.contentEquals(expected))
        view.reset()
        check(view.window(16_000, 20_000, 0, 1L) == null)
        feed(view, audio(32_000))
        check(view.window(16_000, 20_000, 0, 1L) != null)
        val runs = backend.runs
        view.close(); view.close()
        check(backend.closes == 1 && backend.runs == runs)
        check(view.window(16_000, 20_000, 0, 1L) == null)
        check(runCatching { view.accept(audio(800), capture(audio(800))) }.isFailure)
    }
}
