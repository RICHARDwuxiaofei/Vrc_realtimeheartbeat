from __future__ import annotations

import asyncio
import sys
import threading
import time
from typing import Any, Callable

try:
    from bleak import BleakClient as _BleakClient
    from bleak import BleakScanner as _BleakScanner
except ImportError:  # Regular phone-relay mode must still start without BLE extras.
    _BleakClient = None
    _BleakScanner = None


HEART_RATE_SERVICE_UUID = "0000180d-0000-1000-8000-00805f9b34fb"
HEART_RATE_MEASUREMENT_UUID = "00002a37-0000-1000-8000-00805f9b34fb"
BleEventCallback = Callable[[str, dict[str, Any]], None]


def ble_dependency_available() -> bool:
    """Return whether the packaged BLE client and scanner imports succeeded."""
    return _BleakClient is not None and _BleakScanner is not None


def ble_scan_self_test(timeout: float = 3.0) -> int:
    """Exercise the packaged WinRT scanner without connecting to any device."""
    if sys.platform != "win32":
        raise RuntimeError("Windows BLE self-test requires Windows")
    if _BleakScanner is None:
        raise RuntimeError("BLE scanner dependency is unavailable")

    async def discover() -> int:
        devices = await _BleakScanner.discover(
            timeout=timeout,
            return_adv=True,
            service_uuids=[HEART_RATE_SERVICE_UUID],
        )
        return len(devices)

    return asyncio.run(discover())


def parse_heart_rate_measurement(value: bytes | bytearray) -> int | None:
    """Parse Bluetooth SIG Heart Rate Measurement (0x2A37)."""
    if len(value) < 2:
        return None
    is_uint16 = value[0] & 0x01 != 0
    if is_uint16:
        if len(value) < 3:
            return None
        bpm = value[1] | value[2] << 8
    else:
        bpm = value[1]
    return bpm if 1 <= bpm <= 300 else None


