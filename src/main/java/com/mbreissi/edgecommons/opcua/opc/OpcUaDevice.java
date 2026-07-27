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
import com.mbreissi.edgecommons.opcua.opc.config.ServerConfiguration;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.milo.opcua.sdk.client.OpcUaClient;
import org.eclipse.milo.opcua.sdk.client.nodes.UaVariableNode;
import org.eclipse.milo.opcua.stack.core.types.builtin.DataValue;
import org.eclipse.milo.opcua.stack.core.types.builtin.NodeId;
import org.eclipse.milo.opcua.stack.core.types.enumerated.TimestampsToReturn;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ConcurrentHashMap;

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
 */
public class OpcUaDevice implements DeviceSession {

    private static final Logger LOGGER = LogManager.getLogger(OpcUaDevice.class);

    /** The alarm type shared by the connection-lost raise and connection-restored clear (same channel). */
    private static final String CONNECTION_ALARM_TYPE = "connection-lost";

    private final ServerConfiguration config;
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
        allNodes.putAll(new AddressSpaceBrowser(client, config.getId()).browseAll());

        // 3. Publisher (UNS data class, via data(), paused-gated + health-instrumented) + subscriptions.
        publisher = new SignalUpdatePublisher(instance, config, client.getNamespaceTable(), health);
        subscriptions = new SubscriptionManager(client, config, allNodes, publisher, counters);
        subscriptions.createAll();
        seedStaleness();

        // 4. Command surface (sb/status | signals | browse | read | write | rescan). Registered once at
        // the component level (CommandRegistry); this object provides the per-instance logic + lifecycle.
        commands = new CommandService(client, config, counters,
                connection::isConnected, subscriptions::getResolvedSignals, allNodes, events, health);

        // 5. Periodic flush of batched updates + health emission + connection-transition events.
        long tickMs = config.getBatchMs() > 0 ? config.getBatchMs() : 5000;
        new Timer("device-tick-" + config.getId(), true).scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
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
            }
        }, tickMs, tickMs);

        LOGGER.info("[{}] device started", config.getId());
    }

    /** Seed the staleness tracker with every configured signal, so a signal that never ticks is visible. */
    private void seedStaleness() {
        long now = System.nanoTime();
        for (ResolvedSignal rs : subscriptions.getResolvedSignals().values()) {
            health.seedSignal(rs.nodeId().toParseableString(), now);
        }
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
    public JsonObject write(JsonObject body) {
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
            List<DataValue> values = client.readValuesAsync(0.0, TimestampsToReturn.Both, nodeIds).get();
            health.setPollLatencyMs(Math.max(0L, (System.nanoTime() - started) / 1_000_000L));
            for (int i = 0; i < nodes.size(); i++) {
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
     */
    @Override
    public JsonObject rescan() {
        OpcUaClient client = connection.getClient();
        Map<NodeId, UaVariableNode> fresh = new AddressSpaceBrowser(client, config.getId()).browseAll();
        // Refresh without an empty window: add/replace first, then drop nodes that vanished.
        allNodes.putAll(fresh);
        allNodes.keySet().retainAll(fresh.keySet());
        LOGGER.info("[{}] rescan: {} variable nodes", config.getId(), allNodes.size());
        JsonObject result = new JsonObject();
        result.addProperty("id", config.getId());
        result.addProperty("total", allNodes.size());
        result.addProperty("rescanned", true);
        return result;
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
