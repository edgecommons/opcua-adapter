package com.mbreissi.opcua.opcuaadapter.opc;

import com.mbreissi.ggcommons.config.ConfigManager;
import com.mbreissi.ggcommons.credentials.CredentialService;
import com.mbreissi.ggcommons.messaging.MessagingClient;
import com.mbreissi.ggcommons.metrics.MetricEmitter;
import com.mbreissi.opcua.opcuaadapter.opc.config.ServerConfiguration;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.milo.opcua.sdk.client.OpcUaClient;
import org.eclipse.milo.opcua.sdk.client.nodes.UaVariableNode;
import org.eclipse.milo.opcua.stack.core.types.builtin.NodeId;

import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;

/**
 * One OPC UA device connection — the coordinator that wires the focused collaborators:
 * {@link OpcUaConnection} (connect), {@link AddressSpaceBrowser} (browse),
 * {@link SubscriptionManager} (subscribe), {@link SignalUpdatePublisher} (northbound publish),
 * {@link CommandService} (read/write/control), {@link HealthMetrics} (health). One per
 * {@code component.instances[]} entry.
 */
public class OpcUaDevice {

    private static final Logger LOGGER = LogManager.getLogger(OpcUaDevice.class);

    private final ServerConfiguration config;
    private final OpcUaConnection connection;
    private final ClientMetrics counters = new ClientMetrics();

    public OpcUaDevice(ConfigManager configManager, MessagingClient messaging, MetricEmitter metrics,
                       CredentialService credentials, ServerConfiguration config) {
        this.config = config;
        HealthMetrics health = new HealthMetrics(metrics, configManager, config.getId(), counters);

        // 1. Connect (blocks + retries).
        connection = new OpcUaConnection(config, credentials);
        OpcUaClient client = connection.connect();
        health.emit(true);

        // 2. Browse the address space.
        Map<NodeId, UaVariableNode> allNodes = new AddressSpaceBrowser(client, config.getId()).browseAll();

        // 3. Northbound publisher + subscriptions feeding it.
        SignalUpdatePublisher publisher = new SignalUpdatePublisher(messaging, configManager, config, client.getNamespaceTable());
        SubscriptionManager subscriptions = new SubscriptionManager(client, config, allNodes, publisher, counters);
        subscriptions.createAll();

        // 4. Command surface (write / read / control).
        CommandService commands = new CommandService(client, messaging, configManager, config, counters,
                connection::isConnected, subscriptions::getResolvedSignals);
        commands.subscribe();

        // 5. Periodic flush of batched updates + health emission.
        long tickMs = config.getBatchMs() > 0 ? config.getBatchMs() : 5000;
        new Timer("device-tick-" + config.getId(), true).scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                publisher.flush();
                health.emit(connection.isConnected());
            }
        }, tickMs, tickMs);

        LOGGER.info("[{}] device started", config.getId());
    }

    public String getId() {
        return config.getId();
    }

    public boolean isConnected() {
        return connection.isConnected();
    }
}