class BleHeartRateClient:
    """Bleak/WinRT client running on an isolated asyncio thread."""

    def __init__(self, on_event: BleEventCallback | None = None) -> None:
        self._on_event = on_event or (lambda _kind, _data: None)
        self._loop: asyncio.AbstractEventLoop | None = None
        self._thread: threading.Thread | None = None
        self._ready = threading.Event()
        self._client: Any = None
        self._devices: dict[str, Any] = {}
        self._device_names: dict[str, str] = {}
        self._wanted_address = ""
        self._wanted_name = ""
        self._stopping = False
        self._scan_lock: asyncio.Lock | None = None
        self._connect_lock: asyncio.Lock | None = None
        self._reconnect_generation = 0
        self._intentional_clients: set[int] = set()

    @property
    def running(self) -> bool:
        return self._thread is not None and self._thread.is_alive()

    def start(self) -> bool:
        if self.running:
            return True
        if sys.platform != "win32":
            self._emit("ble_error", message="电脑 BLE 直连目前只支持 Windows")
            return False
        if _BleakClient is None or _BleakScanner is None:
            self._emit("ble_error", message="缺少 BLE 组件，请安装最新版电脑端")
            return False
        self._stopping = False
        self._ready.clear()
        self._thread = threading.Thread(target=self._thread_main, name="xiaomi-ble-winrt", daemon=True)
        self._thread.start()
        if not self._ready.wait(3):
            self._emit("ble_error", message="Windows BLE 线程启动超时")
            return False
        return True

    def scan(self, auto_connect_address: str = "") -> bool:
        if not self.start():
            return False
        self._submit(self._scan(auto_connect_address))
        return True

    def connect(self, address: str, name: str = "") -> bool:
        if not address or not self.start():
            return False
        self._submit(self._connect(address, name))
        return True

    def disconnect(self) -> None:
        if self._loop is not None:
            self._submit(self._disconnect_current(clear_wanted=True))

    def stop(self) -> None:
        loop = self._loop
        thread = self._thread
        if loop is None or thread is None:
            return
        self._stopping = True
        try:
            future = asyncio.run_coroutine_threadsafe(self._shutdown(), loop)
            future.result(timeout=5)
        except Exception:
            pass
        loop.call_soon_threadsafe(loop.stop)
        if thread is not threading.current_thread():
            thread.join(timeout=5)
        self._thread = None
        self._loop = None
        self._ready.clear()

    def _thread_main(self) -> None:
        loop = asyncio.new_event_loop()
        asyncio.set_event_loop(loop)
        self._loop = loop
        self._scan_lock = asyncio.Lock()
        self._connect_lock = asyncio.Lock()
        self._ready.set()
        try:
            loop.run_forever()
        finally:
            pending = asyncio.all_tasks(loop)
            for task in pending:
                task.cancel()
            if pending:
                loop.run_until_complete(asyncio.gather(*pending, return_exceptions=True))
            loop.close()

    def _submit(self, coroutine: Any) -> None:
        loop = self._loop
        if loop is None:
            coroutine.close()
            return
        future = asyncio.run_coroutine_threadsafe(coroutine, loop)

        def report_failure(done: Any) -> None:
            try:
                done.result()
            except asyncio.CancelledError:
                pass
            except Exception as exc:
                self._emit("ble_error", message=f"BLE 操作失败：{exc}")

        future.add_done_callback(report_failure)

    async def _scan(self, auto_connect_address: str = "") -> None:
        assert self._scan_lock is not None
        assert _BleakScanner is not None
        self._reconnect_generation += 1
        async with self._scan_lock:
            self._emit("ble_status", message="正在扫描标准心率设备…", connected=False, scanning=True)
            try:
                discovered = await _BleakScanner.discover(
                    timeout=8.0,
                    return_adv=True,
                    service_uuids=[HEART_RATE_SERVICE_UUID],
                )
            except Exception as exc:
                self._emit("ble_error", message=f"Windows BLE 扫描失败：{exc}")
                return

            devices: list[dict[str, Any]] = []
            for device, advertisement in discovered.values():
                advertised_services = {item.lower() for item in (advertisement.service_uuids or [])}
                if HEART_RATE_SERVICE_UUID not in advertised_services:
                    continue
                address = str(device.address)
                name = str(device.name or advertisement.local_name or "BLE 心率设备")
                self._devices[address] = device
                self._device_names[address] = name
                devices.append(
                    {
                        "address": address,
                        "name": name,
                        "rssi": int(advertisement.rssi),
                    }
                )
            devices.sort(key=lambda item: item["rssi"], reverse=True)
            self._emit("ble_devices", devices=devices)
            if not devices:
                self._emit(
                    "ble_status",
                    message="未找到心率广播；请在手环开启“共享心率”",
                    connected=False,
                    scanning=False,
                )
                return
            self._emit(
                "ble_status",
                message=f"找到 {len(devices)} 个心率设备",
                connected=False,
                scanning=False,
            )

        if auto_connect_address and auto_connect_address in self._devices:
            await self._connect(
                auto_connect_address,
                self._device_names.get(auto_connect_address, ""),
            )

    async def _connect(self, address: str, name: str = "") -> None:
        assert self._connect_lock is not None
        assert _BleakClient is not None
        async with self._connect_lock:
            device = self._devices.get(address)
            if device is None:
                self._emit("ble_error", message="设备不在本次扫描结果中，请重新扫描")
                return
            await self._disconnect_current(clear_wanted=False)
            self._reconnect_generation += 1
            generation = self._reconnect_generation
            self._wanted_address = address
            self._wanted_name = name or self._device_names.get(address, "小米手环")
            self._emit(
                "ble_status",
                message=f"正在连接 {self._wanted_name}…",
                connected=False,
                scanning=False,
            )
            client = _BleakClient(device, disconnected_callback=self._on_disconnected)
            self._client = client
            try:
                await client.connect(timeout=20)
                characteristic = client.services.get_characteristic(HEART_RATE_MEASUREMENT_UUID)
                if characteristic is None:
                    raise RuntimeError("设备没有标准心率特征 0x2A37")
                await client.start_notify(characteristic, self._on_notification)
            except Exception:
                self._intentional_clients.add(id(client))
                try:
                    await client.disconnect()
                except Exception:
                    pass
                if self._client is client:
                    self._client = None
                raise
            if generation != self._reconnect_generation:
                return
            self._emit(
                "ble_status",
                message=f"已连接 {self._wanted_name}，等待心率",
                connected=True,
                scanning=False,
                address=address,
                name=self._wanted_name,
            )

    async def _disconnect_current(self, clear_wanted: bool) -> None:
        self._reconnect_generation += 1
        if clear_wanted:
            self._wanted_address = ""
            self._wanted_name = ""
        client, self._client = self._client, None
        if client is None:
            return
        self._intentional_clients.add(id(client))
        try:
            if client.is_connected:
                try:
                    await client.stop_notify(HEART_RATE_MEASUREMENT_UUID)
                except Exception:
                    pass
                await client.disconnect()
        except Exception:
            pass

    async def _shutdown(self) -> None:
        self._stopping = True
        await self._disconnect_current(clear_wanted=True)
        self._devices.clear()
        self._device_names.clear()

    def _on_notification(self, _characteristic: Any, value: bytearray) -> None:
        bpm = parse_heart_rate_measurement(value)
        if bpm is None:
            self._emit("ble_warning", message="忽略了一条无效的标准心率数据")
            return
        self._emit(
            "ble_heart_rate",
            bpm=bpm,
            sample_epoch_ms=time.time_ns() // 1_000_000,
            address=self._wanted_address,
            name=self._wanted_name or "小米手环",
        )

    def _on_disconnected(self, client: Any) -> None:
        client_id = id(client)
        if client_id in self._intentional_clients:
            self._intentional_clients.discard(client_id)
            return
        if self._client is client:
            self._client = None
        address = self._wanted_address
        if self._stopping or not address:
            return
        self._emit("ble_status", message="BLE 已断开，5 秒后重连…", connected=False, scanning=False)
        generation = self._reconnect_generation = self._reconnect_generation + 1
        loop = self._loop
        if loop is not None:
            loop.create_task(self._reconnect_after_delay(address, generation))

    async def _reconnect_after_delay(self, address: str, generation: int) -> None:
        await asyncio.sleep(5)
        if self._stopping or generation != self._reconnect_generation:
            return
        if address in self._devices:
            await self._connect(address, self._device_names.get(address, ""))
        else:
            await self._scan(auto_connect_address=address)

    def _emit(self, kind: str, **data: Any) -> None:
        try:
            self._on_event(kind, data)
        except Exception:
            pass
