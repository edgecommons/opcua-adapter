package com.mbreissi.opcua.opcuaadapter.opc.config;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;

import java.util.ArrayList;
import java.util.List;

/** The optional {@code exclude} list of a subscription: tag matchers to skip. */
public class ExcludeSpec {

    private final List<TagSpec> tagSpecs = new ArrayList<>();

    private ExcludeSpec() {
    }

    public static ExcludeSpec fromJson(JsonArray excludeArray) {
        ExcludeSpec exclude = new ExcludeSpec();
        if (excludeArray != null) {
            for (JsonElement element : excludeArray) {
                exclude.tagSpecs.add(TagSpec.fromJson(element.getAsJsonObject()));
            }
        }
        return exclude;
    }

    public List<TagSpec> getTagSpecs() {
        return tagSpecs;
    }
}
