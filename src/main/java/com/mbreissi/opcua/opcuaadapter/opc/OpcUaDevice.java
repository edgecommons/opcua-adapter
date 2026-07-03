package com.mbreissi.opcua.opcuaadapter.opc;

import com.google.gson.JsonObject;
import com.mbreissi.ggcommons.GgInstance;
import com.mbreissi.ggcommons.config.ConfigManager;
import com.mbreissi.ggcommons.credentials.CredentialService;
import com.mbreissi.ggcommons.messaging.Message;
import com.mbreissi.ggcommons.messaging.MessagingClient;
import com.mbreissi.ggcommons.metrics.MetricEmitter;
import com.mbreissi.ggcommons.uns.UnsClass;
import com.mbreissi.opcua.opcuaadapter.opc.config.ServerConfiguration;
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
 * {@link SubscriptionManager} (subscribe), {@link SignalUpdatePublisher} (northbound publish onto the
 * UNS {@code data} class), {@link CommandService} (the {@code sb/*} verbs), {@link HealthMetrics}
 * (health metric), and an {@link EventEmitter} (UNS {@code evt} alarms). One per
 * {@code component.instances[]} entry.
 *
 * <p>Publishing and the command/event surface are all addressed through the per-instance UNS handle
 * ({@link GgInstance}): {@code data}/{@code evt} topics are minted by {@code instance.uns()} and every
 * envelope is stamped with the instance's {@code identity}.
 */
public class OpcUaDevice {

    private static final Logger LOGGER = LogManager.getLogger(OpcUaDevice.class);

    private final ServerConfiguration config;
    private final GgInstance instance;
    private final OpcUaConnection connection;
    private final ClientMetrics counters = new ClientMetrics();
    private final Map<NodeId, UaVariableNode> allNodes = new ConcurrentHashMap<>();
    private final EventEmitter events;
    private final CommandService commands;
    private volatile boolean lastConnected;

    public OpcUaDevice(GgInstance instance, MessagingClient messaging, ConfigManager configManager,
                       MetricEmitter metrics, CredentialService credentials, ServerConfiguration config) {
        this.config = config;
        this.instance = instance;
        // Operator-facing evt alarms on ecv1/{device}/{component}/{instance}/evt/{channel}; best-effort.
        this.events = (channel, body) -> {
            try {
                String topic = instance.uns().topic(UnsClass.EVT, channel);
                Message m = instance.newMessage("SouthboundEvent", "1.0").withPayload(body).build();
                messaging.publish(topic, m);
            } catch (Exception e) {
                LOGGER.warn("[{}] failed to emit evt/{}: {}", config.getId(), channel, e.toString());
            }
        };

        HealthMetrics health = new HealthMetrics(metrics, configManager, config.getId(), counters);

        // 1. Connect (blocks + retries).
        connection = new OpcUaConnection(config, credentials);
        OpcUaClient client = connection.connect();
        lastConnected = true;
        health.emit(true);

        // 2. Browse the address space (weakly-consistent map so sb/rescan can refresh it safely).
        allNodes.putAll(new AddressSpaceBrowser(client, config.getId()).browseAll());

        // 3. Northbound publisher (UNS data class) + subscriptions feeding it.
        SignalUpdatePublisher publisher = new SignalUpdatePublisher(messaging, instance, config, client.getNamespaceTable());
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
                boolean now = connection.isConnected();
                health.emit(now);
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

    private void emitConnectionEvent(boolean connected) {
        JsonObject body = new JsonObject();
        body.addProperty("id", config.getId());
        body.addProperty("endpoint", config.getConnection().getEndpoint());
        body.addProperty("connected", connected);
        events.emit(connected ? "connection-restored" : "critical/connection-lost", body);
    }

    public String getId() {
        return config.getId();
    }

    public boolean isConnected() {
        return connection.isConnected();
    }
}
