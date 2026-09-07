import json
from pathlib import Path

from vrc_heartbeat.input_sources import PHONE_RELAY, XIAOMI_PC_BLE
from vrc_heartbeat.settings import AppSettings, load_settings, save_settings


TEST_ROOT = Path("build/settings-tests")


def test_old_settings_default_to_phone_relay():
    TEST_ROOT.mkdir(parents=True, exist_ok=True)
    path = TEST_ROOT / "old-settings.json"
    path.write_text('{"listen_port": 9124}', encoding="utf-8")

    settings = load_settings(path)

    assert settings.listen_port == 9124
    assert settings.input_source == PHONE_RELAY
    assert settings.ble_address == ""
    assert settings.relay_interval_seconds == 5
    assert settings.relay_interval_updated_epoch_millis == 0


def test_direct_ble_selection_and_device_round_trip():
    TEST_ROOT.mkdir(parents=True, exist_ok=True)
    path = TEST_ROOT / "direct-settings.json"
    expected = AppSettings(
        input_source=XIAOMI_PC_BLE,
        ble_address="AA:BB:CC:DD:EE:FF",
        ble_name="Xiaomi Smart Band 10",
    )

    save_settings(expected, path)

    assert load_settings(path) == expected
    payload = json.loads(path.read_text(encoding="utf-8"))
    assert payload["input_source"] == XIAOMI_PC_BLE


def test_unknown_source_is_safely_disabled():
    TEST_ROOT.mkdir(parents=True, exist_ok=True)
    path = TEST_ROOT / "unknown-settings.json"
    path.write_text('{"input_source":"future"}', encoding="utf-8")
    assert load_settings(path).input_source == PHONE_RELAY


def test_relay_interval_is_normalized_and_persisted():
    path = TEST_ROOT / "interval-settings.json"
    expected = AppSettings(relay_interval_seconds=10, relay_interval_updated_epoch_millis=1234)

    save_settings(expected, path)

    assert load_settings(path) == expected
    payload = json.loads(path.read_text(encoding="utf-8"))
    assert payload["relay_interval_seconds"] == 10
    assert payload["relay_interval_updated_epoch_millis"] == 1234


def test_ignored_update_tag_is_persisted():
    path = TEST_ROOT / "ignored-update-settings.json"
    expected = AppSettings(ignored_update_tag="v1.3.0")

    save_settings(expected, path)

    assert load_settings(path) == expected
    assert json.loads(path.read_text(encoding="utf-8"))["ignored_update_tag"] == "v1.3.0"


def test_invalid_relay_interval_is_normalized_to_safe_power_saver():
    path = TEST_ROOT / "invalid-interval-settings.json"
    path.write_text('{"relay_interval_seconds":99,"relay_interval_updated_epoch_millis":-1}', encoding="utf-8")

    settings = load_settings(path)

    assert settings.relay_interval_seconds == 10
    assert settings.relay_interval_updated_epoch_millis == 0
