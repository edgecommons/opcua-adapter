# Reference — Messaging Interface & CLI

Complete specification of every UNS topic and message the adapter publishes or accepts, and the
command-line flags. For the data-plane / control-plane model and the reasoning behind the contract,
see [explanation.md](../explanation.md); for client recipes, see the
[how-to guides](../how-to-guides.md).

> **Unified Namespace (UNS).** This adapter uses the edgecommons UNS core. All topics follow
> the grammar `ecv1/{device}/{component}/{instance}/{class}[/{channel…}]`, minted by the library's
> topic builder (`gg.instance(id).uns()`) — never hand-assembled. The site hierarchy rides the
> top-level envelope **`identity`** element (not the topic, not `tags`).
>
> **Class-publish facades.** The `data` and `evt` classes are published through the library's
> `data()`/`events()` facades, not a hand-built `messaging.publish(...)` call:
> the facade constructs and validates the body (quality defaulted to `GOOD` when the adapter doesn't
> supply one — OPC UA always supplies its own `StatusCode`-derived quality explicitly, so this default
> never fires for `data`), mints the sanitized channel, and stamps the envelope identity. This is also
> why `evt`'s channel is always `evt/{severity}/{type}` — the facade derives it from the body's own
> `severity` + `type`, so the two can never disagree.

## Envelope

All messages use the EdgeCommons JSON envelope, `{header, identity, tags, body}`:

```jsonc
{
  "header": {
    "name": "SouthboundSignalUpdate",   // message type (a cmd reply's name is the verb, e.g. "sb/read")
    "version": "1.0",
    "timestamp": "2026-07-03T12:00:00Z",
    "uuid": "…",
    "correlation_id": "…",           // present on replies (echoes the request)
    "reply_to": "…"                  // present on requests (reply destination)
  },
  "identity": {                      // stamped automatically by the library (per-instance)
    "hier": [ { "level": "site", "value": "site1" }, { "level": "shop", "value": "shop1" },
              { "level": "line", "value": "line1" }, { "level": "device", "value": "gw-01" } ],
    "path": "site1/shop1/line1/gw-01",
    "component": "opcua-adapter",
    "instance": "kep1"
  },
  "tags": { "appId": "adapter", … },  // arbitrary business metadata only — no `thing` key
  "body": { … }                       // per message type, below
}
```

**Identity element.** The enterprise hierarchy comes from the top-level `hierarchy`/`identity`
config blocks (the last hierarchy level is the device = the resolved thing name). Every published
message is stamped with `identity = {hier, path, component, instance}`; routing/partitioning never
requires parsing the body *or* the topic.

**Inbound leniency.** A command request's **verb** is the `cmd` topic's channel (after `cmd/`) and the
envelope's `header.name` must equal it (the library inbox enforces this); the request `body` is the
verb's argument object. A EdgeCommons client's `request()` API sets `header.name`/`reply_to`/
`correlation_id` automatically.

## Topics (UNS classes)

| Class | Message | Direction | Topic | Owner |
|-------|---------|-----------|-------|-------|
| `data` | `SouthboundSignalUpdate` | adapter → bus | `ecv1/{device}/{component}/{instance}/data/{signalPath}` | adapter, via `data()` |
| `cmd` | `sb/*` verbs (below) | bus ↔ adapter | `ecv1/{device}/{component}[/{instance}]/cmd/sb/{verb}` | library inbox |
| `evt` | connection/write alarms | adapter → bus | `ecv1/{device}/{component}/{instance}/evt/{severity}/{type}` | adapter, via `events()` |
| `metric` | `southbound_health`, `OpcUaCommand`, `OpcUaSubscription`, `OpcUaBrowse`, `OpcUaConnection` | adapter → bus | `ecv1/{device}/{component}/metric/{metricName}` | library (via `MetricEmitter`) |
| `state` | keepalive | adapter → bus | `ecv1/{device}/{component}/state` | library (heartbeat) |
| `cfg` | effective config | adapter → bus | `ecv1/{device}/{component}/cfg` | library |

- `{device}` is the resolved thing name (the last hierarchy level); `{component}` is `opcua-adapter`;
  `{instance}` is the device instance id (`instances[].id`). A fleet consumer subscribes the six UNS
  wildcards (`ecv1/+/+/+/{state|cfg|evt|metric|data|log}`).
