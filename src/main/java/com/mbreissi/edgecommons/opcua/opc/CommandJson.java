package com.mbreissi.edgecommons.opcua.opc;

import com.google.gson.JsonObject;
import org.eclipse.milo.opcua.stack.core.types.builtin.StatusCode;

import java.util.List;

/**
 * Pure JSON builders for the {@code control/nodes} query and the write acknowledgment
 * ({@code SouthboundWriteResult}). Stateless — mirrors the field style already used elsewhere in
 * {@link CommandService} (the {@code subscriptions} control query, {@code SouthboundReadResult}).
 */
public final class CommandJson {

    private CommandJson() {
    }

    /**
     * One entry of the {@code control/nodes} reply: {@code {signalId, namespace, idType,
     * namespaceUri?, name?, dataType?}}. {@code idType} ({@code Numeric}/{@code String}/{@code Guid}/
     * {@code Opaque}) lets a caller round-trip the identifier back through a read/write. Optional
     * fields are omitted (not written as {@code null}) when unavailable.
     */
    public static JsonObject nodeEntry(int ns, String namespaceUri, String signalId, String idType,
                                       String name, String dataType) {
        JsonObject entry = new JsonObject();
        entry.addProperty("signalId", signalId);
        entry.addProperty("namespace", ns);
        if (idType != null) {
            entry.addProperty("idType", idType);
        }
        if (namespaceUri != null) {
            entry.addProperty("namespaceUri", namespaceUri);
        }
        if (name != null) {
            entry.addProperty("name", name);
        }
        if (dataType != null) {
            entry.addProperty("dataType", dataType);
        }
        return entry;
    }

    /**
     * The requested coordinates of one write entry — {@code {signalId?, ns?, namespaceUri?}} — echoed
     * back (with a status applied by {@link #applyWriteStatus} / {@link #applyWriteFailed}) in the
     * {@code SouthboundWriteResult} reply.
     */
    public static JsonObject writeRequested(String signalId, Integer ns, String namespaceUri) {
        JsonObject coords = new JsonObject();
        if (signalId != null) {
            coords.addProperty("signalId", signalId);
        }
        if (ns != null) {
            coords.addProperty("ns", ns);
        }
        if (namespaceUri != null) {
            coords.addProperty("namespaceUri", namespaceUri);
        }
        return coords;
    }

    /**
     * Apply the {@link StatusCode}s from a batch {@code writeValues} call back onto the per-request
     * result entries. {@code batchToEntry.get(j)} is the {@code perEntry} index that batch position
     * {@code j} originated from — so an entry skipped during preflight (already {@code FAILED}) is left
     * untouched and each server status lands on its originating entry, in order.
     */
    public static void applyBatchResults(List<JsonObject> perEntry, List<Integer> batchToEntry,
                                         List<StatusCode> results) {
        for (int j = 0; j < results.size(); j++) {
            applyWriteStatus(perEntry.get(batchToEntry.get(j)), results.get(j));
        }
    }

    /** Apply a completed write's result {@link StatusCode} to its result entry. */
    public static void applyWriteStatus(JsonObject entry, StatusCode sc) {
        entry.addProperty("status", sc.isGood() ? "SUCCESS" : "FAILED");
        entry.addProperty("qualityRaw", sc.toString());
        if (!sc.isGood()) {
            entry.addProperty("message", "write returned " + sc);
        }
    }

    /** Mark a write entry as failed before it was ever issued (e.g. missing value, unresolvable node). */
    public static void applyWriteFailed(JsonObject entry, String message) {
        entry.addProperty("status", "FAILED");
        entry.addProperty("message", message);
    }
}
