package com.mbreissi.edgecommons.opcua.opc;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mbreissi.edgecommons.facades.Quality;
import com.mbreissi.edgecommons.facades.SignalUpdate;
import com.mbreissi.edgecommons.messaging.Message;
import com.mbreissi.edgecommons.messaging.MessageBuilder;
import org.eclipse.milo.opcua.stack.core.types.builtin.ByteString;
import org.eclipse.milo.opcua.stack.core.types.builtin.DataValue;
import org.eclipse.milo.opcua.stack.core.types.builtin.DateTime;
import org.eclipse.milo.opcua.stack.core.types.builtin.StatusCode;
import org.eclipse.milo.opcua.stack.core.types.builtin.Variant;
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

        SignalUpdate.Sample sample = ValueCodec.toSampleParts(value);

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

        SignalUpdate.Sample sample = ValueCodec.toSampleParts(value);
        JsonObject json = ValueCodec.toSample(value);

        assertNull(sample.sourceTs());
        assertEquals(server.toString(), sample.serverTs());
        assertFalse(json.has("sourceTs"));
        assertEquals(server.toString(), json.get("serverTs").getAsString());
    }

    @Test
    void toSampleUsesCoreBinaryMarkerForByteStringJsonBodies() {
        byte[] payload = new byte[]{0x10, 0x20, 0x30};
        DataValue value = dataValue(ByteString.of(payload), Instant.parse("2026-07-06T17:59:59Z"),
                Instant.parse("2026-07-06T18:00:00Z"));

        JsonObject sample = ValueCodec.toSample(value);
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
        samples.add(ValueCodec.toSample(value));
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

    private static DataValue dataValue(ByteString value, Instant source, Instant server) {
        return new DataValue(new Variant(value), StatusCode.GOOD, new DateTime(source), new DateTime(server));
    }
}
