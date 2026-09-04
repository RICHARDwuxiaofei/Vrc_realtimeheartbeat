package best.nagikokoro.watch6heartrateprobe.mobile

internal enum class PcNotificationState {
    PAUSED,
    NOT_CONFIGURED,
    CONFIRMED,
    WAITING,
}

internal data class PhoneRelayNotificationState(
    val bpm: Int?,
    val pcState: PcNotificationState,
)

internal fun phoneRelayNotificationState(
    state: PhoneRelayState,
    nowMillis: Long,
    heartRateFreshMillis: Long = 15_000L,
    pcAckFreshMillis: Long = 15_000L,
): PhoneRelayNotificationState {
    val bpm = state.currentBpm?.takeIf {
        state.lastPhoneReceiveMillis?.let { received ->
            nowMillis - received <= heartRateFreshMillis
        } == true
    }
    val pcState = when {
        !state.forwardingEnabled -> PcNotificationState.PAUSED
        state.targetIp.isBlank() -> PcNotificationState.NOT_CONFIGURED
        state.lastPcAckMillis?.let { nowMillis - it <= pcAckFreshMillis } == true ->
            PcNotificationState.CONFIRMED
        else -> PcNotificationState.WAITING
    }
    return PhoneRelayNotificationState(bpm = bpm, pcState = pcState)
}
