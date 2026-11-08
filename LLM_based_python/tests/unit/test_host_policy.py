"""Target host policy: system DB, metadata/link-local, loopback, allow list, DNS answers."""

import ipaddress

import pytest

from app.core.config import get_settings
from app.core.exceptions import TargetHostNotAllowedError
from app.infrastructure.database.host_policy import allowed_addresses

DNS = {
    "system-db": ["172.18.0.2"],
    "demo-db": ["172.18.0.3"],
    "alias-of-system": ["172.18.0.2"],
    "metadata.internal": ["169.254.169.254"],
    "localhost": ["127.0.0.1", "::1"],
    "db.example.com": ["203.0.113.10"],
    "rebind.example.com": ["203.0.113.11", "169.254.169.254"],
    "mapped.example.com": ["::ffff:169.254.169.254"],
}


async def fake_resolver(host: str, port: int) -> list[str]:
    if host in DNS:
        return DNS[host]
    try:  # IP literals resolve to themselves
        return [str(ipaddress.ip_address(host))]
    except ValueError:
        raise OSError(f"unknown host {host}") from None


def settings(allowed: str = ""):
    return get_settings().model_copy(update={"system_db_host": "system-db", "target_db_allowed_hosts": allowed})


async def check(host: str, allowed: str = "", port: int = 5432) -> list[str]:
    return await allowed_addresses(host, port, settings(allowed), resolver=fake_resolver)


async def test_private_network_host_is_allowed_by_default():
    assert await check("demo-db") == ["172.18.0.3"]


async def test_public_host_is_allowed_by_default():
    assert await check("db.example.com") == ["203.0.113.10"]


@pytest.mark.parametrize("host", ["system-db", "SYSTEM-DB.", "alias-of-system", "172.18.0.2"])
async def test_system_database_is_never_allowed(host):
    with pytest.raises(TargetHostNotAllowedError):
        await check(host)
    with pytest.raises(TargetHostNotAllowedError):
        await check(host, allowed=f"{host},172.18.0.0/16")


@pytest.mark.parametrize(
    "host",
    [
        "metadata.internal",
        "169.254.169.254",
        "fe80::1",
        "0.0.0.0",
        "::",
        "224.0.0.1",
        "240.0.0.1",
        "mapped.example.com",
    ],
)
async def test_link_local_multicast_unspecified_and_reserved_are_denied(host):
    with pytest.raises(TargetHostNotAllowedError):
        await check(host)
    with pytest.raises(TargetHostNotAllowedError):
        await check(host, allowed="0.0.0.0/0,::/0")


async def test_one_bad_address_denies_the_host():
    # A name with a public and a metadata address must not be accepted on the public one.
    with pytest.raises(TargetHostNotAllowedError):
        await check("rebind.example.com")


@pytest.mark.parametrize("host", ["localhost", "127.0.0.1", "::1"])
async def test_loopback_is_denied_unless_listed(host):
    with pytest.raises(TargetHostNotAllowedError):
        await check(host)


async def test_listed_loopback_is_allowed():
    assert await check("localhost", allowed="localhost") == ["127.0.0.1", "::1"]
    assert await check("127.0.0.1", allowed="127.0.0.0/8") == ["127.0.0.1"]


async def test_allow_list_restricts_hosts():
    allowed = "demo-db, 203.0.113.0/24"
    assert await check("demo-db", allowed) == ["172.18.0.3"]
    assert await check("db.example.com", allowed) == ["203.0.113.10"]
    with pytest.raises(TargetHostNotAllowedError):
        await check("10.1.2.3", allowed)


async def test_unknown_host_raises_oserror():
    with pytest.raises(OSError):
        await check("no-such-host.invalid")
