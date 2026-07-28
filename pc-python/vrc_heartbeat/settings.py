from __future__ import annotations

from dataclasses import asdict, dataclass
import json
import os
from pathlib import Path

from .input_sources import PHONE_RELAY, normalize_input_source
from .i18n import SYSTEM, normalize_language


@dataclass(slots=True)
class AppSettings:
    listen_port: int = 9123
    osc_port: int = 9000
    forward_osc: bool = True
    input_source: str = PHONE_RELAY
    ble_address: str = ""
    ble_name: str = ""
    language: str = SYSTEM


def app_data_dir() -> Path:
    return Path(os.environ.get("LOCALAPPDATA", Path.home())) / "VrcRealtimeHeartbeat"


def settings_path() -> Path:
    return app_data_dir() / "python-settings.json"


def load_settings(path: Path | None = None) -> AppSettings:
    target = path or settings_path()
    try:
        payload = json.loads(target.read_text(encoding="utf-8"))
        return AppSettings(
            listen_port=_port(payload.get("listen_port"), 9123),
            osc_port=_port(payload.get("osc_port"), 9000),
            forward_osc=bool(payload.get("forward_osc", True)),
            input_source=normalize_input_source(payload.get("input_source")),
            ble_address=_safe_text(payload.get("ble_address")),
            ble_name=_safe_text(payload.get("ble_name")),
            language=normalize_language(payload.get("language")),
        )
    except (OSError, ValueError, TypeError, json.JSONDecodeError):
        return AppSettings()


def save_settings(settings: AppSettings, path: Path | None = None) -> None:
    target = path or settings_path()
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(json.dumps(asdict(settings), ensure_ascii=False, indent=2), encoding="utf-8")


def _port(value: object, default: int) -> int:
    return value if type(value) is int and 1 <= value <= 65_535 else default


def _safe_text(value: object) -> str:
    return value.strip()[:200] if isinstance(value, str) else ""
