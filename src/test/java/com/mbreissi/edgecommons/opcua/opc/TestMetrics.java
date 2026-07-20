package com.mbreissi.edgecommons.opcua.opc;

import com.mbreissi.edgecommons.config.ConfigManager;
import com.mbreissi.edgecommons.config.MetricConfigTestFactory;
import com.mbreissi.edgecommons.config.MetricConfiguration;
import com.mbreissi.edgecommons.metrics.Metric;
import com.mbreissi.edgecommons.metrics.MetricEmitter;

import java.util.LinkedHashMap;
import java.util.Map;

/** Shared test doubles for the metric emitters — a minimal {@link ConfigManager} and a capturing
 *  {@link MetricEmitter} — so {@code southbound_health} and the OPC UA operational families can be
 *  asserted without a live messaging target. */
final class TestMetrics {

    private TestMetrics() {
    }

    /** A ConfigManager with just enough resolved identity for {@code MetricBuilder.withConfig}. */
    static ConfigManager config() {
        return new ConfigManager() {
            @Override
            public String getThingName() {
                return "test-thing";
            }

            @Override
            public String getComponentName() {
                return "opcua-adapter";
            }

            @Override
            public MetricConfiguration getMetricConfig() {
                return MetricConfigTestFactory.defaults();
            }
        };
    }

    /** A MetricEmitter that records the last emitted measure map per metric name (defined + emitted). */
    static final class Capturing extends MetricEmitter {
        final Map<String, Map<String, Float>> emitted = new LinkedHashMap<>();
        final Map<String, Map<String, Float>> emittedNow = new LinkedHashMap<>();
        final Map<String, Metric> defined = new LinkedHashMap<>();

        @Override
        public void defineMetric(Metric metric) {
            defined.put(metric.getName(), metric);
        }

        @Override
        public void emitMetric(String name, Map<String, Float> measureValues) {
            emitted.put(name, new LinkedHashMap<>(measureValues));
        }

        @Override
        public void emitMetricNow(String name, Map<String, Float> measureValues) {
            emittedNow.put(name, new LinkedHashMap<>(measureValues));
        }
    }
}
