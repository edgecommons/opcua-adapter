# Sample Configurations

Complete, ready-to-adapt configurations for the OPC UA adapter
(`com.mbreissi.opcua.OpcUaAdapter`), one per realistic deployment scenario. Each sample is a valid
config document; the prose after it explains **what every option does and how it changes runtime
behavior** — data rate, latency, addressing, security, and reconnect/health.

For the exhaustive option list see [reference/configuration.md](reference/configuration.md); for the
topic/message contract see [reference/messaging-interface.md](reference/messaging-interface.md); for
the reasoning behind the timing and security models see [explanation.md](explanation.md); and for
task recipes see [how-to-guides.md](how-to-guides.md).

> **How config reaches the adapter.** The adapter reads one JSON document from the `-c/--config`
> source, which defaults by platform: `HOST` → `FILE`, `GREENGRASS` → `GG_CONFIG` (the deployment),
> `KUBERNETES` → `CONFIGMAP` (a mounted directory, hot-reloaded). Adapter settings live under
> `component`; the sibling sections (`tags`, `messaging`, `logging`, `heartbeat`, `metricEmission`,
> `credentials`) are standard ggcommons sections.

---

## 1. Minimal local / dev run (HOST + MQTT)

The smallest config that connects to a **plain (unsecured) OPC UA server** and republishes a set of
tags to a local MQTT broker. This is the shape you use against a simulator or a lab KEPServerEX while
developing.

The dual-MQTT transport needs broker details. You can supply them inline under `messaging` (shown
here) or as a separate file passed positionally as `--transport MQTT ./messaging.json`.

```jsonc
// config.json
{
  "tags": { "site": "lab", "shop": "s1", "line": "l1" },

  "messaging": {
    "local": { "type": "mqtt", "host": "localhost", "port": 1883, "clientId": "opcua-adapter" }
  },

  "logging": { "level": "INFO" },

  "metricEmission": {
    "target": "messaging",
    "targetConfig": { "topic": "metrics/{ThingName}/{ComponentName}" }
  },

  "component": {
    "global": {
      "defaults": { "publishIntervalMs": 1000, "samplingRateMs": 500, "queueSize": 100 }
    },
    "instances": [
      {
        "id": "sim1",
        "adapter": "opcua",
        "connection": { "endpoint": "opc.tcp://localhost:4840/", "securityPolicy": "None" },
        "publish": { "topic": "southbound/{site}/{ComponentName}/{InstanceId}/{tagId}", "batchMs": 1000 },
        "write":   { "enabled": false },
        "read":    { "topic": "southbound/{ComponentName}/{InstanceId}/read" },
        "subscriptions": [
          {
            "id": "all",
            "include": [ { "namespaceUri": "urn:ggcommons:sim", "match": "Sine.*" } ]
          }
        ]
      }
    ]
  }
}
```

Run it:

```bash
java -jar target/OpcUaAdapter-1.0.0.jar --platform HOST --transport MQTT \
  -c FILE ./config.json -t my-thing
# (or, with a separate broker file: --transport MQTT ./messaging.json)
```

| Option | Effect on runtime behavior |
|--------|----------------------------|
| `tags` | Site/asset identity. Each key is attached to every published message and is usable as a topic template variable (`{site}` below). Pure metadata — changing it does not affect connectivity, only message tagging and topic resolution. |
| `messaging.local` | The **local** MQTT broker the adapter publishes to and listens on. `host`/`port` point at the broker; `clientId` is the MQTT client identity (keep it unique per process or the broker drops the older session). On HOST this is one half of the dual-MQTT transport; add an `iotCore` block (see §3) to also bridge to AWS IoT Core. Without a reachable broker the adapter cannot publish or accept commands. |
| `metricEmission.target` | Where the `southbound_health` metric goes (`log` / `messaging` / `cloudwatch` / `prometheus`). With `messaging`, health is published to `targetConfig.topic`; with `log` it only appears in logs. This is observability only — it does not change OPC UA behavior. |
| `component.global.defaults` | Fallback timing for every instance/tag that does not set its own. `publishIntervalMs` (server→adapter delivery cadence), `samplingRateMs` (how often the server samples the value; `0` = as fast as the server allows), `queueSize` (server-side per-tag buffer). See the timing table in §4. |
| `instances[].id` | Stable, unique instance id. Appears as `{InstanceId}` in topics and as `device.instance` in messages. Each instance is one OPC UA server with its own connection thread, so renaming it changes topic routing and message identity. |
| `connection.endpoint` | The OPC UA server URL (`opc.tcp://host:port/`). The adapter connects here on a dedicated thread and **retries on failure**, so a server that is slow to boot delays only this instance, not the component. |
| `connection.securityPolicy: "None"` | Unencrypted, **anonymous** channel — no certificates required. Fine for a trusted LAN/dev. For a secured server use §3. |
| `publish.topic` | Topic template for `SouthboundTagUpdate` messages; `{tagId}` is replaced per tag with the node identifier, `{site}` from `tags`. Determines where consumers subscribe. |
| `publish.batchMs` | Client-side coalescing window. `1000` here means the adapter buffers a tag's samples and emits **one message per tag per second** (each may carry several `samples`), reducing message count. Set `0` to emit one message the instant each sample arrives (lowest latency, most messages). |
| `write.enabled: false` | The adapter does **not** subscribe to the write topic, so writes are rejected. Set `true` (see §3/§4) to accept writes. |
| `read.topic` | Request/reply topic for on-demand reads. Always available regardless of `write.enabled`. |
| `subscriptions[].include` | The tag matchers to subscribe to. A node is subscribed when it matches **any** `include` matcher and **no** `exclude` matcher. With only `{ "match": "Sine.*" }` and no timing overrides, these tags inherit `global.defaults`. |

