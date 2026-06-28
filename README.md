# OPC UA Adapter (`com.mbreissi.opcua.OpcUaAdapter`)

An AWS IoT Greengrass v2 **southbound protocol adapter** that bridges **OPC UA** servers onto the
GGCommons messaging bus, built on the `ggcommons` Java library and **Eclipse Milo 1.1.x**. It
subscribes to OPC UA tags and republishes value changes using the cross-language **southbound
contract** (`SouthboundTagUpdate`; see `docs/SOUTHBOUND.md` in the ggcommons monorepo), and exposes a
command surface for on-demand **batch read** and **batch write**.

This component was migrated from a pre-refactor bridge: ported to the current ggcommons API, upgraded
Milo 0.6.9 → 1.1.4, decomposed into focused classes, and given working secure connections.

## Capabilities

- **Browse + subscribe** with per-tag sampling rate, queue size, and **deadband**; include/exclude
  matchers by `namespace` + regex over nodeId / browseName / displayName.
- **Tier-1 publishing** — each change → a `SouthboundTagUpdate` message (normalized
  `GOOD|BAD|UNCERTAIN` quality + native `qualityRaw` + source/server timestamps), batched per node.
- **Batch write** (`writeValues`) and **on-demand batch read** (`readValuesAsync`) over request/reply.
- **Secure connections** — `Basic256Sha256` / `SignAndEncrypt` with the client cert/key sourced from
  the ggcommons **credentials vault**, a file, or a **PKCS#11** token; server trust via a PKI dir.
- **`southbound_health`** metric per instance (connection state, read errors).

## Architecture

One `OpcUaDevice` per configured instance coordinates focused collaborators:
`OpcUaConnection` (connect + security) · `AddressSpaceBrowser` · `SubscriptionManager` ·
`TagUpdatePublisher` · `CommandService` (read/write/control) · `ValueCodec` · `HealthMetrics`.

## Build

Java 25 + Maven; depends on `com.mbreissi:ggcommons` (from GitHub Packages, or `mvn install` of
`libs/java` to your local `~/.m2`).

```bash
mvn clean package      # -> target/OpcUaAdapter-1.0.0.jar (shaded)
```

## Run (HOST / MQTT, local)

```bash
java -jar target/OpcUaAdapter-1.0.0.jar \
  --platform HOST --transport MQTT ./messaging-local.json \
  -c FILE ./config.json -t my-thing
```
Needs a local MQTT broker (e.g. EMQX on `localhost:1883`). Under Greengrass: `--platform GREENGRASS
-c GG_CONFIG -t <thing>`.

## Configuration (southbound convention)

Adapter config lives under the permissive `component.global` / `component.instances[]` (no schema
change). One instance = one OPC UA server:

```jsonc
"component": {
  "global": { "defaults": { "publishIntervalMs": 1000, "samplingRateMs": 500, "queueSize": 100 } },
  "instances": [ {
    "id": "kep1",
    "adapter": "opcua",
    "connection": { "endpoint": "opc.tcp://host:4840/", "securityPolicy": "None" },
    "publish": { "topic": "southbound/{site}/{ComponentName}/{InstanceId}/{tagId}", "batchMs": 1000 },
    "write":   { "enabled": true, "topic": "southbound/{ComponentName}/{InstanceId}/write" },
    "read":    { "topic": "southbound/{ComponentName}/{InstanceId}/read" },
    "subscriptions": [ {
      "id": "sines",
      "include": [ { "namespace": 2, "match": "^Simulation\\.Sine.*", "samplingRateMs": 250,
                     "queueSize": 50, "deadband": { "type": "Absolute", "value": 0.5 } } ],
      "exclude": [ { "namespace": 2, "match": "Simulation\\.Sine4" } ]
    } ]
  } ]
}
```

## Command surface

- **Write** (to `write.topic`): `{ "writes": [ { "ns": 2, "tagId": "Setpoint", "value": 42.5 }, ... ] }`
  (a single `{ns,tagId,value}` object is also accepted; optional `status`, `sourceTs`).
- **Read** (request/reply to `read.topic`): request `{ "tags": [ { "ns": 2, "tagId": "Counter" }, ... ] }`
  → reply `SouthboundReadResult` `{ "id": "...", "reads": [ { "tag": {...}, "value", "quality", ... } ] }`.

## Secure connections

Set `securityPolicy` (e.g. `Basic256Sha256`) + `messageMode` (`SignAndEncrypt`) and a client
certificate source:

```jsonc
"connection": {
  "endpoint": "opc.tcp://host:4840/",
  "securityPolicy": "Basic256Sha256",
  "messageMode": "SignAndEncrypt",
  "clientCertificate": { "source": "vault", "secret": "opcua/kep1/appcert" },
  // or  { "source": "file",   "certPath": "...", "keyPath": "..." }
  // or  { "source": "pkcs11", "modulePath": "...", "slotIndex": 0, "pinEnv": "HSM_PIN",
  //       "keyLabel": "...", "certLabel": "..." }
  "trust": {
    "pkiDir": "/var/lib/opcua/{InstanceId}/pki",
    "serverCertificate": { "source": "file", "path": "server.pem" }  // optional pin; else use pkiDir/trusted
  }
}
```

- **`vault` source** reads a `TlsBundle` secret `{certPem, keyPem, caPem}` via
  `gg.getCredentials().getTlsBundle(...)` (requires a `credentials` config section). `caPem` becomes
  the server trust anchor.
- **`applicationUri`** is derived from the client cert's **SubjectAltName URI** (or set
  `connection.applicationUri`). It MUST byte-for-byte equal the SAN URI, or the server rejects the
  session — the single most common OPC UA security failure.

### Client/server certificate requirements (OPC UA)

An OPC UA application instance certificate's **KeyUsage MUST include** `digitalSignature`,
`nonRepudiation` (contentCommitment), `keyEncipherment`, and `dataEncipherment`. **Self-signed**
certs (their own trust anchor) must additionally set `keyCertSign` + `BasicConstraints: CA=true`, and
carry the application URI in a SubjectAltName URI. Milo's validator enforces these and will reject
certs that omit them (`Bad_CertificateUseNotAllowed`). See `validation/gen_certs.py` for a compliant
self-signed generator.

## Validation

`validation/` contains a reproducible smoke harness (asyncua simulator + MQTT test client) covering
subscribe→publish, on-demand read, and batch write, for both **plaintext** and **secure**
(`Basic256Sha256`) connections. See `validation/README.md`.

## Troubleshooting

| Symptom | Cause / fix |
|---|---|
| `Bad_CertificateUseNotAllowed: required KeyUsage '…'` | Cert missing `nonRepudiation` / `keyCertSign` — see cert requirements above. |
| Server rejects session right after secure handshake | `applicationUri` ≠ client cert SAN URI. |
| Connects but no `SouthboundTagUpdate` | subscription `namespace`/`match` don't match any nodes (check the server's address space). |
| Secure connect retries forever | server cert not trusted — pin it via `trust.serverCertificate` or drop it in `pkiDir/trusted`. |
