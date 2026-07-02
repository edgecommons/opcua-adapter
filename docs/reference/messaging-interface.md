# Reference — Messaging Interface & CLI

Complete specification of every topic and message the adapter publishes or accepts, and the
command-line flags. For the data-plane / control-plane model and the reasoning behind the contract,
see [explanation.md](../explanation.md); for client recipes, see the
[how-to guides](../how-to-guides.md).

## Envelope

All messages use the GGCommons JSON envelope:

```jsonc
{
  "header": {
    "name": "SouthboundSignalUpdate",   // message type
    "version": "1.0",
    "timestamp": "2026-06-28T12:00:00Z",
    "uuid": "…",
    "correlation_id": "…",           // present on replies (echoes the request)
    "reply_to": "…"                  // present on requests (reply destination)
  },
  "tags": { "thing": "<thingName>", "site": "plant1", … },
  "body": { … }                       // per message type, below
}
```

**Inbound leniency.** For messages the adapter *consumes* (write, read, control), the **topic**
selects the action; `header.name` is not validated, and a bare JSON object (no envelope) is accepted
as the body. Replies the adapter *sends* are always full envelopes.

**Request/reply.** For read and control queries, the client sets `header.reply_to` (a topic it
subscribes to) and a unique `header.correlation_id`; the adapter publishes the reply to `reply_to`
with the same `correlation_id`. A GGCommons client's `request()` API sets these automatically.

## Topics

| Plane | Message | Direction | Topic (default) | Reply |
|-------|---------|-----------|-----------------|-------|
| data | `SouthboundSignalUpdate` | adapter → bus | `southbound/{site}/{ComponentName}/{InstanceId}/{signalId}` | — |
| data | write | bus → adapter | `southbound/{ComponentName}/{InstanceId}/write` | `SouthboundWriteResult` (only when `reply_to` is set) |
| data | read | bus ↔ adapter | `southbound/{ComponentName}/{InstanceId}/read` | `SouthboundReadResult` |
| control | status | bus ↔ adapter | `southbound/{ComponentName}/{InstanceId}/control/status` | `status` |
| control | subscriptions | bus ↔ adapter | `southbound/{ComponentName}/{InstanceId}/control/subscriptions` | `subscriptions` |
| control | nodes | bus ↔ adapter | `southbound/{ComponentName}/{InstanceId}/control/nodes` | `nodes` |
| control | `southbound_health` | adapter → metric target | per `metricEmission` | — |

Topics are templated — see [configuration.md](configuration.md#template-variables). The control topic
is a fixed wildcard subscription, `…/control/+`.

## Sample object

The `value`/`quality`/timestamp shape used in both `SouthboundSignalUpdate` and `SouthboundReadResult`:

| Field | Type | Notes |
|-------|------|-------|
| `value` | number \| boolean \| string \| array | Numbers (incl. OPC UA unsigned) → JSON number; booleans → JSON boolean; `DateTime` → ISO-8601 string; **arrays → JSON array** (each element by these same rules); anything else → string. |
| `quality` | string | Normalized: `GOOD` \| `BAD` \| `UNCERTAIN`. |
| `qualityRaw` | string | Native OPC UA `StatusCode`. |
| `sourceTs` | string \| null | Device timestamp, ISO-8601 UTC. |
| `serverTs` | string \| null | Server timestamp, ISO-8601 UTC. |

The complete OPC UA ↔ JSON mapping for every type — reads and writes, with the corresponding KEP data
types — is in **[data-types.md](data-types.md)**.

## Data plane

### `SouthboundSignalUpdate` (adapter → bus)

Published when subscribed signal values change. One message carries one signal's `samples` (one or many,
per `publish.batchMs`). Topic: the instance `publish.topic`, with `{signalId}` = the node identifier.

```jsonc
"body": {
  "device": { "adapter": "opcua", "instance": "kep1", "endpoint": "opc.tcp://host:49320/" },
  "signal": {
    "id": "ns=2;s=Channel1.Device1.Sine1",         // canonical, stable id
    "name": "Sine1",                                 // displayName, else browseName
    "address": { "ns": 2, "namespaceUri": "urn:kepware:KEPServerEX", "nodeId": "Channel1.Device1.Sine1" }
  },
  "samples": [ { "value": 0.7071, "quality": "GOOD", "qualityRaw": "Good (0x00000000)",
                 "sourceTs": "2026-06-28T12:00:00.123Z", "serverTs": "2026-06-28T12:00:00.150Z" } ]
}
```

`signal.address` carries `ns` (the current namespace index), `namespaceUri` (the stable namespace
identity, when resolvable), and `nodeId` (the native identifier). Key consumers on `signal.id` or on
`namespaceUri` + `nodeId` rather than the bare index, which can change between servers or restarts.

