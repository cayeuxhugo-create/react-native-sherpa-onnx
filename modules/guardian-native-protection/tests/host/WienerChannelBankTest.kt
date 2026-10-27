package expo.modules.guardiannativeprotection

import java.util.Random

fun wienerChannelBankTests() {
    val random = Random(4107)
    val sources = List(2) { channel -> FloatArray(128_000) { (random.nextGaussian() * (.02 + channel * .015)).toFloat() } }
    fun payload(start: Int, end: Int, route: Long = 7, selected: Int = 0, includeSecond: Boolean = true,
                usable: Boolean = true) = GuardianCaptureChannelFrame(route, 2, selected,
        (if (includeSecond) 0..1 else 0..0).map { channel ->
            GuardianCaptureChannelFrame.Channel(channel, sources[channel].copyOfRange(start, end), usable)
        })
    val bank = GuardianWienerChannelBank()
    val expected = List(2) { GuardianWienerEnhancer() }
    val emitted = List(2) { ArrayList<Float>() }
    for (start in 0 until 96_000 step 800) {
        val outputs = bank.process(payload(start, start + 800, selected = (start / 800) % 2,
            usable = start % 3200 != 0), 800, false)
        for (output in outputs) {
            val channel = output.channelIndex
            check(output.originSample == 0L)
            check(output.outputStart == emitted[channel].size.toLong())
            emitted[channel].addAll(output.filtered.toList())
            val reference = expected[channel].process(sources[channel].copyOfRange(start, start + 800))
            check(output.filtered.contentEquals(reference)) { "Selection or quality flag reset channel state" }
        }
    }
    check(bank.resetCount == 0)
    check(bank.canFinalize(52_000, 80_000))
    for (channel in 0..1) {
        val window = checkNotNull(bank.window(channel, 52_000, 80_000))
        check(!window.status.startupBypass)
        check(window.truth.contentEquals(sources[channel].copyOfRange(52_000, 80_000)))
        check(window.filtered.contentEquals(emitted[channel].subList(52_000, 80_000).toFloatArray()))
    }
    println("PASS physical-channel Wiener states survive selected-channel and transient quality changes")

    val oldEnd = emitted[0].size
    val switched = bank.process(payload(96_000, 96_800, route = 8), 800, false)
    for (channel in 0..1) {
        val tail = switched.first { it.channelIndex == channel && it.truth.isEmpty() }
        check(tail.outputStart == oldEnd.toLong())
        check(tail.filtered.contentEquals(sources[channel].copyOfRange(oldEnd, 96_000)))
        check(checkNotNull(bank.window(channel, 80_000, 96_000)).status.reason == "ABANDONED_FILTER_TAIL")
        check(checkNotNull(bank.window(channel, 90_000, 96_500)).status.startupBypass)
    }
    check(bank.resetCount == 2)
    println("PASS true route changes reset every channel and preserve real original tails with whole-window guards")

    val late = GuardianWienerChannelBank()
    for (start in 0 until 8_000 step 800) late.process(payload(start, start + 800, includeSecond = false), 800, false)
    val first = late.process(payload(8_000, 8_800), 800, false).first { it.channelIndex == 1 }
    check(first.originSample == 8_000L && first.outputStart == 8_000L)
    check(late.window(1, 0, 8_500) == null)
    check(checkNotNull(late.window(1, 8_000, 8_500)).truth.contentEquals(sources[1].copyOfRange(8_000, 8_500)))
    late.process(payload(8_800, 9_600, includeSecond = false), 800, false)
    val returned = late.process(payload(9_600, 10_400), 800, false).first { it.channelIndex == 1 }
    check(returned.originSample == 9_600L)
    check(late.window(1, 8_000, 10_000) == null) { "Missing channel samples were invented" }
    late.process(null, 800, false)
    val afterMissingPayload = late.process(payload(11_200, 12_000), 800, false)
    check(afterMissingPayload.all { it.originSample == 11_200L })
    check(late.window(0, 9_600, 11_700) == null) { "Missing multichannel payload was replaced by primary PCM" }
    val resumedSilence = late.process(GuardianCaptureChannelFrame(7, 2, 0,
        listOf(GuardianCaptureChannelFrame.Channel(0, FloatArray(800), false))), 800, false)
    check(resumedSilence.any { it.channelIndex == 0 && it.truth.size == 800 })
    late.process(null, 800, false)
    val afterGapSilence = late.process(GuardianCaptureChannelFrame(7, 2, 0,
        listOf(GuardianCaptureChannelFrame.Channel(0, FloatArray(800), false))), 800, false)
    check(afterGapSilence.single().truth.size == 800) { "Recorded silence of a known channel was discarded after a gap" }
    println("PASS late channels and missing coverage retain explicit global origins without zero prefixes or gap splicing")

    val tainted = GuardianWienerChannelBank()
    tainted.process(payload(0, 800), 800, true)
    check(checkNotNull(tainted.window(0, 0, 256)).status.reason == "SPEECH_DURING_CALIBRATION")
    tainted.process(payload(800, 1600), 800, false)
    check(tainted.resetCount == 2)
    check(checkNotNull(tainted.window(0, 0, 1200)).status.startupBypass)
    val none = GuardianWienerChannelBank()
    none.process(GuardianCaptureChannelFrame(1, 2, 0, listOf(
        GuardianCaptureChannelFrame.Channel(0, FloatArray(800), false))), 800, false)
    check(none.channelIndices.isEmpty())
    none.process(null, 800, false)
    check(none.inputSamples == 1600L)
    val expired = GuardianWienerChannelBank()
    expired.process(payload(0, 800, includeSecond = false), 800, false)
    expired.process(null, 16_000 * 17, false)
    check(expired.canFinalize(0, 256) && expired.window(0, 0, 256) == null)
    println("PASS early neural evidence taints initial channel profiles, quiet restarts safely, absent channels never become fake PCM")
}
