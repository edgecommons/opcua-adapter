package com.mbreissi.opcua.opcuaadapter.opc;

import com.google.gson.JsonObject;
import org.eclipse.milo.opcua.stack.core.types.builtin.StatusCode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommandJsonTest {

    // ---- nodeEntry ------------------------------------------------------------------------------

    @Test
    void nodeEntry_includesAllFieldsWhenPresent() {
        JsonObject entry = CommandJson.nodeEntry(2, "urn:kepware:KEPServerEX", "Channel1.Device1.Tag1", "String", "Tag1", "Float");

        assertEquals("Channel1.Device1.Tag1", entry.get("signalId").getAsString());
        assertEquals(2, entry.get("namespace").getAsInt());
        assertEquals("String", entry.get("idType").getAsString());
        assertEquals("urn:kepware:KEPServerEX", entry.get("namespaceUri").getAsString());
        assertEquals("Tag1", entry.get("name").getAsString());
        assertEquals("Float", entry.get("dataType").getAsString());
    }

    @Test
    void nodeEntry_omitsOptionalFieldsWhenNull() {
        JsonObject entry = CommandJson.nodeEntry(0, null, "SomeId", null, null, null);

        assertEquals("SomeId", entry.get("signalId").getAsString());
        assertEquals(0, entry.get("namespace").getAsInt());
        assertFalse(entry.has("idType"));
        assertFalse(entry.has("namespaceUri"));
        assertFalse(entry.has("name"));
        assertFalse(entry.has("dataType"));
    }

    // ---- writeRequested ---------------------------------------------------------------------------

    @Test
    void writeRequested_includesOnlyProvidedFields() {
        JsonObject coords = CommandJson.writeRequested("Setpoint", 2, "urn:kepware:KEPServerEX");
        assertEquals("Setpoint", coords.get("signalId").getAsString());
        assertEquals(2, coords.get("ns").getAsInt());
        assertEquals("urn:kepware:KEPServerEX", coords.get("namespaceUri").getAsString());

        JsonObject partial = CommandJson.writeRequested(null, null, null);
        assertFalse(partial.has("signalId"));
        assertFalse(partial.has("ns"));
        assertFalse(partial.has("namespaceUri"));
    }

    // ---- applyWriteStatus / applyWriteFailed -------------------------------------------------------

    @Test
    void applyWriteStatus_good_setsSuccessAndNoMessage() {
        JsonObject entry = new JsonObject();
        CommandJson.applyWriteStatus(entry, StatusCode.GOOD);

        assertEquals("SUCCESS", entry.get("status").getAsString());
        assertTrue(entry.has("qualityRaw"));
        assertFalse(entry.has("message"));
    }

    @Test
    void applyWriteStatus_bad_setsFailedWithMessageAndQualityRaw() {
        JsonObject entry = new JsonObject();
        CommandJson.applyWriteStatus(entry, StatusCode.BAD);

        assertEquals("FAILED", entry.get("status").getAsString());
        assertTrue(entry.has("qualityRaw"));
        assertTrue(entry.has("message"));
        assertTrue(entry.get("message").getAsString().contains("write returned"));
    }

    @Test
    void applyWriteFailed_setsFailedAndMessage() {
        JsonObject entry = new JsonObject();
        CommandJson.applyWriteFailed(entry, "missing value");

        assertEquals("FAILED", entry.get("status").getAsString());
        assertEquals("missing value", entry.get("message").getAsString());
    }

    // ---- applyBatchResults ------------------------------------------------------------------------

    @Test
    void applyBatchResults_landsEachStatusOnItsOriginatingEntry_skippingPreFailed() {
        // entry 0 was skipped during preflight (already FAILED); entries 1 and 2 were batched, so
        // batchToEntry = [1, 2] and results correspond to positions 1 and 2 respectively.
        JsonObject e0 = new JsonObject();
        CommandJson.applyWriteFailed(e0, "missing value");
        JsonObject e1 = new JsonObject();
        JsonObject e2 = new JsonObject();
        List<JsonObject> perEntry = new ArrayList<>(List.of(e0, e1, e2));
        List<Integer> batchToEntry = List.of(1, 2);
        List<StatusCode> results = List.of(StatusCode.GOOD, StatusCode.BAD);

        CommandJson.applyBatchResults(perEntry, batchToEntry, results);

        // Pre-failed entry is untouched.
        assertEquals("FAILED", e0.get("status").getAsString());
        assertEquals("missing value", e0.get("message").getAsString());
        // Batched entries get their own result, in order.
        assertEquals("SUCCESS", e1.get("status").getAsString());
        assertFalse(e1.has("message"));
        assertEquals("FAILED", e2.get("status").getAsString());
        assertTrue(e2.get("message").getAsString().contains("write returned"));
    }
}
