# OPC UA Adapter — Documentation

`com.mbreissi.edgecommons.OpcUaAdapter` connects to OPC UA servers and bridges their signals onto a message
bus: it streams value changes as structured messages and serves on-demand reads, writes, and
management queries. Built on the `edgecommons` library and Eclipse Milo, it runs wherever you deploy it
— as a Greengrass v2 component, a standalone process, or a Kubernetes pod.

| Doc | Start here when you want to… |
|-----|------------------------------|
| **[Tutorial](tutorial.md)** | learn by doing — bring the adapter up against a simulator, end to end |
| **[How-to guides](how-to-guides.md)** | accomplish a specific task — secure a connection, select signals, read/write, deploy |
| **[Reference](reference/)** | look up an exact option, topic, or payload |
| **[Explanation](explanation.md)** | understand how it works and why — the timing pipeline, the two planes, the security model |

## Quick routing

- **"I'm new here."** → [Tutorial](tutorial.md).
- **"What does this config option do?"** → [Reference — Configuration](reference/configuration.md).
- **"What message do I send / receive on which topic?"** → [Reference — Messaging Interface](reference/messaging-interface.md).
- **"What does this metric mean?"** → [Reference — Metrics](reference/metrics.md).
- **"How is each OPC UA / KEP data type represented on the wire?"** → [Reference — Data Types](reference/data-types.md).
- **"How do I connect to a secured server?"** → [How-to](how-to-guides.md#connect-to-a-secured-server).
- **"Why is my data too fast / slow / laggy?"** → [Explanation — The timing pipeline](explanation.md#the-timing-pipeline-the-thing-most-worth-understanding).
- **"Is it connected and healthy?"** → [How-to — Observe health and status](how-to-guides.md#observe-health-and-status).

## Audience

These docs are for **integrators and operators** — people who deploy the adapter and write clients
that consume or command it. They do not cover modifying the adapter's own source.
