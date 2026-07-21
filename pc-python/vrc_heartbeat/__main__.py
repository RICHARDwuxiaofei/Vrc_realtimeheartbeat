from __future__ import annotations

import sys

from .app import main
from .osc import encode_message
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


if __name__ == "__main__":
    if "--self-test" in sys.argv:
        self_test()
    else:
        main()
