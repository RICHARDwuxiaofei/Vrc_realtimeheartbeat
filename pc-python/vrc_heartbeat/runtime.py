from __future__ import annotations

from dataclasses import dataclass
import socket
import threading
import time
from typing import Any, Callable

from .engine import BridgeEngine
from .input_sources import PHONE_RELAY, XIAOMI_PC_BLE
from .osc import encode_message
from .protocol import HeartRatePacket, ProtocolError, build_ack, packet_latency_ms, parse_packet


EventCallback = Callable[[str, dict[str, Any]], None]


@dataclass(frozen=True, slots=True)
class RuntimeConfig:
    listen_host: str = "0.0.0.0"
    listen_port: int = 9123
    osc_host: str = "127.0.0.1"
    osc_port: int = 9000
    forward_osc: bool = True
    input_source: str = PHONE_RELAY


class BridgeRuntime:
    def __init__(self, config: RuntimeConfig, on_event: EventCallback | None = None) -> None:
        self.config = config
        self._on_event = on_event or (lambda _kind, _data: None)
        self._receiver: socket.socket | None = None
        self._osc_socket = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        self._stop = threading.Event()
        self._thread: threading.Thread | None = None
        self._forward_osc = config.forward_osc
        self._diagnostic_mode = False
        self._engine = BridgeEngine(self._send_osc)
        self._engine_lock = threading.RLock()
        self.bound_port = 0
        self._direct_sequence = time.time_ns() // 1_000_000

    @property
    def running(self) -> bool:
        return self._thread is not None and self._thread.is_alive()

    @property
    def forward_osc_enabled(self) -> bool:
        return self._forward_osc

    def start(self) -> None:
        if self.running:
            return
        if self.config.input_source == PHONE_RELAY:
            receiver = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            try:
                receiver.bind((self.config.listen_host, self.config.listen_port))
            except Exception:
                receiver.close()
                raise
            receiver.settimeout(0.05)
            self._receiver = receiver
            self.bound_port = int(receiver.getsockname()[1])
        elif self.config.input_source == XIAOMI_PC_BLE:
            self._receiver = None
            self.bound_port = 0
        else:
            raise ValueError(f"未知心率来源：{self.config.input_source}")
        self._stop.clear()
        with self._engine_lock:
            self._engine.start()
        self._thread = threading.Thread(target=self._run, name="heart-rate-udp", daemon=True)
        self._thread.start()
        if self.config.input_source == PHONE_RELAY:
            self._emit("listening", port=self.bound_port)
        else:
            self._emit("direct_ready")

    def set_forward_osc(self, enabled: bool) -> None:
        self._forward_osc = bool(enabled)

    def set_diagnostic_mode(self, enabled: bool) -> None:
        self._diagnostic_mode = bool(enabled)

    def stop(self) -> None:
        self._stop.set()
        receiver = self._receiver
        if receiver is not None:
            receiver.close()
        thread = self._thread
        if thread is not None and thread is not threading.current_thread():
            thread.join(timeout=2.0)
        self._thread = None
        self._receiver = None
        with self._engine_lock:
            self._engine.stop()
        self._osc_socket.close()
        self._emit("stopped")

    def send_avatar_test(self, bpm: int = 123) -> bool:
        if not self._forward_osc or not self.running:
            return False
        with self._engine_lock:
            self._engine.start_avatar_test(bpm)

        def finish() -> None:
            if self._stop.wait(0.18):
                return
            with self._engine_lock:
                self._engine.finish_avatar_test()

        threading.Thread(target=finish, name="avatar-test-pulse", daemon=True).start()
        return True

    def accept_direct_heart_rate(
        self,
        bpm: int,
        sample_epoch_ms: int,
        device_name: str,
        device_address: str,
    ) -> bool:
        if (
            not self.running
            or self.config.input_source != XIAOMI_PC_BLE
            or not 1 <= bpm <= 300
            or sample_epoch_ms <= 0
        ):
            return False
        self._direct_sequence += 1
        payload: dict[str, Any] = {
            "version": 1,
            "type": "heart_rate",
            "source": "xiaomi_band_pc_ble",
            "sequence": self._direct_sequence,
            "sampleEpochMillis": sample_epoch_ms,
            "bpm": bpm,
            "phoneForwardIntervalSeconds": 1,
            "pcDirectBle": True,
        }
        if self._diagnostic_mode:
            payload.update(
                {
                    "sourceDeviceName": device_name,
                    "sourceDeviceAddress": device_address,
                    "bleServiceUuid": "0000180d-0000-1000-8000-00805f9b34fb",
                    "bleCharacteristicUuid": "00002a37-0000-1000-8000-00805f9b34fb",
                }
            )
        packet = HeartRatePacket(
            packet_type="heart_rate",
            sequence=self._direct_sequence,
            sample_epoch_ms=sample_epoch_ms,
            bpm=bpm,
            forward_interval_seconds=1,
            payload=payload,
        )
        now_ms = _now_ms()
        with self._engine_lock:
            result = self._engine.accept(packet, now_ms)
        self._emit(
            "packet",
            packet=packet,
            result=result.kind,
            sender=device_name or "Windows BLE",
            latency_ms=packet_latency_ms(packet, now_ms),
        )
        return True

    def _run(self) -> None:
        while not self._stop.is_set():
            receiver = self._receiver
            if receiver is None:
                self._stop.wait(0.05)
                self._tick()
                continue
            try:
                data, sender = receiver.recvfrom(65_535)
            except socket.timeout:
                self._tick()
                continue
            except OSError as exc:
                if not self._stop.is_set():
                    self._emit("error", message=f"UDP 接收失败：{exc}")
                break

            now_ms = _now_ms()
            try:
                packet = parse_packet(data)
                receiver.sendto(build_ack(packet.sequence, now_ms, self._diagnostic_mode), sender)
                with self._engine_lock:
                    result = self._engine.accept(packet, now_ms)
                self._emit(
                    "packet",
                    packet=packet,
                    result=result.kind,
                    sender=sender[0],
                    latency_ms=packet_latency_ms(packet, now_ms),
                )
            except ProtocolError as exc:
                self._emit("invalid_packet", sender=sender[0], message=str(exc))
            except OSError as exc:
                self._emit("error", message=f"UDP 回执失败：{exc}")
            except Exception as exc:  # keep the receiver alive after one bad packet
                self._emit("error", message=f"处理数据包失败：{exc}")
            self._tick()

    def _tick(self) -> None:
        with self._engine_lock:
            result = self._engine.tick(_now_ms())
        if result is not None and result.kind == "stale":
            self._emit("stale", timeout_ms=self._engine.timeout_ms)

    def _send_osc(self, address: str, value: bool | int | float) -> None:
        if not self._forward_osc:
            return
        packet = encode_message(address, value)
        self._osc_socket.sendto(packet, (self.config.osc_host, self.config.osc_port))

    def _emit(self, kind: str, **data: Any) -> None:
        try:
            self._on_event(kind, data)
        except Exception:
            pass


def _now_ms() -> int:
    return time.time_ns() // 1_000_000
