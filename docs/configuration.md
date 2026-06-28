# Configuration Reference

This is the complete reference for configuring the OPC UA adapter. Every option, its type, default,
and meaning is listed.

## Where configuration comes from

The adapter reads its configuration from the **ggcommons config source** selected by `-c/--config`,
which defaults from the platform:

| Platform | Default source | Typical use |
|----------|----------------|-------------|
| `HOST` | `FILE <path>` | `-c FILE ./config.json` |
| `GREENGRASS` | `GG_CONFIG` | the deployment's `ComponentConfiguration` |
| `KUBERNETES` | `CONFIGMAP` | a mounted ConfigMap directory |

The configuration is one JSON object. The adapter-specific settings live under **`component`**; the
other top-level sections (`tags`, `messaging`, `credentials`, `logging`, `heartbeat`,
`metricEmission`) are standard ggcommons sections the adapter also relies on. Configuration is
hot-reloadable where the source supports it.

---

## Top-level sections

### `tags` (object) — recommended
Site/asset identity. Every value here is (a) attached to published messages (the message envelope's
`tags`) so consumers can route without parsing the body, and (b) usable as a **template variable** in
topics (e.g. `{site}`).

```jsonc
"tags": { "appId": "line5-opcua", "site": "plant1", "shop": "assembly", "line": "5" }
```
All keys are free-form strings. There are no required keys.

### `messaging` (object) — required on HOST/KUBERNETES
The MQTT broker(s) the component connects to. On HOST this can also be supplied via
`--transport MQTT <file>`. Minimal local form:

```jsonc
"messaging": { "local": { "type": "mqtt", "host": "localhost", "port": 1883, "clientId": "opcua-adapter" } }
```
See the ggcommons messaging docs for the full schema (TLS, `iotCore`, etc.). On GREENGRASS the
default transport is IPC and no `messaging` section is needed.

### `credentials` (object) — required only for the `vault` certificate source
Enables the encrypted local vault used by `clientCertificate.source: "vault"` (see
[security.md](security.md)). Omit it if you use `file`/`pkcs11` cert sources or `None` security.

### `logging`, `heartbeat`, `metricEmission` (objects) — optional
Standard ggcommons sections. `metricEmission.target` (`log` | `messaging` | `cloudwatch` |
`prometheus`) determines where the adapter's `southbound_health` metric goes. Example:

```jsonc
"metricEmission": { "target": "messaging", "targetConfig": { "topic": "metrics/{ThingName}/{ComponentName}" } }
```

---

## `component` (object) — required

```jsonc
"component": {
  "global":    { /* defaults shared by all instances */ },
  "instances": [ /* one entry per OPC UA server */ ]
}
```

### `component.global` (object)

| Key | Type | Default | Meaning |
|-----|------|---------|---------|
| `defaults.publishIntervalMs` | number | `1000` | Default OPC UA subscription publishing interval (ms) for instances/subscriptions that don't override it. |
| `defaults.samplingRateMs` | number | `0` | Default monitored-item sampling interval (ms). `0` = "fastest the server supports". |
| `defaults.queueSize` | number | `100` | Default monitored-item server-side queue size. |

`global.defaults` are overridden by an instance's own `defaults`.

### `component.instances[]` (array of objects)

Each object is one independent OPC UA server connection.

| Key | Type | Default | Meaning |
|-----|------|---------|---------|
| `id` | string | the array index/identity | Instance id. Used in topics as `{InstanceId}` and in message bodies (`device.instance`). Make it stable and unique. |
| `adapter` | string | `"opcua"` | Informational protocol tag, echoed in `device.adapter`. |
| `connection` | object | — | OPC UA endpoint + security. See below. |
| `defaults` | object | inherits `global.defaults` | Per-instance `publishIntervalMs` / `samplingRateMs` / `queueSize` overrides. |
| `publish` | object | — | Where/how tag updates are published. See below. |
| `write` | object | — | The write command topic. See below. |
| `read` | object | — | The on-demand read request topic. See below. |
| `subscriptions` | array | `[]` | What tags to subscribe to. See below. |

#### `instances[].connection` (object)

| Key | Type | Default | Meaning |
|-----|------|---------|---------|
| `endpoint` | string | `""` | OPC UA endpoint URL, e.g. `opc.tcp://host:4840/`. |
| `securityPolicy` | string | `"None"` | Milo `SecurityPolicy` name: `None`, `Basic256Sha256`, … An unknown value falls back to `None` (with a warning). |
| `messageMode` | string | `"None"` | `MessageSecurityMode`: `None`, `Sign`, `SignAndEncrypt`. For a secure policy, `None` is auto-upgraded to `SignAndEncrypt`. |
| `applicationUri` | string | derived from the client cert SAN URI | Only for secure connections; must equal the client cert's SubjectAltName URI. |
| `clientCertificate` | object | — | Client identity source for secure connections — see [security.md](security.md). |
| `trust` | object | — | Server-trust settings for secure connections — see [security.md](security.md). |

For `securityPolicy: "None"` (the default), only `endpoint` is needed; the adapter connects
anonymously.

#### `instances[].defaults` (object)
Same three keys as `global.defaults` (`publishIntervalMs`, `samplingRateMs`, `queueSize`); these
override the global values for this instance and are the fallback for each subscription/tag.

#### `instances[].publish` (object)

