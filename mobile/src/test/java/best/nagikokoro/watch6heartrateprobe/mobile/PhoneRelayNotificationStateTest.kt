package best.nagikokoro.watch6heartrateprobe.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PhoneRelayNotificationStateTest {
    @Test
    fun freshHeartRateAndAckAreShownAsConfirmed() {
        val result = phoneRelayNotificationState(
            PhoneRelayState(
                currentBpm = 72,
                lastPhoneReceiveMillis = 9_000L,
                targetIp = "192.168.1.10",
                lastPcAckMillis = 8_000L,
            ),
            nowMillis = 10_000L,
        )

        assertEquals(72, result.bpm)
        assertEquals(PcNotificationState.CONFIRMED, result.pcState)
    }

    @Test
    fun staleHeartRateIsNotPresentedAsCurrent() {
        val result = phoneRelayNotificationState(
            PhoneRelayState(
                currentBpm = 72,
                lastPhoneReceiveMillis = 10_000L,
                targetIp = "192.168.1.10",
            ),
            nowMillis = 30_001L,
        )

        assertNull(result.bpm)
        assertEquals(PcNotificationState.WAITING, result.pcState)
    }

    @Test
    fun pauseAndMissingTargetTakePriority() {
        assertEquals(
            PcNotificationState.PAUSED,
            phoneRelayNotificationState(
                PhoneRelayState(forwardingEnabled = false),
                nowMillis = 10_000L,
            ).pcState,
        )
        assertEquals(
            PcNotificationState.NOT_CONFIGURED,
            phoneRelayNotificationState(
                PhoneRelayState(forwardingEnabled = true),
                nowMillis = 10_000L,
            ).pcState,
        )
    }
}
