package com.mbreissi.edgecommons.opcua.opc.config;

import com.google.gson.JsonObject;
import org.eclipse.milo.opcua.stack.core.types.enumerated.DeadbandType;

/** A monitored-item deadband: {@code {"type": "None|Absolute|Percent", "value": <number>}}. */
public class DeadbandSpec {

    private DeadbandType type = DeadbandType.None;
    private double value = 0.0;

    private DeadbandSpec(DeadbandType type, double value) {
        if (type != null) {
            this.type = type;
        }
        this.value = value;
    }

    public DeadbandType getType() {
        return type;
    }

    public double getValue() {
        return value;
    }

    public static DeadbandSpec fromJson(JsonObject json) {
        String type = "None";
        double value = 0.0;
        if (json != null) {
            if (json.has("type")) {
                type = json.get("type").getAsString();
            }
            if (json.has("value")) {
                value = json.get("value").getAsDouble();
            }
        }
        return new DeadbandSpec(DeadbandType.valueOf(type), value);
    }
}
