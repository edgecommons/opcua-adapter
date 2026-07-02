package com.mbreissi.opcua.opcuaadapter.opc;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mbreissi.ggcommons.config.ConfigManager;
import com.mbreissi.ggcommons.messaging.Message;
import com.mbreissi.ggcommons.messaging.MessageBuilder;
import com.mbreissi.ggcommons.messaging.MessagingClient;
import com.mbreissi.opcua.opcuaadapter.opc.config.ServerConfiguration;
import com.mbreissi.opcua.opcuaadapter.opc.config.SignalSpec;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.milo.opcua.sdk.client.OpcUaClient;
import org.eclipse.milo.opcua.sdk.client.nodes.UaNode;
import org.eclipse.milo.opcua.sdk.client.nodes.UaVariableNode;
import org.eclipse.milo.opcua.stack.core.NamespaceTable;
import org.eclipse.milo.opcua.stack.core.OpcUaDataType;
import org.eclipse.milo.opcua.stack.core.types.builtin.DataValue;
import org.eclipse.milo.opcua.stack.core.types.builtin.NodeId;
import org.eclipse.milo.opcua.stack.core.types.builtin.StatusCode;
import org.eclipse.milo.opcua.stack.core.types.builtin.Variant;
import org.eclipse.milo.opcua.stack.core.types.enumerated.TimestampsToReturn;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * The device's command surface over messaging: batch write ({@code writeValues}, with a per-entry
 * {@code SouthboundWriteResult} ack when the request carries {@code reply_to}), on-demand batch
 * read (request/reply via {@code readValuesAsync}, by explicit signal list and/or regex
 * include/exclude), and status/subscriptions/nodes management queries.
 */
public class CommandService {

    private static final Logger LOGGER = LogManager.getLogger(CommandService.class);

    /** Default cap on nodes returned by one {@code control/nodes} reply (keeps it well under a 1 MB broker/IPC limit). */
    private static final int DEFAULT_NODES_LIMIT = 2000;

    private final OpcUaClient client;
    private final MessagingClient messaging;
    private final ConfigManager config;
    private final ServerConfiguration serverConfig;
    private final ClientMetrics counters;
    private final BooleanSupplier connected;
    private final Supplier<Map<String, ResolvedSignal>> resolvedSignals;
    private final Map<NodeId, UaVariableNode> allNodes;

    public CommandService(OpcUaClient client, MessagingClient messaging, ConfigManager config,
                          ServerConfiguration serverConfig, ClientMetrics counters,
                          BooleanSupplier connected, Supplier<Map<String, ResolvedSignal>> resolvedSignals,
                          Map<NodeId, UaVariableNode> allNodes) {
        this.client = client;
        this.messaging = messaging;
        this.config = config;
        this.serverConfig = serverConfig;
        this.counters = counters;
        this.connected = connected;
        this.resolvedSignals = resolvedSignals;
        this.allNodes = allNodes;
    }

    public void subscribe() {
        if (serverConfig.isWriteEnabled()) {
            messaging.subscribe(serverConfig.getWriteTopic(), (topic, message) -> handleWrite(message));
            LOGGER.info("[{}] write enabled on {}", serverConfig.getId(), serverConfig.getWriteTopic());
        }
        messaging.subscribe(serverConfig.getReadTopic(), (topic, message) -> handleRead(message));
        messaging.subscribe(serverConfig.getControlTopic(), this::handleControl);
    }

