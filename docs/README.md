# OPC UA Adapter — Documentation

`com.mbreissi.opcua.OpcUaAdapter` connects to OPC UA servers and bridges their tags onto a message
bus: it streams value changes as structured messages and serves on-demand reads, writes, and
management queries. It is a Greengrass v2 component built on the `ggcommons` library and Eclipse Milo.

This documentation is organized along the four [Diátaxis](https://diataxis.fr/) modes, because reading
to *learn*, to *accomplish a task*, to *look something up*, and to *understand* are different needs:

| | Start here when you want to… | |
|---|---|---|
| **[Tutorial](tutorial.md)** | learn by doing — bring the adapter up against a simulator, end to end | *a guided lesson* |
| **[How-to guides](how-to-guides.md)** | accomplish a specific task — secure a connection, select tags, read/write, deploy | *recipes* |
| **[Reference](reference/)** | look up an exact option, topic, or payload | *the specification* |
| **[Explanation](explanation.md)** | understand how it works and why — the timing pipeline, the two planes, the security model | *the discussion* |

## Quick routing

- **"I'm new here."** → [Tutorial](tutorial.md).
- **"What does this config option do?"** → [Reference — Configuration](reference/configuration.md).
- **"What message do I send / receive on which topic?"** → [Reference — Messaging Interface](reference/messaging-interface.md).
- **"How do I connect to a secured server?"** → [How-to](how-to-guides.md#connect-to-a-secured-server).
- **"Why is my data too fast / slow / laggy?"** → [Explanation — The timing pipeline](explanation.md#the-timing-pipeline-the-thing-most-worth-understanding).
- **"Is it connected and healthy?"** → [How-to — Observe health and status](how-to-guides.md#observe-health-and-status).

## Audience

These docs are for **integrators and operators** — people who deploy the adapter and write clients
that consume or command it. (Contributing to the adapter's own code is not covered here; see the
source and the monorepo's `docs/SOUTHBOUND.md` for the cross-language contract.)

## The two planes, in one picture

```
   OPC UA server(s)            OPC UA Adapter                      message bus
  ┌──────────────┐  browse +  ┌────────────────┐   data plane    ┌────────────────────────┐
  │ PLC / SCADA  │◀─subscribe─│ one instance   │── tag updates ─▶│ southbound/.../<tag>   │
  │ historian    │── values ─▶│ per server     │◀── write ───────│ .../write              │
  │ (opc.tcp://) │◀─ write ───│                │◀─▶ read (req/reply) .../read              │
  │              │── read ───▶│                │◀─▶ control (req/reply) .../control/+      │
  └──────────────┘            └────────────────┘   control plane  health ─▶ metric target │
                                                                  └────────────────────────┘
```

The **data plane** carries process values (the tag-update stream, plus reads and writes); the
**control plane** carries management (status/subscription queries and the health metric). Keeping them
distinct is the key to integrating cleanly — see [Explanation](explanation.md#two-planes-data-and-control).
