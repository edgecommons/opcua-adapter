# How-to Guides

Goal-oriented recipes for specific tasks. Each assumes you already have the adapter building and
running (see the [tutorial](tutorial.md)) and know what you want to achieve. For the concepts behind
these steps, see [explanation.md](explanation.md); for exhaustive option lists, see
[reference/](reference/).

---

## Connect to a secured server

**Goal:** connect over an encrypted, mutually-authenticated channel (e.g. `Basic256Sha256` /
`SignAndEncrypt`).

1. Obtain a client application instance certificate and key that meet OPC UA's requirements (key
   usage `digitalSignature`, `nonRepudiation`, `keyEncipherment`, `dataEncipherment`; a SubjectAltName
   URI; and, if self-signed, `keyCertSign` + the CA constraint). `validation/gen_certs.py` produces a
   compliant pair you can adapt.

2. Make the certificate available through one source and reference it on the instance's `connection`:

   ```jsonc
   "connection": {
     "endpoint": "opc.tcp://host:4840/",
     "securityPolicy": "Basic256Sha256",
     "messageMode": "SignAndEncrypt",
     "clientCertificate": { "source": "vault", "secret": "opcua/kep1/appcert" },
     "trust": { "pkiDir": "/var/lib/opcua/{InstanceId}/pki" }
   }
   ```
   Use `{ "source": "file", "certPath": "…", "keyPath": "…" }` for files, or a `pkcs11` block for an
   HSM. With the `vault` source, add a `credentials` section and store the secret as
   `{certPem, keyPem, caPem}`.

3. Trust the server. Either pin its certificate —
   `"trust": { …, "serverCertificate": { "source": "file", "path": "server.pem" } }` (or
   `{ "source": "vault", "secret": "opcua/kep1/appcert", "field": "caPem" }`) — or drop it into
   `pkiDir/trusted/certs/`. There is no auto-trust.

4. Have the **server** trust *your* client certificate (its own trust store / UI).

5. Leave `applicationUri` unset unless you must override it; the adapter derives it from the client
   cert's SAN URI, which the server requires to match.

If the channel opens then the session is rejected, the `applicationUri` and the cert SAN URI disagree.
If the client cert is rejected outright with `Bad_CertificateUseNotAllowed`, the key usage is
incomplete. See the [security model](explanation.md#the-security-model).

---

## Choose exactly which tags to publish

**Goal:** subscribe to a precise set of nodes.

Add `include` matchers (and optional `exclude` matchers) to a subscription. Pin the namespace and
match the node with a Java regex:

```jsonc
"subscriptions": [
  {
    "id": "process",
    "include": [ { "namespace": 2, "match": "^Channel1\\.Device1\\.(Temp|Pressure)\\b.*" } ],
    "exclude": [ { "namespace": 2, "match": "\\.Diagnostics\\." } ]
  }
]
```

- Anchor patterns (`^…`) for exact prefixes and escape literal dots (`\\.`) in JSON.
- `include` matches a node's **identifier, browse name, or display name**; `exclude` matches the
  **identifier only** — write exclusions against the identifier.
- Verify what actually resolved with the `subscriptions` control query (namespace indexes can shift —
  see [explanation](explanation.md#addressing-tags-and-a-trap)).

---

## Tune data rate and latency

**Goal:** get the volume and latency you want. Three settings control three pipeline stages — see
[the timing pipeline](explanation.md#the-timing-pipeline-the-thing-most-worth-understanding).

| You want… | Set |
|-----------|-----|
| One current value per tag every second | `samplingRateMs` and `publishIntervalMs` both ≈ `1000`, small `queueSize`. |
| Every change, low latency | small `samplingRateMs` (e.g. `50`), small `publishIntervalMs` (e.g. `200`), `queueSize ≥ publish/sample`. |
| Fewer, larger messages | raise `batchMs` (adapter coalesces a tag's samples into one message). |
| One message per change | `batchMs: 0`. |
| Drop sensor noise at the source | add a `deadband` (`Absolute` in engineering units, or `Percent`). |

Keep `queueSize ≥ ceil(publishIntervalMs / samplingRateMs)` or the server discards the oldest samples.

---

## Read and write tags from a client

**Goal:** read or write arbitrary tags on demand from a bus client.

**Write** (requires `write.enabled: true`) — publish to the write topic, no reply:
```
topic:   southbound/<ComponentName>/<InstanceId>/write
payload: { "writes": [ { "ns": 2, "tagId": "…Setpoint", "value": 42.5 } ] }
```

**Read** — request/reply; set `reply_to` and `correlation_id`, subscribe to your reply topic:
```
publish   topic: southbound/<ComponentName>/<InstanceId>/read
          payload: { "header": { "reply_to": "app/replies/42", "correlation_id": "42" },
                     "body": { "tags": [ { "ns": 2, "tagId": "…Counter" } ] } }
subscribe topic: app/replies/42   → a SouthboundReadResult with correlation_id "42"
```
With a GGCommons client, use its `request()` API instead of setting the header fields by hand. Full
payload schemas are in the [messaging reference](reference/messaging-interface.md).

---

## Route specific tags to their own topic

**Goal:** send a subset of tags (e.g. alarms) to a different topic than the rest.

Set `topic` on the include matcher; it overrides the instance `publish.topic` for those tags:

```jsonc
"include": [
  { "namespace": 2, "match": ".*\\.Alarm\\..*", "topic": "alarms/{site}/{InstanceId}/{tagId}" },
  { "namespace": 2, "match": ".*" }
]
```

---

## Deploy to a platform

**Goal:** run the adapter on HOST, Greengrass, or Kubernetes.

**HOST (Docker / bare host):**
```bash
java -jar target/OpcUaAdapter-1.0.0.jar --platform HOST --transport MQTT ./messaging.json \
  -c FILE ./config.json -t my-thing
```

**Greengrass (on-device):** config comes from the deployment; transport is IPC.
```bash
java -jar target/OpcUaAdapter-1.0.0.jar --platform GREENGRASS -c GG_CONFIG -t my-thing
# package: gdk component build && gdk component publish
```

**Kubernetes:** build the image and apply the manifests (config from a mounted ConfigMap, identity
from the Downward API — typically no args). See the scaffold's `Dockerfile` and `k8s/`.

---

## Observe health and status

**Goal:** know whether the adapter is connected and working.

- **Health metric** `southbound_health` (`connectionState`, `readErrors`) flows to your
  `metricEmission.target` (log / messaging / CloudWatch / Prometheus).
- **Status query:** request/reply on `…/control/status` → `{ connected, metrics }`.
- **Subscriptions query:** request/reply on `…/control/subscriptions` → the resolved tag list.
- **Logs:** each subsystem logs under its own name with the `[<instanceId>]` prefix; raise detail with
  `logging.level`.
