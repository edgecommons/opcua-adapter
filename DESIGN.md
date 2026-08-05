# opcua-adapter — design & decision register

Internal design notes for the reference Java OPC UA southbound adapter. This is the state-over-time
record (decisions, rationale, breaking changes); the user-facing surface (`README.md`, `docs/`)
describes only the current behavior.

## Architecture

`OpcUaAdapter` (bootstrap) spawns one worker thread per `component.instances[]` entry; each builds an
`OpcUaDevice` (the live `DeviceSession`). `OpcUaDevice` coordinates the focused collaborators —
`OpcUaConnection` (Milo client + security + reconnect), `AddressSpaceBrowser`, `SubscriptionManager`,
`SignalUpdatePublisher`, `CommandService` (read/write/browse engine), `HealthState`/`HealthMetrics`/
`OpcUaOperationalMetrics`. The component-level `CommandRegistry` registers the `sb/*` verbs once on the
library inbox, each declaring `CommandScope.INSTANCE`, and delegates dispatch to the pure
`CommandRouter`, which resolves the library-supplied addressed instance against the connected devices
(the optional-iff-one default and the `NO_SUCH_INSTANCE` existence check) and calls through the
`DeviceSession` seam.

The seam is the coverage boundary: the router, the health/staleness core, the value/signal codecs, and
the config model are broker-free and unit-tested; the live Milo layer is excluded from the in-process
gate and validated against a real server (`validation/`) and the Greengrass lab.

## Decision register

