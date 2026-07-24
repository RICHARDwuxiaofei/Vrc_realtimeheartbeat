from __future__ import annotations

from collections import deque
from dataclasses import dataclass
from time import time


@dataclass(frozen=True, slots=True)
class HeartRateSample:
    epoch_ms: int
    bpm: int
    sender: str
    latency_ms: int


@dataclass(frozen=True, slots=True)
class HeartRateStats:
    minimum: int
    maximum: int
    average: float
    count: int


class HeartRateHistory:
    def __init__(self, window_ms: int = 10 * 60 * 1_000) -> None:
        self.window_ms = window_ms
        self._samples: deque[HeartRateSample] = deque()

    def add(self, sample: HeartRateSample) -> None:
        self._samples.append(sample)
        self.prune(sample.epoch_ms)

    def prune(self, now_ms: int | None = None) -> None:
        current = now_ms if now_ms is not None else int(time() * 1_000)
        cutoff = current - self.window_ms
        while self._samples and self._samples[0].epoch_ms < cutoff:
            self._samples.popleft()

    def samples(self, now_ms: int | None = None) -> tuple[HeartRateSample, ...]:
        self.prune(now_ms)
        return tuple(self._samples)

    def stats(self, now_ms: int | None = None) -> HeartRateStats | None:
        samples = self.samples(now_ms)
        if not samples:
            return None
        values = [sample.bpm for sample in samples]
        return HeartRateStats(min(values), max(values), sum(values) / len(values), len(values))
