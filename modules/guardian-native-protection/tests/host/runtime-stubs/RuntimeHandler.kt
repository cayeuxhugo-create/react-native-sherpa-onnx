package android.os
import java.util.concurrent.*
class Looper {
    val executor = ScheduledThreadPoolExecutor(1) { job -> Thread(job, "test-handler").apply { isDaemon = true } }
}
class HandlerThread(val name: String) {
    val looper = Looper()
    var isAlive = false
    fun start() { isAlive = true }
    fun quitSafely() { looper.executor.shutdownNow(); isAlive = false }
}
class Handler(private val looper: Looper) {
    private val pending = ConcurrentHashMap<Runnable, ScheduledFuture<*>>()
    fun post(action: Runnable): Boolean = postDelayed(action, 0)
    fun postDelayed(action: Runnable, delay: Long): Boolean {
        if (looper.executor.isShutdown) return false
        pending[action] = looper.executor.schedule({ action.run() }, delay, TimeUnit.MILLISECONDS)
        return true
    }
    fun removeCallbacks(action: Runnable) { pending.remove(action)?.cancel(false) }
}
