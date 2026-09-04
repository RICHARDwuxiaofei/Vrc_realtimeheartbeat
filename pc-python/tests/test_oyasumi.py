import socket
import struct
from types import SimpleNamespace

from zeroconf import IPVersion

from vrc_heartbeat.oyasumi import (
    OYASUMI_HEART_RATE_ADDRESS,
    OyasumiOscTarget,
    target_from_service_info,
)
from vrc_heartbeat.runtime import BridgeRuntime, RuntimeConfig


class FakeServiceInfo:
    port = 3210

    def parsed_addresses(self, version):
        assert version == IPVersion.V4Only
        return ["192.0.2.10"]


def test_oscquery_host_info_resolves_dynamic_oyasumi_osc_port():
    responses = {
        "http://192.0.2.10:3210/?HOST_INFO": {
            "NAME": "OyasumiVR",
            "OSC_IP": "0.0.0.0",
            "OSC_PORT": 4567,
            "OSC_TRANSPORT": "UDP",
        },
        "http://192.0.2.10:3210/OyasumiVR/HeartRate": {
            "FULL_PATH": "/OyasumiVR/HeartRate",
            "VALUE_TYPE": "i",
            "ACCESS": 2,
        },
    }

    target = target_from_service_info(
        FakeServiceInfo(),
        "OyasumiVR._oscjson._tcp.local.",
        responses.__getitem__,
    )

    assert target == OyasumiOscTarget(
        host="192.0.2.10",
        port=4567,
        service_name="OyasumiVR._oscjson._tcp.local.",
    )


def test_oscquery_target_rejects_wrong_or_read_only_heart_rate_node():
    responses = {
        "http://192.0.2.10:3210/?HOST_INFO": {"NAME": "OyasumiVR"},
        "http://192.0.2.10:3210/OyasumiVR/HeartRate": {
            "FULL_PATH": "/OyasumiVR/HeartRate",
            "TYPE": "f",
            "ACCESS": 1,
        },
    }

    assert (
        target_from_service_info(
            FakeServiceInfo(),
            "OyasumiVR._oscjson._tcp.local.",
            responses.__getitem__,
        )
        is None
    )


def test_runtime_sends_only_integer_bpm_to_discovered_oyasumi_target():
    receiver = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    receiver.bind(("127.0.0.1", 0))
    receiver.settimeout(2)
    runtime = BridgeRuntime(RuntimeConfig(forward_osc=False, forward_oyasumi=False))
    runtime._forward_oyasumi = True
    runtime._oyasumi_discovery = SimpleNamespace(
        target=OyasumiOscTarget(
            host="127.0.0.1",
            port=receiver.getsockname()[1],
            service_name="test",
        )
    )
    try:
        runtime._send_oyasumi_heart_rate(73)
        address, value = _decode_osc_int(receiver.recvfrom(1024)[0])
        assert address == OYASUMI_HEART_RATE_ADDRESS
        assert value == 73
    finally:
        runtime._oyasumi_discovery = None
        runtime.stop()
        receiver.close()


def _decode_osc_int(packet):
    address_end = packet.index(b"\0")
    address = packet[:address_end].decode("ascii")
    type_tag_offset = (address_end + 4) & ~3
    assert packet[type_tag_offset : type_tag_offset + 2] == b",i"
    return address, struct.unpack(">i", packet[-4:])[0]
