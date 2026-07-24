from vrc_heartbeat.updates import is_newer_version, version_tuple


def test_version_comparison() -> None:
    assert version_tuple("v1.2.3") == (1, 2, 3)
    assert is_newer_version("v1.1.0", "1.0.9")
    assert not is_newer_version("v1.0.0", "1.0.0")
    assert not is_newer_version("invalid", "1.0.0")
