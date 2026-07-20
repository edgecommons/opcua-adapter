package com.mbreissi.edgecommons.opcua;

import com.google.gson.JsonObject;
import com.mbreissi.edgecommons.EdgeCommons;
import com.mbreissi.edgecommons.EdgeCommonsBuilder;
import com.mbreissi.edgecommons.config.ConfigManager;
import com.mbreissi.edgecommons.credentials.CredentialService;
import com.mbreissi.edgecommons.heartbeat.InstanceConnectivity;
import com.mbreissi.edgecommons.metrics.MetricEmitter;
import com.mbreissi.edgecommons.opcua.opc.CommandRegistry;
import com.mbreissi.edgecommons.opcua.opc.HealthState;
import com.mbreissi.edgecommons.opcua.opc.OpcUaConnection;
import com.mbreissi.edgecommons.opcua.opc.OpcUaDevice;
import com.mbreissi.edgecommons.opcua.opc.config.ServerConfiguration;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

/**
 * OPC UA → Greengrass southbound adapter, built on the edgecommons Java library (Unified Namespace).
 *
 * <p>Bridges one or more OPC UA servers (one per {@code component.instances[]} entry) onto the
 * EdgeCommons messaging bus using the southbound contract (docs/SOUTHBOUND.md): subscribes to signals
 * and republishes value changes as {@code SouthboundSignalUpdate} messages on the UNS <b>{@code data}</b>
 * class ({@code ecv1/{device}/{component}/{instance}/data/{signalPath}}), serves the {@code cmd/sb/*}
 * command verbs (status/browse/read/write/subscriptions/rescan) on the library command inbox, raises
 * connection/write {@code evt} alarms, and emits {@code southbound_health} plus OPC UA operational
 * metrics. The site
 * hierarchy rides the top-level {@code identity} envelope element (from {@code hierarchy}/{@code
 * identity} config), stamped automatically per message via {@code gg.instance(id)}.
 */
public class OpcUaAdapter {

    private static final Logger LOGGER = LogManager.getLogger(OpcUaAdapter.class);

    private final EdgeCommons edgeCommons;
    private final ConfigManager config;
    private final MetricEmitter metrics;
    private final CredentialService credentials;
    private final CommandRegistry commandRegistry;
    private final List<OpcUaDevice> devices = new ArrayList<>();
    private final CountDownLatch shutdownLatch = new CountDownLatch(1);

    public static void main(String[] args) {
        // No manual shutdown hook: the EdgeCommons library wires SIGTERM/SIGINT to its graceful,
        // idempotent shutdown() (flips /readyz to 503, unsubscribes, closes messaging/metrics/…).
        new OpcUaAdapter(args).run();
    }

    public OpcUaAdapter(String[] args) {
        edgeCommons = EdgeCommonsBuilder.create("com.mbreissi.edgecommons.OpcUaAdapter").withArgs(args).build();
        config = edgeCommons.getConfigManager();
        metrics = edgeCommons.getMetrics();
        credentials = edgeCommons.getCredentials();   // null when no 'credentials' config section
        // The sb/* command verbs are registered once on the library's single component-scope command
        // inbox and routed to the right device by the request's "instance" field.
        commandRegistry = new CommandRegistry(edgeCommons.getCommands());
    }

    public void run() {
        LOGGER.info("Starting OPC UA adapter (thing={})", config.getThingName());
        edgeCommons.setReady(false);

        // Register the southbound command surface before any device connects so a console can address
        // it immediately (an as-yet-unconnected instance replies NO_SUCH_INSTANCE, never blocks).
        commandRegistry.registerVerbs();

        // Report each configured OPC UA server's connectivity AT THE INSTANCE LEVEL via the component's
        // main state keepalive's instances[] (the #1c surface): a server that has not (re)connected reads
        // disconnected. Identity/data/lifecycle stay under `main`; this is the per-server connectivity view.
        edgeCommons.setInstanceConnectivityProvider(() -> {
            Map<String, OpcUaDevice> byId = new HashMap<>();
            synchronized (devices) {
                for (OpcUaDevice d : devices) {
                    byId.put(d.getId(), d);
                }
            }
            List<InstanceConnectivity> out = new ArrayList<>();
            for (String id : config.getInstanceIds()) {
                OpcUaDevice d = byId.get(id);
                out.add(InstanceConnectivity.of(id, d != null && d.isConnected(),
                        d != null ? d.getEndpoint() : null));
            }
            return out;
        });

        JsonObject globalConfig = config.getGlobalConfig();
        long staleSignalSecs = staleSignalSecs(globalConfig);
        for (String instanceId : config.getInstanceIds()) {
            // Each device connection runs on its own thread: connect() blocks/retries until up.
            Thread worker = new Thread(() -> {
                try {
                    ServerConfiguration serverConfig = new ServerConfiguration(config, globalConfig, instanceId);
                    // The per-message UNS instance token: mints this device's data/evt topics and stamps
                    // its identity. Uses the resolved instance id (= published device.instance).
                    OpcUaDevice device = new OpcUaDevice(edgeCommons.instance(serverConfig.getId()),
                            config, metrics, credentials, serverConfig, staleSignalSecs);
                    synchronized (devices) {
                        devices.add(device);
                    }
                    commandRegistry.addDevice(device);   // now routable by the sb/* verbs
                    edgeCommons.setReady(true);            // ready once at least one device is connected + subscribed
                } catch (OpcUaConnection.UnretryableConnectionException e) {
                    LOGGER.error("[{}] {}", instanceId, e.getMessage());
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

    /**
     * Read {@code component.global.healthThresholds.staleSignalSecs} (SOUTHBOUND.md §5), defaulting to
     * {@link HealthState#DEFAULT_STALE_SIGNAL_SECS} when unset or malformed.
     */
    static long staleSignalSecs(JsonObject globalConfig) {
        try {
            if (globalConfig != null && globalConfig.has("healthThresholds")
                    && globalConfig.get("healthThresholds").isJsonObject()) {
                JsonObject h = globalConfig.getAsJsonObject("healthThresholds");
                if (h.has("staleSignalSecs") && h.get("staleSignalSecs").isJsonPrimitive()) {
                    return h.get("staleSignalSecs").getAsLong();
                }
            }
        } catch (RuntimeException e) {
            LOGGER.debug("staleSignalSecs lookup failed, defaulting: {}", e.toString());
        }
        return HealthState.DEFAULT_STALE_SIGNAL_SECS;
    }
}
