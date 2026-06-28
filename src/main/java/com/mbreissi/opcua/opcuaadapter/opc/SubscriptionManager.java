package com.mbreissi.opcua.opcuaadapter.opc;

import com.mbreissi.opcua.opcuaadapter.opc.config.DeadbandSpec;
import com.mbreissi.opcua.opcuaadapter.opc.config.ServerConfiguration;
import com.mbreissi.opcua.opcuaadapter.opc.config.SubscriptionSpec;
import com.mbreissi.opcua.opcuaadapter.opc.config.TagSpec;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.milo.opcua.sdk.client.OpcUaClient;
import org.eclipse.milo.opcua.sdk.client.nodes.UaVariableNode;
import org.eclipse.milo.opcua.sdk.client.subscriptions.OpcUaMonitoredItem;
import org.eclipse.milo.opcua.sdk.client.subscriptions.OpcUaSubscription;
import org.eclipse.milo.opcua.stack.core.types.builtin.NodeId;
import org.eclipse.milo.opcua.stack.core.types.builtin.StatusCode;
import org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.UShort;
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
import static org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.Unsigned.ushort;

/**
 * Creates and maintains the OPC UA subscriptions for one device: matches address-space nodes to the
 * configured include/exclude tag specs, creates monitored items (with deadband), and routes value
 * changes to the {@link TagUpdatePublisher}. Re-establishes a subscription on transfer failure.
 *
 * <p>A tag spec selects its namespace by URI (resolved to the server's current index from the live
 * namespace table) or by a literal index. Resolution happens here, each time subscriptions are built,
 * so a server that renumbers between connections is picked up automatically.
 */
public class SubscriptionManager {

    private static final Logger LOGGER = LogManager.getLogger(SubscriptionManager.class);

    private final OpcUaClient client;
    private final ServerConfiguration config;
    private final Map<NodeId, UaVariableNode> allNodes;
    private final TagUpdatePublisher publisher;
    private final ClientMetrics counters;

    private final Map<OpcUaSubscription, SubscriptionSpec> subscriptions = new HashMap<>();
    private final Map<String, ResolvedTag> resolvedTags = new ConcurrentHashMap<>();

    public SubscriptionManager(OpcUaClient client, ServerConfiguration config,
                               Map<NodeId, UaVariableNode> allNodes,
                               TagUpdatePublisher publisher, ClientMetrics counters) {
        this.client = client;
        this.config = config;
        this.allNodes = allNodes;
        this.publisher = publisher;
        this.counters = counters;
    }

    public Map<String, ResolvedTag> getResolvedTags() {
        return resolvedTags;
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

            Map<UaVariableNode, TagSpec> matching = filter(spec);
            List<OpcUaMonitoredItem> items = new ArrayList<>();
            matching.forEach((node, tagSpec) -> {
                OpcUaMonitoredItem item = OpcUaMonitoredItem.newDataItem(node.getNodeId());
                item.setSamplingInterval(tagSpec.getSamplingRateMs());
                item.setQueueSize(uint(tagSpec.getQueueSize()));
                DeadbandSpec deadband = tagSpec.getDeadband();
                if (deadband.getType() != DeadbandType.None) {
                    item.setFilter(new DataChangeFilter(
                            DataChangeTrigger.StatusValue,
                            uint(deadband.getType().getValue()),
                            deadband.getValue()));
                }
                item.setDataValueListener((it, value) -> {
                    counters.incrementReadMetrics();
                    publisher.offer(node, tagSpec, value);
                });
                items.add(item);
                resolvedTags.put(node.getNodeId().getIdentifier().toString(), new ResolvedTag(node.getNodeId(), tagSpec));
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

    private Map<UaVariableNode, TagSpec> filter(SubscriptionSpec spec) {
        Map<TagSpec, Integer> includeNs = resolveNamespaces(spec.getIncludeSpec().getTagSpecs());
        Map<TagSpec, Integer> excludeNs = spec.getExcludeSpec() != null
                ? resolveNamespaces(spec.getExcludeSpec().getTagSpecs())
                : new IdentityHashMap<>();

        Map<UaVariableNode, TagSpec> matching = new LinkedHashMap<>();
        for (UaVariableNode node : allNodes.values()) {
            TagSpec include = matchTag(node, spec.getIncludeSpec().getTagSpecs(), includeNs, true);
            if (include == null) {
                continue;
            }
            if (spec.getExcludeSpec() != null
                    && matchTag(node, spec.getExcludeSpec().getTagSpecs(), excludeNs, false) != null) {
                continue;
            }
            matching.put(node, include);
        }
        return matching;
    }

    /**
     * Resolve each tag spec's namespace to an effective index: a {@code namespaceUri} is looked up in
     * the server's current namespace table; otherwise the literal {@code namespace} is used. An
     * unresolved URI maps to {@code -1} (the matcher is skipped, with a warning).
     */
    private Map<TagSpec, Integer> resolveNamespaces(List<TagSpec> specs) {
        Map<TagSpec, Integer> result = new IdentityHashMap<>();
        for (TagSpec spec : specs) {
            int ns;
            if (spec.getNamespaceUri() != null) {
                UShort idx = client.getNamespaceTable().getIndex(spec.getNamespaceUri());
                ns = idx != null ? idx.intValue() : -1;
                if (ns < 0) {
                    LOGGER.warn("[{}] namespaceUri '{}' is not in the server's namespace table; matcher skipped",
                            config.getId(), spec.getNamespaceUri());
                }
            } else {
                ns = spec.getNamespace();
            }
            result.put(spec, ns);
        }
        return result;
    }

    /** Match a node against tag specs by resolved namespace index + regex over nodeId / browseName / displayName. */
    private TagSpec matchTag(UaVariableNode node, List<TagSpec> specs, Map<TagSpec, Integer> nsBySpec, boolean matchNames) {
        String idStr = node.getNodeId().getIdentifier().toString();
        String browseName = node.getBrowseName() != null && node.getBrowseName().getName() != null
                ? node.getBrowseName().getName() : "";
        String displayName = node.getDisplayName() != null && node.getDisplayName().getText() != null
                ? node.getDisplayName().getText() : "";
        for (TagSpec spec : specs) {
            int ns = nsBySpec.getOrDefault(spec, -1);
            if (ns < 0 || !node.getNodeId().getNamespaceIndex().equals(ushort(ns))) {
                continue;
            }
            String regex = spec.getMatch();
            if (idStr.matches(regex)
                    || (matchNames && (browseName.matches(regex) || displayName.matches(regex)))) {
                return spec;
            }
        }
        return null;
    }
}
