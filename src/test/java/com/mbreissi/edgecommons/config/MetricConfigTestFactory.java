package com.mbreissi.edgecommons.config;

import com.google.gson.JsonObject;

/**
 * Test-only bridge into the {@code com.mbreissi.edgecommons.config} package: exposes the package-private
 * {@link MetricConfiguration} constructor so adapter tests can build a usable metric config (with the
 * default namespace) for {@link com.mbreissi.edgecommons.metrics.MetricBuilder#withConfig} without a
 * live config load.
 */
public final class MetricConfigTestFactory {

    private MetricConfigTestFactory() {
    }

    /** A {@link MetricConfiguration} with library defaults (default namespace). */
    public static MetricConfiguration defaults() {
        return new MetricConfiguration(new JsonObject());
    }
}
