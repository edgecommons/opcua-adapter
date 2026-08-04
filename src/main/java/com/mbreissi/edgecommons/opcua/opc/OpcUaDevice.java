package com.mbreissi.edgecommons.opcua.opc;

import com.google.gson.JsonObject;
import com.mbreissi.edgecommons.EdgeCommonsInstance;
import com.mbreissi.edgecommons.commands.CommandException;
import com.mbreissi.edgecommons.config.ConfigManager;
import com.mbreissi.edgecommons.credentials.CredentialService;
import com.mbreissi.edgecommons.facades.EventsFacade;
import com.mbreissi.edgecommons.facades.Severity;
import com.mbreissi.edgecommons.heartbeat.InstanceConnectivity;
import com.mbreissi.edgecommons.metrics.MetricEmitter;
import com.mbreissi.edgecommons.opcua.opc.config.AdapterLimits;
import com.mbreissi.edgecommons.opcua.opc.config.ServerConfiguration;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.milo.opcua.sdk.client.OpcUaClient;
import org.eclipse.milo.opcua.sdk.client.nodes.UaVariableNode;
import org.eclipse.milo.opcua.stack.core.NodeIds;
import org.eclipse.milo.opcua.stack.core.types.builtin.DataValue;
import org.eclipse.milo.opcua.stack.core.types.builtin.NodeId;
import org.eclipse.milo.opcua.stack.core.types.enumerated.TimestampsToReturn;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * One OPC UA device connection — the coordinator that wires the focused collaborators:
 * {@link OpcUaConnection} (connect), {@link AddressSpaceBrowser} (browse),
 * {@link SubscriptionManager} (subscribe), {@link SignalUpdatePublisher} (publishes onto the UNS
 * {@code data} class via the {@code data()} facade), {@link CommandService} (the {@code sb/*} verbs),
 * {@link HealthMetrics} / {@link OpcUaOperationalMetrics} (metrics), and the instance-bound
 * {@link EventsFacade} (UNS {@code evt} alarms). One per {@code component.instances[]} entry.
 *
 * <p>This class is the live {@link DeviceSession} implementation the component-level {@link CommandRouter}
 * routes through — it holds the Eclipse Milo client and can only be exercised against a real server, so
 * it is the thin live driver seam excluded from the in-process coverage gate (validated by
 * {@code validation/} against KEPServerEX). The generic dispatch/routing/error-code/lifecycle logic
 * above it lives in {@link CommandRouter} and is unit-tested with a fake session.
 *
 * <p>The device owns two resources with a lifetime: the periodic tick and the OPC UA session. Both are
 * released by {@link #close()} — a device that keeps publishing while the library tears messaging down
 * races its own shutdown, and subscriptions left behind stay live on the server.
 */
public class OpcUaDevice implements DeviceSession, AutoCloseable {

    private static final Logger LOGGER = LogManager.getLogger(OpcUaDevice.class);

    /** The alarm type shared by the connection-lost raise and connection-restored clear (same channel). */
    private static final String CONNECTION_ALARM_TYPE = "connection-lost";
    /** How long {@link #close()} waits for the in-flight tick to finish before moving on. */
    private static final long CLOSE_TICK_GRACE_MS = 2_000L;

    private final ServerConfiguration config;
    private final AdapterLimits limits;
    private final EdgeCommonsInstance instance;
    private final OpcUaConnection connection;
    private final ClientMetrics counters = new ClientMetrics();
    private final Map<NodeId, UaVariableNode> allNodes = new ConcurrentHashMap<>();
    private final EventsFacade events;
    private final CommandService commands;
    private final HealthState health = new HealthState();
    private final HealthMetrics healthMetrics;
    private final SignalUpdatePublisher publisher;
    private final SubscriptionManager subscriptions;
    private final SubscriptionManager.SubscriptionOutcome subscriptionOutcome;
    private final ScheduledExecutorService ticker;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private volatile boolean lastConnected;

    public OpcUaDevice(EdgeCommonsInstance instance, ConfigManager configManager,
                       MetricEmitter metrics, CredentialService credentials, ServerConfiguration config) {
        this(instance, configManager, metrics, credentials, config,
                HealthState.DEFAULT_STALE_SIGNAL_SECS);
    }

    public OpcUaDevice(EdgeCommonsInstance instance, ConfigManager configManager,
                       MetricEmitter metrics, CredentialService credentials, ServerConfiguration config,
                       long staleSignalSecs) {
        this.config = config;
        this.limits = config.getLimits();
        this.instance = instance;
        // Operator-facing evt alarms/events on ecv1/{device}/{component}/{instance}/evt/{severity}/{type};
        // the facade derives the channel from the body so it can never disagree with the topic.
        this.events = instance.events();

        this.healthMetrics = new HealthMetrics(metrics, configManager, config.getId(), counters, health,
                staleSignalSecs);
        OpcUaOperationalMetrics operationalMetrics =
                new OpcUaOperationalMetrics(metrics, configManager, config.getId(), counters);
        healthMetrics.emit(false);

        // 1. Connect (blocks + retries). Each failed attempt keeps the link state CONNECTING (it has
        // never been up), so the keepalive reports a server that is still coming up as such.
        connection = new OpcUaConnection(config, credentials, counters);
        OpcUaClient client = connection.connect(() -> {
            health.observeConnected(false);
            healthMetrics.emit(false);
        });
        lastConnected = true;
        health.observeConnected(true);
        healthMetrics.emitNow(true);

        // 2. Browse the address space (weakly-consistent map so sb/rescan can refresh it safely).
        BrowseOutcome browse = new AddressSpaceBrowser(
                new MiloBrowseTransport(client), config.getId(), limits).browseAll();
        allNodes.putAll(browse.nodes());
        if (!browse.complete()) {
            LOGGER.warn("[{}] initial browse incomplete: {}", config.getId(), browse.incompleteReason());
        }

        // 3. Publisher (UNS data class, via data(), paused-gated + health-instrumented) + subscriptions.
        publisher = new SignalUpdatePublisher(instance, config, client.getNamespaceTable(), health, counters);
        subscriptions = new SubscriptionManager(client, config, allNodes, publisher, counters);
        subscriptionOutcome = subscriptions.createAll();
        seedStaleness();
        if (!subscriptionOutcome.healthy()) {
            LOGGER.warn("[{}] subscriptions degraded: {}/{} monitored item(s) live, {} subscription(s) failed",
                    config.getId(), subscriptionOutcome.itemsLive(), subscriptionOutcome.itemsRequested(),
                    subscriptionOutcome.subscriptionsFailed());
            emitSubscriptionsDegraded();
        }

        // 4. Command surface (sb/status | signals | browse | read | write | rescan). Registered once at
        // the component level (CommandRegistry); this object provides the per-instance logic + lifecycle.
        commands = new CommandService(client, config, counters,
                connection::isConnected, subscriptions::getResolvedSignals, allNodes, events, health,
                readOperationLimit(client, NodeIds.Server_ServerCapabilities_OperationLimits_MaxNodesPerRead),
                readOperationLimit(client, NodeIds.Server_ServerCapabilities_OperationLimits_MaxNodesPerWrite));

        // 5. Periodic flush of batched updates + health emission + connection-transition events. A
        // retained executor (not a fire-and-forget Timer): it is cancellable at close, and a task that
        // throws is isolated rather than killing the thread and silently stopping every periodic duty.
        long tickMs = config.getBatchMs() > 0 ? config.getBatchMs() : 5000;
        ticker = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "device-tick-" + config.getId());
            t.setDaemon(true);
            return t;
        });
        ticker.scheduleAtFixedRate(() -> tick(operationalMetrics), tickMs, tickMs, TimeUnit.MILLISECONDS);

        LOGGER.info("[{}] device started", config.getId());
    }

    /**
     * The periodic duties: flush batched updates, sample the link, emit metrics, and raise/clear the
     * connection alarm on a transition.
     *
     * <p>Guarded against {@link Throwable}: with a bare task, one unhandled exception would terminate
     * the scheduled task permanently, taking batch publishing, health emission, and connection-event
     * reporting down together and silently.
     */
    private void tick(OpcUaOperationalMetrics operationalMetrics) {
        try {
            publisher.flush();
            // connection.isConnected() is kept live by Milo's SessionActivityListener (wired in
            // OpcUaConnection.connect()) — no active probe needed.
            boolean now = connection.isConnected();
            health.observeConnected(now);
            operationalMetrics.emit(now);
            if (now != lastConnected) {
                lastConnected = now;
                emitConnectionEvent(now);
                healthMetrics.emitNow(now);   // immediate transition emit (SOUTHBOUND.md §5)
            } else {
                healthMetrics.emit(now);      // periodic sampler
            }
        } catch (Throwable t) {
            LOGGER.error("[{}] device tick failed (continuing): {}", config.getId(), t.toString(), t);
        }
    }

    /**
     * Read a server-published operation limit ({@code MaxNodesPerRead} / {@code MaxNodesPerWrite}).
     * Returns 0 when the server does not expose it, which leaves the configured chunk in charge.
     */
    private int readOperationLimit(OpcUaClient client, NodeId limitNode) {
        try {
            List<DataValue> values = client.readValues(0.0, TimestampsToReturn.Neither, List.of(limitNode));
            if (values.isEmpty() || values.get(0).getValue() == null) {
                return 0;
            }
            Object value = values.get(0).getValue().getValue();
            if (value instanceof Number n) {
                long v = n.longValue();
                return v > 0 && v <= Integer.MAX_VALUE ? (int) v : 0;
            }
        } catch (Exception e) {
            LOGGER.debug("[{}] server operation limit {} unavailable: {}", config.getId(), limitNode, e.toString());
        }
        return 0;
    }

    /** Seed the staleness tracker with every subscribed signal, so a signal that never ticks is visible. */
    private void seedStaleness() {
        long now = System.nanoTime();
        for (ResolvedSignal rs : subscriptions.getResolvedSignals().values()) {
            health.seedSignal(rs.signalId(), now);
        }
    }

    private void emitSubscriptionsDegraded() {
        if (events == null) {
            return;
        }
        JsonObject context = new JsonObject();
        context.addProperty("id", config.getId());
        context.addProperty("itemsRequested", subscriptionOutcome.itemsRequested());
        context.addProperty("itemsLive", subscriptionOutcome.itemsLive());
        context.addProperty("subscriptionsFailed", subscriptionOutcome.subscriptionsFailed());
        events.emit(Severity.WARNING, "subscriptions-degraded",
                "not every configured signal is being monitored", context);
    }

    /** What the subscription build achieved — the basis for this device's contribution to readiness. */
    public SubscriptionManager.SubscriptionOutcome subscriptionOutcome() {
        return subscriptionOutcome;
    }

    /** Whether this device is serving telemetry: connected, with monitored items live. */
    public boolean isServing() {
        return connection.isConnected() && subscriptionOutcome.serving();
    }

    // ---- DeviceSession ---------------------------------------------------------------------------

    @Override
    public String id() {
        return config.getId();
    }

    @Override
    public boolean isConnected() {
        return connection.isConnected();
    }

    @Override
    public boolean isPaused() {
        return health.isPaused();
    }

    @Override
    public JsonObject status() {
        health.observeConnected(connection.isConnected());
        JsonObject result = commands.status();
        result.addProperty("state", health.stateToken());
        result.addProperty("paused", health.isPaused());
        return result;
    }

    /**
     * This device's {@code state} keepalive element (D‑SC‑7), sampled from the same {@link HealthState}
     * that answers {@code sb/status}: the live {@code connected} flag, the endpoint as {@code detail},
     * and the {@code CONNECTING}/{@code ONLINE}/{@code BACKOFF}/{@code PAUSED} state token.
     */
    public InstanceConnectivity connectivity() {
        health.observeConnected(connection.isConnected());
        return health.connectivity(config.getId(), getEndpoint());
    }

    @Override
    public JsonObject signals() {
        return commands.signals();
    }

    @Override
    public JsonObject browse(JsonObject body) throws CommandException {
        return commands.browse(body);
    }

    @Override
    public JsonObject read(JsonObject body) throws Exception {
        return commands.read(body);
    }

    @Override
    public JsonObject write(JsonObject body) throws CommandException {
        return commands.write(body);
    }

    @Override
    public void recordCommand(boolean ok) {
        counters.recordCommand(ok);
    }

    @Override
    public boolean pause() {
        boolean changed = health.setPaused(true);
        if (changed) {
            JsonObject ctx = new JsonObject();
            ctx.addProperty("id", config.getId());
            events.emit(Severity.WARNING, "adapter-paused", "telemetry production paused", ctx);
        }
        return changed;
    }

    @Override
    public boolean resume() {
        boolean changed = health.setPaused(false);
        if (changed) {
            JsonObject ctx = new JsonObject();
            ctx.addProperty("id", config.getId());
            events.emit(Severity.INFO, "adapter-resumed", "telemetry production resumed", ctx);
        }
        return changed;
    }

    @Override
    public boolean reconnect() throws CommandException {
        try {
            connection.reconnect();
        } catch (Exception e) {
            health.observeConnected(connection.isConnected());
            healthMetrics.emitNow(connection.isConnected());
            throw new CommandException(CommandRouter.ERR_RECONNECT_FAILED,
                    "reconnect failed: " + (e.getMessage() != null ? e.getMessage() : e.toString()));
        }
        health.observeConnected(connection.isConnected());
        healthMetrics.emitNow(connection.isConnected());
        return connection.isConnected();
    }

    /**
     * {@code repoll}: an immediate explicit read of every subscribed signal, republished onto the
     * {@code data} class — the subscribe-model adapter's "refresh now". Records the read round-trip into
     * {@code southbound_health.pollLatencyMs}. Returns the number of signals read.
     *
     * <p>Partitioned by the server's {@code MaxNodesPerRead} and bounded by a deadline: a large
     * inventory must not become one unbounded service call that the adapter then waits on forever.
     */
    @Override
    public long repoll() throws CommandException {
        OpcUaClient client = connection.getClient();
        if (client == null || !connection.isConnected()) {
            throw new CommandException(CommandRouter.ERR_DEVICE_UNAVAILABLE, "OPC UA session is down");
        }
        List<NodeId> nodeIds = new ArrayList<>();
        List<UaVariableNode> nodes = new ArrayList<>();
        List<ResolvedSignal> specs = new ArrayList<>();
        for (ResolvedSignal rs : subscriptions.getResolvedSignals().values()) {
            UaVariableNode node = allNodes.get(rs.nodeId());
            if (node != null) {
                nodeIds.add(rs.nodeId());
                nodes.add(node);
                specs.add(rs);
            }
        }
        if (nodeIds.isEmpty()) {
            return 0;
        }
        try {
            long started = System.nanoTime();
            List<DataValue> values = commands.readChunked(nodeIds);
            health.setPollLatencyMs(Math.max(0L, (System.nanoTime() - started) / 1_000_000L));
            for (int i = 0; i < nodes.size() && i < values.size(); i++) {
                counters.recordSubscribedRead();
                publisher.offer(nodes.get(i), specs.get(i).spec(), values.get(i));
            }
            publisher.flush();
            return nodeIds.size();
        } catch (Exception e) {
            counters.incrementReadErrors();
            throw new CommandException(CommandRouter.ERR_DEVICE_UNAVAILABLE,
                    "repoll read failed: " + (e.getMessage() != null ? e.getMessage() : e.toString()));
        }
    }

    /**
     * {@code sb/rescan}: re-browse the server's address space and refresh the node cache used by
     * {@code sb/browse} / {@code sb/read} in place (live subscriptions are unaffected). Result
     * {@code {id, total, rescanned}}.
     *
     * <p>An incomplete browse does <b>not</b> replace the cache. A failed traversal returns few or no
     * nodes, and swapping that in would erase a healthy address space while reporting success — so the
     * refusal is reported instead, with the existing cache left intact.
     */
    @Override
    public JsonObject rescan() {
        OpcUaClient client = connection.getClient();
        JsonObject result = new JsonObject();
        result.addProperty("id", config.getId());
        if (client == null || !connection.isConnected()) {
            result.addProperty("total", allNodes.size());
            result.addProperty("rescanned", false);
            result.addProperty("error", "OPC UA session is down");
            return result;
        }
        BrowseOutcome outcome = new AddressSpaceBrowser(
                new MiloBrowseTransport(client), config.getId(), limits).browseAll();
        if (!outcome.complete()) {
            LOGGER.warn("[{}] rescan refused: {}", config.getId(), outcome.incompleteReason());
            result.addProperty("total", allNodes.size());
            result.addProperty("rescanned", false);
            result.addProperty("error", outcome.incompleteReason());
            return result;
        }
        // Refresh without an empty window: add/replace first, then drop nodes that vanished.
        allNodes.putAll(outcome.nodes());
        allNodes.keySet().retainAll(outcome.nodes().keySet());
        LOGGER.info("[{}] rescan: {} variable nodes", config.getId(), allNodes.size());
        result.addProperty("total", allNodes.size());
        result.addProperty("rescanned", true);
        return result;
    }

    /**
     * Release everything this device owns, in the order that keeps the bus truthful: stop producing,
     * stop the server producing, drain what is already buffered while messaging is still up, then drop
     * the session. Idempotent.
     */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        LOGGER.info("[{}] closing device", config.getId());
        // 1. Stop the tick, so nothing new is published while we tear down.
        try {
            ticker.shutdown();
            if (!ticker.awaitTermination(CLOSE_TICK_GRACE_MS, TimeUnit.MILLISECONDS)) {
                ticker.shutdownNow();
            }
        } catch (InterruptedException e) {
            ticker.shutdownNow();
            Thread.currentThread().interrupt();
        } catch (RuntimeException e) {
            LOGGER.debug("[{}] stopping device tick failed: {}", config.getId(), e.toString());
        }
        // 2. Delete server-side subscriptions so no monitored items outlive the adapter.
        try {
            subscriptions.closeAll();
        } catch (RuntimeException e) {
            LOGGER.debug("[{}] closing subscriptions failed: {}", config.getId(), e.toString());
        }
        // 3. Final flush while the library's messaging is still open.
        try {
            publisher.flush();
        } catch (RuntimeException e) {
            LOGGER.debug("[{}] final flush failed: {}", config.getId(), e.toString());
        }
        // 4. Drop the session.
        try {
            connection.disconnect();
        } catch (RuntimeException e) {
            LOGGER.debug("[{}] disconnect failed: {}", config.getId(), e.toString());
        }
        LOGGER.info("[{}] device closed", config.getId());
    }

    /**
     * Raises/clears the connection-lost alarm (severity defaults to {@code critical} for both, so the
     * raise and clear ride the <b>same</b> {@code evt/critical/connection-lost} channel).
     */
    private void emitConnectionEvent(boolean connected) {
        JsonObject context = new JsonObject();
        context.addProperty("id", config.getId());
        context.addProperty("endpoint", config.getConnection().getEndpoint());
        if (connected) {
            events.clearAlarm(CONNECTION_ALARM_TYPE, context);
        } else {
            events.raiseAlarm(CONNECTION_ALARM_TYPE, "OPC UA connection lost", context);
        }
    }

    public String getId() {
        return config.getId();
    }

    /** The configured OPC UA server endpoint URL — the connectivity report's detail (state instances[]). */
    public String getEndpoint() {
        return config.getConnection().getEndpoint();
    }
}
