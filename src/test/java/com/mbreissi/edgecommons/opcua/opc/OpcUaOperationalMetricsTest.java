package com.mbreissi.edgecommons.opcua.opc;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link OpcUaOperationalMetrics}: the four operational families are defined, and the
 * {@code OpcUaCommand} family carries the {@code sb/*} command counters ({@code CommandRequest*} /
 * {@code CommandFailure*}) recorded via {@link ClientMetrics#recordCommand}.
 */
class OpcUaOperationalMetricsTest {

    @Test
    void defines_allFourFamilies() {
        TestMetrics.Capturing emitter = new TestMetrics.Capturing();
        new OpcUaOperationalMetrics(emitter, TestMetrics.config(), "kep1", new ClientMetrics());
        assertTrue(emitter.defined.containsKey("OpcUaCommand"));
        assertTrue(emitter.defined.containsKey("OpcUaSubscription"));
        assertTrue(emitter.defined.containsKey("OpcUaBrowse"));
        assertTrue(emitter.defined.containsKey("OpcUaConnection"));
    }

    @Test
    void command_family_surfacesCommandCounters() {
        TestMetrics.Capturing emitter = new TestMetrics.Capturing();
        ClientMetrics counters = new ClientMetrics();
        OpcUaOperationalMetrics metrics =
                new OpcUaOperationalMetrics(emitter, TestMetrics.config(), "kep1", counters);

        counters.recordCommand(true);
        counters.recordCommand(true);
        counters.recordCommand(false);
        metrics.emit(true);

        Map<String, Float> cmd = emitter.emitted.get("OpcUaCommand");
        assertEquals(3.0f, cmd.get("CommandRequestTotal"));
        assertEquals(3.0f, cmd.get("CommandRequestInterval"));
        assertEquals(1.0f, cmd.get("CommandFailureTotal"));
        assertEquals(1.0f, cmd.get("CommandFailureInterval"));

        // Intervals reset on emit; totals persist.
        metrics.emit(true);
        Map<String, Float> second = emitter.emitted.get("OpcUaCommand");
        assertEquals(3.0f, second.get("CommandRequestTotal"));
        assertEquals(0.0f, second.get("CommandRequestInterval"));
    }

    @Test
    void connection_family_reportsSessionConnectedGauge() {
        TestMetrics.Capturing emitter = new TestMetrics.Capturing();
        ClientMetrics counters = new ClientMetrics();
        OpcUaOperationalMetrics metrics =
                new OpcUaOperationalMetrics(emitter, TestMetrics.config(), "kep1", counters);
        metrics.emit(true);
        assertEquals(1.0f, emitter.emitted.get("OpcUaConnection").get("SessionConnected"));
        metrics.emit(false);
        assertEquals(0.0f, emitter.emitted.get("OpcUaConnection").get("SessionConnected"));
    }
}
