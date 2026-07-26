import pytest

from vrc_heartbeat.pairing import build_pairing_uri, parse_pairing_uri


def test_pairing_uri_round_trip() -> None:
    uri = build_pairing_uri("192.168.1.88", 9123)
    assert uri == "vrc-heartbeat://pair?host=192.168.1.88&port=9123"
    assert parse_pairing_uri(uri).host == "192.168.1.88"
    assert parse_pairing_uri(uri).port == 9123


@pytest.mark.parametrize(
    "uri",
    [
        "https://example.com",
        "vrc-heartbeat://pair?host=bad&port=9123",
        "vrc-heartbeat://pair?host=192.168.1.2&port=0",
    ],
)
def test_pairing_uri_rejects_invalid_values(uri: str) -> None:
    with pytest.raises(ValueError):
        parse_pairing_uri(uri)