### write (bus → adapter)

Writes one or many signal values. Requires `write.enabled: true`. Topic: `write.topic`. Always
performs the write; additionally replies with a `SouthboundWriteResult` when the request carries
`header.reply_to` (otherwise it is fire-and-forget, as before).

```jsonc
"body": { "writes": [ { "namespaceUri": "urn:kepware:KEPServerEX", "signalId": "Channel1.Device1.Setpoint",
                        "value": 42.5, "status": "GOOD", "sourceTs": "2026-06-28T12:00:00Z" } ] }
```
A single object (no `writes` array) is also accepted.

| Field | Required | Notes |
|-------|----------|-------|
| `namespaceUri` | preferred | namespace URI, resolved to the server's current index |
| `ns` | or `namespaceUri` | literal namespace index (used when `namespaceUri` is absent) |
| `signalId` | yes | node identifier (bare, as reported by the `nodes`/`subscriptions` queries) |
| `idType` | no (`String`) | identifier type — `Numeric` \| `String` \| `Guid` — needed to round-trip non-string node ids (numeric/GUID); echo back the `idType` from the `nodes` query |
| `value` | yes | coerced to the node's data type (below) |
| `status` | no (`GOOD`) | `GOOD` \| `BAD` \| `UNCERTAIN` |
| `sourceTs` | no | ISO-8601 source timestamp |

