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
address space.

**Write.** KEP's `_System.*` tags are read-only, so the write path needs a writable tag on a
Channel/Device. With one present (e.g. `Channel1.Device1.Tag2`), `validate_kep_write.py` reads it,
writes a new value of the same type, and reads it back to confirm the write landed (the account needs
write permission):

```bash
python validation/validate_kep_write.py     # write -> read-back; PASS
```

**Secure (`Basic256Sha256` / `SignAndEncrypt`).** Generate a client cert, pin KEP's server cert, and
complete the mutual-trust handshake:

```bash
python validation/gen_certs.py                                   # -> certs/client_{cert,key}.pem
python validation/kep_server_cert.py opc.tcp://<host>:49320 validation/certs/kep_server_cert.pem
java -jar target/OpcUaAdapter-1.0.0.jar --platform HOST --transport MQTT \
     validation/messaging-local.json -c FILE validation/config-kep-secure.json -t kep-thing &
# first attempt fails Bad_SecurityChecksFailed: in KEP's OPC UA Config Mgr -> Trusted Clients,
# trust "GGCommons OPC UA Adapter". The adapter retries every 5s and connects.
python validation/validate_kep.py                                # ALL PASS over the encrypted channel
```

For the realistic production config — secure channel **and** a UserName token together — use the
gitignored `validation/config-kep-secure-user.json` (the secure config plus `connection.user`).

> `validation/config-kep-user.json` and any `validation/*-user.json` are **gitignored** — they carry
> inline credentials. Never commit them. The `certs/` and `pki/` dirs are gitignored too.

## Integration suite (full data-type & feature matrix)

`validate_kep_suite.py` is the broad suite: every adapter data type (read **and** write round-trip),
changing values from simulator functions, and the adapter's features. It needs a dedicated channel of
typed tags, created on the server via the KEP **Configuration REST API** (`kep_setup.py`).

```bash
# 1. create the GGCommonsTest channel/device/tags (Config-API account needs add+edit permission)
KEP_API_USER=Administrator KEP_API_PASS=*** KEP_API_HOST=<host>:57512 python validation/kep_setup.py
# 2. copy the template and set connection.user to a read/write KEP UA account
cp validation/config-kep-suite.example.json validation/config-kep-suite.json   # edit connection.user
# 3. run the adapter on it, then the suite
java -jar target/OpcUaAdapter-1.0.0.jar --platform HOST --transport MQTT \
     validation/messaging-local.json -c FILE validation/config-kep-suite.json -t suite-thing &
python validation/validate_kep_suite.py        # 41 checks; ALL PASS
```

It verifies: each data type (Boolean, SByte, Byte, Int16/32/64, UInt16/32/64, Float, Double, String)
on read and on write→read-back; changing values from `RAMP`/`SINE`/`USER` simulator functions;
include/exclude filtering; per-tag topic override; batch read/write; `namespaceUri`-vs-index
addressing; error handling (unresolvable URI omitted, missing node → `BAD`); the status/subscriptions
control queries; the `southbound_health` metric; and quality normalization.

`kep_setup.py` is idempotent (create-or-edit upsert) and reads credentials from the environment, so no
secret is committed. The writable type tags use the Simulator's **K** holding registers — its **R**
registers free-run, so written values wouldn't stick. The suite subscribes by topic **prefix**
(`southbound/#`, `ggtest/#`, `metrics/#`); the local EMQX does not honor a bare `#`.

## Notes

- `validation/certs/` and `validation/pki/` are generated and **gitignored** — never commit keys.
- **Cert requirements** (`gen_certs.py` follows these; Milo enforces them): an OPC UA application
  instance cert's KeyUsage must include `digitalSignature`, `nonRepudiation`, `keyEncipherment`,
  `dataEncipherment`; a **self-signed** cert also needs `keyCertSign` + `BasicConstraints: CA=true`,
  and the application URI must appear as a SubjectAltName URI (matching the adapter's `applicationUri`).
- The sim's `PEER_CLIENT|TIME_RANGE` validator accepts any structurally-valid client cert (no
  trust-store entry) — fine for a smoke; a real server (KEPServerEX, Prosys) requires the client cert
  to be trusted.
