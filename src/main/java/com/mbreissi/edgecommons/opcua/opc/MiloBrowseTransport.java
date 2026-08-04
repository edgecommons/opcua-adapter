package com.mbreissi.edgecommons.opcua.opc;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.milo.opcua.sdk.client.OpcUaClient;
import org.eclipse.milo.opcua.sdk.client.nodes.UaNode;
import org.eclipse.milo.opcua.sdk.client.nodes.UaVariableNode;
import org.eclipse.milo.opcua.stack.core.NamespaceTable;
import org.eclipse.milo.opcua.stack.core.NodeIds;
import org.eclipse.milo.opcua.stack.core.types.builtin.ByteString;
import org.eclipse.milo.opcua.stack.core.types.builtin.NodeId;
import org.eclipse.milo.opcua.stack.core.types.enumerated.BrowseDirection;
import org.eclipse.milo.opcua.stack.core.types.enumerated.BrowseResultMask;
import org.eclipse.milo.opcua.stack.core.types.enumerated.NodeClass;
import org.eclipse.milo.opcua.stack.core.types.structured.BrowseDescription;
import org.eclipse.milo.opcua.stack.core.types.structured.BrowseNextResponse;
import org.eclipse.milo.opcua.stack.core.types.structured.BrowseResult;

import java.util.List;

import static org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.Unsigned.uint;

/**
 * The live {@link BrowseTransport} over an Eclipse Milo {@code OpcUaClient} — pure delegation, no
 * traversal logic (that lives in {@link AddressSpaceBrowser}, inside the coverage gate).
 *
 * <p>Browses {@code HierarchicalReferences} rather than the {@code References} supertype: the
 * non-hierarchical references (type definitions, model links) are not containment and following them
 * turns discovery into an unbounded walk of the whole type system.
 */
public final class MiloBrowseTransport implements BrowseTransport {

    private static final Logger LOGGER = LogManager.getLogger(MiloBrowseTransport.class);

    private final OpcUaClient client;

    public MiloBrowseTransport(OpcUaClient client) {
        this.client = client;
    }

    @Override
    public BrowseResult browseNode(NodeId nodeId) throws Exception {
        BrowseDescription browse = new BrowseDescription(
                nodeId,
                BrowseDirection.Forward,
                NodeIds.HierarchicalReferences,
                true,
                uint(NodeClass.Object.getValue() | NodeClass.Variable.getValue()),
                uint(BrowseResultMask.All.getValue()));
        return client.browse(browse);
    }

    @Override
    public BrowseResult continueBrowse(ByteString continuationPoint) throws Exception {
        BrowseNextResponse response = client.browseNext(false, List.of(continuationPoint));
        BrowseResult[] results = response.getResults();
        if (results == null || results.length == 0) {
            return null;
        }
        return results[0];
    }

    @Override
    public void releaseContinuationPoint(ByteString continuationPoint) {
        try {
            client.browseNext(true, List.of(continuationPoint));
        } catch (Exception e) {
            LOGGER.trace("releasing continuation point failed: {}", e.toString());
        }
    }

    @Override
    public UaVariableNode resolveVariable(NodeId nodeId) {
        try {
            UaNode node = client.getAddressSpace().getNode(nodeId);
            return node instanceof UaVariableNode variable ? variable : null;
        } catch (Exception e) {
            LOGGER.trace("skipping node {}: {}", nodeId, e.getMessage());
            return null;
        }
    }

    @Override
    public NamespaceTable namespaceTable() {
        return client.getNamespaceTable();
    }
}
