# Deployment & Operations

## Build

Requirements: Java 25, Maven, and `com.mbreissi:ggcommons` resolvable (GitHub Packages, or
`mvn install` of `libs/java` into your local `~/.m2`).

```bash
mvn clean package      # -> target/OpcUaAdapter-1.0.0.jar (shaded, self-contained)
```

## CLI contract

| Flag | Meaning |
|------|---------|
| `--platform <P>` | `GREENGRASS` \| `HOST` \| `KUBERNETES` \| `auto` (default `auto`) |
| `--transport <T> [path]` | `IPC` \| `MQTT [messaging.json]` (defaults from platform) |
| `-c/--config <SOURCE> [args]` | `FILE <path>` \| `ENV` \| `GG_CONFIG` \| `SHADOW` \| `CONFIG_COMPONENT` \| `CONFIGMAP` |
| `-t/--thing <name>` | IoT Thing name (also `{ThingName}` in topics) |

## Run targets

### HOST (Docker / bare host, MQTT)
```bash
java -jar target/OpcUaAdapter-1.0.0.jar \
  --platform HOST --transport MQTT ./messaging-local.json \
  -c FILE ./config.json -t my-thing
```
Needs an MQTT broker (e.g. EMQX on `localhost:1883`). `messaging-local.json` is the broker config
(or put a `messaging` section in the main config and drop the positional path).

### Greengrass (on-device, IPC)
Reads config from the deployment; messaging is Greengrass IPC.
```bash
java -jar target/OpcUaAdapter-1.0.0.jar --platform GREENGRASS -c GG_CONFIG -t my-thing
```
Package with the GDK using the bundled `recipe.yaml` + `gdk-config.json`:
```bash
gdk component build && gdk component publish
```

### Kubernetes
The scaffold's `Dockerfile` + `k8s/` manifests (emitted when KUBERNETES is a target platform) run the
container with config from a mounted ConfigMap (`CONFIGMAP` source) and identity from the Downward
API — typically **no args**.

## Lifecycle

The GGCommons library owns shutdown: on `SIGTERM`/`SIGINT` it flips `/readyz` to 503, unsubscribes,
and closes messaging/metrics/vault before the JVM exits 0. The adapter does **not** install its own
hooks; `main()` blocks on a latch until the signal. Each device connection runs on its own thread and
**retries the OPC UA connection every 5s** until it succeeds (so the component starts even if a server
is temporarily down). Readiness flips to ready once at least one instance connects and subscribes.

## Operating the adapter (control plane)

Everything is over the bus (see [messaging-interface.md](messaging-interface.md)):

- **Is it connected?** Request/reply on `…/control/status` → `{ connected, metrics }`.
- **What is it subscribed to?** Request/reply on `…/control/subscriptions` → the resolved tag list.
- **Health metric** `southbound_health` (`connectionState`, `readErrors`) flows to your
  `metricEmission.target` (log/messaging/CloudWatch/Prometheus).
- **Logs** go to console / the configured log target (set `logging.level`). Each subsystem logs under
  its own class (`OpcUaConnection`, `AddressSpaceBrowser`, `SubscriptionManager`, …) with the instance
  id prefix `[<id>]`.

## Validation / regression

`validation/` contains a reproducible smoke harness (asyncua simulator + MQTT test client) for both
plaintext and `Basic256Sha256` connections — covers subscribe→publish, on-demand read, and batch
write. See [../validation/README.md](../validation/README.md).

## Troubleshooting

| Symptom | Likely cause / fix |
|---|---|
| Adapter starts but no `SouthboundTagUpdate` | Subscription `namespace`/`match` don't match any nodes. Query `…/control/subscriptions` to see what resolved; check the server's address space and your regex. |
| `unable to connect to opc.tcp://… Retrying in 5s` (loop) | Server down/unreachable, wrong endpoint, or (secure) server cert untrusted / cert non-compliant — see [security.md](security.md). |
| Writes ignored | `write.enabled` is `false`, or the write entry is missing `ns`/`tagId`/`value`, or the value can't be coerced to the node's data type. |
| Read/control request never gets a reply | The request lacked `reply_to`/`correlation_id`, or the client isn't subscribed to its reply topic. |
| `withConfig(...) ... IllegalStateException` at build time of a message | (client-side) ensure your client sends a valid envelope/body; the adapter accepts raw JSON bodies for write/read. |
| Metrics not appearing | Check `metricEmission.target` and its `targetConfig` (e.g. the topic for `messaging`). |
