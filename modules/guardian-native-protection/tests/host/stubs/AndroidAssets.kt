package android.content.res
class AssetManager {
    fun open(path: String): java.io.InputStream =
        System.getProperty("guardian.test.assets")?.let { java.io.File(it, path).inputStream() }
            ?: java.io.ByteArrayInputStream(byteArrayOf(1))
}
