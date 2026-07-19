package best.nagikokoro.watch6heartrateprobe

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class RelayStatus(
    val phoneNearby: Boolean = false,
    val phoneName: String = "--",
    val sentCount: Long = 0,
    val failedCount: Long = 0,
    val lastSentMillis: Long? = null,
    val lastAckMillis: Long? = null,
    val lastPcAck: Boolean = false,
    val lastError: String = "--",
)

object RelayStatusStore {
    private val mutable = MutableStateFlow(RelayStatus())
    val state = mutable.asStateFlow()

    @Synchronized
    fun update(transform: (RelayStatus) -> RelayStatus) {
        mutable.value = transform(mutable.value)
    }
}
