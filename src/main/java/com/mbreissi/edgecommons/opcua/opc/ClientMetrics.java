package com.mbreissi.edgecommons.opcua.opc;

import com.google.gson.JsonObject;

import java.util.LinkedHashMap;
import java.util.Map;

/** Lightweight per-instance counters, surfaced in status replies and OPC UA operational metrics. */
public class ClientMetrics {

    private long subscribedReadTotal = 0L;
    private long subscribedReadInterval = 0L;
    private long readRequestTotal = 0L;
    private long readRequestInterval = 0L;
    private long writeRequestTotal = 0L;
    private long writeRequestInterval = 0L;
    private long readFailureTotal = 0L;
    private long readFailureInterval = 0L;
    private long writeFailureTotal = 0L;
    private long writeFailureInterval = 0L;
    private long browseRequestTotal = 0L;
    private long browseRequestInterval = 0L;
    private long browseFailureTotal = 0L;
    private long browseFailureInterval = 0L;
    private long browseReferenceTotal = 0L;
    private long browseReferenceInterval = 0L;
    private long browseTruncatedTotal = 0L;
    private long browseTruncatedInterval = 0L;
    private long subscriptionRecreateTotal = 0L;
    private long subscriptionRecreateInterval = 0L;
    private long connectionAttemptTotal = 0L;
    private long connectionAttemptInterval = 0L;
    private long connectionFailureTotal = 0L;
    private long connectionFailureInterval = 0L;
    private long terminalFailureTotal = 0L;
    private long terminalFailureInterval = 0L;
    private long sessionDisconnectTotal = 0L;
    private long sessionDisconnectInterval = 0L;
    private long sessionReconnectTotal = 0L;
    private long sessionReconnectInterval = 0L;
    private long commandRequestTotal = 0L;
    private long commandRequestInterval = 0L;
    private long commandFailureTotal = 0L;
    private long commandFailureInterval = 0L;
    private long subscriptionCount = 0L;
    private long monitoredItemCount = 0L;
    private long readErrors = 0L;
    private long writeErrors = 0L;
    // Reconnect tally for southbound_health.reconnects (§5 optional), drained by HealthMetrics on its own
    // cadence — kept separate from sessionReconnectInterval (drained by the OpcUaConnection family) so the
    // two emitters never race on one counter.
    private long healthReconnectInterval = 0L;

    public synchronized void resetIntervals() {
        subscribedReadInterval = 0L;
        readRequestInterval = 0L;
        writeRequestInterval = 0L;
        readFailureInterval = 0L;
        writeFailureInterval = 0L;
        browseRequestInterval = 0L;
        browseFailureInterval = 0L;
        browseReferenceInterval = 0L;
        browseTruncatedInterval = 0L;
        subscriptionRecreateInterval = 0L;
        connectionAttemptInterval = 0L;
        connectionFailureInterval = 0L;
        terminalFailureInterval = 0L;
        sessionDisconnectInterval = 0L;
        sessionReconnectInterval = 0L;
        commandRequestInterval = 0L;
        commandFailureInterval = 0L;
    }

    public synchronized void recordSubscribedRead() {
        subscribedReadTotal++;
        subscribedReadInterval++;
    }

    public synchronized void recordReadRequest() {
        readRequestTotal++;
        readRequestInterval++;
    }

    public synchronized void recordWriteRequest() {
        writeRequestTotal++;
        writeRequestInterval++;
    }

    public synchronized void recordReadFailure() {
        readFailureTotal++;
        readFailureInterval++;
    }

    public synchronized void recordWriteFailure() {
        writeFailureTotal++;
        writeFailureInterval++;
    }

    public synchronized void recordBrowseRequest() {
        browseRequestTotal++;
        browseRequestInterval++;
    }

    public synchronized void recordBrowseFailure() {
        browseFailureTotal++;
        browseFailureInterval++;
    }

    public synchronized void recordBrowseResult(long references, boolean truncated) {
        browseReferenceTotal += references;
        browseReferenceInterval += references;
        if (truncated) {
            browseTruncatedTotal++;
            browseTruncatedInterval++;
        }
    }

    public synchronized void recordSubscriptionRecreate() {
        subscriptionRecreateTotal++;
        subscriptionRecreateInterval++;
    }