| Key | Type | Default | Meaning |
|-----|------|---------|---------|
| `topic` | string (template) | `southbound/{ComponentName}/{InstanceId}/{tagId}` | Topic for `SouthboundTagUpdate` messages. Supports template variables incl. `{tagId}`. A subscription tag spec's `topic` overrides this per tag. |
| `batchMs` | number | the instance's `publishIntervalMs` | If `> 0`, value changes are buffered per tag and published together every `batchMs` (one message may carry many `samples`). If `0`, each change publishes immediately. |

#### `instances[].write` (object)

| Key | Type | Default | Meaning |
|-----|------|---------|---------|
| `enabled` | boolean | `false` | If `false`, the adapter does **not** subscribe to the write topic (writes are rejected by omission). Set `true` to allow writes. |
| `topic` | string (template) | `southbound/{ComponentName}/{InstanceId}/write` | Topic the adapter listens on for batch-write commands. |

#### `instances[].read` (object)

| Key | Type | Default | Meaning |
|-----|------|---------|---------|
| `topic` | string (template) | `southbound/{ComponentName}/{InstanceId}/read` | Request/reply topic for on-demand reads. |

> The **control topic** (`southbound/{ComponentName}/{InstanceId}/control/+`) is fixed and not
> configurable. See [messaging-interface.md](messaging-interface.md).

#### `instances[].subscriptions[]` (array of objects)

Each subscription is one OPC UA subscription with its own publishing interval and a set of tag
matchers.

| Key | Type | Default | Meaning |
|-----|------|---------|---------|
| `id` | string | random UUID | Subscription id (for logs/`subscriptions` query). |
| `publishIntervalMs` | number | instance `defaults.publishIntervalMs` | This subscription's OPC UA publishing interval. |
| `include` | array | `[]` | Tag matchers to subscribe to (see tag spec). |
| `exclude` | array | `[]` | Tag matchers to skip (see tag spec). |

##### Tag spec (objects in `include` / `exclude`)

| Key | Type | Default | Meaning |
|-----|------|---------|---------|
| `namespace` | number | `0` | OPC UA namespace index the node must be in (matched exactly). |
| `match` | string (regex) | `".*"` | Java regex. For **include**, matched against the node's identifier **or** browseName **or** displayName. For **exclude**, matched against the node **identifier only**. |
| `topic` | string (template) | inherits `publish.topic` | Per-tag publish topic override (include only). |
| `samplingRateMs` | number | instance `defaults.samplingRateMs` | Monitored-item sampling interval (ms). |
| `queueSize` | number | instance `defaults.queueSize` | Monitored-item server-side queue size. |
| `deadband` | object | `{type:"None"}` | `type`: `None` \| `Absolute` \| `Percent`; `value`: number. Suppresses updates smaller than the deadband. |

A node is subscribed when it matches **any** `include` matcher and **no** `exclude` matcher.

---

## Template variables

These substitute into any topic template:

| Variable | Source |
|----------|--------|
| `{ThingName}` | `-t/--thing` (or the platform identity) |
| `{ComponentName}` / `{ComponentFullName}` | the component name |
| `{InstanceId}` | the instance's `id` |
| `{tagId}` | the OPC UA node identifier (publish topics only, substituted per tag) |
| `{<tagKey>}` | any key under the top-level `tags` (e.g. `{site}`, `{line}`) |

---

## Precedence

For `publishIntervalMs` / `samplingRateMs` / `queueSize`:

```
tag spec value  >  instance.defaults  >  component.global.defaults  >  built-in default
```
(`samplingRateMs`/`queueSize` are tag-spec-level; `publishIntervalMs` is subscription-level.)

---

## Annotated example

```jsonc
{
  "tags": { "appId": "kep1", "site": "plant1", "shop": "assembly", "line": "5" },
  "messaging": { "local": { "type": "mqtt", "host": "localhost", "port": 1883 } },
  "metricEmission": { "target": "messaging", "targetConfig": { "topic": "metrics/{ThingName}/{ComponentName}" } },
  "component": {
    "global": { "defaults": { "publishIntervalMs": 1000, "samplingRateMs": 500, "queueSize": 100 } },
    "instances": [
      {
        "id": "kep1",
        "adapter": "opcua",
        "connection": { "endpoint": "opc.tcp://192.168.1.50:49320/", "securityPolicy": "None" },
        "publish": { "topic": "southbound/{site}/{ComponentName}/{InstanceId}/{tagId}", "batchMs": 1000 },
        "write":   { "enabled": true, "topic": "southbound/{ComponentName}/{InstanceId}/write" },
        "read":    { "topic": "southbound/{ComponentName}/{InstanceId}/read" },
        "subscriptions": [
          {
            "id": "sines",
            "publishIntervalMs": 250,
            "include": [
              { "namespace": 2, "match": "^Simulation Examples\\.Functions\\.Sine.*",
                "samplingRateMs": 250, "queueSize": 50, "deadband": { "type": "Absolute", "value": 0.5 } }
            ],
            "exclude": [ { "namespace": 2, "match": "Sine4$" } ]
          }
        ]
      }
    ]
  }
}
```

---

## Not yet implemented (do not rely on)

These appear in some templates/designs but are **not** acted on by the current build; documented here
to avoid confusion:

- `component.global.healthThresholds.staleTagSecs` and a `staleTags` health measure — the
  `southbound_health` metric currently emits only `connectionState` and `readErrors`.
- `connection.trust.autoTrustServerCert` — there is no auto-trust; trust the server cert explicitly
  (see [security.md](security.md)).
