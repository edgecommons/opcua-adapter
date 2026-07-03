package com.mbreissi.opcua.opcuaadapter.opc;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mbreissi.ggcommons.GgInstance;
import com.mbreissi.ggcommons.config.ConfigManager;
import com.mbreissi.ggcommons.messaging.Message;
import com.mbreissi.ggcommons.messaging.MessagingClient;
import com.mbreissi.ggcommons.uns.UnsClass;
import com.mbreissi.ggcommons.uns.UnsValidationException;
import com.mbreissi.opcua.opcuaadapter.opc.config.ServerConfiguration;
import com.mbreissi.opcua.opcuaadapter.opc.config.SignalSpec;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.milo.opcua.sdk.client.nodes.UaVariableNode;
import org.eclipse.milo.opcua.stack.core.NamespaceTable;
import org.eclipse.milo.opcua.stack.core.types.builtin.DataValue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Builds and publishes the Tier-1 {@code SouthboundSignalUpdate} envelope (docs/SOUTHBOUND.md §2) for
 * signal value changes onto the UNS <b>{@code data}</b> class. When {@code batchMs > 0}, value changes
 * are buffered per node and flushed together by {@link #flush()} (driven by the device's timer);
 * otherwise each change publishes immediately.
 *
 * <p><b>Addressing (UNS §2.0).</b> Each update is published to
 * {@code ecv1/{device}/{component}/{instance}/data/{signalPath}}, minted by the per-instance topic
 * builder ({@code gg.instance(id).uns()}) — never a hand-assembled string. {@code signalPath} is the
 * signal's OPC UA bare identifier sanitized to a single UNS channel token
 * ({@link ConfigManager#sanitize}: {@code / \ + #} and control chars → {@code _}, and {@code ..}
 * collapsed), which is depth-safe by construction (exactly one channel token). The <b>stable</b>
 * {@code signal.id} in the body (the {@code ns=…;…=…} parseable form) remains what consumers key on;
 * the sanitized path is only the routing address. The top-level {@code identity} element is stamped
 * automatically by {@code instance.newMessage(...)} — the site hierarchy rides there, not in the topic.
 */
public class SignalUpdatePublisher {

    private static final Logger LOGGER = LogManager.getLogger(SignalUpdatePublisher.class);

    private final MessagingClient messaging;
    private final GgInstance instance;
    private final ServerConfiguration serverConfig;
    private final NamespaceTable namespaceTable;
    private final Map<UaVariableNode, Buffer> pending = new ConcurrentHashMap<>();

    public SignalUpdatePublisher(MessagingClient messaging, GgInstance instance,
                                 ServerConfiguration serverConfig, NamespaceTable namespaceTable) {
        this.messaging = messaging;
        this.instance = instance;
        this.serverConfig = serverConfig;
        this.namespaceTable = namespaceTable;
    }

    /** Offer a value change for a signal — buffered (batch) or published immediately. */
    public void offer(UaVariableNode node, SignalSpec spec, DataValue value) {
        if (serverConfig.getBatchMs() > 0) {
            pending.computeIfAbsent(node, n -> new Buffer(spec)).queue.add(value);
        } else {
            publish(node, List.of(value));
        }
    }

    /** Flush all buffered values (one message per node). Called on the batch timer. */
    public void flush() {
        pending.forEach((node, buffer) -> {
            List<DataValue> values = new ArrayList<>();
            buffer.queue.drainTo(values);
            if (!values.isEmpty()) {
                publish(node, values);
            }
        });
    }

    private void publish(UaVariableNode node, List<DataValue> values) {
        if (values.isEmpty()) {
            return;
        }
        String browseName = node.getBrowseName() != null && node.getBrowseName().getName() != null
                ? node.getBrowseName().getName() : "";
        String displayName = node.getDisplayName() != null && node.getDisplayName().getText() != null
                ? node.getDisplayName().getText() : "";

        JsonObject device = new JsonObject();
        device.addProperty("adapter", "opcua");
        device.addProperty("instance", serverConfig.getId());
        device.addProperty("endpoint", serverConfig.getConnection().getEndpoint());

        JsonObject signal = new JsonObject();
        signal.addProperty("id", node.getNodeId().toParseableString());
        signal.addProperty("name", !displayName.isEmpty() ? displayName : browseName);
        signal.add("address", ValueCodec.address(node.getNodeId(), namespaceTable));

        JsonArray samples = new JsonArray();
        for (DataValue value : values) {
            samples.add(ValueCodec.toSample(value));
        }

        JsonObject body = new JsonObject();
        body.add("device", device);
        body.add("signal", signal);
        body.add("samples", samples);

        // Sanitize the OPC UA bare identifier into a single UNS channel token: it can legally contain
        // '/', long/GUID forms, etc. that the UNS token rule + IoT-Core depth guard would otherwise
        // reject at build time. Collapsing to one token keeps the topic depth-safe by construction.
        String signalPath = ConfigManager.sanitize(node.getNodeId().getIdentifier().toString());
        String topic;
        try {
            topic = instance.uns().topic(UnsClass.DATA, signalPath);
        } catch (UnsValidationException e) {
            // e.g. an identifier so long the total topic exceeds the IoT-Core 256-byte limit. Drop the
            // message rather than crash the flush loop; the stable signal.id still names it in a log.
            LOGGER.warn("[{}] dropping update for '{}': cannot mint a valid UNS data topic ({})",
                    serverConfig.getId(), node.getNodeId().toParseableString(), e.getMessage());
            return;
        }
        Message message = instance.newMessage("SouthboundSignalUpdate", "1.0")
                .withPayload(body)
                .build();
        messaging.publish(topic, message);
    }

    private static final class Buffer {
        final SignalSpec spec;
        final LinkedBlockingQueue<DataValue> queue = new LinkedBlockingQueue<>();

        Buffer(SignalSpec spec) {
            this.spec = spec;
        }
    }
}
