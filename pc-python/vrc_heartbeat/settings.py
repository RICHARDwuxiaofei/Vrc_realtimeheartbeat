from __future__ import annotations

from dataclasses import asdict, dataclass
import json
import os
from pathlib import Path


@dataclass(slots=True)
class AppSettings:
    listen_port: int = 9123
    osc_port: int = 9000
    forward_osc: bool = True


def settings_path() -> Path:
    root = Path(os.environ.get("LOCALAPPDATA", Path.home())) / "VrcRealtimeHeartbeat"
    return root / "python-settings.json"


def load_settings(path: Path | None = None) -> AppSettings:
    target = path or settings_path()
    try:
        payload = json.loads(target.read_text(encoding="utf-8"))
        return AppSettings(
            listen_port=_port(payload.get("listen_port"), 9123),
            osc_port=_port(payload.get("osc_port"), 9000),
            forward_osc=bool(payload.get("forward_osc", True)),
        )
    except (OSError, ValueError, TypeError, json.JSONDecodeError):
        return AppSettings()


def save_settings(settings: AppSettings, path: Path | None = None) -> None:
    target = path or settings_path()
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(json.dumps(asdict(settings), ensure_ascii=False, indent=2), encoding="utf-8")


def _port(value: object, default: int) -> int:
    return value if type(value) is int and 1 <= value <= 65_535 else default
