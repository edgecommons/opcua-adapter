package com.mbreissi.opcua.opcuaadapter.opc;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.mbreissi.ggcommons.config.ConfigManager;
import com.mbreissi.ggcommons.messaging.Message;
import com.mbreissi.ggcommons.messaging.MessageBuilder;
import com.mbreissi.ggcommons.messaging.MessagingClient;
import com.mbreissi.ggcommons.metrics.Metric;
import com.mbreissi.ggcommons.metrics.MetricBuilder;
import com.mbreissi.ggcommons.metrics.MetricEmitter;
import com.mbreissi.opcua.opcuaadapter.opc.config.DeadbandSpec;
import com.mbreissi.opcua.opcuaadapter.opc.config.ServerConfiguration;
import com.mbreissi.opcua.opcuaadapter.opc.config.SubscriptionSpec;
import com.mbreissi.opcua.opcuaadapter.opc.config.TagSpec;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.milo.opcua.sdk.client.OpcUaClient;
import org.eclipse.milo.opcua.sdk.client.identity.AnonymousProvider;
import org.eclipse.milo.opcua.sdk.client.nodes.UaNode;
import org.eclipse.milo.opcua.sdk.client.nodes.UaVariableNode;
import org.eclipse.milo.opcua.sdk.client.subscriptions.OpcUaMonitoredItem;
import org.eclipse.milo.opcua.sdk.client.subscriptions.OpcUaSubscription;
import org.eclipse.milo.opcua.stack.core.NodeIds;
import org.eclipse.milo.opcua.stack.core.UaException;
import org.eclipse.milo.opcua.stack.core.security.SecurityPolicy;
import org.eclipse.milo.opcua.stack.core.types.builtin.DataValue;
import org.eclipse.milo.opcua.stack.core.types.builtin.DateTime;
import org.eclipse.milo.opcua.stack.core.types.builtin.LocalizedText;
import org.eclipse.milo.opcua.stack.core.types.builtin.NodeId;
import org.eclipse.milo.opcua.stack.core.types.builtin.StatusCode;
import org.eclipse.milo.opcua.stack.core.types.builtin.Variant;
import org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.Unsigned;
import org.eclipse.milo.opcua.stack.core.types.enumerated.BrowseDirection;
import org.eclipse.milo.opcua.stack.core.types.enumerated.BrowseResultMask;
import org.eclipse.milo.opcua.stack.core.types.enumerated.DataChangeTrigger;
import org.eclipse.milo.opcua.stack.core.types.enumerated.DeadbandType;
import org.eclipse.milo.opcua.stack.core.types.enumerated.NodeClass;
import org.eclipse.milo.opcua.stack.core.types.enumerated.TimestampsToReturn;
import org.eclipse.milo.opcua.stack.core.types.structured.BrowseDescription;
import org.eclipse.milo.opcua.stack.core.types.structured.BrowseResult;
import org.eclipse.milo.opcua.stack.core.types.structured.DataChangeFilter;
import org.eclipse.milo.opcua.stack.core.types.structured.ReferenceDescription;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.LinkedBlockingQueue;

import static org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.Unsigned.uint;
import static org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.Unsigned.ushort;

/**
 * One OPC UA device connection: browses the address space, subscribes to matching tags (with
 * deadband), republishes value changes northbound as {@code SouthboundTagUpdate} messages, and
 * serves on-demand batch read + batch write requests. Built on Eclipse Milo 1.1.x.
 */
public class Client {

    private static final Logger LOGGER = LogManager.getLogger(Client.class);
    private static final String HEALTH_METRIC = "southbound_health";

    private final ConfigManager configManager;
    private final MessagingClient messaging;
    private final MetricEmitter metrics;
    private final ServerConfiguration configuration;
    private final ClientMetrics clientMetrics = new ClientMetrics();

    private OpcUaClient uaClient;
    private volatile boolean connected = false;

    private final Map<OpcUaSubscription, SubscriptionSpec> subscriptions = new HashMap<>();
    private final Map<String, TagSpec> resolvedTags = new HashMap<>();
    private final Map<NodeId, UaVariableNode> allNodes = new HashMap<>();
    private final Map<UaVariableNode, LinkedBlockingQueue<DataValue>> pendingMessages = new HashMap<>();

