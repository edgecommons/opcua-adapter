package com.mbreissi.opcua.opcuaadapter.opc;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mbreissi.ggcommons.config.ConfigManager;
import com.mbreissi.ggcommons.messaging.Message;
import com.mbreissi.ggcommons.messaging.MessageBuilder;
import com.mbreissi.ggcommons.messaging.MessagingClient;
import com.mbreissi.opcua.opcuaadapter.opc.config.ServerConfiguration;
import com.mbreissi.opcua.opcuaadapter.opc.config.TagSpec;
import org.eclipse.milo.opcua.sdk.client.nodes.UaVariableNode;
import org.eclipse.milo.opcua.stack.core.NamespaceTable;
import org.eclipse.milo.opcua.stack.core.types.builtin.DataValue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Builds and publishes the Tier-1 {@code SouthboundTagUpdate} envelope (docs/SOUTHBOUND.md §2) for
 * tag value changes. When {@code batchMs > 0}, value changes are buffered per node and flushed
 * together by {@link #flush()} (driven by the device's timer); otherwise each change publishes
 * immediately.
 */
public class TagUpdatePublisher {

    private final MessagingClient messaging;
    private final ConfigManager config;
    private final ServerConfiguration serverConfig;
    private final NamespaceTable namespaceTable;
    private final Map<UaVariableNode, Buffer> pending = new ConcurrentHashMap<>();

    public TagUpdatePublisher(MessagingClient messaging, ConfigManager config, ServerConfiguration serverConfig,
                              NamespaceTable namespaceTable) {
        this.messaging = messaging;
        this.config = config;
        this.serverConfig = serverConfig;
        this.namespaceTable = namespaceTable;
    }

    /** Offer a value change for a tag — buffered (batch) or published immediately. */
    public void offer(UaVariableNode node, TagSpec spec, DataValue value) {
        if (serverConfig.getBatchMs() > 0) {
            pending.computeIfAbsent(node, n -> new Buffer(spec)).queue.add(value);
        } else {
            publish(node, spec, List.of(value));
        }
    }

    /** Flush all buffered values (one message per node). Called on the batch timer. */
    public void flush() {
        pending.forEach((node, buffer) -> {
            List<DataValue> values = new ArrayList<>();
            buffer.queue.drainTo(values);
            if (!values.isEmpty()) {
                publish(node, buffer.spec, values);
            }
        });
    }

    private void publish(UaVariableNode node, TagSpec spec, List<DataValue> values) {
        if (values.isEmpty()) {
            return;
        }
        String tagId = node.getNodeId().getIdentifier().toString();
        String browseName = node.getBrowseName() != null && node.getBrowseName().getName() != null
                ? node.getBrowseName().getName() : "";
        String displayName = node.getDisplayName() != null && node.getDisplayName().getText() != null
                ? node.getDisplayName().getText() : "";

        JsonObject device = new JsonObject();
        device.addProperty("adapter", "opcua");
        device.addProperty("instance", serverConfig.getId());
        device.addProperty("endpoint", serverConfig.getConnection().getEndpoint());

        JsonObject tag = new JsonObject();
        tag.addProperty("id", node.getNodeId().toParseableString());
        tag.addProperty("name", !displayName.isEmpty() ? displayName : browseName);
        tag.add("address", ValueCodec.address(node.getNodeId(), namespaceTable));

        JsonArray samples = new JsonArray();
        for (DataValue value : values) {
            samples.add(ValueCodec.toSample(value));
        }

        JsonObject body = new JsonObject();
        body.add("device", device);
        body.add("tag", tag);
        body.add("samples", samples);

        Message message = MessageBuilder.create("SouthboundTagUpdate", "1.0")
                .withPayload(body)
                .withConfig(config)
                .build();
        String topic = serverConfig.resolvePublishTopic(spec != null ? spec.getTopic() : null, tagId);
        messaging.publish(topic, message);
    }

    private static final class Buffer {
        final TagSpec spec;
        final LinkedBlockingQueue<DataValue> queue = new LinkedBlockingQueue<>();

        Buffer(TagSpec spec) {
            this.spec = spec;
        }
    }
}
