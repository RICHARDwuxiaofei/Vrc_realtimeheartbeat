from __future__ import annotations

from dataclasses import dataclass
from typing import Callable

from .protocol import (
    HeartRatePacket,
    clamp_osc_bpm,
    normalized_heart_rate,
    split_three_digits,
    stale_timeout_ms,
)


OscSender = Callable[[str, bool | int | float], None]


@dataclass(frozen=True, slots=True)
class EngineResult:
    kind: str
    packet: HeartRatePacket | None = None


class BridgeEngine:
    """Pure bridge state machine; networking and UI live elsewhere."""

    PULSE_WIDTH_MS = 120

    def __init__(self, send_osc: OscSender) -> None:
        self._send_osc = send_osc
        self.current_bpm = 0
        self.last_real_packet_ms = 0
        self.timeout_ms = stale_timeout_ms(5)
        self.valid = False
        self._valid_sent: bool | None = None
        self._pulse_active = False
        self._pulse_off_ms = 0
        self._next_pulse_ms = 0

    def start(self) -> None:
        self._set_valid(False)

    def accept(self, packet: HeartRatePacket, now_ms: int) -> EngineResult:
        if not packet.is_real_heart_rate:
            return EngineResult("diagnostic", packet)

        self.current_bpm = packet.bpm
        self.last_real_packet_ms = now_ms
        self.timeout_ms = stale_timeout_ms(packet.forward_interval_seconds)
        self._send_heart_rate(packet.bpm)
        self._set_valid(True)
        if self._next_pulse_ms == 0:
            self._next_pulse_ms = now_ms
        return EngineResult("heart_rate", packet)

    def tick(self, now_ms: int) -> EngineResult | None:
        if (
            self.last_real_packet_ms > 0
            and now_ms - self.last_real_packet_ms >= self.timeout_ms
            and self.valid
        ):
            self._set_valid(False)
            self._stop_pulse()
            return EngineResult("stale")

        if not self.valid or self.current_bpm <= 0:
            return None
        if self._pulse_active and now_ms >= self._pulse_off_ms:
            self._send_osc("/avatar/parameters/HRPulse", False)
            self._pulse_active = False
        if not self._pulse_active and now_ms >= self._next_pulse_ms:
            self._send_osc("/avatar/parameters/HRPulse", True)
            self._pulse_active = True
            self._pulse_off_ms = now_ms + self.PULSE_WIDTH_MS
            self._next_pulse_ms = now_ms + max(250, 60_000 // self.current_bpm)
        return None

    def stop(self) -> None:
        self._set_valid(False)
        self._stop_pulse()
        self.last_real_packet_ms = 0

    def _send_heart_rate(self, bpm: int) -> None:
        value = clamp_osc_bpm(bpm)
        hundreds, tens, ones = split_three_digits(value)

        # HeartRateDisplay_OSC_3Digit requires these Int32 messages in this order.
        self._send_osc("/avatar/parameters/HR_Value", value)
        self._send_osc("/avatar/parameters/HR_Hundreds", hundreds)
        self._send_osc("/avatar/parameters/HR_Tens", tens)
        self._send_osc("/avatar/parameters/HR_Ones", ones)

        # Preserve compatibility with avatars configured for the earlier bridge.
        self._send_osc("/avatar/parameters/HeartRate", value)
        self._send_osc("/avatar/parameters/HeartRateNormalized", normalized_heart_rate(value))

    def _set_valid(self, valid: bool) -> None:
        if self._valid_sent is valid:
            self.valid = valid
            return
        self._send_osc("/avatar/parameters/HeartRateValid", valid)
        self._send_osc("/avatar/parameters/HRValid", valid)
        self._valid_sent = valid
        self.valid = valid

    def _stop_pulse(self) -> None:
        if self._pulse_active or self._next_pulse_ms > 0:
            self._send_osc("/avatar/parameters/HRPulse", False)
        self._pulse_active = False
        self._pulse_off_ms = 0
        self._next_pulse_ms = 0
        self.current_bpm = 0
