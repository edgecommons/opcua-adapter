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

Adapter settings live under `component`; the sibling sections (`hierarchy`, `identity`, `tags`,
`messaging`, `credentials`, `logging`, `heartbeat`, `metricEmission`) are standard ggcommons sections.
Configuration hot-reloads where the source supports it.

## Top-level sections

| Section | Required | Purpose |
|---------|----------|---------|
| `component` | yes | Adapter instances and their global defaults (this document). |
| `hierarchy` | recommended | UNS enterprise hierarchy: an ordered list of level names whose **last** level is the physical node (the device = resolved thing name). Absent ⇒ the default `["device"]`. |
| `identity` | with `hierarchy` | Values for every hierarchy level **except the last**. Keys must match `hierarchy.levels[0..n-2]`. Stamped onto every message's top-level `identity`. |
| `tags` | optional | Arbitrary business metadata attached to every message (no location keys — those moved to `identity`). Still usable as `{key}` template variables in **filesystem-path** templates (e.g. PKI dir). |
| `messaging` | HOST/KUBERNETES | MQTT broker connection (or supply via `--transport MQTT <file>`). On GREENGRASS the transport is IPC. |
| `topic.includeRoot` | optional | `true` inserts the first hierarchy value (`site`) after the `ecv1` root in built topics (multi-site broker). Default `false` (rootless). |
| `credentials` | only for `vault` cert source | Enables the encrypted vault used by `clientCertificate.source: "vault"`. |
| `metricEmission` | optional | Routes the `southbound_health` metric (`target`: `log`/`messaging`/`cloudwatch`/`prometheus`). The `metric` topic is UNS-minted — do **not** set a `targetConfig.topic` (the schema rejects it); use `targetConfig.destination` (`local`/`iotcore`) instead. |
| `logging`, `heartbeat` | optional | Standard ggcommons sections. |

### `hierarchy` / `identity` (UNS)

```jsonc
"hierarchy": { "levels": ["site", "shop", "line", "device"] },   // last = the device (thing name)
"identity":  { "site": "site1", "shop": "shop1", "line": "line1" } // values for all but the last level
```

The device level's value is the resolved thing name (`-t/--thing`, `AWS_IOT_THING_NAME`, or the K8s
Downward API). Level names are strict (`^[A-Za-z0-9_-]+$`, unique). A key in `identity` equal to the
last level name — or not a declared level — is a startup error.

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
| `id` | string | array identity | Stable, unique instance id. Appears as the UNS `{instance}` topic segment and as `device.instance` in messages. |
| `adapter` | string | `"opcua"` | Informational; echoed as `device.adapter`. |
| `connection` | object | — | OPC UA endpoint and security (below). |
| `defaults` | object | inherits `global.defaults` | Per-instance timing overrides (`publishIntervalMs`, `samplingRateMs`, `queueSize`). |
| `publish` | object | — | Signal-update publishing (below). |
| `writes` | object | — | Write allow-list (below). |
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
token, as KEPServerEX does by default.

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

The server validates the user against its own account store and applies that user's authorization. An
under-privileged account can yield empty subscriptions even though the connection succeeds.

#### `connection.clientCertificate`

One of three sources (`source`):

| `source` | Keys | Definition |
|----------|------|-----------|
| `vault` | `secret` | Reads a `TlsBundle` (`{certPem, keyPem, caPem}`) from the credentials vault; requires a `credentials` section. |
| `file` | `certPath`, `keyPath` | PEM certificate and private key files (templated paths). |
| `pkcs11` | `modulePath`, `slotIndex`, `pin` or `pinEnv`, `keyLabel`, `certLabel` | Key + certificate on a PKCS#11 token. |

#### `connection.trust`

| Key | Type | Default | Definition |
|-----|------|---------|-----------|
| `pkiDir` | string (template) | `pki/{InstanceId}` | Trust-list directory; `trusted/`, `rejected/`, `issuers/` are created here. Path templates (`{ThingName}`, `{InstanceId}`, `{tags.*}`) are resolved. |
| `serverCertificate` | object | — | Optionally pin the server cert: `{source:"file", path}`, or `{source:"vault", secret, field:"caPem"}`. |

### `instances[].publish`

| Key | Type | Default | Definition |
|-----|------|---------|-----------|
| `batchMs` | number | the instance `publishIntervalMs` | If `> 0`, buffer a signal's samples and publish one message per `batchMs`. If `0`, publish each sample immediately. |

