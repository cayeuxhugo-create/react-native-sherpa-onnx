package expo.modules.guardiannativeprotection

/** Capture epochs must survive the asynchronous GTCRN join and its timeout path. */
internal object CaptureChannelRevisionTest {
    @JvmStatic
    fun main(args: Array<String>) {
        delayedEnhancementRetainsSourceRevision()
        timeoutAndLateEnhancementRetainSourceRevision()
        println("PASS 2 capture channel revision routing tests")
    }

    private fun input(sequence: Long, revision: Long, queuedAtMs: Long = 0L) =
        GuardianVoiceInputRouter.Input(
            key = GuardianVoiceInputRouter.Key(sequence, sequence * 50_000_000L),
            pcm = floatArrayOf(0.1f, -0.1f),
            gain = 1f,
            enhancementExpected = true,
            queuedAtMs = queuedAtMs,
            captureChannelRevision = revision,
        )

    private fun delayedEnhancementRetainsSourceRevision() {
        val emitted = mutableListOf<Pair<Long, Long>>()
        val router = GuardianVoiceInputRouter(
            noisy = true,
            emit = { frame, _, enhanced ->
                check(enhanced)
                emitted += frame.key.sequence to frame.captureChannelRevision
            },
            warning = { error(it) },
        )
        val oldChannel = input(1L, 7L)
        val newChannel = input(2L, 8L)
        router.primary(oldChannel, 0L)
        router.primary(newChannel, 50L)
        router.enhanced(newChannel.key, floatArrayOf(0.2f, -0.2f), 60L)
        check(emitted.isEmpty())
        router.enhanced(oldChannel.key, floatArrayOf(0.3f, -0.3f), 70L)
        check(emitted == listOf(1L to 7L, 2L to 8L))
    }

    private fun timeoutAndLateEnhancementRetainSourceRevision() {
        val emitted = mutableListOf<Triple<Long, Long, Boolean>>()
        val warnings = mutableListOf<String>()
        val router = GuardianVoiceInputRouter(
            noisy = true,
            emit = { frame, _, enhanced ->
                emitted += Triple(frame.key.sequence, frame.captureChannelRevision, enhanced)
            },
            warning = { warnings += it },
        )
        val oldChannel = input(1L, 9L)
        val newChannel = input(2L, 10L, 150L)
        router.primary(oldChannel, 0L)
        router.primary(newChannel, 150L)
        router.drain(200L)
        router.enhanced(oldChannel.key, floatArrayOf(0.3f, -0.3f), 210L)
        router.enhanced(newChannel.key, floatArrayOf(0.2f, -0.2f), 220L)
        check(emitted == listOf(Triple(1L, 9L, false), Triple(2L, 10L, true)))
        check(warnings.size == 1)
    }
}