- `{signalPath}` is the OPC UA node's bare identifier **sanitized to a single channel token**
  (`/ \ + #` and control chars → `_`, `..` collapsed) so it is always within the IoT-Core topic-depth
  guard. The **stable** `signal.id` in the body (the `ns=…;…=…` parseable form) is what consumers key
  on; the path is only the routing address.
- **`state`/`metric`/`cfg`/`log` are reserved** (library-owned): a component publishing to them
  directly is rejected. The adapter's metrics reach `metric/{metricName}` through the
  metric subsystem, not a raw publish.
- **The command inbox is one per component and subscribes both command scopes** —
  component-scope (`ecv1/{device}/{component}/cmd/#`) and instance-scope
  (`ecv1/{device}/{component}/+/cmd/#`). Every `sb/*` verb is instance-scoped: the addressed instance
  is the `{instance}` topic token, else the request's `instance` body field (see below).

## The command surface — `cmd/sb/*` verbs

The adapter registers its southbound verbs on the library command inbox. A request is a `cmd`
envelope whose `header.name` equals the verb; a reply carries the responder's `identity` and a uniform
body:

```jsonc
// success                              // error
{ "ok": true,  "result": { … } }        { "ok": false, "error": { "code": "…", "message": "…" } }
```

The reply's `header.name` is the verb (e.g. `sb/write`), with the request's `correlation_id`.

**Verb scope.** Every verb declares its scope at registration, and the scope is advertised in the
`describe` reply (`commands[].scope`) so a console knows whether to offer an instance selector. All
ten `sb/*` verbs below are **`instance`**-scoped — each one acts on exactly one OPC UA server.

**Instance addressing.** The adapter is multi-instance behind one per-component inbox. The addressed
instance is resolved before the verb runs:

- **Topic-addressed (instance scope).** Send the command to
  `ecv1/{device}/{component}/{instance}/cmd/sb/{verb}` — the `{instance}` topic token is the
  addressed instance.
- **Body selector.** Send the command to `ecv1/{device}/{component}/cmd/sb/{verb}` with an
  **`"instance"`** body field naming the target.
- **Conflict.** A `body.instance` that disagrees with the `{instance}` topic token is refused with
  `BAD_ARGS` before the verb runs — drop the body field or make them agree.
- **Neither.** When exactly one instance is connected, an unaddressed request defaults to it; with
  several connected it returns `BAD_ARGS`.

Naming an unconnected/unknown instance (or addressing the adapter when none is connected) returns
`NO_SUCH_INSTANCE`.

| Verb | Scope | Kind | Purpose |
|------|-------|------|---------|
| `sb/status` | `instance` | request/reply | instance/connection status + counters (includes `state` and `paused`) |
| `sb/browse` | `instance` | request/reply | browse hierarchical address-space references |
| `sb/read` | `instance` | request/reply | on-demand read (explicit list and/or regex include/exclude) |
| `sb/write` | `instance` | request/reply, **confirmed** | allow-listed batch write, per-entry ack |
| `sb/signals` | `instance` | request/reply | the currently resolved/subscribed signals (each with `writable`) |
| `sb/rescan` | `instance` | request/reply | re-browse the address space and refresh the node cache |
| `sb/pause` | `instance` | request/reply, **confirmed** | suspend publishing for the instance (idempotent) |
| `sb/resume` | `instance` | request/reply, **confirmed** | resume publishing for the instance (idempotent) |
| `reconnect` | `instance` | request/reply, **confirmed** | drop and re-establish the OPC UA session |
| `repoll` | `instance` | request/reply, **confirmed** | immediately read every subscribed signal and republish on `data` |

The library also provides the built-in verbs `ping`, `reload-config`, and `get-configuration` on the
same inbox (out of the box; not southbound-specific). `reload-config` re-reads the configuration
document; adapter settings (endpoints, security, subscriptions, timing, `writes.allow[]`) are applied
at startup, so restart the component for a configuration change to take effect.

