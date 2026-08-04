package com.mbreissi.edgecommons.opcua.opc.config;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mbreissi.edgecommons.opcua.opc.CanonicalSignalId;
import com.mbreissi.edgecommons.config.ConfigManager;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Resolved configuration for one OPC UA device instance (one {@code component.instances[]} entry),
 * following the southbound config convention (docs/SOUTHBOUND.md §4). Instance-level {@code defaults}
 * override {@code component.global.defaults}.
 *
 * <p><b>UNS migration.</b> The legacy publish/read/write/control <em>topic templates</em>
 * ({@code publish.topic}, {@code read.topic}, {@code write.topic}, and the
 * {@code southbound/{site}/{ComponentName}/{InstanceId}/{signalId}} scheme) are <b>retired</b>:
 * signal updates ride the UNS {@code data} class ({@link com.mbreissi.edgecommons.uns.UnsClass#DATA},
 * topic minted by {@code gg.instance(id).uns()}), and the command surface is the library-owned
 * {@code cmd/sb/*} inbox. The boolean {@code write.enabled} is replaced by the {@code writes.allow[]}
 * allow-list (D‑U16), matched against the stable {@code signal.id}. Only the addressing-agnostic keys
 * survive: {@code id}, {@code connection}, {@code defaults}, {@code publish.batchMs}, and
 * {@code subscriptions}.
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
    private final long batchMs;
    private final Set<String> writesAllow;
    private final AdapterLimits limits;
    private final List<SubscriptionSpec> subscriptionSpecs = new ArrayList<>();

    public ServerConfiguration(ConfigManager config, JsonObject globalConfig, String instanceId) {
        this.configManager = config;
        JsonObject inst = config.getInstanceConfig(instanceId);
        this.id = inst.has("id") ? inst.get("id").getAsString() : instanceId;
        this.connection = new ConnectionInfo(inst.has("connection") ? inst.getAsJsonObject("connection") : new JsonObject());

        this.limits = AdapterLimits.fromGlobal(globalConfig);

        JsonObject gDefaults = (globalConfig != null && globalConfig.has("defaults"))
                ? globalConfig.getAsJsonObject("defaults") : new JsonObject();
        JsonObject iDefaults = inst.has("defaults") ? inst.getAsJsonObject("defaults") : new JsonObject();
        this.defaultPublishIntervalMs = num(iDefaults, gDefaults, "publishIntervalMs", DEFAULT_PUBLISH_INTERVAL_MS);
        this.defaultSamplingMs = num(iDefaults, gDefaults, "samplingRateMs", DEFAULT_SAMPLING_MS);
        this.defaultQueueSize = (int) num(iDefaults, gDefaults, "queueSize", DEFAULT_QUEUE_SIZE);

        JsonObject publish = inst.has("publish") ? inst.getAsJsonObject("publish") : new JsonObject();
        this.batchMs = publish.has("batchMs") ? publish.get("batchMs").getAsLong() : (long) defaultPublishIntervalMs;

        // D-U16 write allow-list: the stable signal.ids (or the wildcard "*") the sb/write verb will
        // accept. Absent/empty => no writes are accepted (secure-by-default, matching the retired
        // write.enabled:false default).
        this.writesAllow = new LinkedHashSet<>();
        JsonObject writes = inst.has("writes") ? inst.getAsJsonObject("writes") : new JsonObject();
        if (writes.has("allow")) {
            for (JsonElement el : writes.getAsJsonArray("allow")) {
                String entry = el.getAsString();
                if ("*".equals(entry)) {
                    writesAllow.add(entry);
                    continue;
                }
                // Parsed eagerly so a malformed or index-bearing entry fails the instance at startup
                // rather than silently never matching (or, worse, matching the wrong node later).
                writesAllow.add(CanonicalSignalId.parse(entry).toString());
            }
        }

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

    /** The bounded-operation budgets from {@code component.global.browse} / {@code .limits}. */
    public AdapterLimits getLimits() {
        return limits;
    }

    /**
     * Whether {@code sb/write} may write the given <b>stable signal id</b> — the canonical
     * {@code nsu=<namespaceUri>;<type>=<id>} form as published in {@code signal.id}. A write is
     * permitted iff the id is listed in {@code writes.allow[]} or the list contains the wildcard
     * {@code "*"}. An absent/empty allow-list rejects every write (D‑U16; secure-by-default).
     *
     * <p>The key is deliberately namespace-<b>URI</b> based. A namespace index is a per-session handle:
     * an index-keyed allow-list can refuse a legitimate write after the server renumbers, and can
     * authorize a different node that has taken over the old index.
     *
     * @param signalId the target's canonical {@code signal.id}
     * @return {@code true} if the write is allowed
     */
    public boolean isWriteAllowed(CanonicalSignalId signalId) {
        return signalId != null && (writesAllow.contains("*") || writesAllow.contains(signalId.toString()));
    }

    /** Whether any {@code writes.allow[]} entry is configured (writes are entirely off when empty). */
    public boolean isWriteConfigured() {
        return !writesAllow.isEmpty();
    }

    public List<SubscriptionSpec> getSubscriptionSpecs() {
        return subscriptionSpecs;
    }

    /**
     * Resolve <b>filesystem/path</b> template variables ({@code {ThingName}}, {@code {ComponentName}},
     * configured {@code tags}) plus the app-local {@code {InstanceId}} in a config value — used by the
     * security layer for PKI dir / certificate paths. This is unrelated to UNS topic addressing (those
     * are minted by {@code gg.instance(id).uns()}); it is ordinary config template substitution.
     */
    public String resolveTemplate(String template) {
        String resolved = configManager.resolveTemplate(template);
        if (template.contains("{InstanceId}")) {
            resolved = resolved.replace("{InstanceId}", id);
        }
        return resolved;
    }
}