| ID | Decision | Rationale |
|----|----------|-----------|
| D-OPCUA-1 | **Component-level command inbox; all ten `sb/*` verbs declare `CommandScope.INSTANCE`, and addressing is the library's.** The library `CommandInbox` is one-per-component and subscribes both D-U28 command scopes; this adapter is multi-instance. Each verb registers once via `register(verb, CommandScope.INSTANCE, handler)`; the inbox extracts the delivery topic's `{instance}` token and the body's `instance` field, refuses a conflict between them with `BAD_ARGS` **before dispatch**, and hands the handler the resolved `addressedInstance`. The two policies that need this adapter's configuration stay component-side (D-SC-4): the optional-iff-one default (route to the sole connected instance when none is addressed; `BAD_ARGS` when several are connected) and `NO_SUCH_INSTANCE` for an instance that is not connected. *(Amended twice: the pre-0.4.0 inbox had body-field routing only; the 0.4.0 adoption added `registerScoped` with adapter-side topic/body/conflict logic; the core-0.5.0 adoption deleted that logic — the library owns addressing now.)* | Per-instance inboxes are not a shipped library facade. A declared scope makes "this verb acts on one server" a registration-time fact the library enforces and `describe` advertises, instead of a convention each adapter re-implements; one adapter's hand-rolled topic/body precedence can no longer drift from SOUTHBOUND.md §2.2. |
| D-OPCUA-2 | **`writes.allow[]` is matched on the stable `signal.id`** — the canonical `nsu=<namespaceUri>;<type>=<id>` form (`ns=0;…` in namespace 0), checked before any device I/O; empty list = read-only; `"*"` = allow all. A namespace-index entry (`ns=2;…`) is **rejected at startup**. *(Amended: the original decision named the `ns=<index>;…` parseable form, which contradicted its own rationale — that form embeds the volatile index. See D-OPCUA-13.)* | A volatile namespace index must never gate a control write; the stable id is what consumers already key on. |
| D-OPCUA-3 | **`sb/browse` returns hierarchical reference trees** (`depth` clamped 1..4, `maxRefs` clamped 1..1000, `truncated` flag — the SOUTHBOUND.md §2.2 hierarchical-mode clamps; `ref` stays optional, defaulting to the OPC UA RootFolder, because hierarchical is this adapter's only browse mode and a real address space has a real root — there is no paged `cursor`/`max` form to disambiguate from). | The reference implementation for address-space discovery; bounded to protect the server and the bus. Was `depth` 0..4 / `maxRefs` 1..2000 before the southbound-conformance alignment. |
| D-OPCUA-4 | **`DeviceSession` seam in front of the live Milo layer.** Browse/read/write/subscribe/lifecycle operations are exposed through an interface; `OpcUaDevice` is the live impl, and a fake session drives the `CommandRouter` unit tests. | Makes the dispatch/routing/error-code/lifecycle contract testable without a live server, so the coverage gate is honest and only the thin live driver seam is excluded. |
| D-OPCUA-5 | **`repoll` IS implemented for this subscribe-model adapter** (not N/A): an immediate explicit read of every currently-subscribed signal, republished onto the `data` class — a "refresh now" that does not wait for the next data-change notification. Refused while paused with the top-level code `PAUSED` (was `BAD_ARGS` before the southbound-conformance migration — see the breaking-change register below); session down → `DEVICE_UNAVAILABLE`. | OPC UA is push/subscribe, but an on-demand read-and-republish is a meaningful, well-defined refresh; the issue sanctioned either implementing it or recording N/A, and implementing it is the more useful, faithful choice. |
| D-OPCUA-6 | **`pause`/`resume` suspend publishing, not the server subscriptions.** While paused, `SignalUpdatePublisher.offer`/`flush` drop value changes (subscriptions stay up on the server); idempotent `{paused, changed}`; `sb/status` reports `paused`; an `evt` adapter-paused/adapter-resumed event is emitted. | Gating at the publisher is the least-surprising way to guarantee "no telemetry leaves the adapter while paused" without tearing down and rebuilding server-side subscriptions. |
| D-OPCUA-7 | **`reconnect` cycles the session on the same Milo client** (`client.disconnect()` then `client.connect()`, one immediate attempt), not a full client rebuild. | Milo owns the client's transport and re-transfers subscriptions on reconnect; cycling the session keeps every captured reference (publisher, command engine, subscriptions) valid and the operation cheap. |
| D-OPCUA-8 | **`southbound_health` carries the exact SOUTHBOUND.md §5 eight-measure set:** `connectionState`, `publishLatencyMs`, `pollLatencyMs`, `readErrors`, `staleSignals`, `reconnects`, `writeErrors`, `signalsSubscribed`. `publishLatencyMs` is the last `data()` publish round-trip; `pollLatencyMs` is the last explicit-read round-trip (`repoll`/`sb/read`); `staleSignals` counts subscribed signals with no update past `healthThresholds.staleSignalSecs` (default 30), tracked per-signal in `HealthState` and seeded at subscription time so a signal that never ticks is visible; `writeErrors` counts **device-path** failures only (passed validation + `writes.allow[]`, then server rejection or unavailable-session abort — policy refusals/caller errors surface on `OpcUaCommand.WriteFailure*` instead, mirroring `readErrors`); `signalsSubscribed` is the gauge of monitored items the session currently serves (this subscribe-model adapter's subscription inventory), 0 while disconnected. | Fleet dashboards read every adapter's health the same way; the previous 3-measure set (connectionState/readErrors/writeErrors) was below the canonical contract, and pre-conformance `writeErrors` over-counted by including allow-list refusals and caller errors. |
| D-OPCUA-9 | **The `sb/*` command surface is counted on the existing `OpcUaCommand` family** via new `CommandRequest*`/`CommandFailure*` `(total, interval)` measures (dimensioned by `instance` only), rather than re-dimensioning the family by `verb`×`result`. | The issue's item-8 review calls the instance-only operational-family pattern exemplary/MET; adding a low-cardinality command counter records the whole `sb/*` surface without a wire-metric re-dimension (a per-verb dimension would be a larger, unrequested change to a shipped family). This is a deliberate, surfaced choice — a per-verb breakdown would need a new dimension key. |
| D-OPCUA-10 | **`receivedTs` rides as the additive per-sample extra, captured at receive** (core 0.4.0 / SOUTHBOUND.md §2 four-slot model). OPC UA is a mediated protocol: Milo's SourceTimestamp maps to `sourceTs`, ServerTimestamp (the server's capture stamp) to `serverTs`, and the adapter-receive moment is attached via `Sample.withExtra("receivedTs", …)` — only when it differs from the sample's `serverTs` (an absent `serverTs` always differs: the facade's publish-time default is not the receive moment). Capture happens at `SignalUpdatePublisher.offer` (per value change, before any `batchMs` buffering) and at `sb/read` completion (one batch stamp), never at publish. | Under batching the receive and publish moments diverge; stamping at publish would falsify the slot. The differs-only rule keeps the wire lean where a hypothetical equal stamp adds nothing (per SOUTHBOUND.md: direct-client adapters omit it entirely). |
| D-OPCUA-11 | **`setCommandAvailability` (core 0.4.0): N/A for this adapter — no verb is conditionally available.** Every registered `sb/*` verb is structurally served: `sb/write` is always available as a verb (write *policy* is per-entry `writes.allow[]` gating with per-entry `FAILED` + `evt/warning/write-rejected`, not a verb-level availability state — an empty allow-list means every entry is refused, but the verb still answers), and the lifecycle verbs are always meaningful. No `disabled`/`unsupported` declaration is stored. | The adoption rule is "adopt only where genuinely conditional"; inventing a verb-level state for a per-entry policy would misreport the surface to `describe` consumers (a console would grey out a verb that in fact answers every request). |
| D-OPCUA-12 | **The keepalive's `instances[]` entries carry a `state` token from the one per-instance state model** (core 0.5.0 / D-SC-7). `HealthState` gains a `LinkState` (`CONNECTING` → never been up, `ONLINE`, `BACKOFF` → was up and is re-establishing) folded from the live Milo session flag wherever the device already observes it (initial connect, the device tick, `reconnect`, and each keepalive/`sb/status` sample); the reported token is `PAUSED` when the instance is paused **and** the link is up, else the link token. The same model answers `sb/status`, whose reply gains the same `state` field, and a configured server whose worker has not built its device yet reports `CONNECTING` with no `detail`. | A boolean cannot separate "deliberately paused" from "silently gone stale", so a console alarms on intentional pauses and shows "connected" while data has stopped. Deriving both the pushed and the pulled view from one model is what makes them un-disagreeable; a second bookkeeping path would reintroduce exactly the drift D-SC-7 forbids. A break while paused deliberately reads `BACKOFF` so `connected` stays truthful. |
| D-OPCUA-13 | **Signal identity is `CanonicalSignalId {namespaceUri, idType, identifier}`**, rendered `nsu=<uri>;<type>=<id>` (`ns=0;<type>=<id>` in namespace 0). It is the published `signal.id`, the `writes.allow[]` key, the subscription-inventory key, and the staleness key. The UNS `data` channel token is `<nsToken>_<idType>_<identifier>` where `nsToken` is `ns0` or `u` + the first 8 hex of SHA-256(namespaceUri). | A namespace **index** is a per-session handle (OPC UA Part 3 §5.2.2): after a server renumbers, an index-keyed allow-list can refuse a legitimate write *and* authorize a different node that took over the old index. SOUTHBOUND.md §2 already requires `signal.id` to be canonical and stable, so this is conformance, not a new contract. The channel gains the namespace and id-type discriminators because the bare identifier collided across namespaces and between `i=42`/`s=42`. The namespace token is hashed rather than literal because a namespace URI is vendor text that may contain spaces or `//` (KEPServerEX registers the plain name `Kepware Server`). |
| D-OPCUA-14 | **The address-space traversal is iterative, cycle-safe, budgeted, and continuation-point complete**, returning `BrowseOutcome {nodes, complete, errors, truncated}` rather than a bare map. `sb/rescan` refuses the cache swap unless `complete`. Budgets are `component.global.browse.{maxNodes,maxDepth}`. | Recursion over `References` with no visited set overflowed the stack on a cyclic address space; ignoring `BrowseResult.statusCode` and continuation points produced a silently partial cache; and a partial cache that looks complete let `sb/rescan` erase a healthy address space while reporting success. |
| D-OPCUA-15 | **The subscription inventory records confirmed fact.** A signal enters `resolvedSignals` only after the server confirms its monitored item (`getCreateResult().isGood()`); a re-establish retracts exactly the keys that subscription committed; `subscriptions` is concurrent. `createAll()` returns `SubscriptionOutcome{itemsRequested,itemsLive,subscriptionsFailed}` and readiness requires `connected && (itemsLive > 0 || itemsRequested == 0)`. | The inventory previously recorded intent before the server was asked, and a failure left the entries behind — so `signalsSubscribed` counted signals nobody was monitoring and the component reported ready while serving zero items. |
| D-OPCUA-16 | **Device resources have an explicit lifecycle.** `OpcUaDevice implements AutoCloseable`: cancel the tick executor → delete server-side subscriptions → final flush → disconnect. The periodic tick is a retained `ScheduledExecutorService` whose body catches `Throwable`. The adapter registers a JVM shutdown hook that closes every device. | A bare `java.util.Timer` task dies permanently on one unhandled exception, silently taking batch publishing, health emission, and connection-transition events with it. Subscriptions and sessions left open outlive the process on the server. **Known limit:** hook ordering between the adapter's hook and the library's own is not deterministic, so the shutdown race is narrowed, not closed — a core `EdgeCommons.onShutdown(task)` seam running component tasks *before* messaging teardown is the complete fix and is not yet available. |
| D-OPCUA-17 | **Publisher backpressure is bounded with drop-oldest**, capacity `max(queueSize*4, limits.maxBufferedSamples)`, counted on the operational `OpcUaSubscription.DroppedSample{Total,Interval}`. A publish failure re-buffers the batch; `UnsValidationException` drops it. | An unbounded per-signal queue turns a wedged consumer into an OOM. The counter is *operational*, not a ninth `southbound_health` measure: SOUTHBOUND.md §5 fixes that set at eight for every adapter in every language, and widening it here would be an org-wide contract change. |
| D-OPCUA-18 | **Bounded command execution.** Caller regexes are length-capped and evaluated under a character-access budget; read/write batches are capped by `limits.max{Read,Write}Targets` and **refused** (`BAD_ARGS`) rather than clamped when exceeded; every service call carries `limits.commandTimeoutMs`; batches are partitioned to the server's `MaxNodesPerRead`/`MaxNodesPerWrite`. | Requests arrive over the bus, so every one of these was an unauthenticated resource-exhaustion surface. Refusing rather than clamping keeps a partial read from being mistaken for a complete one. |
| D-OPCUA-19 | **Security selection fails closed.** An unrecognized `securityPolicy`, or a secure policy paired with `messageMode: None`, is an unretryable configuration error; the schema pins the policy `enum`. | The previous fallback to `SecurityPolicy.None` meant a typo silently downgraded an intended encrypted session to an unauthenticated endpoint on any server exposing one — and the free-form schema let the typo pass `edgecommons component validate`. |