---

## 2. Greengrass v2 deployment (IPC) — on-device shape

On `--platform GREENGRASS` there is **no messaging broker block and no config file**: messaging uses
Greengrass IPC (`--transport IPC`, the default for this platform) and the config arrives from the
deployment's `ComponentConfiguration`. The sample below is the `ComponentConfig` block exactly as it
sits in `recipe.yaml` (YAML, because the recipe is YAML); a cloud deployment overrides the same keys
via `aws greengrassv2`.

```yaml
# recipe.yaml — ComponentConfiguration.DefaultConfiguration.ComponentConfig
ComponentConfig:
  logging:
    level: "INFO"
  heartbeat:
    intervalSecs: 5
    targets:
      - type: "messaging"
        config:
          destination: "ipc"                       # heartbeats go out over IPC, not a TCP broker
          topic: "heartbeat/{ThingName}/{ComponentName}"
    measures: { cpu: true, memory: true, disk: false, fds: true, files: true }
  tags: { site: "plant1", shop: "assembly", line: "5" }
  metricEmission:
    target: "log"
    targetConfig:
      logFileName: "/greengrass/v2/logs/{ComponentFullName}.metric.log"
  component:
    global:
      defaults: { publishIntervalMs: 1000, samplingRateMs: 500, queueSize: 100 }
    instances:
      - id: "kep1"
        adapter: "opcua"
        connection: { endpoint: "opc.tcp://192.168.1.50:49320/", securityPolicy: "None" }
        publish: { topic: "southbound/{site}/{ComponentName}/{InstanceId}/{tagId}", batchMs: 1000 }
        write:   { enabled: true }
        read:    { topic: "southbound/{ComponentName}/{InstanceId}/read" }
        subscriptions:
          - id: "process"
            include:
              - { namespaceUri: "urn:kepware:KEPServerEX", match: "^Channel1\\.Device1\\..*" }
```

Run on-device (config comes from the deployment, so no `-c`):

```bash
java -jar OpcUaAdapter-1.0.0.jar --platform GREENGRASS -t my-thing
# package/publish: gdk component build && gdk component publish
```

| Difference from HOST | Effect on runtime behavior |
|----------------------|----------------------------|
| No `messaging` section; transport is IPC | The adapter publishes/subscribes through the Nucleus's local IPC pub/sub (and IoT Core mqttproxy) instead of a TCP MQTT broker. The recipe's `accessControl` grants the IPC topics; the message envelope on the wire is identical to HOST. |
| `heartbeat.targets[].config.destination: "ipc"` | Routes the periodic system heartbeat over IPC. On HOST you would omit this (it goes to the MQTT broker). |
| `metricEmission.target: "log"` with a `/greengrass/v2/logs/...` path | Health metrics are written to the Nucleus-managed component log directory rather than published — convenient because Greengrass already rotates and ships those logs. |
| `connection` / `publish` / `subscriptions` | **Identical semantics to HOST.** The OPC UA side does not know or care about the platform; only the transport and config source change. You can lift an instance verbatim between HOST and GREENGRASS. |
| Cloud override | A `aws greengrassv2 create-deployment` merge config patches these same keys per device/group, so per-site `endpoint`/`tags`/`subscriptions` differences are deployment data, not code. |

