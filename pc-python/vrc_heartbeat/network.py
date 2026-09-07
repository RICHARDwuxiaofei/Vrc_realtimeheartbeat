from __future__ import annotations

from dataclasses import dataclass
import ctypes
import ipaddress
import socket
import sys
from ctypes import wintypes


@dataclass(frozen=True, slots=True)
class LocalIpv4Candidate:
    """A private IPv4 address together with the interface route metadata."""

    address: str
    interface_name: str = ""
    has_default_gateway: bool = False
    metric: int = 0
    if_type: int = 0


# Windows interface types used by normal wired and Wi-Fi adapters.
_IF_TYPE_ETHERNET_CSMACD = 6
_IF_TYPE_IEEE80211 = 71
_IF_OPER_STATUS_UP = 1
_GAA_FLAG_INCLUDE_GATEWAYS = 0x0080
_ERROR_BUFFER_OVERFLOW = 111
_VIRTUAL_INTERFACE_MARKERS = (
    "uu",
    "vpn",
    "virtual",
    "vmware",
    "hyper-v",
    "hyperv",
    "wsl",
    "docker",
    "teredo",
    "tunnel",
    "loopback",
    "tap",
    "wireguard",
    "tailscale",
    "zerotier",
    "hamachi",
    "anyconnect",
    "cloudflare",
)


class _SocketAddress(ctypes.Structure):
    _fields_ = [
        ("lpSockaddr", ctypes.POINTER(ctypes.c_ubyte)),
        ("iSockaddrLength", ctypes.c_int),
    ]


class _AdapterUnicastAddress(ctypes.Structure):
    pass


_AdapterUnicastAddress._fields_ = [
    ("Length", wintypes.ULONG),
    ("Flags", wintypes.ULONG),
    ("Next", ctypes.POINTER(_AdapterUnicastAddress)),
    ("Address", _SocketAddress),
]


class _AdapterGatewayAddress(ctypes.Structure):
    pass


_AdapterGatewayAddress._fields_ = [
    ("Length", wintypes.ULONG),
    ("Reserved", wintypes.ULONG),
    ("Next", ctypes.POINTER(_AdapterGatewayAddress)),
    ("Address", _SocketAddress),
]


class _AdapterAddresses(ctypes.Structure):
    pass


_AdapterAddresses._fields_ = [
    ("Length", wintypes.ULONG),
    ("IfIndex", wintypes.DWORD),
    ("Next", ctypes.POINTER(_AdapterAddresses)),
    ("AdapterName", ctypes.c_char_p),
    ("FirstUnicastAddress", ctypes.POINTER(_AdapterUnicastAddress)),
    ("FirstAnycastAddress", ctypes.c_void_p),
    ("FirstMulticastAddress", ctypes.c_void_p),
    ("FirstDnsServerAddress", ctypes.c_void_p),
    ("DnsSuffix", wintypes.LPWSTR),
    ("Description", wintypes.LPWSTR),
    ("FriendlyName", wintypes.LPWSTR),
    ("PhysicalAddress", ctypes.c_ubyte * 8),
    ("PhysicalAddressLength", wintypes.DWORD),
    ("Flags", wintypes.DWORD),
    ("Mtu", wintypes.DWORD),
    ("IfType", wintypes.DWORD),
    ("OperStatus", wintypes.DWORD),
    ("Ipv6IfIndex", wintypes.DWORD),
    ("ZoneIndices", wintypes.DWORD * 16),
    ("FirstPrefix", ctypes.c_void_p),
    ("TransmitLinkSpeed", ctypes.c_ulonglong),
    ("ReceiveLinkSpeed", ctypes.c_ulonglong),
    ("FirstWinsServerAddress", ctypes.c_void_p),
    ("FirstGatewayAddress", ctypes.POINTER(_AdapterGatewayAddress)),
    ("Ipv4Metric", wintypes.ULONG),
    ("Ipv6Metric", wintypes.ULONG),
]


def local_ipv4_addresses() -> list[str]:
    """Return usable local IPv4 addresses, preferring adapters with a gateway.

    The old hostname lookup also returned addresses from disconnected or virtual
    adapters. Windows' adapter table exposes the route metadata needed to avoid
    advertising a VPN/UU address which cannot reach the phone.
    """

    if sys.platform == "win32":
        candidates = _windows_candidates()
        # Do not fall back to hostname resolution on Windows: it has no route
        # metadata and can re-introduce the exact UU/VPN address this chooser
        # is meant to avoid. Failing closed lets the user enter an address
        # manually instead of generating a QR code that points at the wrong NIC.
        if not candidates:
            return ["--"]
    else:
        candidates = _hostname_candidates()

    # Prefer a routed physical adapter. If the physical LAN uses a static
    # address with no gateway, keep that adapter ahead of VPN/UU interfaces
    # rather than allowing a virtual default route to win. Only when Windows
    # exposes no physical adapter do we fall back to a routed non-physical one.
    physical = [item for item in candidates if _is_physical_candidate(item)]
    routed_physical = [item for item in physical if item.has_default_gateway]
    if physical:
        selected = routed_physical or physical
    else:
        routed = [item for item in candidates if item.has_default_gateway]
        selected = routed or candidates
    selected = sorted(selected, key=_candidate_sort_key)

    unique: list[str] = []
    for item in selected:
        if item.address not in unique:
            unique.append(item.address)
    return unique or ["--"]


