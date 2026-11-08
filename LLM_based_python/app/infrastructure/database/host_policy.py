"""Which hosts a target database connection may reach (guards POST /db/metadata against SSRF).

Users choose the target host, so without a check an authenticated caller could point the service at
its own system database, at the cloud metadata endpoint (169.254.169.254) or at other internal
services, and use the error messages and timings to probe them.

The host is resolved once and the connection goes to the checked addresses, so a DNS answer that
changes between the check and the connect (DNS rebinding) cannot slip past it.

Rules, applied to every resolved address:
- the system database is never allowed (same hostname, or any shared address);
- link-local (incl. cloud metadata), multicast, unspecified and reserved addresses are never allowed;
- loopback is allowed only when TARGET_DB_ALLOWED_HOSTS lists it;
- when TARGET_DB_ALLOWED_HOSTS is set, the host must match an entry (hostname, IP or CIDR).
Private ranges (10/8, 172.16/12, 192.168/16) are allowed by default: internal databases, and the
compose demo-db, live there. Production deployments should set TARGET_DB_ALLOWED_HOSTS.
"""

import asyncio
import ipaddress
import socket
from collections.abc import Awaitable, Callable

from app.core.config import Settings
from app.core.exceptions import TargetHostNotAllowedError

IPAddress = ipaddress.IPv4Address | ipaddress.IPv6Address
IPNetwork = ipaddress.IPv4Network | ipaddress.IPv6Network
Resolver = Callable[[str, int], Awaitable[list[str]]]

NOT_ALLOWED = "Connections to this host are not allowed"


async def resolve_host(host: str, port: int) -> list[str]:
    """All addresses of a host (an IP literal resolves to itself). Raises OSError when unknown."""
    infos = await asyncio.get_running_loop().getaddrinfo(host, port, type=socket.SOCK_STREAM)
    return list(dict.fromkeys(str(info[4][0]) for info in infos))


def _parse_ip(value: str) -> IPAddress:
    address = ipaddress.ip_address(value.split("%", 1)[0])  # drop an IPv6 zone id
    if isinstance(address, ipaddress.IPv6Address) and address.ipv4_mapped is not None:
        return address.ipv4_mapped
    return address


def _always_denied(address: IPAddress) -> bool:
    # ::1 also counts as reserved (it is inside ::/8); loopback has its own rule.
    reserved = address.is_reserved and not address.is_loopback
    return address.is_link_local or address.is_multicast or address.is_unspecified or reserved


class _AllowList:
    def __init__(self, entries: list[str]):
        self.hostnames: set[str] = set()
        self.networks: list[IPNetwork] = []
        for entry in entries:
            try:
                self.networks.append(ipaddress.ip_network(entry, strict=False))
            except ValueError:
                self.hostnames.add(entry.casefold().rstrip("."))

    def __bool__(self) -> bool:
        return bool(self.hostnames or self.networks)

    def matches(self, host: str, address: IPAddress) -> bool:
        return host.casefold().rstrip(".") in self.hostnames or any(address in net for net in self.networks)


async def allowed_addresses(host: str, port: int, settings: Settings, resolver: Resolver = resolve_host) -> list[str]:
    """The addresses to connect to for host:port. Raises TargetHostNotAllowedError or OSError."""
    normalized = host.casefold().rstrip(".")
    if normalized == settings.system_db_host.casefold().rstrip("."):
        raise TargetHostNotAllowedError(NOT_ALLOWED)

    addresses = await resolver(host, port)
    if not addresses:
        raise OSError(f"{host} has no addresses")
    try:
        system_addresses = {_parse_ip(a) for a in await resolver(settings.system_db_host, settings.system_db_port)}
    except OSError:
        system_addresses = set()

    allow_list = _AllowList(settings.target_allowed_hosts)
    for raw in addresses:
        address = _parse_ip(raw)
        listed = allow_list.matches(host, address)
        if (
            address in system_addresses
            or _always_denied(address)
            or (address.is_loopback and not listed)
            or (allow_list and not listed)
        ):
            raise TargetHostNotAllowedError(NOT_ALLOWED)
    return addresses