    public synchronized void setSubscriptionShape(long subscriptionCount, long monitoredItemCount) {
        this.subscriptionCount = Math.max(0L, subscriptionCount);
        this.monitoredItemCount = Math.max(0L, monitoredItemCount);
    }

    /** The resolved monitored-item count — the {@code southbound_health.signalsSubscribed} gauge source. */
    public synchronized long getMonitoredItemCount() {
        return monitoredItemCount;
    }

    public synchronized void recordConnectionAttempt() {
        connectionAttemptTotal++;
        connectionAttemptInterval++;
    }

    public synchronized void recordConnectionFailure() {
        connectionFailureTotal++;
        connectionFailureInterval++;
    }

    public synchronized void recordTerminalFailure() {
        terminalFailureTotal++;
        terminalFailureInterval++;
    }

    public synchronized void recordSessionDisconnect() {
        sessionDisconnectTotal++;
        sessionDisconnectInterval++;
    }

    public synchronized void recordSessionReconnect() {
        sessionReconnectTotal++;
        sessionReconnectInterval++;
        healthReconnectInterval++;
    }

    /** Record one {@code sb/*} command outcome for the {@code OpcUaCommand} family's command counters. */
    public synchronized void recordCommand(boolean ok) {
        commandRequestTotal++;
        commandRequestInterval++;
        if (!ok) {
            commandFailureTotal++;
            commandFailureInterval++;
        }
    }

    /** Returns and resets the reconnect count for {@code southbound_health.reconnects} (interval measure). */
    public synchronized long getIntervalReconnects() {
        long r = healthReconnectInterval;
        healthReconnectInterval = 0L;
        return r;
    }

    /**
     * Compatibility alias for older internal code paths. New code should classify the source of the
     * read and call either {@link #recordSubscribedRead()} or {@link #recordReadRequest()}.
     */
    @Deprecated
    public synchronized void incrementReadMetrics() {
        recordReadRequest();
    }

    /** Compatibility alias for older internal code paths. */
    @Deprecated
    public synchronized void incrementWriteMetrics() {
        recordWriteRequest();
    }

    public synchronized void incrementReadErrors() {
        readErrors++;
    }

    /** Count one DEVICE-PATH write failure toward {@code southbound_health.writeErrors} (an entry that
     *  passed validation + the allow-list and then failed at the server or was aborted by an unavailable
     *  session — never a policy refusal or caller error). */
    public synchronized void incrementWriteErrors() {
        writeErrors++;
    }

    public synchronized long getIntervalReadErrors() {
        long e = readErrors;
        readErrors = 0L;
        return e;
    }

    /** Returns and resets the write-error count accumulated since the last call (interval measure). */
    public synchronized long getIntervalWriteErrors() {
        long e = writeErrors;
        writeErrors = 0L;
        return e;
    }

    public synchronized JsonObject toJsonObject() {
        return snapshot().toJsonObject();
    }

    public synchronized Snapshot snapshot() {
        return new Snapshot(
                new Counter(subscribedReadTotal, subscribedReadInterval),
                new Counter(readRequestTotal, readRequestInterval),
                new Counter(writeRequestTotal, writeRequestInterval),
                new Counter(readFailureTotal, readFailureInterval),
                new Counter(writeFailureTotal, writeFailureInterval),
                new Counter(browseRequestTotal, browseRequestInterval),
                new Counter(browseFailureTotal, browseFailureInterval),
                new Counter(browseReferenceTotal, browseReferenceInterval),
                new Counter(browseTruncatedTotal, browseTruncatedInterval),
                new Counter(subscriptionRecreateTotal, subscriptionRecreateInterval),
                new Counter(connectionAttemptTotal, connectionAttemptInterval),
                new Counter(connectionFailureTotal, connectionFailureInterval),
                new Counter(terminalFailureTotal, terminalFailureInterval),
                new Counter(sessionDisconnectTotal, sessionDisconnectInterval),
                new Counter(sessionReconnectTotal, sessionReconnectInterval),
                new Counter(commandRequestTotal, commandRequestInterval),
                new Counter(commandFailureTotal, commandFailureInterval),
                subscriptionCount,
                monitoredItemCount);
    }

    public synchronized Snapshot snapshotAndResetIntervals() {
        Snapshot snapshot = snapshot();
        resetIntervals();
        return snapshot;
    }

    public record Counter(long total, long interval) {
        JsonObject toJsonObject() {
            JsonObject retVal = new JsonObject();
            retVal.addProperty("total", total);
            retVal.addProperty("interval", interval);
            return retVal;
        }
    }

