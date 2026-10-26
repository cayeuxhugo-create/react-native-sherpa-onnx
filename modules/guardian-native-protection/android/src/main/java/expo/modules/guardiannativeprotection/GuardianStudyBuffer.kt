package expo.modules.guardiannativeprotection

/** Monotonic output sample clock. Never pads missing/evicted samples. */
internal class GuardianStudyBuffer(private val capacity: Int = 16000 * 16) {
    private val ring = FloatArray(capacity)
    var end: Long = 0; private set
    fun append(pcm: FloatArray) {
        require(pcm.all { it.isFinite() })
        for (x in pcm) { ring[(end % capacity).toInt()] = x; end++ }
    }
    fun window(endSample: Long, count: Int): FloatArray? {
        val start = endSample - count
        if (count <= 0 || count > capacity || endSample > end || start < maxOf(0, end-capacity)) return null
        return FloatArray(count) { ring[((start+it) % capacity).toInt()] }
    }
}
