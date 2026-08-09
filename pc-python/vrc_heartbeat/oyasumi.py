from __future__ import annotations

from dataclasses import dataclass
import json
import threading
import time
from typing import Callable
from urllib.error import HTTPError, URLError
from urllib.request import urlopen

from zeroconf import IPVersion, ServiceBrowser, ServiceInfo, ServiceListener, Zeroconf


OSCQUERY_SERVICE_TYPE = "_oscjson._tcp.local."
OYASUMI_SERVICE_NAME = "OyasumiVR"
OYASUMI_HEART_RATE_ADDRESS = "/OyasumiVR/HeartRate"


@dataclass(frozen=True, slots=True)
class OyasumiOscTarget:
    host: str
    port: int
    service_name: str


TargetCallback = Callable[[OyasumiOscTarget | None], None]


class OyasumiOscQueryDiscovery(ServiceListener):
    """Discover OyasumiVR's dynamic OSC UDP endpoint through OSCQuery."""

    def __init__(self, on_target: TargetCallback | None = None) -> None:
        self._on_target = on_target or (lambda _target: None)
        self._lock = threading.RLock()
        self._zeroconf: Zeroconf | None = None
        self._browser: ServiceBrowser | None = None
        self._known_services: set[str] = set()
        self._target: OyasumiOscTarget | None = None

    @property
    def target(self) -> OyasumiOscTarget | None:
        with self._lock:
            return self._target

    def start(self) -> None:
        with self._lock:
            if self._zeroconf is not None:
                return
            zeroconf = Zeroconf(ip_version=IPVersion.V4Only)
            self._zeroconf = zeroconf
            self._browser = ServiceBrowser(zeroconf, OSCQUERY_SERVICE_TYPE, self)

    def stop(self) -> None:
        with self._lock:
            browser, self._browser = self._browser, None
            zeroconf, self._zeroconf = self._zeroconf, None
            self._known_services.clear()
            had_target = self._target is not None
            self._target = None
        if browser is not None:
            browser.cancel()
        if zeroconf is not None:
            zeroconf.close()
        if had_target:
            self._on_target(None)

    def add_service(self, zeroconf: Zeroconf, service_type: str, name: str) -> None:
        with self._lock:
            self._known_services.add(name)
        threading.Thread(
            target=self._resolve_with_retry,
            args=(zeroconf, service_type, name),
            name="oyasumivr-oscquery-resolver",
            daemon=True,
        ).start()

    def update_service(self, zeroconf: Zeroconf, service_type: str, name: str) -> None:
        self.add_service(zeroconf, service_type, name)

    def remove_service(self, _zeroconf: Zeroconf, _service_type: str, name: str) -> None:
        callback = False
        with self._lock:
            self._known_services.discard(name)
            if self._target is not None and self._target.service_name == name:
                self._target = None
                callback = True
        if callback:
            self._on_target(None)

    def _resolve_with_retry(self, zeroconf: Zeroconf, service_type: str, name: str) -> None:
        # OyasumiVR advertises its OSCQuery server just before registering UI-side methods.
        # Retrying avoids a startup race where the heart-rate node is not visible yet.
        for attempt in range(10):
            with self._lock:
                if self._zeroconf is not zeroconf or name not in self._known_services:
                    return
            target = resolve_oyasumi_target(zeroconf, service_type, name)
            if target is not None:
                with self._lock:
                    if self._zeroconf is not zeroconf or name not in self._known_services:
                        return
                    changed = target != self._target
                    self._target = target
                if changed:
                    self._on_target(target)
                return
            if attempt < 9:
                time.sleep(1)


def resolve_oyasumi_target(
    zeroconf: Zeroconf,
    service_type: str,
    service_name: str,
) -> OyasumiOscTarget | None:
    info = zeroconf.get_service_info(service_type, service_name, timeout=2_000)
    if info is None:
        return None
    return target_from_service_info(info, service_name)


def target_from_service_info(
    info: ServiceInfo,
    service_name: str,
    fetch_json: Callable[[str], object] | None = None,
) -> OyasumiOscTarget | None:
    addresses = info.parsed_addresses(IPVersion.V4Only)
    if not addresses:
        return None
    query_host = addresses[0]
    fetch = fetch_json or _fetch_json

    try:
        host_info = fetch(f"http://{query_host}:{info.port}/?HOST_INFO")
        method_info = fetch(
            f"http://{query_host}:{info.port}{OYASUMI_HEART_RATE_ADDRESS}"
        )
    except (HTTPError, URLError, OSError, TimeoutError, ValueError, json.JSONDecodeError):
        return None
    if not isinstance(host_info, dict) or host_info.get("NAME") != OYASUMI_SERVICE_NAME:
        return None
    if not isinstance(method_info, dict):
        return None
    value_type = method_info.get("TYPE") or method_info.get("VALUE_TYPE")
    if (
        method_info.get("FULL_PATH") != OYASUMI_HEART_RATE_ADDRESS
        or value_type != "i"
        or not _is_writeable(method_info.get("ACCESS"))
    ):
        return None
    if host_info.get("OSC_TRANSPORT", "UDP") != "UDP":
        return None

    osc_host = host_info.get("OSC_IP") or query_host
    if not isinstance(osc_host, str) or osc_host in {"0.0.0.0", "::"}:
        osc_host = query_host
    osc_port = host_info.get("OSC_PORT", info.port)
    if type(osc_port) is not int or not 1 <= osc_port <= 65_535:
        return None
    return OyasumiOscTarget(osc_host, osc_port, service_name)


def _is_writeable(access: object) -> bool:
    return type(access) is int and bool(access & 2)


def _fetch_json(url: str) -> object:
    with urlopen(url, timeout=2) as response:
        return json.load(response)
