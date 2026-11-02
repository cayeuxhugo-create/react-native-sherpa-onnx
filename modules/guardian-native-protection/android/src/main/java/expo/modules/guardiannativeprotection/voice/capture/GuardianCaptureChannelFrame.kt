package expo.modules.guardiannativeprotection

/** Synchronous views of one AudioRecord block, before gain or denoising.
 * Every channel has its OWN continuous 0.97 high-pass state. Physical routing
 * changes increment routeRevision; choosing another channel does not.
 * An omitted source means it has emitted only exact zeros since route start.
 * Once a source emits a nonzero sample, producers MUST include it in every
 * subsequent block, including silence. A missing known source is a gap.
 * Arrays are owned by the producer; asynchronous consumers must copyOwned(). */
data class GuardianCaptureChannelFrame(
    val routeRevision: Long,
    val openedChannels: Int,
    val selectedChannel: Int,
    val channels: List<Channel>,
) {
    data class Channel(val index: Int, val samples: FloatArray, val usable: Boolean)

    fun validate(sampleCount: Int) {
        require(openedChannels in 1..30 && selectedChannel in 0 until openedChannels)
        require(routeRevision >= 0 && sampleCount > 0)
        require(channels.map { it.index }.distinct().size == channels.size)
        require(channels.all { it.index in 0 until openedChannels && it.samples.size == sampleCount && it.samples.all(Float::isFinite) })
    }

    fun copyOwned() = copy(channels = channels.map { it.copy(samples = it.samples.copyOf()) })
}
