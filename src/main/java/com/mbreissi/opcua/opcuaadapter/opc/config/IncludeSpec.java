package com.mbreissi.opcua.opcuaadapter.opc.config;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;

import java.util.ArrayList;
import java.util.List;

/** The {@code include} list of a subscription: tag matchers to subscribe to. */
public class IncludeSpec {

    private final List<TagSpec> tagSpecs = new ArrayList<>();

    private IncludeSpec() {
    }

    public static IncludeSpec fromJson(JsonArray includeArray, ServerConfiguration serverConfiguration) {
        IncludeSpec include = new IncludeSpec();
        if (includeArray != null) {
            for (JsonElement element : includeArray) {
                include.tagSpecs.add(TagSpec.fromJson(element.getAsJsonObject(),
                        serverConfiguration.getDefaultSamplingMs(),
                        serverConfiguration.getDefaultQueueSize()));
            }
        }
        return include;
    }

    public List<TagSpec> getTagSpecs() {
        return tagSpecs;
    }
}
