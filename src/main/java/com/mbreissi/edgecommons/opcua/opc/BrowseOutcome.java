package com.mbreissi.edgecommons.opcua.opc;

import org.eclipse.milo.opcua.sdk.client.nodes.UaVariableNode;
import org.eclipse.milo.opcua.stack.core.types.builtin.NodeId;

import java.util.Map;

/**
 * The result of an address-space traversal: the variable nodes found, plus whether the traversal
 * actually saw the whole address space.
 *
 * <p>A bare {@code Map} cannot distinguish "this server has three variables" from "the browse failed
 * after three variables", and that ambiguity is what let {@code sb/rescan} erase a populated cache and
 * report success. {@link #complete()} is false when any browse returned a bad status, threw, or was cut
 * short by a budget — callers that replace state (the rescan cache swap) must refuse on an incomplete
 * outcome.
 *
 * @param nodes     the variable nodes discovered, keyed by node id
 * @param complete  true only when every browse succeeded and no budget was hit
 * @param errors    the number of browse calls that failed or returned a bad status
 * @param truncated true when a node or depth budget stopped the traversal early
 */
public record BrowseOutcome(Map<NodeId, UaVariableNode> nodes, boolean complete, int errors, boolean truncated) {

    /** A complete traversal with no errors. */
    public static BrowseOutcome complete(Map<NodeId, UaVariableNode> nodes) {
        return new BrowseOutcome(nodes, true, 0, false);
    }

    /** The number of variable nodes discovered. */
    public int size() {
        return nodes.size();
    }

    /** A human-readable reason an incomplete outcome is not safe to swap in, or {@code null}. */
    public String incompleteReason() {
        if (complete) {
            return null;
        }
        if (truncated) {
            return "browse hit its node/depth budget after " + nodes.size() + " variable node(s)";
        }
        return "browse failed (" + errors + " error(s)) after " + nodes.size() + " variable node(s)";
    }
}
