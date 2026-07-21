from __future__ import annotations

from dataclasses import dataclass
import json
from typing import Any


class ProtocolError(ValueError):
    """Raised when an incoming phone packet is malformed or unsafe."""


@dataclass(frozen=True, slots=True)
class HeartRatePacket:
    packet_type: str
    sequence: int
    sample_epoch_ms: int
    bpm: int
    forward_interval_seconds: int
    payload: dict[str, Any]

    @property
    def is_real_heart_rate(self) -> bool:
        return self.packet_type == "heart_rate"


def _required_int(payload: dict[str, Any], name: str) -> int:
    value = payload.get(name)
    if type(value) is not int:
        raise ProtocolError(f"字段 {name} 必须是整数")
    return value


def parse_packet(data: bytes | str) -> HeartRatePacket:
    try:
        text = data.decode("utf-8") if isinstance(data, bytes) else data
    except UnicodeDecodeError as exc:
        raise ProtocolError("数据包不是有效 UTF-8") from exc

    try:
        payload = json.loads(text)
    except (json.JSONDecodeError, TypeError) as exc:
        raise ProtocolError("数据包不是有效 JSON") from exc
    if not isinstance(payload, dict):
        raise ProtocolError("JSON 顶层必须是对象")

    sequence = _required_int(payload, "sequence")
    sample_epoch_ms = _required_int(payload, "sampleEpochMillis")
    bpm = _required_int(payload, "bpm")
    packet_type = payload.get("type", "")
    if not isinstance(packet_type, str) or not packet_type:
        raise ProtocolError("字段 type 必须是非空字符串")
    if sequence < 0:
        raise ProtocolError("sequence 不能为负数")
    if sample_epoch_ms <= 0:
        raise ProtocolError("sampleEpochMillis 必须大于 0")
    if not 1 <= bpm <= 300:
        raise ProtocolError(f"BPM 超出有效范围：{bpm}")

    interval = payload.get("phoneForwardIntervalSeconds", 5)
    if type(interval) is not int:
        interval = 5
    interval = max(1, min(30, interval))
    return HeartRatePacket(
        packet_type=packet_type,
        sequence=sequence,
        sample_epoch_ms=sample_epoch_ms,
        bpm=bpm,
        forward_interval_seconds=interval,
        payload=payload,
    )


def build_ack(sequence: int, now_ms: int) -> bytes:
    return json.dumps(
        {"type": "pc_ack", "sequence": sequence, "pcEpochMillis": now_ms},
        ensure_ascii=False,
        separators=(",", ":"),
    ).encode("utf-8")


def packet_latency_ms(packet: HeartRatePacket, now_ms: int) -> int:
    return max(0, now_ms - packet.sample_epoch_ms)


def stale_timeout_ms(forward_interval_seconds: int) -> int:
    seconds = max(1, min(30, forward_interval_seconds))
    return max(10_000, seconds * 2_500)


def split_two_digits(bpm: int) -> tuple[int, int]:
    value = max(0, min(99, bpm))
    return divmod(value, 10)


def normalized_heart_rate(bpm: int) -> float:
    return min(1.0, max(0.0, (bpm - 40.0) / 160.0))
