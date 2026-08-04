package com.mbreissi.edgecommons.opcua.opc;

import com.mbreissi.edgecommons.opcua.opc.config.AdapterLimits;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.milo.opcua.sdk.client.nodes.UaVariableNode;
import org.eclipse.milo.opcua.stack.core.NodeIds;
import org.eclipse.milo.opcua.stack.core.types.builtin.ByteString;
import org.eclipse.milo.opcua.stack.core.types.builtin.NodeId;
import org.eclipse.milo.opcua.stack.core.types.builtin.StatusCode;
import org.eclipse.milo.opcua.stack.core.types.enumerated.NodeClass;
import org.eclipse.milo.opcua.stack.core.types.structured.BrowseResult;
import org.eclipse.milo.opcua.stack.core.types.structured.ReferenceDescription;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Traverses an OPC UA server's address space and collects its variable nodes.
 *
 * <p>The traversal is <b>iterative</b> (an explicit work queue, so a deep or cyclic address space
 * cannot overflow the stack), <b>cycle-safe</b> (a visited set — OPC UA permits an object graph with
 * cycles), <b>bounded</b> (node and depth budgets from {@link AdapterLimits}), and <b>complete</b>
 * (continuation points are consumed via {@code BrowseNext} until exhausted, and released when the
 * traversal stops early, as OPC UA Part 4 §5.9.3 requires).
 *
 * <p>Every browse status is checked, and any failure or budget stop is reported through
 * {@link BrowseOutcome#complete()} rather than silently yielding a partial map — a partial map that
 * looks like a complete one is what allows a cache refresh to erase a healthy address space.
 */
public class AddressSpaceBrowser {

    private static final Logger LOGGER = LogManager.getLogger(AddressSpaceBrowser.class);

    private final BrowseTransport transport;
    private final String instanceId;
    private final AdapterLimits limits;

    public AddressSpaceBrowser(BrowseTransport transport, String instanceId, AdapterLimits limits) {
        this.transport = transport;
        this.instanceId = instanceId;
        this.limits = limits != null ? limits : AdapterLimits.defaults();
    }

    /** Traverse from the root folder. */
    public BrowseOutcome browseAll() {
        return browseFrom(NodeIds.RootFolder);
    }

    /** Traverse from an arbitrary root. */
    public BrowseOutcome browseFrom(NodeId root) {
        Map<NodeId, UaVariableNode> nodes = new HashMap<>();
        Set<NodeId> visited = new HashSet<>();
        Deque<Entry> queue = new ArrayDeque<>();
        int errors = 0;
        boolean truncated = false;

        LOGGER.info("[{}] browsing address space...", instanceId);
        queue.add(new Entry(root, 0));
        visited.add(root);

        while (!queue.isEmpty()) {
            if (nodes.size() >= limits.getBrowseMaxNodes()) {
                truncated = true;
                LOGGER.warn("[{}] browse stopped at the {}-node budget", instanceId, limits.getBrowseMaxNodes());
                break;
            }
            Entry entry = queue.poll();
            Visit visit = visitNode(entry, nodes, visited, queue);
            errors += visit.errors();
            truncated |= visit.truncated();
        }

        boolean complete = errors == 0 && !truncated;
        LOGGER.info("[{}] browse {}: {} variable node(s){}",
                instanceId, complete ? "complete" : "INCOMPLETE", nodes.size(),
                complete ? "" : " (" + errors + " error(s)" + (truncated ? ", budget reached" : "") + ")");
        return new BrowseOutcome(nodes, complete, errors, truncated);
    }

    /**
     * Browse one node, following every continuation point, and enqueue its unvisited object children.
     */
    private Visit visitNode(Entry entry, Map<NodeId, UaVariableNode> nodes, Set<NodeId> visited, Deque<Entry> queue) {
        int errors = 0;
        boolean truncated = false;
        try {
            BrowseResult result = transport.browseNode(entry.nodeId());
            while (result != null) {
                if (isBad(result.getStatusCode())) {
                    LOGGER.warn("[{}] browse of {} returned {}", instanceId, entry.nodeId(), result.getStatusCode());
                    errors++;
                    releaseIfPresent(result.getContinuationPoint());
                    break;
                }
                truncated |= collect(result.getReferences(), entry, nodes, visited, queue);

                ByteString continuationPoint = result.getContinuationPoint();
                if (continuationPoint == null || continuationPoint.isNullOrEmpty()) {
                    break;
                }
                if (truncated || nodes.size() >= limits.getBrowseMaxNodes()) {
                    // Not going to consume this page: hand the point back so the server can free it.
                    transport.releaseContinuationPoint(continuationPoint);
                    truncated = true;
                    break;
                }
                result = transport.continueBrowse(continuationPoint);
            }
        } catch (Exception e) {
            LOGGER.warn("[{}] browse of {} failed: {}", instanceId, entry.nodeId(), e.getMessage());
            errors++;
        }
        return new Visit(errors, truncated);
    }

    /** Record variables and enqueue objects; returns true when the depth budget stopped a descent. */
    private boolean collect(ReferenceDescription[] references, Entry entry,
                            Map<NodeId, UaVariableNode> nodes, Set<NodeId> visited, Deque<Entry> queue) {
        if (references == null) {
            return false;
        }
        boolean truncated = false;
        for (ReferenceDescription rd : references) {
            if (nodes.size() >= limits.getBrowseMaxNodes()) {
                return true;
            }
            NodeId targetId = rd.getNodeId() != null
                    ? rd.getNodeId().toNodeId(transport.namespaceTable()).orElse(null)
                    : null;
            if (targetId == null) {
                // An ExpandedNodeId on a remote server, or one needing the namespace table: skip it.
                continue;
            }
            if (rd.getNodeClass() == NodeClass.Variable) {
                if (visited.add(targetId)) {
                    UaVariableNode variable = transport.resolveVariable(targetId);
                    if (variable != null) {
                        nodes.put(targetId, variable);
                    }
                }
            } else if (rd.getNodeClass() == NodeClass.Object) {
                if (entry.depth() + 1 > limits.getBrowseMaxDepth()) {
                    truncated = true;
                    continue;
                }
                if (visited.add(targetId)) {
                    queue.add(new Entry(targetId, entry.depth() + 1));
                }
            }
        }
        return truncated;
    }

    private void releaseIfPresent(ByteString continuationPoint) {
        if (continuationPoint != null && !continuationPoint.isNullOrEmpty()) {
            transport.releaseContinuationPoint(continuationPoint);
        }
    }

    private static boolean isBad(StatusCode status) {
        return status != null && status.isBad();
    }

    /** One queued node plus the depth it was reached at. */
    private record Entry(NodeId nodeId, int depth) {
    }

    /** The per-node accounting a visit contributes to the outcome. */
    private record Visit(int errors, boolean truncated) {
    }
}
