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
import org.eclipse.milo.opcua.stack.core.types.enumerated.DataChangeTrigger;
import org.eclipse.milo.opcua.stack.core.types.enumerated.DeadbandType;
import org.eclipse.milo.opcua.stack.core.types.structured.DataChangeFilter;

import java.util.ArrayList;
import java.util.HashMap;
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
 */
public class SubscriptionManager {

    private static final Logger LOGGER = LogManager.getLogger(SubscriptionManager.class);

    private final OpcUaClient client;
    private final ServerConfiguration config;
    private final Map<NodeId, UaVariableNode> allNodes;
    private final TagUpdatePublisher publisher;
    private final ClientMetrics counters;

    private final Map<OpcUaSubscription, SubscriptionSpec> subscriptions = new HashMap<>();
    private final Map<String, TagSpec> resolvedTags = new ConcurrentHashMap<>();

    public SubscriptionManager(OpcUaClient client, ServerConfiguration config,
                               Map<NodeId, UaVariableNode> allNodes,
                               TagUpdatePublisher publisher, ClientMetrics counters) {
        this.client = client;
        this.config = config;
        this.allNodes = allNodes;
        this.publisher = publisher;
        this.counters = counters;
    }

    public Map<String, TagSpec> getResolvedTags() {
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
                resolvedTags.put(node.getNodeId().getIdentifier().toString(), tagSpec);
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
        Map<UaVariableNode, TagSpec> matching = new LinkedHashMap<>();
        for (UaVariableNode node : allNodes.values()) {
            TagSpec include = matchTag(node, spec.getIncludeSpec().getTagSpecs(), true);
            if (include == null) {
                continue;
            }
            if (spec.getExcludeSpec() != null && matchTag(node, spec.getExcludeSpec().getTagSpecs(), false) != null) {
                continue;
            }
            matching.put(node, include);
        }
        return matching;
    }

    /** Match a node against tag specs by namespace + regex over nodeId / browseName / displayName. */
    private TagSpec matchTag(UaVariableNode node, List<TagSpec> specs, boolean matchNames) {
        String idStr = node.getNodeId().getIdentifier().toString();
        String browseName = node.getBrowseName() != null && node.getBrowseName().getName() != null
                ? node.getBrowseName().getName() : "";
        String displayName = node.getDisplayName() != null && node.getDisplayName().getText() != null
                ? node.getDisplayName().getText() : "";
        for (TagSpec spec : specs) {
            if (!node.getNodeId().getNamespaceIndex().equals(ushort(spec.getNamespace()))) {
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