    public Client(ConfigManager configManager, MessagingClient messaging, MetricEmitter metrics,
                  ServerConfiguration configuration) {
        this.configManager = configManager;
        this.messaging = messaging;
        this.metrics = metrics;
        this.configuration = configuration;
        defineHealthMetric();
        connect();                       // blocks + retries until connected
        fetchAllNodes();
        createOpcSubscriptions();
        subscribeToControlTopics();
        if (configuration.getBatchMs() > 0) {
            new Timer("batch-publisher-" + configuration.getId(), true)
                    .scheduleAtFixedRate(new BatchPublisher(), 0, configuration.getBatchMs());
        }
    }

    public String getId() {
        return configuration.getId();
    }

    public boolean isConnected() {
        return connected;
    }

    public ClientMetrics getClientMetrics() {
        return clientMetrics;
    }

    public Map<String, TagSpec> getResolvedTags() {
        return resolvedTags;
    }

    // ---- connection -------------------------------------------------------------------------

    private void connect() {
        String endpoint = configuration.getConnection().getEndpoint();
        SecurityPolicy policy = parseSecurityPolicy(configuration.getConnection().getSecurityPolicy());
        while (uaClient == null) {
            try {
                if (policy == SecurityPolicy.None) {
                    // Anonymous, no security. Lambda param types are inferred from the create() overload,
                    // which avoids importing the (package-moved) config/transport builder types.
                    uaClient = OpcUaClient.create(
                            endpoint,
                            endpoints -> endpoints.stream()
                                    .filter(e -> e.getSecurityPolicyUri().equals(SecurityPolicy.None.getUri()))
                                    .findFirst(),
                            transport -> { },
                            cfg -> cfg
                                    .setApplicationName(LocalizedText.english("GGCommons OPC UA Adapter"))
                                    .setApplicationUri("urn:ggcommons:opcua:adapter")
                                    .setIdentityProvider(new AnonymousProvider()));
                } else {
                    // TODO(security, B4): secure channel (Basic256Sha256/SignAndEncrypt) via the
                    // credentials vault. Until implemented, fail fast rather than silently downgrade.
                    throw new UaException(org.eclipse.milo.opcua.stack.core.StatusCodes.Bad_NotImplemented,
                            "Secure OPC UA connections not yet implemented (policy=" + policy + ")");
                }
                uaClient.connect();
                connected = true;
                LOGGER.info("[{}] connected to {}", getId(), endpoint);
                emitHealth();
            } catch (Exception e) {
                uaClient = null;
                connected = false;
                LOGGER.error("[{}] unable to connect to {}: {}. Retrying in 5s...", getId(), endpoint, e.toString());
                emitHealth();
                sleep(5000);
            }
        }
    }

    private SecurityPolicy parseSecurityPolicy(String name) {
        try {
            return SecurityPolicy.valueOf(name);
        } catch (IllegalArgumentException e) {
            LOGGER.warn("[{}] invalid securityPolicy '{}', defaulting to None", getId(), name);
            return SecurityPolicy.None;
        }
    }

    // ---- address-space browse ---------------------------------------------------------------

    private void fetchAllNodes() {
        LOGGER.info("[{}] browsing address space...", getId());
        browseFolder(NodeIds.RootFolder);
        LOGGER.info("[{}] browse complete: {} variable nodes", getId(), allNodes.size());
    }

