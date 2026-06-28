# Validation harness (OPC UA adapter smoke tests)

Reproducible end-to-end smoke tests: a Python **asyncua** OPC UA simulator + an MQTT test client
drive the built adapter and verify three behaviors — **subscribe → `SouthboundTagUpdate`**,
**on-demand batch read**, and **batch write** — for both **plaintext** and **secure**
(`Basic256Sha256`/`SignAndEncrypt`) connections.

## Prerequisites

- `pip install asyncua cryptography paho-mqtt`
- A local MQTT broker on `localhost:1883` (e.g. `docker run -d -p 1883:1883 emqx/emqx`).
- The built adapter: `mvn package` → `target/OpcUaAdapter-1.0.0.jar` (Java 25).

Run all commands **from the repo root** (the secure config uses `validation/certs/...` paths).

## Plaintext smoke

```bash
python validation/opcua_sim_server.py &                       # OPC UA sim on opc.tcp://localhost:4840/
java -jar target/OpcUaAdapter-1.0.0.jar --platform HOST --transport MQTT \
     validation/messaging-local.json -c FILE validation/config.json -t smoke-thing &
python validation/validate.py                                  # ALL PASS expected
```

## Secure smoke (Basic256Sha256 / SignAndEncrypt)

```bash
python validation/gen_certs.py                                 # -> validation/certs/{client,server}_{cert,key}.pem
python validation/opcua_sim_server_secure.py &                 # secured OPC UA sim
java -jar target/OpcUaAdapter-1.0.0.jar --platform HOST --transport MQTT \
     validation/messaging-local.json -c FILE validation/config-secure.json -t smoke-thing &
python validation/validate.py                                  # ALL PASS expected
```

`validate.py` prints a PASS/FAIL summary and exits non-zero on any failure.

## What it checks

| Phase | Verifies |
|---|---|
| A | browse + subscribe + filtering + `SouthboundTagUpdate` envelope + normalized quality |
| B | on-demand batch read (request/reply → `SouthboundReadResult`) |
| C | batch write (Setpoint=42.5) confirmed by reading it back |

## Live KEPServerEX smoke

These run against a real KEPServerEX (no asyncua sim). First open the OPC UA port through the KEP
host's firewall and note the endpoint (default `opc.tcp://<host>:49320`).

**Discover** the server's endpoints, namespaces, and a sample of its tags (drives the config — no
guessing):

```bash
python validation/kep_discover.py opc.tcp://<host>:49320
```

**Anonymous data plane** (requires a `None` endpoint + *Allow anonymous login* on KEP). `config-kep.json`
subscribes to the built-in `_System._Time*` tags (which tick each second) by the stable namespace URI
(`"Kepware Server"`):

```bash
java -jar target/OpcUaAdapter-1.0.0.jar --platform HOST --transport MQTT \
     validation/messaging-local.json -c FILE validation/config-kep.json -t kep-thing &
python validation/validate_kep.py            # subscribe + read-by-namespaceUri; ALL PASS
```

**UserName identity** (KEP rejects anonymous on every endpoint by default). Put credentials in the
**gitignored** `validation/config-kep-user.json` (copy `config-kep.json`, add
`connection.user: { "username": "…", "password": "…" }`):

```bash
java -jar target/OpcUaAdapter-1.0.0.jar --platform HOST --transport MQTT \
     validation/messaging-local.json -c FILE validation/config-kep-user.json -t kep-thing &
python validation/validate_kep_user.py       # data flows under the UserName identity; PASS
```

The server applies that user's authorization — an under-privileged account may browse only part of the
address space (the `config-kep-user.json` here targets the world-readable ns=0 `ServerStatus` so it
works regardless of tag-namespace permissions). KEP's `_System.*` tags are read-only, so the write
phase needs a writable tag on a Channel/Device.

> `validation/config-kep-user.json` and any `validation/*-user.json` are **gitignored** — they carry
> inline credentials. Never commit them.

## Notes

- `validation/certs/` and `validation/pki/` are generated and **gitignored** — never commit keys.
- **Cert requirements** (`gen_certs.py` follows these; Milo enforces them): an OPC UA application
  instance cert's KeyUsage must include `digitalSignature`, `nonRepudiation`, `keyEncipherment`,
  `dataEncipherment`; a **self-signed** cert also needs `keyCertSign` + `BasicConstraints: CA=true`,
  and the application URI must appear as a SubjectAltName URI (matching the adapter's `applicationUri`).
- The sim's `PEER_CLIENT|TIME_RANGE` validator accepts any structurally-valid client cert (no
  trust-store entry) — fine for a smoke; a real server (KEPServerEX, Prosys) requires the client cert
  to be trusted.
