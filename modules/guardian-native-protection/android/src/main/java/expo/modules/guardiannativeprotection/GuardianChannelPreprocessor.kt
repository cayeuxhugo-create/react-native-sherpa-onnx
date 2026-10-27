package expo.modules.guardiannativeprotection

/** Independent source views; never replaces or changes the selected primary PCM. */
internal class GuardianChannelPreprocessor(private val channelCount: Int) {
    private val previousInput = FloatArray(channelCount)
    private val previousOutput = FloatArray(channelCount)
    private val observedSignal = BooleanArray(channelCount)
    private var currentRouteRevision: Long? = null

    init { require(channelCount in 1..30) }

    fun process(
        interleavedPcm: ShortArray,
        interleavedSampleCount: Int,
        routeRevision: Long,
        selectedChannel: Int,
        usable: List<Boolean>,
    ): GuardianCaptureChannelFrame {
        require(interleavedSampleCount in 1..interleavedPcm.size)
        require(interleavedSampleCount % channelCount == 0)
        require(routeRevision >= 0L && selectedChannel in 0 until channelCount)
        require(usable.size == channelCount)
        if (currentRouteRevision != routeRevision) {
            previousInput.fill(0f)
            previousOutput.fill(0f)
            observedSignal.fill(false)
            currentRouteRevision = routeRevision
        }

        val sampleCount = interleavedSampleCount / channelCount
        val channels = ArrayList<GuardianCaptureChannelFrame.Channel>(channelCount)
        for (channel in 0 until channelCount) {
            var inputBefore = previousInput[channel]
            var outputBefore = previousOutput[channel]
            var samples = if (observedSignal[channel]) FloatArray(sampleCount) else null
            for (sample in 0 until sampleCount) {
                val pcm = interleavedPcm[sample * channelCount + channel]
                val input = pcm.toFloat() / 32768f
                // Exact Float32 recurrence used by the primary preprocessMonoPcm().
                val filtered = 0.97f * (outputBefore + input - inputBefore)
                if (pcm != 0.toShort() && samples == null) samples = FloatArray(sampleCount)
                samples?.set(sample, filtered.coerceIn(-1f, 1f))
                inputBefore = input
                outputBefore = filtered
            }
            previousInput[channel] = inputBefore
            previousOutput[channel] = outputBefore
            if (samples != null) {
                observedSignal[channel] = true
                // Keep known channels, including zero blocks, on the original source clock.
                channels += GuardianCaptureChannelFrame.Channel(channel, samples, usable[channel])
            }
        }
        return GuardianCaptureChannelFrame(routeRevision, channelCount, selectedChannel, channels)
    }
}
