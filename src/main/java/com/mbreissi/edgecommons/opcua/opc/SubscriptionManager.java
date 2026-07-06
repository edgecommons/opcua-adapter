package com.mbreissi.edgecommons.opcua.opc;

import com.mbreissi.edgecommons.opcua.opc.config.DeadbandSpec;
import com.mbreissi.edgecommons.opcua.opc.config.ServerConfiguration;
import com.mbreissi.edgecommons.opcua.opc.config.SubscriptionSpec;
import com.mbreissi.edgecommons.opcua.opc.config.SignalSpec;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.milo.opcua.sdk.client.OpcUaClient;
import org.eclipse.milo.opcua.sdk.client.nodes.UaVariableNode;
import org.eclipse.milo.opcua.sdk.client.subscriptions.OpcUaMonitoredItem;
import org.eclipse.milo.opcua.sdk.client.subscriptions.OpcUaSubscription;
import org.eclipse.milo.opcua.stack.core.types.builtin.NodeId;
import org.eclipse.milo.opcua.stack.core.types.builtin.StatusCode;
import org.eclipse.milo.opcua.stack.core.types.enumerated.DataChangeTrigger;
import org.eclipse.milo.opcua.stack.core.types.enumerated.DeadbandType;
import org.eclipse.milo.opcua.stack.core.types.structured.DataChangeFilter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.Unsigned.uint;

/**
 * Creates and maintains the OPC UA subscriptions for one device: matches address-space nodes to the
 * configured include/exclude signal specs, creates monitored items (with deadband), and routes value
 * changes to the {@link SignalUpdatePublisher}. Re-establishes a subscription on transfer failure.
 *
 * <p>A signal spec selects its namespace by URI (resolved to the server's current index from the live
 * namespace table) or by a literal index. Resolution happens here, each time subscriptions are built,
 * so a server that renumbers between connections is picked up automatically.
 */
public class SubscriptionManager {

    private static final Logger LOGGER = LogManager.getLogger(SubscriptionManager.class);

    private final OpcUaClient client;
    private final ServerConfiguration config;
    private final Map<NodeId, UaVariableNode> allNodes;
    private final SignalUpdatePublisher publisher;
    private final ClientMetrics counters;

    private final Map<OpcUaSubscription, SubscriptionSpec> subscriptions = new HashMap<>();
    private final Map<String, ResolvedSignal> resolvedSignals = new ConcurrentHashMap<>();

    public SubscriptionManager(OpcUaClient client, ServerConfiguration config,
                               Map<NodeId, UaVariableNode> allNodes,
                               SignalUpdatePublisher publisher, ClientMetrics counters) {
        this.client = client;
        this.config = config;
        this.allNodes = allNodes;
        this.publisher = publisher;
        this.counters = counters;
    }

    public Map<String, ResolvedSignal> getResolvedSignals() {
        return resolvedSignals;
    }

    public void createAll() {
        for (SubscriptionSpec spec : config.getSubscriptionSpecs()) {
            OpcUaSubscription subscription = create(spec);
            if (subscription != null) {
                subscriptions.put(subscription, spec);
            }
        }
    }

    private OpcUaSubscription create(SubscriptionSpec spec) {
        try {
            OpcUaSubscription subscription = new OpcUaSubscription(client, spec.getPublishIntervalMs());
            subscription.setSubscriptionListener(new OpcUaSubscription.SubscriptionListener() {
                @Override
                public void onTransferFailed(OpcUaSubscription sub, StatusCode status) {
                    LOGGER.warn("[{}] subscription '{}' transfer failed ({}); re-establishing",
                            config.getId(), spec.getId(), status);
                    reestablish(sub);
                }
            });

            Map<UaVariableNode, SignalSpec> matching = filter(spec);
            List<OpcUaMonitoredItem> items = new ArrayList<>();
            matching.forEach((node, signalSpec) -> {
                OpcUaMonitoredItem item = OpcUaMonitoredItem.newDataItem(node.getNodeId());
                item.setSamplingInterval(signalSpec.getSamplingRateMs());
                item.setQueueSize(uint(signalSpec.getQueueSize()));
                DeadbandSpec deadband = signalSpec.getDeadband();
                if (deadband.getType() != DeadbandType.None) {
                    item.setFilter(new DataChangeFilter(
                            DataChangeTrigger.StatusValue,
                            uint(deadband.getType().getValue()),
                            deadband.getValue()));
                }
                item.setDataValueListener((it, value) -> {
                    counters.incrementReadMetrics();
                    publisher.offer(node, signalSpec, value);
                });
                items.add(item);
                resolvedSignals.put(node.getNodeId().getIdentifier().toString(), new ResolvedSignal(node.getNodeId(), signalSpec));
            });

            subscription.addMonitoredItems(items);
            subscription.create();
            subscription.synchronizeMonitoredItems();
            LOGGER.info("[{}] subscription '{}': {} monitored item(s)", config.getId(), spec.getId(), items.size());
            return subscription;
        } catch (Exception e) {
            LOGGER.error("[{}] failed to create subscription '{}': {}", config.getId(), spec.getId(), e.toString());
            return null;
        }
    }

