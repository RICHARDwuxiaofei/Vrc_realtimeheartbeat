from pathlib import Path

from vrc_heartbeat.analytics import HeartRateSample
from vrc_heartbeat.diagnostic_csv import DiagnosticCsvStore


TEST_ROOT = Path("build/diagnostic-csv-tests")


def test_csv_can_be_read_while_append_handle_is_open() -> None:
    TEST_ROOT.mkdir(parents=True, exist_ok=True)
    store = DiagnosticCsvStore(TEST_ROOT / "read-while-writing.csv")
    store.begin()
    store.append(
        HeartRateSample(100_000, 72, "192.168.1.2", 14),
        {"rawBpm": 72.4, "watchBatteryPercent": 88},
        "1970-01-01T00:01:40",
    )

    assert store.active
    assert [sample.bpm for sample in store.read_window(100_000, 1)] == [72]

    store.append(
        HeartRateSample(170_000, 80, "192.168.1.2", 10),
        {"accuracy": "HIGH"},
        "1970-01-01T00:02:50",
    )
    assert [sample.bpm for sample in store.read_window(170_000, 1)] == [80]
    store.stop()


def test_export_copies_current_csv_without_stopping_writer() -> None:
    TEST_ROOT.mkdir(parents=True, exist_ok=True)
    store = DiagnosticCsvStore(TEST_ROOT / "export-source.csv")
    store.begin()
    store.append(HeartRateSample(100, 70, "phone", 1), {}, "time")
    exported = TEST_ROOT / "exported.csv"

    store.export(exported)

    assert store.active
    assert exported.read_text(encoding="utf-8").count("\n") == 2
    assert not store.has_unexported_rows
    store.stop()


def test_diagnostic_mode_can_pause_and_resume_same_csv() -> None:
    TEST_ROOT.mkdir(parents=True, exist_ok=True)
    store = DiagnosticCsvStore(TEST_ROOT / "pause-resume.csv")
    store.begin()
    store.append(HeartRateSample(100_000, 70, "phone", 1), {}, "first")
    store.stop()

    store.resume()
    store.append(HeartRateSample(101_000, 71, "phone", 1), {}, "second")

    assert [sample.bpm for sample in store.read_window(101_000, 1)] == [70, 71]
    store.stop()


def test_read_window_stops_before_old_history(monkeypatch) -> None:
    TEST_ROOT.mkdir(parents=True, exist_ok=True)
    store = DiagnosticCsvStore(TEST_ROOT / "long-session.csv")
    store.begin()
    for index in range(2_000):
        store.append(
            HeartRateSample(index * 1_000, 60 + index % 40, "phone", 1),
            {},
            f"sample-{index}",
        )

    lines_read = 0
    original = store._data_lines_from_newest

    def counting_lines():
        nonlocal lines_read
        for line in original():
            lines_read += 1
            yield line

    monkeypatch.setattr(store, "_data_lines_from_newest", counting_lines)
    samples = store.read_window(now_ms=1_999_000, minutes=1)

    assert len(samples) == 61
    assert samples[0].epoch_ms == 1_939_000
    assert lines_read == 62
    store.stop()
