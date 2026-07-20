package com.mbreissi.edgecommons.opcua.opc;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ClientMetricsTest {

    @Test
    void statusJsonKeepsCountersAndAddsOperationalCounters() {
        ClientMetrics metrics = new ClientMetrics();
        metrics.recordSubscribedRead();
        metrics.recordSubscribedRead();
        metrics.recordReadRequest();
        metrics.recordWriteRequest();
        metrics.recordReadFailure();
        metrics.recordWriteFailure();
        metrics.recordBrowseRequest();
        metrics.recordBrowseResult(3, true);
        metrics.recordSubscriptionRecreate();
        metrics.recordConnectionAttempt();
        metrics.setSubscriptionShape(2, 7);

        JsonObject json = metrics.toJsonObject();

        assertCounter(json.getAsJsonObject("subscribedRead"), 2, 2);
        assertCounter(json.getAsJsonObject("readRequest"), 1, 1);
        assertCounter(json.getAsJsonObject("writeRequest"), 1, 1);
        assertCounter(json.getAsJsonObject("readFailure"), 1, 1);
        assertCounter(json.getAsJsonObject("writeFailure"), 1, 1);
        assertCounter(json.getAsJsonObject("browseRequest"), 1, 1);
        assertCounter(json.getAsJsonObject("browseReference"), 3, 3);
        assertCounter(json.getAsJsonObject("browseTruncated"), 1, 1);
        assertCounter(json.getAsJsonObject("subscriptionRecreate"), 1, 1);
        assertCounter(json.getAsJsonObject("connectionAttempt"), 1, 1);
        assertEquals(2, json.get("subscriptionCount").getAsLong());
        assertEquals(7, json.get("monitoredItemCount").getAsLong());
    }

    @Test
    void metricSnapshotFlattensByOperationalFamilyAndResetsIntervalsOnly() {
        ClientMetrics metrics = new ClientMetrics();
        metrics.recordSubscribedRead();
        metrics.recordReadRequest();
        metrics.recordWriteRequest();
        metrics.recordReadFailure();
        metrics.recordWriteFailure();
        metrics.recordBrowseRequest();
        metrics.recordBrowseFailure();
        metrics.recordBrowseResult(5, true);
        metrics.recordSubscriptionRecreate();
        metrics.recordConnectionAttempt();
        metrics.recordConnectionFailure();
        metrics.recordTerminalFailure();
        metrics.recordSessionDisconnect();
        metrics.recordSessionReconnect();
        metrics.setSubscriptionShape(2, 7);

        ClientMetrics.Snapshot snapshot = metrics.snapshotAndResetIntervals();
        Map<String, Float> command = snapshot.toCommandMeasureValues();
        Map<String, Float> subscription = snapshot.toSubscriptionMeasureValues();
        Map<String, Float> browse = snapshot.toBrowseMeasureValues();
        Map<String, Float> connection = snapshot.toConnectionMeasureValues(true);

        assertEquals(Float.valueOf(1.0f), command.get("ReadRequestTotal"));
        assertEquals(Float.valueOf(1.0f), command.get("ReadRequestInterval"));
        assertEquals(Float.valueOf(1.0f), command.get("ReadFailureTotal"));
        assertEquals(Float.valueOf(1.0f), command.get("ReadFailureInterval"));
        assertEquals(Float.valueOf(1.0f), command.get("WriteRequestTotal"));
        assertEquals(Float.valueOf(1.0f), command.get("WriteRequestInterval"));
        assertEquals(Float.valueOf(1.0f), command.get("WriteFailureTotal"));
        assertEquals(Float.valueOf(1.0f), command.get("WriteFailureInterval"));

        assertEquals(Float.valueOf(1.0f), subscription.get("SubscribedReadTotal"));
        assertEquals(Float.valueOf(1.0f), subscription.get("SubscribedReadInterval"));
        assertEquals(Float.valueOf(1.0f), subscription.get("SubscriptionRecreateTotal"));
        assertEquals(Float.valueOf(1.0f), subscription.get("SubscriptionRecreateInterval"));
        assertEquals(Float.valueOf(2.0f), subscription.get("SubscriptionCount"));
        assertEquals(Float.valueOf(7.0f), subscription.get("MonitoredItemCount"));

        assertEquals(Float.valueOf(1.0f), browse.get("BrowseRequestTotal"));
        assertEquals(Float.valueOf(1.0f), browse.get("BrowseRequestInterval"));
        assertEquals(Float.valueOf(1.0f), browse.get("BrowseFailureTotal"));
        assertEquals(Float.valueOf(1.0f), browse.get("BrowseFailureInterval"));
        assertEquals(Float.valueOf(5.0f), browse.get("BrowseReferenceTotal"));
        assertEquals(Float.valueOf(5.0f), browse.get("BrowseReferenceInterval"));
        assertEquals(Float.valueOf(1.0f), browse.get("BrowseTruncatedTotal"));
        assertEquals(Float.valueOf(1.0f), browse.get("BrowseTruncatedInterval"));

        assertEquals(Float.valueOf(1.0f), connection.get("ConnectionAttemptTotal"));
        assertEquals(Float.valueOf(1.0f), connection.get("ConnectionAttemptInterval"));
        assertEquals(Float.valueOf(1.0f), connection.get("ConnectionFailureTotal"));
        assertEquals(Float.valueOf(1.0f), connection.get("ConnectionFailureInterval"));
        assertEquals(Float.valueOf(1.0f), connection.get("TerminalFailureTotal"));
        assertEquals(Float.valueOf(1.0f), connection.get("TerminalFailureInterval"));
        assertEquals(Float.valueOf(1.0f), connection.get("SessionDisconnectTotal"));
        assertEquals(Float.valueOf(1.0f), connection.get("SessionDisconnectInterval"));
        assertEquals(Float.valueOf(1.0f), connection.get("SessionReconnectTotal"));
        assertEquals(Float.valueOf(1.0f), connection.get("SessionReconnectInterval"));
        assertEquals(Float.valueOf(1.0f), connection.get("SessionConnected"));

        JsonObject afterReset = metrics.toJsonObject();
        assertCounter(afterReset.getAsJsonObject("subscribedRead"), 1, 0);
        assertCounter(afterReset.getAsJsonObject("readRequest"), 1, 0);
        assertCounter(afterReset.getAsJsonObject("writeRequest"), 1, 0);
        assertCounter(afterReset.getAsJsonObject("readFailure"), 1, 0);
        assertCounter(afterReset.getAsJsonObject("writeFailure"), 1, 0);
        assertCounter(afterReset.getAsJsonObject("browseRequest"), 1, 0);
        assertCounter(afterReset.getAsJsonObject("browseFailure"), 1, 0);
        assertCounter(afterReset.getAsJsonObject("browseReference"), 5, 0);
        assertCounter(afterReset.getAsJsonObject("browseTruncated"), 1, 0);
        assertCounter(afterReset.getAsJsonObject("subscriptionRecreate"), 1, 0);
        assertEquals(2, afterReset.get("subscriptionCount").getAsLong());
        assertEquals(7, afterReset.get("monitoredItemCount").getAsLong());
    }

    @Test
    void recordCommand_countsRequestsAndFailures_surfacedInCommandMeasures() {
        ClientMetrics metrics = new ClientMetrics();
        metrics.recordCommand(true);
        metrics.recordCommand(true);
        metrics.recordCommand(false);

        JsonObject json = metrics.toJsonObject();
        assertCounter(json.getAsJsonObject("commandRequest"), 3, 3);
        assertCounter(json.getAsJsonObject("commandFailure"), 1, 1);

        Map<String, Float> command = metrics.snapshot().toCommandMeasureValues();
        assertEquals(Float.valueOf(3.0f), command.get("CommandRequestTotal"));
        assertEquals(Float.valueOf(1.0f), command.get("CommandFailureTotal"));
    }

    @Test
    void getIntervalReconnects_takesAndResets_independentOfOperationalReset() {
        ClientMetrics metrics = new ClientMetrics();
        metrics.recordSessionReconnect();
        metrics.recordSessionReconnect();
        // The health drain is separate from the operational-family interval drain.
        assertEquals(2L, metrics.getIntervalReconnects());
        assertEquals(0L, metrics.getIntervalReconnects());
    }

    @Test
    void resetIntervals_clearsCommandIntervalsButKeepsTotals() {
        ClientMetrics metrics = new ClientMetrics();
        metrics.recordCommand(false);
        metrics.resetIntervals();
        JsonObject json = metrics.toJsonObject();
        assertCounter(json.getAsJsonObject("commandRequest"), 1, 0);
        assertCounter(json.getAsJsonObject("commandFailure"), 1, 0);
    }

    private static void assertCounter(JsonObject counter, long total, long interval) {
        assertEquals(total, counter.get("total").getAsLong());
        assertEquals(interval, counter.get("interval").getAsLong());
    }
}
