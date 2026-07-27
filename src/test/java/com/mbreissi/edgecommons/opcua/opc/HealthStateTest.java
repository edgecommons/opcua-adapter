package com.mbreissi.edgecommons.opcua.opc;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the pure {@link HealthState} — the §5 gauges, the paused toggle, the
 * {@code staleSignals} tracker, and the reported instance state (D‑SC‑7) as it lands on the
 * {@code state} keepalive's {@code instances[]} wire element.
 */
class HealthStateTest {

    @Test
    void setPaused_isIdempotentAndReportsChange() {
        HealthState h = new HealthState();
        assertFalse(h.isPaused());
        assertTrue(h.setPaused(true));   // false -> true: changed
        assertTrue(h.isPaused());
        assertFalse(h.setPaused(true));  // true -> true: no change
        assertTrue(h.setPaused(false));  // true -> false: changed
        assertFalse(h.isPaused());
    }

    @Test
    void latencyGauges_clampNegativeToZero() {
        HealthState h = new HealthState();
        h.setPublishLatencyMs(42);
        h.setPollLatencyMs(7);
        assertEquals(42, h.publishLatencyMs());
        assertEquals(7, h.pollLatencyMs());
        h.setPublishLatencyMs(-5);
        assertEquals(0, h.publishLatencyMs());
    }

    @Test
    void countStale_countsOnlyEntriesOlderThanThreshold() {
        long now = 1_000_000_000_000L;
        long threshold = 30_000_000_000L; // 30s in nanos
        // one fresh (just updated), one stale (updated 40s ago), one exactly at 30s (not > threshold)
        long stale = now - 40_000_000_000L;
        long boundary = now - threshold;
        assertEquals(1, HealthState.countStale(List.of(now, stale, boundary), now, threshold));
    }

    @Test
    void staleCount_seededSignalBecomesStaleAfterThreshold_freshOneDoesNot() {
        HealthState h = new HealthState();
        long start = 0L;
        h.seedSignal("ns=2;s=A", start);
        h.seedSignal("ns=2;s=B", start);
        long staleAfter = HealthState.staleAfterNanos(30);
        // B keeps updating; A never does.
        long now = start + 31_000_000_000L;
        h.onSignalUpdate("ns=2;s=B", now);
        assertEquals(1, h.staleCount(now, staleAfter));
    }

    @Test
    void seedSignal_doesNotClobberALaterUpdate() {
        HealthState h = new HealthState();
        h.onSignalUpdate("ns=2;s=A", 100L);
        h.seedSignal("ns=2;s=A", 0L); // putIfAbsent must not roll the timestamp back
        assertEquals(0, h.staleCount(100L, HealthState.staleAfterNanos(30)));
    }

    @Test
    void staleAfterNanos_defaultsWhenNonPositive() {
        assertEquals(30L * 1_000_000_000L, HealthState.staleAfterNanos(0));
        assertEquals(30L * 1_000_000_000L, HealthState.staleAfterNanos(-1));
        assertEquals(5L * 1_000_000_000L, HealthState.staleAfterNanos(5));
    }

    // ---- the reported instance state (D-SC-7) ----------------------------------------------------

    @Test
    void observeConnected_staysConnectingUntilTheLinkHasBeenUp_thenBacksOff() {
        HealthState h = new HealthState();
        assertEquals(HealthState.LinkState.CONNECTING, h.link());
        // A failed first attempt is still "coming up", not a backoff.
        assertEquals(HealthState.LinkState.CONNECTING, h.observeConnected(false));
        assertEquals(HealthState.LinkState.ONLINE, h.observeConnected(true));
        // Once it has been up, a drop is a backoff.
        assertEquals(HealthState.LinkState.BACKOFF, h.observeConnected(false));
        assertEquals(HealthState.LinkState.ONLINE, h.observeConnected(true));
    }

    @Test
    void stateToken_pausedTakesPrecedenceOnlyWhileTheLinkIsUp() {
        HealthState h = new HealthState();
        h.setPaused(true);
        assertEquals("CONNECTING", h.stateToken());   // never been up: still coming up
        h.observeConnected(true);
        assertEquals("PAUSED", h.stateToken());
        h.observeConnected(false);
        assertEquals("BACKOFF", h.stateToken());      // a break while paused reads BACKOFF
        h.observeConnected(true);
        h.setPaused(false);
        assertEquals("ONLINE", h.stateToken());
    }

    @Test
    void connectivity_wireElement_carriesOnlineThenPaused() {
        HealthState h = new HealthState();
        h.observeConnected(true);

        JsonObject online = h.connectivity("kep1", "opc.tcp://host:49320/").toJson();
        assertEquals("kep1", online.get("instance").getAsString());
        assertTrue(online.get("connected").getAsBoolean());
        assertEquals("ONLINE", online.get("state").getAsString());
        assertEquals("opc.tcp://host:49320/", online.get("detail").getAsString());

        h.setPaused(true);
        JsonObject paused = h.connectivity("kep1", "opc.tcp://host:49320/").toJson();
        assertEquals("PAUSED", paused.get("state").getAsString());
        // A paused instance is still connected - it is deliberately quiet, not unreachable.
        assertTrue(paused.get("connected").getAsBoolean());
    }

    @Test
    void connectivity_downLink_reportsDisconnectedWithTheBackoffToken() {
        HealthState h = new HealthState();
        h.observeConnected(true);
        h.observeConnected(false);
        JsonObject down = h.connectivity("kep1", "opc.tcp://host:49320/").toJson();
        assertFalse(down.get("connected").getAsBoolean());
        assertEquals("BACKOFF", down.get("state").getAsString());
    }

    @Test
    void pendingConnectivity_reportsAConfiguredServerThatHasNotStartedYet() {
        JsonObject pending = HealthState.pendingConnectivity("plc2").toJson();
        assertEquals("plc2", pending.get("instance").getAsString());
        assertFalse(pending.get("connected").getAsBoolean());
        assertEquals("CONNECTING", pending.get("state").getAsString());
        // No endpoint is known before the device exists, so the optional detail is omitted.
        assertFalse(pending.has("detail"));
    }
}
