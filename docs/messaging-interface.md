# Messaging Interface

Once deployed, the adapter is driven **entirely by messages**. This document specifies every topic
and payload it publishes or accepts, split into the **data plane** (process data) and the **control
plane** (management). If you are writing a client, this is the contract.

## Message envelope

All messages use the GGCommons envelope (JSON):

```jsonc
{
  "header": {
    "name": "SouthboundTagUpdate",   // message type
    "version": "1.0",
    "timestamp": "2026-06-28T12:00:00Z",
    "uuid": "…",
    "correlation_id": "…",           // set on replies (echoes the request's id)
    "reply_to": "…"                  // set on requests (where the reply is published)
  },
  "tags": { "thing": "<thingName>", "site": "plant1", … },   // routing tags from config
  "body": { … }                                              // the payload (per message type below)
}
```

**Inbound leniency.** For commands the adapter consumes (write/read/control), the **topic** selects
the action — the `header.name` is not validated, and a **raw JSON object** (no envelope) is also
accepted as the body. Replies the adapter sends are always full envelopes.

### Request/reply mechanics
Read and control queries are request/reply:
1. The client publishes a request with `header.reply_to` (a topic it is subscribed to) and a unique
   `header.correlation_id`.
2. The adapter publishes the reply to `reply_to` with the same `correlation_id`.

With a GGCommons client use its `request(...)` API (it sets these for you). With a plain MQTT client,
set `reply_to`/`correlation_id` yourself and subscribe to your reply topic.

## Topic summary

| Plane | Message | Direction | Topic (default) | Reply |
|-------|---------|-----------|-----------------|-------|
| data | `SouthboundTagUpdate` | adapter→bus | `southbound/{site}/{ComponentName}/{InstanceId}/{tagId}` | — |
| data | write | bus→adapter | `southbound/{ComponentName}/{InstanceId}/write` | — |
| data | read | bus↔adapter | `southbound/{ComponentName}/{InstanceId}/read` | `SouthboundReadResult` |
| control | status | bus↔adapter | `southbound/{ComponentName}/{InstanceId}/control/status` | `status` |
| control | subscriptions | bus↔adapter | `southbound/{ComponentName}/{InstanceId}/control/subscriptions` | `subscriptions` |
| control | `southbound_health` | adapter→metric target | per `metricEmission` | — |

Topics are templated — see [configuration.md](configuration.md#template-variables). The control topic
is a fixed wildcard subscription `…/control/+`.

---

# Data plane

## `SouthboundTagUpdate` (adapter → bus)

Published whenever subscribed tag values change (immediately, or batched per `publish.batchMs`).
One message carries one tag's `samples` (one or many, depending on batching).

**Topic:** the instance's `publish.topic` (or a tag spec's `topic` override), with `{tagId}` = the
OPC UA node identifier.

**Body:**
```jsonc
{
  "device": { "adapter": "opcua", "instance": "kep1", "endpoint": "opc.tcp://host:49320/" },
  "tag": {
    "id": "ns=2;s=Simulation Examples.Functions.Sine1",   // canonical, stable id (NodeId.toParseableString)
    "name": "Sine1",                                       // displayName, else browseName
    "address": { "ns": 2, "nodeId": "Simulation Examples.Functions.Sine1" }  // protocol-native
  },
  "samples": [
    {
      "value": 0.7071,                 // see "Value typing" below
      "quality": "GOOD",               // normalized: GOOD | BAD | UNCERTAIN
      "qualityRaw": "Good (0x00000000)",   // native OPC UA StatusCode
      "sourceTs": "2026-06-28T12:00:00.123Z",   // device timestamp (ISO-8601 UTC) or null
      "serverTs": "2026-06-28T12:00:00.150Z"    // server timestamp or null
    }
  ]
}
```

**Identity.** `tag.id` is the canonical, stable key consumers should index on. `tag.address` is the
protocol-native form for round-tripping back to the device (use `ns`+`nodeId` in read/write).

**Value typing.** Numbers (including OPC UA unsigned types) serialize as JSON numbers, booleans as
JSON booleans; anything else (strings, structured/array values) serializes as a JSON string.

## Write (bus → adapter)

