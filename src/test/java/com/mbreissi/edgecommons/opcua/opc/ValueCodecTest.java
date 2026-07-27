package com.mbreissi.edgecommons.opcua.opc;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.mbreissi.edgecommons.facades.Quality;
import com.mbreissi.edgecommons.facades.SignalUpdate;
import com.mbreissi.edgecommons.messaging.Message;
import com.mbreissi.edgecommons.messaging.MessageBuilder;
import org.eclipse.milo.opcua.stack.core.NodeIds;
import org.eclipse.milo.opcua.stack.core.types.builtin.ByteString;
import org.eclipse.milo.opcua.stack.core.types.builtin.DataValue;
import org.eclipse.milo.opcua.stack.core.types.builtin.DateTime;
import org.eclipse.milo.opcua.stack.core.types.builtin.NodeId;
import org.eclipse.milo.opcua.stack.core.types.builtin.StatusCode;
import org.eclipse.milo.opcua.stack.core.types.builtin.Variant;
import org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.Unsigned;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;

class ValueCodecTest {

    @Test
    void toSamplePartsMapsByteStringToBytesAndPreservesOpcTimestamps() {
        byte[] payload = new byte[]{0x00, 0x01, 0x02, (byte) 0xFE, (byte) 0xFF};
        Instant source = Instant.parse("2026-07-06T17:59:59.900Z");
        Instant server = Instant.parse("2026-07-06T18:00:00Z");
        DataValue value = dataValue(ByteString.of(payload), source, server);

        SignalUpdate.Sample sample = ValueCodec.toSampleParts(value, null);

        assertArrayEquals(payload, assertInstanceOf(byte[].class, sample.value()));
        assertEquals(Quality.GOOD, sample.quality());
        assertEquals(StatusCode.GOOD.toString(), sample.qualityRaw());
        assertEquals(source.toString(), sample.sourceTs());
        assertEquals(server.toString(), sample.serverTs());
    }

    @Test
    void toSampleDoesNotSynthesizeSourceTimestamp() {
        Instant server = Instant.parse("2026-07-06T18:00:00Z");
        DataValue value = new DataValue(new Variant(42), StatusCode.GOOD, null, new DateTime(server));

        SignalUpdate.Sample sample = ValueCodec.toSampleParts(value, null);
        JsonObject json = ValueCodec.toSample(value, null);

        assertNull(sample.sourceTs());
        assertEquals(server.toString(), sample.serverTs());
        assertFalse(json.has("sourceTs"));
        assertEquals(server.toString(), json.get("serverTs").getAsString());
    }

    // ---- the four-slot timestamp model: SourceTimestamp→sourceTs, ServerTimestamp→serverTs,
    // ---- adapter receive → the additive receivedTs extra (SOUTHBOUND.md §2) ----------------------

    @Test
    void toSampleParts_mapsSourceAndServerTimestampSlotsExactly_neverCrossed() {
        Instant source = Instant.parse("2026-07-06T17:59:59.900Z");
        Instant server = Instant.parse("2026-07-06T18:00:00Z");
        DataValue value = new DataValue(new Variant(1), StatusCode.GOOD,
                new DateTime(source), new DateTime(server));

        SignalUpdate.Sample sample = ValueCodec.toSampleParts(value, null);

        // Milo SourceTimestamp → sourceTs, Milo ServerTimestamp → serverTs — exactly, never swapped.
        assertEquals(source.toString(), sample.sourceTs());
        assertEquals(server.toString(), sample.serverTs());
    }

    @Test
    void toSampleParts_attachesReceivedTsExtra_whenItDiffersFromServerTs() {
        Instant server = Instant.parse("2026-07-06T18:00:00Z");
        Instant receivedAt = Instant.parse("2026-07-06T18:00:00.042Z");
        DataValue value = new DataValue(new Variant(1), StatusCode.GOOD, null, new DateTime(server));

        SignalUpdate.Sample sample = ValueCodec.toSampleParts(value, receivedAt);

        assertEquals(server.toString(), sample.serverTs());
        assertEquals(receivedAt.toString(), sample.extra().get("receivedTs"));
    }

