package com.mbreissi.edgecommons.opcua.opc;

import com.mbreissi.edgecommons.heartbeat.InstanceConnectivity;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The shared per-device state behind the canonical {@code southbound_health} metric (SOUTHBOUND.md §5),
 * the {@code sb/*} lifecycle verbs, and the {@code state} keepalive's {@code instances[]} entry. One
 * instance per {@link OpcUaDevice}, and the <b>single</b> state model all three surfaces read
 * (DESIGN-scoped-commands D‑SC‑7) — a health dot, a keepalive entry, and an {@code sb/status} reply can
 * never disagree.
 *
 * <p>It carries the §5 gauges this subscribe-model adapter does not otherwise track — the last publish
 * round-trip ({@code publishLatencyMs}), the last explicit read round-trip ({@code pollLatencyMs}), the
 * {@code paused} flag toggled by {@code sb/pause}/{@code sb/resume}, and the per-signal last-update
 * instants that drive {@code staleSignals} — plus the {@link LinkState} the reported instance state is
 * derived from. The interval counters {@code readErrors}/{@code writeErrors}/
 * {@code reconnects} stay in {@link ClientMetrics} (already interval-drained on emit); this class does
 * not duplicate them. Keeping the staleness, latency and link logic here — with no OPC UA or messaging
 * types — is what lets the {@code southbound_health} shape and the reported state be unit-tested
 * without a live server.
 *
 * <h2>staleSignals</h2>
 * A signal that silently stops updating is otherwise indistinguishable from one that is simply not
 * changing, so its last-update instant is tracked and a signal with no update for longer than
 * {@code healthThresholds.staleSignalSecs} (default 30) is counted. Each configured signal is
 * {@link #seedSignal seeded} at subscription time (so a signal that never ticks becomes stale after the
 * threshold), and refreshed on every successful publish ({@link #onSignalUpdate}).
 */
public class HealthState {

    /**
     * This adapter's own vocabulary for one OPC UA server link's condition — the shared
     * {@code CONNECTING}/{@code ONLINE}/{@code BACKOFF} tokens of DESIGN-scoped-commands §6, reported
     * as the keepalive entry's {@code state} and the {@code sb/status} reply's {@code state}. A boolean
     * cannot tell "coming up for the first time" from "the session died and is being re-established";
     * an operator needs to, so the richer token rides alongside the normalized {@code connected} flag.
     * {@code PAUSED} is not a link condition — it is derived, and takes precedence while the link is up.
     */
    public enum LinkState {

        /** Connecting for the first time; the link has never been up. */
        CONNECTING,

        /** The session is up and serving subscriptions. */
        ONLINE,

        /** The link was up and is now down; Milo is re-establishing the session. */
        BACKOFF
    }

    private final AtomicBoolean paused = new AtomicBoolean();
    private final AtomicReference<LinkState> link = new AtomicReference<>(LinkState.CONNECTING);
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

    /**
     * Fold the live OPC UA session flag into the link state and return the result: a connected session
     * is {@link LinkState#ONLINE}; a session that is down is {@link LinkState#BACKOFF} once the link has
     * been up at least once, and still {@link LinkState#CONNECTING} before that. Called wherever the
     * device already observes the session flag (connect, the device tick, {@code reconnect}, and each
     * keepalive/{@code sb/status} sample), so the state model is never stale.
     */
    public LinkState observeConnected(boolean connected) {
        return link.updateAndGet(current -> connected
                ? LinkState.ONLINE
                : (current == LinkState.CONNECTING ? LinkState.CONNECTING : LinkState.BACKOFF));
    }

    /** The current link condition. */
    public LinkState link() {
        return link.get();
    }

    /**
     * The reported instance state token (D‑SC‑7): the link condition, except that a paused instance
     * whose session is up reports {@code PAUSED} — so a deliberately quiet instance is distinguishable
     * from one that silently went stale. A break while paused still reads {@code BACKOFF}, keeping the
     * normalized {@code connected} flag truthful.
     */
    public String stateToken() {
        LinkState current = link.get();
        return current == LinkState.ONLINE && paused.get() ? "PAUSED" : current.name();
    }

    /**
     * This instance's {@code state} keepalive element: the normalized {@code connected} flag, the
     * endpoint as {@code detail}, and the {@link #stateToken()} — the same model that answers
     * {@code sb/status}, so the pushed and the pulled view cannot diverge.
     */
    public InstanceConnectivity connectivity(String instanceId, String endpoint) {
        return InstanceConnectivity.of(instanceId, link.get() == LinkState.ONLINE, endpoint)
                .withState(stateToken());
    }

    /**
     * The keepalive element for a configured server whose device has not been created yet (its worker
     * is still in the initial connect loop): disconnected, no endpoint detail, state
     * {@link LinkState#CONNECTING}.
     */
    public static InstanceConnectivity pendingConnectivity(String instanceId) {
        return InstanceConnectivity.of(instanceId, false).withState(LinkState.CONNECTING.name());
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
