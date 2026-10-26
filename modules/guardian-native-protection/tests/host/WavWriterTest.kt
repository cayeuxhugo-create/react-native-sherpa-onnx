package expo.modules.guardiannativeprotection
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
fun wavWriterTests() {
    val dir = Files.createTempDirectory("guardian-wav-test").toFile()
    try {
        val writer = GuardianDiagnosticWavWriter(dir, "DISTANT", "testsession", null, 6)
        val pcm = floatArrayOf(-2f, -0.5f, 0f, 0.5f, 2f)
        val original = pcm.copyOf()
        for (stage in listOf("raw", "pregain", "truth", "asr", "vad")) {
            writer.write(stage, pcm, 12, 450)
            writer.write(stage, floatArrayOf(Float.NaN), 13, 460)
            writer.write(stage, floatArrayOf(0.25f, 0.75f), 14, 470)
            writer.write(stage, pcm, 15, 480)
        }
        writer.close(); writer.close(); writer.write("truth", pcm, 16, 490)
        check(pcm.contentEquals(original))
        val wavs = dir.listFiles()!!.filter { it.extension == "wav" }
        check(wavs.size == 5)
        for (file in wavs) {
            val bytes = file.readBytes(); val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            check(bytes.size == 56 && buffer.getInt(4) == 48 && buffer.getInt(40) == 12)
            check(buffer.getShort(20).toInt() == 1 && buffer.getShort(22).toInt() == 1)
            check(buffer.getInt(24) == 16000 && buffer.getShort(34).toInt() == 16)
            val samples = (0 until 6).map { buffer.getShort(44 + 2*it).toInt() }
            check(samples == listOf(-32768, -16384, 0, 16384, 32767, 8192))
        }
        for (file in dir.listFiles()!!.filter { it.extension == "f32" }) {
            val buffer = ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
            check(buffer.remaining() == 24)
            val expected = pcm + floatArrayOf(.25f)
            for (value in expected) check(buffer.float.toRawBits() == value.toRawBits())
        }
        check(dir.listFiles()!!.count { it.extension == "f32" } == 5)
        val weak = GuardianDiagnosticWavWriter(dir, "DISTANT", "weak", null)
        val tiny = floatArrayOf(1e-9f, -1e-9f, -0.0f, Float.MIN_VALUE)
        weak.write("truth", tiny, 1, 1); weak.close()
        val tinyFile = dir.listFiles()!!.filter { it.extension == "f32" }.single { it.length() == 16L }
        val values = ByteBuffer.wrap(tinyFile.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        tiny.forEach { check(values.float.toRawBits() == it.toRawBits()) }
        val lines = dir.listFiles()!!.single { it.extension == "tsv" && it.readText().contains("asr\t12") }.readLines()
        check(lines.size == 11)
        check(lines.contains("asr\t12\t450\t0\t5"))
        check(lines.contains("asr\t14\t470\t5\t1"))
        println("WAV export passed: five stages including pre-filter capture, PCM format, clipping, limits, timestamps, invalid PCM, immutable input, finalization")
    } finally { dir.deleteRecursively() }
}
