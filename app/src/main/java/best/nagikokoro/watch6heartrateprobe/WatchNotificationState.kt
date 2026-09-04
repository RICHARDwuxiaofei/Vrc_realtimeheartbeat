package best.nagikokoro.watch6heartrateprobe

internal fun watchNotificationStatusKey(state: ExerciseSessionState): String = when (state) {
    ExerciseSessionState.ACTIVE -> "运行中"
    ExerciseSessionState.PAUSED -> "已暂停"
    ExerciseSessionState.ENDING, ExerciseSessionState.ENDED -> "正在停止"
    else -> "正在启动"
}

internal fun watchNotificationBpm(
    snapshot: ExerciseSessionSnapshot,
    nowMillis: Long,
    freshMillis: Long = 15_000L,
): Int? = snapshot.bpm?.takeIf {
    snapshot.lastSampleMillis?.let { sampleMillis ->
        nowMillis - sampleMillis <= freshMillis
    } == true
}
