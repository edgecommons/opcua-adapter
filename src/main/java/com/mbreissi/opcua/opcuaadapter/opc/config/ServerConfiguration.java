package com.mbreissi.opcua.opcuaadapter.opc.config;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mbreissi.ggcommons.config.ConfigManager;

import java.util.ArrayList;
import java.util.List;

/**
 * Resolved configuration for one OPC UA device instance (one {@code component.instances[]} entry),
 * following the southbound config convention (docs/SOUTHBOUND.md §4). Instance-level {@code defaults}
 * override {@code component.global.defaults}.
 */
public class ServerConfiguration {

    private static final double DEFAULT_PUBLISH_INTERVAL_MS = 1000.0;
    private static final double DEFAULT_SAMPLING_MS = 0.0;
    private static final int DEFAULT_QUEUE_SIZE = 100;

    private final ConfigManager configManager;
    private final String id;
    private final ConnectionInfo connection;
    private final double defaultPublishIntervalMs;
    private final double defaultSamplingMs;
    private final int defaultQueueSize;
    private final String publishTopicTemplate;
    private final long batchMs;
    private final boolean writeEnabled;
    private final String writeTopic;
    private final String readTopic;
    private final String controlTopic;
    private final List<SubscriptionSpec> subscriptionSpecs = new ArrayList<>();

    public ServerConfiguration(ConfigManager config, JsonObject globalConfig, String instanceId) {
        this.configManager = config;
        JsonObject inst = config.getInstanceConfig(instanceId);
        this.id = inst.has("id") ? inst.get("id").getAsString() : instanceId;
        this.connection = new ConnectionInfo(inst.has("connection") ? inst.getAsJsonObject("connection") : new JsonObject());

        JsonObject gDefaults = (globalConfig != null && globalConfig.has("defaults"))
                ? globalConfig.getAsJsonObject("defaults") : new JsonObject();
        JsonObject iDefaults = inst.has("defaults") ? inst.getAsJsonObject("defaults") : new JsonObject();
        this.defaultPublishIntervalMs = num(iDefaults, gDefaults, "publishIntervalMs", DEFAULT_PUBLISH_INTERVAL_MS);
        this.defaultSamplingMs = num(iDefaults, gDefaults, "samplingRateMs", DEFAULT_SAMPLING_MS);
        this.defaultQueueSize = (int) num(iDefaults, gDefaults, "queueSize", DEFAULT_QUEUE_SIZE);

        JsonObject publish = inst.has("publish") ? inst.getAsJsonObject("publish") : new JsonObject();
        this.publishTopicTemplate = publish.has("topic")
                ? publish.get("topic").getAsString()
                : "southbound/{ComponentName}/{InstanceId}/{signalId}";
        this.batchMs = publish.has("batchMs") ? publish.get("batchMs").getAsLong() : (long) defaultPublishIntervalMs;

        JsonObject write = inst.has("write") ? inst.getAsJsonObject("write") : new JsonObject();
        this.writeEnabled = write.has("enabled") && write.get("enabled").getAsBoolean();
        this.writeTopic = resolveTemplate(write.has("topic")
                ? write.get("topic").getAsString()
                : "southbound/{ComponentName}/{InstanceId}/write");

        JsonObject read = inst.has("read") ? inst.getAsJsonObject("read") : new JsonObject();
        this.readTopic = resolveTemplate(read.has("topic")
                ? read.get("topic").getAsString()
                : "southbound/{ComponentName}/{InstanceId}/read");

        this.controlTopic = resolveTemplate("southbound/{ComponentName}/{InstanceId}/control/+");

        if (inst.has("subscriptions")) {
            for (JsonElement el : inst.getAsJsonArray("subscriptions")) {
                subscriptionSpecs.add(SubscriptionSpec.fromJson(this, el.getAsJsonObject()));
            }
        }
    }

    private static double num(JsonObject primary, JsonObject fallback, String key, double dflt) {
        if (primary.has(key)) {
            return primary.get(key).getAsDouble();
        }
        if (fallback.has(key)) {
            return fallback.get(key).getAsDouble();
        }
        return dflt;
    }

    public String getId() {
        return id;
    }

    public ConnectionInfo getConnection() {
        return connection;
    }

    public double getDefaultPublishIntervalMs() {
        return defaultPublishIntervalMs;
    }

    public double getDefaultSamplingMs() {
        return defaultSamplingMs;
    }

    public int getDefaultQueueSize() {
        return defaultQueueSize;
    }

    public long getBatchMs() {
        return batchMs;
    }

    public boolean isWriteEnabled() {
        return writeEnabled;
    }

    public String getWriteTopic() {
        return writeTopic;
    }

    public String getReadTopic() {
        return readTopic;
    }

    public String getControlTopic() {
        return controlTopic;
    }

    public List<SubscriptionSpec> getSubscriptionSpecs() {
        return subscriptionSpecs;
    }

    /** Resolve the per-signal publish topic, substituting the standard template vars plus {@code {signalId}}. */
    public String resolvePublishTopic(String overrideTemplate, String signalId) {
        String template = overrideTemplate != null ? overrideTemplate : publishTopicTemplate;
        return resolveTemplate(template).replace("{signalId}", signalId);
    }

    /** Resolve template vars ({ThingName}, {ComponentName}, configured tags) plus the app-local {InstanceId}. */
    public String resolveTemplate(String template) {
        String resolved = configManager.resolveTemplate(template);
        if (template.contains("{InstanceId}")) {
            resolved = resolved.replace("{InstanceId}", id);
        }
        return resolved;
    }
}
