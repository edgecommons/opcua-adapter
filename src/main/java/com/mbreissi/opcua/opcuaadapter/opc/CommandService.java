package com.mbreissi.opcua.opcuaadapter.opc;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mbreissi.ggcommons.config.ConfigManager;
import com.mbreissi.ggcommons.messaging.Message;
import com.mbreissi.ggcommons.messaging.MessageBuilder;
import com.mbreissi.ggcommons.messaging.MessagingClient;
import com.mbreissi.opcua.opcuaadapter.opc.config.ServerConfiguration;
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
    private final Supplier<Map<String, ResolvedSignal>> resolvedSignals;

    public CommandService(OpcUaClient client, MessagingClient messaging, ConfigManager config,
                          ServerConfiguration serverConfig, ClientMetrics counters,
                          BooleanSupplier connected, Supplier<Map<String, ResolvedSignal>> resolvedSignals) {
        this.client = client;
        this.messaging = messaging;
        this.config = config;
        this.serverConfig = serverConfig;
        this.counters = counters;
        this.connected = connected;
        this.resolvedSignals = resolvedSignals;
    }

    public void subscribe() {
        if (serverConfig.isWriteEnabled()) {
            messaging.subscribe(serverConfig.getWriteTopic(), (topic, message) -> handleWrite(message));
            LOGGER.info("[{}] write enabled on {}", serverConfig.getId(), serverConfig.getWriteTopic());
        }
        messaging.subscribe(serverConfig.getReadTopic(), (topic, message) -> handleRead(message));
        messaging.subscribe(serverConfig.getControlTopic(), this::handleControl);
    }

    /** Batch write: body {@code {"writes":[{ns,signalId,value,status?,sourceTs?}, ...]}} (single object also accepted). */
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
                if (!w.has("value")) {
                    LOGGER.error("[{}] write entry missing value; skipping: {}", serverConfig.getId(), w);
                    continue;
                }
                NodeId nodeId;
                try {
                    nodeId = nodeIdFrom(w);
                } catch (Exception e) {
                    LOGGER.error("[{}] write entry skipped: {}", serverConfig.getId(), e.getMessage());
                    continue;
                }
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
            LOGGER.debug("[{}] batch write of {} signal(s) complete", serverConfig.getId(), nodeIds.size());
        } catch (Exception e) {
            LOGGER.error("[{}] write request failed: {}", serverConfig.getId(), e.toString());
        }
    }

    /**
     * On-demand batch read (request/reply): request body
     * {@code {"signals":[{namespaceUri|ns, signalId}, ...]}}; reply body
     * {@code {"id":..., "reads":[{signal:{id,address}, value, quality, qualityRaw, sourceTs, serverTs}]}}.
     * Signals that cannot be resolved are omitted from {@code reads}; match results by {@code signal}, not position.
     */
    private void handleRead(Message request) {
        try {
            JsonObject payload = asJsonObject(request);
            JsonArray signals = (payload != null && payload.has("signals")) ? payload.getAsJsonArray("signals") : new JsonArray();
            List<NodeId> nodeIds = new ArrayList<>();
            for (JsonElement el : signals) {
                try {
                    nodeIds.add(nodeIdFrom(el.getAsJsonObject()));
                } catch (Exception e) {
                    LOGGER.warn("[{}] read signal skipped: {}", serverConfig.getId(), e.getMessage());
                }
            }
            List<DataValue> values = nodeIds.isEmpty()
                    ? new ArrayList<>()
                    : client.readValuesAsync(0.0, TimestampsToReturn.Both, nodeIds).get();

            JsonArray reads = new JsonArray();
            for (int i = 0; i < nodeIds.size(); i++) {
                NodeId nodeId = nodeIds.get(i);
                JsonObject signal = new JsonObject();
                signal.addProperty("id", nodeId.toParseableString());
                signal.add("address", ValueCodec.address(nodeId, client.getNamespaceTable()));
                JsonObject read = ValueCodec.toSample(values.get(i));
                read.add("signal", signal);
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
            JsonArray signals = new JsonArray();
            resolvedSignals.get().forEach((signalId, rt) -> {
                JsonObject t = new JsonObject();
                t.addProperty("signalId", signalId);
                int idx = rt.nodeId().getNamespaceIndex().intValue();
                t.addProperty("namespace", idx);
                String uri = client.getNamespaceTable().get(idx);
                if (uri != null) {
                    t.addProperty("namespaceUri", uri);
                }
                t.addProperty("match", rt.spec().getMatch());
                signals.add(t);
            });
            responsePayload.add("signals", signals);
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

    /**
     * Build a {@link NodeId} from a read/write request entry. The namespace is identified by
     * {@code namespaceUri} (preferred — resolved to the server's current index) or a literal
     * {@code ns} index; {@code signalId} is the node identifier. Throws if the entry is incomplete or the
     * URI is not in the server's namespace table.
     */
    private NodeId nodeIdFrom(JsonObject o) {
        if (!o.has("signalId")) {
            throw new IllegalArgumentException("entry missing 'signalId': " + o);
        }
        int ns;
        if (o.has("namespaceUri")) {
            String uri = o.get("namespaceUri").getAsString();
            var idx = client.getNamespaceTable().getIndex(uri);
            if (idx == null) {
                throw new IllegalArgumentException("namespaceUri '" + uri + "' not in the server's namespace table");
            }
            ns = idx.intValue();
        } else if (o.has("ns")) {
            ns = o.get("ns").getAsInt();
        } else {
            throw new IllegalArgumentException("entry needs 'namespaceUri' or 'ns': " + o);
        }
        return new NodeId(ns, o.get("signalId").getAsString());
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
