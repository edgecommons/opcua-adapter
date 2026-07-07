package com.mbreissi.edgecommons.opcua.opc;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.mbreissi.edgecommons.facades.Quality;
import com.mbreissi.edgecommons.facades.SignalUpdate;
import com.mbreissi.edgecommons.messaging.Message;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.milo.opcua.stack.core.NamespaceTable;
import org.eclipse.milo.opcua.stack.core.NodeIds;
import org.eclipse.milo.opcua.stack.core.types.builtin.ByteString;
import org.eclipse.milo.opcua.stack.core.types.builtin.DataValue;
import org.eclipse.milo.opcua.stack.core.types.builtin.DateTime;
import org.eclipse.milo.opcua.stack.core.types.builtin.NodeId;
import org.eclipse.milo.opcua.stack.core.types.builtin.StatusCode;
import org.eclipse.milo.opcua.stack.core.types.builtin.Variant;
import org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.UByte;
import org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.UInteger;
import org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.ULong;
import org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.UShort;
import org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.Unsigned;

import java.lang.reflect.Array;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/**
 * Conversions between OPC UA values and the southbound JSON contract: a {@code DataValue} → a
 * contract {@code sample} (value + normalized quality + timestamps), and a JSON write value → a
 * typed {@code Variant} for the node's data type. Pure/stateless.
 */
public final class ValueCodec {

    private static final Logger LOGGER = LogManager.getLogger(ValueCodec.class);

    private ValueCodec() {
    }

    /** Normalize an OPC UA status code to the contract's {@code GOOD|BAD|UNCERTAIN} quality. */
    public static Quality normalizeQuality(StatusCode sc) {
        if (sc == null) {
            return Quality.UNCERTAIN;
        }
        if (sc.isGood()) {
            return Quality.GOOD;
        }
        if (sc.isBad()) {
            return Quality.BAD;
        }
        return Quality.UNCERTAIN;
    }

    /** Build one contract {@code sample}: value, normalized quality + qualityRaw, source/server timestamps. */
    public static JsonObject toSample(DataValue value) {
        JsonObject sample = new JsonObject();
        Object v = value.getValue() != null ? value.getValue().getValue() : null;
        sample.add("value", encodeValue(v));
        StatusCode sc = value.getStatusCode();
        sample.addProperty("quality", normalizeQuality(sc).wire());
        sample.addProperty("qualityRaw", sc != null ? sc.toString() : "null");
        if (value.getSourceTime() != null) {
            sample.addProperty("sourceTs", value.getSourceTime().getJavaInstant().toString());
        }
        if (value.getServerTime() != null) {
            sample.addProperty("serverTs", value.getServerTime().getJavaInstant().toString());
        }
        return sample;
    }

    /**
     * Build one {@code data()} facade {@link SignalUpdate.Sample}: value + OPC UA's own normalized
     * quality (passed explicitly so the facade never defaults it) + native {@code qualityRaw} +
     * source/server timestamps. {@code sourceTs}/{@code serverTs} are left {@code null} when the
     * server didn't supply one — the facade defaults {@code serverTs} to now and never synthesizes
     * {@code sourceTs}.
     */
    public static SignalUpdate.Sample toSampleParts(DataValue value) {
        Object v = value.getValue() != null ? value.getValue().getValue() : null;
        StatusCode sc = value.getStatusCode();
        Quality quality = normalizeQuality(sc);
        String qualityRaw = sc != null ? sc.toString() : null;
        String sourceTs = value.getSourceTime() != null ? value.getSourceTime().getJavaInstant().toString() : null;
        String serverTs = value.getServerTime() != null ? value.getServerTime().getJavaInstant().toString() : null;
        return new SignalUpdate.Sample(sampleValue(v), quality, qualityRaw, sourceTs, serverTs);
    }