Writes one or many tag values to the device. **Fire-and-forget (no reply).** Requires
`write.enabled: true` (otherwise the adapter doesn't listen on the write topic).

**Topic:** `write.topic`. **Body** (batch, or a single entry without the array):
```jsonc
{
  "writes": [
    { "ns": 2, "tagId": "Simulation Examples.Functions.Setpoint", "value": 42.5,
      "status": "GOOD", "sourceTs": "2026-06-28T12:00:00Z" }
  ]
}
```

| Field | Required | Meaning |
|-------|----------|---------|
| `ns` | yes | namespace index |
| `tagId` | yes | node identifier |
| `value` | yes | value to write; coerced to the node's OPC UA data type |
| `status` | no (default `GOOD`) | `GOOD` \| `BAD` \| `UNCERTAIN` |
| `sourceTs` | no | ISO-8601 source timestamp |

Entries missing `ns`/`tagId`/`value` are skipped (logged). Writes are batched into one OPC UA
`writeValues` call. **Supported value types** (by the target node's data type): `Boolean`, `SByte`,
`Byte`, `Int16`, `UInt16`, `Int32`, `UInt32`, `Int64`, `UInt64`, `Float`, `Double`, `String`.

## Read (request/reply)

Reads arbitrary tags on demand. **Request/reply.**

**Request topic:** `read.topic`. **Request body:**
```jsonc
{ "tags": [ { "ns": 2, "tagId": "…Counter" }, { "ns": 2, "tagId": "…Setpoint" } ] }
```

**Reply** — `header.name` = `SouthboundReadResult`, published to the request's `reply_to`:
```jsonc
{
  "id": "kep1",
  "reads": [
    { "tag": { "id": "ns=2;s=…Counter", "address": { "ns": 2, "nodeId": "…Counter" } },
      "value": 17, "quality": "GOOD", "qualityRaw": "Good (0x00000000)",
      "sourceTs": "…", "serverTs": "…" }
  ]
}
```
`reads[i]` corresponds to `tags[i]`. Sample fields match `SouthboundTagUpdate` samples.

---

# Control plane

## Status query (request/reply)

**Request topic:** `…/control/status` (any body). **Reply** (`header.name` = `status`):
```jsonc
{
  "id": "kep1",
  "connected": true,
  "metrics": { "read": { "interval": 1234, "total": 98765 }, "write": { "interval": 2, "total": 40 } }
}
```
`connected` is the OPC UA session state; `metrics` are read/write counts (interval = since the last
reset, total = lifetime).

## Subscriptions query (request/reply)

**Request topic:** `…/control/subscriptions` (any body). **Reply** (`header.name` = `subscriptions`):
```jsonc
{
  "id": "kep1",
  "tags": [ { "tagId": "…Sine1", "namespace": 2, "match": "^…Sine.*" } ]
}
```
Lists the tags currently resolved/subscribed and the matcher that selected each.

## `southbound_health` metric (adapter → metric target)

Emitted periodically (and on connect) via the configured `metricEmission.target`
(`log`/`messaging`/`cloudwatch`/`prometheus`).

| Measure | Unit | Meaning |
|---------|------|---------|
| `connectionState` | Count | `1` connected, `0` down |
| `readErrors` | Count | read errors over the interval |

Dimensions: `instance` (+ the auto-injected `coreName`/`component`).

## Heartbeat (ggcommons)

If a `heartbeat` config section is present, the standard ggcommons heartbeat (CPU/mem/disk/…) is
published to `heartbeat/{ThingName}/{ComponentName}`. Not adapter-specific; see the ggcommons docs.

---

## Worked client example (plain MQTT)

Read two tags:
```
PUBLISH topic: southbound/OpcUaAdapter/kep1/read
payload: {"header":{"name":"ReadTags","reply_to":"app/replies/123","correlation_id":"123"},
          "body":{"tags":[{"ns":2,"tagId":"…Counter"},{"ns":2,"tagId":"…Setpoint"}]}}
SUBSCRIBE topic: app/replies/123   ->  receives the SouthboundReadResult (correlation_id "123")
```
Write one tag:
```
PUBLISH topic: southbound/OpcUaAdapter/kep1/write
payload: {"writes":[{"ns":2,"tagId":"…Setpoint","value":42.5}]}
```