def _windows_candidates() -> list[LocalIpv4Candidate]:
    try:
        api = ctypes.WinDLL("iphlpapi.dll").GetAdaptersAddresses
        api.argtypes = [
            wintypes.ULONG,
            wintypes.ULONG,
            ctypes.c_void_p,
            ctypes.POINTER(_AdapterAddresses),
            ctypes.POINTER(wintypes.ULONG),
        ]
        api.restype = wintypes.ULONG

        size = wintypes.ULONG(15 * 1024)
        buffer = ctypes.create_string_buffer(size.value)
        head = ctypes.cast(buffer, ctypes.POINTER(_AdapterAddresses))
        result = api(socket.AF_INET, _GAA_FLAG_INCLUDE_GATEWAYS, None, head, ctypes.byref(size))
        if result == _ERROR_BUFFER_OVERFLOW:
            buffer = ctypes.create_string_buffer(size.value)
            head = ctypes.cast(buffer, ctypes.POINTER(_AdapterAddresses))
            result = api(socket.AF_INET, _GAA_FLAG_INCLUDE_GATEWAYS, None, head, ctypes.byref(size))
        if result != 0:
            return []

        candidates: list[LocalIpv4Candidate] = []
        current = head
        while bool(current):
            adapter = current.contents
            if adapter.OperStatus == _IF_OPER_STATUS_UP:
                interface_name = _adapter_name(adapter)
                has_gateway = _has_ipv4_gateway(adapter.FirstGatewayAddress)
                unicast = adapter.FirstUnicastAddress
                while bool(unicast):
                    address = _socket_address_ipv4(unicast.contents.Address)
                    if address is not None and _is_usable_private_ipv4(address):
                        candidates.append(
                            LocalIpv4Candidate(
                                address=address,
                                interface_name=interface_name,
                                has_default_gateway=has_gateway,
                                metric=int(adapter.Ipv4Metric),
                                if_type=int(adapter.IfType),
                            )
                        )
                    unicast = unicast.contents.Next
            current = adapter.Next
        return candidates
    except (AttributeError, OSError, TypeError, ValueError):
        return []


def _hostname_candidates() -> list[LocalIpv4Candidate]:
    addresses: set[str] = set()
    try:
        for item in socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET):
            address = item[4][0]
            if _is_usable_private_ipv4(address):
                addresses.add(address)
    except OSError:
        pass
    return [LocalIpv4Candidate(address=address) for address in addresses]


def _adapter_name(adapter: _AdapterAddresses) -> str:
    values = (adapter.FriendlyName, adapter.Description)
    return next((value.strip() for value in values if isinstance(value, str) and value.strip()), "")


def _has_ipv4_gateway(pointer: ctypes.POINTER(_AdapterGatewayAddress)) -> bool:
    current = pointer
    while bool(current):
        if _socket_address_ipv4(current.contents.Address) is not None:
            return True
        current = current.contents.Next
    return False


def _socket_address_ipv4(address: _SocketAddress) -> str | None:
    pointer = address.lpSockaddr
    length = int(address.iSockaddrLength)
    if not pointer or length < 8:
        return None
    try:
        raw = ctypes.string_at(pointer, length)
        if int.from_bytes(raw[:2], byteorder="little") != socket.AF_INET:
            return None
        return socket.inet_ntop(socket.AF_INET, raw[4:8])
    except (OSError, ValueError):
        return None


def _is_usable_private_ipv4(address: str) -> bool:
    try:
        parsed = ipaddress.ip_address(address)
    except ValueError:
        return False
    return parsed.version == 4 and parsed.is_private and not parsed.is_loopback and not parsed.is_link_local


def _candidate_sort_key(candidate: LocalIpv4Candidate) -> tuple[int, int, int, int, str]:
    name = candidate.interface_name.casefold()
    virtual = any(marker in name for marker in _VIRTUAL_INTERFACE_MARKERS)
    normal_adapter = candidate.if_type in {_IF_TYPE_ETHERNET_CSMACD, _IF_TYPE_IEEE80211}
    # Lower tuple values sort first: routed, physical and lower metric win.
    return (
        0 if candidate.has_default_gateway else 1,
        0 if normal_adapter else 1,
        1 if virtual else 0,
        candidate.metric if candidate.metric > 0 else 10_000,
        candidate.address,
    )


def _is_physical_candidate(candidate: LocalIpv4Candidate) -> bool:
    name = candidate.interface_name.casefold()
    return (
        candidate.if_type in {_IF_TYPE_ETHERNET_CSMACD, _IF_TYPE_IEEE80211}
        and not any(marker in name for marker in _VIRTUAL_INTERFACE_MARKERS)
    )
