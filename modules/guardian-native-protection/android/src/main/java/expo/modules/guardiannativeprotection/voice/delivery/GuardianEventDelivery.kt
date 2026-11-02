package expo.modules.guardiannativeprotection

/** Single-owner retry queue. IDs are preserved across retries; commit must be idempotent.
 * A successful commit means durable local acceptance, not remote delivery.
 * If storage is unavailable, bounded RAM retention is explicitly NOT crash durable. */
internal class GuardianEventDelivery<T>(private val capacity: Int = 64) {
    private val pending = LinkedHashMap<String, T>()
    @Volatile var lastError: String? = null; private set
    private var overflowed = false
    val size: Int get() = synchronized(this) { pending.size }
    init { require(capacity > 0) }
    @Synchronized fun any(predicate: (T) -> Boolean): Boolean = pending.values.any(predicate)
    @Synchronized fun offer(id: String, value: T): Boolean {
        require(id.isNotBlank())
        if (id in pending) return true
        if (pending.size >= capacity) { overflowed = true; lastError = "VOICE_DELIVERY_QUEUE_FULL"; return false }
        pending[id] = value
        return true
    }
    @Synchronized fun drain(commit: (T) -> Unit): List<T> {
        val accepted = ArrayList<T>()
        val iterator = pending.entries.iterator()
        while (iterator.hasNext()) {
            val event = iterator.next().value
            try { commit(event) } catch (error: Exception) {
                lastError = "VOICE_PERSISTENCE_FAILED:${error.javaClass.simpleName}"
                return accepted
            }
            iterator.remove()
            accepted += event
        }
        lastError = if (overflowed) "VOICE_DELIVERY_QUEUE_FULL" else null
        return accepted
    }
}
