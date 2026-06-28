package com.mbreissi.opcua.opcuaadapter.opc.config;

import com.google.gson.JsonObject;

/**
 * One tag matcher within a subscription's include/exclude list (southbound config convention):
 * {@code { "namespaceUri": "<uri>", "namespace": <int>, "match": "<regex>", "topic": "<optional>",
 * "samplingRateMs": <num>, "queueSize": <int>, "deadband": {...} }}.
 *
 * <p>The namespace is identified by {@code namespaceUri} (preferred — resolved to the server's current
 * index at runtime) or by a literal {@code namespace} index (fallback). {@code match} is a regex
 * applied to the node's identifier, browse name, or display name.
 */
public class TagSpec {

    private final int namespace;
    private final String namespaceUri;
    private final String match;
    private final String topic;
    private final double samplingRateMs;
    private final int queueSize;
    private final DeadbandSpec deadband;

    private TagSpec(int namespace, String namespaceUri, String match, String topic,
                   double samplingRateMs, int queueSize, DeadbandSpec deadband) {
        this.namespace = namespace;
        this.namespaceUri = namespaceUri;
        this.match = match;
        this.topic = topic;
        this.samplingRateMs = samplingRateMs;
        this.queueSize = queueSize;
        this.deadband = deadband;
    }

    /** Literal namespace index; used only when {@link #getNamespaceUri()} is {@code null}. */
    public int getNamespace() {
        return namespace;
    }

    /** Namespace URI (preferred); resolved to the server's current index at runtime, or {@code null}. */
    public String getNamespaceUri() {
        return namespaceUri;
    }

    public String getMatch() {
        return match;
    }

    /** Optional per-tag publish-topic override; {@code null} to use the instance's publish topic. */
    public String getTopic() {
        return topic;
    }

    public double getSamplingRateMs() {
        return samplingRateMs;
    }

    public int getQueueSize() {
        return queueSize;
    }

    public DeadbandSpec getDeadband() {
        return deadband;
    }

    public static TagSpec fromJson(JsonObject o) {
        return fromJson(o, 0.0, 1);
    }

    public static TagSpec fromJson(JsonObject o, double defaultSamplingMs, int defaultQueueSize) {
        int ns = o.has("namespace") ? o.get("namespace").getAsInt() : 0;
        String nsUri = o.has("namespaceUri") ? o.get("namespaceUri").getAsString() : null;
        String match = o.has("match") ? o.get("match").getAsString() : ".*";
        String topic = o.has("topic") ? o.get("topic").getAsString() : null;
        double sampling = o.has("samplingRateMs") ? o.get("samplingRateMs").getAsDouble() : defaultSamplingMs;
        int queue = o.has("queueSize") ? o.get("queueSize").getAsInt() : defaultQueueSize;
        DeadbandSpec deadband = DeadbandSpec.fromJson(o.has("deadband") ? o.getAsJsonObject("deadband") : null);
        return new TagSpec(ns, nsUri, match, topic, sampling, queue, deadband);
    }
}
