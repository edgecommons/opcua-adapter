# Reference - Metrics

The OPC UA adapter emits health and operational metrics through the EdgeCommons metric service. With
`metricEmission.target: messaging`, metrics are published on the reserved UNS `metric` class:

```text
ecv1/{device}/OpcUaAdapter/main/metric/{metricName}
```

The adapter never writes reserved `metric` topics directly. It defines metrics through `MetricEmitter`,
so the same names, measures, units, and dimensions are used by messaging, CloudWatch, and Prometheus
targets.

## Dimension model

OPC UA metrics use a deliberately small CloudWatch dimension set. Each metric has the `instance`
dimension, plus runtime-injected dimensions such as component identity and category. Operation and
window semantics are encoded in measure names rather than high-cardinality dimensions.

Endpoint URLs, node ids, namespace URIs, browse names, and raw status text are not metric dimensions.
Use data messages, events, logs, or command replies for those details.

## `southbound_health`

Small compatibility health metric for per-instance connection state and read/write errors.

Dimensions: `instance`.

| Measure | Unit | Purpose |
|---|---:|---|
| `connectionState` | Count | `1` connected, `0` disconnected. Helps drive simple per-server health alarms. |
| `readErrors` | Count | Read errors observed during the reporting interval. Helps identify failing explicit reads. |
| `writeErrors` | Count | Write errors observed during the reporting interval. Helps identify rejected or failed control writes. |

## `OpcUaCommand`

Explicit `sb/read` and `sb/write` command activity.

Dimensions: `instance`.

| Measure | Unit | Purpose |
|---|---:|---|
| `ReadRequestTotal` | Count | Lifetime explicit `sb/read` samples returned. Helps quantify command-plane read demand. |
| `ReadRequestInterval` | Count | Explicit `sb/read` samples returned in the interval. Helps build current read-rate dashboards. |
| `ReadFailureTotal` | Count | Lifetime failed `sb/read` command requests. Helps identify persistent explicit-read problems. |
| `ReadFailureInterval` | Count | Failed `sb/read` command requests in the interval. Helps alert on active read failures. |
| `WriteRequestTotal` | Count | Lifetime `sb/write` entries issued to the server. Helps audit write activity. |
| `WriteRequestInterval` | Count | `sb/write` entries issued in the interval. Helps build current write-rate dashboards. |
| `WriteFailureTotal` | Count | Lifetime failed `sb/write` entries, including preflight rejection and bad server status. Helps audit failed control actions. |
| `WriteFailureInterval` | Count | Failed `sb/write` entries in the interval. Helps alert on active write failures. |

## `OpcUaSubscription`

Subscription data-change throughput and subscription shape.

Dimensions: `instance`.

| Measure | Unit | Purpose |
|---|---:|---|
| `SubscribedReadTotal` | Count | Lifetime subscription data-change samples received. Helps measure total streamed telemetry volume. |
| `SubscribedReadInterval` | Count | Subscription data-change samples received in the interval. Helps measure current telemetry rate. |
| `SubscriptionRecreateTotal` | Count | Lifetime subscription re-establishment attempts after transfer failure. Helps detect unstable sessions. |
| `SubscriptionRecreateInterval` | Count | Subscription re-establishment attempts in the interval. Helps alert on active subscription churn. |
| `SubscriptionCount` | Count | Active OPC UA subscription objects. Helps verify configured subscription grouping. |
| `MonitoredItemCount` | Count | Resolved monitored signal count. Helps confirm the live subscription inventory. |

## `OpcUaBrowse`

Address-space browse command use and response size.

Dimensions: `instance`.

| Measure | Unit | Purpose |
|---|---:|---|
| `BrowseRequestTotal` | Count | Lifetime `sb/browse` command requests. Helps quantify address-space exploration demand. |
| `BrowseRequestInterval` | Count | `sb/browse` requests in the interval. Helps spot active console or API browsing. |
| `BrowseFailureTotal` | Count | Lifetime failed `sb/browse` command requests. Helps identify browse permissions or server issues. |
| `BrowseFailureInterval` | Count | Failed `sb/browse` requests in the interval. Helps alert on active browse failures. |
| `BrowseReferenceTotal` | Count | Lifetime hierarchical references returned. Helps understand browse response volume. |
| `BrowseReferenceInterval` | Count | Hierarchical references returned in the interval. Helps detect expensive browse activity. |
| `BrowseTruncatedTotal` | Count | Lifetime browse responses truncated by `maxRefs`. Helps identify too-broad browse requests. |
| `BrowseTruncatedInterval` | Count | Truncated browse responses in the interval. Helps tune console/API browse depth and limits. |

## `OpcUaConnection`

Initial connection, terminal failures, and live session transitions.

Dimensions: `instance`.

| Measure | Unit | Purpose |
|---|---:|---|
| `ConnectionAttemptTotal` | Count | Lifetime initial OPC UA connection attempts. Helps detect startup retry loops. |
| `ConnectionAttemptInterval` | Count | Initial connection attempts in the interval. Helps alert on active retry storms. |
| `ConnectionFailureTotal` | Count | Lifetime failed initial connection attempts. Helps diagnose unavailable servers or bad endpoint config. |
| `ConnectionFailureInterval` | Count | Failed initial connection attempts in the interval. Helps alert on current connectivity failure. |
| `TerminalFailureTotal` | Count | Lifetime unretryable initial connection failures. Helps identify bad credentials, security policy, or certificate configuration. |
| `TerminalFailureInterval` | Count | Unretryable initial connection failures in the interval. Helps alert on configuration failures that will not self-heal. |
| `SessionDisconnectTotal` | Count | Lifetime active sessions becoming inactive after initial connection. Helps measure runtime instability. |
| `SessionDisconnectInterval` | Count | Active sessions becoming inactive in the interval. Helps alert on current server/network disruption. |
| `SessionReconnectTotal` | Count | Lifetime inactive sessions becoming active again. Helps confirm Milo session recovery. |
| `SessionReconnectInterval` | Count | Session reconnections in the interval. Helps detect churn even when the final state is connected. |
| `SessionConnected` | Count | `1` connected, `0` disconnected. Helps build current liveness dashboards. |
