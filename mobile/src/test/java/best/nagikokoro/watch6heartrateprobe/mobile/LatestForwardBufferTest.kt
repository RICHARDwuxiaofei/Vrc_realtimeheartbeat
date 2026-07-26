package best.nagikokoro.watch6heartrateprobe.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LatestForwardBufferTest {
    @Test
    fun diagnosticIsNotOverwrittenByContinuousSamples() {
        val buffer = LatestForwardBuffer<String>()
        buffer.offer("diagnostic", diagnostic = true)
        buffer.offer("heart-1", diagnostic = false)
        buffer.offer("heart-2", diagnostic = false)

        assertEquals("diagnostic", buffer.poll())
        assertEquals("heart-2", buffer.poll())
        assertNull(buffer.poll())
        assertTrue(buffer.isEmpty())
    }

    @Test
    fun clearDropsBothSlots() {
        val buffer = LatestForwardBuffer<String>()
        buffer.offer("diagnostic", diagnostic = true)
        buffer.offer("heart", diagnostic = false)

        buffer.clear()

        assertNull(buffer.poll())
        assertTrue(buffer.isEmpty())
    }
}