    /**
     * Batch write: body {@code {"writes":[{ns|namespaceUri,signalId,value,status?,sourceTs?}, ...]}}
     * (single object also accepted). Always performs the write; when the request carries
     * {@code reply_to}, also replies with a {@code SouthboundWriteResult} — one result per request
     * entry, in order, each {@code SUCCESS}/{@code FAILED} (+ {@code message} on failure).
     */
    private void handleWrite(Message request) {
        try {
            JsonObject payload = asJsonObject(request);
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

            List<JsonObject> perEntry = new ArrayList<>();
            List<NodeId> nodeIds = new ArrayList<>();
            List<DataValue> dataValues = new ArrayList<>();
            List<Integer> batchToEntry = new ArrayList<>();

            for (JsonElement el : writes) {
                JsonObject w = el.getAsJsonObject();
                String signalId = w.has("signalId") ? w.get("signalId").getAsString() : null;
                Integer ns = w.has("ns") ? w.get("ns").getAsInt() : null;
                String namespaceUri = w.has("namespaceUri") ? w.get("namespaceUri").getAsString() : null;
                JsonObject coords = CommandJson.writeRequested(signalId, ns, namespaceUri);
                perEntry.add(coords);

                if (!w.has("value")) {
                    LOGGER.error("[{}] write entry missing value; skipping: {}", serverConfig.getId(), w);
                    CommandJson.applyWriteFailed(coords, "missing value");
                    continue;
                }
                NodeId nodeId;
                try {
                    nodeId = nodeIdFrom(w);
                } catch (Exception e) {
                    LOGGER.error("[{}] write entry skipped: {}", serverConfig.getId(), e.getMessage());
                    CommandJson.applyWriteFailed(coords, e.getMessage());
                    continue;
                }
                UaNode node = client.getAddressSpace().getNode(nodeId);
                if (!(node instanceof UaVariableNode)) {
                    LOGGER.error("[{}] write target {} is not a variable; skipping", serverConfig.getId(), nodeId);
                    CommandJson.applyWriteFailed(coords, "target is not a variable");
                    continue;
                }
                Variant variant = ValueCodec.variantFromValue(((UaVariableNode) node).getDataType(), w.get("value"));
                if (variant == null) {
                    CommandJson.applyWriteFailed(coords, "unsupported value type or coercion failed");
                    continue;
                }
                StatusCode status = ValueCodec.statusFromString(w.has("status") ? w.get("status").getAsString() : "GOOD");
                DataValue dv = new DataValue(variant, status, ValueCodec.sourceTime(w.has("sourceTs") ? w.get("sourceTs").getAsString() : null));
                nodeIds.add(nodeId);
                dataValues.add(dv);
                batchToEntry.add(perEntry.size() - 1);
            }

            if (!nodeIds.isEmpty()) {
                try {
                    List<StatusCode> results = client.writeValues(nodeIds, dataValues);
                    for (int j = 0; j < results.size(); j++) {
                        counters.incrementWriteMetrics();
                        StatusCode result = results.get(j);
                        if (result.isBad()) {
                            LOGGER.warn("[{}] write to {} returned {}", serverConfig.getId(), nodeIds.get(j), result);
                        }
                    }
                    CommandJson.applyBatchResults(perEntry, batchToEntry, results);
                    LOGGER.debug("[{}] batch write of {} signal(s) complete", serverConfig.getId(), nodeIds.size());
                } catch (Exception e) {
                    // The write service call itself failed (e.g. session down): mark every batched
                    // entry FAILED so the reply below still carries a result for each one.
                    LOGGER.error("[{}] batch write failed: {}", serverConfig.getId(), e.toString());
                    String message = "write failed: " + (e.getMessage() != null ? e.getMessage() : e.toString());
                    for (int entryIdx : batchToEntry) {
                        CommandJson.applyWriteFailed(perEntry.get(entryIdx), message);
                    }
                }
            }

            if (request.getHeader() != null && request.getHeader().getReplyTo() != null) {
                JsonArray writesResult = new JsonArray();
                perEntry.forEach(writesResult::add);
                JsonObject responsePayload = new JsonObject();
                responsePayload.addProperty("id", serverConfig.getId());
                responsePayload.add("writes", writesResult);
                reply(request, "SouthboundWriteResult", responsePayload);
            }
        } catch (Exception e) {
            LOGGER.error("[{}] write request failed: {}", serverConfig.getId(), e.toString());
        }
    }

