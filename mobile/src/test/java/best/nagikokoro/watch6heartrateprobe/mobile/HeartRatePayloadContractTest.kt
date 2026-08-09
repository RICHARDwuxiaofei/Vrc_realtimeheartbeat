package best.nagikokoro.watch6heartrateprobe.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HeartRatePayloadContractTest {
    @Test
    fun acceptsOnlyExactIntegerBpmValues() {
        assertEquals(72, HeartRatePayloadContract.exactBpm(72))
        assertEquals(142, HeartRatePayloadContract.exactBpm(142L))
        assertNull(HeartRatePayloadContract.exactBpm(72.0))
        assertNull(HeartRatePayloadContract.exactBpm("72"))
        assertNull(HeartRatePayloadContract.exactBpm(true))
        assertNull(HeartRatePayloadContract.exactBpm(0))
        assertNull(HeartRatePayloadContract.exactBpm(301))
    }

    @Test
    fun pcAcknowledgementMustEchoSequenceAndBpm() {
        assertTrue(HeartRatePayloadContract.acknowledgementMatches("pc_ack", 7, 7, 84, 84))
        assertFalse(HeartRatePayloadContract.acknowledgementMatches("pc_ack", 8, 7, 84, 84))
        assertFalse(HeartRatePayloadContract.acknowledgementMatches("pc_ack", 7, 7, 85, 84))
        assertFalse(HeartRatePayloadContract.acknowledgementMatches("pc_ack", 7, 7, null, 84))
    }
}