**Request bounds.** `sb/read` accepts at most `component.global.limits.maxReadTargets` signals (1000
by default) and `sb/write` at most `maxWriteTargets` entries; a larger request is refused with
`BAD_ARGS` rather than silently truncated, so a caller never mistakes a partial result for a complete
one. An `include`/`exclude` matcher that is over-long or too expensive to evaluate is likewise refused
with `BAD_ARGS`. Every OPC UA service call is bounded by `limits.commandTimeoutMs` (15 s by default)
and answers `DEVICE_UNAVAILABLE` on expiry, and large batches are split to the server's published
`MaxNodesPerRead`/`MaxNodesPerWrite` operation limits.

## Sample object

The `value`/`quality`/timestamp shape used in both `SouthboundSignalUpdate` and the `sb/read` result:

| Field | Type | Notes |
|-------|------|-------|
| `value` | number \| boolean \| string \| array | Numbers (incl. OPC UA unsigned) → JSON number; booleans → JSON boolean; `DateTime` → ISO-8601 string; **arrays → JSON array** (each element by these same rules); anything else → string. |
| `quality` | string | Normalized: `GOOD` \| `BAD` \| `UNCERTAIN`. |
| `qualityRaw` | string | Native OPC UA `StatusCode`. |
| `sourceTs` | string \| null | The **machine** timestamp (OPC UA SourceTimestamp), ISO-8601 UTC; present only when the server supplied it — never synthesized. |
| `serverTs` | string \| null | The **capture** timestamp (OPC UA ServerTimestamp — the OPC UA server's stamp), ISO-8601 UTC. |
| `receivedTs` | string | The **adapter receive** timestamp, ISO-8601 UTC — when the value arrived at the adapter from the mediating OPC UA server. Additive per-sample field, present only when it differs from the sample's `serverTs` (for this mediated protocol it does, including when the server supplied no `serverTs`). |

The envelope `header.timestamp` is the **publish** timestamp — under batching (`publish.batchMs`),
`receivedTs` and the publish moment diverge, so each sample keeps its own receive stamp.

The complete OPC UA ↔ JSON mapping for every type is in **[data-types.md](data-types.md)**.

## Data plane

### `SouthboundSignalUpdate` (adapter → bus, `data` class)

Published when subscribed signal values change, through the library `data()` facade (`instance.data()
.signal(id).name(…).address(…).device(…).addSamples(…).signalPath(…).publish()` —
`SignalUpdatePublisher`): the facade constructs this body, defaults an omitted `quality` to `GOOD` and
an omitted `serverTs` to now, and mints the topic. OPC UA always supplies its own `StatusCode`-derived
`quality` explicitly (`ValueCodec.normalizeQuality`), so the facade's default never fires here — it
exists for sources with no native quality notion. One message carries one signal's `samples` (one or
many, per `publish.batchMs`). Topic: `ecv1/{device}/{component}/{instance}/data/{signalPath}`.

```jsonc
"body": {
  "device": { "adapter": "opcua", "instance": "kep1", "endpoint": "opc.tcp://host:49320/" },
  "signal": {
    "id": "ns=2;s=Channel1.Device1.Sine1",         // canonical, stable id (consumers key on this)
    "name": "Sine1",                                 // displayName, else browseName
    "address": { "ns": 2, "namespaceUri": "urn:kepware:KEPServerEX", "nodeId": "Channel1.Device1.Sine1" }
  },
  "samples": [ { "value": 0.7071, "quality": "GOOD", "qualityRaw": "Good (0x00000000)",
                 "sourceTs": "2026-07-03T12:00:00.123Z", "serverTs": "2026-07-03T12:00:00.150Z",
                 "receivedTs": "2026-07-03T12:00:00.190Z" } ]
}
```

`signal.address` carries `ns` (the current namespace index), `namespaceUri` (the stable namespace
identity, when resolvable), and `nodeId` (the native identifier). Key consumers on `signal.id` or on
`namespaceUri` + `nodeId` rather than the bare index, which can change between servers or restarts.

### `sb/write` (confirmed, allow-listed batch)

Writes one or many signal values. A target is written **only** when its stable `signal.id` is in the
instance's `writes.allow[]`. Request body (a single object without the `writes` array is also
accepted):

```jsonc
"body": {
  "instance": "kep1",
  "writes": [ { "namespaceUri": "urn:kepware:KEPServerEX", "signalId": "Channel1.Device1.Setpoint",
                "value": 42.5, "status": "GOOD", "sourceTs": "2026-07-03T12:00:00Z" } ]
}
```

