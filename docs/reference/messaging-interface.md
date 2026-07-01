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
| data | write | bus → adapter | `southbound/{ComponentName}/{InstanceId}/write` | — |
| data | read | bus ↔ adapter | `southbound/{ComponentName}/{InstanceId}/read` | `SouthboundReadResult` |
| control | status | bus ↔ adapter | `southbound/{ComponentName}/{InstanceId}/control/status` | `status` |
| control | subscriptions | bus ↔ adapter | `southbound/{ComponentName}/{InstanceId}/control/subscriptions` | `subscriptions` |
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

Writes one or many signal values. Fire-and-forget (no reply). Requires `write.enabled: true`. Topic:
`write.topic`.

```jsonc
"body": { "writes": [ { "namespaceUri": "urn:kepware:KEPServerEX", "signalId": "Channel1.Device1.Setpoint",
                        "value": 42.5, "status": "GOOD", "sourceTs": "2026-06-28T12:00:00Z" } ] }
```
A single object (no `writes` array) is also accepted.

| Field | Required | Notes |
|-------|----------|-------|
| `namespaceUri` | preferred | namespace URI, resolved to the server's current index |
| `ns` | or `namespaceUri` | literal namespace index (used when `namespaceUri` is absent) |
| `signalId` | yes | node identifier |
| `value` | yes | coerced to the node's data type (below) |
| `status` | no (`GOOD`) | `GOOD` \| `BAD` \| `UNCERTAIN` |
| `sourceTs` | no | ISO-8601 source timestamp |

Entries missing a namespace (`namespaceUri` or `ns`), `signalId`, or `value` are skipped. Writes are
issued as one OPC UA `writeValues` call.
Supported value types (by the target node's data type): `Boolean`, `SByte`, `Byte`, `Int16`,
`UInt16`, `Int32`, `UInt32`, `Int64`, `UInt64`, `Float`, `Double`, `String`, and `DateTime`
(ISO-8601 string). To write an **array** signal, send a JSON array as `value` (e.g.
`"value": [1, 2, 3, 4]`); its elements are coerced to the node's element type and the length must
match the signal's array dimension.

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
Each signal is addressed by `namespaceUri` (preferred) or `ns` index, plus `signalId`. `reads[i]`
corresponds to `signals[i]`; each entry carries the sample fields above plus its own `signal`. Signals that
cannot be resolved are omitted from `reads`, so match results by `signal` rather than position.

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
"body": { "id": "kep1", "signals": [ { "signalId": "…Sine1", "namespace": 2, "namespaceUri": "urn:kepware:KEPServerEX", "match": "^…Sine.*" } ] }
```
Lists the signals currently resolved/subscribed, the **resolved** namespace index and its URI, and the
matcher that selected each.

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
