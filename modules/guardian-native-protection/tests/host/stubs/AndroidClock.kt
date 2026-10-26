package android.os
object SystemClock {
    fun currentThreadTimeMillis(): Long = java.lang.management.ManagementFactory.getThreadMXBean().currentThreadCpuTime / 1000000L
    fun elapsedRealtime(): Long = System.nanoTime() / 1_000_000L
}
