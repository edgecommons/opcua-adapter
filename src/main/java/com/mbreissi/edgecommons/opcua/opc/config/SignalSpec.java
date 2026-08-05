package com.mbreissi.edgecommons.opcua.opc.config;

import com.google.gson.JsonObject;
import com.mbreissi.edgecommons.opcua.opc.SafeRegex;

import java.util.regex.Pattern;

/**
 * One signal matcher within a subscription's include/exclude list (southbound config convention):
 * {@code { "namespaceUri": "<uri>", "namespace": <int>, "match": "<regex>",
 * "samplingRateMs": <num>, "queueSize": <int>, "deadband": {...} }}.
 *
 * <p>The namespace is identified by {@code namespaceUri} (preferred — resolved to the server's current
 * index at runtime) or by a literal {@code namespace} index (fallback). {@code match} is a regex
 * applied to the node's identifier, browse name, or display name.
 */
public class SignalSpec {

    private final int namespace;
    private final String namespaceUri;
    private final String match;
    private final double samplingRateMs;
    private final int queueSize;
    private final DeadbandSpec deadband;
    private Pattern compiled;

    private SignalSpec(int namespace, String namespaceUri, String match,
                   double samplingRateMs, int queueSize, DeadbandSpec deadband) {
        this.namespace = namespace;
        this.namespaceUri = namespaceUri;
        this.match = match;
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

    /**
     * The {@link #getMatch()} regex, compiled once and cached. Compilation is lazy (first use) so it
     * stays on the same call path as before — a config-time regex still surfaces a bad pattern where it
     * always did (subscription build), while the on-demand read path compiles each request's patterns
     * exactly once instead of recompiling per address-space node.
     */
    public Pattern pattern() {
        Pattern p = compiled;
        if (p == null) {
            p = SafeRegex.compile(match, AdapterLimits.DEFAULT_MAX_REGEX_LENGTH);
            compiled = p;
        }
        return p;
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

    public static SignalSpec fromJson(JsonObject o) {
        return fromJson(o, 0.0, 1);
    }

    public static SignalSpec fromJson(JsonObject o, double defaultSamplingMs, int defaultQueueSize) {
        int ns = o.has("namespace") ? o.get("namespace").getAsInt() : 0;
        String nsUri = o.has("namespaceUri") ? o.get("namespaceUri").getAsString() : null;
        String match = o.has("match") ? o.get("match").getAsString() : ".*";
        double sampling = o.has("samplingRateMs") ? o.get("samplingRateMs").getAsDouble() : defaultSamplingMs;
        int queue = o.has("queueSize") ? o.get("queueSize").getAsInt() : defaultQueueSize;
        DeadbandSpec deadband = DeadbandSpec.fromJson(o.has("deadband") ? o.getAsJsonObject("deadband") : null);
        return new SignalSpec(ns, nsUri, match, sampling, queue, deadband);
    }
}
