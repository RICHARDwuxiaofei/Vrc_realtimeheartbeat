package best.nagikokoro.watch6heartrateprobe.mobile

import org.junit.Assert.assertEquals
import org.junit.Test

class PairingUriTest {
    @Test
    fun parsesValidPairingUri() {
        val target = PairingUri.parse("vrc-heartbeat://pair?host=192.168.1.88&port=9123")
        assertEquals("192.168.1.88", target.host)
        assertEquals(9123, target.port)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsForeignUri() {
        PairingUri.parse("https://example.com")
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsInvalidIpv4() {
        PairingUri.parse("vrc-heartbeat://pair?host=999.1.1.1&port=9123")
    }
}
