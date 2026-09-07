from vrc_heartbeat import network


def test_gatewayless_uu_adapter_is_not_advertised(monkeypatch):
    monkeypatch.setattr(network.sys, "platform", "win32")
    monkeypatch.setattr(
        network,
        "_windows_candidates",
        lambda: [
            network.LocalIpv4Candidate(
                "172.19.84.237",
                interface_name="UU",
                has_default_gateway=False,
                metric=5,
                if_type=53,
            ),
            network.LocalIpv4Candidate(
                "192.168.100.139",
                interface_name="以太网",
                has_default_gateway=True,
                metric=25,
                if_type=6,
            ),
        ],
    )

    assert network.local_ipv4_addresses() == ["192.168.100.139"]


def test_windows_adapter_query_failure_fails_closed(monkeypatch):
    monkeypatch.setattr(network.sys, "platform", "win32")
    monkeypatch.setattr(network, "_windows_candidates", lambda: [])
    monkeypatch.setattr(
        network,
        "_hostname_candidates",
        lambda: [network.LocalIpv4Candidate("172.19.84.237", "UU")],
    )

    assert network.local_ipv4_addresses() == ["--"]


def test_multiple_routed_adapters_are_sorted_by_physical_type_and_metric(monkeypatch):
    monkeypatch.setattr(network.sys, "platform", "win32")
    monkeypatch.setattr(
        network,
        "_windows_candidates",
        lambda: [
            network.LocalIpv4Candidate("10.0.0.8", "VPN Adapter", True, 1, 53),
            network.LocalIpv4Candidate("192.168.100.139", "Ethernet", True, 25, 6),
            network.LocalIpv4Candidate("192.168.1.22", "Wi-Fi", True, 50, 71),
        ],
    )

    assert network.local_ipv4_addresses() == ["192.168.100.139", "192.168.1.22"]


def test_physical_static_address_beats_virtual_default_route(monkeypatch):
    monkeypatch.setattr(network.sys, "platform", "win32")
    monkeypatch.setattr(
        network,
        "_windows_candidates",
        lambda: [
            network.LocalIpv4Candidate("172.19.84.237", "UU", True, 5, 53),
            network.LocalIpv4Candidate("192.168.100.139", "Ethernet", False, 25, 6),
        ],
    )

    assert network.local_ipv4_addresses() == ["192.168.100.139"]


def test_hostname_fallback_ignores_loopback_and_link_local(monkeypatch):
    monkeypatch.setattr(network.sys, "platform", "linux")
    monkeypatch.setattr(network, "_windows_candidates", lambda: [])
    monkeypatch.setattr(
        network.socket,
        "getaddrinfo",
        lambda *_args: [
            (2, 2, 17, "", ("127.0.0.1", 0)),
            (2, 2, 17, "", ("169.254.10.2", 0)),
            (2, 2, 17, "", ("10.0.0.5", 0)),
        ],
    )

    assert network.local_ipv4_addresses() == ["10.0.0.5"]