| Field | Required | Notes |
|-------|----------|-------|
| `instance` | if >1 instance | target device instance id |
| `namespaceUri` | preferred | namespace URI, resolved to the server's current index |
| `ns` | or `namespaceUri` | literal namespace index (used when `namespaceUri` is absent) |
| `signalId` | yes | node identifier (bare, as reported by `sb/browse`/`sb/signals`) |
| `idType` | no (`String`) | identifier type — `Numeric` \| `String` \| `Guid` — needed to round-trip numeric/GUID node ids |
| `value` | yes | coerced to the node's data type |
| `status` | no (`GOOD`) | `GOOD` \| `BAD` \| `UNCERTAIN` |
| `sourceTs` | no | ISO-8601 source timestamp |

Supported value types (by target data type): `Boolean`, `SByte`, `Byte`, `Int16`, `UInt16`, `Int32`,
`UInt32`, `Int64`, `UInt64`, `Float`, `Double`, `String`, and `DateTime` (ISO-8601 string). To write
an **array**, send a JSON array as `value`; elements are coerced to the node's element type.

The result confirms every request entry **in order**:

```jsonc
// reply body (header.name = "sb/write")
{ "ok": true, "result": {
    "id": "kep1",
    "writes": [
      { "namespaceUri": "urn:kepware:KEPServerEX", "signalId": "Channel1.Device1.Setpoint",
        "status": "SUCCESS", "qualityRaw": "Good (0x00000000)" },
      { "signalId": "Channel1.Device1.Locked", "status": "FAILED",
        "message": "write not allowed by writes.allow[]" }
    ] } }
```

| Entry field | Notes |
|-------------|-------|
| `status` | `SUCCESS` \| `FAILED` |
| `qualityRaw` | native OPC UA `StatusCode` (entries actually written) |
| `message` | present on `FAILED` — reason (not allow-listed, missing/unresolvable node, bad value, or server status) |

A target rejected by the allow-list is confirmed `FAILED` (never issued) **and** raises
`evt/warning/write-rejected`. Allowed writes in the same batch still proceed — the batch is not failed
wholesale.

### `sb/read` (on-demand)

Reads arbitrary signals on demand. Request body:

```jsonc
"body": { "instance": "kep1",
          "signals": [ { "namespaceUri": "urn:kepware:KEPServerEX", "signalId": "…Counter" }, { "ns": 2, "signalId": "…Setpoint" } ] }
```
```jsonc
// reply body (header.name = "sb/read")
{ "ok": true, "result": {
    "id": "kep1",
    "reads": [ { "signal": { "id": "ns=2;s=…Counter", "address": { "ns": 2, "namespaceUri": "urn:kepware:KEPServerEX", "nodeId": "…Counter" } },
                 "value": 17, "quality": "GOOD", "qualityRaw": "Good (0x00000000)",
                 "sourceTs": "…", "serverTs": "…", "receivedTs": "…" } ] } }
```

Each explicit signal is addressed by `namespaceUri` (preferred) or `ns` index, plus `signalId` (and an
optional `idType` for numeric/GUID node ids). Explicit `signals[]` are read as given — order and
duplicates preserved. Signals that cannot be resolved are omitted from `reads`, so match results by
`signal`, not position.

