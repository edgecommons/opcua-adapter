package com.mbreissi.edgecommons.opcua.opc;

import com.google.gson.JsonObject;
import com.mbreissi.edgecommons.EdgeCommonsInstance;
import com.mbreissi.edgecommons.config.ConfigManager;
import com.mbreissi.edgecommons.credentials.CredentialService;
import com.mbreissi.edgecommons.facades.EventsFacade;
import com.mbreissi.edgecommons.metrics.MetricEmitter;
import com.mbreissi.edgecommons.opcua.opc.config.ServerConfiguration;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.milo.opcua.sdk.client.OpcUaClient;
import org.eclipse.milo.opcua.sdk.client.nodes.UaVariableNode;
import org.eclipse.milo.opcua.stack.core.types.builtin.NodeId;

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
 * {@link EventsFacade} (UNS {@code evt} alarms, DESIGN-class-facades §2.2). One per
 * {@code component.instances[]} entry.
 *
 * <p>Publishing and the command/event surface are all addressed through the per-instance UNS handle
 * ({@link EdgeCommonsInstance}): {@code data()}/{@code events()} mint their topics and stamp every envelope with
 * the instance's {@code identity} — this class never hand-mints a topic or hand-builds a body.
 */
public class OpcUaDevice {

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
    private volatile boolean lastConnected;

    public OpcUaDevice(EdgeCommonsInstance instance, ConfigManager configManager,
                       MetricEmitter metrics, CredentialService credentials, ServerConfiguration config) {
        this.config = config;
        this.instance = instance;
        // Operator-facing evt alarms/events on ecv1/{device}/{component}/{instance}/evt/{severity}/{type};
        // the facade derives the channel from the body so it can never disagree with the topic.
        this.events = instance.events();

        HealthMetrics health = new HealthMetrics(metrics, configManager, config.getId(), counters);
        OpcUaOperationalMetrics operationalMetrics = new OpcUaOperationalMetrics(metrics, configManager, config.getId(), counters);
        health.emit(false);

        // 1. Connect (blocks + retries).
        connection = new OpcUaConnection(config, credentials, counters);
        OpcUaClient client = connection.connect(() -> health.emit(false));
        lastConnected = true;
        health.emit(true);

        // 2. Browse the address space (weakly-consistent map so sb/rescan can refresh it safely).
        allNodes.putAll(new AddressSpaceBrowser(client, config.getId()).browseAll());

        // 3. Publisher (UNS data class, via data()) + subscriptions feeding it.
        SignalUpdatePublisher publisher = new SignalUpdatePublisher(instance, config, client.getNamespaceTable());
        SubscriptionManager subscriptions = new SubscriptionManager(client, config, allNodes, publisher, counters);
        subscriptions.createAll();

        // 4. Command surface (sb/status | browse | read | write | subscriptions | rescan). Registered
        // once at the component level (CommandRegistry); this object provides the per-instance logic.
        commands = new CommandService(client, config, counters,
                connection::isConnected, subscriptions::getResolvedSignals, allNodes, events);

        // 5. Periodic flush of batched updates + health emission + connection-transition events.
        long tickMs = config.getBatchMs() > 0 ? config.getBatchMs() : 5000;
        new Timer("device-tick-" + config.getId(), true).scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                publisher.flush();
                // connection.isConnected() is kept live by Milo's SessionActivityListener (wired in
                // OpcUaConnection.connect()) — no active probe needed.
                boolean now = connection.isConnected();
                health.emit(now);
                operationalMetrics.emit(now);
                if (now != lastConnected) {
                    lastConnected = now;
                    emitConnectionEvent(now);
                }
            }
        }, tickMs, tickMs);

        LOGGER.info("[{}] device started", config.getId());
    }

    /** The per-instance command logic backing the component-level {@code sb/*} verbs. */
    public CommandService commandService() {
        return commands;
    }

    /**
     * {@code sb/rescan}: re-browse the server's address space and refresh the node cache used by
     * {@code sb/browse} / {@code sb/read} in place (live subscriptions are unaffected). Result
     * {@code {id, total, rescanned}}.
     */
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
     * raise and clear ride the <b>same</b> {@code evt/critical/connection-lost} channel — a console
     * tracking {@code evt/critical/#} sees both transitions on one topic, replacing the old asymmetric
     * {@code critical/connection-lost} / {@code connection-restored} channel pair).
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

    public boolean isConnected() {
        return connection.isConnected();
    }

    /** The configured OPC UA server endpoint URL — the connectivity report's detail (state instances[]). */
    public String getEndpoint() {
        return config.getConnection().getEndpoint();
    }
}