> **Addressing is UNS-minted.** There is no `publish.topic` — signal updates ride the UNS `data` class
> (`ecv1/{device}/{component}/{instance}/data/{signalPath}`), where `{signalPath}` is the node's bare
> identifier sanitized to one channel token. The legacy `publish.topic` template (and per-signal
> `topic` overrides) are retired.

### `instances[].writes` (D‑U16 allow-list)

| Key | Type | Default | Definition |
|-----|------|---------|-----------|
| `allow` | string[] | `[]` | Stable `signal.id`s the `sb/write` verb may write (or the wildcard `"*"` = allow all). **Absent/empty ⇒ every write is rejected** (secure-by-default; replaces the boolean `write.enabled: false`). |

Matching is **exact** against the target's stable `signal.id` (the `ns=<ns>;<type>=<id>` parseable
form, as published in `SouthboundSignalUpdate.signal.id`), e.g. `"ns=2;s=Channel1.Device1.Setpoint"`.
A non-allow-listed write is confirmed `FAILED` and raises `evt/warning/write-rejected`.

> There is no per-instance write/read/control *topic*. Reads, writes, and management queries are the
> UNS `cmd/sb/*` verbs on the library inbox — see the
> [messaging reference](messaging-interface.md#the-command-surface--cmdsb-verbs).

### `instances[].subscriptions[]`

| Key | Type | Default | Definition |
|-----|------|---------|-----------|
| `id` | string | random UUID | Subscription id (logs and the `sb/subscriptions` query). |
| `publishIntervalMs` | number | instance default | This subscription's OPC UA publishing interval. |
| `include` | array | `[]` | Signal matchers to subscribe to (below). |
| `exclude` | array | `[]` | Signal matchers to skip (below). |

#### Signal matcher (entries of `include` / `exclude`)

| Key | Type | Default | Definition |
|-----|------|---------|-----------|
| `namespaceUri` | string | — | OPC UA namespace **URI** (preferred). Resolved to the server's current index at runtime; stable across servers and restarts. If absent on the server, the matcher is skipped (with a warning). |
| `namespace` | number | `0` | Literal namespace index; used only when `namespaceUri` is absent. |
| `match` | string (regex) | `".*"` | Java regex. **Include** matches identifier, browse name, or display name. **Exclude** matches the identifier only. |
| `samplingRateMs` | number | instance default | Monitored-item sampling interval. |
| `queueSize` | number | instance default | Monitored-item queue size. |
| `deadband` | object | `{type:"None"}` | `type`: `None`/`Absolute`/`Percent`; `value`: number. |

A node is subscribed when it matches **any** `include` matcher and **no** `exclude` matcher. (A
per-matcher `topic` override is no longer honored — addressing is UNS-minted; the key is ignored.)

## Template variables

Substituted into **filesystem-path** templates (PKI dir, cert paths) — not topics (which are
UNS-minted):

| Variable | Resolves to |
|----------|-------------|
| `{ThingName}` | the `-t/--thing` value (or platform identity) |
| `{ComponentName}` / `{ComponentFullName}` | the component's short / fully-qualified name |
| `{InstanceId}` | the instance `id` |
| `{<key>}` | any key under the top-level `tags` |

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
  "hierarchy": { "levels": ["site", "shop", "line", "device"] },
  "identity":  { "site": "plant1", "shop": "assembly", "line": "5" },
  "tags":      { "appId": "kep1" },
  "messaging": { "local": { "type": "mqtt", "host": "localhost", "port": 1883 } },
  "metricEmission": { "target": "messaging", "targetConfig": { "destination": "local" } },
  "component": {
    "global": { "defaults": { "publishIntervalMs": 1000, "samplingRateMs": 500, "queueSize": 100 } },
    "instances": [
      {
        "id": "kep1",
        "adapter": "opcua",
        "connection": { "endpoint": "opc.tcp://192.168.1.50:49320/", "securityPolicy": "None" },
        "publish": { "batchMs": 1000 },
        "writes":  { "allow": [ "ns=2;s=Channel1.Device1.Setpoint" ] },
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
  `southbound_health` metric emits `connectionState`, `readErrors`, and `writeErrors`.
- `connection.trust.autoTrustServerCert` — there is no auto-trust; trust the server certificate
  explicitly.
- a signal matcher's `topic` key — per-signal topic overrides are retired (addressing is UNS-minted).
