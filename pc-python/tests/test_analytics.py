from vrc_heartbeat.analytics import HeartRateHistory, HeartRateSample


def sample(epoch_ms: int, bpm: int) -> HeartRateSample:
    return HeartRateSample(epoch_ms, bpm, "192.168.1.2", 12)


def test_history_prunes_samples_outside_window_and_calculates_stats() -> None:
    history = HeartRateHistory(window_ms=600_000)
    history.add(sample(1_000, 70))
    history.add(sample(300_000, 90))
    history.add(sample(600_000, 80))

    stats = history.stats(now_ms=601_001)

    assert stats is not None
    assert (stats.minimum, stats.maximum, stats.average, stats.count) == (80, 90, 85.0, 2)
    assert [item.bpm for item in history.samples(601_001)] == [90, 80]


def test_empty_history_has_no_stats() -> None:
    assert HeartRateHistory().stats(now_ms=1_000) is None
