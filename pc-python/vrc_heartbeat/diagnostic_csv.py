from __future__ import annotations

import csv
from pathlib import Path
import shutil
from typing import Any, Iterator, TextIO

from .analytics import HeartRateSample
from .settings import settings_path


FIELDNAMES = [
    "timestamp",
    "epoch_ms",
    "bpm",
    "source",
    "source_device_name",
    "source_device_address",
    "pc_direct_ble",
    "ble_service_uuid",
    "ble_characteristic_uuid",
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
TAIL_READ_CHUNK_BYTES = 64 * 1_024


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
                "source": payload.get("source", ""),
                "source_device_name": payload.get("sourceDeviceName", ""),
                "source_device_address": payload.get("sourceDeviceAddress", ""),
                "pc_direct_ble": payload.get("pcDirectBle", ""),
                "ble_service_uuid": payload.get("bleServiceUuid", ""),
                "ble_characteristic_uuid": payload.get("bleCharacteristicUuid", ""),
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
            for line in self._data_lines_from_newest():
                try:
                    row = next(csv.DictReader([line], fieldnames=FIELDNAMES))
                    epoch_ms = int(row["epoch_ms"])
                    if epoch_ms < cutoff:
                        break
                    samples.append(
                        HeartRateSample(
                            epoch_ms=epoch_ms,
                            bpm=int(row["bpm"]),
                            sender=row["phone_ip"],
                            latency_ms=int(row["latency_ms"]),
                        )
                    )
                except (csv.Error, KeyError, StopIteration, TypeError, ValueError):
                    continue
        except (OSError, UnicodeError):
            return ()
        samples.reverse()
        return tuple(samples)

    def _data_lines_from_newest(self) -> Iterator[str]:
        """Yield complete CSV rows from the file tail without scanning old history."""
        with self.path.open("rb") as handle:
            handle.seek(0, 2)
            position = handle.tell()
            carry = b""
            while position > 0:
                read_size = min(TAIL_READ_CHUNK_BYTES, position)
                position -= read_size
                handle.seek(position)
                block = handle.read(read_size) + carry
                lines = block.splitlines()
                if position > 0:
                    carry = lines.pop(0) if lines else block
                else:
                    carry = b""
                for raw_line in reversed(lines):
                    if not raw_line:
                        continue
                    line = raw_line.decode("utf-8")
                    if line == ",".join(FIELDNAMES):
                        return
                    yield line

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
