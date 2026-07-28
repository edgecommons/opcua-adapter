# OPC UA Adapter (`com.mbreissi.edgecommons.OpcUaAdapter`)

An AWS IoT Greengrass v2 **southbound protocol adapter** that bridges **OPC UA** servers onto the
EdgeCommons messaging bus, built on the `edgecommons` **Unified Namespace (UNS)** library and **Eclipse
Milo 1.1.x**. It browses each server, subscribes to signals, and republishes value changes using the
cross-language **southbound contract** (`SouthboundSignalUpdate`) on the UNS **`data`** class, and
serves the standardized `cmd/sb/*` command surface for on-demand **read**, allow-listed **write**,
address-space **browse**, the configured-signal **inventory**, and the **lifecycle-control** verbs.

Signal updates ride `ecv1/{device}/{component}/{instance}/data/{signalPath}` (topics minted by
`gg.instance(id).uns()`, the site hierarchy carried in the top-level envelope `identity`); the command
surface is the library-owned `cmd/sb/*` inbox; write access is gated by a `writes.allow[]` allow-list
keyed on the stable `signal.id`.

## 📖 Documentation

Full operator/integrator docs are in **[`docs/`](docs/)**, organized by [Diátaxis](https://diataxis.fr/):

| Doc | For |
|-----|-----|
| [docs/tutorial.md](docs/tutorial.md) | **Learn by doing** — bring the adapter up against a simulator, end to end |
| [docs/how-to-guides.md](docs/how-to-guides.md) | **Task recipes** — secure connect, select signals, tune rates, read/write, deploy |
| [docs/reference/configuration.md](docs/reference/configuration.md) | **Every configuration option** — types, defaults, meaning |
| [docs/reference/messaging-interface.md](docs/reference/messaging-interface.md) | **Every topic & message payload** + the CLI |
| [docs/reference/metrics.md](docs/reference/metrics.md) | **Every metric** — `southbound_health` and the OPC UA operational families |
| [docs/explanation.md](docs/explanation.md) | **How it works and why** — the timing pipeline, the two planes, the security model |

`DESIGN.md` records the internal design decisions and the decision register.

## Capabilities

- **Browse + subscribe** with per-signal sampling rate, queue size, and **deadband**; include/exclude
  matchers by `namespace` + regex over nodeId / browseName / displayName.
- **Signal-update publishing** — each change → a `SouthboundSignalUpdate` message on the UNS `data`
  class, published through the library **`data()` facade** (normalized `GOOD|BAD|UNCERTAIN` quality —
  passed explicitly from OPC UA's own `StatusCode`, never defaulted — + native `qualityRaw` +
  source/server timestamps + a per-sample adapter-receive `receivedTs` when it differs from
  `serverTs`), batched per signal, stamped with the top-level `identity`.
- **Command surface** — the standardized `cmd/sb/*` family on the library inbox: `sb/read`,
  allow-listed `sb/write` (confirmed, per-entry ack), `sb/browse` (hierarchical address-space refs),
  `sb/signals` (the configured inventory, each entry flagged `writable`), `sb/status`, `sb/rescan`, and
  the lifecycle-control verbs `sb/pause` / `sb/resume` (idempotent `{paused, changed}`), `reconnect`
  (`{connected}`), and `repoll` (`{polled}` — an immediate explicit read + republish, refused while
  paused with `PAUSED`). Every verb is **instance-scoped**: a request targets a device instance by the
  `{instance}` topic token or by an `instance` body selector (the two must agree, else `BAD_ARGS`),
  and an unaddressed request defaults to the sole connected instance; standardized error codes
  (`NO_SUCH_INSTANCE`, `BAD_ARGS`, `PAUSED`, `WRITE_NOT_ALLOWED`, `RECONNECT_FAILED`,
  `DEVICE_UNAVAILABLE`, …).
- **Edge-console panels** — an `overview` / `signals` / `diagnostics` / `address-space` panel trio
  bound to the served verbs (the overview panel drives the lifecycle-control verbs).
- **Events** — operator-facing `evt` alarms published through the library **`events()` facade**
  (channel `evt/{severity}/{type}`, derived from the body so it can never disagree with the topic):
  `evt/critical/connection-lost` (a stateful alarm — the raise and the connection-restored clear ride
  the same channel), `evt/warning/write-rejected`, and pause/resume events.
- **Secure connections** — `Basic256Sha256` / `SignAndEncrypt` with the client cert/key from the
  edgecommons **credentials vault**, a file, or a **PKCS#11** token; explicit server trust.
- **Health** — the canonical `southbound_health` metric (`connectionState`, `publishLatencyMs`,
  `pollLatencyMs`, `readErrors`, `staleSignals`, `reconnects`, `writeErrors`, `signalsSubscribed`)
  on the UNS `metric` class, alongside the `OpcUaCommand` / `OpcUaSubscription` / `OpcUaBrowse` /
  `OpcUaConnection` operational families. `staleSignals` is driven by `component.global.healthThresholds.staleSignalSecs`
  (default 30).

## Architecture

The component-level `CommandRegistry` registers the `sb/*` verbs once on the library inbox; the pure
`CommandRouter` routes each request to the right device by its `instance` field, applies the
standardized error codes, shapes the lifecycle-verb replies, and records the command metric. Routing is
written against the `DeviceSession` seam, so it is unit-tested with a fake session. One `OpcUaDevice`
(the live `DeviceSession`) per configured instance coordinates focused collaborators: `OpcUaConnection`
(connect + security) · `AddressSpaceBrowser` · `SubscriptionManager` · `SignalUpdatePublisher` (UNS
`data`, via the library `data()` facade, paused-gated and latency/staleness-instrumented) ·
`CommandService` (the read/write/browse logic) · the library `EventsFacade` (`instance.events()`, UNS
`evt`) · `ValueCodec` · `HealthState` (the §5 gauges + staleness tracker) · `HealthMetrics` ·
`OpcUaOperationalMetrics`.

The live Eclipse Milo driver seam (`OpcUaConnection`, `AddressSpaceBrowser`, `SubscriptionManager`,
`SignalUpdatePublisher`, `CommandService`, `OpcUaDevice`, the security layer) is validated against a
real server by [`validation/`](validation/); every broker-free decision above the seam is unit-tested
under the JaCoCo 90% line-coverage gate.

## Quickstart (HOST / MQTT, local)

```bash
mvn clean package      # -> target/opcua-adapter-1.0.0.jar (Java 25)
java -jar target/opcua-adapter-1.0.0.jar \
  --platform HOST --transport MQTT ./messaging-local.json \
  -c FILE ./config.json -t my-thing
```
Needs a local MQTT broker (e.g. `docker run -d -p 1883:1883 emqx/emqx`). Subscribe to
`ecv1/+/+/+/data/#` for signal updates (and `ecv1/+/+/+/state`, `ecv1/+/+/+/metric/#` for the
keepalive and health); drive `ecv1/{thing}/opcua-adapter/cmd/sb/status` for status. See
[docs/](docs/) for configuration, the message interface, security, and the other deploy targets. A
reproducible end-to-end smoke harness lives in [`validation/`](validation/).
