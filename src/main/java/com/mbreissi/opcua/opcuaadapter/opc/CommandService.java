package com.mbreissi.opcua.opcuaadapter.opc;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mbreissi.ggcommons.config.ConfigManager;
import com.mbreissi.ggcommons.messaging.Message;
import com.mbreissi.ggcommons.messaging.MessageBuilder;
import com.mbreissi.ggcommons.messaging.MessagingClient;
import com.mbreissi.opcua.opcuaadapter.opc.config.ServerConfiguration;
import com.mbreissi.opcua.opcuaadapter.opc.config.TagSpec;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.milo.opcua.sdk.client.OpcUaClient;
import org.eclipse.milo.opcua.sdk.client.nodes.UaNode;
import org.eclipse.milo.opcua.sdk.client.nodes.UaVariableNode;
import org.eclipse.milo.opcua.stack.core.types.builtin.DataValue;
import org.eclipse.milo.opcua.stack.core.types.builtin.NodeId;
import org.eclipse.milo.opcua.stack.core.types.builtin.StatusCode;
import org.eclipse.milo.opcua.stack.core.types.builtin.Variant;
import org.eclipse.milo.opcua.stack.core.types.enumerated.TimestampsToReturn;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * The device's command surface over messaging: batch write ({@code writeValues}), on-demand batch
 * read (request/reply via {@code readValuesAsync}), and status/subscriptions control queries.
 */
public class CommandService {

    private static final Logger LOGGER = LogManager.getLogger(CommandService.class);

    private final OpcUaClient client;
    private final MessagingClient messaging;
    private final ConfigManager config;
    private final ServerConfiguration serverConfig;
    private final ClientMetrics counters;
    private final BooleanSupplier connected;
    private final Supplier<Map<String, TagSpec>> resolvedTags;

    public CommandService(OpcUaClient client, MessagingClient messaging, ConfigManager config,
                          ServerConfiguration serverConfig, ClientMetrics counters,
                          BooleanSupplier connected, Supplier<Map<String, TagSpec>> resolvedTags) {
        this.client = client;
        this.messaging = messaging;
        this.config = config;
        this.serverConfig = serverConfig;
        this.counters = counters;
        this.connected = connected;
        this.resolvedTags = resolvedTags;
    }

    public void subscribe() {
        if (serverConfig.isWriteEnabled()) {
            messaging.subscribe(serverConfig.getWriteTopic(), (topic, message) -> handleWrite(message));
            LOGGER.info("[{}] write enabled on {}", serverConfig.getId(), serverConfig.getWriteTopic());
        }
        messaging.subscribe(serverConfig.getReadTopic(), (topic, message) -> handleRead(message));
        messaging.subscribe(serverConfig.getControlTopic(), this::handleControl);
    }

    /** Batch write: body {@code {"writes":[{ns,tagId,value,status?,sourceTs?}, ...]}} (single object also accepted). */
    private void handleWrite(Message message) {
        try {
            JsonObject payload = asJsonObject(message);
            if (payload == null) {
                return;
            }
            JsonArray writes;
            if (payload.has("writes")) {
                writes = payload.getAsJsonArray("writes");
            } else {
                writes = new JsonArray();
                writes.add(payload);
            }
            List<NodeId> nodeIds = new ArrayList<>();
            List<DataValue> dataValues = new ArrayList<>();
            for (JsonElement el : writes) {
                JsonObject w = el.getAsJsonObject();
                if (!w.has("ns") || !w.has("tagId") || !w.has("value")) {
                    LOGGER.error("[{}] write entry missing ns/tagId/value; skipping: {}", serverConfig.getId(), w);
                    continue;
                }
                NodeId nodeId = new NodeId(w.get("ns").getAsInt(), w.get("tagId").getAsString());
                UaNode node = client.getAddressSpace().getNode(nodeId);
                if (!(node instanceof UaVariableNode)) {
                    LOGGER.error("[{}] write target {} is not a variable; skipping", serverConfig.getId(), nodeId);
                    continue;
                }
                Variant variant = ValueCodec.variantFromValue(((UaVariableNode) node).getDataType(), w.get("value"));
                if (variant == null) {
                    continue;
                }
                StatusCode status = ValueCodec.statusFromString(w.has("status") ? w.get("status").getAsString() : "GOOD");
                DataValue dv = new DataValue(variant, status, ValueCodec.sourceTime(w.has("sourceTs") ? w.get("sourceTs").getAsString() : null));
                nodeIds.add(nodeId);
                dataValues.add(dv);
            }
            if (nodeIds.isEmpty()) {
                return;
            }
            List<StatusCode> results = client.writeValues(nodeIds, dataValues);
            for (int i = 0; i < results.size(); i++) {
                counters.incrementWriteMetrics();
                if (results.get(i).isBad()) {
                    LOGGER.warn("[{}] write to {} returned {}", serverConfig.getId(), nodeIds.get(i), results.get(i));
                }
            }
            LOGGER.debug("[{}] batch write of {} tag(s) complete", serverConfig.getId(), nodeIds.size());
        } catch (Exception e) {
            LOGGER.error("[{}] write request failed: {}", serverConfig.getId(), e.toString());
        }
    }

