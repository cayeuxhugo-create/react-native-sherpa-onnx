package expo.modules.guardiannativeprotection

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Private f32 input/output runner; publishes no audio or transcript to the repository. */
object WienerReplay {
    @JvmStatic
    fun main(args: Array<String>) {
        if (args.contentEquals(arrayOf("--self-test"))) {
            wienerEnhancerTests()
            return
        }
        require(args.size in 2..3) { "WienerReplay input.f32 output.f32 [400,800,...]" }
        val bytes = File(args[0]).readBytes()
        require(bytes.size % 4 == 0)
        val floats = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        val input = FloatArray(floats.remaining())
        floats.get(input)
        val chunks = if (args.size == 3) args[2].split(',').map { it.toInt() }.toIntArray() else intArrayOf(800)
        require(chunks.isNotEmpty() && chunks.all { it > 0 })
        val enhancer = GuardianWienerEnhancer()
        var position = 0
        var chunkIndex = 0
        var maxProcessNanos = 0L
        var calibrationProcessNanos = 0L
        File(args[1]).outputStream().buffered().use { output ->
            while (position < input.size) {
                val end = minOf(input.size, position + chunks[chunkIndex++ % chunks.size])
                val wasReady = enhancer.calibrationReady
                val block = input.copyOfRange(position, end)
                val begin = System.nanoTime()
                val enhanced = enhancer.process(block)
                val elapsed = System.nanoTime() - begin
                maxProcessNanos = maxOf(maxProcessNanos, elapsed)
                if (!wasReady && enhancer.calibrationReady) calibrationProcessNanos = elapsed
                val buffer = ByteBuffer.allocate(enhanced.size * 4).order(ByteOrder.LITTLE_ENDIAN)
                buffer.asFloatBuffer().put(enhanced)
                output.write(buffer.array())
                position = end
            }
        }
        System.err.println("inputSamples=${enhancer.inputSamples} outputSamples=${enhancer.outputSamples} " +
            "calibrationValid=${enhancer.calibrationValid} maxProcessMs=${maxProcessNanos / 1e6} " +
            "calibrationProcessMs=${calibrationProcessNanos / 1e6}")
    }
}
