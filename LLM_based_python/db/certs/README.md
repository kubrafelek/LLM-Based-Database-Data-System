# Development TLS certificates

**Development and test only. Never use these for a real database: the private key is public.**

- `ca.crt`: self-signed CA (`TARGET_DB_SSL_ROOT_CERT` for `verify-ca` / `verify-full`)
- `server.crt`, `server.key`: server certificate for `demo-db`, `test-target-db`, `localhost`, `127.0.0.1`

Compose starts `demo-db` and `test-target-db` with `ssl=on` using these files, so the default
`TARGET_DB_SSL_MODE=prefer` already encrypts, and the TLS tests can check `require` and `verify-full`.

Regenerate (valid 10 years): `python scripts/generate_dev_certs.py`