## Breaking wire changes in the identity remediation

Bringing `signal.id` into conformance with SOUTHBOUND.md §2 ("canonical and stable") changes
wire-visible and config-visible surfaces. Shipped without aliases, consistent with the SD-E precedent:

- **`signal.id`** (telemetry body, `sb/read`/`sb/signals` replies): `ns=<index>;<type>=<id>` →
  `nsu=<namespaceUri>;<type>=<id>` (`ns=0;…` unchanged in namespace 0).
- **UNS `data` channel token:** the sanitized bare identifier → `<nsToken>_<idType>_<identifier>`.
  Consumers keyed on the old channel literal must be updated.
- **`writes.allow[]` entries:** must be canonical; a `ns=<index>;…` entry now fails the instance at
  startup with a message naming the canonical replacement.
- **`securityPolicy`:** an unrecognized value now fails the instance instead of connecting as `None`.
- **`sb/rescan`:** an incomplete browse now replies `{"rescanned": false, "error": …}` and keeps the
  cache, where it previously reported success with an emptied cache.
- **Per-signal `topic`:** removed from the schema and the parser (the docs already said it was ignored).
- **New refusals:** over-cap `sb/read`/`sb/write` and over-budget matchers answer `BAD_ARGS`; a
  service-call timeout answers `DEVICE_UNAVAILABLE`. No new error-code strings were introduced.

