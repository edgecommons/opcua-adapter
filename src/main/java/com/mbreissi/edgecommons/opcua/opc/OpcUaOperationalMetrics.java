package com.mbreissi.edgecommons.opcua.opc;

import com.mbreissi.edgecommons.config.ConfigManager;
import com.mbreissi.edgecommons.metrics.MetricBuilder;
import com.mbreissi.edgecommons.metrics.MetricEmitter;

/** Defines and emits OPC UA operational metric families for one device instance. */
public class OpcUaOperationalMetrics {

    static final String COMMAND = "OpcUaCommand";
    static final String SUBSCRIPTION = "OpcUaSubscription";
    static final String BROWSE = "OpcUaBrowse";
    static final String CONNECTION = "OpcUaConnection";

    private final MetricEmitter metrics;
    private final ClientMetrics counters;

    public OpcUaOperationalMetrics(MetricEmitter metrics, ConfigManager config, String instanceId, ClientMetrics counters) {
        this.metrics = metrics;
        this.counters = counters;
        defineCommand(config, instanceId);
        defineSubscription(config, instanceId);
        defineBrowse(config, instanceId);
        defineConnection(config, instanceId);
    }

    public void emit(boolean connected) {
        ClientMetrics.Snapshot snapshot = counters.snapshotAndResetIntervals();
        metrics.emitMetric(COMMAND, snapshot.toCommandMeasureValues());
        metrics.emitMetric(SUBSCRIPTION, snapshot.toSubscriptionMeasureValues());
        metrics.emitMetric(BROWSE, snapshot.toBrowseMeasureValues());
        metrics.emitMetric(CONNECTION, snapshot.toConnectionMeasureValues(connected));
    }

    private void defineCommand(ConfigManager config, String instanceId) {
        metrics.defineMetric(MetricBuilder.create(COMMAND)
                .withConfig(config)
                .addMeasure("ReadRequestTotal", "Count", 60)
                .addMeasure("ReadRequestInterval", "Count", 60)
                .addMeasure("ReadFailureTotal", "Count", 60)
                .addMeasure("ReadFailureInterval", "Count", 60)
                .addMeasure("WriteRequestTotal", "Count", 60)
                .addMeasure("WriteRequestInterval", "Count", 60)
                .addMeasure("WriteFailureTotal", "Count", 60)
                .addMeasure("WriteFailureInterval", "Count", 60)
                .addMeasure("CommandRequestTotal", "Count", 60)
                .addMeasure("CommandRequestInterval", "Count", 60)
                .addMeasure("CommandFailureTotal", "Count", 60)
                .addMeasure("CommandFailureInterval", "Count", 60)
                .addDimension("instance", instanceId)
                .build());
    }

    private void defineSubscription(ConfigManager config, String instanceId) {
        metrics.defineMetric(MetricBuilder.create(SUBSCRIPTION)
                .withConfig(config)
                .addMeasure("SubscribedReadTotal", "Count", 60)
                .addMeasure("SubscribedReadInterval", "Count", 60)
                .addMeasure("SubscriptionRecreateTotal", "Count", 60)
                .addMeasure("SubscriptionRecreateInterval", "Count", 60)
                .addMeasure("DroppedSampleTotal", "Count", 60)
                .addMeasure("DroppedSampleInterval", "Count", 60)
                .addMeasure("SubscriptionCount", "Count", 1)
                .addMeasure("MonitoredItemCount", "Count", 1)
                .addDimension("instance", instanceId)
                .build());
    }

    private void defineBrowse(ConfigManager config, String instanceId) {
        metrics.defineMetric(MetricBuilder.create(BROWSE)
                .withConfig(config)
                .addMeasure("BrowseRequestTotal", "Count", 60)
                .addMeasure("BrowseRequestInterval", "Count", 60)
                .addMeasure("BrowseFailureTotal", "Count", 60)
                .addMeasure("BrowseFailureInterval", "Count", 60)
                .addMeasure("BrowseReferenceTotal", "Count", 60)
                .addMeasure("BrowseReferenceInterval", "Count", 60)
                .addMeasure("BrowseTruncatedTotal", "Count", 60)
                .addMeasure("BrowseTruncatedInterval", "Count", 60)
                .addDimension("instance", instanceId)
                .build());
    }

    private void defineConnection(ConfigManager config, String instanceId) {
        metrics.defineMetric(MetricBuilder.create(CONNECTION)
                .withConfig(config)
                .addMeasure("ConnectionAttemptTotal", "Count", 60)
                .addMeasure("ConnectionAttemptInterval", "Count", 60)
                .addMeasure("ConnectionFailureTotal", "Count", 60)
                .addMeasure("ConnectionFailureInterval", "Count", 60)
                .addMeasure("TerminalFailureTotal", "Count", 60)
                .addMeasure("TerminalFailureInterval", "Count", 60)
                .addMeasure("SessionDisconnectTotal", "Count", 60)
                .addMeasure("SessionDisconnectInterval", "Count", 60)
                .addMeasure("SessionReconnectTotal", "Count", 60)
                .addMeasure("SessionReconnectInterval", "Count", 60)
                .addMeasure("SessionConnected", "Count", 1)
                .addDimension("instance", instanceId)
                .build());
    }
}