**Regex include/exclude.** In addition to (or instead of) `signals[]`, the request may carry `include`
(required to activate this path) and an optional `exclude` — the same **signal matcher** shape as
`subscriptions[].include`/`exclude`
([configuration reference](configuration.md#signal-matcher-entries-of-include--exclude)). `include`
tests identifier/browse/display name; `exclude` tests the identifier only. Nodes selected by `include`
(and not excluded) are appended and deduplicated against the explicit list. A malformed matcher (bad
regex or non-object entry) fails the request with a `BAD_ARGS` error.

```jsonc
"body": { "instance": "kep1",
  "include": [ { "namespaceUri": "urn:kepware:KEPServerEX", "match": "^Channel1\\.Device1\\..*" } ],
  "exclude": [ { "namespaceUri": "urn:kepware:KEPServerEX", "match": "\\.Diagnostics\\." } ] }
```

## Control plane

### `sb/status`

Request body `{ "instance": "kep1" }` (optional selector). Result:

```jsonc
{ "ok": true, "result": {
    "id": "kep1", "connected": true, "state": "ONLINE", "paused": false,
    "metrics": {
      "subscribedRead": { "interval": 1234, "total": 98765 },
      "readRequest": { "interval": 2, "total": 40 },
      "writeRequest": { "interval": 1, "total": 12 } } } }
```
`connected` is the OPC UA session state; `state` is the richer condition token
(`CONNECTING` | `ONLINE` | `BACKOFF` | `PAUSED` — the same value the `state` keepalive reports for
this instance); `paused` is `true` while publishing is suspended for the
instance (see `sb/pause`/`sb/resume`). `subscribedRead` counts data-change samples from active
subscriptions, `readRequest` counts explicit `sb/read` samples returned, and `writeRequest` counts
explicit `sb/write` attempts issued to the server. The status reply also includes the operational
counters used by the OPC UA metric families below (`readFailure`, `writeFailure`, browse counters,
subscription re-establishment counters, connection counters, `subscriptionCount`, and
`monitoredItemCount`). `interval` counts reset each reporting cycle; `total` is lifetime.

### `sb/signals`

Result lists the currently resolved/subscribed signals, each signal's `idType`, the **resolved**
namespace index and its URI, the matcher that selected it, and whether it is `writable`:

```jsonc
{ "ok": true, "result": { "id": "kep1", "signals": [
    { "signalId": "nsu=urn:kepware:KEPServerEX;s=…Sine1", "idType": "String", "namespace": 2,
      "namespaceUri": "urn:kepware:KEPServerEX", "match": "^…Sine.*", "writable": false } ] } }
```

| Field | Notes |
|-------|-------|
| `signalId` | the stable `signal.id`, in the canonical `nsu=<namespaceUri>;<type>=<identifier>` form (`ns=0;…` in namespace 0) — the same value published in the telemetry body and the key `writes.allow[]` matches |
| `idType` | `Numeric` \| `String` \| `Guid` |
| `namespace` | the namespace index this session resolved the signal at — a per-session handle, useful for diagnostics but not an identity |
| `namespaceUri` | that namespace's URI, when resolvable |
| `match` | the matcher that selected the signal |
| `writable` | `true` iff the signal's stable `signal.id` is in the instance's `writes.allow[]` (i.e. `sb/write` may write it) |

### `sb/browse` (hierarchical refs)

Request body optional `{ "instance": "kep1", "ref": "root", "depth": 1, "maxRefs": 500 }`. Browses
forward OPC UA `HierarchicalReferences` from the requested root and returns a nested tree of
references. `ref` may be `"root"` (default), `"objects"`, `"types"`, `"views"`, a parseable OPC UA
NodeId such as `"ns=2;s=Channel1.Device1"`, or a normal command ref object with `namespaceUri`/`ns`,
`signalId`, and optional `idType`.

`depth` defaults to `1` and is clamped to `1..4`. `maxRefs` defaults to `500` and is clamped to
`1..1000`; when the adapter has to stop before returning every requested descendant, `truncated` is
`true`.

```jsonc
{
  "ok": true,
  "result": {
    "id": "kep1",
    "mode": "hierarchical",
    "ref": "ns=0;i=84",
    "depth": 1,
    "maxRefs": 500,
    "refCount": 1,
    "truncated": false,
    "root": {
      "nodeId": "ns=0;i=84",
      "signalId": "84",
      "namespace": 0,
      "namespaceUri": "http://opcfoundation.org/UA/",
      "idType": "Numeric",
      "name": "Root",
      "browseName": "Root",
      "nodeClass": "Object",
      "refs": [
        {
          "referenceTypeId": "ns=0;i=35",
          "referenceType": "Organizes",
          "isForward": true,
          "targetNodeId": "ns=0;i=85",
          "target": {
            "nodeId": "ns=0;i=85",
            "signalId": "85",
            "namespace": 0,
            "idType": "Numeric",
            "name": "Objects",
            "browseName": "Objects",
            "nodeClass": "Object",
            "refs": []
          }
        }
      ]
    }
  }
}
```

| Node field | Notes |
|------------|-------|
| `nodeId` | parseable OPC UA NodeId, best for follow-up browse calls |
| `signalId` | bare node identifier; with `namespaceUri`/`ns` and `idType`, usable for read/write refs |
| `namespace` | the node's current namespace index |
| `idType` | `Numeric` \| `String` \| `Guid` \| `Opaque` — echo `Numeric`/`Guid` on a read/write to round-trip a non-string id. `Opaque` is reported for discovery only: it is **not** writable/round-trippable (a write/read reconstructs it as a `String` id), matching the `sb/write` field table's `Numeric` \| `String` \| `Guid`. |
| `namespaceUri` | that namespace's URI, when resolvable |
| `name` | display name, else browse name; omitted if neither is set |
| `browseName` | OPC UA browse name, when available |
| `nodeClass` | OPC UA node class, such as `Object`, `Variable`, or `Method` |
| `dataType` | the node's OPC UA scalar type name (e.g. `Double`), or its raw type NodeId; omitted if unreadable |
| `refs` | outgoing hierarchical references; each ref carries `referenceType`, `referenceTypeId`, `targetNodeId`, and a nested `target` when resolvable |

### `sb/rescan`

Re-browses the server's address space and refreshes the node cache used by `sb/browse`/`sb/read` in
place (live subscriptions are unaffected). Result `{ "id": "kep1", "total": <n>, "rescanned": true }`.

The cache is replaced only when the traversal completed. If the browse failed part-way, hit its node
or depth budget, or the session is down, the existing cache is **kept** and the reply reports
`{ "rescanned": false, "error": "<reason>", "total": <unchanged count> }` — a partial traversal would
otherwise erase a healthy address space while reporting success.

### `sb/pause` (confirmed)

Suspends publishing for the instance. The OPC UA subscriptions stay up on the server; value changes are
dropped rather than forwarded while paused. Idempotent — pausing an already-paused instance succeeds and
reports `changed: false`. Request body may carry the `instance` selector. Result:

```jsonc
{ "ok": true, "result": { "id": "kep1", "paused": true, "changed": true } }
```

`changed` is `true` only when this call moved the instance from running to paused.

### `sb/resume` (confirmed)

Resumes publishing for the instance. Idempotent — resuming an already-running instance succeeds and
reports `changed: false`. Request body may carry the `instance` selector. Result:

```jsonc
{ "ok": true, "result": { "id": "kep1", "paused": false, "changed": true } }
```

`changed` is `true` only when this call moved the instance from paused to running.

### `reconnect` (confirmed)

Drops and re-establishes the OPC UA session, making one immediate connection attempt on the same Milo
client. Request body may carry the `instance` selector. Result:

```jsonc
{ "ok": true, "result": { "id": "kep1", "connected": true } }
```

`connected` reflects the session state after the attempt. If the reconnect attempt fails the request
returns error code `RECONNECT_FAILED`.

### `repoll` (confirmed)

Immediately reads every subscribed signal and republishes the results onto the `data` class — the
subscribe-model adapter's "refresh now". Request body may carry the `instance` selector. Result:

```jsonc
{ "ok": true, "result": { "id": "kep1", "polled": 128 } }
```

`polled` is the number of signals read and republished. `repoll` is **refused while the instance is
paused** with error code `PAUSED`; if the OPC UA session is down it returns `DEVICE_UNAVAILABLE`.

## Events (`evt` class)

Operator-facing alarms/events, published through the library `events()` facade
(`instance.events().raiseAlarm(…)/.clearAlarm(…)/.emit(…)`) onto
`ecv1/{device}/{component}/{instance}/evt/{severity}/{type}` (message name `evt`, version `1.0`). The
facade **derives the channel from the body's own `severity` + `type`**, so the topic and body can never
disagree — the same mechanism the `modbus-adapter` uses, so both adapters' `evt` channels are
uniformly `{severity}/{type}`.

```jsonc
"body": {
  "severity":  "critical|warning|info|debug",   // channel token 1
  "type":      "connection-lost",                // channel token 2 (sanitized)
  "message":   "OPC UA connection lost",          // optional operator text
  "timestamp": "2026-07-03T12:00:00Z",            // defaulted to now
  "context":   { "id": "kep1", "endpoint": "opc.tcp://host:49320/" },
  "alarm":     true,                              // present only for the connection-lost alarm
  "active":    true                               // true = raised (lost), false = cleared (restored)
}
```

| Channel | When | Shape |
|---------|------|-------|
| `evt/critical/connection-lost` | the OPC UA session goes down (`raiseAlarm`) or comes back up (`clearAlarm`) | **stateful alarm** (`alarm:true`) — the raise (`active:true`) and the restored clear (`active:false`) ride the *same* channel (severity defaults to `critical` for both), so a console tracking `evt/critical/#` sees the full transition on one topic. `context: {id, endpoint}`. |
| `evt/warning/write-rejected` | an `sb/write` target failed the `writes.allow[]` allow-list | plain event (no `alarm`/`active`); `message` is the rejection reason, `context: {id, signalId}`. |

Subscribe `ecv1/+/+/+/evt/#` for every adapter event, or `ecv1/+/+/+/evt/critical/#` for just alarms.

## Metrics (`metric` class, reserved — automatic)

The metric subsystem publishes health and OPC UA operational metrics on the reserved `metric` class
(`ecv1/{device}/{component}/metric/{metricName}`) through `MetricEmitter`; the component never
addresses that topic itself. For every metric's dimensions, measures, units, and diagnostic purpose,
see [Reference - Metrics](metrics.md).

## State keepalive (`state` class)

The library heartbeat publishes a `state` keepalive to `ecv1/{device}/{component}/state` each
tick (`state` is reserved/library-owned — the adapter never publishes it directly). On the
**RUNNING** keepalive the adapter contributes a per-server connectivity view through the library's
instance-connectivity provider: the body carries an `instances[]` array with **one entry per
configured OPC UA server** (`config.getInstanceIds()`), so a console sees every server's liveness
under the one component — no phantom UNS instance per server.

```jsonc
"body": {
  "status": "RUNNING",
  "uptimeSecs": 3600,
  "instances": [
    { "instance": "kep1", "connected": true,  "state": "ONLINE",  "detail": "opc.tcp://host:49320/" },
    { "instance": "line3", "connected": true, "state": "PAUSED",  "detail": "opc.tcp://line3:4840/" },
    { "instance": "plc2", "connected": false, "state": "BACKOFF", "detail": "opc.tcp://plc2:4840/" }
  ]
}
```

| Field | Notes |
|-------|-------|
| `instance` | the configured device instance id (`instances[].id`) |
| `connected` | that server's **live** OPC UA session state — a server that has not yet (re)connected, or one whose session died mid-session, reads `false` (kept live event-driven, no polling; see [explanation.md](../explanation.md)) |
| `state` | the richer condition token: `CONNECTING` (coming up, never been connected), `ONLINE`, `BACKOFF` (was up, session lost, re-establishing), or `PAUSED` (connected, but publishing suspended by `sb/pause`). A break while paused reads `BACKOFF`, so `connected` and `state` never disagree |
| `detail` | the server's OPC UA endpoint URL; a server that has *never* connected yet reports `connected:false` with no `detail` |

`instances[]` is the **passive / observable** counterpart to the `sb/status` verb: `sb/status` is a
pull (request/reply) for one instance's connection + counters, while the keepalive pushes every
server's `connected` flag and `state` on every RUNNING tick with no request. Both read the same
per-server state model, so the pushed and the pulled view always agree — a deliberately paused
instance is distinguishable from one that silently went quiet. It rides the RUNNING keepalive
**only** (a `STOPPED` state carries no live instances).

## CLI

| Flag | Values | Notes |
|------|--------|-------|
| `--platform` | `GREENGRASS` \| `HOST` \| `KUBERNETES` \| `auto` | Default `auto`. |
| `--transport` | `IPC` \| `MQTT [path]` | Defaults from the platform; `IPC` only on GREENGRASS. |
| `-c/--config` | `FILE <path>` \| `ENV` \| `GG_CONFIG` \| `SHADOW` \| `CONFIG_COMPONENT` \| `CONFIGMAP` | Default from the platform. |
| `-t/--thing` | `<name>` | IoT Thing name = the device (last hierarchy level) in every UNS topic. |
