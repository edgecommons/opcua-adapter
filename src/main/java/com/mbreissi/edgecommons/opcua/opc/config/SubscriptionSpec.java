package com.mbreissi.edgecommons.opcua.opc.config;

import com.google.gson.JsonObject;

import java.util.UUID;

/** One OPC UA subscription: a publish interval plus include/exclude signal matchers. */
public class SubscriptionSpec {

    private double publishIntervalMs;
    private IncludeSpec includeSpec;
    private ExcludeSpec excludeSpec;
    private String id;

    public static SubscriptionSpec fromJson(ServerConfiguration serverConfiguration, JsonObject o) {
        SubscriptionSpec s = new SubscriptionSpec();
        s.id = o.has("id") ? o.get("id").getAsString() : UUID.randomUUID().toString();
        s.publishIntervalMs = o.has("publishIntervalMs")
                ? o.get("publishIntervalMs").getAsDouble()
                : serverConfiguration.getDefaultPublishIntervalMs();
        s.includeSpec = IncludeSpec.fromJson(o.has("include") ? o.getAsJsonArray("include") : null, serverConfiguration);
        s.excludeSpec = ExcludeSpec.fromJson(o.has("exclude") ? o.getAsJsonArray("exclude") : null);
        return s;
    }

    public double getPublishIntervalMs() {
        return publishIntervalMs;
    }

    public IncludeSpec getIncludeSpec() {
        return includeSpec;
    }

    public ExcludeSpec getExcludeSpec() {
        return excludeSpec;
    }

    public String getId() {
        return id;
    }
}