## Breaking wire changes in this baseline (SD-E)

Adopting the standardized `sb/*` family renamed one verb and moved the error codes to the standardized
set. These are **wire-visible** and shipped **without aliases** (the components are experimental/pre-1.0;
a documented breaking change is acceptable, a silent one is not):

- **Verb:** `sb/subscriptions` → **`sb/signals`** (the old verb is removed; the reply gains a `writable`
  flag per entry).
- **Error codes:** `UNKNOWN_INSTANCE` → **`NO_SUCH_INSTANCE`**; `INSTANCE_REQUIRED` / `BAD_MATCHER` /
  `BAD_BROWSE_REF` → **`BAD_ARGS`**. New verb-specific codes: `RECONNECT_FAILED`, `DEVICE_UNAVAILABLE`.

Known consumers were grepped for the old literals (`edge-console` `ui/`+`protocol/`,
`bottling-company-test`); findings are recorded in the remediation PR. Edge-console renders command
replies descriptor-generically, so no code-switch on these strings is expected — the grep is the
verification.

## Breaking wire changes in the southbound-conformance migration

Aligning with the amended SOUTHBOUND.md conventions (the eight-measure §5 set, the `PAUSED` refusal
code, and the §2.2 hierarchical-browse clamps). Wire-visible, shipped without aliases (same pre-1.0
posture as SD-E above):

