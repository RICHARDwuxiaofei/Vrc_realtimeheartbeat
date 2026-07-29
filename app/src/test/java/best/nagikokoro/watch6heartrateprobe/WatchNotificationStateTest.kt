package best.nagikokoro.watch6heartrateprobe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WatchNotificationStateTest {
    @Test
    fun sessionStatesMapToNotificationStatus() {
        assertEquals("运行中", watchNotificationStatusKey(ExerciseSessionState.ACTIVE))
        assertEquals("已暂停", watchNotificationStatusKey(ExerciseSessionState.PAUSED))
        assertEquals("正在停止", watchNotificationStatusKey(ExerciseSessionState.ENDING))
        assertEquals("正在启动", watchNotificationStatusKey(ExerciseSessionState.STARTING))
    }

    @Test
    fun onlyFreshHeartRateIsShown() {
        val snapshot = ExerciseSessionSnapshot(
            bpm = 68,
            lastSampleMillis = 9_000L,
        )

        assertEquals(68, watchNotificationBpm(snapshot, nowMillis = 10_000L))
        assertNull(watchNotificationBpm(snapshot, nowMillis = 30_001L))
    }
}
