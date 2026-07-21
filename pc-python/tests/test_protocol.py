import json

import pytest

from vrc_heartbeat.protocol import (
    ProtocolError,
    build_ack,
    clamp_osc_bpm,
    normalized_heart_rate,
    packet_latency_ms,
    parse_packet,
    split_three_digits,
    stale_timeout_ms,
)


def packet(**overrides):
    value = {
        "type": "heart_rate",
        "sequence": 42,
        "sampleEpochMillis": 1_000,
        "bpm": 84,
        "phoneForwardIntervalSeconds": 5,
    }
    value.update(overrides)
    return json.dumps(value).encode()


def test_parse_real_packet():
    parsed = parse_packet(packet())
    assert parsed.is_real_heart_rate
    assert parsed.sequence == 42
    assert parsed.bpm == 84
    assert parsed.forward_interval_seconds == 5


@pytest.mark.parametrize("interval, expected", [(-5, 1), (0, 1), (10, 10), (99, 30), ("5", 5)])
def test_forward_interval_is_safe(interval, expected):
    assert parse_packet(packet(phoneForwardIntervalSeconds=interval)).forward_interval_seconds == expected


@pytest.mark.parametrize(
    "payload",
    [b"not-json", b"[]", packet(sequence=True), packet(sampleEpochMillis=0), packet(bpm=0), packet(bpm=301)],
)
def test_invalid_packets_are_rejected(payload):
    with pytest.raises(ProtocolError):
        parse_packet(payload)


def test_ack_matches_existing_phone_contract():
    assert json.loads(build_ack(42, 9_000)) == {
        "type": "pc_ack",
        "sequence": 42,
        "pcEpochMillis": 9_000,
    }


def test_latency_never_goes_negative():
    parsed = parse_packet(packet(sampleEpochMillis=2_000))
    assert packet_latency_ms(parsed, 1_500) == 0


def test_timeout_scales_with_phone_interval():
    assert stale_timeout_ms(1) == 10_000
    assert stale_timeout_ms(5) == 12_500
    assert stale_timeout_ms(30) == 75_000


@pytest.mark.parametrize(
    "bpm, value, digits",
    [
        (-1, 0, (0, 0, 0)),
        (0, 0, (0, 0, 0)),
        (9, 9, (0, 0, 9)),
        (84, 84, (0, 8, 4)),
        (142, 142, (1, 4, 2)),
        (999, 999, (9, 9, 9)),
        (1_200, 999, (9, 9, 9)),
    ],
)
def test_three_digit_display_is_clamped_and_split(bpm, value, digits):
    assert clamp_osc_bpm(bpm) == value
    assert split_three_digits(bpm) == digits


def test_normalized_legacy_parameter_is_clamped():
    assert normalized_heart_rate(20) == 0.0
    assert normalized_heart_rate(40) == 0.0
    assert normalized_heart_rate(200) == 1.0
    assert normalized_heart_rate(240) == 1.0
