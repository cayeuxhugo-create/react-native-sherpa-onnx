package android.util
import java.io.*
/** Host test double; real Android AtomicFile durability still needs device tests. */
class AtomicFile(private val base: File) {
    private val backup = File(base.path + ".bak")
    fun startWrite(): FileOutputStream {
        if (base.exists()) check(base.renameTo(backup))
        return FileOutputStream(base)
    }
    fun finishWrite(stream: FileOutputStream) { stream.fd.sync(); stream.close(); backup.delete() }
    fun failWrite(stream: FileOutputStream) { stream.close(); base.delete(); if (backup.exists()) backup.renameTo(base) }
    fun openRead(): FileInputStream { if (backup.exists()) {base.delete(); backup.renameTo(base)}; return FileInputStream(base) }
    fun delete() { base.delete(); backup.delete() }
}
