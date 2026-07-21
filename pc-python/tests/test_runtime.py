import json
import socket
import struct
import threading

from vrc_heartbeat.runtime import BridgeRuntime, RuntimeConfig


def test_udp_runtime_returns_matching_ack_and_reports_packet():
    events = []
    received = threading.Event()

    def on_event(kind, data):
        events.append((kind, data))
        if kind == "packet":
            received.set()

    runtime = BridgeRuntime(RuntimeConfig(listen_host="127.0.0.1", listen_port=0, forward_osc=False), on_event)
    runtime.start()
    try:
        client = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        client.settimeout(2)
        payload = json.dumps(
            {"type": "heart_rate", "sequence": 77, "sampleEpochMillis": 1, "bpm": 72}
        ).encode()
        client.sendto(payload, ("127.0.0.1", runtime.bound_port))
        ack, _ = client.recvfrom(1024)
        assert json.loads(ack)["sequence"] == 77
        assert received.wait(2)
        packet_event = next(data for kind, data in events if kind == "packet")
        assert packet_event["packet"].bpm == 72
        assert packet_event["sender"] == "127.0.0.1"
    finally:
        runtime.stop()


def test_invalid_udp_packet_does_not_kill_runtime():
    events = []
    invalid = threading.Event()

    def on_event(kind, data):
        events.append((kind, data))
        if kind == "invalid_packet":
            invalid.set()

    runtime = BridgeRuntime(RuntimeConfig(listen_host="127.0.0.1", listen_port=0, forward_osc=False), on_event)
    runtime.start()
    try:
        client = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        client.sendto(b"bad-json", ("127.0.0.1", runtime.bound_port))
        assert invalid.wait(2)
        assert runtime.running
    finally:
        runtime.stop()


def test_osc_forwarding_can_be_toggled_while_running():
    runtime = BridgeRuntime(RuntimeConfig(listen_host="127.0.0.1", listen_port=0, forward_osc=False))
    assert runtime.forward_osc_enabled is False
    runtime.set_forward_osc(True)
    assert runtime.forward_osc_enabled is True


def test_real_udp_packet_forwards_required_three_digit_osc_in_order():
    osc_receiver = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    osc_receiver.bind(("127.0.0.1", 0))
    osc_receiver.settimeout(2)
    osc_port = osc_receiver.getsockname()[1]
    runtime = BridgeRuntime(
        RuntimeConfig(
            listen_host="127.0.0.1",
            listen_port=0,
            osc_host="127.0.0.1",
            osc_port=osc_port,
            forward_osc=False,
        )
    )
    runtime.start()
    runtime.set_forward_osc(True)
    client = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        payload = json.dumps(
            {"type": "heart_rate", "sequence": 78, "sampleEpochMillis": 1, "bpm": 142}
        ).encode()
        client.sendto(payload, ("127.0.0.1", runtime.bound_port))
        required = [_decode_osc_int(osc_receiver.recvfrom(1024)[0]) for _ in range(4)]
        assert required == [
            ("/avatar/parameters/HR_Value", 142),
            ("/avatar/parameters/HR_Hundreds", 1),
            ("/avatar/parameters/HR_Tens", 4),
            ("/avatar/parameters/HR_Ones", 2),
        ]
    finally:
        client.close()
        runtime.stop()
        osc_receiver.close()


def _decode_osc_int(packet):
    address_end = packet.index(b"\0")
    address = packet[:address_end].decode("ascii")
    type_tag_offset = (address_end + 4) & ~3
    assert packet[type_tag_offset : type_tag_offset + 2] == b",i"
    return address, struct.unpack(">i", packet[-4:])[0]
