from __future__ import annotations


PHONE_RELAY = "phone_udp"
XIAOMI_PC_BLE = "xiaomi_pc_ble"
VALID_INPUT_SOURCES = frozenset({PHONE_RELAY, XIAOMI_PC_BLE})

INPUT_SOURCE_LABELS = {
    PHONE_RELAY: "手机中转（Galaxy / 小米）",
    XIAOMI_PC_BLE: "电脑直连小米手环（实验）",
}


def normalize_input_source(value: object) -> str:
    return value if isinstance(value, str) and value in VALID_INPUT_SOURCES else PHONE_RELAY
