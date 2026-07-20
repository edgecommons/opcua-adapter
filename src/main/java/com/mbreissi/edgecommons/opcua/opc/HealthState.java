package com.mbreissi.edgecommons.opcua.opc;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The shared per-device state behind the canonical {@code southbound_health} metric (SOUTHBOUND.md §5)
 * and the {@code sb/*} lifecycle verbs. One instance per {@link OpcUaDevice}.
 *
 * <p>It carries the §5 gauges this subscribe-model adapter does not otherwise track — the last publish
 * round-trip ({@code publishLatencyMs}), the last explicit read round-trip ({@code pollLatencyMs}), the
 * {@code paused} flag toggled by {@code sb/pause}/{@code sb/resume}, and the per-signal last-update
 * instants that drive {@code staleSignals}. The interval counters {@code readErrors}/{@code writeErrors}/
 * {@code reconnects} stay in {@link ClientMetrics} (already interval-drained on emit); this class does
 * not duplicate them. Keeping the staleness and latency logic here — with no OPC UA or messaging types —
 * is what lets the {@code southbound_health} shape be unit-tested without a live server.
 *
 * <h2>staleSignals</h2>
 * A signal that silently stops updating is otherwise indistinguishable from one that is simply not
 * changing, so its last-update instant is tracked and a signal with no update for longer than
 * {@code healthThresholds.staleSignalSecs} (default 30) is counted. Each configured signal is
 * {@link #seedSignal seeded} at subscription time (so a signal that never ticks becomes stale after the
 * threshold), and refreshed on every successful publish ({@link #onSignalUpdate}).
 */
public class HealthState {

    private final AtomicBoolean paused = new AtomicBoolean();
    private final AtomicLong publishLatencyMs = new AtomicLong();
    private final AtomicLong pollLatencyMs = new AtomicLong();

    /** Per-signal last-update instant (nanos) — the staleness tracker driving {@code staleSignals}. */
    private final Map<String, Long> lastUpdateNanos = new ConcurrentHashMap<>();

    /** Flip the paused flag, returning whether the state actually changed (idempotent — pausing an
     *  already-paused instance is not an error). */
    public boolean setPaused(boolean value) {
        return paused.getAndSet(value) != value;
    }

    public boolean isPaused() {
        return paused.get();
    }

    public void setPublishLatencyMs(long ms) {
        publishLatencyMs.set(Math.max(0L, ms));
    }

    public long publishLatencyMs() {
        return publishLatencyMs.get();
    }

    public void setPollLatencyMs(long ms) {
        pollLatencyMs.set(Math.max(0L, ms));
    }

    public long pollLatencyMs() {
        return pollLatencyMs.get();
    }

    /** Seed a configured signal's baseline instant (subscription time), so a signal that never
     *  updates still becomes stale after the threshold rather than being invisible. */
    public void seedSignal(String signalId, long nowNanos) {
        lastUpdateNanos.putIfAbsent(signalId, nowNanos);
    }

    /** Note that a signal just updated — feeds the {@code staleSignals} tracker. */
    public void onSignalUpdate(String signalId, long nowNanos) {
        lastUpdateNanos.put(signalId, nowNanos);
    }

    /** How many tracked signals have not updated within {@code staleAfterNanos} of {@code nowNanos}. */
    public long staleCount(long nowNanos, long staleAfterNanos) {
        return countStale(lastUpdateNanos.values(), nowNanos, staleAfterNanos);
    }

    /**
     * The pure core of {@code southbound_health.staleSignals}: how many last-update instants are older
     * than {@code staleAfterNanos} relative to {@code nowNanos}.
     */
    static long countStale(Iterable<Long> lastUpdateNanos, long nowNanos, long staleAfterNanos) {
        long n = 0;
        for (long t : lastUpdateNanos) {
            if (nowNanos - t > staleAfterNanos) {
                n++;
            }
        }
        return n;
    }

    /**
     * Resolve {@code component.global.healthThresholds.staleSignalSecs} to nanoseconds, defaulting to
     * {@link #DEFAULT_STALE_SIGNAL_SECS} when unset or non-positive.
     */
    public static long staleAfterNanos(long staleSignalSecs) {
        return Math.max(1L, staleSignalSecs > 0 ? staleSignalSecs : DEFAULT_STALE_SIGNAL_SECS) * 1_000_000_000L;
    }

    /** The {@code healthThresholds.staleSignalSecs} default (SOUTHBOUND.md §5). */
    public static final long DEFAULT_STALE_SIGNAL_SECS = 30L;
}