Entries missing a namespace (`namespaceUri` or `ns`), `signalId`, or `value` are marked `FAILED` (see
below) rather than sent to the server. The remaining, well-formed entries are issued as one OPC UA
`writeValues` call.
Supported value types (by the target node's data type): `Boolean`, `SByte`, `Byte`, `Int16`,
`UInt16`, `Int32`, `UInt32`, `Int64`, `UInt64`, `Float`, `Double`, `String`, and `DateTime`
(ISO-8601 string). To write an **array** signal, send a JSON array as `value` (e.g.
`"value": [1, 2, 3, 4]`); its elements are coerced to the node's element type and the length must
match the signal's array dimension.

#### `SouthboundWriteResult` (reply, only when `reply_to` is set)

One result per request entry, **in the same order** as `writes`:

```jsonc
// reply: header.name = "SouthboundWriteResult", published to reply_to
"body": {
  "id": "kep1",
  "writes": [
    { "namespaceUri": "urn:kepware:KEPServerEX", "signalId": "Channel1.Device1.Setpoint",
      "status": "SUCCESS", "qualityRaw": "Good (0x00000000)" },
    { "signalId": "Channel1.Device1.Bogus", "status": "FAILED", "message": "target is not a variable" }
  ]
}
```

Each entry echoes back whichever of `signalId` / `ns` / `namespaceUri` the request supplied, plus:

| Field | Notes |
|-------|-------|
| `status` | `SUCCESS` \| `FAILED` |
| `qualityRaw` | native OPC UA `StatusCode` (only for entries actually written) |
| `message` | present on `FAILED` — the reason (missing/unresolvable node, bad value, or the server's write status) |

### read (request/reply)

Reads arbitrary signals on demand. Request topic: `read.topic`.

```jsonc
// request body
"body": { "signals": [ { "namespaceUri": "urn:kepware:KEPServerEX", "signalId": "…Counter" }, { "ns": 2, "signalId": "…Setpoint" } ] }
```
```jsonc
// reply: header.name = "SouthboundReadResult", published to reply_to
"body": {
  "id": "kep1",
  "reads": [ { "signal": { "id": "ns=2;s=…Counter", "address": { "ns": 2, "namespaceUri": "urn:kepware:KEPServerEX", "nodeId": "…Counter" } },
               "value": 17, "quality": "GOOD", "qualityRaw": "Good (0x00000000)",
               "sourceTs": "…", "serverTs": "…" } ]
}
```
Each explicit signal is addressed by `namespaceUri` (preferred) or `ns` index, plus `signalId` (and an
optional `idType` — `Numeric`/`String`/`Guid`, default `String` — to address a numeric/GUID node id, as
reported by the `nodes` query). Signals that cannot be resolved are omitted from `reads`, so match
results by `signal` rather than position (there is no positional correspondence to `signals[i]`).
Explicit `signals[]` are read as given — order and duplicates preserved. A malformed `include`/`exclude`
matcher (bad regex or non-object entry) yields a reply with an `error` field and empty `reads` rather
than no reply.

**Regex include/exclude.** In addition to (or instead of) an explicit `signals[]` list, the request may
carry `include` (required to activate this path) and an optional `exclude` — the same **signal matcher**
shape used by `subscriptions[].include`/`exclude` ([configuration reference](configuration.md#signal-matcher-entries-of-include--exclude)):
`namespaceUri`/`namespace` + a `match` regex. `include` tests a node's identifier, browse name, and
display name; `exclude` tests the identifier only. The address space is scanned once per request; nodes
selected by `include` (and not excluded) are added to the read, deduplicated against any explicit
`signals[]` entries.

```jsonc
// request body: read everything under Channel1.Device1 except its Diagnostics subtree
"body": {
  "include": [ { "namespaceUri": "urn:kepware:KEPServerEX", "match": "^Channel1\\.Device1\\..*" } ],
  "exclude": [ { "namespaceUri": "urn:kepware:KEPServerEX", "match": "\\.Diagnostics\\." } ]
}
```

Omitting `include` reproduces the original explicit-list-only behavior exactly.

## Control plane

### status (request/reply)

Request topic `…/control/status` (any body). Reply `header.name` = `status`:

```jsonc
"body": {
  "id": "kep1",
  "connected": true,
  "metrics": { "read": { "interval": 1234, "total": 98765 }, "write": { "interval": 2, "total": 40 } }
}
```
`connected` is the OPC UA session state; `interval` counts reset each reporting cycle, `total` is
lifetime.

### subscriptions (request/reply)

Request topic `…/control/subscriptions` (any body). Reply `header.name` = `subscriptions`:

```jsonc
"body": { "id": "kep1", "signals": [ { "signalId": "…Sine1", "idType": "String", "namespace": 2, "namespaceUri": "urn:kepware:KEPServerEX", "match": "^…Sine.*" } ] }
```
Lists the signals currently resolved/subscribed, each signal's `idType` (`Numeric`/`String`/`Guid`/
`Opaque`, for round-tripping via read/write), the **resolved** namespace index and its URI, and the
matcher that selected each.

### nodes (request/reply)

Request topic `…/control/nodes` (optional body `{ "offset": <int>, "limit": <int> }`). Reply
`header.name` = `nodes`. Enumerates the variable nodes the adapter found while browsing the server's
address space at connect time — not just the ones matched by a subscription — for
discovery/commissioning tooling and to build `read`/`write`/subscription matchers against real node
identifiers.

**Paging.** A large address space (tens of thousands of nodes) would exceed the broker/IPC max message
size in a single reply, so the list is **paged over a stable snapshot**: `offset` (default `0`) and
`limit` (default `2000`) select a window, and the reply reports `total` (full node count), the applied
`offset`/`limit`, and `truncated` (`true` when more nodes remain past this window — page again with a
higher `offset`).

```jsonc
"body": {
  "id": "kep1",
  "total": 8123,
  "offset": 0,
  "limit": 2000,
  "truncated": true,
  "nodes": [
    { "signalId": "Channel1.Device1.Sine1", "namespace": 2, "idType": "String",
      "namespaceUri": "urn:kepware:KEPServerEX", "name": "Sine1", "dataType": "Double" },
    { "signalId": "1001", "namespace": 2, "idType": "Numeric", "namespaceUri": "urn:kepware:KEPServerEX" }
  ]
}
```

| Field | Notes |
|-------|-------|
| `signalId` | node identifier (bare) |
| `namespace` | the node's current namespace index |
| `idType` | identifier type — `Numeric` \| `String` \| `Guid` \| `Opaque` — echo it back on a read/write to round-trip a non-string node id |
| `namespaceUri` | that namespace's URI, when resolvable |
| `name` | display name, else browse name; omitted if neither is set |
| `dataType` | the node's OPC UA scalar type name (e.g. `Double`), or its raw type NodeId if unrecognized; omitted if unreadable |

A node that cannot be read (e.g. a transient browse error) is silently skipped rather than failing the
whole query.

### `southbound_health` (metric)

Emitted via the configured `metricEmission.target`.

| Measure | Unit | Meaning |
|---------|------|---------|
| `connectionState` | Count | `1` connected, `0` down |
| `readErrors` | Count | read errors over the interval |

Dimensions: `instance` (plus auto-injected `coreName`/`component`).

## CLI

| Flag | Values | Notes |
|------|--------|-------|
| `--platform` | `GREENGRASS` \| `HOST` \| `KUBERNETES` \| `auto` | Default `auto`. |
| `--transport` | `IPC` \| `MQTT [path]` | Defaults from the platform; `IPC` only on GREENGRASS. |
| `-c/--config` | `FILE <path>` \| `ENV` \| `GG_CONFIG` \| `SHADOW` \| `CONFIG_COMPONENT` \| `CONFIGMAP` | Default from the platform. |
| `-t/--thing` | `<name>` | IoT Thing name; also `{ThingName}` in topics. |
