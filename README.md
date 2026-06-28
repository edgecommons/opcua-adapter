# OPC UA Adapter (`com.mbreissi.opcua.OpcUaAdapter`)

An AWS IoT Greengrass v2 **southbound protocol adapter** that bridges **OPC UA** servers onto the
GGCommons messaging bus, built on the `ggcommons` Java library and **Eclipse Milo 1.1.x**. It browses
each server, subscribes to tags, and republishes value changes using the cross-language **southbound
contract** (`SouthboundTagUpdate`), and exposes a command surface for on-demand **batch read**,
**batch write**, and management queries.

Migrated from a pre-refactor bridge: ported to the current ggcommons API, upgraded Milo 0.6.9 → 1.1.4,
decomposed into focused classes, and given working secure connections.

## 📖 Documentation

Full operator/integrator docs are in **[`docs/`](docs/)**:

| Doc | For |
|-----|-----|
| [docs/README.md](docs/README.md) | Overview + the data-plane / control-plane model |
| [docs/configuration.md](docs/configuration.md) | **Every configuration option**, with types, defaults, meaning |
| [docs/messaging-interface.md](docs/messaging-interface.md) | The **full message interface** — every topic & payload, with schemas |
| [docs/security.md](docs/security.md) | Secure connections, certificate sources & requirements |
| [docs/deployment-and-operations.md](docs/deployment-and-operations.md) | Build, deploy (HOST/Greengrass/k8s), operate, troubleshoot |

## Capabilities

- **Browse + subscribe** with per-tag sampling rate, queue size, and **deadband**; include/exclude
  matchers by `namespace` + regex over nodeId / browseName / displayName.
- **Tag-update publishing** — each change → a `SouthboundTagUpdate` message (normalized
  `GOOD|BAD|UNCERTAIN` quality + native `qualityRaw` + source/server timestamps), batched per tag.
- **Batch write** (`writeValues`) and **on-demand batch read** (`readValuesAsync`) via request/reply.
- **Secure connections** — `Basic256Sha256` / `SignAndEncrypt` with the client cert/key from the
  ggcommons **credentials vault**, a file, or a **PKCS#11** token; explicit server trust.
- **Control plane** — `status` / `subscriptions` queries and a `southbound_health` metric.

## Architecture

One `OpcUaDevice` per configured instance coordinates focused collaborators:
`OpcUaConnection` (connect + security) · `AddressSpaceBrowser` · `SubscriptionManager` ·
`TagUpdatePublisher` · `CommandService` (read/write/control) · `ValueCodec` · `HealthMetrics`.

## Quickstart (HOST / MQTT, local)

```bash
mvn clean package      # -> target/OpcUaAdapter-1.0.0.jar (Java 25)
java -jar target/OpcUaAdapter-1.0.0.jar \
  --platform HOST --transport MQTT ./messaging-local.json \
  -c FILE ./config.json -t my-thing
```
Needs a local MQTT broker (e.g. `docker run -d -p 1883:1883 emqx/emqx`). Subscribe to `southbound/#`
for tag updates. See [docs/](docs/) for configuration, the message interface, security, and the other
deploy targets. A reproducible end-to-end smoke harness lives in [`validation/`](validation/).