> The component reports **ready** as soon as its first instance is connected and subscribing — a
> signal orchestrators can gate on. Each instance reconnects independently with retry, so one
> unreachable server never blocks the others.

---

## 3. Secured OPC UA server (policy + certificates + user)

A mutually-authenticated, encrypted channel using `Basic256Sha256` / `SignAndEncrypt`, plus a
UserName identity token. This is the production shape against a hardened KEPServerEX. It also shows
the **dual-MQTT** messaging block (local broker **and** AWS IoT Core) you would use on a HOST gateway.

```jsonc
// config.json
{
  "tags": { "site": "plant1", "shop": "assembly", "line": "5" },

  "messaging": {
    "local":   { "type": "mqtt", "host": "localhost", "port": 1883, "clientId": "opcua-adapter" },
    "iotCore": {
      "endpoint": "xxxx-ats.iot.us-east-1.amazonaws.com",
      "port": 8883,
      "clientId": "opcua-adapter",
      "credentials": { "certPath": "creds/device.cert.pem", "keyPath": "creds/device.key", "caPath": "creds/root-CA.crt" }
    }
  },

  "credentials": { "vault": { "path": "/var/lib/opcua/{InstanceId}/vault" } },

  "component": {
    "global": { "defaults": { "publishIntervalMs": 1000, "samplingRateMs": 500, "queueSize": 50 } },
    "instances": [
      {
        "id": "kep1",
        "adapter": "opcua",
        "connection": {
          "endpoint": "opc.tcp://192.168.1.50:49320",
          "securityPolicy": "Basic256Sha256",
          "messageMode": "SignAndEncrypt",
          "clientCertificate": { "source": "vault", "secret": "opcua/kep1/appcert" },
          "trust": {
            "pkiDir": "/var/lib/opcua/{InstanceId}/pki",
            "serverCertificate": { "source": "file", "path": "/etc/opcua/kep1-server.pem" }
          },
          "user": { "source": "vault", "secret": "opcua/kep1/login" }
        },
        "publish": { "topic": "southbound/{site}/{ComponentName}/{InstanceId}/{tagId}", "batchMs": 1000 },
        "write":   { "enabled": true },
        "read":    { "topic": "southbound/{ComponentName}/{InstanceId}/read" },
        "subscriptions": [
          { "id": "process",
            "include": [ { "namespaceUri": "urn:kepware:KEPServerEX", "match": "^Channel1\\.Device1\\..*" } ] }
        ]
      }
    ]
  }
}
```

| Option | Effect on runtime behavior |
|--------|----------------------------|
| `messaging.iotCore` | Adds the **cloud** half of the dual-MQTT transport. The adapter connects simultaneously to the local broker and to AWS IoT Core over mutual TLS on `8883`. `credentials.certPath`/`keyPath`/`caPath` are the device's X.509 identity for IoT Core; a missing/expired cert means the cloud leg fails to connect (the local leg is unaffected). |
| `credentials` | Enables the encrypted local vault. **Required** whenever any `source: "vault"` reference is used (here for the client cert and the OPC UA user). Without it, those references cannot resolve and the secure connection fails to start. |
| `connection.securityPolicy: "Basic256Sha256"` | Requests an **encrypted, signed** channel using that cipher suite (vs `None`). The adapter must present an application instance certificate the server trusts, and must trust the server's. |
| `connection.messageMode: "SignAndEncrypt"` | Every message is signed and encrypted. `Sign` signs only (integrity, no confidentiality). Under any non-`None` policy a `messageMode` of `None` is auto-upgraded to `SignAndEncrypt`. |
| `clientCertificate.source: "vault"` | The adapter's identity (cert + private key) is read from the credentials vault secret as a `TlsBundle` (`{certPem, keyPem, caPem}`). Alternatives: `source: "file"` (`certPath`+`keyPath` PEM files) or `source: "pkcs11"` (`modulePath`, `slotIndex`, `pin`/`pinEnv`, `keyLabel`, `certLabel` — the private key never leaves the token). The vault is recommended because the key is encrypted at rest. |
| `trust.pkiDir` | Directory holding the trust store; the adapter creates `trusted/`, `rejected/`, `issuers/` under it. A server cert is accepted only if it (or its issuer) is in `trusted/`; an untrusted cert is written to `rejected/` for an operator to inspect and promote. **There is no auto-trust mode.** |
| `trust.serverCertificate` | Optionally **pins** the expected server certificate up front so the first connection succeeds without a manual promote step: `{source:"file", path}` or `{source:"vault", secret, field:"caPem"}`. |
| `connection.user` | A **UserName identity token**, independent of channel security (KEPServerEX rejects anonymous logins even on its `None` endpoint). `source: "vault"` reads a `BasicAuth` (`{username, password}`) secret; the inline form `{username, password}` is for dev only — **keep configs with inline passwords out of version control.** The server applies that user's authorization, so an under-privileged account can yield empty subscriptions even when the connection succeeds. |
| `applicationUri` (not shown) | Leave unset; the adapter derives it from the client certificate's SubjectAltName URI, which the server requires to match. Setting it wrong lets the channel open and then fails the session. |

