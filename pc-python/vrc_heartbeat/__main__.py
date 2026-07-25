from __future__ import annotations

import sys

from .app import main
from .ble_direct import ble_dependency_available, ble_scan_self_test, parse_heart_rate_measurement
from .osc import encode_message
from .pairing import build_pairing_uri
from .protocol import build_ack, parse_packet


def self_test() -> None:
    packet = parse_packet(
        b'{"type":"heart_rate","sequence":7,"sampleEpochMillis":1000,"bpm":72}'
    )
    assert packet.bpm == 72
    assert b'"sequence":7' in build_ack(7, 2000)
    assert encode_message("/avatar/parameters/HR_Value", 72)
    assert encode_message("/avatar/parameters/HR_Hundreds", 0)
    assert encode_message("/avatar/parameters/HR_Tens", 7)
    assert encode_message("/avatar/parameters/HR_Ones", 2)
    pairing_uri = build_pairing_uri("192.168.1.88", 9123)
    assert pairing_uri == "vrc-heartbeat://pair?host=192.168.1.88&port=9123"
    import qrcode

    assert qrcode.make(pairing_uri).size[0] > 0
    assert ble_dependency_available()
    assert parse_heart_rate_measurement(b"\x00\x48") == 72


if __name__ == "__main__":
    if "--ble-scan-self-test" in sys.argv:
        ble_scan_self_test()
    elif "--self-test" in sys.argv:
        self_test()
    else:
        main()