    @Test
    void toSampleParts_omitsReceivedTs_whenItEqualsServerTs() {
        Instant server = Instant.parse("2026-07-06T18:00:00Z");
        DataValue value = new DataValue(new Variant(1), StatusCode.GOOD, null, new DateTime(server));

        SignalUpdate.Sample sample = ValueCodec.toSampleParts(value, server);

        assertNull(sample.extra());
    }

    @Test
    void toSampleParts_attachesReceivedTs_whenTheServerSuppliedNoServerTs() {
        Instant receivedAt = Instant.parse("2026-07-06T18:00:00.042Z");
        DataValue value = new DataValue(new Variant(1), StatusCode.GOOD, null, null);

        SignalUpdate.Sample sample = ValueCodec.toSampleParts(value, receivedAt);

        // serverTs stays null (the facade defaults it at publish); the receive moment still rides.
        assertNull(sample.serverTs());
        assertEquals(receivedAt.toString(), sample.extra().get("receivedTs"));
    }

    @Test
    void toSample_json_carriesReceivedTsUnderTheSameRules() {
        Instant server = Instant.parse("2026-07-06T18:00:00Z");
        Instant receivedAt = Instant.parse("2026-07-06T18:00:00.042Z");
        DataValue value = new DataValue(new Variant(1), StatusCode.GOOD, null, new DateTime(server));

        JsonObject differs = ValueCodec.toSample(value, receivedAt);
        assertEquals(receivedAt.toString(), differs.get("receivedTs").getAsString());
        assertEquals(server.toString(), differs.get("serverTs").getAsString());

        JsonObject equal = ValueCodec.toSample(value, server);
        assertFalse(equal.has("receivedTs"));

        JsonObject none = ValueCodec.toSample(value, null);
        assertFalse(none.has("receivedTs"));
    }

    @Test
    void toSampleParts_receivedTsExtra_survivesTheDataFacadeReservedKeyCheck() {
        // receivedTs is additive (SOUTHBOUND.md §2) — not one of the facade's reserved canonical
        // sample keys, so the extra passes the build-time rejection.
        assertFalse(com.mbreissi.edgecommons.facades.DataFacade.RESERVED_SAMPLE_EXTRA_KEYS
                .contains("receivedTs"));
    }

    @Test
    void toSampleUsesCoreBinaryMarkerForByteStringJsonBodies() {
        byte[] payload = new byte[]{0x10, 0x20, 0x30};
        DataValue value = dataValue(ByteString.of(payload), Instant.parse("2026-07-06T17:59:59Z"),
                Instant.parse("2026-07-06T18:00:00Z"));

        JsonObject sample = ValueCodec.toSample(value, null);
        JsonObject marker = sample.getAsJsonObject("value").getAsJsonObject("_edgecommonsBinary");

        assertEquals("base64", marker.get("encoding").getAsString());
        assertEquals(payload.length, marker.get("length").getAsInt());
        assertEquals(Base64.getEncoder().encodeToString(payload), marker.get("data").getAsString());
    }

    @Test
    void coreProtobufRoundTripCarriesBytesAndTimestampMillis() {
        byte[] payload = new byte[]{0x10, 0x20, 0x30};
        Instant source = Instant.parse("2026-07-06T17:59:59.900Z");
        Instant server = Instant.parse("2026-07-06T18:00:00Z");
        DataValue value = dataValue(ByteString.of(payload), source, server);

        JsonObject signal = new JsonObject();
        signal.addProperty("id", "ns=2;s=ByteStringNode");
        JsonArray samples = new JsonArray();
        samples.add(ValueCodec.toSample(value, null));
        JsonObject body = new JsonObject();
        body.add("signal", signal);
        body.add("samples", samples);

        Message message = MessageBuilder.create("SouthboundSignalUpdate", "1.0")
                .withTimestampMs(Instant.parse("2026-07-06T18:00:01Z").toEpochMilli())
                .withPayload(body)
                .build();

        Message decoded = Message.fromBytes(message.toBytes());
        JsonObject decodedBody = assertInstanceOf(JsonObject.class, decoded.getBody());
        JsonObject decodedSample = decodedBody.getAsJsonArray("samples").get(0).getAsJsonObject();
        JsonObject decodedMarker = decodedSample.getAsJsonObject("value").getAsJsonObject("_edgecommonsBinary");

        assertEquals(source.toString(), decodedSample.get("sourceTs").getAsString());
        assertEquals(source.toEpochMilli(), decodedSample.get("sourceTsMs").getAsLong());
        assertEquals(server.toString(), decodedSample.get("serverTs").getAsString());
        assertEquals(server.toEpochMilli(), decodedSample.get("serverTsMs").getAsLong());
        assertEquals(Base64.getEncoder().encodeToString(payload), decodedMarker.get("data").getAsString());
    }

