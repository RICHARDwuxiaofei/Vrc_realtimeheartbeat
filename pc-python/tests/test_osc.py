import struct

import pytest

from vrc_heartbeat.osc import encode_message


def test_integer_message_has_big_endian_payload():
    packet = encode_message("/x", 42)
    assert packet.startswith(b"/x\0\0,i\0\0")
    assert packet[-4:] == struct.pack(">i", 42)
    assert len(packet) % 4 == 0


def test_float_message_has_big_endian_payload():
    packet = encode_message("/rate", 0.5)
    assert b",f\0\0" in packet
    assert packet[-4:] == struct.pack(">f", 0.5)


@pytest.mark.parametrize("value, tag", [(True, b",T\0\0"), (False, b",F\0\0")])
def test_boolean_uses_osc_type_tag_without_payload(value, tag):
    packet = encode_message("/valid", value)
    assert packet.endswith(tag)


def test_invalid_address_is_rejected():
    with pytest.raises(ValueError):
        encode_message("avatar/value", 1)


def test_unsupported_value_is_rejected():
    with pytest.raises(TypeError):
        encode_message("/x", "42")
