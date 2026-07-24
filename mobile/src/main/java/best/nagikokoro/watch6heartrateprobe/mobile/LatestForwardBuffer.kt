package best.nagikokoro.watch6heartrateprobe.mobile

/**
 * Keeps only the latest continuous sample while reserving a separate slot for
 * an explicit diagnostic request. Callers provide synchronization.
 */
internal class LatestForwardBuffer<T> {
    private var latestSample: T? = null
    private var latestDiagnostic: T? = null

    fun offer(value: T, diagnostic: Boolean) {
        if (diagnostic) {
            latestDiagnostic = value
        } else {
            latestSample = value
        }
    }

    fun poll(): T? = latestDiagnostic?.also { latestDiagnostic = null }
        ?: latestSample.also { latestSample = null }

    fun clear() {
        latestSample = null
        latestDiagnostic = null
    }

    fun isEmpty(): Boolean = latestSample == null && latestDiagnostic == null
}
