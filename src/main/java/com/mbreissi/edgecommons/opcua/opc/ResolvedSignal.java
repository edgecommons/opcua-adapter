package com.mbreissi.edgecommons.opcua.opc;

import com.mbreissi.edgecommons.opcua.opc.config.SignalSpec;
import org.eclipse.milo.opcua.stack.core.types.builtin.NodeId;

/**
 * A signal that a subscription has bound to: the actual {@link NodeId} it resolved to (carrying the
 * current namespace index) plus the {@link SignalSpec} that selected it. Used for the {@code
 * subscriptions} control query.
 */
public record ResolvedSignal(NodeId nodeId, SignalSpec spec) {
}
