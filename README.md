# OPC UA Adapter (`com.mbreissi.opcua.OpcUaAdapter`)

An AWS IoT Greengrass v2 **southbound protocol adapter** that bridges **OPC UA** servers onto the
GGCommons messaging bus, built on the `ggcommons` **Unified Namespace (UNS)** library and **Eclipse
Milo 1.1.x**. It browses each server, subscribes to signals, and republishes value changes using the
cross-language **southbound contract** (`SouthboundSignalUpdate`) on the UNS **`data`** class, and
exposes a `cmd/sb/*` command surface for on-demand **read**, allow-listed **write**, and management
queries.

Migrated onto the ggcommons UNS core: signal updates ride
`ecv1/{device}/{component}/{instance}/data/{signalPath}` (topics minted by `gg.instance(id).uns()`, the
site hierarchy carried in the top-level envelope `identity` — not `tags.thing`); the command surface is
the library-owned `cmd/sb/*` inbox; write access is gated by a `writes.allow[]` allow-list. Also
upgraded Milo 0.6.9 → 1.1.4, decomposed into focused classes, and given working secure connections.

## 📖 Documentation

Full operator/integrator docs are in **[`docs/`](docs/)**, organized by [Diátaxis](https://diataxis.fr/):

| Doc | For |
|-----|-----|
| [docs/tutorial.md](docs/tutorial.md) | **Learn by doing** — bring the adapter up against a simulator, end to end |
| [docs/how-to-guides.md](docs/how-to-guides.md) | **Task recipes** — secure connect, select signals, tune rates, read/write, deploy |
| [docs/reference/configuration.md](docs/reference/configuration.md) | **Every configuration option** — types, defaults, meaning |
| [docs/reference/messaging-interface.md](docs/reference/messaging-interface.md) | **Every topic & message payload** + the CLI |
| [docs/explanation.md](docs/explanation.md) | **How it works and why** — the timing pipeline, the two planes, the security model |

## Capabilities

- **Browse + subscribe** with per-signal sampling rate, queue size, and **deadband**; include/exclude
  matchers by `namespace` + regex over nodeId / browseName / displayName.
- **Signal-update publishing** — each change → a `SouthboundSignalUpdate` message on the UNS `data`
  class (normalized `GOOD|BAD|UNCERTAIN` quality + native `qualityRaw` + source/server timestamps),
  batched per signal, stamped with the top-level `identity`.
- **Command surface** — `cmd/sb/*` verbs on the library inbox: `sb/read`, allow-listed `sb/write`
  (confirmed, per-entry ack), `sb/browse` (paged address-space enumeration), `sb/status`,
  `sb/subscriptions`, `sb/rescan`. Multi-instance requests carry an `instance` selector.
- **Events** — operator-facing `evt` alarms: `critical/connection-lost`, `connection-restored`,
  `warning/write-rejected`.
- **Secure connections** — `Basic256Sha256` / `SignAndEncrypt` with the client cert/key from the
  ggcommons **credentials vault**, a file, or a **PKCS#11** token; explicit server trust.
- **Health** — a `southbound_health` metric (`connectionState`, `readErrors`, `writeErrors`) on the
  UNS `metric` class.

## Architecture

The component-level `CommandRegistry` registers the `sb/*` verbs once on the library inbox and routes
each request to the right device by its `instance` field. One `OpcUaDevice` per configured instance
coordinates focused collaborators: `OpcUaConnection` (connect + security) · `AddressSpaceBrowser` ·
`SubscriptionManager` · `SignalUpdatePublisher` (UNS `data`) · `CommandService` (the `sb/*` logic) ·
`EventEmitter` (UNS `evt`) · `ValueCodec` · `HealthMetrics`.

## Quickstart (HOST / MQTT, local)

```bash
mvn clean package      # -> target/OpcUaAdapter-1.0.0.jar (Java 25)
java -jar target/OpcUaAdapter-1.0.0.jar \
  --platform HOST --transport MQTT ./messaging-local.json \
  -c FILE ./config.json -t my-thing
```
Needs a local MQTT broker (e.g. `docker run -d -p 1883:1883 emqx/emqx`). Subscribe to
`ecv1/+/+/+/data/#` for signal updates (and `ecv1/+/+/+/state`, `ecv1/+/+/+/metric/#` for the
keepalive and health); drive `ecv1/{thing}/OpcUaAdapter/main/cmd/sb/status` for status. See
[docs/](docs/) for configuration, the message interface, security, and the other deploy targets. A
reproducible end-to-end smoke harness lives in [`validation/`](validation/).
