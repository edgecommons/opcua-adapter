# Reference — Configuration

Complete reference for every configuration option. For *why* these settings exist and how they
interact, read [explanation.md](../explanation.md); for task recipes, see the
[how-to guides](../how-to-guides.md).

## Config source

The adapter reads one JSON document from the `-c/--config` source, which defaults by platform:

| Platform | Default source | Example |
|----------|----------------|---------|
| `HOST` | `FILE <path>` | `-c FILE ./config.json` |
| `GREENGRASS` | `GG_CONFIG` | the deployment `ComponentConfiguration` |
| `KUBERNETES` | `CONFIGMAP` | a mounted ConfigMap directory (re-read on change) |

Adapter settings live under `component`; the sibling sections (`tags`, `messaging`, `credentials`,
`logging`, `heartbeat`, `metricEmission`) are standard ggcommons sections. Configuration hot-reloads
where the source supports it.

## Top-level sections

| Section | Required | Purpose |
|---------|----------|---------|
| `component` | yes | Adapter instances and their global defaults (this document). |
| `tags` | recommended | Site/asset identity; attached to every published message and usable as topic template variables. |
| `messaging` | HOST/KUBERNETES | MQTT broker connection (or supply via `--transport MQTT <file>`). On GREENGRASS the transport is IPC. |
| `credentials` | only for `vault` cert source | Enables the encrypted vault used by `clientCertificate.source: "vault"` (see [security how-to](../how-to-guides.md#connect-to-a-secured-server)). |
| `metricEmission` | optional | Routes the `southbound_health` metric (`target`: `log`/`messaging`/`cloudwatch`/`prometheus`). |
| `logging`, `heartbeat` | optional | Standard ggcommons sections. |

## `component.global`

| Key | Type | Default | Definition |
|-----|------|---------|-----------|
| `defaults.publishIntervalMs` | number | `1000` | Fallback subscription publishing interval (ms). |
| `defaults.samplingRateMs` | number | `0` | Fallback monitored-item sampling interval (ms); `0` = server's fastest. |
| `defaults.queueSize` | number | `100` | Fallback monitored-item server-side queue size. |

These are overridden by an instance's own `defaults`.

## `component.instances[]`

| Key | Type | Default | Definition |
|-----|------|---------|-----------|
| `id` | string | array identity | Stable, unique instance id. Appears as `{InstanceId}` in topics and as `device.instance` in messages. |
| `adapter` | string | `"opcua"` | Informational; echoed as `device.adapter`. |
| `connection` | object | — | OPC UA endpoint and security (below). |
| `defaults` | object | inherits `global.defaults` | Per-instance timing overrides (`publishIntervalMs`, `samplingRateMs`, `queueSize`). |
| `publish` | object | — | Signal-update publishing (below). |
| `write` | object | — | Write command topic (below). |
| `read` | object | — | On-demand read request topic (below). |
| `subscriptions` | array | `[]` | Subscriptions and signal matchers (below). |

### `instances[].connection`

| Key | Type | Default | Definition |
|-----|------|---------|-----------|
| `endpoint` | string | `""` | OPC UA endpoint URL, e.g. `opc.tcp://host:4840/`. |
| `securityPolicy` | string | `"None"` | Milo `SecurityPolicy` name (`None`, `Basic256Sha256`, …). An unrecognized value falls back to `None` with a warning. |
| `messageMode` | string | `"None"` | `MessageSecurityMode`: `None`, `Sign`, `SignAndEncrypt`. Under a secure policy, `None` is auto-upgraded to `SignAndEncrypt`. |
| `applicationUri` | string | derived from cert SAN URI | Secure only. Must equal the client certificate's SubjectAltName URI. |
| `user` | object | — | Optional UserName identity token (any policy). Inline or vault-backed — see below. |
| `clientCertificate` | object | — | Secure only. Client identity source — see below. |
| `trust` | object | — | Secure only. Server-trust settings — see below. |

For `securityPolicy: "None"` only `endpoint` is required (plus `user`, if the server demands one).

#### `connection.user`

Optional. Supplies a **UserName identity token**; without it the adapter connects **anonymously**.
It is independent of channel security — a `None` (unencrypted) endpoint can still require a user
token, as KEPServerEX does by default. On a `None` channel the password is encrypted with the
server's endpoint certificate when the server's user-token policy requires it.

| Form | Keys | Definition |
|------|------|-----------|
| inline | `username`, `password` | Credentials in the config. **Keep any config with an inline password out of version control.** |
| vault | `source: "vault"`, `secret` | Reads a `BasicAuth` (`{username, password}`) from the credentials vault; requires a `credentials` section. |

```jsonc
"connection": {
  "endpoint": "opc.tcp://host:49320",
  "securityPolicy": "None",
  "user": { "source": "vault", "secret": "opcua/kep1/login" }
}
```

The server validates the user against its own account store (e.g. KEPServerEX's **User Manager**) and
applies that user's authorization. An under-privileged account may see only part of the address space,
so an over-restricted user can yield empty subscriptions even though the connection succeeds.

#### `connection.clientCertificate`

One of three sources (`source`):

| `source` | Keys | Definition |
|----------|------|-----------|
| `vault` | `secret` | Reads a `TlsBundle` (`{certPem, keyPem, caPem}`) from the credentials vault; requires a `credentials` section. |
| `file` | `certPath`, `keyPath` | PEM certificate and private key files (templated paths). |
| `pkcs11` | `modulePath`, `slotIndex`, `pin` or `pinEnv`, `keyLabel`, `certLabel` | Key + certificate on a PKCS#11 token. (Net-new; validate against your token.) |

#### `connection.trust`

| Key | Type | Default | Definition |
|-----|------|---------|-----------|
| `pkiDir` | string (template) | `pki/{InstanceId}` | Trust-list directory; `trusted/`, `rejected/`, `issuers/` are created here. |
| `serverCertificate` | object | — | Optionally pin the server cert: `{source:"file", path}`, or `{source:"vault", secret, field:"caPem"}`. |

### `instances[].publish`

| Key | Type | Default | Definition |
|-----|------|---------|-----------|
| `topic` | string (template) | `southbound/{ComponentName}/{InstanceId}/{signalId}` | Topic for `SouthboundSignalUpdate` messages. A signal matcher's `topic` overrides this per signal. |
| `batchMs` | number | the instance `publishIntervalMs` | If `> 0`, buffer a signal's samples and publish one message per `batchMs`. If `0`, publish each sample immediately. |

### `instances[].write`

| Key | Type | Default | Definition |
|-----|------|---------|-----------|
| `enabled` | boolean | `false` | If `false`, the adapter does not subscribe to the write topic (writes are not accepted). |
| `topic` | string (template) | `southbound/{ComponentName}/{InstanceId}/write` | Topic the adapter listens on for batch writes. |

### `instances[].read`

| Key | Type | Default | Definition |
|-----|------|---------|-----------|
| `topic` | string (template) | `southbound/{ComponentName}/{InstanceId}/read` | Request/reply topic for on-demand reads. |

> The control topic, `southbound/{ComponentName}/{InstanceId}/control/+`, is fixed and not
> configurable.

### `instances[].subscriptions[]`

| Key | Type | Default | Definition |
|-----|------|---------|-----------|
| `id` | string | random UUID | Subscription id (logs and the `subscriptions` query). |
| `publishIntervalMs` | number | instance default | This subscription's OPC UA publishing interval. |
| `include` | array | `[]` | Signal matchers to subscribe to (below). |
| `exclude` | array | `[]` | Signal matchers to skip (below). |

#### Signal matcher (entries of `include` / `exclude`)

| Key | Type | Default | Definition |
|-----|------|---------|-----------|
| `namespaceUri` | string | — | OPC UA namespace **URI** (preferred). Resolved to the server's current index at runtime; stable across servers and restarts. If the URI is absent on the server, the matcher is skipped (with a warning). |
| `namespace` | number | `0` | Literal namespace index; used only when `namespaceUri` is absent. |
| `match` | string (regex) | `".*"` | Java regex. **Include** matches identifier, browse name, or display name. **Exclude** matches the identifier only. |
| `topic` | string (template) | inherits `publish.topic` | Per-signal publish-topic override (include). |
| `samplingRateMs` | number | instance default | Monitored-item sampling interval. |
| `queueSize` | number | instance default | Monitored-item queue size. |
| `deadband` | object | `{type:"None"}` | `type`: `None`/`Absolute`/`Percent`; `value`: number. |

A node is subscribed when it matches **any** `include` matcher and **no** `exclude` matcher.

## Template variables

Substituted into any topic template:

| Variable | Resolves to |
|----------|-------------|
| `{ThingName}` | the `-t/--thing` value (or platform identity) |
| `{ComponentName}` / `{ComponentFullName}` | the component's short / fully-qualified name |
| `{InstanceId}` | the instance `id` |
| `{signalId}` | the OPC UA node identifier (publish topics only, per signal) |
| `{<key>}` | any key under the top-level `tags` (e.g. `{site}`) |

## Precedence

`publishIntervalMs`, `samplingRateMs`, and `queueSize` resolve from the most specific source:

```
signal-spec value  ▸  instances[].defaults  ▸  component.global.defaults  ▸  built-in default
```

`samplingRateMs` and `queueSize` are signal-matcher settings; `publishIntervalMs` is a subscription
setting.

## Complete example

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
        "publish": { "topic": "southbound/{site}/{ComponentName}/{InstanceId}/{signalId}", "batchMs": 1000 },
        "write":   { "enabled": true, "topic": "southbound/{ComponentName}/{InstanceId}/write" },
        "read":    { "topic": "southbound/{ComponentName}/{InstanceId}/read" },
        "subscriptions": [
          {
            "id": "sines",
            "publishIntervalMs": 250,
            "include": [
              { "namespaceUri": "urn:kepware:KEPServerEX", "match": "^Channel1\\.Device1\\..*",
                "samplingRateMs": 250, "queueSize": 50, "deadband": { "type": "Absolute", "value": 0.5 } }
            ],
            "exclude": [ { "namespaceUri": "urn:kepware:KEPServerEX", "match": "\\.Diagnostics\\." } ]
          }
        ]
      }
    ]
  }
}
```

## Accepted but not implemented

The current build ignores these (documented to prevent misplaced trust):

- `component.global.healthThresholds.staleSignalSecs` and a `staleSignals` health measure — the
  `southbound_health` metric emits only `connectionState` and `readErrors`.
- `connection.trust.autoTrustServerCert` — there is no auto-trust; trust the server certificate
  explicitly.
