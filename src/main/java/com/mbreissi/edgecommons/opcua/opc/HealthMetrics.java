package com.mbreissi.edgecommons.opcua.opc;

import com.mbreissi.edgecommons.config.ConfigManager;
import com.mbreissi.edgecommons.metrics.Metric;
import com.mbreissi.edgecommons.metrics.MetricBuilder;
import com.mbreissi.edgecommons.metrics.MetricEmitter;

import java.util.HashMap;
import java.util.Map;

/** Defines and emits the standard {@code southbound_health} metric for one device instance. */
public class HealthMetrics {

    private static final String NAME = "southbound_health";

    private final MetricEmitter metrics;
    private final ClientMetrics counters;

    public HealthMetrics(MetricEmitter metrics, ConfigManager config, String instanceId, ClientMetrics counters) {
        this.metrics = metrics;
        this.counters = counters;
        Metric health = MetricBuilder.create(NAME)
                .withConfig(config)
                .addMeasure("connectionState", "Count", 1)
                .addMeasure("readErrors", "Count", 60)
                .addMeasure("writeErrors", "Count", 60)
                .addDimension("instance", instanceId)
                .build();
        metrics.defineMetric(health);
    }

    public void emit(boolean connected) {
        Map<String, Float> m = new HashMap<>();
        m.put("connectionState", connected ? 1.0f : 0.0f);
        m.put("readErrors", (float) counters.getIntervalReadErrors());
        m.put("writeErrors", (float) counters.getIntervalWriteErrors());
        metrics.emitMetric(NAME, m);
    }
}
