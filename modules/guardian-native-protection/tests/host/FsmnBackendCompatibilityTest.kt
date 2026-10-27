package expo.modules.guardiannativeprotection

import java.io.File

/** Run in separate JVMs against previous, incompatible and corrected JNI.
 * Uses the real verified assets, without decoding or recording any speech.
 */
object FsmnBackendCompatibilityTest {
    @JvmStatic fun main(args: Array<String>) {
        require(args.size == 2 && args[1] in setOf("serial", "legacy", "incompatible"))
        val assets = File(args[0])
        var reads = 0
        val result = runCatching {
            GuardianFsmnConfirmation.load { name ->
                reads++
                File(assets, name).readBytes()
            }
        }
        check(reads == 3) { "The backend must be checked only after verifying all three assets" }
        if (args[1] == "legacy") {
            val error = result.exceptionOrNull()
            check(error is UnsatisfiedLinkError && error.message.orEmpty().contains("executionBackend")) {
                "The previous JNI must be rejected before native model creation"
            }
            println("PASS FSMN legacy JNI rejected at backend handshake after asset verification")
        } else if (args[1] == "incompatible") {
            val error = result.exceptionOrNull()
            check(error is IllegalStateException &&
                error.message.orEmpty().contains("serial CPU native backend")) {
                "An incompatible backend must be rejected before native model creation"
            }
            println("PASS FSMN incompatible backend rejected before model creation")
        } else {
            result.getOrThrow().close()
            check(GuardianFsmnNative.executionBackend() == GuardianFsmnConfirmation.EXECUTION_BACKEND)
            println("PASS FSMN serial JNI backend accepted with verified model assets")
        }
    }
}
