from __future__ import annotations

from datetime import datetime
from pathlib import Path

from vrc_heartbeat.app_logging import AppFileLogger


TEST_ROOT = Path("build/app-logging-tests")


def test_logger_creates_daily_log_folder_and_appends() -> None:
    now = lambda: datetime(2026, 7, 29, 1, 2, 3, 456000)
    logs = TEST_ROOT / "append" / "logs"
    logger = AppFileLogger(logs, now=now)

    assert logger.info("应用启动")
    assert logger.warning("第一行\n第二行")

    target = logs / "vrc-heartbeat-2026-07-29.log"
    contents = target.read_text(encoding="utf-8")
    assert "2026-07-29 01:02:03.456 [INFO ] 应用启动" in contents
    assert "[WARN ] 第一行\n    第二行" in contents


def test_logger_removes_expired_daily_logs() -> None:
    logs = TEST_ROOT / "retention" / "logs"
    logs.mkdir(parents=True, exist_ok=True)
    (logs / "vrc-heartbeat-2026-07-01.log").write_text("old", encoding="utf-8")
    (logs / "vrc-heartbeat-2026-07-28.log").write_text("recent", encoding="utf-8")
    logger = AppFileLogger(
        logs,
        now=lambda: datetime(2026, 7, 29, 12, 0, 0),
        retention_days=14,
    )

    assert logger.error("boom")
    assert not (logs / "vrc-heartbeat-2026-07-01.log").exists()
    assert (logs / "vrc-heartbeat-2026-07-28.log").exists()
    assert (logs / "vrc-heartbeat-2026-07-29.log").exists()