    private void browseFolder(NodeId folderNodeId) {
        BrowseDescription browse = new BrowseDescription(
                folderNodeId,
                BrowseDirection.Forward,
                NodeIds.References,
                true,
                uint(NodeClass.Object.getValue() | NodeClass.Variable.getValue()),
                uint(BrowseResultMask.All.getValue()));
        try {
            BrowseResult result = uaClient.browse(browse);
            ReferenceDescription[] references = result.getReferences();
            if (references == null) {
                return;
            }
            for (ReferenceDescription rd : references) {
                rd.getNodeId().toNodeId(uaClient.getNamespaceTable()).ifPresent(nodeId -> {
                    try {
                        if (rd.getNodeClass() == NodeClass.Object) {
                            browseFolder(nodeId);
                        } else if (rd.getNodeClass() == NodeClass.Variable) {
                            UaNode node = uaClient.getAddressSpace().getNode(nodeId);
                            if (node instanceof UaVariableNode) {
                                allNodes.put(nodeId, (UaVariableNode) node);
                            }
                        }
                    } catch (UaException e) {
                        LOGGER.trace("[{}] skipping node {}: {}", getId(), nodeId, e.getMessage());
                    }
                });
            }
        } catch (UaException e) {
            LOGGER.warn("[{}] browse of {} failed: {}", getId(), folderNodeId, e.getMessage());
        }
    }

    // ---- subscriptions ----------------------------------------------------------------------

    private void createOpcSubscriptions() {
        for (SubscriptionSpec spec : configuration.getSubscriptionSpecs()) {
            OpcUaSubscription subscription = createOpcSubscription(spec);
            if (subscription != null) {
                subscriptions.put(subscription, spec);
            }
        }
    }

    private OpcUaSubscription createOpcSubscription(SubscriptionSpec spec) {
        try {
            OpcUaSubscription subscription = new OpcUaSubscription(uaClient, spec.getPublishIntervalMs());
            subscription.setSubscriptionListener(new OpcUaSubscription.SubscriptionListener() {
                @Override
                public void onTransferFailed(OpcUaSubscription sub, StatusCode status) {
                    LOGGER.warn("[{}] subscription '{}' transfer failed ({}); re-establishing", getId(), spec.getId(), status);
                    reestablishSubscription(sub);
                }
            });

            Map<UaVariableNode, TagSpec> matching = filterForSubscription(spec);
            List<OpcUaMonitoredItem> items = new ArrayList<>();
            matching.forEach((node, tagSpec) -> {
                OpcUaMonitoredItem item = OpcUaMonitoredItem.newDataItem(node.getNodeId());
                item.setSamplingInterval(tagSpec.getSamplingRateMs());
                item.setQueueSize(uint(tagSpec.getQueueSize()));
                DeadbandSpec deadband = tagSpec.getDeadband();
                if (deadband.getType() != DeadbandType.None) {
                    item.setFilter(new DataChangeFilter(
                            DataChangeTrigger.StatusValue,
                            uint(deadband.getType().getValue()),
                            deadband.getValue()));
                }
                item.setDataValueListener((it, value) -> onSubscriptionValue(node, value));
                items.add(item);
                resolvedTags.put(node.getNodeId().getIdentifier().toString(), tagSpec);
            });

            subscription.addMonitoredItems(items);
            subscription.create();
            subscription.synchronizeMonitoredItems();
            LOGGER.info("[{}] subscription '{}': {} monitored item(s)", getId(), spec.getId(), items.size());
            return subscription;
        } catch (Exception e) {
            LOGGER.error("[{}] failed to create subscription '{}': {}", getId(), spec.getId(), e.toString());
            return null;
        }
    }

    public void reestablishSubscription(OpcUaSubscription subscription) {
        SubscriptionSpec spec = subscriptions.remove(subscription);
        if (spec == null) {
            return;
        }
        OpcUaSubscription replacement = createOpcSubscription(spec);
        if (replacement != null) {
            subscriptions.put(replacement, spec);
        }
    }

    private Map<UaVariableNode, TagSpec> filterForSubscription(SubscriptionSpec spec) {
        Map<UaVariableNode, TagSpec> matching = new LinkedHashMap<>();
        for (UaVariableNode node : allNodes.values()) {
            TagSpec include = matchTag(node, spec.getIncludeSpec().getTagSpecs(), true);
            if (include == null) {
                continue;
            }
            if (spec.getExcludeSpec() != null && matchTag(node, spec.getExcludeSpec().getTagSpecs(), false) != null) {
                continue;
            }
            matching.put(node, include);
        }
        return matching;
    }

