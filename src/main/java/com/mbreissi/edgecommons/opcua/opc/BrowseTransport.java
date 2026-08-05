package com.mbreissi.edgecommons.opcua.opc;

import org.eclipse.milo.opcua.sdk.client.nodes.UaVariableNode;
import org.eclipse.milo.opcua.stack.core.NamespaceTable;
import org.eclipse.milo.opcua.stack.core.types.builtin.ByteString;
import org.eclipse.milo.opcua.stack.core.types.builtin.NodeId;
import org.eclipse.milo.opcua.stack.core.types.structured.BrowseResult;

/**
 * The four OPC UA operations an address-space traversal needs, behind an interface so the traversal
 * logic — budgets, cycle detection, continuation-point handling, partial-result accounting — is
 * exercised by the in-process unit suite instead of hiding behind the live-driver coverage exclusion.
 *
 * <p>{@link MiloBrowseTransport} is the live implementation over an {@code OpcUaClient}; it is pure
 * delegation and stays outside the gate.
 */
public interface BrowseTransport {

    /** Browse one node's forward hierarchical references. Never returns null. */
    BrowseResult browseNode(NodeId nodeId) throws Exception;

    /** Fetch the next page for a continuation point ({@code BrowseNext}, release = false). */
    BrowseResult continueBrowse(ByteString continuationPoint) throws Exception;

    /**
     * Release a continuation point the traversal will not consume ({@code BrowseNext}, release = true).
     * Best-effort: a server that has already discarded it is not an error worth propagating.
     */
    void releaseContinuationPoint(ByteString continuationPoint);

    /** Resolve a variable node, or {@code null} when it cannot be read. */
    UaVariableNode resolveVariable(NodeId nodeId);

    /** The server's current namespace table, for resolving reference targets to local node ids. */
    NamespaceTable namespaceTable();
}
