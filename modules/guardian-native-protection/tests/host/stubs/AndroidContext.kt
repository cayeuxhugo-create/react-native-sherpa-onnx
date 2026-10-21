package android.content
open class Context {
    open val applicationContext: Context get() = this
    open val assets: android.content.res.AssetManager get() = android.content.res.AssetManager()
}