    public record Snapshot(
            Counter subscribedRead,
            Counter readRequest,
            Counter writeRequest,
            Counter readFailure,
            Counter writeFailure,
            Counter browseRequest,
            Counter browseFailure,
            Counter browseReference,
            Counter browseTruncated,
            Counter subscriptionRecreate,
            Counter connectionAttempt,
            Counter connectionFailure,
            Counter terminalFailure,
            Counter sessionDisconnect,
            Counter sessionReconnect,
            Counter commandRequest,
            Counter commandFailure,
            long subscriptionCount,
            long monitoredItemCount) {

        JsonObject toJsonObject() {
            JsonObject retVal = new JsonObject();
            retVal.add("subscribedRead", subscribedRead.toJsonObject());
            retVal.add("readRequest", readRequest.toJsonObject());
            retVal.add("writeRequest", writeRequest.toJsonObject());
            retVal.add("readFailure", readFailure.toJsonObject());
            retVal.add("writeFailure", writeFailure.toJsonObject());
            retVal.add("browseRequest", browseRequest.toJsonObject());
            retVal.add("browseFailure", browseFailure.toJsonObject());
            retVal.add("browseReference", browseReference.toJsonObject());
            retVal.add("browseTruncated", browseTruncated.toJsonObject());
            retVal.add("subscriptionRecreate", subscriptionRecreate.toJsonObject());
            retVal.add("connectionAttempt", connectionAttempt.toJsonObject());
            retVal.add("connectionFailure", connectionFailure.toJsonObject());
            retVal.add("terminalFailure", terminalFailure.toJsonObject());
            retVal.add("sessionDisconnect", sessionDisconnect.toJsonObject());
            retVal.add("sessionReconnect", sessionReconnect.toJsonObject());
            retVal.add("commandRequest", commandRequest.toJsonObject());
            retVal.add("commandFailure", commandFailure.toJsonObject());
            retVal.addProperty("subscriptionCount", subscriptionCount);
            retVal.addProperty("monitoredItemCount", monitoredItemCount);
            return retVal;
        }

        Map<String, Float> toCommandMeasureValues() {
            Map<String, Float> retVal = new LinkedHashMap<>();
            addMeasures(retVal, "ReadRequest", readRequest);
            addMeasures(retVal, "ReadFailure", readFailure);
            addMeasures(retVal, "WriteRequest", writeRequest);
            addMeasures(retVal, "WriteFailure", writeFailure);
            addMeasures(retVal, "CommandRequest", commandRequest);
            addMeasures(retVal, "CommandFailure", commandFailure);
            return retVal;
        }

        Map<String, Float> toSubscriptionMeasureValues() {
            Map<String, Float> retVal = new LinkedHashMap<>();
            addMeasures(retVal, "SubscribedRead", subscribedRead);
            addMeasures(retVal, "SubscriptionRecreate", subscriptionRecreate);
            retVal.put("SubscriptionCount", (float) subscriptionCount);
            retVal.put("MonitoredItemCount", (float) monitoredItemCount);
            return retVal;
        }

        Map<String, Float> toBrowseMeasureValues() {
            Map<String, Float> retVal = new LinkedHashMap<>();
            addMeasures(retVal, "BrowseRequest", browseRequest);
            addMeasures(retVal, "BrowseFailure", browseFailure);
            addMeasures(retVal, "BrowseReference", browseReference);
            addMeasures(retVal, "BrowseTruncated", browseTruncated);
            return retVal;
        }

        Map<String, Float> toConnectionMeasureValues(boolean connected) {
            Map<String, Float> retVal = new LinkedHashMap<>();
            addMeasures(retVal, "ConnectionAttempt", connectionAttempt);
            addMeasures(retVal, "ConnectionFailure", connectionFailure);
            addMeasures(retVal, "TerminalFailure", terminalFailure);
            addMeasures(retVal, "SessionDisconnect", sessionDisconnect);
            addMeasures(retVal, "SessionReconnect", sessionReconnect);
            retVal.put("SessionConnected", connected ? 1.0f : 0.0f);
            return retVal;
        }

        private static void addMeasures(Map<String, Float> measures, String name, Counter counter) {
            measures.put(name + "Total", (float) counter.total());
            measures.put(name + "Interval", (float) counter.interval());
        }
    }
}