    /** Match a node against tag specs by namespace + regex over nodeId/browseName/displayName. */
    private TagSpec matchTag(UaVariableNode node, List<TagSpec> specs, boolean matchNames) {
        String idStr = node.getNodeId().getIdentifier().toString();
        String browseName = node.getBrowseName() != null && node.getBrowseName().getName() != null
                ? node.getBrowseName().getName() : "";
        String displayName = node.getDisplayName() != null && node.getDisplayName().getText() != null
                ? node.getDisplayName().getText() : "";
        for (TagSpec spec : specs) {
            if (!node.getNodeId().getNamespaceIndex().equals(ushort(spec.getNamespace()))) {
                continue;
            }
            String regex = spec.getMatch();
            if (idStr.matches(regex)
                    || (matchNames && (browseName.matches(regex) || displayName.matches(regex)))) {
                return spec;
            }
        }
        return null;
    }

    private void onSubscriptionValue(UaVariableNode node, DataValue value) {
        clientMetrics.incrementReadMetrics();
        if (configuration.getBatchMs() > 0) {
            pendingMessages.computeIfAbsent(node, n -> new LinkedBlockingQueue<>()).add(value);
        } else {
            List<DataValue> single = new ArrayList<>();
            single.add(value);
            publishTagUpdate(node, single);
        }
    }

    // ---- northbound publish (SouthboundTagUpdate contract) ----------------------------------

    private void publishTagUpdate(UaVariableNode node, List<DataValue> values) {
        if (values.isEmpty()) {
            return;
        }
        String tagId = node.getNodeId().getIdentifier().toString();
        String canonicalId = node.getNodeId().toParseableString();
        String browseName = node.getBrowseName() != null && node.getBrowseName().getName() != null
                ? node.getBrowseName().getName() : "";
        String displayName = node.getDisplayName() != null && node.getDisplayName().getText() != null
                ? node.getDisplayName().getText() : "";

        JsonObject device = new JsonObject();
        device.addProperty("adapter", "opcua");
        device.addProperty("instance", getId());
        device.addProperty("endpoint", configuration.getConnection().getEndpoint());

        JsonObject address = new JsonObject();
        address.addProperty("ns", node.getNodeId().getNamespaceIndex().intValue());
        address.addProperty("nodeId", tagId);

        JsonObject tag = new JsonObject();
        tag.addProperty("id", canonicalId);
        tag.addProperty("name", !displayName.isEmpty() ? displayName : browseName);
        tag.add("address", address);

        JsonArray samples = new JsonArray();
        for (DataValue value : values) {
            samples.add(toSample(value));
        }

        JsonObject body = new JsonObject();
        body.add("device", device);
        body.add("tag", tag);
        body.add("samples", samples);

        Message message = MessageBuilder.create("SouthboundTagUpdate", "1.0")
                .withPayload(body)
                .withConfig(configManager)
                .build();
        TagSpec spec = resolvedTags.get(tagId);
        String topic = configuration.resolvePublishTopic(spec != null ? spec.getTopic() : null, tagId);
        messaging.publish(topic, message);
    }

    /** One sample with normalized quality (GOOD|BAD|UNCERTAIN) + the native status in qualityRaw. */
    private JsonObject toSample(DataValue value) {
        JsonObject sample = new JsonObject();
        Object v = value.getValue() != null ? value.getValue().getValue() : null;
        if (v == null) {
            sample.add("value", JsonNull.INSTANCE);
        } else if (v instanceof Number) {
            sample.addProperty("value", (Number) v);
        } else if (v instanceof Boolean) {
            sample.addProperty("value", (Boolean) v);
        } else {
            sample.addProperty("value", v.toString());
        }
        StatusCode sc = value.getStatusCode();
        sample.addProperty("quality", normalizeQuality(sc));
        sample.addProperty("qualityRaw", sc != null ? sc.toString() : "null");
        sample.addProperty("sourceTs", value.getSourceTime() != null ? value.getSourceTime().getJavaInstant().toString() : null);
        sample.addProperty("serverTs", value.getServerTime() != null ? value.getServerTime().getJavaInstant().toString() : null);
        return sample;
    }