    /**
     * Render an OPC UA value to JSON: numbers (incl. unsigned) and booleans as themselves, a
     * {@link DateTime} as an ISO-8601 UTC string, an array element-wise (recursively), and anything
     * else as its string form.
     */
    private static JsonElement encodeValue(Object v) {
        if (v == null) {
            return JsonNull.INSTANCE;
        }
        if (v instanceof ByteString bytes) {
            return encodeByteString(bytes);
        }
        if (v instanceof Number) {
            return new JsonPrimitive((Number) v);
        }
        if (v instanceof Boolean) {
            return new JsonPrimitive((Boolean) v);
        }
        if (v instanceof DateTime) {
            return new JsonPrimitive(((DateTime) v).getJavaInstant().toString());
        }
        if (v.getClass().isArray()) {
            JsonArray arr = new JsonArray();
            int n = Array.getLength(v);
            for (int i = 0; i < n; i++) {
                arr.add(encodeValue(Array.get(v, i)));
            }
            return arr;
        }
        return new JsonPrimitive(v.toString());
    }

    private static Object sampleValue(Object v) {
        if (v instanceof ByteString bytes) {
            return bytes.isNull() ? JsonNull.INSTANCE : bytes.bytesOrEmpty().clone();
        }
        return encodeValue(v);
    }

    private static JsonElement encodeByteString(ByteString bytes) {
        if (bytes.isNull()) {
            return JsonNull.INSTANCE;
        }
        return Message.binaryBodyMarker(bytes.bytesOrEmpty().clone());
    }

    /**
     * The protocol-native {@code address} object for a node id: {@code {ns, namespaceUri?, nodeId}}.
     * {@code namespaceUri} (the stable identity, resolved from the namespace table) is included when
     * available so consumers are not tied to the volatile namespace index.
     */
    public static JsonObject address(NodeId nodeId, NamespaceTable namespaceTable) {
        JsonObject address = new JsonObject();
        int ns = nodeId.getNamespaceIndex().intValue();
        address.addProperty("ns", ns);
        String uri = namespaceTable != null ? namespaceTable.get(ns) : null;
        if (uri != null) {
            address.addProperty("namespaceUri", uri);
        }
        address.addProperty("nodeId", nodeId.getIdentifier().toString());
        return address;
    }

    /**
     * The OPC UA identifier type of a node id: {@code "Numeric"} | {@code "String"} | {@code "Guid"} |
     * {@code "Opaque"}. Emitted alongside the bare identifier by the discovery queries so a caller can
     * round-trip a non-string node id back through {@link #nodeId(int, String, String)}.
     */
    public static String idTypeName(NodeId nodeId) {
        Object id = nodeId.getIdentifier();
        if (id instanceof UInteger) {
            return "Numeric";
        }
        if (id instanceof UUID) {
            return "Guid";
        }
        if (id instanceof ByteString) {
            return "Opaque";
        }
        return "String";
    }

    /**
     * Rebuild a {@link NodeId} from a namespace index + bare identifier + optional {@code idType}
     * ({@code "Numeric"}/{@code "String"}/{@code "Guid"}; case-insensitive). A {@code null}/absent or
     * unrecognized {@code idType} constructs a {@code String} identifier (the historical default), so a
     * plain string-identifier request is unchanged. This is the inverse of {@link #idTypeName(NodeId)}
     * over the identifier emitted by the {@code nodes}/{@code subscriptions} queries.
     */
    public static NodeId nodeId(int ns, String identifier, String idType) {
        if ("Numeric".equalsIgnoreCase(idType)) {
            return new NodeId(ns, Unsigned.uint(Long.parseLong(identifier)));
        }
        if ("Guid".equalsIgnoreCase(idType)) {
            return new NodeId(ns, UUID.fromString(identifier));
        }
        // "String" (default) and "Opaque"/unknown: construct a String identifier.
        return new NodeId(ns, identifier);
    }

