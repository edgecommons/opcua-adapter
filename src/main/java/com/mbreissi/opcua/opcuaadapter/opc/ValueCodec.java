package com.mbreissi.opcua.opcuaadapter.opc;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.milo.opcua.stack.core.NodeIds;
import org.eclipse.milo.opcua.stack.core.types.builtin.DataValue;
import org.eclipse.milo.opcua.stack.core.types.builtin.DateTime;
import org.eclipse.milo.opcua.stack.core.types.builtin.NodeId;
import org.eclipse.milo.opcua.stack.core.types.builtin.StatusCode;
import org.eclipse.milo.opcua.stack.core.types.builtin.Variant;
import org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.Unsigned;

import java.time.Instant;
import java.time.format.DateTimeFormatter;

/**
 * Conversions between OPC UA values and the southbound JSON contract: a {@code DataValue} → a
 * contract {@code sample} (value + normalized quality + timestamps), and a JSON write value → a
 * typed {@code Variant} for the node's data type. Pure/stateless.
 */
public final class ValueCodec {

    private static final Logger LOGGER = LogManager.getLogger(ValueCodec.class);

    private ValueCodec() {
    }

    /** Normalize an OPC UA status code to the contract's {@code GOOD|BAD|UNCERTAIN}. */
    public static String normalizeQuality(StatusCode sc) {
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

    /** Build one contract {@code sample}: value, normalized quality + qualityRaw, source/server timestamps. */
    public static JsonObject toSample(DataValue value) {
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

    /** The protocol-native {@code address} object for a node id: {@code {ns, nodeId}}. */
    public static JsonObject address(NodeId nodeId) {
        JsonObject address = new JsonObject();
        address.addProperty("ns", nodeId.getNamespaceIndex().intValue());
        address.addProperty("nodeId", nodeId.getIdentifier().toString());
        return address;
    }

    /** Coerce a JSON write value to a {@link Variant} of the node's data type, or {@code null} if unsupported. */
    public static Variant variantFromValue(NodeId targetType, JsonElement value) {
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
            LOGGER.warn("unsupported write target type {}", targetType);
            return null;
        } catch (Exception e) {
            LOGGER.error("value coercion failed for type {}: {}", targetType, e.toString());
            return null;
        }
    }

    public static StatusCode statusFromString(String s) {
        if ("GOOD".equalsIgnoreCase(s)) {
            return StatusCode.GOOD;
        }
        if ("BAD".equalsIgnoreCase(s)) {
            return StatusCode.BAD;
        }
        return StatusCode.UNCERTAIN;
    }

    public static DateTime sourceTime(String iso) {
        if (iso == null) {
            return DateTime.MIN_VALUE;
        }
        return new DateTime(Instant.from(DateTimeFormatter.ISO_INSTANT.parse(iso)));
    }
}