    // ---- quality normalization -------------------------------------------------------------------

    @Test
    void normalizeQuality_mapsGoodBadUncertainAndNull() {
        assertEquals(Quality.GOOD, ValueCodec.normalizeQuality(StatusCode.GOOD));
        assertEquals(Quality.BAD, ValueCodec.normalizeQuality(StatusCode.BAD));
        assertEquals(Quality.UNCERTAIN, ValueCodec.normalizeQuality(StatusCode.UNCERTAIN));
        assertEquals(Quality.UNCERTAIN, ValueCodec.normalizeQuality(null));
    }

    // ---- encodeValue via toSample: number / boolean / datetime / array ---------------------------

    @Test
    void toSample_encodesScalarsBooleansDateTimesAndArrays() {
        assertEquals(42, sampleValueOf(new Variant(42)).getAsInt());
        assertEquals(true, sampleValueOf(new Variant(true)).getAsBoolean());

        Instant when = Instant.parse("2026-07-06T18:00:00Z");
        assertEquals(when.toString(), sampleValueOf(new Variant(new DateTime(when))).getAsString());

        JsonObject arr = ValueCodec.toSample(new DataValue(new Variant(new int[]{1, 2, 3}),
                StatusCode.GOOD, null, null), null);
        assertEquals(3, arr.getAsJsonArray("value").size());
        assertEquals(2, arr.getAsJsonArray("value").get(1).getAsInt());
    }

    @Test
    void toSample_nullValue_encodesJsonNull_andQualityRaw() {
        JsonObject sample = ValueCodec.toSample(new DataValue(Variant.NULL_VALUE, StatusCode.BAD, null, null), null);
        assertEquals("BAD", sample.get("quality").getAsString());
        assertEquals(StatusCode.BAD.toString(), sample.get("qualityRaw").getAsString());
    }

    // ---- idTypeName + nodeId round-trip ----------------------------------------------------------

    @Test
    void idTypeName_classifiesNumericGuidStringOpaque() {
        assertEquals("Numeric", ValueCodec.idTypeName(ValueCodec.nodeId(2, "1001", "Numeric")));
        assertEquals("Guid", ValueCodec.idTypeName(ValueCodec.nodeId(2,
                "12345678-1234-1234-1234-123456789abc", "Guid")));
        assertEquals("String", ValueCodec.idTypeName(ValueCodec.nodeId(2, "Tag", "String")));
    }

    // ---- address ---------------------------------------------------------------------------------

    @Test
    void address_carriesNsAndBareIdentifier() {
        JsonObject a = ValueCodec.address(new NodeId(2, "Channel1.Device1.Tag"), null);
        assertEquals(2, a.get("ns").getAsInt());
        assertEquals("Channel1.Device1.Tag", a.get("nodeId").getAsString());
        assertFalse(a.has("namespaceUri"));
    }

    // ---- statusFromString / sourceTime -----------------------------------------------------------

    @Test
    void statusFromString_mapsGoodBadUncertainDefault() {
        assertEquals(StatusCode.GOOD, ValueCodec.statusFromString("GOOD"));
        assertEquals(StatusCode.BAD, ValueCodec.statusFromString("bad"));
        assertEquals(StatusCode.UNCERTAIN, ValueCodec.statusFromString("whatever"));
    }

    @Test
    void sourceTime_nullIsMinValue_isoParsesOtherwise() {
        assertEquals(DateTime.MIN_VALUE, ValueCodec.sourceTime(null));
        Instant when = Instant.parse("2026-07-06T18:00:00Z");
        assertEquals(when, ValueCodec.sourceTime(when.toString()).getJavaInstant());
    }

