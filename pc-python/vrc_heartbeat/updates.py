from __future__ import annotations

import json
from typing import Any
from urllib.request import Request, urlopen


LATEST_RELEASE_API = (
    "https://api.github.com/repos/RICHARDwuxiaofei/Vrc_realtimeheartbeat/releases/latest"
)


def version_tuple(value: str) -> tuple[int, ...]:
    clean = value.strip().lstrip("vV").split("-", 1)[0]
    try:
        return tuple(int(part) for part in clean.split("."))
    except ValueError:
        return ()


def is_newer_version(latest: str, current: str) -> bool:
    left = version_tuple(latest)
    right = version_tuple(current)
    if not left or not right:
        return False
    width = max(len(left), len(right))
    return left + (0,) * (width - len(left)) > right + (0,) * (width - len(right))


def fetch_latest_release(timeout: float = 4.0) -> dict[str, Any]:
    request = Request(
        LATEST_RELEASE_API,
        headers={
            "Accept": "application/vnd.github+json",
            "User-Agent": "VrcRealtimeHeartbeat-update-checker",
        },
    )
    with urlopen(request, timeout=timeout) as response:
        payload = json.loads(response.read().decode("utf-8"))
    return {
        "tag": str(payload.get("tag_name", "")),
        "url": str(payload.get("html_url", "")),
        "name": str(payload.get("name", "")),
    }
