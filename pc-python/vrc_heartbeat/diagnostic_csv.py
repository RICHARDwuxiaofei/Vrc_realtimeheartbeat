from __future__ import annotations

import csv
from pathlib import Path
import shutil
from typing import Any, TextIO

from .analytics import HeartRateSample
from .settings import settings_path


FIELDNAMES = [
    "timestamp",
    "epoch_ms",
    "bpm",
    "phone_ip",
    "latency_ms",
    "raw_bpm",
    "accuracy",
    "watch_battery_percent",
    "watch_screen_interactive",
    "watch_relay_mode",
    "watch_relay_interval_seconds",
    "watch_received_epoch_ms",
    "phone_received_epoch_ms",
    "phone_local_ip",
    "phone_network_type",
    "phone_vpn_active",
]


class DiagnosticCsvStore:
    """Disk-backed diagnostic history; append and read handles remain independent."""

    def __init__(self, path: Path | None = None) -> None:
        self.path = path or settings_path().parent / "diagnostic-current.csv"
        self._handle: TextIO | None = None
        self._writer: csv.DictWriter | None = None
        self.row_count = 0
        self.exported_row_count = 0

    @property
    def active(self) -> bool:
        return self._handle is not None

    @property
    def has_unexported_rows(self) -> bool:
        return self.row_count > self.exported_row_count

    def begin(self) -> None:
        self.stop()
        self.path.parent.mkdir(parents=True, exist_ok=True)
        self._handle = self.path.open("w", newline="", encoding="utf-8")
        self._writer = csv.DictWriter(self._handle, fieldnames=FIELDNAMES)
        self._writer.writeheader()
        self._handle.flush()
        self.row_count = 0
        self.exported_row_count = 0

    def resume(self) -> None:
        if self.active:
            return
        if not self.path.exists():
            self.begin()
            return
        self._handle = self.path.open("a", newline="", encoding="utf-8")
        self._writer = csv.DictWriter(self._handle, fieldnames=FIELDNAMES)

    def append(
        self,
        sample: HeartRateSample,
        payload: dict[str, Any],
        timestamp: str,
    ) -> None:
        if self._writer is None or self._handle is None:
            return
        self._writer.writerow(
            {
                "timestamp": timestamp,
                "epoch_ms": sample.epoch_ms,
                "bpm": sample.bpm,
                "phone_ip": sample.sender,
                "latency_ms": sample.latency_ms,
                "raw_bpm": payload.get("rawBpm", ""),
                "accuracy": payload.get("accuracy", ""),
                "watch_battery_percent": payload.get("watchBatteryPercent", ""),
                "watch_screen_interactive": payload.get("watchScreenInteractive", ""),
                "watch_relay_mode": payload.get("watchRelayMode", ""),
                "watch_relay_interval_seconds": payload.get("watchRelayIntervalSeconds", ""),
                "watch_received_epoch_ms": payload.get("watchReceivedEpochMillis", ""),
                "phone_received_epoch_ms": payload.get("phoneReceivedEpochMillis", ""),
                "phone_local_ip": payload.get("phoneLocalIp", ""),
                "phone_network_type": payload.get("phoneNetworkType", ""),
                "phone_vpn_active": payload.get("phoneVpnActive", ""),
            }
        )
        self._handle.flush()
        self.row_count += 1

    def read_window(self, now_ms: int, minutes: int) -> tuple[HeartRateSample, ...]:
        if not self.path.exists():
            return ()
        cutoff = now_ms - max(1, min(10, minutes)) * 60_000
        samples: list[HeartRateSample] = []
        try:
            with self.path.open("r", newline="", encoding="utf-8") as handle:
                for row in csv.DictReader(handle):
                    try:
                        epoch_ms = int(row["epoch_ms"])
                        if epoch_ms < cutoff:
                            continue
                        samples.append(
                            HeartRateSample(
                                epoch_ms=epoch_ms,
                                bpm=int(row["bpm"]),
                                sender=row["phone_ip"],
                                latency_ms=int(row["latency_ms"]),
                            )
                        )
                    except (KeyError, TypeError, ValueError):
                        continue
        except OSError:
            return ()
        return tuple(samples)

    def export(self, destination: Path) -> None:
        if self._handle is not None:
            self._handle.flush()
        shutil.copyfile(self.path, destination)
        self.exported_row_count = self.row_count

    def stop(self) -> None:
        handle, self._handle = self._handle, None
        self._writer = None
        if handle is not None:
            handle.close()
