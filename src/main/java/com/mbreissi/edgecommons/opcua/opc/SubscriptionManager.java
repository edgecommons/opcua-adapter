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
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
 *
 * <p><b>The inventory records fact, not intent.</b> A signal enters {@link #getResolvedSignals()} only
 * after the server has confirmed its monitored item was created — so {@code signalsSubscribed} counts
 * items the session actually serves, and a device cannot report itself subscribed to signals that
 * failed. Entries are keyed by {@link CanonicalSignalId}, so two namespaces exposing the same bare
 * identifier no longer overwrite one another.
 */
public class SubscriptionManager {

    private static final Logger LOGGER = LogManager.getLogger(SubscriptionManager.class);

    private final OpcUaClient client;
    private final ServerConfiguration config;
    private final Map<NodeId, UaVariableNode> allNodes;
    private final SignalUpdatePublisher publisher;
    private final ClientMetrics counters;

    /** Live subscriptions. Concurrent: Milo invokes the transfer-failed callback on its own thread. */
    private final Map<OpcUaSubscription, SubscriptionSpec> subscriptions = new ConcurrentHashMap<>();
    /** The inventory keys each subscription committed, so a re-establish can retract exactly its own. */
    private final Map<OpcUaSubscription, Set<String>> committed = new ConcurrentHashMap<>();
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

    /** The confirmed inventory, keyed by canonical signal id. */
    public Map<String, ResolvedSignal> getResolvedSignals() {
        return resolvedSignals;
    }

    /**
     * What a subscription build actually achieved.
     *
     * @param itemsRequested monitored items the configuration asked for
     * @param itemsLive      monitored items the server confirmed
     * @param subscriptionsFailed subscription specs that could not be created at all
     */
    public record SubscriptionOutcome(int itemsRequested, int itemsLive, int subscriptionsFailed) {

        /** Whether the device is serving what it was configured to serve. */
        public boolean healthy() {
            return subscriptionsFailed == 0 && itemsLive == itemsRequested;
        }

        /** Whether the device is serving anything at all (a zero-signal config is vacuously serving). */
        public boolean serving() {
            return itemsLive > 0 || itemsRequested == 0;
        }
    }

    /** Build every configured subscription, reporting what actually came up. */
    public SubscriptionOutcome createAll() {
        int requested = 0;
        int live = 0;
        int failed = 0;
        for (SubscriptionSpec spec : config.getSubscriptionSpecs()) {
            Created created = create(spec);
            requested += created.requested();
            live += created.live();
            if (created.subscription() == null) {
                failed++;
            } else {
                subscriptions.put(created.subscription(), spec);
                committed.put(created.subscription(), created.keys());
            }
        }
        updateSubscriptionShape();
        return new SubscriptionOutcome(requested, live, failed);
    }

    /**
     * Create one subscription. Monitored items are staged locally and committed to the inventory only
     * once the server has confirmed them, so a failure part-way through leaves no phantom entries.
     */
    private Created create(SubscriptionSpec spec) {
        Map<UaVariableNode, SignalSpec> matching = filter(spec);
        int requested = matching.size();
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

            // Stage: build the items and remember what each one would contribute to the inventory.
            Map<OpcUaMonitoredItem, ResolvedSignal> staged = new LinkedHashMap<>();
            List<OpcUaMonitoredItem> items = new ArrayList<>();
            for (Map.Entry<UaVariableNode, SignalSpec> entry : matching.entrySet()) {
                UaVariableNode node = entry.getKey();
                SignalSpec signalSpec = entry.getValue();
                CanonicalSignalId canonicalId;
                try {
                    canonicalId = CanonicalSignalId.of(node.getNodeId(), client.getNamespaceTable());
                } catch (RuntimeException e) {
                    LOGGER.warn("[{}] skipping {}: {}", config.getId(), node.getNodeId(), e.getMessage());
                    continue;
                }
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
                    counters.recordSubscribedRead();
                    publisher.offer(node, signalSpec, value);
                });
                items.add(item);
                staged.put(item, new ResolvedSignal(node.getNodeId(), signalSpec, canonicalId));
            }

            subscription.addMonitoredItems(items);
            subscription.create();
            subscription.synchronizeMonitoredItems();

            // Commit: only the items the server actually accepted.
            Set<String> keys = commit(staged);
            LOGGER.info("[{}] subscription '{}': {}/{} monitored item(s) live",
                    config.getId(), spec.getId(), keys.size(), requested);
            return new Created(subscription, keys, requested, keys.size());
        } catch (Exception e) {
            LOGGER.error("[{}] failed to create subscription '{}': {}", config.getId(), spec.getId(), e.toString());
            return new Created(null, Set.of(), requested, 0);
        }
    }

    /** Commit the confirmed staged items into the inventory, returning the keys committed. */
    private Set<String> commit(Map<OpcUaMonitoredItem, ResolvedSignal> staged) {
        Set<String> keys = new LinkedHashSet<>();
        staged.forEach((item, resolved) -> {
            if (!isCreated(item)) {
                LOGGER.warn("[{}] monitored item for {} was not created ({}); not counted as subscribed",
                        config.getId(), resolved.signalId(),
                        item.getCreateResult().map(StatusCode::toString).orElse("no result"));
                return;
            }
            String key = resolved.signalId();
            resolvedSignals.put(key, resolved);
            keys.add(key);
        });
        return keys;
    }

    /** Whether the server confirmed this monitored item's creation. */
    private static boolean isCreated(OpcUaMonitoredItem item) {
        return item.getCreateResult().map(StatusCode::isGood).orElse(false);
    }

    /**
     * Re-establish a subscription after a transfer failure. The old subscription's committed inventory
     * is retracted first, so the gauge reflects the rebuild rather than accreting stale entries.
     */
    public void reestablish(OpcUaSubscription subscription) {
        SubscriptionSpec spec = subscriptions.remove(subscription);
        if (spec == null) {
            return;
        }
        retract(subscription);
        counters.recordSubscriptionRecreate();
        Created created = create(spec);
        if (created.subscription() != null) {
            subscriptions.put(created.subscription(), spec);
            committed.put(created.subscription(), created.keys());
        }
        updateSubscriptionShape();
    }

    /** Drop a subscription's committed inventory entries. */
    private void retract(OpcUaSubscription subscription) {
        Set<String> keys = committed.remove(subscription);
        if (keys != null) {
            keys.forEach(resolvedSignals::remove);
        }
    }

    /**
     * Delete every server-side subscription, best-effort. Called on shutdown so the adapter does not
     * leave monitored items running on the server after it exits.
     */
    public void closeAll() {
        for (OpcUaSubscription subscription : List.copyOf(subscriptions.keySet())) {
            subscriptions.remove(subscription);
            retract(subscription);
            try {
                subscription.delete();
            } catch (Exception e) {
                LOGGER.debug("[{}] deleting subscription failed: {}", config.getId(), e.toString());
            }
        }
        updateSubscriptionShape();
    }

    private void updateSubscriptionShape() {
        counters.setSubscriptionShape(subscriptions.size(), resolvedSignals.size());
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

    /** One subscription build: the subscription (null on failure), its committed keys, and counts. */
    private record Created(OpcUaSubscription subscription, Set<String> keys, int requested, int live) {
    }
}
