package best.nagikokoro.watch6heartrateprobe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ConfirmedHeartRateContractTest {
    @Test
    fun watchAcceptsOnlyExactConfirmedBpm() {
        assertEquals(84, ConfirmedHeartRateContract.exactBpm(84))
        assertEquals(142, ConfirmedHeartRateContract.exactBpm(142L))
        assertNull(ConfirmedHeartRateContract.exactBpm(84.0))
        assertNull(ConfirmedHeartRateContract.exactBpm("84"))
        assertNull(ConfirmedHeartRateContract.exactBpm(0))
        assertNull(ConfirmedHeartRateContract.exactBpm(301))
    }
}
