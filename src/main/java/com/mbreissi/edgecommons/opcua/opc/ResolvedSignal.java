package com.mbreissi.edgecommons.opcua.opc;

import com.mbreissi.edgecommons.opcua.opc.config.SignalSpec;
import org.eclipse.milo.opcua.stack.core.types.builtin.NodeId;

/**
 * One subscribed signal: the live {@link NodeId} it is monitored by, the matcher that selected it, and
 * its {@link CanonicalSignalId} — the stable identity it is published, inventoried, and authorized
 * under.
 *
 * <p>The node id is session-scoped (its namespace index is only meaningful while this session lasts);
 * the canonical id is not, which is why the inventory and staleness tracker key on the latter.
 */
public record ResolvedSignal(NodeId nodeId, SignalSpec spec, CanonicalSignalId canonicalId) {

    /** The canonical id in its wire form — the published {@code signal.id}. */
    public String signalId() {
        return canonicalId.toString();
    }
}
