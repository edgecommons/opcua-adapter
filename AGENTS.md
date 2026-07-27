# opcua-adapter

Load-bearing context for an AI coding agent (and a human contributor) working in this repo. Keep it in
sync with the component as you change it, the same way you would keep a README accurate.

## What this is

`opcua-adapter` (Greengrass component name `com.mbreissi.edgecommons.OpcUaAdapter`) is the **reference
Java southbound protocol adapter** for EdgeCommons. It connects to OPC UA servers over Eclipse Milo,
subscribes to signals, publishes their value changes onto the UNS `data` class as
`SouthboundSignalUpdate`, and serves the standardized `cmd/sb/*` command surface — so a consumer can
chart an OPC UA node without knowing the protocol. One OPC UA **server** is one
`component.instances[]` entry.

## The shape (the seam you implement)

The component-level `CommandRegistry` registers the `sb/*` verbs once on the library command inbox
via `registerScoped` (the inbox is one-per-component, subscribes both D-U28 command scopes, and hands
handlers the delivery topic's `{instance}` token; this adapter is multi-instance). The **pure
`CommandRouter`** routes each request to a device — the topic-addressed instance is authoritative
(a conflicting `body.instance` is `BAD_ARGS`); component-scoped requests route by the `instance`
body field — enforces the standardized error-code family, shapes
the lifecycle-verb replies, and records the `OpcUaCommand` metric — all against the **`DeviceSession`**
seam, so the whole dispatch/routing/lifecycle contract is unit-tested with a fake session, no live
server. `OpcUaDevice` is the live `DeviceSession`: it owns the Milo client (`OpcUaConnection`), the
address-space cache (`AddressSpaceBrowser`), the subscriptions (`SubscriptionManager`), the publisher
(`SignalUpdatePublisher`), the read/write/browse engine (`CommandService`), and the health state
(`HealthState` → `HealthMetrics`).

Everything above the seam is protocol-free. The live Milo layer is the driver seam — replace-or-mock
it, don't leak it upward: `CommandRouter`/`HealthState`/`ValueCodec`/the config model import nothing
from Eclipse Milo's client.

## The `sb/*` command family (docs/SOUTHBOUND.md §2.2)

`sb/status` (adds `paused`), `sb/browse` (hierarchical, `depth` 1..4 / `maxRefs` 1..1000 clamped),
`sb/read` (ref-accepting, regex
include/exclude), `sb/write` (confirmed, allow-listed batch), `sb/signals` (configured inventory +
`writable` flag), `sb/rescan`, plus the lifecycle-control family `sb/pause` / `sb/resume` (idempotent
`{paused, changed}`), `reconnect` (`{connected}`), and `repoll` (`{polled}` — an immediate explicit
read + republish; refused while paused with `PAUSED`). Standardized error codes: `NO_SUCH_INSTANCE`,
`BAD_ARGS`, `PAUSED`, `WRITE_NOT_ALLOWED`, `RECONNECT_FAILED`, `DEVICE_UNAVAILABLE`. **These are
wire-visible strings** — a change here is a breaking wire change (see `DESIGN.md`'s register). Keep the
command surface consistent with `docs/reference/messaging-interface.md`.

## Metrics (docs/reference/metrics.md)

`southbound_health` carries **exactly** the SOUTHBOUND.md §5 eight-measure set: `connectionState`,
`publishLatencyMs`, `pollLatencyMs`, `readErrors`, `staleSignals`, `reconnects`, `writeErrors`, and
`signalsSubscribed`. `writeErrors` counts **device-path** failures only (an entry that passed
validation + `writes.allow[]` and then failed at the server or was aborted by an unavailable session —
policy refusals and caller errors do not count, mirroring `readErrors`). `signalsSubscribed` is a
gauge: the monitored-item count the session currently serves while connected, 0 while disconnected.
`staleSignals` is driven by `component.global.healthThresholds.staleSignalSecs`
(default 30) via `HealthState`'s per-signal last-update tracker. The operational families
(`OpcUaCommand` — including the `CommandRequest*`/`CommandFailure*` counters, `OpcUaSubscription`,
`OpcUaBrowse`, `OpcUaConnection`) use `(total, interval)` pairs dimensioned by `instance` only — keep
dimensions low-cardinality (never a node id, endpoint, or error string).

## Config

Adapter config lives under `component.global` / `component.instances[]`, validated by
`config.schema.json` (`edgecommons component validate`). `component.global` carries `defaults` and
`healthThresholds`; each instance carries `connection` (endpoint + security), `defaults`, `publish`,
`writes.allow[]`, and `subscriptions[]`. The sibling envelope sections (`hierarchy`, `identity`,
`messaging`, `logging`, `heartbeat`, `metricEmission`, `credentials`) are the canonical EdgeCommons
schema and are not redeclared here. See `docs/reference/configuration.md` and
`test-configs/opcua-adapter.json`.

## Validation expectations

- `mvn verify` passes with **no live infrastructure** (the unit suite exercises the pure logic and the
  seam via fakes) and enforces the org **90% line-coverage** gate (JaCoCo `check`). The gate's
  `<excludes>` scope out **only** the live Milo driver seam + `main()` bootstrap
  (`OpcUaAdapter`, `OpcUaConnection`, `AddressSpaceBrowser`, `SubscriptionManager`,
  `SignalUpdatePublisher`, `CommandService`, `OpcUaDevice`, `CommandRegistry`, `opc/security/**`) —
  code that cannot run without a live OPC UA session and a running EdgeCommons, validated on real
  infrastructure. Every broker-free decision stays in the gate and is unit-tested. Don't lower the gate
  or widen the excludes to pass — add tests.
- The live seam is validated by [`validation/`](validation/) against KEPServerEX (192.168.1.180) and by
  the Greengrass lab deploy (`lab-5950x`). Wire-contract or metric changes require both.
- Always unsubscribe / handle SIGTERM — the library wires graceful shutdown; don't leak subscriptions.

## Org conventions this component inherits

- **UNS grammar:** `ecv1/{device}/{component}/{instance}/{class}[/channel]`; reserved classes
  (`state`, `metric`, `cfg`, `log`) are library-owned — publish through `data()`/`events()`, never a
  hand-built topic or envelope.
- **Southbound contract:** a data point is a **signal**, not a tag. Quality is normalized to
  `GOOD | BAD | UNCERTAIN` with the native OPC UA `StatusCode` preserved in `qualityRaw`. Timestamps
  follow the SOUTHBOUND.md §2 four-slot model: OPC UA SourceTimestamp → `sourceTs`, ServerTimestamp →
  `serverTs`, and the adapter-receive moment rides as the additive per-sample `receivedTs` extra when
  it differs from `serverTs` (captured at receive, not publish — batching diverges them).
- **Writes are allow-listed** by stable `signal.id`, checked before any device I/O. An empty
  `writes.allow` means read-only — the correct default for anything touching a control system.
- **Kebab artifact naming:** the Maven `artifactId`/jar, the `recipe.yaml` artifact URI + Run script,
  the `Dockerfile`, the k8s resource names, and `test-configs/` are kebab `opcua-adapter`; only the
  Greengrass **component name** stays PascalCase reverse-DNS (`com.mbreissi.edgecommons.OpcUaAdapter`).
