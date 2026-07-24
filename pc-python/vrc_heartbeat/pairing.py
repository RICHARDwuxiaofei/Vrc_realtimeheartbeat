from __future__ import annotations

from dataclasses import dataclass
from urllib.parse import parse_qs, urlencode, urlparse


PAIR_SCHEME = "vrc-heartbeat"
PAIR_HOST = "pair"


@dataclass(frozen=True, slots=True)
class PairingTarget:
    host: str
    port: int


def build_pairing_uri(host: str, port: int) -> str:
    target = parse_pairing_target(host, port)
    return f"{PAIR_SCHEME}://{PAIR_HOST}?{urlencode({'host': target.host, 'port': target.port})}"


def parse_pairing_uri(value: str) -> PairingTarget:
    parsed = urlparse(value.strip())
    if parsed.scheme != PAIR_SCHEME or parsed.netloc != PAIR_HOST:
        raise ValueError("不是有效的 VRChat 心率桥配对码")
    query = parse_qs(parsed.query)
    host = query.get("host", [""])[0]
    raw_port = query.get("port", [""])[0]
    try:
        port = int(raw_port)
    except ValueError as exc:
        raise ValueError("配对码端口无效") from exc
    return parse_pairing_target(host, port)


def parse_pairing_target(host: str, port: int) -> PairingTarget:
    value = host.strip()
    parts = value.split(".")
    if len(parts) != 4:
        raise ValueError("配对地址必须是 IPv4")
    try:
        octets = [int(part) for part in parts]
    except ValueError as exc:
        raise ValueError("配对地址必须是 IPv4") from exc
    if any(not 0 <= octet <= 255 for octet in octets):
        raise ValueError("配对地址必须是 IPv4")
    if not 1 <= port <= 65_535:
        raise ValueError("配对端口无效")
    return PairingTarget(value, port)