    private static String normalizeQuality(StatusCode sc) {
        if (sc == null) {
            return "UNCERTAIN";
        }
        if (sc.isGood()) {
            return "GOOD";
        }
        if (sc.isBad()) {
            return "BAD";
        }
        return "UNCERTAIN";
    }

    // ---- command surface: control topics (write / read / status) ----------------------------

    private void subscribeToControlTopics() {
        if (configuration.isWriteEnabled()) {
            messaging.subscribe(configuration.getWriteTopic(), (topic, message) -> handleWrite(message));
            LOGGER.info("[{}] write enabled on {}", getId(), configuration.getWriteTopic());
        }
        // On-demand read: request/reply. Reads arbitrary tags at any time.
        messaging.subscribe(configuration.getReadTopic(), (topic, message) -> handleRead(message));
        // Status / subscriptions queries.
        messaging.subscribe(configuration.getControlTopic(), this::handleControl);
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
                    LOGGER.error("[{}] write entry missing ns/tagId/value; skipping: {}", getId(), w);
                    continue;
                }
                NodeId nodeId = new NodeId(w.get("ns").getAsInt(), w.get("tagId").getAsString());
                UaNode node = uaClient.getAddressSpace().getNode(nodeId);
                if (!(node instanceof UaVariableNode)) {
                    LOGGER.error("[{}] write target {} is not a variable; skipping", getId(), nodeId);
                    continue;
                }
                NodeId dataType = ((UaVariableNode) node).getDataType();
                Variant variant = variantFromValue(dataType, w.get("value"));
                if (variant == null) {
                    LOGGER.error("[{}] could not coerce value for {} (type {})", getId(), nodeId, dataType);
                    continue;
                }
                StatusCode status = statusFromString(w.has("status") ? w.get("status").getAsString() : "GOOD");
                DataValue dv = new DataValue(variant, status, sourceTime(w.has("sourceTs") ? w.get("sourceTs").getAsString() : null));
                nodeIds.add(nodeId);
                dataValues.add(dv);
            }
            if (nodeIds.isEmpty()) {
                return;
            }
            List<StatusCode> results = uaClient.writeValues(nodeIds, dataValues);
            for (int i = 0; i < results.size(); i++) {
                clientMetrics.incrementWriteMetrics();
                if (results.get(i).isBad()) {
                    LOGGER.warn("[{}] write to {} returned {}", getId(), nodeIds.get(i), results.get(i));
                }
            }
            LOGGER.debug("[{}] batch write of {} tag(s) complete", getId(), nodeIds.size());
        } catch (Exception e) {
            LOGGER.error("[{}] write request failed: {}", getId(), e.toString());
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
                    : uaClient.readValuesAsync(0.0, TimestampsToReturn.Both, nodeIds).get();

            JsonArray reads = new JsonArray();
            for (int i = 0; i < nodeIds.size(); i++) {
                NodeId nodeId = nodeIds.get(i);
                JsonObject address = new JsonObject();
                address.addProperty("ns", nodeId.getNamespaceIndex().intValue());
                address.addProperty("nodeId", nodeId.getIdentifier().toString());
                JsonObject tag = new JsonObject();
                tag.addProperty("id", nodeId.toParseableString());
                tag.add("address", address);
                JsonObject read = toSample(values.get(i));
                read.add("tag", tag);
                reads.add(read);
                clientMetrics.incrementReadMetrics();
            }
            JsonObject responsePayload = new JsonObject();
            responsePayload.addProperty("id", getId());
            responsePayload.add("reads", reads);
            Message response = MessageBuilder.create("SouthboundReadResult", "1.0")
                    .withCorrelationId(request.getCorrelationId())
                    .withPayload(responsePayload)
                    .withConfig(configManager)
                    .build();
            messaging.reply(request, response);
        } catch (Exception e) {
            LOGGER.error("[{}] read request failed: {}", getId(), e.toString());
        }
    }

    private void handleControl(String topic, Message request) {
        if (topic.endsWith("status")) {
            JsonObject responsePayload = new JsonObject();
            responsePayload.addProperty("id", getId());
            responsePayload.addProperty("connected", isConnected());
            responsePayload.add("metrics", clientMetrics.toJsonObject());
            reply(request, "status", responsePayload);
        } else if (topic.endsWith("subscriptions")) {
            JsonObject responsePayload = new JsonObject();
            responsePayload.addProperty("id", getId());
            JsonArray tags = new JsonArray();
            resolvedTags.forEach((tagId, spec) -> {
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
                .withConfig(configManager)
                .build();
        messaging.reply(request, response);
    }

    // ---- value coercion helpers -------------------------------------------------------------

    private Variant variantFromValue(NodeId targetType, JsonElement value) {
        try {
            if (targetType.equals(NodeIds.Boolean)) {
                return new Variant(value.getAsBoolean());
            } else if (targetType.equals(NodeIds.SByte)) {
                return new Variant((byte) value.getAsInt());
            } else if (targetType.equals(NodeIds.Byte)) {
                return new Variant(Unsigned.ubyte(value.getAsInt()));
            } else if (targetType.equals(NodeIds.Int16)) {
                return new Variant(value.getAsShort());
            } else if (targetType.equals(NodeIds.UInt16)) {
                return new Variant(Unsigned.ushort(value.getAsInt()));
            } else if (targetType.equals(NodeIds.Int32)) {
                return new Variant(value.getAsInt());
            } else if (targetType.equals(NodeIds.UInt32)) {
                return new Variant(Unsigned.uint(value.getAsLong()));
            } else if (targetType.equals(NodeIds.Int64)) {
                return new Variant(value.getAsLong());
            } else if (targetType.equals(NodeIds.UInt64)) {
                return new Variant(Unsigned.ulong(value.getAsLong()));
            } else if (targetType.equals(NodeIds.Float)) {
                return new Variant(value.getAsFloat());
            } else if (targetType.equals(NodeIds.Double)) {
                return new Variant(value.getAsDouble());
            } else if (targetType.equals(NodeIds.String)) {
                return new Variant(value.getAsString());
            }
            LOGGER.warn("[{}] unsupported write target type {}", getId(), targetType);
            return null;
        } catch (Exception e) {
            LOGGER.error("[{}] value coercion failed for type {}: {}", getId(), targetType, e.toString());
            return null;
        }
    }

    private static StatusCode statusFromString(String s) {
        if ("GOOD".equalsIgnoreCase(s)) {
            return StatusCode.GOOD;
        }
        if ("BAD".equalsIgnoreCase(s)) {
            return StatusCode.BAD;
        }
        return StatusCode.UNCERTAIN;
    }

    private static DateTime sourceTime(String iso) {
        if (iso == null) {
            return DateTime.MIN_VALUE;
        }
        return new DateTime(Instant.from(DateTimeFormatter.ISO_INSTANT.parse(iso)));
    }

    private static JsonObject asJsonObject(Message message) {
        Object body = message.getBody();
        if (body instanceof JsonObject) {
            return (JsonObject) body;
        }
        Object raw = message.getRaw();
        return raw instanceof JsonObject ? (JsonObject) raw : null;
    }

    // ---- metrics + util ---------------------------------------------------------------------

    private void defineHealthMetric() {
        Metric health = MetricBuilder.create(HEALTH_METRIC)
                .withConfig(configManager)
                .addMeasure("connectionState", "Count", 1)
                .addMeasure("readErrors", "Count", 60)
                .addDimension("instance", configuration.getId())
                .build();
        metrics.defineMetric(health);
    }

    private void emitHealth() {
        Map<String, Float> m = new HashMap<>();
        m.put("connectionState", connected ? 1.0f : 0.0f);
        m.put("readErrors", (float) clientMetrics.getIntervalReadErrors());
        metrics.emitMetric(HEALTH_METRIC, m);
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private class BatchPublisher extends TimerTask {
        @Override
        public void run() {
            pendingMessages.forEach((node, queue) -> {
                List<DataValue> values = new ArrayList<>();
                queue.drainTo(values);
                if (!values.isEmpty()) {
                    publishTagUpdate(node, values);
                }
            });
            emitHealth();
        }
    }
}
