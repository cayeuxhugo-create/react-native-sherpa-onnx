package expo.modules.guardiannativeprotection
/** Test double for native ASR; never used by the Android application. */
class GuardianQwenRescueRecognizer(val decodeAction: (FloatArray) -> Result?) {
    data class Result(val text: String, val language: String = "French", val elapsedMs: Long = 1L, val audioDurationMs: Long = 1000L)
    val released = java.util.concurrent.CountDownLatch(1)
    fun decode(pcm: FloatArray): Result? = decodeAction(pcm)
    fun release() { released.countDown() }
}
