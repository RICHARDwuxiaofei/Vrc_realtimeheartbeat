package best.nagikokoro.watch6heartrateprobe

import java.util.concurrent.atomic.AtomicBoolean

object RelayDiagnosticModeStore {
    private val enabledState = AtomicBoolean(false)

    val enabled: Boolean
        get() = enabledState.get()

    fun setEnabled(enabled: Boolean): Boolean = enabledState.getAndSet(enabled) != enabled
}
