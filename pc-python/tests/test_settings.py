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
    assert settings.forward_oyasumi is False


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


def test_oyasumi_forwarding_round_trip():
    TEST_ROOT.mkdir(parents=True, exist_ok=True)
    path = TEST_ROOT / "oyasumi-settings.json"

    save_settings(AppSettings(forward_oyasumi=True), path)

    assert load_settings(path).forward_oyasumi is True


def test_unknown_source_is_safely_disabled():
    TEST_ROOT.mkdir(parents=True, exist_ok=True)
    path = TEST_ROOT / "unknown-settings.json"
    path.write_text('{"input_source":"future"}', encoding="utf-8")
    assert load_settings(path).input_source == PHONE_RELAY