- **Error code:** `repoll` while paused now refuses with top-level code **`PAUSED`** (was `BAD_ARGS`).
- **`southbound_health`:** gains the **`signalsSubscribed`** gauge (monitored items served while
  connected, 0 while disconnected) — now the full eight-measure set. **`writeErrors`** narrows to
  device-path failures only: allow-list refusals and caller errors (missing value, unresolvable ref,
  bad value type) no longer count (they remain visible on `OpcUaCommand.WriteFailure*`).
- **`sb/browse` clamps:** `depth` 1..4 (was 0..4 — an explicit `depth: 0` now clamps to 1) and
  `maxRefs` 1..1000 (was 1..2000 — larger requests now clamp to 1000).
- **Panel descriptor:** the `address-space` `treeBrowser` no longer advertises `writeVerb` (the
  guarded-write console flow does not exist); `cmd/sb/write` itself is unchanged.

## Additive wire changes in the core-0.5.0 adoption

Neither change removes or renames anything on the wire; consumers that ignore the new field keep
working (per D-SC-7, an absent/unknown `state` must be ignored):

- **`state` keepalive:** each `instances[]` entry gains `state`
  (`CONNECTING` | `ONLINE` | `BACKOFF` | `PAUSED`) beside the existing `connected`/`detail`.
- **`sb/status`:** the reply gains the same `state` field beside `connected`/`paused`.
- **`describe`:** every `sb/*` verb now advertises `"scope": "instance"` (the library populates it from
  the declared `CommandScope`). Addressing errors are raised by the library before the handler runs;
  the codes are unchanged (`BAD_ARGS` for a topic/body conflict), only the message text differs.

## Coverage-gate scope

The JaCoCo 90% line gate excludes **only** the live Milo driver seam + bootstrap: `OpcUaAdapter`,
`OpcUaConnection`, `AddressSpaceBrowser`, `SubscriptionManager`, `SignalUpdatePublisher`,
`CommandService`, `OpcUaDevice` (incl. its timer task), and
`opc/security/**` — all validated on real infrastructure. `CommandRegistry` is **not** excluded (only
its register-on-a-live-inbox calls are uncovered). The router, `HealthState`, `HealthMetrics`,
`OpcUaOperationalMetrics`, `ClientMetrics`, `ValueCodec`, `SignalMatching`, `CommandJson`, and the
config model stay in the gate and are unit-tested. Do not widen the excludes to pass — add tests.

## Naming (WS-9)

Kebab artifact naming end-to-end: Maven `artifactId`/jar (`opcua-adapter-1.0.0.jar`), `recipe.yaml`
artifact URI + Run script, `Dockerfile`, `test-configs/opcua-adapter.json`, and the Kubernetes resource
names (`k8s/deployment.yaml` / `k8s/configmap.yaml` were `OpcUaAdapter*` — invalid RFC 1123, rejected by
the API server; now `opcua-adapter*`). The Greengrass **component name** stays PascalCase reverse-DNS
`com.mbreissi.edgecommons.OpcUaAdapter` (`recipe.yaml`, `gdk-config.json`).
