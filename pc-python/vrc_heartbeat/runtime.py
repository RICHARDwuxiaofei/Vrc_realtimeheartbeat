from __future__ import annotations

from dataclasses import dataclass
import socket
import threading
import time
from typing import Any, Callable

from .engine import BridgeEngine
from .osc import encode_message
from .protocol import ProtocolError, build_ack, packet_latency_ms, parse_packet


EventCallback = Callable[[str, dict[str, Any]], None]


@dataclass(frozen=True, slots=True)
class RuntimeConfig:
    listen_host: str = "0.0.0.0"
    listen_port: int = 9123
    osc_host: str = "127.0.0.1"
    osc_port: int = 9000
    forward_osc: bool = True


class BridgeRuntime:
    def __init__(self, config: RuntimeConfig, on_event: EventCallback | None = None) -> None:
        self.config = config
        self._on_event = on_event or (lambda _kind, _data: None)
        self._receiver: socket.socket | None = None
        self._osc_socket = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        self._stop = threading.Event()
        self._thread: threading.Thread | None = None
        self._forward_osc = config.forward_osc
        self._engine = BridgeEngine(self._send_osc)
        self.bound_port = 0

    @property
    def running(self) -> bool:
        return self._thread is not None and self._thread.is_alive()

    @property
    def forward_osc_enabled(self) -> bool:
        return self._forward_osc

    def start(self) -> None:
        if self.running:
            return
        receiver = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        try:
            receiver.bind((self.config.listen_host, self.config.listen_port))
        except Exception:
            receiver.close()
            raise
        receiver.settimeout(0.05)
        self._receiver = receiver
        self.bound_port = int(receiver.getsockname()[1])
        self._stop.clear()
        self._engine.start()
        self._thread = threading.Thread(target=self._run, name="heart-rate-udp", daemon=True)
        self._thread.start()
        self._emit("listening", port=self.bound_port)

    def set_forward_osc(self, enabled: bool) -> None:
        self._forward_osc = bool(enabled)

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
        self._engine.stop()
        self._osc_socket.close()
        self._emit("stopped")

    def _run(self) -> None:
        while not self._stop.is_set():
            receiver = self._receiver
            if receiver is None:
                break
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
                receiver.sendto(build_ack(packet.sequence, now_ms), sender)
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