> **Two spec requirements that trip everyone up:** the client certificate's **key usage** must
> include `digitalSignature`, `nonRepudiation`, `keyEncipherment`, `dataEncipherment` (a self-signed
> cert also needs `keyCertSign` + the CA constraint), and its **SAN URI must equal** the
> `applicationUri` the adapter presents. See [the security how-to](how-to-guides.md#connect-to-a-secured-server).

---

## 4. Selective tag subscription (include / exclude / deadband / per-tag timing)

The address space is large; you usually want a precise subset at a controlled data rate. This config
uses multiple subscriptions with their own publish intervals, include/exclude matchers, per-tag
sampling, queue depth, deadbands, and a per-tag topic override for alarms.

```jsonc
// component section only — drop into any of the platform shapes above
"component": {
  "global": {
    "defaults": { "publishIntervalMs": 1000, "samplingRateMs": 500, "queueSize": 100 }
  },
  "instances": [
    {
      "id": "kep1",
      "adapter": "opcua",
      "connection": { "endpoint": "opc.tcp://192.168.1.50:49320", "securityPolicy": "None" },
      "defaults": { "publishIntervalMs": 1000, "samplingRateMs": 500, "queueSize": 100 },
      "publish": { "topic": "southbound/{site}/{ComponentName}/{InstanceId}/{tagId}", "batchMs": 1000 },
      "write":   { "enabled": true },
      "subscriptions": [
        {
          "id": "fast-process",
          "publishIntervalMs": 200,
          "include": [
            { "namespaceUri": "urn:kepware:KEPServerEX",
              "match": "^Channel1\\.Device1\\.(Temp|Pressure)\\b.*",
              "samplingRateMs": 50, "queueSize": 50,
              "deadband": { "type": "Absolute", "value": 0.5 } }
          ],
          "exclude": [
            { "namespaceUri": "urn:kepware:KEPServerEX", "match": "\\.Diagnostics\\." }
          ]
        },
        {
          "id": "alarms",
          "publishIntervalMs": 250,
          "include": [
            { "namespaceUri": "urn:kepware:KEPServerEX", "match": ".*\\.Alarm\\..*",
              "topic": "alarms/{site}/{InstanceId}/{tagId}" }
          ]
        }
      ]
    }
  ]
}
```

### What each subscription/matcher option does

| Option | Effect on runtime behavior |
|--------|----------------------------|
| `subscriptions[]` (multiple) | Each subscription is an independent OPC UA subscription with its **own `publishIntervalMs`**. Split tags into groups by how fresh they need to be — fast process values vs slow housekeeping vs alarms — so each group gets the cadence it needs without over-publishing the rest. |
| `subscriptions[].publishIntervalMs` | How often the **server delivers** that subscription's accumulated samples to the adapter. `200` ms here → low latency; a larger value reduces network chatter and lets the server batch. Overrides the instance/global default for this subscription only. |
| `include[].namespaceUri` | Pins the OPC UA namespace by its **URI** (preferred). The adapter resolves it to the server's current index at connect time and re-resolves on rebuild, so a server that renumbers after a restart is followed automatically. A missing URI means that matcher is skipped (with a warning). |
| `include[].namespace` | Literal namespace **index**, used only when `namespaceUri` is absent. Indexes are volatile across servers/restarts — use only for servers you know to be stable. |
| `include[].match` | Java regex selecting nodes. For **include** it is tested against the node's **identifier, browse name, *and* display name**; for **exclude** it is tested against the **identifier only**. Anchor (`^…`) and escape literal dots (`\\.`) in JSON. |
| `exclude[]` | Removes nodes that an `include` matched. A node is subscribed only if it matches some `include` and **no** `exclude`. Because exclude tests the identifier only, write exclusions against the stable id (e.g. `\\.Diagnostics\\.`), not a display name. |
| `include[].topic` | Per-tag publish-topic **override**. The `alarms` matcher routes alarm tags to `alarms/{site}/{InstanceId}/{tagId}` while everything else uses the instance `publish.topic` — useful for sending a subset (alarms, events) to a dedicated stream. |
| `samplingRateMs` (per tag) | How often the **server samples** the underlying value. `50` ms captures fast transients; `0` means "as fast as the server allows." A signal changing faster than this is only observed at sample boundaries — sampling sets the **resolution**. |
| `queueSize` (per tag) | Server-side buffer holding samples taken between two publishes. On overflow the **oldest samples are discarded**. Keep `queueSize ≥ ceil(publishIntervalMs / samplingRateMs)` or you silently drop data — here `200/50 = 4`, so `50` is comfortable. |
| `deadband` | A **server-side** filter applied *before* the queue: the server ignores changes smaller than the threshold, so jitter never enters the pipeline. `type: "Absolute"`, `value: 0.5` suppresses changes below 0.5 engineering units. `type: "Percent"` expresses the threshold as a fraction of the tag's range (requires the server to advertise that range). `type: "None"` disables it. A firm deadband on a fast sampler preserves genuine transients while discarding noise. |

### The timing pipeline at a glance

A value passes through three stages, each with its own control (full discussion in
[explanation.md](explanation.md#the-timing-pipeline-the-thing-most-worth-understanding)):

| You want… | Set |
|-----------|-----|
| One current value per tag per second | `samplingRateMs` and `publishIntervalMs` both ≈ `1000`, small `queueSize`. |
| Every change, low latency | small `samplingRateMs` (e.g. `50`), small `publishIntervalMs` (e.g. `200`), `queueSize ≥ publish/sample`. |
| Fewer, larger messages | raise `batchMs` (the adapter coalesces a tag's samples into one message). |
| One message per change | `batchMs: 0`. |
| Drop sensor noise at the source | add a `deadband`. |

`samplingRateMs` sets **resolution**, `publishIntervalMs` sets **latency**, `batchMs` sets **message
granularity** — and they compound: sampling at 50 ms, publishing at 1 s, batching at 1 s yields
messages each carrying ~20 samples arriving ~1 s after the values were read.

---

## 5. Kubernetes (ConfigMap)

On `--platform KUBERNETES` the config source defaults to `CONFIGMAP`: the whole ConfigMap is mounted
as a **directory** (typically `/etc/ggcommons`) so the adapter watches the kubelet `..data` swap and
**hot-reloads in place** on `kubectl apply` — no restart. The broker config lives in the same
ConfigMap (in-cluster broker via Service DNS); identity comes from the Downward API, so usually **no
CLI args** are needed.

```yaml
# k8s/configmap.yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: OpcUaAdapter-config
  labels: { app.kubernetes.io/name: OpcUaAdapter }
data:
  config.json: |-
    {
      "messaging": {
        "local": { "type": "mqtt", "host": "emqx.default.svc.cluster.local", "port": 1883, "clientId": "OpcUaAdapter" }
      },
      "logging": { "level": "INFO" },
      "metricEmission": { "target": "prometheus" },
      "tags": { "site": "plant1" },
      "component": {
        "global": { "defaults": { "publishIntervalMs": 1000, "samplingRateMs": 500, "queueSize": 100 } },
        "instances": [
          {
            "id": "kep1",
            "adapter": "opcua",
            "connection": { "endpoint": "opc.tcp://192.168.1.50:49320/", "securityPolicy": "None" },
            "publish": { "topic": "southbound/{site}/{ComponentName}/{InstanceId}/{tagId}", "batchMs": 1000 },
            "write":   { "enabled": true },
            "subscriptions": [
              { "id": "process",
                "include": [ { "namespaceUri": "urn:kepware:KEPServerEX", "match": "^Channel1\\.Device1\\..*" } ] }
            ]
          }
        ]
      }
    }
```

| Option | Effect on runtime behavior |
|--------|----------------------------|
| Mounted as a **whole-volume** ConfigMap (never `subPath`) | Preserves the `..data` symlink swap so the `CONFIGMAP` source detects changes and **hot-reloads** subscriptions/timing without a pod restart. Mounting a single key via `subPath` breaks hot reload. |
| `component` section identical to other platforms | The component **name** (`{ComponentName}`) is fixed by the adapter binary, so it does **not** appear in the config — only the `component` *object* (global/instances) does. The same `component`/`connection`/`subscriptions` shape works verbatim on HOST, GREENGRASS, and KUBERNETES. |
| `messaging.local.host` = a Service DNS name | The in-cluster MQTT broker reached via Kubernetes Service DNS (`emqx.default.svc.cluster.local`). The adapter publishes here; point it at your broker Service. |
| `metricEmission.target: "prometheus"` | Exposes `southbound_health` on the pod's metrics port for Prometheus scraping (the Deployment exposes `:9090`), instead of publishing it — the idiomatic k8s path. |
| No `-t/--thing` arg | Identity resolves from the Downward API (`GGCOMMONS_THING_NAME` ▸ `POD_NAME`). The Deployment also gates traffic on the HTTP health probes (`/startupz`, `/livez`, `/readyz`) the library serves. |
| `connection` / `subscriptions` | Same OPC UA semantics as every other platform — only the config **source** (ConfigMap) and the metrics/identity wiring differ. Editing the ConfigMap and re-applying changes the live subscription set on the fly. |

> Deploy with `kubectl apply -f k8s/`; the companion `deployment.yaml` mounts this ConfigMap at
> `/etc/ggcommons` (read-only, whole volume), sets `workingDir: /tmp` (the Java MQTT client needs a
> writable cwd), and wires the health/metrics ports.

---

## 6. One adapter, multiple servers

Because each instance is independent, a single deployment can bridge several OPC UA servers by
listing several `instances` — they share only the process. Mix security and timing per server freely.

```jsonc
"component": {
  "global": { "defaults": { "publishIntervalMs": 500, "samplingRateMs": 200, "queueSize": 20 } },
  "instances": [
    {
      "id": "sim1",
      "adapter": "opcua",
      "connection": { "endpoint": "opc.tcp://10.0.0.11:4840/", "securityPolicy": "None" },
      "publish": { "topic": "southbound/{site}/{ComponentName}/{InstanceId}/{tagId}", "batchMs": 0 },
      "subscriptions": [
        { "id": "sines",
          "include": [ { "namespaceUri": "urn:ggcommons:sim", "match": "Sine.*" } ] }
      ]
    },
    {
      "id": "kep1",
      "adapter": "opcua",
      "connection": {
        "endpoint": "opc.tcp://10.0.0.50:49320",
        "securityPolicy": "Basic256Sha256", "messageMode": "SignAndEncrypt",
        "clientCertificate": { "source": "vault", "secret": "opcua/kep1/appcert" },
        "user": { "source": "vault", "secret": "opcua/kep1/login" }
      },
      "publish": { "topic": "southbound/{site}/{ComponentName}/{InstanceId}/{tagId}", "batchMs": 1000 },
      "subscriptions": [
        { "id": "live",
          "include": [ { "namespaceUri": "urn:kepware:KEPServerEX", "match": "^Plant\\.Line5\\..*" } ] }
      ]
    }
  ]
}
```

| Behavior | Detail |
|----------|--------|
| Independent connections | Each instance connects on its own thread and reconnects with retry. A server that is down or slow to boot delays **only its own instance**; the others keep running. |
| Distinct topics | `{InstanceId}` (here `sim1` / `kep1`) keeps each server's tag updates on separate topics, so consumers can subscribe per server. |
| Per-instance everything | Security, `user`, timing, and `batchMs` are all per instance — `sim1` streams every change immediately (`batchMs: 0`) over a plain channel, while `kep1` batches once a second over an encrypted, authenticated channel. |
| Readiness | The component reports **ready** once the *first* instance is connected and subscribing, so orchestrators are not blocked waiting for every server. |

---

## Where settings resolve from (precedence)

`publishIntervalMs`, `samplingRateMs`, and `queueSize` resolve from the most specific source that
provides them:

```
tag-spec value  ▸  instances[].defaults  ▸  component.global.defaults  ▸  built-in default
```

Built-in defaults: `publishIntervalMs = 1000`, `samplingRateMs = 0` (server's fastest),
`queueSize = 100`. `publishIntervalMs` is a **subscription** setting; `samplingRateMs` and
`queueSize` are **tag-matcher** settings. `batchMs` defaults to the resolved instance
`publishIntervalMs` when omitted.

For the full option matrix, defaults, and template variables, see
[reference/configuration.md](reference/configuration.md).
