import sys

import pytest

from vrc_heartbeat.ble_direct import BleHeartRateClient, parse_heart_rate_measurement


@pytest.mark.parametrize(
    "payload, expected",
    [
        (bytes([0x00, 88]), 88),
        (bytes([0x01, 0x04, 0x01]), 260),
        (bytes([0x10, 72, 0x34, 0x12]), 72),
    ],
)
def test_parses_standard_heart_rate_measurement(payload, expected):
    assert parse_heart_rate_measurement(payload) == expected


@pytest.mark.parametrize(
    "payload",
    [
        b"",
        bytes([0x00]),
        bytes([0x01, 72]),
        bytes([0x00, 0]),
        bytes([0x01, 0x2D, 0x01]),
    ],
)
def test_rejects_truncated_or_unsafe_measurement(payload):
    assert parse_heart_rate_measurement(payload) is None


def test_notification_emits_only_valid_bpm():
    events = []
    client = BleHeartRateClient(lambda kind, data: events.append((kind, data)))

    client._on_notification(None, bytearray([0x00, 76]))
    client._on_notification(None, bytearray([0x00, 0]))

    assert events[0][0] == "ble_heart_rate"
    assert events[0][1]["bpm"] == 76
    assert events[1] == ("ble_warning", {"message": "忽略了一条无效的标准心率数据"})


@pytest.mark.skipif(sys.platform != "win32", reason="WinRT event loop is Windows-only")
def test_windows_ble_event_loop_starts_and_stops():
    client = BleHeartRateClient()
    assert client.start()
    assert client.running
    client.stop()
    assert not client.running
