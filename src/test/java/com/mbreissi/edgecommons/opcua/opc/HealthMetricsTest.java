package com.mbreissi.edgecommons.opcua.opc;

import com.mbreissi.edgecommons.config.ConfigManager;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link HealthMetrics}: the canonical {@code southbound_health} measure set (SOUTHBOUND.md
 * §5) is defined and emitted with exactly the required measures and the right values, and the interval
 * counters are drained on emit.
 */
class HealthMetricsTest {

    private static final String[] REQUIRED = {
            "connectionState", "publishLatencyMs", "pollLatencyMs", "readErrors", "staleSignals",
            "reconnects", "writeErrors", "signalsSubscribed",
    };

    @Test
    void emit_carriesExactlyTheSection5MeasureSet() {
        ConfigManager config = TestMetrics.config();
        TestMetrics.Capturing emitter = new TestMetrics.Capturing();
        ClientMetrics counters = new ClientMetrics();
        HealthState health = new HealthState();
        HealthMetrics metrics = new HealthMetrics(emitter, config, "kep1", counters, health, 30);

        assertTrue(emitter.defined.containsKey("southbound_health"));

        health.setPublishLatencyMs(12);
        health.setPollLatencyMs(34);
        metrics.emit(true);

        Map<String, Float> m = emitter.emitted.get("southbound_health");
        assertEquals(REQUIRED.length, m.size());
        for (String measure : REQUIRED) {
            assertTrue(m.containsKey(measure), "missing measure " + measure);
        }
        assertEquals(1.0f, m.get("connectionState"));
        assertEquals(12.0f, m.get("publishLatencyMs"));
        assertEquals(34.0f, m.get("pollLatencyMs"));
    }

    @Test
    void emit_drainsReadWriteAndReconnectIntervals() {
        ClientMetrics counters = new ClientMetrics();
        HealthState health = new HealthState();
        TestMetrics.Capturing emitter = new TestMetrics.Capturing();
        HealthMetrics metrics = new HealthMetrics(emitter, TestMetrics.config(), "kep1", counters, health, 30);

        counters.incrementReadErrors();
        counters.incrementReadErrors();
        counters.incrementWriteErrors();
        counters.recordSessionReconnect();

        metrics.emit(false);
        Map<String, Float> first = emitter.emitted.get("southbound_health");
        assertEquals(0.0f, first.get("connectionState"));
        assertEquals(2.0f, first.get("readErrors"));
        assertEquals(1.0f, first.get("writeErrors"));
        assertEquals(1.0f, first.get("reconnects"));

        // Intervals reset after the drain.
        metrics.emit(false);
        Map<String, Float> second = emitter.emitted.get("southbound_health");
        assertEquals(0.0f, second.get("readErrors"));
        assertEquals(0.0f, second.get("writeErrors"));
        assertEquals(0.0f, second.get("reconnects"));
    }

    @Test
    void signalsSubscribed_isTheMonitoredItemGaugeWhileConnected_andZeroWhileDisconnected() {
        ClientMetrics counters = new ClientMetrics();
        HealthState health = new HealthState();
        TestMetrics.Capturing emitter = new TestMetrics.Capturing();
        HealthMetrics metrics = new HealthMetrics(emitter, TestMetrics.config(), "kep1", counters, health, 30);

        counters.setSubscriptionShape(2, 17);

        metrics.emit(true);
        assertEquals(17.0f, emitter.emitted.get("southbound_health").get("signalsSubscribed"));

        // A gauge, not an interval counter: it does not drain, and reads 0 while disconnected.
        metrics.emit(true);
        assertEquals(17.0f, emitter.emitted.get("southbound_health").get("signalsSubscribed"));
        metrics.emit(false);
        assertEquals(0.0f, emitter.emitted.get("southbound_health").get("signalsSubscribed"));
    }

    @Test
    void staleSignals_reflectsTheTracker() {
        ClientMetrics counters = new ClientMetrics();
        HealthState health = new HealthState();
        TestMetrics.Capturing emitter = new TestMetrics.Capturing();
        HealthMetrics metrics = new HealthMetrics(emitter, TestMetrics.config(), "kep1", counters, health, 1);

        // A signal seeded far in the past is stale (staleSignalSecs=1).
        health.seedSignal("ns=2;s=Old", System.nanoTime() - 5_000_000_000L);
        metrics.emitNow(true);
        assertEquals(1.0f, emitter.emittedNow.get("southbound_health").get("staleSignals"));
    }
}