    public void reestablish(OpcUaSubscription subscription) {
        SubscriptionSpec spec = subscriptions.remove(subscription);
        if (spec == null) {
            return;
        }
        OpcUaSubscription replacement = create(spec);
        if (replacement != null) {
            subscriptions.put(replacement, spec);
        }
    }

    private Map<UaVariableNode, SignalSpec> filter(SubscriptionSpec spec) {
        Map<SignalSpec, Integer> includeNs = resolveNamespaces(spec.getIncludeSpec().getSignalSpecs());
        Map<SignalSpec, Integer> excludeNs = spec.getExcludeSpec() != null
                ? resolveNamespaces(spec.getExcludeSpec().getSignalSpecs())
                : new IdentityHashMap<>();

        Map<UaVariableNode, SignalSpec> matching = new LinkedHashMap<>();
        for (UaVariableNode node : allNodes.values()) {
            SignalSpec include = matchSignal(node, spec.getIncludeSpec().getSignalSpecs(), includeNs, true);
            if (include == null) {
                continue;
            }
            if (spec.getExcludeSpec() != null
                    && matchSignal(node, spec.getExcludeSpec().getSignalSpecs(), excludeNs, false) != null) {
                continue;
            }
            matching.put(node, include);
        }
        return matching;
    }

    /**
     * Resolve each signal spec's namespace to an effective index: a {@code namespaceUri} is looked up in
     * the server's current namespace table; otherwise the literal {@code namespace} is used. An
     * unresolved URI maps to {@code -1} (the matcher is skipped, with a warning). Delegates the actual
     * resolution to {@link SignalMatching#resolveNamespace}, shared with the on-demand read path.
     */
    private Map<SignalSpec, Integer> resolveNamespaces(List<SignalSpec> specs) {
        Map<SignalSpec, Integer> result = new IdentityHashMap<>();
        for (SignalSpec spec : specs) {
            int ns = SignalMatching.resolveNamespace(spec, client.getNamespaceTable());
            if (spec.getNamespaceUri() != null && ns < 0) {
                LOGGER.warn("[{}] namespaceUri '{}' is not in the server's namespace table; matcher skipped",
                        config.getId(), spec.getNamespaceUri());
            }
            result.put(spec, ns);
        }
        return result;
    }

    /**
     * Match a node against signal specs by resolved namespace index + regex over nodeId / browseName /
     * displayName. Delegates to {@link SignalMatching#firstMatch}, shared with the on-demand read path.
     */
    private SignalSpec matchSignal(UaVariableNode node, List<SignalSpec> specs, Map<SignalSpec, Integer> nsBySpec, boolean matchNames) {
        String idStr = node.getNodeId().getIdentifier().toString();
        String browseName = node.getBrowseName() != null && node.getBrowseName().getName() != null
                ? node.getBrowseName().getName() : "";
        String displayName = node.getDisplayName() != null && node.getDisplayName().getText() != null
                ? node.getDisplayName().getText() : "";
        int nodeNs = node.getNodeId().getNamespaceIndex().intValue();
        return SignalMatching.firstMatch(specs, nsBySpec, idStr, browseName, displayName, nodeNs, matchNames);
    }
}