    /**
     * On-demand batch read (request/reply): request body {@code {"tags":[{ns,tagId}, ...]}};
     * reply body {@code {"id":..., "reads":[{tag:{id,address}, value, quality, qualityRaw, sourceTs, serverTs}]}}.
     */
    private void handleRead(Message request) {
        try {
            JsonObject payload = asJsonObject(request);
            JsonArray tags = (payload != null && payload.has("tags")) ? payload.getAsJsonArray("tags") : new JsonArray();
            List<NodeId> nodeIds = new ArrayList<>();
            for (JsonElement el : tags) {
                JsonObject t = el.getAsJsonObject();
                nodeIds.add(new NodeId(t.get("ns").getAsInt(), t.get("tagId").getAsString()));
            }
            List<DataValue> values = nodeIds.isEmpty()
                    ? new ArrayList<>()
                    : client.readValuesAsync(0.0, TimestampsToReturn.Both, nodeIds).get();

            JsonArray reads = new JsonArray();
            for (int i = 0; i < nodeIds.size(); i++) {
                NodeId nodeId = nodeIds.get(i);
                JsonObject tag = new JsonObject();
                tag.addProperty("id", nodeId.toParseableString());
                tag.add("address", ValueCodec.address(nodeId));
                JsonObject read = ValueCodec.toSample(values.get(i));
                read.add("tag", tag);
                reads.add(read);
                counters.incrementReadMetrics();
            }
            JsonObject responsePayload = new JsonObject();
            responsePayload.addProperty("id", serverConfig.getId());
            responsePayload.add("reads", reads);
            reply(request, "SouthboundReadResult", responsePayload);
        } catch (Exception e) {
            LOGGER.error("[{}] read request failed: {}", serverConfig.getId(), e.toString());
        }
    }

    private void handleControl(String topic, Message request) {
        if (topic.endsWith("status")) {
            JsonObject responsePayload = new JsonObject();
            responsePayload.addProperty("id", serverConfig.getId());
            responsePayload.addProperty("connected", connected.getAsBoolean());
            responsePayload.add("metrics", counters.toJsonObject());
            reply(request, "status", responsePayload);
        } else if (topic.endsWith("subscriptions")) {
            JsonObject responsePayload = new JsonObject();
            responsePayload.addProperty("id", serverConfig.getId());
            JsonArray tags = new JsonArray();
            resolvedTags.get().forEach((tagId, spec) -> {
                JsonObject t = new JsonObject();
                t.addProperty("tagId", tagId);
                t.addProperty("namespace", spec.getNamespace());
                t.addProperty("match", spec.getMatch());
                tags.add(t);
            });
            responsePayload.add("tags", tags);
            reply(request, "subscriptions", responsePayload);
        }
    }

    private void reply(Message request, String name, JsonObject payload) {
        Message response = MessageBuilder.create(name, "1.0")
                .withCorrelationId(request.getCorrelationId())
                .withPayload(payload)
                .withConfig(config)
                .build();
        messaging.reply(request, response);
    }

    private static JsonObject asJsonObject(Message message) {
        Object body = message.getBody();
        if (body instanceof JsonObject) {
            return (JsonObject) body;
        }
        Object raw = message.getRaw();
        return raw instanceof JsonObject ? (JsonObject) raw : null;
    }
}
