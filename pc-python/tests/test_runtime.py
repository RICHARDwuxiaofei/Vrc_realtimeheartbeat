import json
import socket
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