    /**
     * On-demand batch read (request/reply): request body
     * {@code {"signals":[{namespaceUri|ns, signalId}, ...], "include":[{signal matcher}, ...],
     * "exclude":[{signal matcher}, ...]}}; reply body
     * {@code {"id":..., "reads":[{signal:{id,address}, value, quality, qualityRaw, sourceTs, serverTs}]}}.
     * {@code signals} names exact signals (read as given — order and duplicates preserved);
     * {@code include} (with optional {@code exclude}) selects signals by the same namespace + regex
     * matching used for subscriptions (include tests identifier/browseName/displayName, exclude tests
     * the identifier only). Include-matched signals are appended after the explicit list and
     * deduplicated against it. Signals that cannot be resolved are omitted from {@code reads}; match
     * results by {@code signal}, not position. A malformed {@code include}/{@code exclude} matcher
     * yields a reply with an {@code error} field rather than no reply at all.
     */
    private void handleRead(Message request) {
        try {
            JsonObject payload = asJsonObject(request);
            JsonArray signals = (payload != null && payload.has("signals")) ? payload.getAsJsonArray("signals") : new JsonArray();

            List<NodeId> explicit = new ArrayList<>();
            for (JsonElement el : signals) {
                try {
                    explicit.add(nodeIdFrom(el.getAsJsonObject()));
                } catch (Exception e) {
                    LOGGER.warn("[{}] read signal skipped: {}", serverConfig.getId(), e.getMessage());
                }
            }

            List<NodeId> includeMatched = new ArrayList<>();
            if (payload != null && payload.has("include")) {
                List<SignalSpec> includeSpecs;
                List<SignalSpec> excludeSpecs;
                try {
                    includeSpecs = specsFromJson(payload.getAsJsonArray("include"));
                    excludeSpecs = payload.has("exclude")
                            ? specsFromJson(payload.getAsJsonArray("exclude"))
                            : List.of();
                    // Compile the caller-supplied regexes up front so a malformed pattern (or a
                    // non-object matcher entry) turns into an error reply instead of escaping to the
                    // outer catch and leaving the request/reply caller hanging.
                    includeSpecs.forEach(SignalSpec::pattern);
                    excludeSpecs.forEach(SignalSpec::pattern);
                } catch (Exception e) {
                    LOGGER.warn("[{}] read include/exclude rejected: {}", serverConfig.getId(), e.toString());
                    replyReadError(request, "invalid include/exclude: " + e.getMessage());
                    return;
                }
                NamespaceTable table = client.getNamespaceTable();
                Map<SignalSpec, Integer> includeNs = new HashMap<>();
                includeSpecs.forEach(spec -> includeNs.put(spec, SignalMatching.resolveNamespace(spec, table)));
                Map<SignalSpec, Integer> excludeNs = new HashMap<>();
                excludeSpecs.forEach(spec -> excludeNs.put(spec, SignalMatching.resolveNamespace(spec, table)));

                for (UaVariableNode node : allNodes.values()) {
                    String idStr = node.getNodeId().getIdentifier().toString();
                    String browseName = node.getBrowseName() != null && node.getBrowseName().getName() != null
                            ? node.getBrowseName().getName() : "";
                    String displayName = node.getDisplayName() != null && node.getDisplayName().getText() != null
                            ? node.getDisplayName().getText() : "";
                    int nodeNs = node.getNodeId().getNamespaceIndex().intValue();

                    SignalSpec included = SignalMatching.firstMatch(includeSpecs, includeNs, idStr, browseName, displayName, nodeNs, true);
                    if (included == null) {
                        continue;
                    }
                    if (!excludeSpecs.isEmpty()
                            && SignalMatching.firstMatch(excludeSpecs, excludeNs, idStr, browseName, displayName, nodeNs, false) != null) {
                        continue;
                    }
                    includeMatched.add(node.getNodeId());
                }
            }

            List<NodeId> nodeIds = mergeReadTargets(explicit, includeMatched);
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

    private static List<SignalSpec> specsFromJson(JsonArray array) {
        List<SignalSpec> specs = new ArrayList<>();
        for (JsonElement el : array) {
            specs.add(SignalSpec.fromJson(el.getAsJsonObject()));
        }
        return specs;
    }

    /**
     * The ordered read target list: the {@code explicit} signals[] as given (order and duplicates
     * preserved — an explicit-only request reads exactly what it asked for, once per entry), followed
     * by {@code include}-matched node ids that are not already present (deduplicated against the
     * explicit entries and against each other).
     */
    static List<NodeId> mergeReadTargets(List<NodeId> explicit, List<NodeId> includeMatched) {
        List<NodeId> result = new ArrayList<>(explicit);
        Set<NodeId> seen = new HashSet<>(explicit);
        for (NodeId id : includeMatched) {
            if (seen.add(id)) {
                result.add(id);
            }
        }
        return result;
    }

    private void replyReadError(Message request, String error) {
        JsonObject responsePayload = new JsonObject();
        responsePayload.addProperty("id", serverConfig.getId());
        responsePayload.addProperty("error", error);
        responsePayload.add("reads", new JsonArray());
        reply(request, "SouthboundReadResult", responsePayload);
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
                t.addProperty("idType", ValueCodec.idTypeName(rt.nodeId()));
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
        } else if (topic.endsWith("nodes")) {
            // The address space can hold tens of thousands of nodes; a single unpaged reply would blow
            // past the broker/IPC max message size (e.g. EMQX's 1 MB default) and be silently dropped.
            // Page over a stable snapshot: optional body {offset, limit}, with {total, truncated} back.
            JsonObject reqBody = asJsonObject(request);
            int total = allNodes.size();
            int offset = reqBody != null && reqBody.has("offset") ? Math.max(0, reqBody.get("offset").getAsInt()) : 0;
            int limit = reqBody != null && reqBody.has("limit") ? Math.max(0, reqBody.get("limit").getAsInt()) : DEFAULT_NODES_LIMIT;

            JsonArray nodes = new JsonArray();
            boolean truncated = false;
            int index = -1;
            int emitted = 0;
            for (UaVariableNode node : allNodes.values()) {
                index++;
                if (index < offset) {
                    continue;
                }
                if (emitted >= limit) {
                    truncated = true;
                    break;
                }
                try {
                    NodeId id = node.getNodeId();
                    int ns = id.getNamespaceIndex().intValue();
                    String uri = client.getNamespaceTable().get(ns);
                    String browseName = node.getBrowseName() != null && node.getBrowseName().getName() != null
                            ? node.getBrowseName().getName() : null;
                    String displayName = node.getDisplayName() != null && node.getDisplayName().getText() != null
                            ? node.getDisplayName().getText() : null;
                    String name = displayName != null ? displayName : browseName;
                    String dataType = null;
                    try {
                        NodeId dt = node.getDataType();
                        if (dt != null) {
                            OpcUaDataType builtin = OpcUaDataType.fromNodeId(dt);
                            dataType = builtin != null ? builtin.name() : dt.toParseableString();
                        }
                    } catch (Exception ignore) {
                        // best-effort: an unreadable data type just omits the field
                    }
                    nodes.add(CommandJson.nodeEntry(ns, uri, id.getIdentifier().toString(),
                            ValueCodec.idTypeName(id), name, dataType));
                } catch (Exception e) {
                    LOGGER.trace("[{}] nodes query: skipping unreadable node: {}", serverConfig.getId(), e.toString());
                }
                emitted++;
            }
            JsonObject responsePayload = new JsonObject();
            responsePayload.addProperty("id", serverConfig.getId());
            responsePayload.addProperty("total", total);
            responsePayload.addProperty("offset", offset);
            responsePayload.addProperty("limit", limit);
            responsePayload.addProperty("truncated", truncated);
            responsePayload.add("nodes", nodes);
            reply(request, "nodes", responsePayload);
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
     * {@code ns} index; {@code signalId} is the node identifier and the optional {@code idType}
     * ({@code Numeric}/{@code String}/{@code Guid}, as reported by the {@code nodes}/{@code
     * subscriptions} queries; default {@code String}) selects its OPC UA identifier type so numeric /
     * GUID nodes round-trip correctly. Throws if the entry is incomplete or the URI is not in the
     * server's namespace table.
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
        String idType = o.has("idType") ? o.get("idType").getAsString() : null;
        return ValueCodec.nodeId(ns, o.get("signalId").getAsString(), idType);
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
