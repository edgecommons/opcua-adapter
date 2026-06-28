# OPC UA Adapter — Documentation

`com.mbreissi.opcua.OpcUaAdapter` bridges one or more **OPC UA servers** onto the GGCommons
messaging bus. It browses each server, subscribes to tags, and republishes value changes as
structured messages, and it serves on-demand reads/writes and management queries. It is a normal
Greengrass v2 component built on the `ggcommons` Java library and **Eclipse Milo 1.1.x**.

This folder is the operator/integrator reference. If you are **deploying** the adapter or **writing a
client** that consumes or commands it, start here.

## Documentation map

| Doc | Read it to… |
|-----|-------------|
| **[configuration.md](configuration.md)** | Understand **every configuration option** — what it means, its type, default, and effect. |
| **[messaging-interface.md](messaging-interface.md)** | Understand the **full message interface** — every topic and payload the adapter publishes or accepts (data plane + control plane), with schemas and examples. |
| **[security.md](security.md)** | Configure **secure (encrypted/authenticated) OPC UA connections** — certificate sources, trust, and the OPC UA certificate requirements. |
| **[deployment-and-operations.md](deployment-and-operations.md)** | **Build, deploy** (HOST / Greengrass / Kubernetes) and **operate** (health, status, logs, troubleshooting). |

## What the adapter does (at a glance)

```
   OPC UA server(s)                 OPC UA Adapter                     GGCommons messaging bus
  ┌───────────────┐   browse +    ┌──────────────────┐   publish     ┌──────────────────────┐
  │  PLC / SCADA  │◀── subscribe ─│  one device per   │── tag updates▶│ southbound/.../<tag> │
  │  historian    │── values ────▶│  instance         │               │                      │
  │  (opc.tcp://) │◀── write ─────│  (browse, sub,    │◀── write ─────│ .../write            │
  │               │── read ──────▶│   read, command)  │◀─▶ read (req/reply) .../read          │
  └───────────────┘               └──────────────────┘◀─▶ control (req/reply) .../control/+  │
                                          │ health metric ─▶ metricEmission target            │
                                          └──────────────────────────────────────────────────┘
```

- **One instance per server.** Each entry in `component.instances[]` is an independent connection to
  one OPC UA endpoint, with its own subscriptions, topics, and security.
- **The adapter is configured once and then driven by messages.** After deployment, clients interact
  with it entirely over the bus (publish/subscribe + request/reply) — see the messaging interface.

## Data plane vs. control plane

The adapter's message interface splits into two planes. Knowing which is which tells you what to
subscribe to, what to send, and what to expect back.

### Data plane — tag values (high volume)
The continuous flow of process data between the OPC UA server and the bus.

| Interaction | Direction | Topic (default) | Reply? |
|---|---|---|---|
| **Tag updates** (`SouthboundTagUpdate`) | adapter → bus | `southbound/{site}/{ComponentName}/{InstanceId}/{tagId}` | no (stream) |
| **Write tags** | bus → adapter → device | `southbound/{ComponentName}/{InstanceId}/write` | no |
| **Read tags** (on demand) | bus ↔ adapter ↔ device | `southbound/{ComponentName}/{InstanceId}/read` | **yes** (`SouthboundReadResult`) |

### Control plane — management (low volume)
Operating, observing, and introspecting the adapter itself — not process data.

| Interaction | Direction | Topic (default) | Reply? |
|---|---|---|---|
| **Status query** | bus ↔ adapter | `southbound/{ComponentName}/{InstanceId}/control/status` | **yes** (`status`) |
| **Subscriptions query** | bus ↔ adapter | `southbound/{ComponentName}/{InstanceId}/control/subscriptions` | **yes** (`subscriptions`) |
| **Health metric** (`southbound_health`) | adapter → metric target | per `metricEmission` config | no |
| **Heartbeat** (ggcommons) | adapter → bus | `heartbeat/{ThingName}/{ComponentName}` | no |

Full schemas, examples, and the request/reply mechanics are in
**[messaging-interface.md](messaging-interface.md)**.

## Mental model for a deployer

1. **Configure** the connection(s), subscriptions, and topics — [configuration.md](configuration.md).
2. **Secure** the connection if the server requires it — [security.md](security.md).
3. **Deploy** to your platform — [deployment-and-operations.md](deployment-and-operations.md).
4. **Consume** `SouthboundTagUpdate` and (optionally) **command** the adapter via read/write/control —
   [messaging-interface.md](messaging-interface.md).