    /**
     * Coerce a JSON write value to a {@link Variant} of the node's data type, or {@code null} if
     * unsupported. A JSON array is written as a typed OPC UA array of {@code targetType} (the node's
     * element type); a scalar is written as that type. {@code DateTime} accepts an ISO-8601 string.
     */
    public static Variant variantFromValue(NodeId targetType, JsonElement value) {
        try {
            if (value != null && value.isJsonArray()) {
                return arrayVariant(targetType, value.getAsJsonArray());
            }
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
            } else if (targetType.equals(NodeIds.DateTime)) {
                return new Variant(parseDateTime(value.getAsString()));
            }
            LOGGER.warn("unsupported write target type {}", targetType);
            return null;
        } catch (Exception e) {
            LOGGER.error("value coercion failed for type {}: {}", targetType, e.toString());
            return null;
        }
    }

    /** Build a typed OPC UA array {@link Variant} of {@code elementType} from a JSON array, or null. */
    private static Variant arrayVariant(NodeId elementType, JsonArray a) {
        int n = a.size();
        if (elementType.equals(NodeIds.Boolean)) {
            Boolean[] x = new Boolean[n];
            for (int i = 0; i < n; i++) x[i] = a.get(i).getAsBoolean();
            return new Variant(x);
        } else if (elementType.equals(NodeIds.SByte)) {
            Byte[] x = new Byte[n];
            for (int i = 0; i < n; i++) x[i] = (byte) a.get(i).getAsInt();
            return new Variant(x);
        } else if (elementType.equals(NodeIds.Byte)) {
            UByte[] x = new UByte[n];
            for (int i = 0; i < n; i++) x[i] = Unsigned.ubyte(a.get(i).getAsInt());
            return new Variant(x);
        } else if (elementType.equals(NodeIds.Int16)) {
            Short[] x = new Short[n];
            for (int i = 0; i < n; i++) x[i] = a.get(i).getAsShort();
            return new Variant(x);
        } else if (elementType.equals(NodeIds.UInt16)) {
            UShort[] x = new UShort[n];
            for (int i = 0; i < n; i++) x[i] = Unsigned.ushort(a.get(i).getAsInt());
            return new Variant(x);
        } else if (elementType.equals(NodeIds.Int32)) {
            Integer[] x = new Integer[n];
            for (int i = 0; i < n; i++) x[i] = a.get(i).getAsInt();
            return new Variant(x);
        } else if (elementType.equals(NodeIds.UInt32)) {
            UInteger[] x = new UInteger[n];
            for (int i = 0; i < n; i++) x[i] = Unsigned.uint(a.get(i).getAsLong());
            return new Variant(x);
        } else if (elementType.equals(NodeIds.Int64)) {
            Long[] x = new Long[n];
            for (int i = 0; i < n; i++) x[i] = a.get(i).getAsLong();
            return new Variant(x);
        } else if (elementType.equals(NodeIds.UInt64)) {
            ULong[] x = new ULong[n];
            for (int i = 0; i < n; i++) x[i] = Unsigned.ulong(a.get(i).getAsLong());
            return new Variant(x);
        } else if (elementType.equals(NodeIds.Float)) {
            Float[] x = new Float[n];
            for (int i = 0; i < n; i++) x[i] = a.get(i).getAsFloat();
            return new Variant(x);
        } else if (elementType.equals(NodeIds.Double)) {
            Double[] x = new Double[n];
            for (int i = 0; i < n; i++) x[i] = a.get(i).getAsDouble();
            return new Variant(x);
        } else if (elementType.equals(NodeIds.String)) {
            String[] x = new String[n];
            for (int i = 0; i < n; i++) x[i] = a.get(i).getAsString();
            return new Variant(x);
        } else if (elementType.equals(NodeIds.DateTime)) {
            DateTime[] x = new DateTime[n];
            for (int i = 0; i < n; i++) x[i] = parseDateTime(a.get(i).getAsString());
            return new Variant(x);
        }
        LOGGER.warn("unsupported write array element type {}", elementType);
        return null;
    }

    private static DateTime parseDateTime(String iso) {
        return new DateTime(Instant.from(DateTimeFormatter.ISO_INSTANT.parse(iso)));
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
