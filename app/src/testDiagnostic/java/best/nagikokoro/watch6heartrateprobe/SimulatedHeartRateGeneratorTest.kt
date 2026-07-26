package best.nagikokoro.watch6heartrateprobe

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SimulatedHeartRateGeneratorTest {
    @Test
    fun generated_walk_stays_between_60_and_80() {
        val random = Random(20260726)
        var bpm = SimulatedHeartRateGenerator.initial(random)

        repeat(10_000) {
            bpm = SimulatedHeartRateGenerator.next(bpm, random)
            assertTrue(bpm in 60..80)
        }
    }

    @Test
    fun boundary_steps_bounce_inward() {
        assertEquals(61, SimulatedHeartRateGenerator.nextWithStep(60, -1))
        assertEquals(79, SimulatedHeartRateGenerator.nextWithStep(80, 1))
        assertEquals(72, SimulatedHeartRateGenerator.nextWithStep(70, 2))
    }
}