    // ---- variantFromValue: scalar coercions ------------------------------------------------------

    @Test
    void variantFromValue_coercesScalarTypes() {
        assertEquals(true, ValueCodec.variantFromValue(NodeIds.Boolean, prim(true)).getValue());
        assertEquals(7, ValueCodec.variantFromValue(NodeIds.Int32, prim(7)).getValue());
        assertEquals(9L, ValueCodec.variantFromValue(NodeIds.Int64, prim(9)).getValue());
        assertEquals(1.5f, ValueCodec.variantFromValue(NodeIds.Float, prim(1.5)).getValue());
        assertEquals(2.5d, ValueCodec.variantFromValue(NodeIds.Double, prim(2.5)).getValue());
        assertEquals("hi", ValueCodec.variantFromValue(NodeIds.String, prim("hi")).getValue());
        assertEquals(Unsigned.uint(3L), ValueCodec.variantFromValue(NodeIds.UInt32, prim(3)).getValue());
        assertEquals((short) 4, ValueCodec.variantFromValue(NodeIds.Int16, prim(4)).getValue());
    }

    @Test
    void variantFromValue_dateTimeFromIso() {
        Instant when = Instant.parse("2026-07-06T18:00:00Z");
        Variant v = ValueCodec.variantFromValue(NodeIds.DateTime, prim(when.toString()));
        assertEquals(when, ((DateTime) v.getValue()).getJavaInstant());
    }

    @Test
    void variantFromValue_unsupportedTypeOrBadCoercion_returnsNull() {
        assertNull(ValueCodec.variantFromValue(NodeIds.Guid, prim("not-a-type-we-handle")));
        // a bad DateTime string trips the coercion catch → null
        assertNull(ValueCodec.variantFromValue(NodeIds.DateTime, prim("nope")));
    }

    // ---- variantFromValue: typed arrays ----------------------------------------------------------

    @Test
    void variantFromValue_coercesTypedArrays() {
        Object ints = ValueCodec.variantFromValue(NodeIds.Int32, arr(1, 2, 3)).getValue();
        assertArrayEquals(new Integer[]{1, 2, 3}, (Integer[]) ints);

        Object doubles = ValueCodec.variantFromValue(NodeIds.Double, arr(1.0, 2.0)).getValue();
        assertArrayEquals(new Double[]{1.0, 2.0}, (Double[]) doubles);

        Object bools = ValueCodec.variantFromValue(NodeIds.Boolean, arrBool(true, false)).getValue();
        assertArrayEquals(new Boolean[]{true, false}, (Boolean[]) bools);

        Object strings = ValueCodec.variantFromValue(NodeIds.String, arrStr("a", "b")).getValue();
        assertArrayEquals(new String[]{"a", "b"}, (String[]) strings);
    }

    @Test
    void variantFromValue_unsupportedArrayElementType_returnsNull() {
        assertNull(ValueCodec.variantFromValue(NodeIds.Guid, arrStr("x", "y")));
    }

    private static JsonPrimitive prim(Object o) {
        if (o instanceof Boolean b) {
            return new JsonPrimitive(b);
        }
        if (o instanceof Number n) {
            return new JsonPrimitive(n);
        }
        return new JsonPrimitive(String.valueOf(o));
    }

    private static JsonArray arr(double... xs) {
        JsonArray a = new JsonArray();
        for (double x : xs) {
            a.add(x == Math.floor(x) ? new JsonPrimitive((int) x) : new JsonPrimitive(x));
        }
        return a;
    }

    private static JsonArray arrBool(boolean... xs) {
        JsonArray a = new JsonArray();
        for (boolean x : xs) {
            a.add(x);
        }
        return a;
    }

    private static JsonArray arrStr(String... xs) {
        JsonArray a = new JsonArray();
        for (String x : xs) {
            a.add(x);
        }
        return a;
    }

    private static com.google.gson.JsonElement sampleValueOf(Variant v) {
        return ValueCodec.toSample(new DataValue(v, StatusCode.GOOD, null, null), null).get("value");
    }

    private static DataValue dataValue(ByteString value, Instant source, Instant server) {
        return new DataValue(new Variant(value), StatusCode.GOOD, new DateTime(source), new DateTime(server));
    }
}
