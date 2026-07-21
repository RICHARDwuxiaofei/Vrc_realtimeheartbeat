from __future__ import annotations

import struct
from typing import TypeAlias


OscValue: TypeAlias = bool | int | float


def _padded_string(value: str) -> bytes:
    raw = value.encode("utf-8") + b"\0"
    return raw + (b"\0" * ((-len(raw)) % 4))


def encode_message(address: str, value: OscValue) -> bytes:
    if not address.startswith("/"):
        raise ValueError("OSC address must start with '/'")
    if type(value) is bool:
        return _padded_string(address) + _padded_string(",T" if value else ",F")
    if type(value) is int:
        return _padded_string(address) + _padded_string(",i") + struct.pack(">i", value)
    if type(value) is float:
        return _padded_string(address) + _padded_string(",f") + struct.pack(">f", value)
    raise TypeError(f"Unsupported OSC value: {type(value).__name__}")
