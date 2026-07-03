package com.mbreissi.opcua.opcuaadapter;

import com.google.gson.JsonObject;
import com.mbreissi.ggcommons.GGCommons;
import com.mbreissi.ggcommons.GGCommonsBuilder;
import com.mbreissi.ggcommons.config.ConfigManager;
import com.mbreissi.ggcommons.credentials.CredentialService;
import com.mbreissi.ggcommons.messaging.MessagingClient;
import com.mbreissi.ggcommons.metrics.MetricEmitter;
import com.mbreissi.opcua.opcuaadapter.opc.CommandRegistry;
import com.mbreissi.opcua.opcuaadapter.opc.OpcUaDevice;
import com.mbreissi.opcua.opcuaadapter.opc.config.ServerConfiguration;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;

/**
 * OPC UA → Greengrass southbound adapter, built on the ggcommons Java library (Unified Namespace).
 *
 * <p>Bridges one or more OPC UA servers (one per {@code component.instances[]} entry) onto the
 * GGCommons messaging bus using the southbound contract (docs/SOUTHBOUND.md): subscribes to signals
 * and republishes value changes as {@code SouthboundSignalUpdate} messages on the UNS <b>{@code data}</b>
 * class ({@code ecv1/{device}/{component}/{instance}/data/{signalPath}}), serves the {@code cmd/sb/*}
 * command verbs (status/browse/read/write/subscriptions/rescan) on the library command inbox, raises
 * connection/write {@code evt} alarms, and emits the {@code southbound_health} metric. The site
 * hierarchy rides the top-level {@code identity} envelope element (from {@code hierarchy}/{@code
 * identity} config), stamped automatically per message via {@code gg.instance(id)}.
 */
public class OpcUaAdapter {

    private static final Logger LOGGER = LogManager.getLogger(OpcUaAdapter.class);

    private final GGCommons ggCommons;
    private final ConfigManager config;
    private final MessagingClient messaging;
    private final MetricEmitter metrics;
    private final CredentialService credentials;
    private final CommandRegistry commandRegistry;
    private final List<OpcUaDevice> devices = new ArrayList<>();
    private final CountDownLatch shutdownLatch = new CountDownLatch(1);

    public static void main(String[] args) {
        // No manual shutdown hook: the GGCommons library wires SIGTERM/SIGINT to its graceful,
        // idempotent shutdown() (flips /readyz to 503, unsubscribes, closes messaging/metrics/…).
        new OpcUaAdapter(args).run();
    }

    public OpcUaAdapter(String[] args) {
        ggCommons = GGCommonsBuilder.create("com.mbreissi.opcua.OpcUaAdapter").withArgs(args).build();
        config = ggCommons.getConfigManager();
        messaging = ggCommons.getMessaging();
        metrics = ggCommons.getMetrics();
        credentials = ggCommons.getCredentials();   // null when no 'credentials' config section
        // The sb/* command verbs are registered once on the library's single main-instance command
        // inbox and routed to the right device by the request's "instance" field.
        commandRegistry = new CommandRegistry(ggCommons.getCommands());
    }

    public void run() {
        LOGGER.info("Starting OPC UA adapter (thing={})", config.getThingName());
        ggCommons.setReady(false);

        // Register the southbound command surface before any device connects so a console can address
        // it immediately (an as-yet-unconnected instance replies UNKNOWN_INSTANCE, never blocks).
        commandRegistry.registerVerbs();

        JsonObject globalConfig = config.getGlobalConfig();
        for (String instanceId : config.getInstanceIds()) {
            // Each device connection runs on its own thread: connect() blocks/retries until up.
            Thread worker = new Thread(() -> {
                try {
                    ServerConfiguration serverConfig = new ServerConfiguration(config, globalConfig, instanceId);
                    // The per-message UNS instance token: mints this device's data/evt topics and stamps
                    // its identity. Uses the resolved instance id (= published device.instance).
                    OpcUaDevice device = new OpcUaDevice(ggCommons.instance(serverConfig.getId()),
                            messaging, config, metrics, credentials, serverConfig);
                    synchronized (devices) {
                        devices.add(device);
                    }
                    commandRegistry.addDevice(device);   // now routable by the sb/* verbs
                    ggCommons.setReady(true);            // ready once at least one device is connected + subscribed
                } catch (Exception e) {
                    LOGGER.error("[{}] failed to start client", instanceId, e);
                }
            }, "adapter-" + instanceId);
            worker.setDaemon(true);
            worker.start();
        }

        // Block until the library's signal hook fires graceful shutdown and the JVM exits.
        try {
            shutdownLatch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        LOGGER.info("OPC UA adapter stopped");
    }
}
