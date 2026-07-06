package com.mbreissi.edgecommons.opcua.opc.config;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;

import java.util.ArrayList;
import java.util.List;

/** The optional {@code exclude} list of a subscription: signal matchers to skip. */
public class ExcludeSpec {

    private final List<SignalSpec> signalSpecs = new ArrayList<>();

    private ExcludeSpec() {
    }

    public static ExcludeSpec fromJson(JsonArray excludeArray) {
        ExcludeSpec exclude = new ExcludeSpec();
        if (excludeArray != null) {
            for (JsonElement element : excludeArray) {
                exclude.signalSpecs.add(SignalSpec.fromJson(element.getAsJsonObject()));
            }
        }
        return exclude;
    }

    public List<SignalSpec> getSignalSpecs() {
        return signalSpecs;
    }
}
