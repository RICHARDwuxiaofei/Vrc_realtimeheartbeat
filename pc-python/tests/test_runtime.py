import json
import socket
import struct
import threading
import time

from vrc_heartbeat.input_sources import XIAOMI_PC_BLE
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
        ack_payload = json.loads(ack)
        assert ack_payload["sequence"] == 77
        assert ack_payload["diagnosticMode"] is False
        assert received.wait(2)
        packet_event = next(data for kind, data in events if kind == "packet")
        assert packet_event["packet"].bpm == 72
        assert packet_event["sender"] == "127.0.0.1"
    finally:
        runtime.stop()


def test_udp_ack_requests_diagnostic_payload_only_after_toggle():
    runtime = BridgeRuntime(RuntimeConfig(listen_host="127.0.0.1", listen_port=0, forward_osc=False))
    runtime.start()
    runtime.set_diagnostic_mode(True)
    client = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    client.settimeout(2)
    try:
        payload = json.dumps(
            {"type": "phone_diagnostic", "sequence": 88, "sampleEpochMillis": 1, "bpm": 72}
        ).encode()
        client.sendto(payload, ("127.0.0.1", runtime.bound_port))
        ack, _ = client.recvfrom(1024)
        assert json.loads(ack)["diagnosticMode"] is True
    finally:
        client.close()
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


def test_direct_ble_mode_does_not_bind_udp_and_accepts_external_bpm():
    events = []
    runtime = BridgeRuntime(
        RuntimeConfig(input_source=XIAOMI_PC_BLE, listen_port=0, forward_osc=False),
        lambda kind, data: events.append((kind, data)),
    )
    runtime.start()
    try:
        assert runtime.running
        assert runtime.bound_port == 0
        assert runtime.accept_direct_heart_rate(
            83,
            int(time.time() * 1_000),
            "Xiaomi Smart Band 10",
            "AA:BB:CC:DD:EE:FF",
        )
        packet = next(data["packet"] for kind, data in events if kind == "packet")
        assert packet.bpm == 83
        assert packet.payload["source"] == "xiaomi_band_pc_ble"
        assert "sourceDeviceAddress" not in packet.payload
    finally:
        runtime.stop()


def test_direct_ble_diagnostic_mode_includes_device_and_profile():
    events = []
    runtime = BridgeRuntime(
        RuntimeConfig(input_source=XIAOMI_PC_BLE, listen_port=0, forward_osc=False),
        lambda kind, data: events.append((kind, data)),
    )
    runtime.start()
    runtime.set_diagnostic_mode(True)
    try:
        assert runtime.accept_direct_heart_rate(
            84,
            int(time.time() * 1_000),
            "Xiaomi Smart Band 10",
            "AA:BB:CC:DD:EE:FF",
        )
        packet = next(data["packet"] for kind, data in events if kind == "packet")
        assert packet.payload["sourceDeviceName"] == "Xiaomi Smart Band 10"
        assert packet.payload["sourceDeviceAddress"] == "AA:BB:CC:DD:EE:FF"
        assert packet.payload["bleServiceUuid"].startswith("0000180d")
        assert packet.payload["bleCharacteristicUuid"].startswith("00002a37")
    finally:
        runtime.stop()


def test_phone_mode_rejects_direct_ble_injection():
    runtime = BridgeRuntime(RuntimeConfig(listen_host="127.0.0.1", listen_port=0, forward_osc=False))
    runtime.start()
    try:
        assert not runtime.accept_direct_heart_rate(80, 1, "band", "address")
    finally:
        runtime.stop()


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
