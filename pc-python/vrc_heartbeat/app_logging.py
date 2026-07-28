from __future__ import annotations

from datetime import datetime, timedelta
from pathlib import Path
import threading
from typing import Callable

from .settings import app_data_dir


class AppFileLogger:
    """Small, dependency-free daily logger for the Windows desktop bridge."""

    def __init__(
        self,
        directory: Path | None = None,
        *,
        now: Callable[[], datetime] = datetime.now,
        retention_days: int = 14,
    ) -> None:
        self.directory = directory or app_data_dir() / "logs"
        self._now = now
        self.retention_days = max(1, retention_days)
        self._lock = threading.Lock()
        self.last_error = ""

    @property
    def path(self) -> Path:
        return self.directory / f"vrc-heartbeat-{self._now():%Y-%m-%d}.log"

    def info(self, message: str) -> bool:
        return self.write("INFO", message)

    def warning(self, message: str) -> bool:
        return self.write("WARN", message)

    def error(self, message: str) -> bool:
        return self.write("ERROR", message)

    def write(self, level: str, message: str) -> bool:
        timestamp = self._now()
        normalized = str(message).replace("\r\n", "\n").replace("\r", "\n")
        normalized = normalized.replace("\n", "\n    ")
        line = f"{timestamp:%Y-%m-%d %H:%M:%S.%f}"[:-3] + f" [{level.upper():5}] {normalized}\n"
        try:
            with self._lock:
                self.directory.mkdir(parents=True, exist_ok=True)
                target = self.directory / f"vrc-heartbeat-{timestamp:%Y-%m-%d}.log"
                with target.open("a", encoding="utf-8", newline="") as handle:
                    handle.write(line)
                    handle.flush()
                self._remove_expired_logs(timestamp)
            self.last_error = ""
            return True
        except OSError as exc:
            self.last_error = str(exc)
            return False

    def _remove_expired_logs(self, now: datetime) -> None:
        cutoff = (now - timedelta(days=self.retention_days)).date()
        for candidate in self.directory.glob("vrc-heartbeat-????-??-??.log"):
            try:
                date_text = candidate.stem.removeprefix("vrc-heartbeat-")
                if datetime.strptime(date_text, "%Y-%m-%d").date() < cutoff:
                    candidate.unlink()
            except (OSError, ValueError):
                continue
