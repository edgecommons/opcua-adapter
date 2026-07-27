package com.mbreissi.edgecommons.opcua.opc;

import com.mbreissi.edgecommons.config.ConfigManager;
import com.mbreissi.edgecommons.metrics.Metric;
import com.mbreissi.edgecommons.metrics.MetricBuilder;
import com.mbreissi.edgecommons.metrics.MetricEmitter;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Defines and emits the canonical {@code southbound_health} metric (SOUTHBOUND.md §5) for one device
 * instance, with <b>exactly</b> the §5 measure set:
 *
 * <table border="1">
 *   <caption>southbound_health measures</caption>
 *   <tr><th>Measure</th><th>Unit</th><th>Res</th><th>Source</th></tr>
 *   <tr><td>{@code connectionState}</td><td>Count</td><td>1</td><td>1 = session up, 0 = down</td></tr>
 *   <tr><td>{@code publishLatencyMs}</td><td>Milliseconds</td><td>1</td><td>last northbound publish round-trip</td></tr>
 *   <tr><td>{@code pollLatencyMs}</td><td>Milliseconds</td><td>1</td><td>last explicit-read round-trip ({@code repoll}/{@code sb/read})</td></tr>
 *   <tr><td>{@code readErrors}</td><td>Count</td><td>60</td><td>read errors over the interval</td></tr>
 *   <tr><td>{@code staleSignals}</td><td>Count</td><td>60</td><td>signals with no update past {@code healthThresholds.staleSignalSecs}</td></tr>
 *   <tr><td>{@code reconnects}</td><td>Count</td><td>60</td><td>§5-optional: session reconnects over the interval</td></tr>
 *   <tr><td>{@code writeErrors}</td><td>Count</td><td>60</td><td>§5-optional: device-path write failures over the interval</td></tr>
 *   <tr><td>{@code signalsSubscribed}</td><td>Count</td><td>1</td><td>§5-optional gauge: monitored items served while connected; 0 while disconnected</td></tr>
 * </table>
 *
 * <p>The interval counters ({@code readErrors}, {@code reconnects}, {@code writeErrors}) are drained from
 * {@link ClientMetrics} only here (the OPC UA operational families drain their own, separate counters),
 * so the two emitters never race. {@code writeErrors} counts only <b>device-path</b> failures — an entry
 * that passed validation and the {@code writes.allow[]} allow-list and was then rejected by the server or
 * aborted by an unavailable session; policy refusals and caller errors do not count, mirroring
 * {@code readErrors}. {@code staleSignals} is computed from {@link HealthState}'s per-signal last-update
 * tracker against the configured {@code staleSignalSecs} threshold. {@code signalsSubscribed} is the
 * gauge of signals the session currently serves ({@link ClientMetrics#getMonitoredItemCount()} — this
 * subscribe-model adapter's subscription inventory) while connected, 0 while disconnected.
 */
public class HealthMetrics {

    private static final String NAME = "southbound_health";

    private final MetricEmitter metrics;
    private final ClientMetrics counters;
    private final HealthState health;
    private final long staleAfterNanos;

    public HealthMetrics(MetricEmitter metrics, ConfigManager config, String instanceId,
                         ClientMetrics counters, HealthState health, long staleSignalSecs) {
        this.metrics = metrics;
        this.counters = counters;
        this.health = health;
        this.staleAfterNanos = HealthState.staleAfterNanos(staleSignalSecs);
        Metric metric = MetricBuilder.create(NAME)
                .withConfig(config)
                .addMeasure("connectionState", "Count", 1)
                .addMeasure("publishLatencyMs", "Milliseconds", 1)
                .addMeasure("pollLatencyMs", "Milliseconds", 1)
                .addMeasure("readErrors", "Count", 60)
                .addMeasure("staleSignals", "Count", 60)
                .addMeasure("reconnects", "Count", 60)
                .addMeasure("writeErrors", "Count", 60)
                .addMeasure("signalsSubscribed", "Count", 1)
                .addDimension("instance", instanceId)
                .build();
        metrics.defineMetric(metric);
    }

    /** Build the §5 measure map, draining the interval counters. Package-private for unit testing. */
    Map<String, Float> measures(boolean connected, long nowNanos) {
        Map<String, Float> m = new LinkedHashMap<>();
        m.put("connectionState", connected ? 1.0f : 0.0f);
        m.put("publishLatencyMs", (float) health.publishLatencyMs());
        m.put("pollLatencyMs", (float) health.pollLatencyMs());
        m.put("readErrors", (float) counters.getIntervalReadErrors());
        m.put("staleSignals", (float) health.staleCount(nowNanos, staleAfterNanos));
        m.put("reconnects", (float) counters.getIntervalReconnects());
        m.put("writeErrors", (float) counters.getIntervalWriteErrors());
        m.put("signalsSubscribed", connected ? (float) counters.getMonitoredItemCount() : 0.0f);
        return m;
    }

    /** Periodic sampler emit (buffered per the metric target's resolution). */
    public void emit(boolean connected) {
        metrics.emitMetric(NAME, measures(connected, System.nanoTime()));
    }

    /** Immediate transition emit (connect/disconnect), unbuffered (SOUTHBOUND.md §5). */
    public void emitNow(boolean connected) {
        metrics.emitMetricNow(NAME, measures(connected, System.nanoTime()));
    }
}
