"""Generate the development/test TLS certificates in db/certs (CA + server cert for the demo/test DBs).

Development only: the private keys are committed so `docker compose up` works without setup.
Never use them for a real database.

    python scripts/generate_dev_certs.py
"""

import datetime
import ipaddress
from pathlib import Path

from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.x509.oid import ExtendedKeyUsageOID, NameOID

OUT = Path(__file__).resolve().parent.parent / "db" / "certs"
SERVER_NAMES = ["demo-db", "test-target-db", "localhost"]
VALID = datetime.timedelta(days=3650)


def name(common_name: str) -> x509.Name:
    return x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, common_name)])


def pem_key(key: ec.EllipticCurvePrivateKey) -> bytes:
    encoding, key_format = serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8
    return key.private_bytes(encoding, key_format, serialization.NoEncryption())


def main() -> None:
    now = datetime.datetime.now(datetime.UTC)
    ca_key = ec.generate_private_key(ec.SECP256R1())
    ca = (
        x509.CertificateBuilder()
        .subject_name(name("LLM Data Discovery dev CA (do not trust)"))
        .issuer_name(name("LLM Data Discovery dev CA (do not trust)"))
        .public_key(ca_key.public_key())
        .serial_number(x509.random_serial_number())
        .not_valid_before(now)
        .not_valid_after(now + VALID)
        .add_extension(x509.BasicConstraints(ca=True, path_length=0), critical=True)
        .add_extension(x509.KeyUsage(False, False, False, False, False, True, True, False, False), critical=True)
        .sign(ca_key, hashes.SHA256())
    )
    server_key = ec.generate_private_key(ec.SECP256R1())
    server = (
        x509.CertificateBuilder()
        .subject_name(name("demo-db"))
        .issuer_name(ca.subject)
        .public_key(server_key.public_key())
        .serial_number(x509.random_serial_number())
        .not_valid_before(now)
        .not_valid_after(now + VALID)
        .add_extension(
            x509.SubjectAlternativeName(
                [x509.DNSName(n) for n in SERVER_NAMES] + [x509.IPAddress(ipaddress.ip_address("127.0.0.1"))]
            ),
            critical=False,
        )
        .add_extension(x509.BasicConstraints(ca=False, path_length=None), critical=True)
        .add_extension(x509.ExtendedKeyUsage([ExtendedKeyUsageOID.SERVER_AUTH]), critical=False)
        .sign(ca_key, hashes.SHA256())
    )
    OUT.mkdir(parents=True, exist_ok=True)
    (OUT / "ca.crt").write_bytes(ca.public_bytes(serialization.Encoding.PEM))
    (OUT / "server.crt").write_bytes(server.public_bytes(serialization.Encoding.PEM))
    (OUT / "server.key").write_bytes(pem_key(server_key))
    print(f"Wrote ca.crt, server.crt, server.key to {OUT}")


if __name__ == "__main__":
    main()
